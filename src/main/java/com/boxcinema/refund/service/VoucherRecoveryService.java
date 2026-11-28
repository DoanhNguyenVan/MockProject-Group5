package com.boxcinema.refund.service;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.BookingFoodItemRepository;
import com.boxcinema.common.repository.BookingRepository;
import com.boxcinema.common.repository.OrderVoucherRedemptionRepository;
import com.boxcinema.common.repository.PromotionRepository;
import com.boxcinema.common.repository.RefundAuditLogRepository;
import com.boxcinema.common.repository.RefundRequestRepository;
import com.boxcinema.common.repository.TicketRepository;
import com.boxcinema.common.repository.VoucherRecoveryRepository;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.function.Consumer;

/**
 * Independent Voucher Recovery Service.
 * Runs in REQUIRES_NEW so failure or retry never rolls back or re-triggers PayOS Payout.
 * Never reactivates an expired/used old voucher; always issues a new single-use private Promotion.
 */
@Service
public class VoucherRecoveryService {

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private final SecureRandom random = new SecureRandom();

    private final RefundRequestRepository refundRequestRepository;
    private final VoucherRecoveryRepository voucherRecoveryRepository;
    private final PromotionRepository promotionRepository;
    private final RefundAuditLogRepository auditLogRepository;
    private final RefundEmailService refundEmailService;
    private final BookingRepository bookingRepository;
    private final TransactionTemplate requiresNewTx;
    private final TransactionTemplate readOnlyTx;

    @Autowired(required = false)
    private OrderVoucherRedemptionRepository redemptionRepository;

    @Autowired(required = false)
    private TicketRepository ticketRepository;

    @Autowired(required = false)
    private BookingFoodItemRepository bookingFoodItemRepository;

    private volatile Consumer<VoucherRecovery> customFailureInjectorForTesting;

    @Value("${app.refund.voucher.expiry-days:30}")
    private int voucherExpiryDays;

    public VoucherRecoveryService(
            RefundRequestRepository refundRequestRepository,
            VoucherRecoveryRepository voucherRecoveryRepository,
            PromotionRepository promotionRepository,
            RefundAuditLogRepository auditLogRepository,
            RefundEmailService refundEmailService) {
        this(refundRequestRepository, voucherRecoveryRepository, promotionRepository,
                auditLogRepository, refundEmailService, null, null);
    }

    public VoucherRecoveryService(
            RefundRequestRepository refundRequestRepository,
            VoucherRecoveryRepository voucherRecoveryRepository,
            PromotionRepository promotionRepository,
            RefundAuditLogRepository auditLogRepository,
            RefundEmailService refundEmailService,
            PlatformTransactionManager transactionManager) {
        this(refundRequestRepository, voucherRecoveryRepository, promotionRepository,
                auditLogRepository, refundEmailService, transactionManager, null);
    }

    @Autowired
    public VoucherRecoveryService(
            RefundRequestRepository refundRequestRepository,
            VoucherRecoveryRepository voucherRecoveryRepository,
            PromotionRepository promotionRepository,
            RefundAuditLogRepository auditLogRepository,
            RefundEmailService refundEmailService,
            @Autowired(required = false) PlatformTransactionManager transactionManager,
            @Autowired(required = false) BookingRepository bookingRepository) {
        this.refundRequestRepository = refundRequestRepository;
        this.voucherRecoveryRepository = voucherRecoveryRepository;
        this.promotionRepository = promotionRepository;
        this.auditLogRepository = auditLogRepository;
        this.refundEmailService = refundEmailService;
        this.bookingRepository = bookingRepository;
        if (transactionManager != null) {
            TransactionTemplate tpl = new TransactionTemplate(transactionManager);
            tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTx = tpl;

            TransactionTemplate readTpl = new TransactionTemplate(transactionManager);
            readTpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            readTpl.setReadOnly(true);
            this.readOnlyTx = readTpl;
        } else {
            this.requiresNewTx = null;
            this.readOnlyTx = null;
        }
    }

    /** Test-only fault-injection hook (never exposed via HTTP). */
    public void setCustomFailureInjectorForTesting(Consumer<VoucherRecovery> injector) {
        this.customFailureInjectorForTesting = injector;
    }

