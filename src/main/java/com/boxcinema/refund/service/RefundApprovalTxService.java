package com.boxcinema.refund.service;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.enums.RefundMethod;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BankDataEncryptionService;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.refund.dto.RefundReviewRequestDto;
import com.boxcinema.refund.provider.PayoutProviderAdapter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

/**
 * Transactional core for RefundRequest screening, business approval, and Admin Payout decision
 * with strict canonical lock ordering:
 * Booking FOR UPDATE -> RefundRequest FOR UPDATE -> RefundRequestItem FOR UPDATE -> Tickets FOR UPDATE.
 */
@Service
public class RefundApprovalTxService {

    private static final Set<String> POST_BUSINESS_STATUSES = Set.of(
            "AWAITING_PAYOUT_APPROVAL", "PAYOUT_APPROVED", "PAYOUT_PROCESSING",
            "PAYOUT_SUCCEEDED", "PAYOUT_FAILED", "APPROVED", "PARTIALLY_APPROVED",
            "COMPLETED", "REJECTED", "PAYOUT_REJECTED", "INELIGIBLE", "CANCELLED"
    );

    private static final Set<String> REVIEWABLE_STATUSES = Set.of(
            "PENDING_REVIEW", "UNDER_VERIFICATION", "MORE_INFO_REQUIRED", "VERIFIED", "ESCALATED_TO_ADMIN"
    );
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final BookingQrRepository bookingQrRepository;
    private final BookingFoodItemRepository bookingFoodItemRepository;
    private final BookingFoodItemComponentRepository componentRepository;
    private final IncidentReportRepository incidentRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final RefundRequestItemRepository refundRequestItemRepository;
    private final RefundApprovalHistoryRepository historyRepository;
    private final RefundCommunicationRepository communicationRepository;
    private final RefundAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final PurchaseSnapshotService snapshotService;
    private final RefundEmailService refundEmailService;
    private final PayoutEligibilityService payoutEligibilityService;
    private final PayOsPayoutTransactionRepository payoutRepository;
    private final TransactionTemplate txTemplate;
    private final TransactionTemplate readOnlyTxTemplate;
    private final TransactionTemplate requiresNewTxTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired(required = false)
    private VoucherRecoveryService voucherRecoveryService;

    @Autowired(required = false)
    private FoodFulfillmentHistoryRepository foodFulfillmentHistoryRepository;

    @Autowired(required = false)
    private BookingSeatRepository bookingSeatRepository;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Autowired(required = false)
    private RefundDeadlineService refundDeadlineService;

    @Autowired(required = false)
    private RefundLimitService refundLimitService;

    @Autowired(required = false)
    private PayoutProviderAdapter payoutProviderAdapter;

    @Autowired(required = false)
    private BankDataEncryptionService bankEncryptionService;

    public void setRefundDeadlineService(RefundDeadlineService refundDeadlineService) {
        this.refundDeadlineService = refundDeadlineService;
    }

    public void setRefundLimitService(RefundLimitService refundLimitService) {
        this.refundLimitService = refundLimitService;
    }

    public void setPayoutProviderAdapter(PayoutProviderAdapter payoutProviderAdapter) {
        this.payoutProviderAdapter = payoutProviderAdapter;
    }

    public void setBankEncryptionService(BankDataEncryptionService bankEncryptionService) {
        this.bankEncryptionService = bankEncryptionService;
    }

    public RefundApprovalTxService(
            BookingRepository bookingRepository,
            TicketRepository ticketRepository,
            BookingQrRepository bookingQrRepository,
            BookingFoodItemRepository bookingFoodItemRepository,
            BookingFoodItemComponentRepository componentRepository,
            IncidentReportRepository incidentRepository,
            RefundRequestRepository refundRequestRepository,
            RefundRequestItemRepository refundRequestItemRepository,
            RefundApprovalHistoryRepository historyRepository,
            RefundCommunicationRepository communicationRepository,
            RefundAuditLogRepository auditLogRepository,
            UserRepository userRepository,
            PurchaseSnapshotService snapshotService,
            RefundEmailService refundEmailService) {
        this(bookingRepository, ticketRepository, bookingQrRepository, bookingFoodItemRepository,
                componentRepository, incidentRepository, refundRequestRepository, refundRequestItemRepository,
                historyRepository, communicationRepository, auditLogRepository, userRepository,
                null, snapshotService, refundEmailService, null, null, null);
    }

    public RefundApprovalTxService(
            BookingRepository bookingRepository,
            TicketRepository ticketRepository,
            BookingQrRepository bookingQrRepository,
            BookingFoodItemRepository bookingFoodItemRepository,
            BookingFoodItemComponentRepository componentRepository,
            IncidentReportRepository incidentRepository,
            RefundRequestRepository refundRequestRepository,
            RefundRequestItemRepository refundRequestItemRepository,
            RefundApprovalHistoryRepository historyRepository,
            RefundCommunicationRepository communicationRepository,
            RefundAuditLogRepository auditLogRepository,
            UserRepository userRepository,
            PaymentRepository paymentRepository,
            PurchaseSnapshotService snapshotService,
            RefundEmailService refundEmailService) {
        this(bookingRepository, ticketRepository, bookingQrRepository, bookingFoodItemRepository,
                componentRepository, incidentRepository, refundRequestRepository, refundRequestItemRepository,
                historyRepository, communicationRepository, auditLogRepository, userRepository,
                paymentRepository, snapshotService, refundEmailService, null, null, null);
    }

    @Autowired
    public RefundApprovalTxService(
            BookingRepository bookingRepository,
            TicketRepository ticketRepository,
            BookingQrRepository bookingQrRepository,
            BookingFoodItemRepository bookingFoodItemRepository,
            BookingFoodItemComponentRepository componentRepository,
            IncidentReportRepository incidentRepository,
            RefundRequestRepository refundRequestRepository,
            RefundRequestItemRepository refundRequestItemRepository,
            RefundApprovalHistoryRepository historyRepository,
            RefundCommunicationRepository communicationRepository,
            RefundAuditLogRepository auditLogRepository,
            UserRepository userRepository,
            @Autowired(required = false) PaymentRepository paymentRepository,
            PurchaseSnapshotService snapshotService,
            RefundEmailService refundEmailService,
            @Autowired(required = false) PayoutEligibilityService payoutEligibilityService,
            @Autowired(required = false) PayOsPayoutTransactionRepository payoutRepository,
            @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.bookingRepository = bookingRepository;
        this.ticketRepository = ticketRepository;
        this.bookingQrRepository = bookingQrRepository;
        this.bookingFoodItemRepository = bookingFoodItemRepository;
        this.componentRepository = componentRepository;
        this.incidentRepository = incidentRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.refundRequestItemRepository = refundRequestItemRepository;
        this.historyRepository = historyRepository;
        this.communicationRepository = communicationRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.snapshotService = snapshotService;
        this.refundEmailService = refundEmailService;
        this.payoutEligibilityService = payoutEligibilityService;
        this.payoutRepository = payoutRepository;
        if (transactionManager != null) {
            this.txTemplate = new TransactionTemplate(transactionManager);
            TransactionTemplate readTpl = new TransactionTemplate(transactionManager);
            readTpl.setReadOnly(true);
            this.readOnlyTxTemplate = readTpl;
            TransactionTemplate reqNewTpl = new TransactionTemplate(transactionManager);
            reqNewTpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTxTemplate = reqNewTpl;
        } else {
            this.txTemplate = null;
            this.readOnlyTxTemplate = null;
            this.requiresNewTxTemplate = null;
        }
    }

    public record ReviewOutcome(boolean triggerVoucherRecovery, Long refundRequestId, Long firstApprovedItemId) {}

    public record PayoutDispatchPrep(boolean proceed, boolean alreadyProcessed, String resultCode, String message, Long amountVnd) {}

    private record PreLockInfo(Long bookingId) {}

    private <T> T runInTx(TransactionTemplate tpl, Supplier<T> action) {
        return tpl != null ? tpl.execute(status -> action.get()) : action.get();
    }

    public ReviewOutcome applyReviewDecision(Long refundRequestId, RefundReviewRequestDto reviewDto, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Actor not found.");
        }
        PreLockInfo preLock = runInTx(readOnlyTxTemplate, () -> {
            User preActor = userRepository.findById(actorUserId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Actor not found."));
            RefundRequest lookup = refundRequestRepository.findById(refundRequestId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
            enforceBranchIsolation(preActor, lookup);
            Long bId = lookup.getBooking().getBookingId();
            if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
                entityManager.detach(lookup);
            }
            return new PreLockInfo(bId);
        });

        return runInTx(txTemplate, () -> applyReviewDecisionTx(refundRequestId, preLock.bookingId(), reviewDto, actorUserId));
    }

