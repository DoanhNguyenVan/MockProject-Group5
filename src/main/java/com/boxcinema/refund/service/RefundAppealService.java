package com.boxcinema.refund.service;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.enums.RefundAppealStatus;
import com.boxcinema.common.enums.RefundWorkflowState;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.*;
import com.boxcinema.refund.dto.RefundAppealDecisionRequestDto;
import com.boxcinema.refund.dto.RefundAppealResponseDto;
import com.boxcinema.refund.dto.RefundAppealSubmitRequestDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Stage 2 Separate Customer Refund Appeal Service.
 *
 * Enforces:
 * - Appeals exist ONLY in refund_appeals + refund_appeal_history (never in RefundWorkflowState).
 * - Customer can submit an appeal ONLY when RefundRequest is in REJECTED state.
 * - At most ONE open appeal per RefundRequest at a time, enforced by active_appeal_lock UNIQUE constraint.
 * - Appeal states: APPEAL_SUBMITTED -> APPEAL_UNDER_REVIEW -> APPEAL_ACCEPTED / APPEAL_REJECTED.
 * - MANAGER is cinemaId-scoped (403 cross-cinema); ADMIN is system-wide; CUSTOMER is blocked from reviewing (403).
 * - When APPEAL_ACCEPTED: active_appeal_lock is cleared and original RefundRequest transitions back to UNDER_REVIEW
 *   (requestStatus = UNDER_VERIFICATION, workflowStatus = UNDER_REVIEW) without auto-approving or executing payout.
 * - When APPEAL_REJECTED: active_appeal_lock is cleared and original RefundRequest remains REJECTED.
 */
@Service
public class RefundAppealService {

    private static final Set<String> REJECTED_REQUEST_STATUSES = Set.of(
            "REJECTED", "PAYOUT_REJECTED", "INELIGIBLE"
    );

    private static final Set<String> OPEN_APPEAL_STATUSES = Set.of(
            RefundAppealStatus.APPEAL_SUBMITTED.name(),
            RefundAppealStatus.APPEAL_UNDER_REVIEW.name()
    );

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final RefundAppealRepository appealRepository;
    private final RefundAppealHistoryRepository appealHistoryRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final RefundRequestItemRepository refundRequestItemRepository;
    private final RefundApprovalHistoryRepository approvalHistoryRepository;
    private final RefundAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    @Autowired
    public RefundAppealService(RefundAppealRepository appealRepository,
                               RefundAppealHistoryRepository appealHistoryRepository,
                               RefundRequestRepository refundRequestRepository,
                               RefundRequestItemRepository refundRequestItemRepository,
                               RefundApprovalHistoryRepository approvalHistoryRepository,
                               RefundAuditLogRepository auditLogRepository,
                               UserRepository userRepository) {
        this.appealRepository = appealRepository;
        this.appealHistoryRepository = appealHistoryRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.refundRequestItemRepository = refundRequestItemRepository;
        this.approvalHistoryRepository = approvalHistoryRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public RefundAppealResponseDto submitCustomerAppeal(Long refundRequestId,
                                                        RefundAppealSubmitRequestDto dto,
                                                        Long authenticatedUserId) {
        if (refundRequestId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REFUND_ID_REQUIRED", "Refund request ID is required.");
        }
        if (dto == null || dto.getAppealReason() == null || dto.getAppealReason().trim().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "APPEAL_REASON_REQUIRED",
                    "A mandatory appeal reason is required to submit a refund appeal.");
        }

        RefundRequest req = refundRequestRepository.findByIdForUpdate(refundRequestId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND",
                        "Refund request not found: " + refundRequestId));

        verifyCustomerOwnership(req, authenticatedUserId, dto.getPurchaseEmail());

        String wfStatus = req.getWorkflowStatus() != null ? req.getWorkflowStatus().trim().toUpperCase(Locale.ROOT) : "";
        String reqStatus = req.getRequestStatus() != null ? req.getRequestStatus().trim().toUpperCase(Locale.ROOT) : "";
        boolean isRejected = RefundWorkflowState.REJECTED.name().equals(wfStatus)
                || REJECTED_REQUEST_STATUSES.contains(reqStatus);
        if (!isRejected) {
            throw new BusinessException(HttpStatus.CONFLICT, "APPEAL_ONLY_ALLOWED_WHEN_REJECTED",
                    "A refund appeal can only be submitted when the refund request is in REJECTED state (current status: "
                            + wfStatus + " / " + reqStatus + ").");
        }