    /**
     * Generates a single-use replacement voucher for approved discount amount in its own independent tx.
     */
    public VoucherRecovery generateRecoveryVoucher(Long refundRequestId, Long firstApprovedItemId, Long actorUserId) {
        Long bookingId = resolveBookingIdOutsideTx(refundRequestId);
        try {
            return runInRequiresNew(() -> executeVoucherGenerationTx(refundRequestId, bookingId, firstApprovedItemId, actorUserId));
        } catch (com.boxcinema.common.exception.BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            return runInRequiresNew(() -> recordFailureTx(refundRequestId, bookingId, firstApprovedItemId, ex));
        }
    }

    private <T> T runInRequiresNew(java.util.function.Supplier<T> action) {
        return requiresNewTx != null ? requiresNewTx.execute(status -> action.get()) : action.get();
    }

    private Long resolveBookingIdOutsideTx(Long refundRequestId) {
        if (bookingRepository == null || refundRequestId == null) {
            return null;
        }
        java.util.function.Supplier<Long> reader = () -> refundRequestRepository.findById(refundRequestId)
                .map(r -> r.getBooking() != null ? r.getBooking().getBookingId() : null)
                .orElse(null);
        return readOnlyTx != null ? readOnlyTx.execute(status -> reader.get()) : reader.get();
    }

