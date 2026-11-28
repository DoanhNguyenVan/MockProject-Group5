package com.boxcinema.refund.controller;

import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.refund.dto.OrderLookupResponseDto;
import com.boxcinema.refund.dto.PublicRefundSubmitRequestDto;
import com.boxcinema.refund.dto.RefundRequestDetailDto;
import com.boxcinema.refund.service.RefundService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

@RestController
@RequestMapping("/api/staff/refunds")
@PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
public class StaffRefundApiController {

    private final RefundService refundService;

    public StaffRefundApiController(RefundService refundService) {
        this.refundService = refundService;
    }

    @GetMapping
    public ResponseEntity<Page<RefundRequestDetailDto>> listBranchRefunds(
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
                null, status, payoutStatus, reasonCategory, verifier,
                minAmount, maxAmount,
                RefundService.parseDateTimeBound(fromDate, false),
                RefundService.parseDateTimeBound(toDate, true),
                search, page, size, userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<RefundRequestDetailDto> getBranchRefund(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(refundService.getRefundDetailForStaffOrManager(id, userId));
    }

    @PostMapping("/lookup-order")
    public ResponseEntity<OrderLookupResponseDto> lookupOrderForCounter(
            @RequestBody Map<String, String> body) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        String orderCode = body != null ? body.get("orderCode") : null;
        String purchaseEmail = body != null ? body.get("purchaseEmail") : null;
        return ResponseEntity.ok(refundService.lookupOrderForStaff(orderCode, purchaseEmail, userId));
    }

    @PostMapping
    public ResponseEntity<RefundRequestDetailDto> submitCounterRefund(
            @RequestBody PublicRefundSubmitRequestDto body,
            HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        RefundRequestDetailDto created = refundService.submitStaffRefundRequest(
                body, userId, request.getRemoteAddr());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
