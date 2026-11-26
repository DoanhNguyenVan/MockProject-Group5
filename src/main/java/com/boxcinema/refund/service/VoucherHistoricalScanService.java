package com.boxcinema.refund.service;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Programmatic historical scan service verifying one-voucher-per-order integrity,
 * per-item allocation sum invariants, settled-payment reconciliation, and voucher
 * restoration non-duplication across the database.
 */
@Service
@Transactional(readOnly = true)
public class VoucherHistoricalScanService {

    private static final Set<String> SETTLED_BOOKING_STATUSES = Set.of(
            "CONFIRMED", "PAID", "PAID_SEAT_CONFLICT", "PARTIALLY_REFUNDED", "REFUNDED"
    );

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final OrderVoucherRedemptionRepository redemptionRepository;
    private final OrderVoucherAllocationRepository allocationRepository;
    private final VoucherRecoveryRepository voucherRecoveryRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public VoucherHistoricalScanService(
            BookingRepository bookingRepository,
            PaymentRepository paymentRepository,
            OrderVoucherRedemptionRepository redemptionRepository,
            OrderVoucherAllocationRepository allocationRepository,
            VoucherRecoveryRepository voucherRecoveryRepository,
            RefundRequestRepository refundRequestRepository,
            @Autowired(required = false) JdbcTemplate jdbcTemplate) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.redemptionRepository = redemptionRepository;
        this.allocationRepository = allocationRepository;
        this.voucherRecoveryRepository = voucherRecoveryRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ViolationRecord(
            String violationCode,
            Long bookingId,
            String bookingCode,
            String detail
    ) {}

    public record HistoricalVoucherScanReport(
            LocalDateTime scannedAt,
            int totalBookingsScanned,
            int bookingsWithVoucherCount,
            int redemptionRowsCount,
            int duplicateVoucherViolationsCount,
            int allocationSumMismatchCount,
            int unreconciledPaymentMismatchCount,
            int doubleRestorationViolationsCount,
            int reconciledOrdersCount,
            int unresolvedOrdersCount,
            boolean clean,
            List<ViolationRecord> violations
    ) {}

