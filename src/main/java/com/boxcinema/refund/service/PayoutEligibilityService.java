package com.boxcinema.refund.service;

import com.boxcinema.common.entity.Booking;
import com.boxcinema.common.entity.PayOsPayoutTransaction;
import com.boxcinema.common.entity.Payment;
import com.boxcinema.common.entity.RefundRequest;
import com.boxcinema.common.entity.RefundRequestItem;
import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.PayOsPayoutTransactionRepository;
import com.boxcinema.common.repository.PaymentRepository;
import com.boxcinema.common.repository.RefundRequestItemRepository;
import com.boxcinema.common.repository.RefundRequestRepository;
import com.boxcinema.common.service.BankDataEncryptionService;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Centralized 13-point screening & validation gate determining whether a RefundRequest
 * is eligible to enter or be dispatched from the Admin Payout Approval queue.
 */
@Service
public class PayoutEligibilityService {

    public static final Set<String> PAYOUT_ELIGIBLE_BOOKING_STATUSES = Set.of(
            "CONFIRMED", "PAID", "PAID_SEAT_CONFLICT", "PARTIALLY_REFUNDED", "PAYMENT_RECONCILED_FOR_REFUND"
    );
    public static final Set<String> TERMINAL_REJECT_CANCEL_STATUSES = Set.of("REJECTED", "PAYOUT_REJECTED", "CANCELLED", "INELIGIBLE");
    public static final Set<String> BUSINESS_APPROVED_STATUSES = Set.of("AWAITING_PAYOUT_APPROVAL", "PAYOUT_APPROVED", "PAYOUT_FAILED", "APPROVED", "PARTIALLY_APPROVED");

    private final PaymentRepository paymentRepository;
    private final RefundRequestItemRepository itemRepository;
    private final PayOsPayoutTransactionRepository payoutRepository;
    private final BankDataEncryptionService bankEncryptionService;
    private final RefundRequestRepository refundRequestRepository;

    public PayoutEligibilityService(
            PaymentRepository paymentRepository,
            RefundRequestItemRepository itemRepository,
            PayOsPayoutTransactionRepository payoutRepository,
            BankDataEncryptionService bankEncryptionService) {
        this(paymentRepository, itemRepository, payoutRepository, bankEncryptionService, null);
    }

    @Autowired
    public PayoutEligibilityService(
            PaymentRepository paymentRepository,
            RefundRequestItemRepository itemRepository,
            PayOsPayoutTransactionRepository payoutRepository,
            BankDataEncryptionService bankEncryptionService,
            @Autowired(required = false) RefundRequestRepository refundRequestRepository) {
        this.paymentRepository = paymentRepository;
        this.itemRepository = itemRepository;
        this.payoutRepository = payoutRepository;
        this.bankEncryptionService = bankEncryptionService;
        this.refundRequestRepository = refundRequestRepository;
    }

    public record EvaluationResult(boolean eligible, List<String> reasons, String primaryErrorCode, long amountVnd) {}