        if (appealRepository.findByActiveAppealLock(req.getId()).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT, "APPEAL_ALREADY_OPEN",
                    "An active appeal is already open for refund request " + req.getRequestCode() + ".");
        }

        User customerUser = null;
        if (authenticatedUserId != null) {
            customerUser = userRepository.findById(authenticatedUserId).orElse(req.getCustomerUser());
        } else {
            customerUser = req.getCustomerUser();
        }

        LocalDateTime now = LocalDateTime.now();
        int existingCount = appealRepository.findByRefundRequestIdOrderBySubmittedAtDesc(req.getId()).size();
        String appealCode = "APL-" + req.getRequestCode() + "-" + (existingCount + 1);
        if (appealRepository.findByAppealCode(appealCode).isPresent()) {
            appealCode = appealCode + "-" + (System.currentTimeMillis() % 10000);
        }

        RefundAppeal appeal = new RefundAppeal();
        appeal.setAppealCode(appealCode);
        appeal.setRefundRequest(req);
        appeal.setCustomerUser(customerUser);
        appeal.setPurchaseEmail(req.getPurchaseEmail() != null
                ? req.getPurchaseEmail()
                : (dto.getPurchaseEmail() != null ? dto.getPurchaseEmail().trim() : ""));
        appeal.setAppealReason(dto.getAppealReason().trim());
        appeal.setEvidenceUrl(dto.getEvidenceUrl() != null && !dto.getEvidenceUrl().trim().isEmpty()
                ? dto.getEvidenceUrl().trim()
                : null);
        appeal.setStatus(RefundAppealStatus.APPEAL_SUBMITTED.name());
        appeal.setActiveAppealLock(req.getId());
        appeal.setSubmittedAt(now);
        appeal.setUpdatedAt(now);

        try {
            appeal = appealRepository.saveAndFlush(appeal);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(HttpStatus.CONFLICT, "APPEAL_ALREADY_OPEN",
                    "An active appeal is already open for refund request " + req.getRequestCode() + ".");
        }

        Long actorId = customerUser != null ? customerUser.getUserId() : authenticatedUserId;
        recordAppealHistory(appeal, actorId, "CUSTOMER", "APPEAL_SUBMITTED",
                null, RefundAppealStatus.APPEAL_SUBMITTED.name(), appeal.getAppealReason(), now);

        appendRefundApprovalHistory(req, customerUser, "CUSTOMER", "CUSTOMER_APPEAL_SUBMITTED",
                req.getRequestStatus(), req.getRequestStatus(),
                "Appeal " + appeal.getAppealCode() + " submitted: " + appeal.getAppealReason(), now);

        recordAuditLog(req, appeal, actorId, "CUSTOMER", "REFUND_APPEAL_SUBMITTED",
                Map.of("appealCode", appeal.getAppealCode(),
                        "status", appeal.getStatus(),
                        "appealReason", appeal.getAppealReason()));

        return toResponseDto(appeal);
    }

    @Transactional
    public RefundAppealResponseDto startAppealReview(Long appealId, Long reviewerUserId) {
        User reviewer = requireManagerOrAdmin(reviewerUserId);
        RefundAppeal appeal = appealRepository.findByIdForUpdate(appealId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "APPEAL_NOT_FOUND",
                        "Refund appeal not found: " + appealId));
        enforceCinemaScopeForManager(reviewer, appeal.getRefundRequest());

        String prevStatus = appeal.getStatus();
        if (!OPEN_APPEAL_STATUSES.contains(prevStatus)) {
            throw new BusinessException(HttpStatus.CONFLICT, "APPEAL_ALREADY_DECIDED",
                    "Cannot start review on an already-decided appeal (" + prevStatus + ").");
        }

        if (RefundAppealStatus.APPEAL_SUBMITTED.name().equals(prevStatus)) {
            LocalDateTime now = LocalDateTime.now();
            appeal.setStatus(RefundAppealStatus.APPEAL_UNDER_REVIEW.name());
            appeal.setReviewedBy(reviewer);
            appeal.setUpdatedAt(now);
            appealRepository.saveAndFlush(appeal);

            recordAppealHistory(appeal, reviewer.getUserId(), reviewer.getRole(),
                    "APPEAL_REVIEW_STARTED", prevStatus, RefundAppealStatus.APPEAL_UNDER_REVIEW.name(),
                    "Appeal taken under review by " + reviewer.getRole(), now);

            recordAuditLog(appeal.getRefundRequest(), appeal, reviewer.getUserId(), reviewer.getRole(),
                    "REFUND_APPEAL_UNDER_REVIEW",
                    Map.of("appealCode", appeal.getAppealCode(),
                            "previousStatus", prevStatus,
                            "newStatus", appeal.getStatus()));
        }

        return toResponseDto(appeal);
    }

    @Transactional
    public RefundAppealResponseDto decideAppeal(Long appealId,
                                                RefundAppealDecisionRequestDto dto,
                                                Long reviewerUserId) {
        User reviewer = requireManagerOrAdmin(reviewerUserId);
        if (dto == null || dto.getDecision() == null || dto.getDecision().trim().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "APPEAL_DECISION_REQUIRED",
                    "Appeal decision (ACCEPT or REJECT) is required.");
        }
        if (dto.getDecisionNote() == null || dto.getDecisionNote().trim().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "APPEAL_DECISION_NOTE_REQUIRED",
                    "A mandatory decisionNote is required when accepting or rejecting a refund appeal.");
        }
        String cleanNote = dto.getDecisionNote().trim();
        RefundAppealStatus targetStatus = parseTargetDecision(dto.getDecision());

        RefundAppeal appeal = appealRepository.findByIdForUpdate(appealId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "APPEAL_NOT_FOUND",
                        "Refund appeal not found: " + appealId));
        RefundRequest req = refundRequestRepository.findByIdForUpdate(appeal.getRefundRequest().getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND",
                        "Refund request not found: " + appeal.getRefundRequest().getId()));
        enforceCinemaScopeForManager(reviewer, req);

        String prevAppealStatus = appeal.getStatus();
        if (!OPEN_APPEAL_STATUSES.contains(prevAppealStatus)) {
            throw new BusinessException(HttpStatus.CONFLICT, "APPEAL_ALREADY_DECIDED",
                    "Appeal " + appeal.getAppealCode() + " has already been decided (" + prevAppealStatus + ").");
        }

        LocalDateTime now = LocalDateTime.now();
        // If appeal was still in APPEAL_SUBMITTED, record intermediate transition to APPEAL_UNDER_REVIEW in history
        if (RefundAppealStatus.APPEAL_SUBMITTED.name().equals(prevAppealStatus)) {
            recordAppealHistory(appeal, reviewer.getUserId(), reviewer.getRole(),
                    "APPEAL_REVIEW_STARTED", RefundAppealStatus.APPEAL_SUBMITTED.name(),
                    RefundAppealStatus.APPEAL_UNDER_REVIEW.name(), "Appeal review started prior to decision", now);
            prevAppealStatus = RefundAppealStatus.APPEAL_UNDER_REVIEW.name();
        }

        appeal.setStatus(targetStatus.name());
        appeal.setActiveAppealLock(null);
        appeal.setReviewedBy(reviewer);
        appeal.setReviewedAt(now);
        appeal.setDecisionNote(cleanNote);
        appeal.setUpdatedAt(now);
        appealRepository.saveAndFlush(appeal);

        String prevReqStatus = req.getRequestStatus();
        String prevWfStatus = req.getWorkflowStatus();

        if (targetStatus == RefundAppealStatus.APPEAL_ACCEPTED) {
            // Reopen original RefundRequest back to UNDER_REVIEW without auto-approving or executing payout
            List<RefundRequestItem> items = refundRequestItemRepository.findByRefundRequestIdForUpdate(req.getId());
            for (RefundRequestItem item : items) {
                item.setItemStatus("PENDING");
                item.setApprovedQuantity(0);
                item.setApprovedCashAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                item.setApprovedVoucherAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
                item.setDecisionReason(null);
                item.setDecidedBy(null);
                item.setDecidedAt(null);
                if (item.getActiveLockKey() == null) {
                    String candidateKey = buildItemLockKey(item);
                    if (candidateKey != null && refundRequestItemRepository.findByActiveLockKey(candidateKey).isEmpty()) {
                        item.setActiveLockKey(candidateKey);
                    }
                }
            }
            refundRequestItemRepository.saveAll(items);

            req.setRequestStatus("UNDER_VERIFICATION");
            req.setWorkflowStatus(RefundWorkflowState.UNDER_REVIEW.name());
            req.setPayoutStatus("NOT_REQUIRED");
            req.setVoucherStatus("NOT_REQUIRED");
            req.setApprovedCashTotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            req.setApprovedVoucherTotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            req.setDecisionNote("Reopened via accepted appeal " + appeal.getAppealCode() + ": " + cleanNote);
            req.setReviewedBy(reviewer);
            req.setReviewedAt(now);
            refundRequestRepository.saveAndFlush(req);

            recordAppealHistory(appeal, reviewer.getUserId(), reviewer.getRole(),
                    "APPEAL_ACCEPTED", prevAppealStatus, RefundAppealStatus.APPEAL_ACCEPTED.name(),
                    cleanNote, now);

            appendRefundApprovalHistory(req, reviewer, reviewer.getRole(),
                    "APPEAL_ACCEPTED_REOPENED_FOR_REVIEW", prevReqStatus, "UNDER_VERIFICATION",
                    "Appeal " + appeal.getAppealCode() + " accepted: " + cleanNote, now);

            recordAuditLog(req, appeal, reviewer.getUserId(), reviewer.getRole(),
                    "REFUND_APPEAL_ACCEPTED",
                    Map.of("appealCode", appeal.getAppealCode(),
                            "previousRefundWorkflowStatus", prevWfStatus != null ? prevWfStatus : "",
                            "newRefundWorkflowStatus", req.getWorkflowStatus(),
                            "decisionNote", cleanNote));
        } else {
            // APPEAL_REJECTED: keep RefundRequest in REJECTED state
            req.setWorkflowStatus(RefundWorkflowState.REJECTED.name());
            refundRequestRepository.saveAndFlush(req);

            recordAppealHistory(appeal, reviewer.getUserId(), reviewer.getRole(),
                    "APPEAL_REJECTED", prevAppealStatus, RefundAppealStatus.APPEAL_REJECTED.name(),
                    cleanNote, now);

            appendRefundApprovalHistory(req, reviewer, reviewer.getRole(),
                    "APPEAL_REJECTED", prevReqStatus, req.getRequestStatus(),
                    "Appeal " + appeal.getAppealCode() + " rejected: " + cleanNote, now);

            recordAuditLog(req, appeal, reviewer.getUserId(), reviewer.getRole(),
                    "REFUND_APPEAL_REJECTED",
                    Map.of("appealCode", appeal.getAppealCode(),
                            "refundWorkflowStatus", req.getWorkflowStatus(),
                            "decisionNote", cleanNote));
        }

        return toResponseDto(appeal);
    }

    @Transactional(readOnly = true)
    public List<RefundAppealResponseDto> getAppealsForRefundRequest(Long refundRequestId,
                                                                    Long actorUserId,
                                                                    String purchaseEmail) {
        RefundRequest req = refundRequestRepository.findById(refundRequestId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND",
                        "Refund request not found: " + refundRequestId));

        if (actorUserId != null) {
            Optional<User> actorOpt = userRepository.findById(actorUserId);
            if (actorOpt.isPresent()) {
                User actor = actorOpt.get();
                String role = actor.getRole() != null ? actor.getRole().toUpperCase(Locale.ROOT) : "";
                if ("ADMIN".equals(role)) {
                    return mapAppeals(appealRepository.findByRefundRequestIdOrderBySubmittedAtDesc(refundRequestId));
                }
                if ("MANAGER".equals(role) || "STAFF".equals(role)) {
                    enforceCinemaScopeForManager(actor, req);
                    return mapAppeals(appealRepository.findByRefundRequestIdOrderBySubmittedAtDesc(refundRequestId));
                }
            }
        }
        verifyCustomerOwnership(req, actorUserId, purchaseEmail);
        return mapAppeals(appealRepository.findByRefundRequestIdOrderBySubmittedAtDesc(refundRequestId));
    }

    @Transactional(readOnly = true)
    public List<RefundAppealResponseDto> getAppealQueueForManagerOrAdmin(Long actorUserId,
                                                                         Long cinemaIdFilter,
                                                                         String statusFilter) {
        User actor = requireManagerOrAdmin(actorUserId);
        Long effectiveCinemaId = cinemaIdFilter;
        if ("MANAGER".equalsIgnoreCase(actor.getRole())) {
            if (actor.getCinemaId() == null) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "MANAGER_CINEMA_UNASSIGNED",
                        "Manager account is not assigned to a cinema branch.");
            }
            if (cinemaIdFilter != null && !actor.getCinemaId().equals(cinemaIdFilter)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "CROSS_CINEMA_APPEAL_FORBIDDEN",
                        "Manager cannot access appeals belonging to another cinema branch.");
            }
            effectiveCinemaId = actor.getCinemaId();
        }
        String cleanStatus = (statusFilter != null && !statusFilter.trim().isEmpty())
                ? statusFilter.trim().toUpperCase(Locale.ROOT)
                : null;
        return mapAppeals(appealRepository.findAppealsForQueue(effectiveCinemaId, cleanStatus));
    }

    @Transactional(readOnly = true)
    public RefundAppealResponseDto getAppealDetail(Long appealId, Long actorUserId) {
        User actor = requireManagerOrAdmin(actorUserId);
        RefundAppeal appeal = appealRepository.findById(appealId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "APPEAL_NOT_FOUND",
                        "Refund appeal not found: " + appealId));
        enforceCinemaScopeForManager(actor, appeal.getRefundRequest());
        return toResponseDto(appeal);
    }

    private User requireManagerOrAdmin(Long userId) {
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User not found."));
        String role = user.getRole() != null ? user.getRole().toUpperCase(Locale.ROOT) : "";
        if (!"MANAGER".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only MANAGER or ADMIN is permitted to review or decide refund appeals.");
        }
        return user;
    }

    private void enforceCinemaScopeForManager(User actor, RefundRequest req) {
        if ("ADMIN".equalsIgnoreCase(actor.getRole())) {
            return;
        }
        Long actorCinemaId = actor.getCinemaId();
        Long reqCinemaId = req.getCinema() != null ? req.getCinema().getCinemaId() : null;
        if (actorCinemaId == null || reqCinemaId == null || !actorCinemaId.equals(reqCinemaId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "CROSS_CINEMA_APPEAL_FORBIDDEN",
                    "Branch isolation enforced: cannot access or decide appeals for another cinema branch.");
        }
    }

    private void verifyCustomerOwnership(RefundRequest req, Long authenticatedUserId, String purchaseEmail) {
        if (authenticatedUserId != null) {
            if (req.getCustomerUser() != null && authenticatedUserId.equals(req.getCustomerUser().getUserId())) {
                return;
            }
            if (req.getBooking() != null && req.getBooking().getUser() != null
                    && authenticatedUserId.equals(req.getBooking().getUser().getUserId())) {
                return;
            }
        }
        if (purchaseEmail != null && !purchaseEmail.trim().isEmpty()
                && req.getPurchaseEmail() != null
                && req.getPurchaseEmail().trim().equalsIgnoreCase(purchaseEmail.trim())) {
            return;
        }
        throw new BusinessException(HttpStatus.FORBIDDEN, "APPEAL_ACCESS_DENIED",
                "You do not have permission to submit or view appeals for this refund request.");
    }

    private RefundAppealStatus parseTargetDecision(String rawDecision) {
        String d = rawDecision.trim().toUpperCase(Locale.ROOT);
        if ("ACCEPT".equals(d) || "ACCEPTED".equals(d) || "APPEAL_ACCEPTED".equals(d) || "APPROVE".equals(d)) {
            return RefundAppealStatus.APPEAL_ACCEPTED;
        }
        if ("REJECT".equals(d) || "REJECTED".equals(d) || "APPEAL_REJECTED".equals(d)) {
            return RefundAppealStatus.APPEAL_REJECTED;
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_APPEAL_DECISION",
                "Invalid appeal decision '" + rawDecision + "'. Expected ACCEPT or REJECT.");
    }

    private String buildItemLockKey(RefundRequestItem item) {
        if ("TICKET".equalsIgnoreCase(item.getItemKind()) && item.getTicketId() != null) {
            return "TICKET:" + item.getTicketId();
        }
        if ("FOOD_ITEM".equalsIgnoreCase(item.getItemKind()) && item.getBookingFoodItemId() != null) {
            return "FOOD:" + item.getBookingFoodItemId();
        }
        if ("COMBO_COMPONENT".equalsIgnoreCase(item.getItemKind()) && item.getBookingFoodItemComponentId() != null) {
            return "COMP:" + item.getBookingFoodItemComponentId();
        }
        return null;
    }

    private void recordAppealHistory(RefundAppeal appeal,
                                     Long actorUserId,
                                     String actorRole,
                                     String action,
                                     String previousStatus,
                                     String newStatus,
                                     String note,
                                     LocalDateTime now) {
        RefundAppealHistory hist = new RefundAppealHistory();
        hist.setAppealId(appeal.getId());
        hist.setRefundRequestId(appeal.getRefundRequest().getId());
        hist.setActorUserId(actorUserId);
        hist.setActorRole(actorRole != null ? actorRole : "CUSTOMER");
        hist.setAction(action);
        hist.setPreviousStatus(previousStatus);
        hist.setNewStatus(newStatus);
        hist.setNote(note);
        hist.setCreatedAt(now);
        appealHistoryRepository.save(hist);
    }

    private void appendRefundApprovalHistory(RefundRequest req,
                                             User actorUser,
                                             String actorRole,
                                             String action,
                                             String prevStatus,
                                             String newStatus,
                                             String note,
                                             LocalDateTime now) {
        RefundApprovalHistory hist = new RefundApprovalHistory();
        hist.setRefundRequest(req);
        hist.setActorUser(actorUser);
        hist.setActorRole(actorRole != null ? actorRole : "CUSTOMER");
        hist.setAction(action);
        hist.setPreviousStatus(prevStatus);
        hist.setNewStatus(newStatus);
        hist.setNote(note);
        hist.setCreatedAt(now);
        approvalHistoryRepository.save(hist);
    }

    private void recordAuditLog(RefundRequest req,
                                RefundAppeal appeal,
                                Long actorUserId,
                                String actorRole,
                                String eventType,
                                Map<String, Object> details) {
        RefundAuditLog audit = new RefundAuditLog();
        audit.setRefundRequestId(req.getId());
        audit.setEntityType("REFUND_APPEAL");
        audit.setEntityId(appeal.getId());
        audit.setEventType(eventType);
        audit.setActorUserId(actorUserId);
        audit.setActorRole(actorRole);
        audit.setCinemaId(req.getCinema() != null ? req.getCinema().getCinemaId() : null);
        audit.setDetailsJson(toJson(details));
        auditLogRepository.save(audit);
    }

    private List<RefundAppealResponseDto> mapAppeals(List<RefundAppeal> appeals) {
        List<RefundAppealResponseDto> list = new ArrayList<>();
        for (RefundAppeal a : appeals) {
            list.add(toResponseDto(a));
        }
        return list;
    }

    private RefundAppealResponseDto toResponseDto(RefundAppeal appeal) {
        RefundRequest req = appeal.getRefundRequest();
        RefundAppealResponseDto dto = new RefundAppealResponseDto();
        dto.setId(appeal.getId());
        dto.setAppealCode(appeal.getAppealCode());
        if (req != null) {
            dto.setRefundRequestId(req.getId());
            dto.setRefundRequestCode(req.getRequestCode());
            dto.setRefundWorkflowStatus(req.getWorkflowStatus());
            dto.setRefundRequestStatus(req.getRequestStatus());
            if (req.getCinema() != null) {
                dto.setCinemaId(req.getCinema().getCinemaId());
                dto.setCinemaName(req.getCinema().getCinemaName());
            }
        }
        if (appeal.getCustomerUser() != null) {
            dto.setCustomerUserId(appeal.getCustomerUser().getUserId());
        }
        dto.setPurchaseEmail(appeal.getPurchaseEmail());
        dto.setAppealReason(appeal.getAppealReason());
        dto.setEvidenceUrl(appeal.getEvidenceUrl());
        dto.setStatus(appeal.getStatus());
        dto.setActiveLockOpen(appeal.getActiveAppealLock() != null);
        if (appeal.getReviewedBy() != null) {
            dto.setReviewedById(appeal.getReviewedBy().getUserId());
            dto.setReviewedByName(appeal.getReviewedBy().getFullName());
        }
        dto.setReviewedAt(appeal.getReviewedAt());
        dto.setDecisionNote(appeal.getDecisionNote());
        dto.setSubmittedAt(appeal.getSubmittedAt());
        dto.setUpdatedAt(appeal.getUpdatedAt());

        List<RefundAppealHistory> histories = appealHistoryRepository.findByAppealIdOrderByCreatedAtAsc(appeal.getId());
        List<RefundAppealResponseDto.AppealHistoryItemDto> histDtos = new ArrayList<>();
        for (RefundAppealHistory h : histories) {
            RefundAppealResponseDto.AppealHistoryItemDto item = new RefundAppealResponseDto.AppealHistoryItemDto();
            item.setId(h.getId());
            item.setActorUserId(h.getActorUserId());
            item.setActorRole(h.getActorRole());
            item.setAction(h.getAction());
            item.setPreviousStatus(h.getPreviousStatus());
            item.setNewStatus(h.getNewStatus());
            item.setNote(h.getNote());
            item.setCreatedAt(h.getCreatedAt());
            histDtos.add(item);
        }
        dto.setHistory(histDtos);
        return dto;
    }

    private String toJson(Object obj) {
        try {
            return JSON_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