    private RefundRequest lockRefundRequestInCanonicalOrder(Long refundRequestId, Long bookingId) {
        if (bookingId != null && bookingRepository != null) {
            bookingRepository.findByIdForUpdate(bookingId);
        }
        return refundRequestRepository.findByIdForUpdate(refundRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Refund request not found: " + refundRequestId));
    }

    private VoucherRecovery executeVoucherGenerationTx(Long refundRequestId, Long bookingId, Long firstApprovedItemId, Long actorUserId) {
        RefundRequest req = lockRefundRequestInCanonicalOrder(refundRequestId, bookingId);

        if (!java.util.Set.of(
                "AWAITING_PAYOUT_APPROVAL", "PAYOUT_APPROVED", "PAYOUT_PROCESSING",
                "PAYOUT_SUCCEEDED", "PAYOUT_FAILED", "APPROVED", "PARTIALLY_APPROVED", "COMPLETED"
        ).contains(req.getRequestStatus())) {
            throw new com.boxcinema.common.exception.BusinessException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "REFUND_NOT_APPROVED",
                    "Cannot generate recovery voucher when refund status is " + req.getRequestStatus());
        }

        BigDecimal recoveryAmount = req.getApprovedVoucherTotal() != null
                ? req.getApprovedVoucherTotal().setScale(0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        if (recoveryAmount.compareTo(BigDecimal.ZERO) <= 0) {
            req.setVoucherStatus("NOT_REQUIRED");
            PayOsPayoutService.recomputeOverallStatus(req);
            refundRequestRepository.save(req);
            return null;
        }

        // Idempotency lock: one recovery voucher per refund request
        long lockKey = -req.getId();
        VoucherRecovery existing = voucherRecoveryRepository.findByActiveItemLock(lockKey).orElse(null);
        if (existing != null && "GENERATED".equalsIgnoreCase(existing.getStatus())) {
            return existing;
        }

        Booking booking = req.getBooking();
        long originalBookingDiscountVnd = booking != null
                ? PurchaseSnapshotService.toVndLong(booking.getDiscountAmount())
                : recoveryAmount.longValue();
        OrderVoucherRedemption ovr = null;
        if (redemptionRepository != null && booking != null && booking.getBookingId() != null) {
            ovr = redemptionRepository.findByBooking_BookingId(booking.getBookingId()).orElse(null);
            if (ovr != null && ovr.getActualDiscount() != null && ovr.getActualDiscount() > 0) {
                originalBookingDiscountVnd = Math.max(originalBookingDiscountVnd, ovr.getActualDiscount());
            }
        }
        if (booking != null && booking.getBookingId() != null) {
            long itemAllocatedDiscountSum = 0L;
            if (ticketRepository != null) {
                for (Ticket t : ticketRepository.findByBooking_BookingId(booking.getBookingId())) {
                    itemAllocatedDiscountSum += PurchaseSnapshotService.toVndLong(t.getDiscountAllocated());
                }
            }
            if (bookingFoodItemRepository != null) {
                for (BookingFoodItem bfi : bookingFoodItemRepository.findByBooking_BookingId(booking.getBookingId())) {
                    itemAllocatedDiscountSum += PurchaseSnapshotService.toVndLong(bfi.getDiscountAllocated());
                }
            }
            originalBookingDiscountVnd = Math.max(originalBookingDiscountVnd, itemAllocatedDiscountSum);
        }

        long otherGeneratedVnd = 0L;
        if (booking != null && booking.getBookingId() != null) {
            for (VoucherRecovery sibling : voucherRecoveryRepository.findByBooking_BookingIdOrderByIdAsc(booking.getBookingId())) {
                if ("GENERATED".equalsIgnoreCase(sibling.getStatus())
                        && (sibling.getRefundRequest() == null || !req.getId().equals(sibling.getRefundRequest().getId()))) {
                    otherGeneratedVnd += PurchaseSnapshotService.toVndLong(sibling.getRecoveryAmount());
                }
            }
        }
        if (originalBookingDiscountVnd > 0 && (otherGeneratedVnd + recoveryAmount.longValue() > originalBookingDiscountVnd)) {
            throw new com.boxcinema.common.exception.BusinessException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "VOUCHER_ALREADY_RESTORED",
                    "Cumulative restored voucher value (" + (otherGeneratedVnd + recoveryAmount.longValue())
                            + " VND) would exceed original order voucher discount (" + originalBookingDiscountVnd + " VND).");
        }
        Promotion oldPromo = booking.getPromotion();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime policyMinExpiry = now.plusDays(Math.max(1, voucherExpiryDays));
        LocalDateTime oldEndAt = booking.getPromotionEndAtSnapshot() != null
                ? booking.getPromotionEndAtSnapshot()
                : (oldPromo != null ? oldPromo.getEndAt() : null);

        // If old voucher is still valid beyond configured policy window, inherit endAt; otherwise apply policy expiry
        LocalDateTime effectiveExpiry = (oldEndAt != null && oldEndAt.isAfter(policyMinExpiry))
                ? oldEndAt
                : policyMinExpiry;

        VoucherRecovery vr = existing != null ? existing : new VoucherRecovery();
        vr.setRefundRequest(req);
        vr.setRefundRequestItemId(firstApprovedItemId);
        vr.setBooking(booking);
        vr.setOldPromotionId(oldPromo != null ? oldPromo.getPromotionId() : null);
        vr.setOldVoucherCodeSnapshot(booking.getVoucherCodeSnapshot());
        vr.setRecoveryAmount(recoveryAmount.setScale(2, RoundingMode.HALF_UP));
        vr.setMinOrderAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        vr.setExpiresAt(effectiveExpiry);
        vr.setActiveItemLock(lockKey);

        String newCode = generateUniqueVoucherCode(booking.getBookingId());

        Promotion newPromo = new Promotion();
        newPromo.setPromotionName("Refund Recovery Voucher #" + req.getRequestCode());
        newPromo.setDescription("Single-use compensation voucher recovered from refund " + req.getRequestCode()
                + (booking.getVoucherCodeSnapshot() != null ? " (original voucher: " + booking.getVoucherCodeSnapshot() + ")" : ""));
        newPromo.setVoucherCode(newCode);
        newPromo.setDiscountType("FIXED");
        newPromo.setDiscountValue(recoveryAmount.setScale(2, RoundingMode.HALF_UP));
        newPromo.setMaxDiscount(recoveryAmount.setScale(2, RoundingMode.HALF_UP));
        newPromo.setMinOrderAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        newPromo.setStartAt(now.minusMinutes(1));
        newPromo.setEndAt(effectiveExpiry);
        newPromo.setUsageLimit(1);
        newPromo.setPerUserLimit(1);
        newPromo.setStatus("ACTIVE");
        newPromo.setPublic(false); // Never leak to public promotion dropdown
        newPromo.setPromotionSource("REFUND_RECOVERY");
        newPromo.setTargetUserId(req.getCustomerUser() != null ? req.getCustomerUser().getUserId() : null);
        newPromo.setTargetEmail(req.getPurchaseEmail());
        newPromo = promotionRepository.save(newPromo);

        if (customFailureInjectorForTesting != null) {
            customFailureInjectorForTesting.accept(vr);
        }

        vr.setNewPromotionId(newPromo.getPromotionId());
        vr.setNewVoucherCode(newCode);
        vr.setStatus("GENERATED");
        vr.setLastError(null);
        vr.setGeneratedAt(now);
        vr = voucherRecoveryRepository.save(vr);

        req.setVoucherStatus("GENERATED");
        PayOsPayoutService.recomputeOverallStatus(req);
        refundRequestRepository.save(req);

        if (ovr != null && redemptionRepository != null) {
            long cumulativeRestored = otherGeneratedVnd + recoveryAmount.longValue();
            ovr.setStatus(cumulativeRestored >= originalBookingDiscountVnd ? "RESTORED" : "PARTIALLY_RESTORED");
            ovr.setRestoredAt(now);
            redemptionRepository.save(ovr);
        }

        refundEmailService.enqueueVoucherIssuedEmail(req, vr);

        RefundAuditLog audit = new RefundAuditLog();
        audit.setRefundRequestId(req.getId());
        audit.setEntityType("VOUCHER_RECOVERY");
        audit.setEntityId(vr.getId());
        audit.setEventType("VOUCHER_GENERATED");
        audit.setActorUserId(actorUserId);
        audit.setCinemaId(req.getCinema() != null ? req.getCinema().getCinemaId() : null);
        audit.setDetailsJson(toJson(java.util.Map.of(
                "newVoucherCode", newCode,
                "amount", recoveryAmount.longValue()
        )));
        auditLogRepository.save(audit);
        return vr;
    }

