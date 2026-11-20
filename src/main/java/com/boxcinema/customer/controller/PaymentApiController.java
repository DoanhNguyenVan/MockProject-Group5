package com.boxcinema.customer.controller;

import com.boxcinema.customer.dto.response.PayOsCreatePaymentResponseDto;
import com.boxcinema.customer.dto.response.PayOsWebhookPayloadDto;
import com.boxcinema.customer.dto.response.PaymentStatusDto;
import com.boxcinema.customer.dto.request.CreatePaymentRequest;
import com.boxcinema.customer.service.PayOsService;
import com.boxcinema.common.util.SecurityContextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentApiController {

    private static final Logger log = LoggerFactory.getLogger(PaymentApiController.class);

    private final PayOsService payOsService;

    public PaymentApiController(PayOsService payOsService) {
        this.payOsService = payOsService;
    }

    @PostMapping("/payos/create")
    public ResponseEntity<PayOsCreatePaymentResponseDto> createPayment(@RequestBody CreatePaymentRequest request) {
        if (request == null || request.getBookingId() == null) {
            return ResponseEntity.badRequest().build();
        }
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        PayOsCreatePaymentResponseDto response = payOsService.createPayment(request.getBookingId(), userId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<PaymentStatusDto> getPaymentStatus(@PathVariable Long id) {
        PaymentStatusDto status = payOsService.getPaymentStatus(id);
        return ResponseEntity.ok(status);
    }

    @PostMapping("/payos/webhook")
    public ResponseEntity<Map<String, Object>> handlePayOsWebhook(@RequestBody PayOsWebhookPayloadDto payload) {
        log.info("Received PayOS webhook callback: orderCode={}, code={}",
                payload != null && payload.getData() != null ? payload.getData().getOrderCode() : "null",
                payload != null ? payload.getCode() : "null");

        try {
            boolean valid = payOsService.verifyWebhookSignature(payload);
            if (!valid) {
                log.warn("Invalid PayOS webhook signature received!");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("code", "01", "desc", "Invalid signature"));
            }

            payOsService.processWebhook(payload);
            return ResponseEntity.ok(Map.of("code", "00", "desc", "Success"));
        } catch (Exception ex) {
            log.error("Error processing PayOS webhook", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "99", "desc", ex.getMessage()));
        }
    }

    @PostMapping("/payos/simulate-payment/{orderCode}")
    public ResponseEntity<Map<String, Object>> simulatePayment(@PathVariable Long orderCode) {
        log.info("Simulating PayOS payment success for orderCode={}", orderCode);
        payOsService.simulatePayment(orderCode);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Payment simulated successfully for orderCode " + orderCode
        ));
    }
}