    public EvaluationResult evaluateForBusinessApproval(RefundRequest req, Booking booking, BigDecimal approvedCashTotal, User reviewer) {
        List<String> reasons = new ArrayList<>();
        String errorCode = null;

        if (req == null || isBlank(req.getRequestCode())) {
            reasons.add("Yêu cầu hoàn tiền không tồn tại hoặc thiếu mã yêu cầu.");
            errorCode = "REFUND_NOT_FOUND";
        }
        if (booking == null) {
            reasons.add("Yêu cầu không gắn với đơn hàng hợp lệ.");
            if (errorCode == null) errorCode = "BOOKING_NOT_FOUND";
        } else {
            String bStatus = upper(booking.getBookingStatus());
            if (!PAYOUT_ELIGIBLE_BOOKING_STATUSES.contains(bStatus)) {
                reasons.add("Trạng thái đơn hàng (" + bStatus + ") không đủ điều kiện hoàn tiền.");
                if (errorCode == null) errorCode = "ORDER_NOT_PAID";
            }
            if (booking.isSnapshotReviewRequired()) {
                reasons.add("Đơn hàng đang bị gắn cờ rà soát snapshot kế toán.");
                if (errorCode == null) errorCode = "SNAPSHOT_REVIEW_REQUIRED";
            }
        }

        if (req != null) {
            if (req.isDeadlineExceeded() && req.getDeadlineExceptionBy() == null) {
                reasons.add("Yêu cầu hoàn tiền đã quá thời hạn quy định và chưa có ngoại lệ được Admin phê duyệt.");
                if (errorCode == null) errorCode = "REFUND_DEADLINE_EXCEEDED";
            }
            if (isBlank(req.getPurchaseEmail()) || isBlank(resolveRecipientHolderName(req, booking))) {
                reasons.add("Thiếu thông tin khách hàng / người nhận tiền.");
                if (errorCode == null) errorCode = "CUSTOMER_INFO_MISSING";
            }
            if (isBlank(req.getReasonCategory()) || isBlank(req.getDescription())) {
                reasons.add("Thiếu lý do hoàn tiền rõ ràng.");
                if (errorCode == null) errorCode = "REASON_REQUIRED";
            }
        }

        long cashVnd = approvedCashTotal != null ? PurchaseSnapshotService.toVndLong(approvedCashTotal) : 0L;
        if (cashVnd <= 0) {
            reasons.add("Số tiền hoàn phải lớn hơn 0 VND.");
            if (errorCode == null) errorCode = "INVALID_PAYOUT_AMOUNT";
        }

        Optional<Payment> paidOpt = booking != null
                ? paymentRepository.findFirstByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(booking.getBookingId(), "PAID")
                : Optional.empty();
        if (booking != null && (paidOpt.isEmpty() || paidOpt.get().getAmount() == null
                || PurchaseSnapshotService.toVndLong(paidOpt.get().getAmount()) <= 0)) {
            reasons.add("Đơn hàng chưa có giao dịch thanh toán PAID hợp lệ.");
            if (errorCode == null) errorCode = "NO_PAID_PAYMENT";
        } else if (booking != null && cashVnd > 0) {
            long effectiveCap = computeEffectivePaidCap(booking, paidOpt.orElse(null));
            long committedOrInFlight = computeCommittedOrApprovedAmountExcluding(
                    booking.getBookingId(), req != null ? req.getId() : null);
            if (committedOrInFlight + cashVnd > effectiveCap) {
                reasons.add("Tổng số tiền chi vượt quá số tiền thực tế khách hàng đã thanh toán.");
                if (errorCode == null) errorCode = "PAYOUT_CAP_EXCEEDED";
            }
        }

        if (reviewer == null || reviewer.getUserId() == null) {
            reasons.add("Chưa có người xác minh / phê duyệt nghiệp vụ chịu trách nhiệm.");
            if (errorCode == null) errorCode = "VERIFIER_REQUIRED";
        } else {
            if (req != null && req.getCustomerUser() != null
                    && reviewer.getUserId().equals(req.getCustomerUser().getUserId())) {
                reasons.add("Người xác minh không được tự phê duyệt yêu cầu hoàn tiền của chính mình.");
                if (errorCode == null) errorCode = "SELF_APPROVAL_FORBIDDEN";
            }
            if (!isAuthorizedBusinessApprover(req, reviewer)) {
                reasons.add("Người phê duyệt nghiệp vụ không đủ phân quyền cho chi nhánh này.");
                if (errorCode == null) errorCode = "BRANCH_ACCESS_DENIED";
            }
        }

        if (req != null) {
            validateBankDetails(req, reasons);
            if (errorCode == null && !reasons.isEmpty()) {
                errorCode = "INVALID_BANK_INFO";
            }
        }

        return new EvaluationResult(reasons.isEmpty(), reasons, errorCode, Math.max(0L, cashVnd));
    }

    public EvaluationResult evaluate(RefundRequest req, boolean requireAwaitingQueueStatus) {
        return evaluate(req, requireAwaitingQueueStatus, null);
    }