    /**
     * Strict voucher restoration entrypoint that explicitly rejects double restoration attempts
     * for the same refund request with BusinessException(HttpStatus.CONFLICT, "VOUCHER_ALREADY_RESTORED", ...).
     */
    public VoucherRecovery restoreOrderVoucherStrict(Long refundRequestId, Long firstApprovedItemId, Long actorUserId) {
        if (refundRequestId != null) {
            VoucherRecovery existing = voucherRecoveryRepository.findByActiveItemLock(-refundRequestId).orElse(null);
            if (existing != null && "GENERATED".equalsIgnoreCase(existing.getStatus())) {
                throw new com.boxcinema.common.exception.BusinessException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "VOUCHER_ALREADY_RESTORED",
                        "Voucher has already been restored for refund request " + refundRequestId);
            }
        }
        return generateRecoveryVoucher(refundRequestId, firstApprovedItemId, actorUserId);
    }

    /**
     * Atomically deactivates any unredeemed recovery voucher generated for this refund request
     * within the caller's active transaction (where Booking -> RefundRequest locks are already held).
     *
     * @return true if no recovery voucher was already redeemed; false if a voucher was already used.
     */
    public boolean revokeRecoveryVoucherInCurrentTx(Long refundRequestId, Long actorUserId, String reason) {
        if (refundRequestId == null) {
            return true;
        }
        java.util.List<VoucherRecovery> recoveries =
                voucherRecoveryRepository.findByRefundRequest_IdOrderByIdAsc(refundRequestId);
        if (recoveries.isEmpty()) {
            return true;
        }
        boolean allRevokedOrUnused = true;
        for (VoucherRecovery vr : recoveries) {
            String st = vr.getStatus() != null ? vr.getStatus().trim().toUpperCase(java.util.Locale.ROOT) : "";
            if ("REVOKED".equals(st)) {
                if (vr.getActiveItemLock() != null) {
                    vr.setActiveItemLock(null);
                    voucherRecoveryRepository.save(vr);
                }
                continue;
            }
            Promotion promo = null;
            if (vr.getNewVoucherCode() != null && !vr.getNewVoucherCode().isBlank()) {
                promo = promotionRepository.findByVoucherCodeIgnoreCaseForUpdate(vr.getNewVoucherCode()).orElse(null);
            }
            if (promo == null && vr.getNewPromotionId() != null) {
                promo = promotionRepository.findById(vr.getNewPromotionId()).orElse(null);
            }
            boolean alreadyRedeemed = promo != null
                    && (promo.getUsageCount() > 0 || promotionRepository.countTotalUsage(promo.getPromotionId()) > 0);
            if (alreadyRedeemed) {
                allRevokedOrUnused = false;
                continue;
            }
            if (promo != null && !"INACTIVE".equalsIgnoreCase(promo.getStatus())) {
                promo.setStatus("INACTIVE");
                promotionRepository.save(promo);
            }
            vr.setStatus("REVOKED");
            vr.setActiveItemLock(null);
            vr.setLastError(reason != null ? reason : "Revoked due to refund cancellation/rejection/hold.");
            vr = voucherRecoveryRepository.save(vr);

            if (redemptionRepository != null && vr.getBooking() != null && vr.getBooking().getBookingId() != null) {
                Long bId = vr.getBooking().getBookingId();
                long remainingGenerated = 0L;
                for (VoucherRecovery sib : voucherRecoveryRepository.findByBooking_BookingIdOrderByIdAsc(bId)) {
                    if (!sib.getId().equals(vr.getId()) && "GENERATED".equalsIgnoreCase(sib.getStatus())) {
                        remainingGenerated += PurchaseSnapshotService.toVndLong(sib.getRecoveryAmount());
                    }
                }
                final long remGen = remainingGenerated;
                redemptionRepository.findByBooking_BookingId(bId).ifPresent(ovr -> {
                    ovr.setStatus(remGen > 0 ? "PARTIALLY_RESTORED" : "APPLIED");
                    if (remGen == 0) {
                        ovr.setRestoredAt(null);
                    }
                    redemptionRepository.save(ovr);
                });
            }

            RefundAuditLog audit = new RefundAuditLog();
            audit.setRefundRequestId(refundRequestId);
            audit.setEntityType("VOUCHER_RECOVERY");
            audit.setEntityId(vr.getId());
            audit.setEventType("VOUCHER_REVOKED");
            audit.setActorUserId(actorUserId);
            audit.setCinemaId(vr.getRefundRequest() != null && vr.getRefundRequest().getCinema() != null
                    ? vr.getRefundRequest().getCinema().getCinemaId() : null);
            java.util.Map<String, Object> details = new java.util.LinkedHashMap<>();
            details.put("voucherCode", vr.getNewVoucherCode());
            details.put("reason", reason);
            audit.setDetailsJson(toJson(details));
            auditLogRepository.save(audit);
        }
        return allRevokedOrUnused;
    }

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static String toJson(java.util.Map<String, ?> map) {
        try {
            return JSON_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private VoucherRecovery recordFailureTx(Long refundRequestId, Long bookingId, Long firstApprovedItemId, Exception ex) {
        RefundRequest req = lockRefundRequestInCanonicalOrder(refundRequestId, bookingId);
        long lockKey = -req.getId();
        VoucherRecovery vr = voucherRecoveryRepository.findByActiveItemLock(lockKey).orElseGet(VoucherRecovery::new);
        Booking booking = req.getBooking();
        Promotion oldPromo = booking.getPromotion();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime policyMinExpiry = now.plusDays(Math.max(1, voucherExpiryDays));
        LocalDateTime oldEndAt = booking.getPromotionEndAtSnapshot() != null
                ? booking.getPromotionEndAtSnapshot()
                : (oldPromo != null ? oldPromo.getEndAt() : null);
        LocalDateTime effectiveExpiry = (oldEndAt != null && oldEndAt.isAfter(policyMinExpiry))
                ? oldEndAt
                : policyMinExpiry;
        BigDecimal recoveryAmount = req.getApprovedVoucherTotal() != null
                ? req.getApprovedVoucherTotal().setScale(0, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        vr.setRefundRequest(req);
        vr.setRefundRequestItemId(firstApprovedItemId);
        vr.setBooking(booking);
        vr.setOldPromotionId(oldPromo != null ? oldPromo.getPromotionId() : null);
        vr.setOldVoucherCodeSnapshot(booking.getVoucherCodeSnapshot());
        vr.setRecoveryAmount(recoveryAmount.setScale(2, RoundingMode.HALF_UP));
        vr.setMinOrderAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        vr.setExpiresAt(effectiveExpiry);
        vr.setActiveItemLock(lockKey);
        vr.setNewPromotionId(null);
        vr.setNewVoucherCode(null);
        vr.setStatus("FAILED");
        vr.setRetryCount(vr.getRetryCount() + 1);
        vr.setLastError(ex.getMessage());
        vr = voucherRecoveryRepository.save(vr);

        req.setVoucherStatus("FAILED");
        PayOsPayoutService.recomputeOverallStatus(req);
        refundRequestRepository.save(req);
        return vr;
    }

    private String generateUniqueVoucherCode(Long bookingId) {
        String code;
        do {
            StringBuilder sb = new StringBuilder("RCV-").append(bookingId).append("-");
            for (int i = 0; i < 6; i++) {
                sb.append(CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length())));
            }
            code = sb.toString();
        } while (promotionRepository.findByVoucherCodeIgnoreCase(code).isPresent());
        return code;
    }
}
