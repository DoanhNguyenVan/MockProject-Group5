package com.boxcinema.admin.controller;

import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.customer.service.PayOsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPaymentReconciliationController {

    private final PayOsService payOsService;

    public AdminPaymentReconciliationController(PayOsService payOsService) {
        this.payOsService = payOsService;
    }

    @GetMapping("/reconciliation-queue")
    public ResponseEntity<List<Map<String, Object>>> listReconciliationQueue() {
        return ResponseEntity.ok(payOsService.listCollectionReconciliationQueue());
    }

    @PostMapping("/{paymentId}/reconcile-decision")
    public ResponseEntity<Map<String, Object>> resolveReconciliationDecision(
            @PathVariable Long paymentId,
            @RequestBody(required = false) Map<String, String> body
    ) {
        Long adminUserId = SecurityContextUtils.resolveCurrentUserId();
        String decision = body != null ? body.get("decision") : null;
        String note = body != null ? body.get("note") : null;
        return ResponseEntity.ok(payOsService.resolveCollectionReconciliation(paymentId, decision, note, adminUserId));
    }
}