    public EvaluationResult evaluate(RefundRequest req, boolean requireAwaitingQueueStatus, Long candidatePayoutAdminId) {
        List<String> reasons = new ArrayList<>();
        String errorCode = null;

        if (req == null || req.getId() == null || isBlank(req.getRequestCode())) {
            return new EvaluationResult(false, List.of("Yêu cầu hoàn tiền không tồn tại."), "REFUND_NOT_FOUND", 0L);
        }

        Booking booking = req.getBooking();
        if (booking == null) {
            reasons.add("Thiếu thông tin đơn hàng liên quan.");
            errorCode = "BOOKING_NOT_FOUND";
        } else {
            String bStatus = upper(booking.getBookingStatus());
            if (!PAYOUT_ELIGIBLE_BOOKING_STATUSES.contains(bStatus)) {
                reasons.add("Đơn hàng chưa thanh toán hợp lệ (" + bStatus + ").");
                if (errorCode == null) errorCode = "ORDER_NOT_PAID";
            }
            if (booking.isSnapshotReviewRequired()) {
                reasons.add("Đơn hàng đang yêu cầu rà soát snapshot trước khi chi.");
                if (errorCode == null) errorCode = "SNAPSHOT_REVIEW_REQUIRED";
            }
        }

        if (isBlank(req.getPurchaseEmail()) || isBlank(resolveRecipientHolderName(req, booking))) {
            reasons.add("Thiếu thông tin khách hàng hoặc tên người nhận.");
            if (errorCode == null) errorCode = "CUSTOMER_INFO_MISSING";
        }

        if (isBlank(req.getReasonCategory()) || isBlank(req.getDescription())) {
            reasons.add("Thiếu lý do hoàn tiền rõ ràng.");
            if (errorCode == null) errorCode = "REASON_REQUIRED";
        }

        if (req.isDeadlineExceeded() && req.getDeadlineExceptionBy() == null) {
            reasons.add("Yêu cầu hoàn tiền đã quá thời hạn quy định và chưa có ngoại lệ được Admin phê duyệt.");
            if (errorCode == null) errorCode = "REFUND_DEADLINE_EXCEEDED";
        }

        String reqStatus = upper(req.getRequestStatus());
        String pStatus = upper(req.getPayoutStatus());

        if (TERMINAL_REJECT_CANCEL_STATUSES.contains(reqStatus) || "REJECTED".equals(pStatus) || "CANCELLED".equals(pStatus)) {
            reasons.add("Yêu cầu đã bị từ chối, hủy hoặc đánh dấu không đủ điều kiện (" + reqStatus + ").");
            if (errorCode == null) errorCode = "REFUND_TERMINAL_STATE";
        }

        if ("SUCCEEDED".equals(pStatus) || "COMPLETED".equals(reqStatus) || "PAYOUT_SUCCEEDED".equals(reqStatus)) {
            reasons.add("Yêu cầu đã được chuyển tiền / hoàn tất trước đó.");
            if (errorCode == null) errorCode = "ALREADY_REFUNDED";
        }

        if (Set.of("PENDING", "PROCESSING").contains(pStatus) || payoutRepository.findByActiveRequestLock(req.getId()).isPresent()) {
            reasons.add("Đã tồn tại lệnh PayOS Payout đang xử lý hoặc đã khóa cho yêu cầu này.");
            if (errorCode == null) errorCode = "PAYOUT_IN_PROGRESS";
        }

        List<PayOsPayoutTransaction> historyTx = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(req.getId());
        if (historyTx.stream().anyMatch(tx -> "SUCCEEDED".equalsIgnoreCase(tx.getStatus()))) {
            if (!reasons.contains("Yêu cầu đã được chuyển tiền / hoàn tất trước đó.")) {
                reasons.add("Lịch sử giao dịch đã có lệnh Payout SUCCEEDED.");
            }
            if (errorCode == null) errorCode = "ALREADY_REFUNDED";
        }

        User reviewer = req.getReviewedBy();
        if (reviewer == null || req.getReviewedAt() == null) {
            reasons.add("Yêu cầu chưa hoàn tất bước xác minh và có người chịu trách nhiệm xác minh.");
            if (errorCode == null) errorCode = "VERIFICATION_INCOMPLETE";
        } else {
            if (req.getCustomerUser() != null && reviewer.getUserId() != null
                    && reviewer.getUserId().equals(req.getCustomerUser().getUserId())) {
                reasons.add("Người xác minh bước 1 không được trùng với khách hàng yêu cầu hoàn tiền.");
                if (errorCode == null) errorCode = "SELF_APPROVAL_FORBIDDEN";
            }
            if (!isAuthorizedBusinessApprover(req, reviewer)) {
                reasons.add("Người phê duyệt nghiệp vụ không hợp lệ cho chi nhánh.");
                if (errorCode == null) errorCode = "UNAUTHORIZED_VERIFIER";
            }
        }

        if (candidatePayoutAdminId != null) {
            if (req.getCustomerUser() != null && candidatePayoutAdminId.equals(req.getCustomerUser().getUserId())) {
                reasons.add("Admin không được tự duyệt chi cho yêu cầu hoàn tiền của chính mình.");
                if (errorCode == null) errorCode = "SELF_APPROVAL_FORBIDDEN";
            }
        }

        List<RefundRequestItem> items = itemRepository.findByRefundRequest_IdOrderByIdAsc(req.getId());
        boolean hasApprovedItem = items.stream().anyMatch(i -> "APPROVED".equalsIgnoreCase(i.getItemStatus()));
        if (!hasApprovedItem) {
            reasons.add("Chưa có hạng mục nào được phê duyệt nghiệp vụ.");
            if (errorCode == null) errorCode = "BUSINESS_APPROVAL_REQUIRED";
        }

        if (requireAwaitingQueueStatus && (!"AWAITING_PAYOUT_APPROVAL".equals(reqStatus) || !"AWAITING_APPROVAL".equals(pStatus))) {
            reasons.add("Yêu cầu không ở trạng thái Chờ Admin duyệt chi (" + reqStatus + " / " + pStatus + ").");
            if (errorCode == null) errorCode = "NOT_AWAITING_PAYOUT_APPROVAL";
        } else if (!requireAwaitingQueueStatus && !BUSINESS_APPROVED_STATUSES.contains(reqStatus)) {
            reasons.add("Yêu cầu chưa được phê duyệt nghiệp vụ (" + reqStatus + ").");
            if (errorCode == null) errorCode = "BUSINESS_APPROVAL_REQUIRED";
        }

        long amountVnd = req.getApprovedCashTotal() != null
                ? PurchaseSnapshotService.toVndLong(req.getApprovedCashTotal())
                : 0L;
        if (amountVnd <= 0) {
            reasons.add("Số tiền cần hoàn không hợp lệ (<= 0 VND).");
            if (errorCode == null) errorCode = "INVALID_PAYOUT_AMOUNT";
        }

        Optional<Payment> paidOpt = booking != null
                ? paymentRepository.findFirstByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(booking.getBookingId(), "PAID")
                : Optional.empty();
        if (booking != null && (paidOpt.isEmpty() || paidOpt.get().getAmount() == null
                || PurchaseSnapshotService.toVndLong(paidOpt.get().getAmount()) <= 0)) {
            reasons.add("Không tìm thấy giao dịch thanh toán PAID hợp lệ.");
            if (errorCode == null) errorCode = "NO_PAID_PAYMENT";
        } else if (booking != null && amountVnd > 0) {
            long effectiveCap = computeEffectivePaidCap(booking, paidOpt.orElse(null));
            long committedOrInFlight = computeCommittedOrApprovedAmountExcluding(booking.getBookingId(), req.getId());
            if (committedOrInFlight + amountVnd > effectiveCap) {
                reasons.add("Số tiền hoàn vượt quá số tiền đã thu thực tế trên đơn hàng.");
                if (errorCode == null) errorCode = "PAYOUT_CAP_EXCEEDED";
            }
        }

        int bankSizeBefore = reasons.size();
        validateBankDetails(req, reasons);
        if (reasons.size() > bankSizeBefore && errorCode == null) {
            errorCode = "INVALID_BANK_INFO";
        }

        return new EvaluationResult(reasons.isEmpty(), reasons, errorCode, Math.max(0L, amountVnd));
    }

