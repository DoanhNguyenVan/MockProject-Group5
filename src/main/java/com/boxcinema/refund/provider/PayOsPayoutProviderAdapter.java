package com.boxcinema.refund.provider;

import com.boxcinema.common.entity.Payment;
import com.boxcinema.common.entity.RefundRequest;
import com.boxcinema.common.enums.PayoutFeeClassification;
import com.boxcinema.common.util.PayOsPayoutSignatureUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Official PayOS Payout v1 provider adapter:
 * - PayOS Collection VietQR (/v2/payment-requests) does not support automated original-payment reversal;
 *   refunds must fall back to BANK_PAYOUT via PayOS Payout v1 (/v1/payouts).
 * - Implements transfer fee assessment with fail-closed LIVE default (UNVERIFIED -> PAYOUT_BLOCKED)
 *   and MOCK default (SENDER_PAYS) plus clean test override seams for all 8 fee scenarios.
 * - Supports POST /v1/payouts/estimate-credit with official x-signature request header.
 */
@Component
public class PayOsPayoutProviderAdapter implements PayoutProviderAdapter {

    public static final String PROVIDER_PAYOS = "PAYOS";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    @Value("${payos.payout.mode:MOCK}")
    private String mode = "MOCK";

    @Value("${payos.payout.base-url:https://api-merchant.payos.vn}")
    private String baseUrl = "https://api-merchant.payos.vn";

    @Value("${payos.payout.client-id:}")
    private String clientId = "";

    @Value("${payos.payout.api-key:}")
    private String apiKey = "";

    @Value("${payos.payout.checksum-key:}")
    private String checksumKey = "";

    @Value("${payos.payout.mock-fee-policy:SENDER_PAYS}")
    private String mockFeePolicy = "SENDER_PAYS";

    @Value("${payos.payout.live-fee-policy:UNVERIFIED}")
    private String liveFeePolicy = "UNVERIFIED";

    @Value("${payos.payout.mock-estimated-fee-vnd:0}")
    private long mockEstimatedFeeVnd = 0L;

    @FunctionalInterface
    public interface FeeAssessmentSupplier {
        PayoutFeeAssessment assess(RefundRequest request, long approvedAmountVnd, boolean liveMode);
    }

    @FunctionalInterface
    public interface EstimateCreditTransport {
        PayoutExecutionResult postEstimateCredit(String url, HttpHeaders headers, Map<String, Object> payload);
    }

    private volatile FeeAssessmentSupplier customFeeAssessmentSupplier;
    private volatile PayoutFeeClassification mockFeePolicyOverride;
    private volatile PayoutFeeClassification liveFeePolicyOverride;
    private volatile EstimateCreditTransport customEstimateCreditTransport;

