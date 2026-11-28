package com.boxcinema.customer.controller;

import com.boxcinema.customer.dto.response.PayOsCreatePaymentResponseDto;
import com.boxcinema.customer.dto.response.PayOsWebhookPayloadDto;
import com.boxcinema.customer.dto.response.PaymentStatusDto;
import com.boxcinema.customer.dto.request.CreatePaymentRequest;
import com.boxcinema.customer.service.PayOsService;
import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.refund.service.PayOsPayoutService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/payments")
public class PaymentApiController {

    private static final Logger log = LoggerFactory.getLogger(PaymentApiController.class);

    private final PayOsService payOsService;
    private final PayOsPayoutService payOsPayoutService;

    @Value("${payos.collection.allow-simulate:false}")
    private boolean allowSimulate;

    @Value("${payos.collection.mode:MOCK}")
    private String collectionMode = "MOCK";

    public PaymentApiController(PayOsService payOsService) {
        this(payOsService, null);
    }

    @Autowired
    public PaymentApiController(PayOsService payOsService, @Autowired(required = false) PayOsPayoutService payOsPayoutService) {
        this.payOsService = payOsService;
        this.payOsPayoutService = payOsPayoutService;
    }

    @PostMapping("/payos/create")
    public ResponseEntity<?> createPayment(@RequestBody CreatePaymentRequest request) {
        if (request == null || request.getBookingId() == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            Long userId = SecurityContextUtils.resolveCurrentUserId();
            PayOsCreatePaymentResponseDto response = payOsService.createPayment(request.getBookingId(), userId);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "errorCode", "INVALID_BOOKING_STATE",
                    "message", ex.getMessage()
            ));
        }
    }

    @GetMapping("/{id}/status")
    public ResponseEntity<PaymentStatusDto> getPaymentStatus(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        PaymentStatusDto status = payOsService.getPaymentStatus(id, userId);
        return ResponseEntity.ok(status);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMalformedWebhookJson(HttpMessageNotReadableException ex) {
        log.warn("Rejected malformed payment/webhook JSON payload: {}", ex.getClass().getSimpleName());
        return ResponseEntity.badRequest().body(Map.of("code", "01", "desc", "Malformed webhook payload"));
    }

    @PostMapping("/payos/webhook")
    public ResponseEntity<Map<String, Object>> handlePayOsWebhook(@RequestBody PayOsWebhookPayloadDto payload) {
        Long orderCode = payload != null && payload.getData() != null ? payload.getData().getOrderCode() : null;
        String code = payload != null ? payload.getCode() : null;
        log.info("Received PayOS webhook callback: orderCode={}, code={}", orderCode, sanitizeLogToken(code));

        try {
            boolean valid = payOsService.verifyWebhookSignature(payload);
            if (!valid) {
                log.warn("Invalid PayOS webhook signature received! orderCode={}, outcome=INVALID_SIGNATURE", orderCode);
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(Map.of("code", "01", "desc", "Invalid signature"));
            }

            payOsService.processWebhook(payload);
            return ResponseEntity.ok(Map.of("code", "00", "desc", "Success"));
        } catch (IllegalArgumentException ex) {
            log.warn("Rejected PayOS webhook payload: orderCode={}, reason={}", orderCode, sanitizeLogToken(ex.getMessage()));
            return ResponseEntity.badRequest().body(Map.of("code", "01", "desc", ex.getMessage()));
        } catch (Exception ex) {
            log.error("Error processing PayOS webhook for orderCode={}", orderCode, ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "99", "desc", "Internal webhook error"));
        }
    }

    @PostMapping("/payos/payout/webhook")
    public ResponseEntity<Map<String, Object>> handlePayOsPayoutWebhook(
            @RequestBody(required = false) String rawBody,
            @RequestHeader(value = "x-signature", required = false) String headerSignature
    ) {
        if (payOsPayoutService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", "99", "desc", "Payout service unavailable"));
        }
        try {
            PayOsPayoutService.PayoutWebhookOutcome outcome =
                    payOsPayoutService.processPayoutWebhookWithOutcome(rawBody, headerSignature);
            if (outcome == PayOsPayoutService.PayoutWebhookOutcome.IGNORED_UNKNOWN) {
                return ResponseEntity.ok(Map.of(
                        "code", "00",
                        "desc", "Ignored: unknown payout reference",
                        "ignored", true,
                        "status", "UNKNOWN_REFERENCE"
                ));
            }
            if (outcome == PayOsPayoutService.PayoutWebhookOutcome.CONFLICT_IGNORED) {
                return ResponseEntity.ok(Map.of(
                        "code", "00",
                        "desc", "Ignored: conflicting late payout webhook",
                        "ignored", true,
                        "status", "CONFLICT_IGNORED"
                ));
            }
            return ResponseEntity.ok(Map.of("code", "00", "desc", "Success"));
        } catch (IllegalArgumentException ex) {
            log.warn("Rejected PayOS payout webhook: {}", sanitizeLogToken(ex.getMessage()));
            return ResponseEntity.badRequest().body(Map.of("code", "01", "desc", ex.getMessage()));
        } catch (Exception ex) {
            log.error("Error processing PayOS payout webhook", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "99", "desc", "Internal error"));
        }
    }

    private static String sanitizeLogToken(String value) {
        if (value == null) return "null";
        String clean = value.replace("\r", "").replace("\n", "").replaceAll("[\\p{Cntrl}]", "").trim();
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }

    @PostMapping("/payos/simulate-payment/{orderCode}")
    public ResponseEntity<Map<String, Object>> simulatePayment(@PathVariable Long orderCode) {
        if (!allowSimulate || "LIVE".equalsIgnoreCase(collectionMode)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "errorCode", "SIMULATE_DISABLED",
                    "message", "Simulated payment endpoint is disabled."
            ));
        }
        log.info("Simulating PayOS payment success for orderCode={}", orderCode);
        payOsService.simulatePayment(orderCode);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Payment simulated successfully for orderCode " + orderCode
        ));
    }
}