    public HistoricalVoucherScanReport runFullScan() {
        List<Booking> bookings = bookingRepository.findAll();
        List<OrderVoucherRedemption> allRedemptions = redemptionRepository.findAll();
        List<ViolationRecord> violations = new ArrayList<>();

        int bookingsWithVoucher = 0;
        int duplicateVoucherViolations = 0;
        int allocationMismatches = 0;
        int paymentMismatches = 0;
        int doubleRestorations = 0;
        int reconciledOrders = 0;
        int unresolvedOrders = 0;

        // 1. Check for duplicate redemptions per booking_id
        Map<Long, List<OrderVoucherRedemption>> redemptionsByBooking = new LinkedHashMap<>();
        for (OrderVoucherRedemption r : allRedemptions) {
            Long bId = r.getBooking() != null ? r.getBooking().getBookingId() : null;
            if (bId != null) {
                redemptionsByBooking.computeIfAbsent(bId, k -> new ArrayList<>()).add(r);
            }
        }
        for (Map.Entry<Long, List<OrderVoucherRedemption>> entry : redemptionsByBooking.entrySet()) {
            if (entry.getValue().size() > 1) {
                duplicateVoucherViolations++;
                Booking b = entry.getValue().get(0).getBooking();
                violations.add(new ViolationRecord(
                        "DUPLICATE_VOUCHER_ON_ORDER",
                        entry.getKey(),
                        b != null ? b.getBookingCode() : null,
                        "Order has " + entry.getValue().size() + " voucher redemption rows."));
            }
        }

        // Also check via raw SQL if JdbcTemplate is available
        if (jdbcTemplate != null) {
            try {
                List<Map<String, Object>> sqlDups = jdbcTemplate.queryForList(
                        "SELECT booking_id, COUNT(*) AS cnt FROM order_voucher_redemptions GROUP BY booking_id HAVING COUNT(*) > 1");
                for (Map<String, Object> row : sqlDups) {
                    Long bId = ((Number) row.get("booking_id")).longValue();
                    boolean alreadyRecorded = violations.stream()
                            .anyMatch(v -> "DUPLICATE_VOUCHER_ON_ORDER".equals(v.violationCode()) && Objects.equals(v.bookingId(), bId));
                    if (!alreadyRecorded) {
                        duplicateVoucherViolations++;
                        violations.add(new ViolationRecord(
                                "DUPLICATE_VOUCHER_ON_ORDER",
                                bId,
                                null,
                                "SQL group check found multiple voucher redemptions on booking_id=" + bId));
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 2. Scan each booking for voucher allocation invariant, settled-payment reconciliation & voucher recovery caps
        for (Booking b : bookings) {
            Long bId = b.getBookingId();
            boolean orderHasIssue = false;

            long grossSubVnd = PurchaseSnapshotService.toVndLong(b.getSubtotal());
            long discountVnd = PurchaseSnapshotService.toVndLong(b.getDiscountAmount());
            long feeVnd = PurchaseSnapshotService.toVndLong(b.getServiceFee());
            long totalVnd = PurchaseSnapshotService.toVndLong(b.getTotalAmount());

            String status = b.getBookingStatus() != null ? b.getBookingStatus().trim().toUpperCase(Locale.ROOT) : "";
            boolean isSettledOrder = SETTLED_BOOKING_STATUSES.contains(status);

            if (b.getPromotion() != null || discountVnd > 0) {
                bookingsWithVoucher++;
                List<OrderVoucherRedemption> redemptions = redemptionsByBooking.getOrDefault(bId, List.of());
                if (!redemptions.isEmpty()) {
                    OrderVoucherRedemption ovr = redemptions.get(0);
                    if (ovr.getActualDiscount() < 0 || ovr.getActualDiscount() > ovr.getEligibleAmount()) {
                        allocationMismatches++;
                        orderHasIssue = true;
                        violations.add(new ViolationRecord(
                                "INVALID_VOUCHER_DISCOUNT_BOUNDS",
                                bId,
                                b.getBookingCode(),
                                "actual_discount=" + ovr.getActualDiscount() + " exceeds eligible_amount=" + ovr.getEligibleAmount()));
                    }
                    List<OrderVoucherAllocation> allocs = allocationRepository.findByRedemption_IdOrderByIdAsc(ovr.getId());
                    if (!allocs.isEmpty()) {
                        long sumAllocDiscount = 0L;
                        for (OrderVoucherAllocation a : allocs) {
                            sumAllocDiscount += a.getAllocatedDiscount();
                            if (a.getNetPaidAmount() != (a.getGrossAmount() - a.getAllocatedDiscount())) {
                                allocationMismatches++;
                                orderHasIssue = true;
                                violations.add(new ViolationRecord(
                                        "ITEM_NET_PAID_INVARIANT_MISMATCH",
                                        bId,
                                        b.getBookingCode(),
                                        "Item " + a.getItemRefKey() + " net_paid=" + a.getNetPaidAmount()
                                                + " != gross(" + a.getGrossAmount() + ") - discount(" + a.getAllocatedDiscount() + ")"));
                            }
                        }
                        boolean legacyUnallocatedPendingHold = !isSettledOrder && sumAllocDiscount == 0L;
                        if (!legacyUnallocatedPendingHold && sumAllocDiscount != ovr.getActualDiscount()) {
                            allocationMismatches++;
                            orderHasIssue = true;
                            violations.add(new ViolationRecord(
                                    "ALLOCATION_SUM_MISMATCH",
                                    bId,
                                    b.getBookingCode(),
                                    "sum(allocated_discount)=" + sumAllocDiscount + " != actual_discount=" + ovr.getActualDiscount()));
                        }
                    }
                }
            }

            // 3. Settled payment reconciliation check
            if (isSettledOrder) {
                Optional<Payment> paidOpt = paymentRepository
                        .findFirstByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(bId, "PAID");
                if (paidOpt.isEmpty() || paidOpt.get().getAmount() == null) {
                    paymentMismatches++;
                    orderHasIssue = true;
                    violations.add(new ViolationRecord(
                            "MISSING_SETTLED_PAYMENT",
                            bId,
                            b.getBookingCode(),
                            "Booking in settled status " + status + " has no PAID payment record."));
                } else {
                    long paidVnd = PurchaseSnapshotService.toVndLong(paidOpt.get().getAmount());
                    long expectedNetTotal = Math.max(0L, grossSubVnd - discountVnd + feeVnd);
                    if (totalVnd != paidVnd || (grossSubVnd > 0 && expectedNetTotal != paidVnd) || b.isSnapshotReviewRequired()) {
                        paymentMismatches++;
                        orderHasIssue = true;
                        violations.add(new ViolationRecord(
                                "SETTLED_PAYMENT_AMOUNT_MISMATCH",
                                bId,
                                b.getBookingCode(),
                                "Settled payment amount (" + paidVnd + ") does not reconcile with booking total (" + totalVnd + ")."));
                    }

                    // Check cumulative cash refunds against settled payment amount
                    long cumulativeApprovedCash = 0L;
                    for (RefundRequest rr : refundRequestRepository.findByBooking_BookingIdOrderByCreatedAtDesc(bId)) {
                        String rStatus = rr.getRequestStatus() != null ? rr.getRequestStatus().toUpperCase(Locale.ROOT) : "";
                        if (!Set.of("REJECTED", "PAYOUT_REJECTED", "CANCELLED", "INELIGIBLE").contains(rStatus)
                                && rr.getApprovedCashTotal() != null) {
                            cumulativeApprovedCash += PurchaseSnapshotService.toVndLong(rr.getApprovedCashTotal());
                        }
                    }
                    if (cumulativeApprovedCash > paidVnd) {
                        paymentMismatches++;
                        orderHasIssue = true;
                        violations.add(new ViolationRecord(
                                "CUMULATIVE_REFUND_EXCEEDS_SETTLED_PAYMENT",
                                bId,
                                b.getBookingCode(),
                                "Cumulative approved cash refund (" + cumulativeApprovedCash + ") exceeds settled payment (" + paidVnd + ")."));
                    }
                }
            }

            // 4. Voucher recovery double-restoration check
            List<VoucherRecovery> recoveries = voucherRecoveryRepository.findByBooking_BookingIdOrderByIdAsc(bId);
            if (!recoveries.isEmpty()) {
                long totalRecoveredVnd = 0L;
                Set<Long> seenActiveRequests = new HashSet<>();
                for (VoucherRecovery vr : recoveries) {
                    if ("GENERATED".equalsIgnoreCase(vr.getStatus())) {
                        totalRecoveredVnd += PurchaseSnapshotService.toVndLong(vr.getRecoveryAmount());
                        Long rrId = vr.getRefundRequest() != null ? vr.getRefundRequest().getId() : null;
                        if (rrId != null && !seenActiveRequests.add(rrId)) {
                            doubleRestorations++;
                            orderHasIssue = true;
                            violations.add(new ViolationRecord(
                                    "DUPLICATE_VOUCHER_RESTORATION_FOR_REQUEST",
                                    bId,
                                    b.getBookingCode(),
                                    "Multiple GENERATED recovery vouchers found for refund_request_id=" + rrId));
                        }
                    }
                }
                if (totalRecoveredVnd > discountVnd) {
                    doubleRestorations++;
                    orderHasIssue = true;
                    violations.add(new ViolationRecord(
                            "RECOVERY_EXCEEDS_ORIGINAL_VOUCHER_DISCOUNT",
                            bId,
                            b.getBookingCode(),
                            "Cumulative recovered voucher amount (" + totalRecoveredVnd
                                    + ") exceeds original booking discount (" + discountVnd + ")."));
                }
            }

            if (orderHasIssue) {
                unresolvedOrders++;
            } else {
                reconciledOrders++;
            }
        }

        boolean clean = violations.isEmpty();
        return new HistoricalVoucherScanReport(
                LocalDateTime.now(),
                bookings.size(),
                bookingsWithVoucher,
                allRedemptions.size(),
                duplicateVoucherViolations,
                allocationMismatches,
                paymentMismatches,
                doubleRestorations,
                reconciledOrders,
                unresolvedOrders,
                clean,
                List.copyOf(violations)
        );
    }
}
