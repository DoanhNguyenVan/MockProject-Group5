package com.boxcinema.refund.controller;

import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.refund.dto.*;
import com.boxcinema.refund.service.IncidentService;
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
@RequestMapping("/api/manager")
@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
public class ManagerRefundApiController {

    private final RefundService refundService;
    private final IncidentService incidentService;

    @Autowired(required = false)
    private RefundLimitService refundLimitService;

    @Autowired(required = false)
    private RefundAppealService refundAppealService;

    @Autowired(required = false)
    private com.boxcinema.refund.service.PayOsPayoutService payOsPayoutService;

    public ManagerRefundApiController(RefundService refundService, IncidentService incidentService) {
        this.refundService = refundService;
        this.incidentService = incidentService;
    }

    @GetMapping("/refunds")
    public ResponseEntity<Page<RefundRequestDetailDto>> listBranchRefunds(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String payoutStatus,
            @RequestParam(required = false) String reasonCategory,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.listRefundRequestsFiltered(
                null, status, payoutStatus, reasonCategory, null,
                minAmount, maxAmount, RefundService.parseDateTimeBound(fromDate, false), RefundService.parseDateTimeBound(toDate, true),
                search, page, size, userId));
    }

    @GetMapping("/refunds/{id}")
    public ResponseEntity<RefundRequestDetailDto> getRefundDetail(@PathVariable Long id) {
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

    @PostMapping("/refunds/{id}/retry-payout")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<RefundRequestDetailDto> retryPayout(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.retryPayoutOnly(id, userId));
    }

    @PostMapping("/refunds/{id}/retry-voucher")
    public ResponseEntity<RefundRequestDetailDto> retryVoucher(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.retryVoucherOnly(id, userId));
    }

    @GetMapping("/incidents")
    public ResponseEntity<Page<IncidentResponseDto>> listBranchIncidents(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String group,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(incidentService.listIncidents(null, status, group, page, size, userId));
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

    @GetMapping("/refunds/limits")
    public ResponseEntity<List<RefundLimitConfigDto>> listRefundLimitConfigs() {
        return ResponseEntity.ok(refundLimitService.getAllLimitConfigs());
    }

    @GetMapping("/refunds/limits/summary")
    public ResponseEntity<RefundLimitSummaryDto> getRefundLimitSummary(
            @RequestParam(required = false) Long cinemaId,
            @RequestParam(required = false) Long incidentId,
            @RequestParam(required = false) String date) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        LocalDate parsedDate = (date != null && !date.isBlank()) ? LocalDate.parse(date.trim()) : null;
        return ResponseEntity.ok(refundLimitService.getQuotaSummary(cinemaId, userId, incidentId, parsedDate));
    }

    @GetMapping("/refunds/appeals")
    public ResponseEntity<List<RefundAppealResponseDto>> listBranchAppeals(
            @RequestParam(required = false) String status) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundAppealService.getAppealQueueForManagerOrAdmin(userId, null, status));
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

    @GetMapping("/payouts/preflight-status")
    public ResponseEntity<Map<String, Object>> getPayoutPreflightStatus() {
        if (payOsPayoutService == null) {
            return ResponseEntity.ok(Map.of("livePayoutEnabled", false, "payoutMode", "MOCK"));
        }
        return ResponseEntity.ok(payOsPayoutService.getPayoutPreflightStatus());
    }

    @GetMapping("/staff-errors")
    public ResponseEntity<Page<StaffErrorLogDto>> listBranchStaffErrors(
            @RequestParam(required = false) Long staffUserId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(incidentService.listStaffErrors(null, staffUserId, page, size, userId));
    }
}
