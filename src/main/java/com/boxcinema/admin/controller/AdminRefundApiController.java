package com.boxcinema.admin.controller;

import com.boxcinema.common.dto.CinemaDto;
import com.boxcinema.common.repository.CinemaRepository;
import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.refund.dto.*;
import com.boxcinema.refund.service.IncidentService;
import com.boxcinema.refund.service.PayOsPayoutService;
import com.boxcinema.refund.service.RefundAppealService;
import com.boxcinema.refund.service.RefundLimitService;
import com.boxcinema.refund.service.RefundService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRefundApiController {

    private final RefundService refundService;
    private final IncidentService incidentService;
    private final CinemaRepository cinemaRepository;
    private final PayOsPayoutService payOsPayoutService;

    @Autowired(required = false)
    private RefundLimitService refundLimitService;

    @Autowired(required = false)
    private RefundAppealService refundAppealService;

    @Autowired(required = false)
    private com.boxcinema.refund.service.LivePayoutGate livePayoutGate;

    @Autowired(required = false)
    private com.boxcinema.refund.service.LivePayoutWorkflowService livePayoutWorkflowService;

    public AdminRefundApiController(
            RefundService refundService,
            IncidentService incidentService,
            CinemaRepository cinemaRepository,
            PayOsPayoutService payOsPayoutService) {
        this.refundService = refundService;
        this.incidentService = incidentService;
        this.cinemaRepository = cinemaRepository;
        this.payOsPayoutService = payOsPayoutService;
    }

    @GetMapping("/cinemas")
    public ResponseEntity<List<CinemaDto>> listCinemas() {
        List<CinemaDto> list = cinemaRepository.findAll().stream()
                .map(c -> new CinemaDto(c.getCinemaId(), c.getCinemaName(), c.getCity()))
                .toList();
        return ResponseEntity.ok(list);
    }

    @GetMapping("/refunds")
    public ResponseEntity<Page<RefundRequestDetailDto>> listAllRefunds(
            @RequestParam(required = false) Long cinemaId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String payoutStatus,
            @RequestParam(required = false) String reasonCategory,
            @RequestParam(required = false) String verifier,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.listRefundRequestsFiltered(
                cinemaId, status, payoutStatus, reasonCategory, verifier,
                minAmount, maxAmount, RefundService.parseDateTimeBound(fromDate, false), RefundService.parseDateTimeBound(toDate, true),
                search, page, size, userId));
    }

    @GetMapping("/refunds/payout-queue")
    public ResponseEntity<Page<RefundRequestDetailDto>> listPayoutQueue(
            @RequestParam(required = false) Long cinemaId,
            @RequestParam(required = false) String reasonCategory,
            @RequestParam(required = false) String verifier,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.listAdminPayoutQueue(
                cinemaId, reasonCategory, verifier, minAmount, maxAmount,
                RefundService.parseDateTimeBound(fromDate, false), RefundService.parseDateTimeBound(toDate, true),
                search, page, size, userId));
    }

    @GetMapping("/refunds/{id}")
    public ResponseEntity<RefundRequestDetailDto> getRefund(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.getRefundDetailForStaffOrManager(id, userId));
    }

    @PostMapping("/refunds/{id}/review")
    public ResponseEntity<RefundRequestDetailDto> reviewRefund(
            @PathVariable Long id,
            @RequestBody RefundReviewRequestDto request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.reviewRefundRequest(id, request, userId));
    }

    @PostMapping("/refunds/{id}/payout/approve")
    public ResponseEntity<RefundRequestDetailDto> approveSinglePayout(
            @PathVariable Long id,
            @RequestBody(required = false) PayoutDecisionRequestDto request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        // In LIVE mode a real transfer can only be started through the typed-confirmation workflow
        // (/payout/live-confirmation + /payout/live-execute). Fail before any approval state changes.
        if (livePayoutGate != null && payOsPayoutService.isAdminLiveGateEnforced()
                && !livePayoutGate.hasAnyDispatchAuthorization(id)) {
            throw new com.boxcinema.common.exception.BusinessException(HttpStatus.CONFLICT, "LIVE_CONFIRMATION_REQUIRED",
                    "Live payout requires the Admin live confirmation screen (typed phrase). Use Live Payout Control.");
        }
        String note = request != null ? request.getNote() : null;
        Boolean overrideApproved = request != null ? request.getLimitOverrideApproved() : null;
        String overrideReason = request != null ? request.getLimitOverrideReason() : null;
        return ResponseEntity.ok(refundService.approveSinglePayout(id, note, overrideApproved, overrideReason, userId));
    }

    // ---------------------------------------------------------------- Live payout control (Admin only)

    @GetMapping("/payouts/live-control")
    public ResponseEntity<Map<String, Object>> getLivePayoutControl() {
        return ResponseEntity.ok(livePayoutWorkflowService.getControlStatus());
    }

    @PutMapping("/payouts/live-control/mode")
    public ResponseEntity<Map<String, Object>> setLivePayoutMode(
            @RequestBody Map<String, Object> body, jakarta.servlet.http.HttpServletRequest http) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        boolean enable = body != null && Boolean.TRUE.equals(body.get("enabled"));
        String note = body != null && body.get("note") != null ? String.valueOf(body.get("note")) : null;
        return ResponseEntity.ok(livePayoutWorkflowService.setMode(enable, note, userId, http.getRemoteAddr()));
    }

    @PutMapping("/payouts/live-control/max-amount")
    public ResponseEntity<Map<String, Object>> setLivePayoutMaxAmount(
            @RequestBody Map<String, Object> body, jakarta.servlet.http.HttpServletRequest http) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        Object raw = body != null ? body.get("maxAmountVnd") : null;
        long amount;
        try {
            amount = raw instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            throw new com.boxcinema.common.exception.BusinessException(HttpStatus.BAD_REQUEST, "LIVE_MAX_AMOUNT_INVALID",
                    "maxAmountVnd must be an integer VND amount entered by the Admin.");
        }
        String note = body.get("note") != null ? String.valueOf(body.get("note")) : null;
        return ResponseEntity.ok(livePayoutWorkflowService.setMaxAmount(amount, note, userId, http.getRemoteAddr()));
    }

    @GetMapping("/refunds/{id}/payout/live-confirmation")
    public ResponseEntity<Map<String, Object>> getLivePayoutConfirmation(
            @PathVariable Long id, jakarta.servlet.http.HttpServletRequest http) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(livePayoutWorkflowService.buildConfirmation(id, userId, http.getRemoteAddr()));
    }

    @PostMapping("/refunds/{id}/payout/live-execute")
    public ResponseEntity<Map<String, Object>> executeLivePayout(
            @PathVariable Long id,
            @RequestBody LiveExecuteRequestDto request,
            jakarta.servlet.http.HttpServletRequest http) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(livePayoutWorkflowService.execute(id, request, userId, http.getRemoteAddr()));
    }

    @PostMapping("/refunds/{id}/payout/reject")
    public ResponseEntity<RefundRequestDetailDto> rejectSinglePayout(
            @PathVariable Long id,
            @RequestBody(required = false) PayoutDecisionRequestDto request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        String reason = request != null ? (request.getReason() != null && !request.getReason().isBlank() ? request.getReason() : request.getNote()) : null;
        return ResponseEntity.ok(refundService.rejectSinglePayout(id, reason, userId));
    }

    @PostMapping("/refunds/{id}/payout/hold-for-reverify")
    public ResponseEntity<RefundRequestDetailDto> holdForReverify(
            @PathVariable Long id,
            @RequestBody(required = false) PayoutDecisionRequestDto request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        String reason = request != null ? (request.getReason() != null && !request.getReason().isBlank() ? request.getReason() : request.getNote()) : null;
        return ResponseEntity.ok(refundService.holdPayoutForReverify(id, reason, userId));
    }

    @GetMapping("/refunds/{id}/payout/preview")
    public ResponseEntity<BatchPayoutPreviewDto> previewSinglePayout(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.previewBatchPayout(List.of(id), userId));
    }

    @PostMapping("/refunds/{id}/payout/assess-fee")
    public ResponseEntity<com.boxcinema.refund.provider.PayoutFeeAssessment> assessSinglePayoutFee(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.assessSinglePayoutFeePreflight(id, userId));
    }

    @PostMapping("/refunds/payout/batch-preview")
    public ResponseEntity<BatchPayoutPreviewDto> previewBatchPayout(
            @RequestBody BatchPayoutRequestDto request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.previewBatchPayout(
                request != null ? request.getRefundRequestIds() : List.of(), userId));
    }

    @PostMapping("/refunds/payout/batch-approve")
    public ResponseEntity<BatchPayoutResponseDto> executeBatchPayout(
            @RequestBody BatchPayoutRequestDto request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        if (payOsPayoutService.isAdminLiveGateEnforced()) {
            throw new com.boxcinema.common.exception.BusinessException(HttpStatus.CONFLICT, "LIVE_BATCH_PAYOUT_DISABLED",
                    "Batch payouts are disabled in LIVE mode. Confirm and execute exactly one payout at a time.");
        }
        return ResponseEntity.ok(refundService.executeBatchPayout(request, userId));
    }

    @PostMapping("/refunds/{id}/retry-payout")
    public ResponseEntity<RefundRequestDetailDto> retryPayout(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.retryPayoutOnly(id, userId));
    }

    @PostMapping("/refunds/{id}/retry-voucher")
    public ResponseEntity<RefundRequestDetailDto> retryVoucher(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.retryVoucherOnly(id, userId));
    }

    @GetMapping("/payouts")
    public ResponseEntity<Page<PayoutTransactionDetailDto>> listPayoutTransactions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String batchCode,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.listPayoutTransactions(
                status, batchCode, search, RefundService.parseDateTimeBound(fromDate, false), RefundService.parseDateTimeBound(toDate, true),
                page, size, userId));
    }

    @GetMapping("/payouts/preflight-status")
    public ResponseEntity<Map<String, Object>> getPayoutPreflightStatus() {
        return ResponseEntity.ok(payOsPayoutService.getPayoutPreflightStatus());
    }

    @GetMapping("/payouts/account-balance")
    public ResponseEntity<PayoutAccountBalanceDto> getPayoutAccountBalance() {
        return ResponseEntity.ok(payOsPayoutService.fetchPayoutAccountBalance());
    }

    @GetMapping("/payouts/payos-remote")
    public ResponseEntity<Map<String, Object>> listPayOsRemotePayouts(
            @RequestParam(required = false) String referenceId,
            @RequestParam(required = false) String approvalState,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(payOsPayoutService.fetchPayOsRemotePayouts(referenceId, approvalState, category, fromDate, toDate, limit, offset));
    }

    @GetMapping("/payouts/{txId}")
    public ResponseEntity<PayoutTransactionDetailDto> getPayoutTransaction(@PathVariable Long txId) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.getPayoutTransactionDetail(txId, userId));
    }

    @GetMapping("/payouts/{txId}/reconciliation-logs")
    public ResponseEntity<List<com.boxcinema.common.entity.PayoutReconciliationLog>> listPayoutReconciliationLogs(@PathVariable Long txId) {
        return ResponseEntity.ok(payOsPayoutService.listReconciliationLogsForTx(txId));
    }

    @PostMapping("/payouts/{txId}/reconcile")
    public ResponseEntity<PayoutTransactionDetailDto> reconcilePayoutTransaction(@PathVariable Long txId) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.reconcilePayoutTransaction(txId, userId));
    }

    @PostMapping("/payouts/{txId}/confirm-failed")
    public ResponseEntity<PayoutTransactionDetailDto> confirmPayoutFailed(
            @PathVariable Long txId,
            @RequestBody(required = false) PayoutDecisionRequestDto request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        String reason = request != null ? (request.getReason() != null && !request.getReason().isBlank() ? request.getReason() : request.getNote()) : null;
        return ResponseEntity.ok(refundService.confirmPayoutFailed(txId, reason, userId));
    }

    @GetMapping("/incidents")
    public ResponseEntity<Page<IncidentResponseDto>> listAllIncidents(
            @RequestParam(required = false) Long cinemaId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String group,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(incidentService.listIncidents(cinemaId, status, group, page, size, userId));
    }

    @GetMapping("/incidents/{id}")
    public ResponseEntity<IncidentResponseDto> getIncident(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(incidentService.getIncident(id, userId));
    }

    @PostMapping("/incidents")
    public ResponseEntity<IncidentResponseDto> createIncident(@RequestBody IncidentCreateRequestDto request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(incidentService.createIncident(request, userId));
    }

    @PutMapping("/incidents/{id}")
    public ResponseEntity<IncidentResponseDto> updateIncident(
            @PathVariable Long id,
            @RequestBody IncidentUpdateRequestDto request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(incidentService.updateIncident(id, request, userId));
    }

    @PatchMapping("/incidents/{id}/customer-notified")
    public ResponseEntity<IncidentResponseDto> updateCustomerNotifiedAt(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        String raw = body != null ? body.get("customerNotifiedAt") : null;
        LocalDateTime notifiedAt = (raw != null && !raw.isBlank()) ? LocalDateTime.parse(raw.trim()) : null;
        return ResponseEntity.ok(incidentService.updateCustomerNotifiedAt(id, notifiedAt, userId));
    }

    @DeleteMapping("/incidents/{id}")
    public ResponseEntity<Void> deleteIncident(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        incidentService.deleteIncident(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/incidents/{id}/verify")
    public ResponseEntity<IncidentResponseDto> verifyIncident(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        String decision = body != null ? body.get("decision") : null;
        String note = body != null ? body.get("note") : null;
        return ResponseEntity.ok(incidentService.verifyByManager(id, decision, note, userId));
    }

    @PostMapping("/incidents/{id}/resolve-appeal")
    public ResponseEntity<IncidentResponseDto> resolveAppeal(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        String decision = body != null ? body.get("decision") : null;
        String note = body != null ? body.get("note") : null;
        return ResponseEntity.ok(incidentService.resolveAppealByAdmin(id, decision, note, userId));
    }

    @GetMapping("/refunds/limits")
    public ResponseEntity<List<RefundLimitConfigDto>> listRefundLimitConfigs() {
        return ResponseEntity.ok(refundLimitService.getAllLimitConfigs());
    }

    @PutMapping("/refunds/limits")
    public ResponseEntity<RefundLimitConfigDto> updateRefundLimitConfig(
            @RequestBody RefundLimitUpdateRequestDto request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundLimitService.updateLimitConfig(
                request != null ? request.getConfigKey() : null,
                request != null ? request.getLimitAmountVnd() : null,
                request != null ? request.getReason() : null,
                userId));
    }

    @PutMapping("/refunds/limits/{configKey}")
    public ResponseEntity<RefundLimitConfigDto> updateRefundLimitConfigByKey(
            @PathVariable String configKey,
            @RequestBody RefundLimitUpdateRequestDto request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundLimitService.updateLimitConfig(
                configKey,
                request != null ? request.getLimitAmountVnd() : null,
                request != null ? request.getReason() : null,
                userId));
    }

    @GetMapping("/refunds/limits/summary")
    public ResponseEntity<RefundLimitSummaryDto> getRefundLimitSummary(
            @RequestParam(required = false) Long cinemaId,
            @RequestParam(required = false) Long managerUserId,
            @RequestParam(required = false) Long incidentId,
            @RequestParam(required = false) String date) {
        LocalDate parsedDate = (date != null && !date.isBlank()) ? LocalDate.parse(date.trim()) : null;
        return ResponseEntity.ok(refundLimitService.getQuotaSummary(cinemaId, managerUserId, incidentId, parsedDate));
    }

    @GetMapping("/refunds/appeals")
    public ResponseEntity<List<RefundAppealResponseDto>> listAllAppeals(
            @RequestParam(required = false) Long cinemaId,
            @RequestParam(required = false) String status) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundAppealService.getAppealQueueForManagerOrAdmin(userId, cinemaId, status));
    }

    @GetMapping("/refunds/appeals/{appealId}")
    public ResponseEntity<RefundAppealResponseDto> getAppealDetail(@PathVariable Long appealId) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundAppealService.getAppealDetail(appealId, userId));
    }

    @PostMapping("/refunds/appeals/{appealId}/start-review")
    public ResponseEntity<RefundAppealResponseDto> startAppealReview(@PathVariable Long appealId) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundAppealService.startAppealReview(appealId, userId));
    }

    @PostMapping({"/refunds/appeals/{appealId}/decide", "/refunds/appeals/{appealId}/decision"})
    public ResponseEntity<RefundAppealResponseDto> decideAppeal(
            @PathVariable Long appealId,
            @RequestBody RefundAppealDecisionRequestDto request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundAppealService.decideAppeal(appealId, request, userId));
    }

    @GetMapping("/staff-errors")
    public ResponseEntity<Page<StaffErrorLogDto>> listAllStaffErrors(
            @RequestParam(required = false) Long cinemaId,
            @RequestParam(required = false) Long staffUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(incidentService.listStaffErrors(cinemaId, staffUserId, page, size, userId));
    }
}