    public PayOsPayoutProviderAdapter() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(30000);
        this.restTemplate = new RestTemplate(factory);
    }

    public void setCustomFeeAssessmentSupplier(FeeAssessmentSupplier supplier) {
        this.customFeeAssessmentSupplier = supplier;
    }

    public void clearCustomFeeAssessmentSupplier() {
        this.customFeeAssessmentSupplier = null;
    }

    public void setMockFeePolicyOverride(PayoutFeeClassification override) {
        this.mockFeePolicyOverride = override;
    }

    public void setLiveFeePolicyOverride(PayoutFeeClassification override) {
        this.liveFeePolicyOverride = override;
    }

    public void setCustomEstimateCreditTransport(EstimateCreditTransport transport) {
        this.customEstimateCreditTransport = transport;
    }

    public void resetOverridesForTesting() {
        this.customFeeAssessmentSupplier = null;
        this.mockFeePolicyOverride = null;
        this.liveFeePolicyOverride = null;
        this.customEstimateCreditTransport = null;
    }

    public boolean hasExplicitFeeOverride() {
        return customFeeAssessmentSupplier != null
                || mockFeePolicyOverride != null
                || liveFeePolicyOverride != null;
    }

    public PayoutFeeClassification resolveConfiguredPolicy(boolean liveMode) {
        if (liveMode) {
            if (liveFeePolicyOverride != null) {
                return liveFeePolicyOverride;
            }
            return PayoutFeeClassification.fromStringOrUnverified(liveFeePolicy);
        }
        if (mockFeePolicyOverride != null) {
            return mockFeePolicyOverride;
        }
        return PayoutFeeClassification.fromStringOrUnverified(mockFeePolicy);
    }

    @Override
    public String getProviderName() {
        return PROVIDER_PAYOS;
    }

    @Override
    public boolean supportsOriginalPaymentReversal(Payment originalPayment) {
        // Official PayOS v2 payment-requests API (VietQR bank transfer collection) does NOT provide
        // a direct reversal/refund-to-source endpoint; cash refunds must fall back to BANK_PAYOUT.
        return false;
    }

    @Override
    public String getOriginalPaymentFallbackReason(Payment originalPayment) {
        String provider = (originalPayment != null && originalPayment.getPaymentProvider() != null)
                ? originalPayment.getPaymentProvider()
                : "PAYOS";
        String method = (originalPayment != null && originalPayment.getPaymentMethod() != null)
                ? originalPayment.getPaymentMethod()
                : "PAYOS_QR";
        return "Original payment channel (" + provider + "/" + method
                + ") does not support automated direct reversal; fell back to BANK_PAYOUT via PayOS Payout v1.";
    }

    @Override
    public PayoutFeeAssessment assessTransferFee(RefundRequest request, long approvedAmountVnd, boolean liveMode) {
        try {
            PayoutFeeAssessment raw;
            FeeAssessmentSupplier supplier = this.customFeeAssessmentSupplier;
            if (supplier != null) {
                raw = supplier.assess(request, approvedAmountVnd, liveMode);
            } else {
                raw = assessByConfiguredPolicy(approvedAmountVnd, liveMode);
            }
            return PayoutFeeAssessment.validateDefensively(PROVIDER_PAYOS, approvedAmountVnd, raw);
        } catch (Exception ex) {
            String msg = ex.getMessage() != null && !ex.getMessage().isBlank()
                    ? ex.getMessage()
                    : ex.getClass().getSimpleName();
            return PayoutFeeAssessment.blocked(
                    PROVIDER_PAYOS,
                    PayoutFeeClassification.UNKNOWN,
                    Math.max(0L, approvedAmountVnd),
                    "Transfer fee assessment failed: " + msg
            );
        }
    }

    private PayoutFeeAssessment assessByConfiguredPolicy(long approvedAmountVnd, boolean liveMode) {
        PayoutFeeClassification cls = resolveConfiguredPolicy(liveMode);
        return switch (cls) {
            case SENDER_PAYS -> {
                long fee = liveMode ? 0L : Math.max(0L, mockEstimatedFeeVnd);
                yield PayoutFeeAssessment.senderPays(
                        PROVIDER_PAYOS,
                        approvedAmountVnd,
                        fee,
                        liveMode
                                ? "Verified LIVE PayOS Payout sender-paid transfer fee policy: cinema bears fees, customer receives 100%."
                                : "MOCK PayOS Payout sender-paid transfer fee policy: cinema bears fees, customer receives 100%."
                );
            }
            case NO_FEE -> PayoutFeeAssessment.noFee(
                    PROVIDER_PAYOS,
                    approvedAmountVnd,
                    "Zero transfer fee confirmed; customer receives 100% of approved refund."
            );
            case RECIPIENT_DEDUCTED -> PayoutFeeAssessment.recipientDeducted(
                    PROVIDER_PAYOS,
                    approvedAmountVnd,
                    Math.max(1100L, mockEstimatedFeeVnd),
                    "Transfer fee is deducted from recipient; violates 100% customer refund guarantee."
            );
            case UNVERIFIED -> PayoutFeeAssessment.blocked(
                    PROVIDER_PAYOS,
                    PayoutFeeClassification.UNVERIFIED,
                    approvedAmountVnd,
                    "PayOS Payout transfer fee bearer is UNVERIFIED (official PayOS API docs do not contractually guarantee zero recipient fee deduction and expose no feeBearer field). Live payout blocked."
            );
            case UNKNOWN -> PayoutFeeAssessment.blocked(
                    PROVIDER_PAYOS,
                    PayoutFeeClassification.UNKNOWN,
                    approvedAmountVnd,
                    "PayOS Payout transfer fee bearer is UNKNOWN; payout blocked to protect 100% customer refund receipt."
            );
        };
    }

    /**
     * Calls official PayOS POST /v1/payouts/estimate-credit with request x-signature header.
     * Note: Official PayOS /v1/payouts/estimate-credit takes the batch schema
     * ({referenceId, category, validateDestination, payouts:[...]}) and returns {data:{estimateCredit:long}}.
     */
    @Override
    public Long estimateCredit(
            String referenceId,
            long amountVnd,
            String description,
            String toBin,
            String toAccountNumberPlain,
            List<String> category) {
        boolean isLive = "LIVE".equalsIgnoreCase(trimSafe(mode));
        if (!isLive && customEstimateCreditTransport == null) {
            return Math.max(0L, mockEstimatedFeeVnd);
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("referenceId", referenceId);
        item.put("amount", amountVnd);
        item.put("description", description);
        item.put("toBin", toBin);
        item.put("toAccountNumber", toAccountNumberPlain);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("referenceId", referenceId);
        payload.put("category", (category != null && !category.isEmpty()) ? category : List.of("refund"));
        payload.put("validateDestination", false);
        payload.put("payouts", List.of(item));

        String signingKey = !trimSafe(checksumKey).isEmpty()
                ? trimSafe(checksumKey)
                : (isLive ? "" : "MOCK_PAYOUT_CHECKSUM_KEY");
        if (signingKey.isEmpty()) {
            throw new IllegalStateException("PAYOS_PAYOUT_CHECKSUM_KEY is required to sign /v1/payouts/estimate-credit.");
        }
        String signature = PayOsPayoutSignatureUtil.signPayload(payload, signingKey);

        String url = effectiveBaseUrl() + "/v1/payouts/estimate-credit";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-client-id", trimSafe(clientId));
        headers.set("x-api-key", trimSafe(apiKey));
        headers.set("x-signature", signature);

        PayoutExecutionResult outcome;
        if (customEstimateCreditTransport != null) {
            outcome = customEstimateCreditTransport.postEstimateCredit(url, headers, payload);
        } else {
            try {
                String json = objectMapper.writeValueAsString(payload);
                ResponseEntity<String> resp = restTemplate.exchange(
                        url, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
                outcome = new PayoutExecutionResult(
                        resp.getStatusCode().value(),
                        resp.getBody(),
                        resp.getHeaders().getFirst("x-signature"),
                        false,
                        null
                );
            } catch (ResourceAccessException ex) {
                throw new IllegalStateException("Timeout calling PayOS /v1/payouts/estimate-credit", ex);
            } catch (HttpStatusCodeException ex) {
                throw new IllegalStateException("PayOS /v1/payouts/estimate-credit returned HTTP " + ex.getStatusCode().value(), ex);
            } catch (Exception ex) {
                throw new IllegalStateException("Unexpected error calling PayOS /v1/payouts/estimate-credit", ex);
            }
        }

        if (outcome == null || outcome.networkTimeout() || outcome.httpStatus() < 200 || outcome.httpStatus() >= 300
                || outcome.body() == null || outcome.body().isBlank()) {
            throw new IllegalStateException("PayOS /v1/payouts/estimate-credit did not return a valid 2xx response.");
        }
        try {
            JsonNode root = objectMapper.readTree(outcome.body());
            if (!"00".equals(root.path("code").asText(""))) {
                throw new IllegalStateException("PayOS /v1/payouts/estimate-credit failed with code=" + root.path("code").asText(""));
            }
            JsonNode estNode = root.path("data").path("estimateCredit");
            if (estNode.isMissingNode() || estNode.isNull() || !estNode.canConvertToLong()) {
                throw new IllegalStateException("Missing estimateCredit in PayOS /v1/payouts/estimate-credit response.");
            }
            return estNode.asLong();
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse PayOS /v1/payouts/estimate-credit response.", ex);
        }
    }

    @Override
    public PayoutExecutionResult submitPayout(PayoutExecutionCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("referenceId", command.referenceId());
        payload.put("amount", command.amountVnd());
        payload.put("description", command.description());
        payload.put("toBin", command.toBin());
        payload.put("toAccountNumber", command.toAccountNumberPlain());
        payload.put("category", (command.category() != null && !command.category().isEmpty())
                ? command.category()
                : List.of("refund"));

        boolean isLive = "LIVE".equalsIgnoreCase(trimSafe(mode));
        String signingKey = !trimSafe(checksumKey).isEmpty()
                ? trimSafe(checksumKey)
                : (isLive ? "" : "MOCK_PAYOUT_CHECKSUM_KEY");
        if (signingKey.isEmpty()) {
            throw new IllegalStateException("PAYOS_PAYOUT_CHECKSUM_KEY is required in LIVE mode.");
        }
        String signature = PayOsPayoutSignatureUtil.signPayload(payload, signingKey);

        String url = effectiveBaseUrl() + "/v1/payouts";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-client-id", trimSafe(clientId));
        headers.set("x-api-key", trimSafe(apiKey));
        headers.set("x-idempotency-key", command.idempotencyKey());
        headers.set("x-signature", signature);

        try {
            String json = objectMapper.writeValueAsString(payload);
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
            return new PayoutExecutionResult(
                    resp.getStatusCode().value(),
                    resp.getBody(),
                    resp.getHeaders().getFirst("x-signature"),
                    false,
                    null
            );
        } catch (ResourceAccessException ex) {
            return new PayoutExecutionResult(0, null, null, true, "Timeout calling PayOS payout API");
        } catch (HttpStatusCodeException ex) {
            return new PayoutExecutionResult(
                    ex.getStatusCode().value(),
                    ex.getResponseBodyAsString(),
                    ex.getResponseHeaders() != null ? ex.getResponseHeaders().getFirst("x-signature") : null,
                    false,
                    ex.getStatusText()
            );
        } catch (Exception ex) {
            return new PayoutExecutionResult(500, null, null, true,
                    "Unexpected payout transport error: " + ex.getClass().getSimpleName());
        }
    }

    @Override
    public PayoutReconciliationResult fetchPayoutStatus(String providerPayoutId, String referenceId) {
        String effectiveBase = effectiveBaseUrl();
        String url = (providerPayoutId != null && !providerPayoutId.isBlank())
                ? effectiveBase + "/v1/payouts/" + URLEncoder.encode(providerPayoutId.trim(), StandardCharsets.UTF_8).replace("+", "%20")
                : effectiveBase + "/v1/payouts?referenceId=" + URLEncoder.encode(referenceId, StandardCharsets.UTF_8) + "&limit=10&offset=0";
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-client-id", trimSafe(clientId));
        headers.set("x-api-key", trimSafe(apiKey));
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return new PayoutReconciliationResult(
                    resp.getStatusCode().value(),
                    resp.getBody(),
                    resp.getHeaders().getFirst("x-signature"),
                    false,
                    null
            );
        } catch (ResourceAccessException ex) {
            return new PayoutReconciliationResult(0, null, null, true, "Timeout contacting PayOS payout API");
        } catch (HttpStatusCodeException ex) {
            return new PayoutReconciliationResult(
                    ex.getStatusCode().value(),
                    ex.getResponseBodyAsString(),
                    ex.getResponseHeaders() != null ? ex.getResponseHeaders().getFirst("x-signature") : null,
                    false,
                    ex.getStatusText()
            );
        } catch (Exception ex) {
            return new PayoutReconciliationResult(500, null, null, true,
                    "Unexpected payout GET error: " + ex.getClass().getSimpleName());
        }
    }

    private String effectiveBaseUrl() {
        String b = trimSafe(baseUrl);
        if (b.isEmpty()) {
            return "https://api-merchant.payos.vn";
        }
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    private static String trimSafe(String s) {
        return s == null ? "" : s.trim();
    }
}