    private ReviewOutcome applyReviewDecisionTx(Long refundRequestId, Long bookingId, RefundReviewRequestDto reviewDto, Long actorUserId) {
        // Strict canonical lock order FIRST: Booking -> RefundRequest -> RefundRequestItems -> Tickets
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found."));
        RefundRequest req = refundRequestRepository.findByIdForUpdate(refundRequestId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
        List<RefundRequestItem> items = refundRequestItemRepository.findByRefundRequestIdForUpdate(refundRequestId);
        List<Ticket> allTickets = ticketRepository.findByBookingIdForUpdate(booking.getBookingId());
        Map<Long, Ticket> lockedTicketMap = new HashMap<>();
        for (Ticket t : allTickets) {
            lockedTicketMap.put(t.getTicketId(), t);
        }

        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Actor not found."));
        enforceBranchIsolation(actor, req);

        if (req.getCustomerUser() != null && actor.getUserId().equals(req.getCustomerUser().getUserId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
                    "Maker-checker violation: You cannot review or approve your own refund request.");
        }

        String prevStatus = req.getRequestStatus() != null ? req.getRequestStatus().toUpperCase(Locale.ROOT) : "PENDING_REVIEW";
        String action = reviewDto != null && reviewDto.getAction() != null
                ? reviewDto.getAction().trim().toUpperCase(Locale.ROOT)
                : "DECIDE_ITEMS";
        LocalDateTime now = LocalDateTime.now();

        if ("CANCEL".equals(action)) {
            if (!REVIEWABLE_STATUSES.contains(prevStatus) && !"AWAITING_PAYOUT_APPROVAL".equals(prevStatus)) {
                throw new BusinessException(HttpStatus.CONFLICT, "CANNOT_CANCEL_REFUND",
                        "Cannot cancel refund request in status " + prevStatus);
            }
            if (payoutRepository != null && payoutRepository.findByActiveRequestLock(req.getId()).isPresent()
                    || Set.of("PENDING", "PROCESSING", "SUCCEEDED").contains(upper(req.getPayoutStatus()))) {
                throw new BusinessException(HttpStatus.CONFLICT, "PAYOUT_ALREADY_IN_PROGRESS_OR_COMPLETED",
                        "Cannot cancel after PayOS Payout has been approved or dispatched.");
            }
            String note = requireNonBlankNote(reviewDto != null ? reviewDto.getNote() : null,
                    "A cancellation reason note is mandatory.");
            revertProvisionalApprovalAndVoucher(
                    booking, req, items, allTickets, lockedTicketMap,
                    "CANCELLED", false, actor.getUserId(), note);
            if (refundLimitService != null) {
                refundLimitService.releaseReservationsOnFailureCancelOrReject(
                        req.getId(), "Refund request cancelled: " + note);
            }
            req.setRequestStatus("CANCELLED");
            req.setPayoutStatus("CANCELLED");
            req.setReviewedBy(actor);
            req.setReviewedAt(now);
            req.setDecisionNote(note);
            refundRequestItemRepository.saveAll(items);
            refundRequestRepository.save(req);
            appendHistory(req, null, actor, "REQUEST_CANCELLED", prevStatus, "CANCELLED", note);
            recordAudit(req, actor, "REFUND_CANCELLED");
            return new ReviewOutcome(false, req.getId(), null);
        }

        if (POST_BUSINESS_STATUSES.contains(prevStatus)) {
            // Double-click / already business-decided: idempotent no-op
            return new ReviewOutcome(false, req.getId(), null);
        }

        if ("START_VERIFICATION".equals(action)) {
            req.setRequestStatus("UNDER_VERIFICATION");
            req.setReviewedBy(actor);
            req.setReviewedAt(now);
            if (reviewDto != null && reviewDto.getNote() != null && !reviewDto.getNote().isBlank()) {
                req.setDecisionNote(reviewDto.getNote().trim());
            }
            refundRequestRepository.save(req);
            appendHistory(req, null, actor, "VERIFICATION_STARTED", prevStatus, "UNDER_VERIFICATION", req.getDecisionNote());
            recordAudit(req, actor, "REFUND_VERIFICATION_STARTED");
            return new ReviewOutcome(false, req.getId(), null);
        }

        if ("MARK_VERIFIED".equals(action)) {
            req.setRequestStatus("VERIFIED");
            req.setReviewedBy(actor);
            req.setReviewedAt(now);
            if (reviewDto != null && reviewDto.getNote() != null && !reviewDto.getNote().isBlank()) {
                req.setDecisionNote(reviewDto.getNote().trim());
            }
            refundRequestRepository.save(req);
            appendHistory(req, null, actor, "REQUEST_VERIFIED", prevStatus, "VERIFIED", req.getDecisionNote());
            recordAudit(req, actor, "REFUND_VERIFIED");
            return new ReviewOutcome(false, req.getId(), null);
        }

        if ("ESCALATE_TO_ADMIN".equals(action)) {
            String note = requireNonBlankNote(reviewDto != null ? reviewDto.getNote() : null,
                    "Escalation note explaining why Admin review is required is mandatory.");
            req.setRequestStatus("ESCALATED_TO_ADMIN");
            req.setReviewedBy(actor);
            req.setReviewedAt(now);
            req.setDecisionNote(note);
            refundRequestRepository.save(req);
            appendHistory(req, null, actor, "ESCALATED_TO_ADMIN", prevStatus, "ESCALATED_TO_ADMIN", note);
            recordAudit(req, actor, "REFUND_ESCALATED_TO_ADMIN");
            return new ReviewOutcome(false, req.getId(), null);
        }

        if ("MARK_INELIGIBLE".equals(action)) {
            String note = requireNonBlankNote(reviewDto != null ? reviewDto.getNote() : null,
                    "Ineligibility reason is mandatory when marking a request INELIGIBLE.");
            req.setRequestStatus("INELIGIBLE");
            req.setPayoutStatus("NOT_REQUIRED");
            req.setVoucherStatus("NOT_REQUIRED");
            req.setReviewedBy(actor);
            req.setReviewedAt(now);
            req.setDecisionNote(note);
            for (RefundRequestItem item : items) {
                item.setItemStatus("REJECTED");
                item.setApprovedQuantity(0);
                item.setApprovedCashAmount(BigDecimal.ZERO);
                item.setApprovedVoucherAmount(BigDecimal.ZERO);
                item.setDecisionReason(note);
                item.setDecidedBy(actor);
                item.setDecidedAt(now);
                item.setActiveLockKey(null);
            }
            refundRequestItemRepository.saveAll(items);
            refundRequestRepository.save(req);
            appendHistory(req, null, actor, "MARKED_INELIGIBLE", prevStatus, "INELIGIBLE", note);
            refundEmailService.enqueueRejectedEmail(req, items);
            recordAudit(req, actor, "REFUND_INELIGIBLE");
            return new ReviewOutcome(false, req.getId(), null);
        }

        if ("REQUEST_MORE_INFO".equals(action)) {
            String note = requireNonBlankNote(reviewDto != null ? reviewDto.getNote() : null,
                    "A message/question is required when requesting more information.");
            req.setRequestStatus("MORE_INFO_REQUIRED");
            req.setReviewedBy(actor);
            req.setReviewedAt(now);
            req.setDecisionNote(note);
            for (RefundRequestItem item : items) {
                if ("PENDING".equalsIgnoreCase(item.getItemStatus())) {
                    item.setItemStatus("MORE_INFO_REQUIRED");
                }
            }
            refundRequestItemRepository.saveAll(items);
            refundRequestRepository.save(req);

            RefundCommunication comm = new RefundCommunication();
            comm.setRefundRequest(req);
            comm.setSenderType("ADMIN".equalsIgnoreCase(actor.getRole()) ? "ADMIN" : "MANAGER");
            comm.setSenderUser(actor);
            comm.setSenderName(actor.getFullName());
            comm.setMessage(note);
            comm.setEmailDispatched(true);
            comm = communicationRepository.save(comm);

            appendHistory(req, null, actor, "REQUEST_MORE_INFO", prevStatus, "MORE_INFO_REQUIRED", note);
            refundEmailService.enqueueMoreInfoEmail(req, comm.getId(), comm.getMessage());
            return new ReviewOutcome(false, req.getId(), null);
        }

        if (reviewDto == null || reviewDto.getItemDecisions() == null || reviewDto.getItemDecisions().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DECISIONS_REQUIRED", "Item decisions are required.");
        }

        boolean approvingAny = reviewDto.getItemDecisions().stream()
                .anyMatch(d -> "APPROVED".equalsIgnoreCase(d.getDecision()));
        if (approvingAny && booking.isSnapshotReviewRequired()) {
            throw new BusinessException(HttpStatus.CONFLICT, "SNAPSHOT_REVIEW_REQUIRED",
                    "Booking payment snapshot requires manual accounting reconciliation before refund approval.");
        }

        Map<Long, RefundReviewRequestDto.ItemDecisionDto> decisionMap = new HashMap<>();
        for (RefundReviewRequestDto.ItemDecisionDto d : reviewDto.getItemDecisions()) {
            if (d.getItemId() != null) {
                decisionMap.put(d.getItemId(), d);
            }
        }

        Long showtimeId = booking.getShowtime() != null ? booking.getShowtime().getShowtimeId() : null;
        boolean hasVerifiedCinemaIncident = incidentRepository.existsVerifiedIncidentForBookingOrShowtime(
                "CINEMA_INCIDENT", booking.getBookingId(), showtimeId);
        boolean hasVerifiedProductIncident = incidentRepository.existsVerifiedIncidentForBookingOrShowtime(
                "PRODUCT_INCIDENT", booking.getBookingId(), showtimeId);
        boolean hasVerifiedStaffError = incidentRepository.existsVerifiedIncidentForBookingOrShowtime(
                "STAFF_ERROR", booking.getBookingId(), showtimeId);

        IncidentReport linkedVerifiedIncident = req.getIncidentReport();
        if (linkedVerifiedIncident == null || !"VERIFIED".equalsIgnoreCase(linkedVerifiedIncident.getStatus())) {
            List<IncidentReport> byBook = incidentRepository.findByBooking_BookingIdAndStatus(booking.getBookingId(), "VERIFIED");
            if (!byBook.isEmpty()) {
                linkedVerifiedIncident = byBook.get(0);
            } else if (showtimeId != null) {
                List<IncidentReport> byShow = incidentRepository.findByShowtime_ShowtimeIdAndStatus(showtimeId, "VERIFIED");
                if (!byShow.isEmpty()) {
                    linkedVerifiedIncident = byShow.get(0);
                }
            }
            if (linkedVerifiedIncident != null && req.getIncidentReport() == null) {
                req.setIncidentReport(linkedVerifiedIncident);
            }
        }
        if (linkedVerifiedIncident != null && "VERIFIED".equalsIgnoreCase(linkedVerifiedIncident.getStatus())
                && "CINEMA_INCIDENT".equalsIgnoreCase(linkedVerifiedIncident.getIncidentGroup())) {
            hasVerifiedCinemaIncident = true;
        }

        if (approvingAny) {
            if (refundDeadlineService != null && RefundDeadlineService.resolveShowtimeEndAt(booking) != null) {
                LocalDateTime evalAnchor = req.getCreatedAt() != null ? req.getCreatedAt() : refundDeadlineService.now();
                RefundDeadlineService.DeadlineEvaluation dlEval =
                        refundDeadlineService.evaluate(booking, linkedVerifiedIncident, evalAnchor);
                req.setRefundDeadlineAt(dlEval.deadlineAt());
                req.setDeadlineBasis(dlEval.deadlineBasis());
                req.setDeadlineExceeded(dlEval.deadlineExceeded());
            }

            if (req.isDeadlineExceeded() && req.getDeadlineExceptionBy() == null) {
                if (Boolean.TRUE.equals(reviewDto.getDeadlineExceptionApproved())) {
                    if (!"ADMIN".equalsIgnoreCase(actor.getRole())) {
                        throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_DEADLINE_EXCEPTION_ONLY",
                                "Only an ADMIN can grant a refund deadline exception.");
                    }
                    String exReason = (reviewDto.getDeadlineExceptionReason() != null && !reviewDto.getDeadlineExceptionReason().isBlank())
                            ? reviewDto.getDeadlineExceptionReason().trim()
                            : (reviewDto.getNote() != null ? reviewDto.getNote().trim() : "");
                    if (exReason.isBlank()) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, "DEADLINE_EXCEPTION_REASON_REQUIRED",
                                "A mandatory reason is required to grant a refund deadline exception.");
                    }
                    req.setDeadlineExceptionBy(actor.getUserId());
                    req.setDeadlineExceptionReason(exReason);
                    req.setDeadlineExceptionAt(now);
                    recordDeadlineExceptionAudit(req, actor, exReason);
                } else {
                    throw new BusinessException(HttpStatus.CONFLICT, "REFUND_DEADLINE_EXCEEDED",
                            "Refund request exceeds the refund deadline (" + req.getRefundDeadlineAt()
                                    + "). An explicit ADMIN deadline exception with mandatory reason is required.");
                }
            }
        }

        BigDecimal totalCashApproved = BigDecimal.ZERO;
        BigDecimal totalVoucherApproved = BigDecimal.ZERO;
        int approvedCount = 0;
        int rejectedCount = 0;
        Long firstApprovedItemId = null;

        for (RefundRequestItem item : items) {
            RefundReviewRequestDto.ItemDecisionDto dec = decisionMap.get(item.getId());
            if (dec == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "MISSING_ITEM_DECISION",
                        "Decision missing for item: " + item.getItemLabelSnapshot());
            }
            String decStatus = dec.getDecision() != null ? dec.getDecision().trim().toUpperCase(Locale.ROOT) : "";
            if (!"APPROVED".equals(decStatus) && !"REJECTED".equals(decStatus)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ITEM_DECISION",
                        "Item decision must be APPROVED or REJECTED.");
            }

            if ("REJECTED".equals(decStatus)) {
                String reason = dec.getReason();
                if (reason == null || reason.isBlank()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "REJECTION_REASON_REQUIRED",
                            "A mandatory rejection reason is required for item: " + item.getItemLabelSnapshot());
                }
                item.setItemStatus("REJECTED");
                item.setApprovedQuantity(0);
                item.setApprovedCashAmount(BigDecimal.ZERO);
                item.setApprovedVoucherAmount(BigDecimal.ZERO);
                item.setDecisionReason(reason.trim());
                item.setDecidedBy(actor);
                item.setDecidedAt(now);
                item.setActiveLockKey(null);
                rejectedCount++;
                appendHistory(req, item.getId(), actor, "ITEM_REJECTED", prevStatus, "REJECTED", reason.trim());
                continue;
            }

            int reqQty = item.getRequestedQuantity();
            int appQty = dec.getApprovedQuantity() != null ? dec.getApprovedQuantity() : reqQty;
            if (appQty <= 0 || appQty > reqQty) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_APPROVED_QTY",
                        "Approved quantity must be between 1 and " + reqQty + " for " + item.getItemLabelSnapshot());
            }

            long[] cashAndVoucher;
            if ("TICKET".equalsIgnoreCase(item.getItemKind())) {
                Ticket ticket = lockedTicketMap.get(item.getTicketId());
                if (ticket == null) {
                    ticket = ticketRepository.findById(item.getTicketId())
                            .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "Ticket not found."));
                }
                if ("REFUNDED".equalsIgnoreCase(ticket.getTicketStatus()) || "REFUNDED".equalsIgnoreCase(ticket.getRefundStatus())) {
                    throw new BusinessException(HttpStatus.CONFLICT, "ALREADY_REFUNDED", "Ticket already refunded.");
                }
                boolean isConflictUnissued = "CONFLICT_UNISSUED".equalsIgnoreCase(ticket.getTicketStatus());
                boolean isCheckedIn = !isConflictUnissued
                        && ("USED".equalsIgnoreCase(ticket.getTicketStatus())
                        || "CHECKED_IN".equalsIgnoreCase(ticket.getTicketStatus())
                        || ticket.getCheckedInAt() != null);
                boolean hasVerifiedIncidentForTicket = hasVerifiedCinemaIncident
                        || (linkedVerifiedIncident != null && "VERIFIED".equalsIgnoreCase(linkedVerifiedIncident.getStatus()));
                if (isCheckedIn && !hasVerifiedIncidentForTicket) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "CHECKED_IN_TICKET_REQUIRES_VERIFIED_INCIDENT",
                            "Checked-in ticket " + ticket.getTicketCode() + " can only be refunded when backed by a verified CINEMA_INCIDENT.");
                }
                ticket.setTicketStatus("REFUNDED");
                ticket.setRefundStatus("REFUNDED");
                ticketRepository.save(ticket);

                long netVnd = PurchaseSnapshotService.toVndLong(ticket.getNetPaidAmount());
                long discVnd = PurchaseSnapshotService.toVndLong(ticket.getDiscountAllocated());
                cashAndVoucher = new long[]{netVnd, discVnd};
            } else if ("FOOD_ITEM".equalsIgnoreCase(item.getItemKind())) {
                BookingFoodItem bfi = bookingFoodItemRepository.findByIdForUpdate(item.getBookingFoodItemId())
                        .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "FOOD_ITEM_NOT_FOUND", "Food item not found."));
                int remainingRefundable = bfi.getQuantity() - bfi.getRefundedQuantity() - bfi.getReplacementAcceptedQuantity();
                if (appQty > remainingRefundable) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "EXCEEDS_REFUNDABLE_QTY",
                            "Requested quantity exceeds remaining refundable quantity (after replacements/prior refunds).");
                }
                boolean hasCannotFulfill = hasCannotFulfillEvidence(
                        booking.getBookingId(), bfi.getId(), null, bfi.getDeliveryStatus());
                boolean isWrongFoodClaim = Set.of("STAFF_ERROR", "WRONG_FOOD", "WRONG_FOOD_DELIVERED")
                        .contains(upper(req.getReasonCategory()));
                if (isWrongFoodClaim && !(hasCannotFulfill || hasVerifiedProductIncident || hasVerifiedStaffError)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "REPLACEMENT_FIRST_REQUIRED",
                            "Wrong food item (" + bfi.getProductNameSnapshot()
                                    + ") must be replaced first; cash refund is allowed only when replacement cannot be fulfilled or backed by a verified incident.");
                }
                int undeliveredRemaining = Math.max(0, bfi.getQuantity() - bfi.getDeliveredQuantity()
                        - bfi.getReplacementAcceptedQuantity() - bfi.getRefundedQuantity());
                if (appQty > undeliveredRemaining && !(hasCannotFulfill || hasVerifiedProductIncident || hasVerifiedStaffError)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "DELIVERED_FOOD_NOT_REFUNDABLE",
                            "Already-delivered food (" + bfi.getProductNameSnapshot() + ") cannot be refunded without a verified product or staff incident.");
                }
                long[] prev = computePriorFoodRefundedTotals(booking.getBookingId(), bfi.getId(), null);
                cashAndVoucher = snapshotService.calculateQuantityRefund(
                        bfi.getQuantity(), bfi.getNetPaidAmount(), bfi.getDiscountAllocated(),
                        bfi.getRefundedQuantity(), BigDecimal.valueOf(prev[0]), BigDecimal.valueOf(prev[1]), appQty);
                bfi.setRefundedQuantity(bfi.getRefundedQuantity() + appQty);
                if (bfi.getRefundedQuantity() >= bfi.getQuantity()) {
                    bfi.setDeliveryStatus("REFUNDED");
                }
                bookingFoodItemRepository.save(bfi);
                item.setActiveLockKey(null);
            } else if ("COMBO_COMPONENT".equalsIgnoreCase(item.getItemKind())) {
                BookingFoodItemComponent comp = componentRepository.findByIdForUpdate(item.getBookingFoodItemComponentId())
                        .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "COMPONENT_NOT_FOUND", "Combo component not found."));
                int remainingRefundable = comp.getTotalQuantity() - comp.getRefundedQuantity() - comp.getReplacementAcceptedQuantity();
                if (appQty > remainingRefundable) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "EXCEEDS_REFUNDABLE_QTY",
                            "Requested quantity exceeds remaining refundable quantity for combo component.");
                }
                boolean hasCannotFulfill = hasCannotFulfillEvidence(
                        booking.getBookingId(), comp.getBookingFoodItem() != null ? comp.getBookingFoodItem().getId() : null,
                        comp.getId(), comp.getDeliveryStatus());
                boolean isWrongFoodClaim = Set.of("STAFF_ERROR", "WRONG_FOOD", "WRONG_FOOD_DELIVERED")
                        .contains(upper(req.getReasonCategory()));
                if (isWrongFoodClaim && !(hasCannotFulfill || hasVerifiedProductIncident || hasVerifiedStaffError)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "REPLACEMENT_FIRST_REQUIRED",
                            "Wrong combo component (" + comp.getComponentNameSnapshot()
                                    + ") must be replaced first; cash refund is allowed only when replacement cannot be fulfilled or backed by a verified incident.");
                }
                int undeliveredRemaining = Math.max(0, comp.getTotalQuantity() - comp.getDeliveredQuantity()
                        - comp.getReplacementAcceptedQuantity() - comp.getRefundedQuantity());
                if (appQty > undeliveredRemaining && !(hasCannotFulfill || hasVerifiedProductIncident || hasVerifiedStaffError)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "DELIVERED_FOOD_NOT_REFUNDABLE",
                            "Already-delivered combo component (" + comp.getComponentNameSnapshot() + ") cannot be refunded without a verified product or staff incident.");
                }
                long[] prev = computePriorFoodRefundedTotals(booking.getBookingId(), null, comp.getId());
                cashAndVoucher = snapshotService.calculateQuantityRefund(
                        comp.getTotalQuantity(), comp.getNetPaidAmount(), comp.getDiscountAllocated(),
                        comp.getRefundedQuantity(), BigDecimal.valueOf(prev[0]), BigDecimal.valueOf(prev[1]), appQty);
                comp.setRefundedQuantity(comp.getRefundedQuantity() + appQty);
                if (comp.getRefundedQuantity() >= comp.getTotalQuantity()) {
                    comp.setDeliveryStatus("REFUNDED");
                }
                componentRepository.save(comp);
                item.setActiveLockKey(null);
            } else {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ITEM_KIND", "Unknown item kind: " + item.getItemKind());
            }

            item.setItemStatus("APPROVED");
            item.setApprovedQuantity(appQty);
            item.setApprovedCashAmount(BigDecimal.valueOf(cashAndVoucher[0]).setScale(2, RoundingMode.HALF_UP));
            item.setApprovedVoucherAmount(BigDecimal.valueOf(cashAndVoucher[1]).setScale(2, RoundingMode.HALF_UP));
            item.setDecisionReason(dec.getReason() != null ? dec.getReason().trim() : null);
            item.setDecidedBy(actor);
            item.setDecidedAt(now);

            totalCashApproved = totalCashApproved.add(item.getApprovedCashAmount());
            totalVoucherApproved = totalVoucherApproved.add(item.getApprovedVoucherAmount());
            approvedCount++;
            if (firstApprovedItemId == null) firstApprovedItemId = item.getId();
            appendHistory(req, item.getId(), actor, "ITEM_APPROVED", prevStatus, "APPROVED",
                    "Cash: " + cashAndVoucher[0] + " VND, Voucher: " + cashAndVoucher[1] + " VND");
        }

        // Enforce booking-level cumulative payout cap (<= totalAmount - serviceFee, and <= paid payment amount if present)
        long maxBookingCashCap = Math.max(0L,
                PurchaseSnapshotService.toVndLong(booking.getTotalAmount())
                        - PurchaseSnapshotService.toVndLong(booking.getServiceFee()));
        Payment settledPaidPayment = null;
        if (paymentRepository != null) {
            Optional<Payment> paidPaymentOpt = paymentRepository
                    .findFirstByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(booking.getBookingId(), "PAID");
            if (paidPaymentOpt.isPresent() && paidPaymentOpt.get().getAmount() != null) {
                Payment paidPay = paidPaymentOpt.get();
                settledPaidPayment = paidPay;
                if (req.getOriginalPaymentId() == null) {
                    req.setOriginalPaymentId(paidPay.getPaymentId());
                }
                if (req.getOriginalPaymentRef() == null) {
                    req.setOriginalPaymentRef(paidPay.getProviderTransactionId() != null
                            ? paidPay.getProviderTransactionId() : paidPay.getPaymentLinkId());
                }
                long settledPaidVnd = PurchaseSnapshotService.toVndLong(paidPay.getAmount());
                maxBookingCashCap = Math.min(maxBookingCashCap, settledPaidVnd);
            }
        }
        long alreadyApprovedElsewhere = computeTotalApprovedCashForBookingExcluding(booking.getBookingId(), req.getId());
        if (alreadyApprovedElsewhere + totalCashApproved.longValue() > maxBookingCashCap) {
            throw new BusinessException(HttpStatus.CONFLICT, "PAYOUT_CAP_EXCEEDED",
                    "Cumulative cash refunds would exceed the net amount paid for this booking.");
        }

        if (!allTickets.isEmpty() && allTickets.stream().allMatch(t -> "REFUNDED".equalsIgnoreCase(t.getTicketStatus()))) {
            bookingQrRepository.findByBookingIdForUpdate(booking.getBookingId()).ifPresent(qr -> {
                qr.setStatus("REVOKED");
                bookingQrRepository.save(qr);
            });
        }

        req.setApprovedCashTotal(totalCashApproved.setScale(2, RoundingMode.HALF_UP));
        req.setApprovedVoucherTotal(totalVoucherApproved.setScale(2, RoundingMode.HALF_UP));
        req.setReviewedBy(actor);
        req.setReviewedAt(now);
        req.setDecisionNote(reviewDto.getNote() != null ? reviewDto.getNote().trim() : null);

        if (approvedCount > 0) {
            boolean hasCashPayout = totalCashApproved.compareTo(BigDecimal.ZERO) > 0;
            if (hasCashPayout && payoutEligibilityService != null) {
                PayoutEligibilityService.EvaluationResult eval =
                        payoutEligibilityService.evaluateForBusinessApproval(req, booking, totalCashApproved, actor);
                if (!eval.eligible()) {
                    String errCode = eval.primaryErrorCode() != null ? eval.primaryErrorCode() : "PAYOUT_ELIGIBILITY_FAILED";
                    throw new BusinessException(HttpStatus.CONFLICT, errCode, String.join("; ", eval.reasons()));
                }
            }

            if (hasCashPayout) {
                long approvedCashVnd = PurchaseSnapshotService.toVndLong(totalCashApproved);
                resolveAndAuditRefundMethodFallback(req, settledPaidPayment, actor);
                captureRecipientSnapshot(req, approvedCashVnd);
                if (refundLimitService != null) {
                    refundLimitService.reserveOnApproval(
                            req,
                            actor,
                            approvedCashVnd,
                            reviewDto != null ? reviewDto.getLimitOverrideApproved() : null,
                            reviewDto != null ? reviewDto.getLimitOverrideReason() : null
                    );
                }
                // Stage 1 stops at AWAITING_PAYOUT_APPROVAL; Admin explicitly dispatches PayOS Payout in Stage 2
                req.setRequestStatus("AWAITING_PAYOUT_APPROVAL");
                req.setPayoutStatus("AWAITING_APPROVAL");
            } else {
                String newStatus = (rejectedCount == 0) ? "APPROVED" : "PARTIALLY_APPROVED";
                req.setRequestStatus(newStatus);
                req.setPayoutStatus("NOT_REQUIRED");
            }
            String prevVoucherStatus = upper(req.getVoucherStatus());
            if ("ISSUED".equals(prevVoucherStatus) || "GENERATED".equals(prevVoucherStatus)) {
                req.setVoucherStatus(prevVoucherStatus);
            } else {
                req.setVoucherStatus(totalVoucherApproved.compareTo(BigDecimal.ZERO) > 0 ? "PENDING" : "NOT_REQUIRED");
            }
            refundRequestItemRepository.saveAll(items);
            refundRequestRepository.save(req);

            String histAction = hasCashPayout
                    ? "BUSINESS_APPROVED_QUEUED_FOR_PAYOUT"
                    : (rejectedCount == 0 ? "REQUEST_APPROVED" : "REQUEST_PARTIALLY_APPROVED");
            String histNote = (rejectedCount == 0 ? "Full business approval" : "Partial item business approval")
                    + (req.getDecisionNote() != null ? " — " + req.getDecisionNote() : "");
            appendHistory(req, null, actor, histAction, prevStatus, req.getRequestStatus(), histNote);
            refundEmailService.enqueueApprovedEmail(req);
            recordAudit(req, actor, hasCashPayout ? "REFUND_QUEUED_FOR_PAYOUT" : ("REFUND_" + req.getRequestStatus()));
            boolean shouldTriggerVoucher = totalVoucherApproved.compareTo(BigDecimal.ZERO) > 0
                    && !"ISSUED".equals(prevVoucherStatus) && !"GENERATED".equals(prevVoucherStatus);
            return new ReviewOutcome(shouldTriggerVoucher, req.getId(), firstApprovedItemId);
        } else {
            req.setRequestStatus("REJECTED");
            req.setPayoutStatus("NOT_REQUIRED");
            req.setVoucherStatus("NOT_REQUIRED");
            refundRequestItemRepository.saveAll(items);
            refundRequestRepository.save(req);

            appendHistory(req, null, actor, "REQUEST_REJECTED", prevStatus, "REJECTED", req.getDecisionNote());
            refundEmailService.enqueueRejectedEmail(req, items);
            recordAudit(req, actor, "REFUND_REJECTED");
            return new ReviewOutcome(false, req.getId(), null);
        }
    }

    /**
     * Stage 2 (Admin only): Locks Booking -> RefundRequest, verifies full 13-point eligibility,
     * and atomically transitions AWAITING_PAYOUT_APPROVAL -> PAYOUT_APPROVED before calling PayOS HTTP outside DB TX.
     */
    public PayoutDispatchPrep approvePayoutForDispatchTx(Long refundRequestId, Long adminUserId, String note, String batchCode) {
        return approvePayoutForDispatchTx(refundRequestId, adminUserId, note, batchCode, null, null);
    }

    public PayoutDispatchPrep approvePayoutForDispatchTx(Long refundRequestId,
                                                         Long adminUserId,
                                                         String note,
                                                         String batchCode,
                                                         Boolean limitOverrideApproved,
                                                         String limitOverrideReason) {
        if (adminUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Admin authentication required.");
        }
        PreLockInfo preLock = runInTx(readOnlyTxTemplate, () -> {
            User preAdmin = userRepository.findById(adminUserId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Actor not found."));
            if (!"ADMIN".equalsIgnoreCase(preAdmin.getRole())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_PAYOUT_ONLY", "Only Admin can approve and dispatch cash payouts.");
            }
            RefundRequest lookup = refundRequestRepository.findById(refundRequestId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found: " + refundRequestId));
            Long bId = lookup.getBooking().getBookingId();
            if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
                entityManager.detach(lookup);
            }
            return new PreLockInfo(bId);
        });

        try {
            return runInTx(txTemplate, () -> {
            Booking booking = bookingRepository.findByIdForUpdate(preLock.bookingId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found."));
            RefundRequest req = refundRequestRepository.findByIdForUpdate(refundRequestId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
            User admin = userRepository.findById(adminUserId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Actor not found."));
            if (!"ADMIN".equalsIgnoreCase(admin.getRole())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_PAYOUT_ONLY", "Only Admin can approve and dispatch cash payouts.");
            }

            String prevStatus = upper(req.getRequestStatus());
            String prevPayout = upper(req.getPayoutStatus());
            long cashVnd = req.getApprovedCashTotal() != null ? PurchaseSnapshotService.toVndLong(req.getApprovedCashTotal()) : 0L;

            if (Set.of("PAYOUT_APPROVED", "PAYOUT_PROCESSING", "PAYOUT_SUCCEEDED", "COMPLETED").contains(prevStatus)
                    || Set.of("PENDING", "PROCESSING", "SUCCEEDED").contains(prevPayout)
                    || (payoutRepository != null && payoutRepository.findByActiveRequestLock(refundRequestId).isPresent())) {
                return new PayoutDispatchPrep(false, true, "ALREADY_PROCESSED",
                        "Yêu cầu đã được duyệt chi hoặc đang xử lý (" + prevStatus + " / " + prevPayout + ").", cashVnd);
            }

            if (req.getCustomerUser() != null && admin.getUserId().equals(req.getCustomerUser().getUserId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
                        "Maker-checker violation: Admin cannot approve cash payout for their own refund request.");
            }
            boolean sameVerifierAndApprover = req.getReviewedBy() != null
                    && admin.getUserId().equals(req.getReviewedBy().getUserId());

            if (payoutEligibilityService != null) {
                PayoutEligibilityService.EvaluationResult eval =
                        payoutEligibilityService.evaluate(req, true, admin.getUserId());
                if (!eval.eligible()) {
                    return new PayoutDispatchPrep(false, false, "SKIPPED_INELIGIBLE",
                            String.join("; ", eval.reasons()), eval.amountVnd());
                }
                cashVnd = eval.amountVnd();
            } else if (!"AWAITING_PAYOUT_APPROVAL".equals(prevStatus) || cashVnd <= 0) {
                return new PayoutDispatchPrep(false, false, "SKIPPED_INELIGIBLE",
                        "Yêu cầu không ở trạng thái Chờ Admin duyệt chi.", cashVnd);
            }

            // Re-verify cumulative booking cap under Booking FOR UPDATE lock including sibling PAYOUT_APPROVED requests
            Payment paidPaymentForFallback = null;
            if (paymentRepository != null) {
                Payment paidPayment = paymentRepository
                        .findFirstByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(booking.getBookingId(), "PAID")
                        .orElse(null);
                paidPaymentForFallback = paidPayment;
                if (paidPayment != null) {
                    if (req.getOriginalPaymentId() == null) {
                        req.setOriginalPaymentId(paidPayment.getPaymentId());
                    }
                    if (req.getOriginalPaymentRef() == null) {
                        req.setOriginalPaymentRef(paidPayment.getProviderTransactionId() != null
                                ? paidPayment.getProviderTransactionId() : paidPayment.getPaymentLinkId());
                    }
                }
                long effectiveCap = PayoutEligibilityService.computeEffectivePaidCap(booking, paidPayment);
                long siblingCommitted = payoutEligibilityService != null
                        ? payoutEligibilityService.computeCommittedOrApprovedAmountExcluding(booking.getBookingId(), req.getId())
                        : (payoutRepository != null ? payoutRepository.sumCommittedOrInFlightAmountByBookingId(booking.getBookingId()) : 0L);
                if (siblingCommitted + cashVnd > effectiveCap) {
                    return new PayoutDispatchPrep(false, false, "SKIPPED_INELIGIBLE",
                            "Tổng số tiền hoàn trên đơn hàng vượt quá số tiền thực thu.", cashVnd);
                }
            }

            resolveAndAuditRefundMethodFallback(req, paidPaymentForFallback, admin);
            if (req.getRecipientSnapshotHash() != null && !req.getRecipientSnapshotHash().isBlank()) {
                verifyRecipientSnapshotOrThrow(req, cashVnd, admin.getUserId());
            } else {
                captureRecipientSnapshot(req, cashVnd);
            }

            if (refundLimitService != null) {
                refundLimitService.revalidateAndRolloverBeforePayout(
                        req, admin, cashVnd, limitOverrideApproved, limitOverrideReason);
            }

            LocalDateTime now = LocalDateTime.now();
            String cleanNote = (note != null && !note.isBlank()) ? note.trim() : null;
            req.setRequestStatus("PAYOUT_APPROVED");
            req.setPayoutStatus("PENDING");
            req.setPayoutDecidedBy(admin);
            req.setPayoutDecidedAt(now);
            if (cleanNote != null) {
                req.setPayoutDecisionNote(cleanNote);
            }
            refundRequestRepository.save(req);

            String histNote = (batchCode != null ? "[Batch " + batchCode + "] " : "")
                    + (sameVerifierAndApprover ? "[Single-Admin: same user verified Stage 1 and approved Stage 2] " : "")
                    + "Admin approved payout dispatch (" + cashVnd + " VND)"
                    + (cleanNote != null ? ": " + cleanNote : "");
            appendHistory(req, null, admin, "PAYOUT_APPROVED", prevStatus, "PAYOUT_APPROVED", histNote);

            RefundAuditLog audit = new RefundAuditLog();
            audit.setRefundRequestId(req.getId());
            audit.setEntityType("REFUND_REQUEST");
            audit.setEntityId(req.getId());
            audit.setEventType("PAYOUT_APPROVED");
            audit.setActorUserId(admin.getUserId());
            audit.setActorRole(admin.getRole());
            audit.setCinemaId(req.getCinema() != null ? req.getCinema().getCinemaId() : null);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("cashVnd", cashVnd);
            details.put("singleAdminSelfApproved", sameVerifierAndApprover);
            if (req.getReviewedBy() != null) {
                details.put("stage1VerifierUserId", req.getReviewedBy().getUserId());
            }
            if (batchCode != null) {
                details.put("batchCode", batchCode);
            }
            audit.setDetailsJson(toJson(details));
            auditLogRepository.save(audit);

            if (sameVerifierAndApprover) {
                RefundAuditLog singleAdminAudit = new RefundAuditLog();
                singleAdminAudit.setRefundRequestId(req.getId());
                singleAdminAudit.setEntityType("REFUND_REQUEST");
                singleAdminAudit.setEntityId(req.getId());
                singleAdminAudit.setEventType("SINGLE_ADMIN_PAYOUT_SELF_APPROVED");
                singleAdminAudit.setActorUserId(admin.getUserId());
                singleAdminAudit.setActorRole(admin.getRole());
                singleAdminAudit.setCinemaId(req.getCinema() != null ? req.getCinema().getCinemaId() : null);
                singleAdminAudit.setDetailsJson(toJson(Map.of(
                        "stage1VerifierUserId", admin.getUserId(),
                        "stage2ApproverUserId", admin.getUserId(),
                        "cashVnd", cashVnd)));
                auditLogRepository.save(singleAdminAudit);
            }

            return new PayoutDispatchPrep(true, false, "READY", "Approved for dispatch.", cashVnd);
        });
        } catch (SnapshotTamperAbort ex) {
            persistTamperAuditAfterRollback(ex);
            throw new BusinessException(HttpStatus.CONFLICT, "RECIPIENT_SNAPSHOT_TAMPERED",
                    "Immutable recipient bank snapshot mismatch detected; payout blocked to prevent unauthorized alteration.");
        }
    }

    /**
     * Stage 2 Rejection (Admin only): Rejects cash payout for an AWAITING_PAYOUT_APPROVAL or PAYOUT_FAILED request
     * with mandatory reason, recording actor, timestamp, history, audit log, and notification email.
     */
    public void rejectPayoutTx(Long refundRequestId, Long adminUserId, String reason) {
        if (adminUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Admin authentication required.");
        }
        String cleanReason = requireNonBlankNote(reason, "Payout rejection reason is mandatory.");

        PreLockInfo preLock = runInTx(readOnlyTxTemplate, () -> {
            User preAdmin = userRepository.findById(adminUserId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Actor not found."));
            if (!"ADMIN".equalsIgnoreCase(preAdmin.getRole())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_PAYOUT_ONLY", "Only Admin can reject cash payouts.");
            }
            RefundRequest lookup = refundRequestRepository.findById(refundRequestId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
            Long bId = lookup.getBooking().getBookingId();
            if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
                entityManager.detach(lookup);
            }
            return new PreLockInfo(bId);
        });

        runInTx(txTemplate, () -> {
            Booking booking = bookingRepository.findByIdForUpdate(preLock.bookingId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found."));
            RefundRequest req = refundRequestRepository.findByIdForUpdate(refundRequestId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
            List<RefundRequestItem> items = refundRequestItemRepository.findByRefundRequestIdForUpdate(refundRequestId);
            List<Ticket> allTickets = ticketRepository.findByBookingIdForUpdate(booking.getBookingId());
            Map<Long, Ticket> lockedTicketMap = new HashMap<>();
            for (Ticket t : allTickets) {
                lockedTicketMap.put(t.getTicketId(), t);
            }

            User admin = userRepository.findById(adminUserId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Actor not found."));
            if (!"ADMIN".equalsIgnoreCase(admin.getRole())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_PAYOUT_ONLY", "Only Admin can reject cash payouts.");
            }

            String prevStatus = upper(req.getRequestStatus());
            if (payoutRepository != null && payoutRepository.findByActiveRequestLock(refundRequestId).isPresent()
                    || Set.of("PENDING", "PROCESSING", "SUCCEEDED").contains(upper(req.getPayoutStatus()))) {
                throw new BusinessException(HttpStatus.CONFLICT, "PAYOUT_ALREADY_IN_PROGRESS_OR_COMPLETED",
                        "Cannot reject payout because a PayOS transaction is already active or succeeded.");
            }
            if (!Set.of("AWAITING_PAYOUT_APPROVAL", "PAYOUT_FAILED", "PAYOUT_BLOCKED").contains(prevStatus)) {
                throw new BusinessException(HttpStatus.CONFLICT, "INVALID_PAYOUT_REJECT_STATE",
                        "Only requests in AWAITING_PAYOUT_APPROVAL, PAYOUT_FAILED, or PAYOUT_BLOCKED can be rejected for payout (current: " + prevStatus + ").");
            }

            revertProvisionalApprovalAndVoucher(
                    booking, req, items, allTickets, lockedTicketMap,
                    "REJECTED", false, admin.getUserId(), cleanReason);
            if (refundLimitService != null) {
                refundLimitService.releaseReservationsOnFailureCancelOrReject(
                        req.getId(), "Payout rejected by Admin: " + cleanReason);
            }

            LocalDateTime now = LocalDateTime.now();
            req.setRequestStatus("PAYOUT_REJECTED");
            req.setPayoutStatus("REJECTED");
            req.setPayoutDecidedBy(admin);
            req.setPayoutDecidedAt(now);
            req.setPayoutDecisionNote(cleanReason);
            refundRequestItemRepository.saveAll(items);
            refundRequestRepository.save(req);

            appendHistory(req, null, admin, "PAYOUT_REJECTED", prevStatus, "PAYOUT_REJECTED", cleanReason);

            RefundAuditLog audit = new RefundAuditLog();
            audit.setRefundRequestId(req.getId());
            audit.setEntityType("REFUND_REQUEST");
            audit.setEntityId(req.getId());
            audit.setEventType("PAYOUT_REJECTED");
            audit.setActorUserId(admin.getUserId());
            audit.setActorRole(admin.getRole());
            audit.setCinemaId(req.getCinema() != null ? req.getCinema().getCinemaId() : null);
            audit.setDetailsJson(toJson(Map.of("reason", cleanReason)));
            auditLogRepository.save(audit);

            refundEmailService.enqueuePayoutRejectedEmail(req, cleanReason);
            return null;
        });
    }

    /**
     * Admin Hold-for-Reverify: Unlocks an AWAITING_PAYOUT_APPROVAL or PAYOUT_FAILED request back to MORE_INFO_REQUIRED
     * (ONLY if no payout is active/succeeded), reverts Stage 1 provisional item/ticket/food approval state so
     * customer can edit bank details and Manager/Admin can re-verify &amp; re-approve cleanly.
     */
    public void holdForBankReverifyTx(Long refundRequestId, Long adminUserId, String reason) {
        if (adminUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Admin authentication required.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "HOLD_REASON_REQUIRED",
                    "A mandatory reason is required when holding payout for bank re-verification.");
        }
        String cleanReason = reason.trim();

        PreLockInfo preLock = runInTx(readOnlyTxTemplate, () -> {
            User preAdmin = userRepository.findById(adminUserId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Actor not found."));
            if (!"ADMIN".equalsIgnoreCase(preAdmin.getRole())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_PAYOUT_ONLY",
                        "Only Admin can hold payouts for bank re-verification.");
            }
            RefundRequest lookup = refundRequestRepository.findById(refundRequestId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
            Long bId = lookup.getBooking().getBookingId();
            if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
                entityManager.detach(lookup);
            }
            return new PreLockInfo(bId);
        });

        runInTx(txTemplate, () -> {
            Booking booking = bookingRepository.findByIdForUpdate(preLock.bookingId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found."));
            RefundRequest req = refundRequestRepository.findByIdForUpdate(refundRequestId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
            List<RefundRequestItem> items = refundRequestItemRepository.findByRefundRequestIdForUpdate(refundRequestId);
            List<Ticket> allTickets = ticketRepository.findByBookingIdForUpdate(booking.getBookingId());
            Map<Long, Ticket> lockedTicketMap = new HashMap<>();
            for (Ticket t : allTickets) {
                lockedTicketMap.put(t.getTicketId(), t);
            }

            User admin = userRepository.findById(adminUserId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Actor not found."));
            if (!"ADMIN".equalsIgnoreCase(admin.getRole())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_PAYOUT_ONLY",
                        "Only Admin can hold payouts for bank re-verification.");
            }

            boolean hasActiveOrSucceededTx = (payoutRepository != null && payoutRepository.findByActiveRequestLock(refundRequestId).isPresent())
                    || Set.of("PENDING", "PROCESSING", "SUCCEEDED").contains(upper(req.getPayoutStatus()))
                    || (payoutRepository != null && payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(refundRequestId).stream()
                            .anyMatch(tx -> "SUCCEEDED".equalsIgnoreCase(tx.getStatus())));
            if (hasActiveOrSucceededTx) {
                throw new BusinessException(HttpStatus.CONFLICT, "PAYOUT_IN_PROGRESS_OR_COMPLETED",
                        "Cannot hold for bank re-verification because a PayOS payout is active or already succeeded.");
            }

            String prevStatus = upper(req.getRequestStatus());
            if (!Set.of("AWAITING_PAYOUT_APPROVAL", "PAYOUT_FAILED", "PAYOUT_BLOCKED").contains(prevStatus)) {
                throw new BusinessException(HttpStatus.CONFLICT, "INVALID_HOLD_STATE",
                        "Only requests in AWAITING_PAYOUT_APPROVAL, PAYOUT_FAILED, or PAYOUT_BLOCKED can be held for re-verification (current: " + prevStatus + ").");
            }

            revertProvisionalApprovalAndVoucher(
                    booking, req, items, allTickets, lockedTicketMap,
                    "MORE_INFO_REQUIRED", true, admin.getUserId(), cleanReason);
            if (refundLimitService != null) {
                refundLimitService.releaseReservationsOnFailureCancelOrReject(
                        req.getId(), "Held for bank re-verification: " + cleanReason);
            }

            LocalDateTime now = LocalDateTime.now();
            req.setRecipientSnapshotJson(null);
            req.setRecipientSnapshotHash(null);
            req.setPayoutBlockedReason(null);
            req.setFeeAssessmentStatus(null);
            req.setFeeAmountVnd(0L);
            req.setWorkflowStatus("UNDER_REVIEW");
            req.setRequestStatus("MORE_INFO_REQUIRED");
            req.setPayoutStatus("NOT_REQUIRED");
            req.setPayoutDecidedBy(admin);
            req.setPayoutDecidedAt(now);
            req.setPayoutDecisionNote(cleanReason);
            refundRequestItemRepository.saveAll(items);
            refundRequestRepository.save(req);

            RefundCommunication comm = new RefundCommunication();
            comm.setRefundRequest(req);
            comm.setSenderType("ADMIN");
            comm.setSenderUser(admin);
            comm.setSenderName(admin.getFullName());
            comm.setMessage(cleanReason);
            comm.setEmailDispatched(true);
            comm = communicationRepository.save(comm);

            appendHistory(req, null, admin, "PAYOUT_HELD_FOR_REVERIFY", prevStatus, "MORE_INFO_REQUIRED", cleanReason);

            RefundAuditLog audit = new RefundAuditLog();
            audit.setRefundRequestId(req.getId());
            audit.setEntityType("REFUND_REQUEST");
            audit.setEntityId(req.getId());
            audit.setEventType("PAYOUT_HELD_FOR_REVERIFY");
            audit.setActorUserId(admin.getUserId());
            audit.setActorRole(admin.getRole());
            audit.setCinemaId(req.getCinema() != null ? req.getCinema().getCinemaId() : null);
            audit.setDetailsJson(toJson(Map.of("reason", cleanReason)));
            auditLogRepository.save(audit);

            refundEmailService.enqueueBankReverifyEmail(req, comm.getId(), cleanReason);
            return null;
        });
    }

    /**
     * Reverts Stage 1 provisional Ticket / BookingFoodItem / BookingFoodItemComponent / BookingQr mutations
     * and atomically revokes any unredeemed Stage 1 recovery voucher (Resolution 2).
     */
    private void revertProvisionalApprovalAndVoucher(
            Booking booking,
            RefundRequest req,
            List<RefundRequestItem> items,
            List<Ticket> allTickets,
            Map<Long, Ticket> lockedTicketMap,
            String targetItemStatus,
            boolean keepActiveLocks,
            Long actorUserId,
            String reason) {
        LocalDateTime now = LocalDateTime.now();
        for (RefundRequestItem item : items) {
            if ("APPROVED".equalsIgnoreCase(item.getItemStatus())) {
                if ("TICKET".equalsIgnoreCase(item.getItemKind()) && item.getTicketId() != null) {
                    Ticket ticket = lockedTicketMap.get(item.getTicketId());
                    if (ticket != null && "REFUNDED".equalsIgnoreCase(ticket.getTicketStatus())) {
                        if ("PAID_SEAT_CONFLICT".equalsIgnoreCase(booking.getBookingStatus()) && ticket.getCheckedInAt() == null) {
                            ticket.setTicketStatus("CONFLICT_UNISSUED");
                        } else {
                            ticket.setTicketStatus(ticket.getCheckedInAt() != null ? "USED" : "VALID");
                        }
                        ticket.setRefundStatus("NONE");
                        ticketRepository.save(ticket);
                    }
                    item.setActiveLockKey(keepActiveLocks ? ("TICKET:" + item.getTicketId()) : null);
                } else if ("FOOD_ITEM".equalsIgnoreCase(item.getItemKind()) && item.getBookingFoodItemId() != null) {
                    BookingFoodItem bfi = bookingFoodItemRepository.findByIdForUpdate(item.getBookingFoodItemId()).orElse(null);
                    if (bfi != null) {
                        bfi.setRefundedQuantity(Math.max(0, bfi.getRefundedQuantity() - item.getApprovedQuantity()));
                        if ("REFUNDED".equalsIgnoreCase(bfi.getDeliveryStatus()) && bfi.getRefundedQuantity() < bfi.getQuantity()) {
                            bfi.setDeliveryStatus(bfi.getDeliveredQuantity() >= bfi.getQuantity()
                                    ? "DELIVERED"
                                    : (bfi.getDeliveredQuantity() > 0 ? "PARTIAL" : "PENDING"));
                        }
                        bookingFoodItemRepository.save(bfi);
                    }
                    item.setActiveLockKey(keepActiveLocks ? ("FOOD:" + item.getBookingFoodItemId() + ":" + req.getId()) : null);
                } else if ("COMBO_COMPONENT".equalsIgnoreCase(item.getItemKind()) && item.getBookingFoodItemComponentId() != null) {
                    BookingFoodItemComponent comp = componentRepository.findByIdForUpdate(item.getBookingFoodItemComponentId()).orElse(null);
                    if (comp != null) {
                        comp.setRefundedQuantity(Math.max(0, comp.getRefundedQuantity() - item.getApprovedQuantity()));
                        if ("REFUNDED".equalsIgnoreCase(comp.getDeliveryStatus()) && comp.getRefundedQuantity() < comp.getTotalQuantity()) {
                            comp.setDeliveryStatus(comp.getDeliveredQuantity() >= comp.getTotalQuantity()
                                    ? "DELIVERED"
                                    : (comp.getDeliveredQuantity() > 0 ? "PARTIAL" : "PENDING"));
                        }
                        componentRepository.save(comp);
                    }
                    item.setActiveLockKey(keepActiveLocks ? ("COMP:" + item.getBookingFoodItemComponentId() + ":" + req.getId()) : null);
                }
                item.setItemStatus(targetItemStatus);
                item.setApprovedQuantity(0);
                item.setApprovedCashAmount(BigDecimal.ZERO);
                item.setApprovedVoucherAmount(BigDecimal.ZERO);
                item.setDecisionReason(keepActiveLocks ? null : reason);
                item.setDecidedBy(keepActiveLocks ? null : item.getDecidedBy());
                item.setDecidedAt(keepActiveLocks ? null : now);
            } else if (!keepActiveLocks) {
                item.setActiveLockKey(null);
                if (!"REJECTED".equalsIgnoreCase(item.getItemStatus())) {
                    item.setItemStatus(targetItemStatus);
                }
            } else if ("PENDING".equalsIgnoreCase(item.getItemStatus())) {
                item.setItemStatus(targetItemStatus);
            }
        }

        if (!allTickets.isEmpty() && allTickets.stream().anyMatch(t -> !"REFUNDED".equalsIgnoreCase(t.getTicketStatus()))) {
            bookingQrRepository.findByBookingIdForUpdate(booking.getBookingId()).ifPresent(qr -> {
                if ("REVOKED".equalsIgnoreCase(qr.getStatus())) {
                    qr.setStatus(allTickets.stream().anyMatch(t -> t.getCheckedInAt() != null) ? "USED" : "ACTIVE");
                    bookingQrRepository.save(qr);
                }
            });
        }

        boolean allRevokedOrUnused = voucherRecoveryService == null
                || voucherRecoveryService.revokeRecoveryVoucherInCurrentTx(req.getId(), actorUserId, reason);
        req.setApprovedCashTotal(BigDecimal.ZERO);
        if (allRevokedOrUnused) {
            req.setVoucherStatus("NOT_REQUIRED");
            req.setApprovedVoucherTotal(BigDecimal.ZERO);
        }
    }

    /**
     * Synchronizes BookingSeat status/activeSeatKey, releases future showtime_seats, and updates
     * Booking.bookingStatus once a RefundRequest reaches terminal completion.
     */
    public void syncBookingAndSeatsOnTerminalRefund(Long refundRequestId) {
        if (refundRequestId == null) {
            return;
        }
        Long bookingId = runInTx(readOnlyTxTemplate, () -> refundRequestRepository.findById(refundRequestId)
                .map(r -> r.getBooking() != null ? r.getBooking().getBookingId() : null)
                .orElse(null));
        if (bookingId == null) {
            return;
        }
        runInTx(txTemplate, () -> {
            Booking booking = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
            RefundRequest req = refundRequestRepository.findByIdForUpdate(refundRequestId).orElse(null);
            if (booking == null || req == null) {
                return null;
            }
            String rs = upper(req.getRequestStatus());
            String ps = upper(req.getPayoutStatus());
            boolean cashTerminal = "COMPLETED".equals(rs) || "PAYOUT_SUCCEEDED".equals(rs) || "SUCCEEDED".equals(ps);
            boolean voucherOnlyTerminal = ("APPROVED".equals(rs) || "PARTIALLY_APPROVED".equals(rs)) && "NOT_REQUIRED".equals(ps);
            if (!cashTerminal && !voucherOnlyTerminal) {
                return null;
            }

            List<RefundRequestItem> items = refundRequestItemRepository.findByRefundRequest_IdOrderByIdAsc(refundRequestId);
            List<Ticket> allTickets = ticketRepository.findByBookingIdForUpdate(bookingId);
            List<BookingFoodItem> foodItems = bookingFoodItemRepository.findByBooking_BookingId(bookingId);

            if (bookingSeatRepository != null) {
                Map<Long, BookingSeat> seatMap = new HashMap<>();
                for (BookingSeat bs : bookingSeatRepository.findByBooking_BookingId(bookingId)) {
                    seatMap.put(bs.getBookingSeatId(), bs);
                }
                boolean isFutureShowtime = booking.getShowtime() != null
                        && booking.getShowtime().getStartAt() != null
                        && booking.getShowtime().getStartAt().isAfter(LocalDateTime.now());
                for (RefundRequestItem item : items) {
                    if ("APPROVED".equalsIgnoreCase(item.getItemStatus())
                            && "TICKET".equalsIgnoreCase(item.getItemKind())
                            && item.getBookingSeatId() != null) {
                        BookingSeat bs = seatMap.get(item.getBookingSeatId());
                        if (bs != null) {
                            if (!"REFUNDED".equalsIgnoreCase(bs.getStatus()) || bs.getActiveSeatKey() != null) {
                                bs.setStatus("REFUNDED");
                                bs.setActiveSeatKey(null);
                                bookingSeatRepository.save(bs);
                            }
                            if (isFutureShowtime && bs.getShowtimeSeatId() != null && jdbcTemplate != null) {
                                jdbcTemplate.update(
                                        "UPDATE showtime_seats SET seat_status = 'AVAILABLE', hold_token_hash = NULL, "
                                                + "held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL "
                                                + "WHERE showtime_seat_id = ? AND seat_status = 'BOOKED'",
                                        bs.getShowtimeSeatId());
                            }
                        }
                    }
                }
            }

            boolean hasAnyItem = !allTickets.isEmpty() || !foodItems.isEmpty();
            boolean allTicketsRefunded = allTickets.stream()
                    .allMatch(t -> "REFUNDED".equalsIgnoreCase(t.getTicketStatus()) || "REFUNDED".equalsIgnoreCase(t.getRefundStatus()));
            boolean anyValidTicketAwaitingCheckin = allTickets.stream()
                    .anyMatch(t -> "VALID".equalsIgnoreCase(t.getTicketStatus()) && !"REFUNDED".equalsIgnoreCase(t.getRefundStatus()));
            boolean allFoodRefunded = foodItems.stream()
                    .allMatch(f -> (f.getRefundedQuantity() + f.getReplacementAcceptedQuantity()) >= f.getQuantity());

            if (hasAnyItem && allTicketsRefunded && allFoodRefunded) {
                if (!"REFUNDED".equalsIgnoreCase(booking.getBookingStatus())) {
                    booking.setBookingStatus("REFUNDED");
                    bookingRepository.save(booking);
                }
            } else if (!anyValidTicketAwaitingCheckin
                    && !"REFUNDED".equalsIgnoreCase(booking.getBookingStatus())
                    && !"PARTIALLY_REFUNDED".equalsIgnoreCase(booking.getBookingStatus())) {
                booking.setBookingStatus("PARTIALLY_REFUNDED");
                bookingRepository.save(booking);
            }
            return null;
        });
    }

    private boolean hasCannotFulfillEvidence(Long bookingId, Long bfiId, Long compId, String deliveryStatus) {
        if ("ISSUE_REPORTED".equalsIgnoreCase(deliveryStatus)) {
            return true;
        }
        if (foodFulfillmentHistoryRepository == null || bookingId == null) {
            return false;
        }
        for (FoodFulfillmentHistory h : foodFulfillmentHistoryRepository.findByBooking_BookingIdOrderByCreatedAtDesc(bookingId)) {
            if (!"CANNOT_FULFILL".equalsIgnoreCase(h.getActionType())) {
                continue;
            }
            if (compId != null && compId.equals(h.getBookingFoodItemComponentId())) {
                return true;
            }
            if (compId == null && bfiId != null && bfiId.equals(h.getBookingFoodItemId())) {
                return true;
            }
            if (h.getBookingFoodItemId() == null && h.getBookingFoodItemComponentId() == null) {
                return true;
            }
        }
        return false;
    }

    private void enforceBranchIsolation(User actor, RefundRequest req) {
        if (!"ADMIN".equalsIgnoreCase(actor.getRole())) {
            Long userCinemaId = actor.getCinemaId();
            Long reqCinemaId = req.getCinema() != null ? req.getCinema().getCinemaId() : null;
            if (userCinemaId == null || !userCinemaId.equals(reqCinemaId)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "BRANCH_ACCESS_DENIED", "Access denied for this cinema branch.");
            }
        }
    }

    private static String requireNonBlankNote(String note, String message) {
        if (note == null || note.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NOTE_REQUIRED", message);
        }
        return note.trim();
    }

    private static String upper(String s) {
        return s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
    }

    long[] computePriorFoodRefundedTotals(Long bookingId, Long bfiId, Long compId) {
        long cash = 0L;
        long voucher = 0L;
        List<RefundRequest> requests = refundRequestRepository.findByBooking_BookingIdOrderByCreatedAtDesc(bookingId);
        for (RefundRequest rr : requests) {
            for (RefundRequestItem item : refundRequestItemRepository.findByRefundRequest_IdOrderByIdAsc(rr.getId())) {
                if (!"APPROVED".equalsIgnoreCase(item.getItemStatus())) continue;
                if (bfiId != null && "FOOD_ITEM".equalsIgnoreCase(item.getItemKind()) && bfiId.equals(item.getBookingFoodItemId())) {
                    cash += PurchaseSnapshotService.toVndLong(item.getApprovedCashAmount());
                    voucher += PurchaseSnapshotService.toVndLong(item.getApprovedVoucherAmount());
                } else if (compId != null && "COMBO_COMPONENT".equalsIgnoreCase(item.getItemKind()) && compId.equals(item.getBookingFoodItemComponentId())) {
                    cash += PurchaseSnapshotService.toVndLong(item.getApprovedCashAmount());
                    voucher += PurchaseSnapshotService.toVndLong(item.getApprovedVoucherAmount());
                }
            }
        }
        return new long[]{cash, voucher};
    }

    private long computeTotalApprovedCashForBookingExcluding(Long bookingId, Long excludeRequestId) {
        long sum = 0L;
        for (RefundRequest rr : refundRequestRepository.findByBooking_BookingIdOrderByCreatedAtDesc(bookingId)) {
            if (!rr.getId().equals(excludeRequestId)
                    && !"CANCELLED".equalsIgnoreCase(rr.getRequestStatus())
                    && !"PAYOUT_REJECTED".equalsIgnoreCase(rr.getRequestStatus())
                    && rr.getApprovedCashTotal() != null) {
                sum += PurchaseSnapshotService.toVndLong(rr.getApprovedCashTotal());
            }
        }
        return sum;
    }

    private void appendHistory(RefundRequest req, Long itemId, User actor, String action, String prev, String next, String note) {
        RefundApprovalHistory h = new RefundApprovalHistory();
        h.setRefundRequest(req);
        h.setRefundRequestItemId(itemId);
        h.setActorUser(actor);
        h.setActorRole(actor.getRole());
        h.setAction(action);
        h.setPreviousStatus(prev);
        h.setNewStatus(next);
        h.setNote(note);
        historyRepository.save(h);
    }

    private void recordAudit(RefundRequest req, User actor, String eventType) {
        RefundAuditLog log = new RefundAuditLog();
        log.setRefundRequestId(req.getId());
        log.setEntityType("REFUND_REQUEST");
        log.setEntityId(req.getId());
        log.setEventType(eventType);
        log.setActorUserId(actor.getUserId());
        log.setActorRole(actor.getRole());
        log.setCinemaId(req.getCinema() != null ? req.getCinema().getCinemaId() : null);
        log.setDetailsJson(toJson(Map.of(
                "cashApproved", req.getApprovedCashTotal().longValue(),
                "voucherApproved", req.getApprovedVoucherTotal().longValue()
        )));
        auditLogRepository.save(log);
    }

    public RefundRequest grantDeadlineExceptionTx(Long refundRequestId, String reason, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required.");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DEADLINE_EXCEPTION_REASON_REQUIRED",
                    "A mandatory reason is required to grant a refund deadline exception.");
        }
        String cleanReason = reason.trim();
        PreLockInfo preLock = runInTx(readOnlyTxTemplate, () -> {
            User actor = userRepository.findById(actorUserId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Actor not found."));
            if (!"ADMIN".equalsIgnoreCase(actor.getRole())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_DEADLINE_EXCEPTION_ONLY",
                        "Only an ADMIN can grant a refund deadline exception.");
            }
            RefundRequest lookup = refundRequestRepository.findById(refundRequestId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
            Long bId = lookup.getBooking().getBookingId();
            if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
                entityManager.detach(lookup);
            }
            return new PreLockInfo(bId);
        });

        return runInTx(txTemplate, () -> {
            bookingRepository.findByIdForUpdate(preLock.bookingId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found."));
            RefundRequest req = refundRequestRepository.findByIdForUpdate(refundRequestId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
            User admin = userRepository.findById(actorUserId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Actor not found."));
            if (!"ADMIN".equalsIgnoreCase(admin.getRole())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_DEADLINE_EXCEPTION_ONLY",
                        "Only an ADMIN can grant a refund deadline exception.");
            }
            LocalDateTime now = refundDeadlineService != null ? refundDeadlineService.now() : LocalDateTime.now();
            req.setDeadlineExceptionBy(admin.getUserId());
            req.setDeadlineExceptionReason(cleanReason);
            req.setDeadlineExceptionAt(now);
            req = refundRequestRepository.save(req);

            appendHistory(req, null, admin, "DEADLINE_EXCEPTION_GRANTED", req.getRequestStatus(), req.getRequestStatus(), cleanReason);
            recordDeadlineExceptionAudit(req, admin, cleanReason);
            return req;
        });
    }

    private void recordDeadlineExceptionAudit(RefundRequest req, User admin, String reason) {
        RefundAuditLog log = new RefundAuditLog();
        log.setRefundRequestId(req.getId());
        log.setEntityType("REFUND_REQUEST");
        log.setEntityId(req.getId());
        log.setEventType("REFUND_DEADLINE_EXCEPTION_GRANTED");
        log.setActorUserId(admin.getUserId());
        log.setActorRole(admin.getRole());
        log.setCinemaId(req.getCinema() != null ? req.getCinema().getCinemaId() : null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason);
        if (req.getRefundDeadlineAt() != null) {
            details.put("refundDeadlineAt", req.getRefundDeadlineAt().toString());
        }
        if (req.getDeadlineBasis() != null) {
            details.put("deadlineBasis", req.getDeadlineBasis());
        }
        log.setDetailsJson(toJson(details));
        auditLogRepository.save(log);
    }

    public static String buildCanonicalRecipientSnapshotJson(RefundRequest req, long approvedAmountVnd, String resolvedAccountHash) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("accountHolderName", req.getAccountHolderName() != null ? req.getAccountHolderName().trim() : "");
        snap.put("approvedAmountVnd", approvedAmountVnd);
        snap.put("bankBin", req.getToBin() != null ? req.getToBin().trim() : "");
        snap.put("bankName", req.getToBankName() != null ? req.getToBankName().trim() : "");
        snap.put("refundRequestId", req.getId());
        snap.put("requestCode", req.getRequestCode() != null ? req.getRequestCode().trim() : "");
        snap.put("toAccountNumberHash", resolvedAccountHash != null ? resolvedAccountHash.trim().toLowerCase(Locale.ROOT) : "");
        snap.put("toAccountNumberMasked", req.getToAccountNumberMasked() != null ? req.getToAccountNumberMasked().trim() : "");
        return toJson(snap);
    }

    public void captureRecipientSnapshot(RefundRequest req, long approvedAmountVnd) {
        if (req == null) return;
        String resolvedHash = resolveEffectiveAccountHash(req);
        if (resolvedHash != null && (req.getToAccountNumberHash() == null || req.getToAccountNumberHash().isBlank())) {
            req.setToAccountNumberHash(resolvedHash);
        }
        String json = buildCanonicalRecipientSnapshotJson(req, approvedAmountVnd, resolvedHash);
        req.setRecipientSnapshotJson(json);
        req.setRecipientSnapshotHash(CryptoUtils.sha256(json));
    }

    public void verifyRecipientSnapshotOrThrow(RefundRequest req, long approvedAmountVnd, Long actorUserId) {
        if (req == null || req.getRecipientSnapshotHash() == null || req.getRecipientSnapshotHash().isBlank()) {
            return;
        }
        String resolvedHash = resolveEffectiveAccountHash(req);
        String currentJson = buildCanonicalRecipientSnapshotJson(req, approvedAmountVnd, resolvedHash);
        String currentHash = CryptoUtils.sha256(currentJson);
        String storedHash = req.getRecipientSnapshotHash().trim();
        String storedJson = req.getRecipientSnapshotJson();

        boolean hashMatches = storedHash.equalsIgnoreCase(currentHash);
        boolean jsonMatches = storedJson == null || storedJson.isBlank()
                || CryptoUtils.sha256(storedJson).equalsIgnoreCase(storedHash);
        boolean encryptedHashMatches = true;
        if (bankEncryptionService != null && req.getToAccountNumberEncrypted() != null && !req.getToAccountNumberEncrypted().isBlank()) {
            try {
                String plain = bankEncryptionService.decryptAccountNumber(req.getToAccountNumberEncrypted()).replaceAll("\\s+", "");
                String computedFromEnc = bankEncryptionService.hashAccountNumber(plain);
                if (resolvedHash != null && !resolvedHash.isBlank()) {
                    encryptedHashMatches = computedFromEnc.equalsIgnoreCase(resolvedHash);
                }
            } catch (RuntimeException ex) {
                encryptedHashMatches = false;
            }
        }

        if (!hashMatches || !jsonMatches || !encryptedHashMatches) {
            Long cinemaId = req.getCinema() != null ? req.getCinema().getCinemaId() : null;
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("storedHash", storedHash);
            details.put("currentHash", currentHash);
            details.put("encryptedHashMatches", encryptedHashMatches);
            SnapshotTamperAbort abort = new SnapshotTamperAbort(req.getId(), cinemaId, actorUserId, toJson(details));
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw abort;
            }
            persistTamperAuditAfterRollback(abort);
            throw new BusinessException(HttpStatus.CONFLICT, "RECIPIENT_SNAPSHOT_TAMPERED",
                    "Immutable recipient bank snapshot mismatch detected; payout blocked to prevent unauthorized alteration.");
        }
    }

    private static final class SnapshotTamperAbort extends RuntimeException {
        private final Long refundRequestId;
        private final Long cinemaId;
        private final Long actorUserId;
        private final String detailsJson;

        private SnapshotTamperAbort(Long refundRequestId, Long cinemaId, Long actorUserId, String detailsJson) {
            super("RECIPIENT_SNAPSHOT_TAMPERED");
            this.refundRequestId = refundRequestId;
            this.cinemaId = cinemaId;
            this.actorUserId = actorUserId;
            this.detailsJson = detailsJson;
        }
    }

    private void persistTamperAuditAfterRollback(SnapshotTamperAbort abort) {
        if (auditLogRepository == null || abort == null) {
            return;
        }
        Runnable saveAction = () -> {
            RefundAuditLog audit = new RefundAuditLog();
            audit.setRefundRequestId(abort.refundRequestId);
            audit.setEntityType("REFUND_REQUEST");
            audit.setEntityId(abort.refundRequestId);
            audit.setEventType("RECIPIENT_SNAPSHOT_TAMPERED");
            audit.setActorUserId(abort.actorUserId);
            audit.setCinemaId(abort.cinemaId);
            audit.setDetailsJson(abort.detailsJson);
            auditLogRepository.save(audit);
        };
        if (requiresNewTxTemplate != null) {
            requiresNewTxTemplate.executeWithoutResult(status -> saveAction.run());
        } else {
            saveAction.run();
        }
    }

    private String resolveEffectiveAccountHash(RefundRequest req) {
        if (req.getToAccountNumberHash() != null && !req.getToAccountNumberHash().isBlank()) {
            return req.getToAccountNumberHash().trim().toLowerCase(Locale.ROOT);
        }
        if (bankEncryptionService != null && req.getToAccountNumberEncrypted() != null && !req.getToAccountNumberEncrypted().isBlank()) {
            try {
                String plain = bankEncryptionService.decryptAccountNumber(req.getToAccountNumberEncrypted()).replaceAll("\\s+", "");
                return bankEncryptionService.hashAccountNumber(plain).toLowerCase(Locale.ROOT);
            } catch (RuntimeException ignored) {
                return "";
            }
        }
        return "";
    }

    private void resolveAndAuditRefundMethodFallback(RefundRequest req, Payment paidPayment, User actor) {
        if (req == null) return;
        boolean supportsOriginal = payoutProviderAdapter != null
                && payoutProviderAdapter.supportsOriginalPaymentReversal(paidPayment);
        if (supportsOriginal) {
            req.setRefundMethod(RefundMethod.ORIGINAL_PAYMENT_METHOD.name());
            return;
        }
        req.setRefundMethod(RefundMethod.BANK_PAYOUT.name());
        String fallbackReason = payoutProviderAdapter != null
                ? payoutProviderAdapter.getOriginalPaymentFallbackReason(paidPayment)
                : "Original payment channel (PayOS VietQR) does not support direct reversal; fell back to BANK_PAYOUT.";
        RefundAuditLog audit = new RefundAuditLog();
        audit.setRefundRequestId(req.getId());
        audit.setEntityType("REFUND_REQUEST");
        audit.setEntityId(req.getId());
        audit.setEventType("REFUND_METHOD_FALLBACK_SELECTED");
        if (actor != null) {
            audit.setActorUserId(actor.getUserId());
            audit.setActorRole(actor.getRole());
        }
        audit.setCinemaId(req.getCinema() != null ? req.getCinema().getCinemaId() : null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("refundMethod", RefundMethod.BANK_PAYOUT.name());
        if (req.getOriginalPaymentId() != null) {
            details.put("originalPaymentId", req.getOriginalPaymentId());
        }
        if (req.getOriginalPaymentRef() != null) {
            details.put("originalPaymentRef", req.getOriginalPaymentRef());
        }
        details.put("reason", fallbackReason);
        audit.setDetailsJson(toJson(details));
        auditLogRepository.save(audit);
    }

    private static String toJson(Map<String, ?> map) {
        try {
            return JSON_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