    public long computeCommittedOrApprovedAmountExcluding(Long bookingId, Long excludeRefundRequestId) {
        if (bookingId == null) return 0L;
        long sum = payoutRepository.sumCommittedOrInFlightAmountByBookingId(bookingId);
        if (refundRequestRepository != null) {
            for (RefundRequest sib : refundRequestRepository.findByBooking_BookingIdOrderByCreatedAtDesc(bookingId)) {
                if (excludeRefundRequestId != null && excludeRefundRequestId.equals(sib.getId())) {
                    continue;
                }
                if ("PAYOUT_APPROVED".equals(upper(sib.getRequestStatus()))
                        && payoutRepository.findByActiveRequestLock(sib.getId()).isEmpty()
                        && payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(sib.getId()).stream()
                                .noneMatch(tx -> "SUCCEEDED".equalsIgnoreCase(tx.getStatus()))) {
                    sum += sib.getApprovedCashTotal() != null
                            ? PurchaseSnapshotService.toVndLong(sib.getApprovedCashTotal())
                            : 0L;
                }
            }
        }
        return sum;
    }

    public static long computeEffectivePaidCap(Booking booking, Payment paidPayment) {
        if (booking == null) return 0L;
        long cap = Math.max(0L,
                PurchaseSnapshotService.toVndLong(booking.getTotalAmount())
                        - PurchaseSnapshotService.toVndLong(booking.getServiceFee()));
        if (paidPayment != null && paidPayment.getAmount() != null) {
            cap = Math.min(cap, PurchaseSnapshotService.toVndLong(paidPayment.getAmount()));
        }
        return cap;
    }

    public static String resolveDisplayStage(RefundRequest req, PayOsPayoutTransaction latestTx) {
        if (req == null) return "";
        String rs = upper(req.getRequestStatus());
        String ps = upper(req.getPayoutStatus());
        String ws = upper(req.getWorkflowStatus());
        if ("PAYOUT_BLOCKED".equals(ws) || "PAYOUT_BLOCKED".equals(rs) || "PAYOUT_BLOCKED".equals(ps)) {
            return "PAYOUT_BLOCKED";
        }
        if (latestTx != null) {
            String txStatus = upper(latestTx.getStatus());
            if ("SUCCEEDED".equals(txStatus)) return "SUCCEEDED";
            if ("FAILED".equals(txStatus)) return "FAILED";
            if ("PROCESSING".equals(txStatus)) {
                String txState = upper(latestTx.getTransactionState());
                return ("UNKNOWN_OUTCOME".equals(txState) || "MANUAL_RECONCILE_REQUIRED".equals(txState) || "UNKNOWN".equals(ws))
                        ? "UNKNOWN_OUTCOME"
                        : "PROCESSING";
            }
            if ("PENDING".equals(txStatus)) return "DISPATCHED";
        }
        if ("UNKNOWN".equals(ws)) return "UNKNOWN_OUTCOME";
        if ("PAYOUT_APPROVED".equals(rs) || "PENDING".equals(ps)) return "PAYOUT_APPROVED";
        if ("AWAITING_PAYOUT_APPROVAL".equals(rs) || "AWAITING_APPROVAL".equals(ps)) return "AWAITING_PAYOUT_APPROVAL";
        if ("PAYOUT_PROCESSING".equals(rs) || "PROCESSING".equals(ps)) return "PROCESSING";
        if ("PAYOUT_SUCCEEDED".equals(rs) || "COMPLETED".equals(rs) || "SUCCEEDED".equals(ps)) return "SUCCEEDED";
        if ("PAYOUT_FAILED".equals(rs) || "FAILED".equals(ps)) return "FAILED";
        if ("PAYOUT_REJECTED".equals(rs) || "REJECTED".equals(ps)) return "PAYOUT_REJECTED";
        return rs;
    }

    private void validateBankDetails(RefundRequest req, List<String> reasons) {
        if (isBlank(req.getToBin())) {
            reasons.add("Thiếu mã BIN ngân hàng nhận tiền.");
        } else {
            try {
                bankEncryptionService.validateAndResolveBankName(req.getToBin());
            } catch (IllegalArgumentException ex) {
                reasons.add(ex.getMessage());
            }
        }
        if (isBlank(req.getToAccountNumberEncrypted()) || isBlank(req.getToAccountNumberMasked())) {
            reasons.add("Thiếu thông tin số tài khoản ngân hàng nhận tiền.");
        } else {
            try {
                String plain = bankEncryptionService.decryptAccountNumber(req.getToAccountNumberEncrypted());
                bankEncryptionService.validateAccountNumber(plain);
            } catch (RuntimeException ex) {
                reasons.add("Số tài khoản ngân hàng không hợp lệ hoặc không thể giải mã.");
            }
        }
        if (isBlank(resolveRecipientHolderName(req, req.getBooking()))) {
            reasons.add("Thiếu tên người nhận / chủ tài khoản ngân hàng.");
        }
    }

    public static String resolveRecipientHolderName(RefundRequest req, Booking booking) {
        if (req != null && !isBlank(req.getAccountHolderName())) return req.getAccountHolderName().trim();
        if (req != null && !isBlank(req.getCustomerName())) return req.getCustomerName().trim();
        if (booking != null && !isBlank(booking.getGuestName())) return booking.getGuestName().trim();
        if (booking != null && booking.getUser() != null && !isBlank(booking.getUser().getFullName())) {
            return booking.getUser().getFullName().trim();
        }
        if (req != null && req.getCustomerUser() != null && !isBlank(req.getCustomerUser().getFullName())) {
            return req.getCustomerUser().getFullName().trim();
        }
        return null;
    }

    private boolean isAuthorizedBusinessApprover(RefundRequest req, User reviewer) {
        if (reviewer == null || isBlank(reviewer.getRole())) return false;
        String role = upper(reviewer.getRole());
        if ("ADMIN".equals(role)) return true;
        if ("MANAGER".equals(role)) {
            Long rCinema = reviewer.getCinemaId();
            Long qCinema = (req != null && req.getCinema() != null) ? req.getCinema().getCinemaId() : null;
            return rCinema != null && rCinema.equals(qCinema);
        }
        return false;
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
