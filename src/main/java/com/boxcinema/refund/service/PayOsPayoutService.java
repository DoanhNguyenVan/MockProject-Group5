package com.boxcinema.refund.service;

import com.boxcinema.common.entity.Booking;
import com.boxcinema.common.entity.PayOsPayoutTransaction;
import com.boxcinema.common.entity.Payment;
import com.boxcinema.common.entity.PayoutFeeAssessmentEntity;
import com.boxcinema.common.entity.PayoutReconciliationLog;
import com.boxcinema.common.entity.RefundAuditLog;
import com.boxcinema.common.entity.RefundRequest;
import com.boxcinema.common.enums.PayoutFeeClassification;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.BookingRepository;
import com.boxcinema.common.repository.PayOsPayoutTransactionRepository;
import com.boxcinema.common.repository.PaymentRepository;
import com.boxcinema.common.repository.PayoutFeeAssessmentRepository;
import com.boxcinema.common.repository.PayoutReconciliationLogRepository;
import com.boxcinema.common.repository.RefundAuditLogRepository;
import com.boxcinema.common.repository.RefundRequestRepository;
import com.boxcinema.common.service.BankDataEncryptionService;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.common.util.PayOsPayoutSignatureUtil;
import com.boxcinema.refund.dto.PayoutAccountBalanceDto;
import com.boxcinema.refund.provider.PayOsPayoutProviderAdapter;
import com.boxcinema.refund.provider.PayoutFeeAssessment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Automated PayOS Payout (v1/payouts) service.
 * Executes in 3 distinct steps (NO HTTP call inside DB transaction):
 *   Tx1 -> lock Booking -> RefundRequest, persist PENDING payout row with unique referenceId & idempotencyKey
 *   HTTP -> call PayOS (or simulate in MOCK mode using real x-signature calculation)
 *   Tx2 -> record SUCCEEDED / PROCESSING (including UNKNOWN_OUTCOME) / FAILED + enqueue email if SUCCEEDED
 */
@Service
public class PayOsPayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayOsPayoutService.class);

    private static final Set<String> INTERRUPTED_CODES = Set.of("632", "633", "634", "606");

    private final RefundRequestRepository refundRequestRepository;
    private final PayOsPayoutTransactionRepository payoutRepository;
    private final RefundAuditLogRepository auditLogRepository;
    private final BankDataEncryptionService bankEncryptionService;
    private final RefundEmailService refundEmailService;
    private final TransactionTemplate txTemplate;
    private final TransactionTemplate readOnlyTxTemplate;
    private final TransactionTemplate requiresNewTxTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final PaymentRepository paymentRepository;
    private final BookingRepository bookingRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired(required = false)
    private RefundLimitService refundLimitService;

    public void setRefundLimitService(RefundLimitService refundLimitService) {
        this.refundLimitService = refundLimitService;
    }

    @Value("${payos.payout.mode:MOCK}")
    private String mode;

    @Value("${payos.payout.base-url:https://api-merchant.payos.vn}")
    private String baseUrl;

    @Value("${payos.payout.client-id:}")
    private String clientId;

    @Value("${payos.payout.api-key:}")
    private String apiKey;

    @Value("${payos.payout.checksum-key:}")
    private String checksumKey;

    @Value("${payos.collection.client-id:${payos.client-id:}}")
    private String paymentClientId;

    @Value("${payos.collection.api-key:${payos.api-key:}}")
    private String paymentApiKey;

    @Value("${payos.collection.checksum-key:${payos.checksum-key:}}")
    private String paymentChecksumKey;

    @Value("${payos.payout.connect-timeout-ms:10000}")
    private int connectTimeoutMs;

    @Value("${payos.payout.read-timeout-ms:30000}")
    private int readTimeoutMs;

    @Value("${payos.payout.not-found-grace-seconds:900}")
    private int notFoundGraceSeconds = 900;

    @Value("${payos.payout.allow-auto-fail-on-not-found:false}")
    private boolean allowAutoFailOnNotFound = false;

    @Value("${app.refund.live-payout-enabled:false}")
    private boolean livePayoutEnabled = false;

    @Autowired(required = false)
    private PayOsPayoutProviderAdapter payoutProviderAdapter;

    @Autowired(required = false)
    private PayoutFeeAssessmentRepository feeAssessmentRepository;

    @Autowired(required = false)
    private PayoutReconciliationLogRepository reconciliationLogRepository;

    private volatile boolean enforceLiveGuardsWithCustomTransport = false;

    @Autowired(required = false)
    private LivePayoutGate livePayoutGate;

    /** Test-only: also enforce the Admin runtime gate when a mock transport is injected (never true in production). */
    private volatile boolean enforceAdminGateWithCustomTransport = false;

    public void setLivePayoutGate(LivePayoutGate livePayoutGate) {
        this.livePayoutGate = livePayoutGate;
    }

    public void setEnforceAdminGateWithCustomTransport(boolean enforce) {
        this.enforceAdminGateWithCustomTransport = enforce;
    }

    /** True when real LIVE dispatch is configured and the Admin runtime gate applies (always in production). */
    public boolean isAdminLiveGateEnforced() {
        return "LIVE".equals(getMode())
                && (customTransportExecutor == null || enforceAdminGateWithCustomTransport);
    }

    public interface TransportExecutor {
        HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap);
        HttpOutcome getPayout(String url, HttpHeaders headers);
        default HttpOutcome getBalance(String url, HttpHeaders headers) {
            return getPayout(url, headers);
        }
    }

    public record HttpOutcome(int httpStatus, String body, String signatureHeader, boolean networkTimeout, String errorMessage) {}

    private volatile TransportExecutor customTransportExecutor;

    public boolean isLivePayoutEnabled() {
        return livePayoutEnabled;
    }

    public void setLivePayoutEnabled(boolean livePayoutEnabled) {
        this.livePayoutEnabled = livePayoutEnabled;
    }

    public boolean isEnforceLiveGuardsWithCustomTransport() {
        return enforceLiveGuardsWithCustomTransport;
    }

    public void setEnforceLiveGuardsWithCustomTransport(boolean enforce) {
        this.enforceLiveGuardsWithCustomTransport = enforce;
    }

    public PayOsPayoutProviderAdapter getPayoutProviderAdapter() {
        if (this.payoutProviderAdapter == null) {
            this.payoutProviderAdapter = new PayOsPayoutProviderAdapter();
        }
        return this.payoutProviderAdapter;
    }

    public void setPayoutProviderAdapter(PayOsPayoutProviderAdapter adapter) {
        this.payoutProviderAdapter = adapter;
    }

    public void setFeeAssessmentRepository(PayoutFeeAssessmentRepository feeAssessmentRepository) {
        this.feeAssessmentRepository = feeAssessmentRepository;
    }

    public void setReconciliationLogRepository(PayoutReconciliationLogRepository reconciliationLogRepository) {
        this.reconciliationLogRepository = reconciliationLogRepository;
    }

    public static String buildTransferDescription(Long refundRequestId) {
        return sanitizeDescription("BOX REFUND " + refundRequestId);
    }

    public PayoutFeeAssessment assessFeePreflight(RefundRequest req, long amountVnd) {
        boolean isLive = "LIVE".equalsIgnoreCase(trimSafe(mode));
        return getPayoutProviderAdapter().assessTransferFee(req, amountVnd, isLive);
    }

    public Map<String, Object> getPayoutPreflightStatus() {
        boolean isLive = "LIVE".equalsIgnoreCase(trimSafe(mode));
        String cid = trimSafe(clientId);
        String akey = trimSafe(apiKey);
        String ckey = trimSafe(checksumKey);
        String payCid = trimSafe(paymentClientId);
        String payAkey = trimSafe(paymentApiKey);
        String payCkey = trimSafe(paymentChecksumKey);
        boolean credsConfigured = !isBlank(cid) && !isBlank(akey) && !isBlank(ckey);
        boolean credsDistinct = credsConfigured
                && (isBlank(payCid) || !cid.equalsIgnoreCase(payCid))
                && (isBlank(payAkey) || !akey.equals(payAkey))
                && (isBlank(payCkey) || !ckey.equals(payCkey));
        PayoutFeeClassification policy = getPayoutProviderAdapter().resolveConfiguredPolicy(isLive);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("livePayoutEnabled", livePayoutEnabled);
        out.put("payoutMode", getMode());
        out.put("providerName", getPayoutProviderAdapter().getProviderName());
        out.put("configuredFeePolicy", policy.name());
        out.put("feePolicyGuaranteesFullReceipt", policy.allowsGuaranteedFullReceipt());
        out.put("credentialsConfigured", credsConfigured);
        out.put("credentialsDistinct", credsDistinct);
        out.put("liveDispatchReady", isLive && livePayoutEnabled && credsConfigured && credsDistinct && policy.allowsGuaranteedFullReceipt());
        boolean adminLive = livePayoutGate != null && livePayoutGate.isAdminLiveEnabled();
        boolean maxSet = livePayoutGate != null && livePayoutGate.getMaxTestAmountVnd() != null;
        out.put("adminLiveEnabled", adminLive);
        out.put("maxTestAmountConfigured", maxSet);
        out.put("liveExecutionReady", isLive && livePayoutEnabled && credsConfigured && credsDistinct
                && policy.allowsGuaranteedFullReceipt() && adminLive && maxSet);
        return out;
    }

    public List<PayoutReconciliationLog> listReconciliationLogsForTx(Long payoutTxId) {
        if (reconciliationLogRepository == null || payoutTxId == null) {
            return List.of();
        }
        return reconciliationLogRepository.findByPayoutTransactionIdOrderByCreatedAtDescIdDesc(payoutTxId);
    }

    public PayOsPayoutService(
            RefundRequestRepository refundRequestRepository,
            PayOsPayoutTransactionRepository payoutRepository,
            RefundAuditLogRepository auditLogRepository,
            BankDataEncryptionService bankEncryptionService,
            RefundEmailService refundEmailService,
            PlatformTransactionManager transactionManager) {
        this(refundRequestRepository, payoutRepository, auditLogRepository,
                bankEncryptionService, refundEmailService, transactionManager, null, null);
    }

    @Autowired
    public PayOsPayoutService(
            RefundRequestRepository refundRequestRepository,
            PayOsPayoutTransactionRepository payoutRepository,
            RefundAuditLogRepository auditLogRepository,
            BankDataEncryptionService bankEncryptionService,
            RefundEmailService refundEmailService,
            PlatformTransactionManager transactionManager,
            @Autowired(required = false) PaymentRepository paymentRepository,
            @Autowired(required = false) BookingRepository bookingRepository) {
        this.refundRequestRepository = refundRequestRepository;
        this.payoutRepository = payoutRepository;
        this.auditLogRepository = auditLogRepository;
        this.bankEncryptionService = bankEncryptionService;
        this.refundEmailService = refundEmailService;
        this.txTemplate = new TransactionTemplate(transactionManager);
        TransactionTemplate readTpl = new TransactionTemplate(transactionManager);
        readTpl.setReadOnly(true);
        this.readOnlyTxTemplate = readTpl;
        TransactionTemplate reqNewTpl = new TransactionTemplate(transactionManager);
        reqNewTpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTxTemplate = reqNewTpl;
        this.restTemplate = buildRestTemplate(10000, 30000);
        this.paymentRepository = paymentRepository;
        this.bookingRepository = bookingRepository;
    }

    @PostConstruct
    public void validateConfiguration() {
        SimpleClientHttpRequestFactory rf = (SimpleClientHttpRequestFactory) this.restTemplate.getRequestFactory();
        rf.setConnectTimeout(Math.min(Math.max(connectTimeoutMs, 1000), 10000));
        rf.setReadTimeout(Math.min(Math.max(readTimeoutMs, 1000), 30000));

        String cid = trimSafe(clientId);
        String akey = trimSafe(apiKey);
        String ckey = trimSafe(checksumKey);
        String payCid = trimSafe(paymentClientId);
        String payAkey = trimSafe(paymentApiKey);
        String payCkey = trimSafe(paymentChecksumKey);

        if ("LIVE".equalsIgnoreCase(trimSafe(mode))) {
            if (isBlank(cid) || isBlank(akey) || isBlank(ckey)) {
                throw new IllegalStateException("PAYOS_PAYOUT_CLIENT_ID, PAYOS_PAYOUT_API_KEY, and PAYOS_PAYOUT_CHECKSUM_KEY are required in LIVE mode.");
            }
            if ((!isBlank(payCid) && cid.equalsIgnoreCase(payCid))
                    || (!isBlank(payAkey) && akey.equals(payAkey))
                    || (!isBlank(payCkey) && ckey.equals(payCkey))) {
                throw new IllegalStateException("PayOS Payout credentials must be strictly separate from PayOS Payment credentials.");
            }
        }
    }

    public void setCustomTransportExecutor(TransportExecutor executor) {
        this.customTransportExecutor = executor;
    }

    public String getMode() {
        return trimSafe(mode).isEmpty() ? "MOCK" : trimSafe(mode).toUpperCase(Locale.ROOT);
    }

    private RestTemplate buildRestTemplate(int connectMs, int readMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMs);
        factory.setReadTimeout(readMs);
        return new RestTemplate(factory);
    }

    public PayOsPayoutTransaction initiateOrReconcilePayout(Long refundRequestId, Long actorUserId) {
        return initiateOrReconcilePayout(refundRequestId, actorUserId, null);
    }

    public PayOsPayoutTransaction initiateOrReconcilePayout(Long refundRequestId, Long actorUserId, String batchCode) {
        Long bookingId = resolveBookingIdForRequest(refundRequestId);
        PrepareResult prep;
        try {
            prep = txTemplate.execute(status -> prepareAttemptTx1(refundRequestId, bookingId, actorUserId, batchCode));
        } catch (SnapshotTamperAbort ex) {
            persistTamperAuditAfterRollback(ex);
            throw new BusinessException(HttpStatus.CONFLICT, "RECIPIENT_SNAPSHOT_TAMPERED",
                    "Recipient bank snapshot mismatch detected before PayOS payout submission; dispatch aborted.");
        }
        if (prep == null) {
            return null;
        }
        if (prep.skipHttp()) {
            if (prep.reconcileExistingId() != null) {
                return reconcileTransaction(prep.reconcileExistingId(), actorUserId, "PRE_RETRY");
            }
            return prep.existingTx();
        }

        HttpOutcome outcome;
        if ("MOCK".equalsIgnoreCase(trimSafe(mode)) && customTransportExecutor == null) {
            outcome = executeMockPayout(prep.payloadMap(), prep.referenceId());
        } else {
            outcome = executeHttpPost(prep.payloadMap(), prep.idempotencyKey(), prep.signature());
        }

        return txTemplate.execute(status -> finalizeOutcomeTx2(prep.txId(), refundRequestId, bookingId, outcome, actorUserId));
    }

    public PayOsPayoutTransaction reconcileTransaction(Long payoutTxId, Long actorUserId) {
        return reconcileTransaction(payoutTxId, actorUserId, "MANUAL");
    }

    public PayOsPayoutTransaction reconcileTransaction(Long payoutTxId, Long actorUserId, String triggerSource) {
        String effectiveTrigger = !isBlank(triggerSource) ? triggerSource.trim().toUpperCase(Locale.ROOT) : "MANUAL";
        ReconcileMeta meta = readOnlyTxTemplate.execute(status -> {
            PayOsPayoutTransaction txMeta = payoutRepository.findById(payoutTxId)
                    .orElseThrow(() -> new IllegalArgumentException("Payout transaction not found: " + payoutTxId));
            RefundRequest r = txMeta.getRefundRequest();
            Long rrId = r.getId();
            Long bId = r.getBooking() != null ? r.getBooking().getBookingId() : null;
            if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
                entityManager.clear();
            }
            return new ReconcileMeta(rrId, bId);
        });
        if (meta == null) {
            throw new IllegalArgumentException("Payout transaction not found: " + payoutTxId);
        }
        Long refundRequestId = meta.refundRequestId();
        Long bookingId = meta.bookingId();

        ReconcileSnapshot snap = txTemplate.execute(status -> {
            lockRefundRequestInCanonicalOrder(refundRequestId, bookingId);
            PayOsPayoutTransaction tx = payoutRepository.findByIdForUpdate(payoutTxId)
                    .orElseThrow(() -> new IllegalArgumentException("Payout transaction not found: " + payoutTxId));
            if ("SUCCEEDED".equalsIgnoreCase(tx.getStatus()) || "FAILED".equalsIgnoreCase(tx.getStatus())) {
                return new ReconcileSnapshot(tx, true, null, null);
            }
            return new ReconcileSnapshot(tx, false, tx.getPayosPayoutId(), tx.getReferenceId());
        });
        if (snap == null || snap.alreadyTerminal()) {
            return snap != null ? snap.tx() : null;
        }

        HttpOutcome outcome;
        if ("MOCK".equalsIgnoreCase(trimSafe(mode)) && customTransportExecutor == null) {
            outcome = executeMockGet(snap.payosPayoutId(), snap.referenceId(), snap.tx().getAmount(), snap.tx().getToBin());
        } else {
            String effectiveBase = effectiveBaseUrl();
            String url = !isBlank(snap.payosPayoutId())
                    ? effectiveBase + "/v1/payouts/" + encodeUrlSegment(snap.payosPayoutId())
                    : effectiveBase + "/v1/payouts?referenceId=" + encodeQueryParam(snap.referenceId()) + "&limit=10&offset=0";
            outcome = executeHttpGet(url);
        }

        return txTemplate.execute(status -> applyReconcileOutcomeTx(refundRequestId, bookingId, payoutTxId, snap.referenceId(), outcome, actorUserId, effectiveTrigger));
    }

    /**
     * Checks PayOS Payout Account Balance (GET /v1/payouts-account/balance).
     * Never hits external PayOS when MOCK mode & no test transport is installed.
     */
    public PayoutAccountBalanceDto fetchPayoutAccountBalance() {
        PayoutAccountBalanceDto dto = new PayoutAccountBalanceDto();
        dto.setMode(getMode());
        dto.setCheckedAt(LocalDateTime.now());

        if ("MOCK".equalsIgnoreCase(trimSafe(mode)) && customTransportExecutor == null) {
            dto.setLiveVerified(false);
            dto.setHttpStatus(200);
            dto.setResponseCode("00");
            dto.setResponseDesc("MOCK mode balance check (PayOS LIVE not called)");
            dto.setAccountNumberMasked("******MOCK");
            dto.setAccountName("BOX CINEMA MOCK PAYOUT");
            dto.setCurrency("VND");
            dto.setBalance("100000000");
            dto.setMessage("MOCK mode active — balance simulated locally.");
            return dto;
        }

        String url = effectiveBaseUrl() + "/v1/payouts-account/balance";
        HttpOutcome outcome = executeBalanceGet(url);
        dto.setHttpStatus(outcome.httpStatus());
        dto.setLiveVerified(false);

        if (outcome.networkTimeout() || isBlank(outcome.body())) {
            dto.setMessage(outcome.errorMessage() != null ? sanitizeErrorBody(outcome.errorMessage()) : "Unable to reach PayOS Payout balance endpoint.");
            return dto;
        }

        try {
            JsonNode root = objectMapper.readTree(outcome.body());
            String code = root.path("code").asText("");
            String desc = sanitizeErrorBody(root.path("desc").asText(""));
            dto.setResponseCode(code);
            dto.setResponseDesc(desc);
            boolean ok = outcome.httpStatus() >= 200 && outcome.httpStatus() < 300 && "00".equals(code);
            if (ok && root.has("data") && root.get("data").isObject()) {
                JsonNode data = root.get("data");
                if (!verifyRestResponseSignature(root, data, outcome.signatureHeader(), getMode())) {
                    dto.setLiveVerified(false);
                    dto.setMessage("PayOS Payout balance response x-signature verification failed.");
                    return dto;
                }
                dto.setLiveVerified(true);
                dto.setAccountNumberMasked(bankEncryptionService.maskAccountNumber(data.path("accountNumber").asText("")));
                dto.setAccountName(data.path("accountName").asText(null));
                dto.setCurrency(data.path("currency").asText("VND"));
                dto.setBalance(data.path("balance").asText(null));
                dto.setMessage("Live PayOS Payout balance retrieved cleanly.");
            } else {
                dto.setLiveVerified(false);
                dto.setMessage("PayOS Payout balance returned HTTP " + outcome.httpStatus() + " (code=" + code + "): " + desc);
            }
        } catch (Exception ex) {
            dto.setMessage("Unparseable PayOS balance response.");
        }
        return dto;
    }

    /**
     * Queries PayOS Payout remote list (GET /v1/payouts?referenceId=...&approvalState=...&category=...&fromDate=...&toDate=...&limit=...&offset=...).
     */
    public Map<String, Object> fetchPayOsRemotePayouts(String referenceId, String approvalState, String category, int limit, int offset) {
        return fetchPayOsRemotePayouts(referenceId, approvalState, category, null, null, limit, offset);
    }

    public Map<String, Object> fetchPayOsRemotePayouts(
            String referenceId, String approvalState, String category,
            String fromDateIso, String toDateIso, int limit, int offset) {
        int safeLimit = Math.min(Math.max(1, limit), 50);
        int safeOffset = Math.max(0, offset);

        if ("MOCK".equalsIgnoreCase(trimSafe(mode)) && customTransportExecutor == null) {
            List<PayOsPayoutTransaction> items = !isBlank(referenceId)
                    ? payoutRepository.findByReferenceId(referenceId.trim()).map(List::of).orElse(List.of())
                    : payoutRepository.findAll().stream()
                            .sorted(Comparator.comparing(PayOsPayoutTransaction::getInitiatedAt).reversed())
                            .limit(safeLimit)
                            .toList();
            List<Map<String, Object>> mockPayouts = new ArrayList<>();
            for (PayOsPayoutTransaction tx : items) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", tx.getPayosPayoutId() != null ? tx.getPayosPayoutId() : ("MOCK-PO-" + tx.getReferenceId()));
                row.put("referenceId", tx.getReferenceId());
                row.put("approvalState", tx.getApprovalState() != null ? tx.getApprovalState() : tx.getStatus());
                row.put("amount", tx.getAmount());
                row.put("status", tx.getStatus());
                mockPayouts.add(row);
            }
            return Map.of("mode", "MOCK", "code", "00", "desc", "MOCK remote payout listing", "payouts", mockPayouts);
        }

        StringBuilder url = new StringBuilder(effectiveBaseUrl())
                .append("/v1/payouts?limit=").append(safeLimit)
                .append("&offset=").append(safeOffset);
        if (!isBlank(referenceId)) url.append("&referenceId=").append(encodeQueryParam(referenceId.trim()));
        if (!isBlank(approvalState)) url.append("&approvalState=").append(encodeQueryParam(approvalState.trim()));
        if (!isBlank(category)) url.append("&category=").append(encodeQueryParam(category.trim()));
        if (!isBlank(fromDateIso)) url.append("&fromDate=").append(encodeQueryParam(fromDateIso.trim()));
        if (!isBlank(toDateIso)) url.append("&toDate=").append(encodeQueryParam(toDateIso.trim()));

        HttpOutcome outcome = executeHttpGet(url.toString());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", getMode());
        result.put("httpStatus", outcome.httpStatus());
        if (outcome.networkTimeout() || isBlank(outcome.body())) {
            result.put("code", "GATEWAY_ERROR");
            result.put("desc", outcome.errorMessage() != null ? sanitizeErrorBody(outcome.errorMessage()) : "No response from PayOS");
            result.put("payouts", List.of());
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(outcome.body());
            String code = root.path("code").asText("");
            result.put("code", code);
            result.put("desc", sanitizeErrorBody(root.path("desc").asText("")));
            JsonNode dataNode = root.path("data");
            if ("00".equals(code) && !verifyRestResponseSignature(root, dataNode, outcome.signatureHeader(), getMode())) {
                result.put("code", "INVALID_SIGNATURE");
                result.put("desc", "PayOS remote payouts response x-signature verification failed.");
                result.put("payouts", List.of());
                return result;
            }
            JsonNode payoutsNode = dataNode.path("payouts");
            result.put("payouts", sanitizeRemotePayouts(payoutsNode));
            if (dataNode.has("pagination") && dataNode.get("pagination").isObject()) {
                result.put("pagination", objectMapper.convertValue(dataNode.get("pagination"), new TypeReference<Map<String, Object>>() {}));
            }
            return result;
        } catch (Exception ex) {
            result.put("code", "PARSE_ERROR");
            result.put("desc", "Invalid JSON from PayOS payouts endpoint.");
            result.put("payouts", List.of());
            return result;
        }
    }

    private List<Map<String, Object>> sanitizeRemotePayouts(JsonNode payoutsNode) {
        if (payoutsNode == null || !payoutsNode.isArray()) {
            return List.of();
        }
        List<Map<String, Object>> list = objectMapper.convertValue(
                payoutsNode, new TypeReference<List<Map<String, Object>>>() {});
        for (Map<String, Object> row : list) {
            maskAccountFieldsRecursively(row);
        }
        return list;
    }

    @SuppressWarnings("unchecked")
    private void maskAccountFieldsRecursively(Object node) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey();
                Object val = entry.getValue();
                if (("toAccountNumber".equalsIgnoreCase(key) || "accountNumber".equalsIgnoreCase(key))
                        && val instanceof String str && !str.isBlank()) {
                    entry.setValue(bankEncryptionService != null
                            ? bankEncryptionService.maskAccountNumber(str)
                            : "******");
                } else if (val instanceof Map || val instanceof List) {
                    maskAccountFieldsRecursively(val);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                maskAccountFieldsRecursively(item);
            }
        }
    }

    /**
     * Admin-only manual confirmation that an unresolved payout (e.g. MANUAL_RECONCILE_REQUIRED /
     * UNKNOWN_OUTCOME / PROCESSING) truly failed on my.payos.vn, releasing active_request_lock
     * only after an explicit confirmation note is provided.
     */
    public PayOsPayoutTransaction confirmPayoutFailedByAdmin(Long txId, String reason, Long adminUserId) {
        if (adminUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Admin authentication required.");
        }
        if (isBlank(reason)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CONFIRM_FAILED_REASON_REQUIRED",
                    "A mandatory confirmation note verifying PayOS portal status is required.");
        }
        String cleanReason = reason.trim();

        ReconcileMeta meta = readOnlyTxTemplate.execute(status -> payoutRepository.findById(txId)
                .map(tx -> {
                    RefundRequest rr = tx.getRefundRequest();
                    Long rrId = rr != null ? rr.getId() : null;
                    Long bId = (rr != null && rr.getBooking() != null) ? rr.getBooking().getBookingId() : null;
                    if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
                        entityManager.clear();
                    }
                    return new ReconcileMeta(rrId, bId);
                })
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYOUT_NOT_FOUND", "Payout transaction not found.")));

        return txTemplate.execute(status -> {
            RefundRequest req = meta.refundRequestId() != null
                    ? lockRefundRequestInCanonicalOrder(meta.refundRequestId(), meta.bookingId())
                    : null;
            PayOsPayoutTransaction tx = payoutRepository.findByIdForUpdate(txId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYOUT_NOT_FOUND", "Payout transaction not found."));

            String curStatus = tx.getStatus() != null ? tx.getStatus().toUpperCase(Locale.ROOT) : "";
            if ("SUCCEEDED".equals(curStatus)) {
                throw new BusinessException(HttpStatus.CONFLICT, "PAYOUT_ALREADY_SUCCEEDED",
                        "Cannot mark a succeeded payout transaction as failed.");
            }
            if (!"PROCESSING".equals(curStatus) && !"PENDING".equals(curStatus)) {
                throw new BusinessException(HttpStatus.CONFLICT, "INVALID_PAYOUT_STATE",
                        "Payout transaction is already in terminal state " + curStatus + ".");
            }

            String prevState = tx.getStatus() + ":" + (tx.getTransactionState() != null ? tx.getTransactionState() : "");
            tx.setLastReconciledAt(LocalDateTime.now());
            String code = !isBlank(tx.getPayosResponseCode()) ? tx.getPayosResponseCode() : "MANUAL_FAILED";
            markPayoutFailed(tx, req, code, "Admin confirmed failed after PayOS check: " + cleanReason);
            if (req != null) {
                recordReconciliationLog(tx, req, "ADMIN_CONFIRM_FAILED", prevState, "FAILED",
                        code, "Admin confirmed failed: " + cleanReason, adminUserId,
                        Map.of("reason", cleanReason, "referenceId", tx.getReferenceId()));
                recordAudit(req, tx, "PAYOUT_ADMIN_CONFIRMED_FAILED", adminUserId);
            }
            return tx;
        });
    }

    @Scheduled(fixedDelayString = "${payos.payout.reconcile-poll-interval-ms:30000}")
    public void runScheduledPayoutReconcile() {
        if (!"LIVE".equalsIgnoreCase(trimSafe(mode))) {
            return;
        }
        scheduledReconcileProcessingPayouts();
    }

    public void scheduledReconcileProcessingPayouts() {
        List<PayOsPayoutTransaction> candidates = payoutRepository.findByStatusInOrderByInitiatedAtAsc(List.of("PENDING", "PROCESSING"));
        LocalDateTime pendingCutoff = LocalDateTime.now().minusSeconds(30);
        int processed = 0;
        for (PayOsPayoutTransaction tx : candidates) {
            if (processed >= 50) {
                break;
            }
            if ("MANUAL_RECONCILE_REQUIRED".equalsIgnoreCase(tx.getTransactionState())) {
                continue;
            }
            if ("PENDING".equalsIgnoreCase(tx.getStatus())
                    && (tx.getInitiatedAt() == null || tx.getInitiatedAt().isAfter(pendingCutoff))) {
                continue;
            }
            processed++;
            try {
                reconcileTransaction(tx.getId(), null, "SCHEDULED");
            } catch (Exception e) {
                log.warn("Scheduled payout reconciliation skipped for ref {}: {}", sanitizeLogToken(tx.getReferenceId()), e.getMessage());
            }
        }
    }

    public enum PayoutWebhookOutcome {
        PROCESSED,
        DUPLICATE,
        IGNORED_UNKNOWN,
        CONFLICT_IGNORED
    }

    public void processPayoutWebhook(String rawBody, String headerSignature) {
        processPayoutWebhookWithOutcome(rawBody, headerSignature);
    }

    public PayoutWebhookOutcome processPayoutWebhookWithOutcome(String rawBody, String headerSignature) {
        if (isBlank(rawBody)) {
            throw new IllegalArgumentException("Empty PayOS payout webhook body.");
        }
        final JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON in PayOS payout webhook.", e);
        }
        if (root == null || !root.isObject() || !root.has("data") || !root.get("data").isObject() || root.get("data").isEmpty()) {
            throw new IllegalArgumentException("Missing or empty data object in PayOS payout webhook.");
        }
        JsonNode codeNode = root.get("code");
        if (codeNode == null || !codeNode.isTextual() || !"00".equals(codeNode.asText("").trim())) {
            throw new IllegalArgumentException("Missing or invalid envelope code in PayOS payout webhook.");
        }

        JsonNode dataNode = root.get("data");
        String headerSig = !isBlank(headerSignature) ? headerSignature.trim() : null;
        String bodySig = null;
        if (root.has("signature") && !root.get("signature").isNull()) {
            JsonNode sigNode = root.get("signature");
            if (!sigNode.isTextual()) {
                throw new IllegalArgumentException("Invalid PayOS payout webhook signature.");
            }
            String trimmedBodySig = sigNode.asText("").trim();
            if (!trimmedBodySig.isEmpty()) {
                bodySig = trimmedBodySig;
            }
        }
        if (headerSig != null && bodySig != null && !MessageDigest.isEqual(
                headerSig.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                bodySig.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Conflicting PayOS payout webhook signatures.");
        }
        String sig = headerSig != null ? headerSig : bodySig;
        String effectiveChecksumKey = !isBlank(trimSafe(checksumKey))
                ? trimSafe(checksumKey)
                : ("LIVE".equalsIgnoreCase(trimSafe(mode)) ? "" : "MOCK_PAYOUT_CHECKSUM_KEY");
        Map<String, Object> dataMap = objectMapper.convertValue(dataNode, new TypeReference<>() {});
        if (isBlank(sig) || isBlank(effectiveChecksumKey)
                || !PayOsPayoutSignatureUtil.verifySignature(dataMap, sig, effectiveChecksumKey)) {
            throw new IllegalArgumentException("Invalid PayOS payout webhook signature.");
        }

        JsonNode txnsNode = dataNode.get("transactions");
        if (txnsNode != null && !txnsNode.isNull() && !txnsNode.isArray()) {
            throw new IllegalArgumentException("Invalid transactions field in PayOS payout webhook.");
        }
        List<String> txnReferenceIds = new ArrayList<>();
        boolean anyTxnStatePresent = false;
        if (txnsNode != null && txnsNode.isArray()) {
            for (JsonNode item : txnsNode) {
                if (item == null || !item.isObject()) {
                    throw new IllegalArgumentException("Invalid transaction entry in PayOS payout webhook.");
                }
                String tRef = textOrNull(item.get("referenceId"));
                if (!isBlank(tRef)) {
                    txnReferenceIds.add(tRef);
                }
                if (!isBlank(textOrNull(item.get("state")))) {
                    anyTxnStatePresent = true;
                }
            }
        }

        String topReferenceId = textOrNull(dataNode.get("referenceId"));
        String payosPayoutId = textOrNull(dataNode.get("id"));
        String approvalState = textOrNull(dataNode.get("approvalState"));

        boolean hasAnyReferenceId = !isBlank(topReferenceId) || !txnReferenceIds.isEmpty();
        if (!hasAnyReferenceId && isBlank(payosPayoutId)) {
            throw new IllegalArgumentException("Missing referenceId and payout id in PayOS payout webhook.");
        }
        if (isBlank(approvalState) && !anyTxnStatePresent) {
            throw new IllegalArgumentException("Missing payout status/state in PayOS payout webhook.");
        }

        final String resolvedTopRefId = topReferenceId;
        final List<String> resolvedTxnRefIds = List.copyOf(txnReferenceIds);
        final String resolvedPayoutId = payosPayoutId;
        final String canonicalData = PayOsPayoutSignatureUtil.buildDataString(dataMap);
        final String fullFingerprint = CryptoUtils.sha256(canonicalData);
        final String fpEventType = "FP:" + fullFingerprint.substring(0, 48);
        final String sanitizedRaw = sanitizeWebhookRawBody(root, rawBody);

        WebhookTarget target = readOnlyTxTemplate.execute(status -> {
            Optional<PayOsPayoutTransaction> lookupOpt = Optional.empty();
            if (!isBlank(resolvedTopRefId)) {
                lookupOpt = payoutRepository.findByReferenceId(resolvedTopRefId);
            } else if (!resolvedTxnRefIds.isEmpty()) {
                for (String candidateRef : resolvedTxnRefIds) {
                    lookupOpt = payoutRepository.findByReferenceId(candidateRef);
                    if (lookupOpt.isPresent()) {
                        break;
                    }
                }
            } else if (!isBlank(resolvedPayoutId)) {
                // Only fall back to payosPayoutId when no referenceId was supplied at all
                lookupOpt = payoutRepository.findByPayosPayoutId(resolvedPayoutId);
            }
            if (lookupOpt.isEmpty()) {
                if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
                    entityManager.clear();
                }
                return null;
            }
            PayOsPayoutTransaction lookup = lookupOpt.get();
            RefundRequest r = lookup.getRefundRequest();
            Long rrId = r.getId();
            Long bId = r.getBooking() != null ? r.getBooking().getBookingId() : null;
            Long tId = lookup.getId();
            if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
                entityManager.clear();
            }
            return new WebhookTarget(rrId, tId, bId);
        });
        if (target == null) {
            log.warn("PayOS payout webhook received for unknown payout referenceId={} payosPayoutId={} fp={}",
                    sanitizeLogToken(!isBlank(resolvedTopRefId) ? resolvedTopRefId : (!resolvedTxnRefIds.isEmpty() ? resolvedTxnRefIds.get(0) : null)),
                    sanitizeLogToken(resolvedPayoutId),
                    fullFingerprint.substring(0, 12));
            return PayoutWebhookOutcome.IGNORED_UNKNOWN;
        }

        PayoutWebhookOutcome outcome = txTemplate.execute(status -> {
            RefundRequest req = lockRefundRequestInCanonicalOrder(target.refundRequestId(), target.bookingId());
            PayOsPayoutTransaction tx = payoutRepository.findByIdForUpdate(target.txId()).orElseThrow();

            // Strict pre-mutation validation against locked local payout record
            JsonNode matchedTxn = validateInboundWebhookPayload(tx, req, dataNode);

            // Durable DB-backed deduplication surviving backend restarts
            if (isWebhookFingerprintAlreadyProcessed(req.getId(), tx.getId(), fpEventType)) {
                log.info("Duplicate PayOS payout webhook ignored idempotently for referenceId={} fp={}",
                        sanitizeLogToken(tx.getReferenceId()), fullFingerprint.substring(0, 12));
                return PayoutWebhookOutcome.DUPLICATE;
            }

            boolean incomingSucceeded = isIncomingSucceeded(dataNode, matchedTxn);

            if ("SUCCEEDED".equalsIgnoreCase(tx.getStatus())) {
                recordWebhookFingerprint(req, tx, fpEventType, fullFingerprint, "IGNORED_ALREADY_SUCCEEDED");
                recordAudit(req, tx, "PAYOUT_WEBHOOK_IGNORED_ALREADY_SUCCEEDED", null);
                return PayoutWebhookOutcome.DUPLICATE;
            }
            if ("FAILED".equalsIgnoreCase(tx.getStatus()) && !incomingSucceeded) {
                recordWebhookFingerprint(req, tx, fpEventType, fullFingerprint, "IGNORED_ALREADY_FAILED");
                recordAudit(req, tx, "PAYOUT_WEBHOOK_IGNORED_ALREADY_FAILED", null);
                return PayoutWebhookOutcome.DUPLICATE;
            }
            if (incomingSucceeded) {
                boolean anotherActiveOrSucceeded = payoutRepository.findByActiveRequestLock(req.getId())
                        .map(other -> !other.getId().equals(tx.getId()))
                        .orElse(false)
                        || payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(req.getId()).stream()
                                .anyMatch(other -> !other.getId().equals(tx.getId()) && "SUCCEEDED".equalsIgnoreCase(other.getStatus()));
                if (anotherActiveOrSucceeded) {
                    log.warn("Conflicting late SUCCEEDED webhook ignored for stale payout referenceId={} refundRequestId={} fp={}",
                            sanitizeLogToken(tx.getReferenceId()), req.getId(), fullFingerprint.substring(0, 12));
                    recordWebhookFingerprint(req, tx, fpEventType, fullFingerprint, "CONFLICT_LATE_SUCCESS");
                    recordAudit(req, tx, "PAYOUT_WEBHOOK_LATE_SUCCESS_CONFLICT", null);
                    return PayoutWebhookOutcome.CONFLICT_IGNORED;
                }
            }

            String prevState = tx.getStatus() + ":" + (tx.getTransactionState() != null ? tx.getTransactionState() : "");
            if (sanitizedRaw != null) {
                tx.setRawResponseSanitized(sanitizedRaw);
            }
            tx.setLastReconciledAt(LocalDateTime.now());
            applyPayOsPayoutData(tx, req, dataNode, matchedTxn);
            payoutRepository.save(tx);
            recomputeOverallStatus(req);
            refundRequestRepository.save(req);
            recordWebhookFingerprint(req, tx, fpEventType, fullFingerprint, "PROCESSED_" + tx.getStatus());
            recordReconciliationLog(tx, req, "WEBHOOK", prevState,
                    tx.getStatus() + ":" + (tx.getTransactionState() != null ? tx.getTransactionState() : ""),
                    "00", "Webhook processed (note: PayOS Payout webhooks are optional/unverified; polling remains primary)",
                    null, Map.of("referenceId", tx.getReferenceId(), "workflowStatus", req.getWorkflowStatus(), "fingerprint", fullFingerprint.substring(0, 12)));
            recordAudit(req, tx, "PAYOUT_WEBHOOK_" + tx.getStatus(), null);
            return PayoutWebhookOutcome.PROCESSED;
        });
        return outcome != null ? outcome : PayoutWebhookOutcome.PROCESSED;
    }

    private JsonNode validateInboundWebhookPayload(PayOsPayoutTransaction tx, RefundRequest req, JsonNode dataNode) {
        String topRefId = textOrNull(dataNode.get("referenceId"));
        if (!isBlank(topRefId) && !tx.getReferenceId().equals(topRefId)) {
            throw new IllegalArgumentException("ReferenceId mismatch in PayOS payout webhook.");
        }

        String incomingPayoutId = textOrNull(dataNode.get("id"));
        if (!isBlank(incomingPayoutId) && !isBlank(tx.getPayosPayoutId())
                && !tx.getPayosPayoutId().equals(incomingPayoutId)) {
            throw new IllegalArgumentException("Payout id mismatch in PayOS payout webhook.");
        }

        if (dataNode.hasNonNull("amount")) {
            JsonNode topAmtNode = dataNode.get("amount");
            if (!topAmtNode.isNumber() || !topAmtNode.canConvertToLong()) {
                throw new IllegalArgumentException("Invalid payout amount in PayOS payout webhook.");
            }
            long topAmt = topAmtNode.asLong(-1L);
            if (topAmt <= 0 || tx.getAmount() == null || topAmt != tx.getAmount()) {
                throw new IllegalArgumentException("Payout amount mismatch in PayOS payout webhook.");
            }
        }

        JsonNode txnsNode = dataNode.get("transactions");
        JsonNode matchedTxn = null;
        if (txnsNode != null && txnsNode.isArray() && !txnsNode.isEmpty()) {
            for (JsonNode item : txnsNode) {
                String tRef = textOrNull(item.get("referenceId"));
                if (!isBlank(tRef) && tx.getReferenceId().equals(tRef)) {
                    matchedTxn = item;
                    break;
                }
            }
            if (matchedTxn == null) {
                if (txnsNode.size() == 1 && isBlank(textOrNull(txnsNode.get(0).get("referenceId")))) {
                    matchedTxn = txnsNode.get(0);
                } else {
                    throw new IllegalArgumentException("Transaction referenceId mismatch in PayOS payout webhook.");
                }
            }

            String incomingTxnId = textOrNull(matchedTxn.get("id"));
            if (!isBlank(incomingTxnId) && !isBlank(tx.getPayosTransactionId())
                    && !tx.getPayosTransactionId().equals(incomingTxnId)) {
                throw new IllegalArgumentException("Transaction id mismatch in PayOS payout webhook.");
            }

            JsonNode amtNode = matchedTxn.get("amount");
            if (amtNode == null || amtNode.isNull() || !amtNode.isNumber() || !amtNode.canConvertToLong()) {
                throw new IllegalArgumentException("Missing or invalid transaction amount in PayOS payout webhook.");
            }
            long respAmt = amtNode.asLong(-1L);
            if (respAmt <= 0 || tx.getAmount() == null || respAmt != tx.getAmount()) {
                throw new IllegalArgumentException("Transaction amount mismatch in PayOS payout webhook.");
            }

            String incomingToBin = textOrNull(matchedTxn.get("toBin"));
            if (!isBlank(incomingToBin) && !isBlank(tx.getToBin()) && !tx.getToBin().equals(incomingToBin)) {
                throw new IllegalArgumentException("Recipient bank BIN mismatch in PayOS payout webhook.");
            }

            String incomingToAcc = textOrNull(matchedTxn.get("toAccountNumber"));
            if (!matchesBeneficiaryAccount(req, incomingToAcc)) {
                throw new IllegalArgumentException("Recipient bank account number mismatch in PayOS payout webhook.");
            }
        }

        String approvalState = textOrNull(dataNode.get("approvalState"));
        String txnState = matchedTxn != null ? textOrNull(matchedTxn.get("state")) : null;
        if (isBlank(approvalState) && isBlank(txnState)) {
            throw new IllegalArgumentException("Missing payout status/state in PayOS payout webhook.");
        }
        if ((isCompletedApprovalState(approvalState) || "SUCCEEDED".equalsIgnoreCase(txnState))
                && matchedTxn == null) {
            throw new IllegalArgumentException("Missing transaction details for completed PayOS payout webhook.");
        }
        return matchedTxn;
    }

    private boolean isIncomingSucceeded(JsonNode dataNode, JsonNode matchedTxn) {
        String approvalState = dataNode.path("approvalState").asText("");
        String txnState = "";
        if (matchedTxn != null) {
            txnState = matchedTxn.path("state").asText("");
        } else {
            JsonNode txns = dataNode.path("transactions");
            if (txns.isArray() && !txns.isEmpty()) {
                txnState = txns.get(0).path("state").asText("");
            }
        }
        return "SUCCEEDED".equalsIgnoreCase(txnState) && isCompletedApprovalState(approvalState);
    }

    private boolean isWebhookFingerprintAlreadyProcessed(Long refundRequestId, Long txId, String fpEventType) {
        if (entityManager != null) {
            Long count = entityManager.createQuery(
                    "SELECT COUNT(a) FROM RefundAuditLog a "
                            + "WHERE a.entityType = :entityType AND a.entityId = :entityId AND a.eventType = :eventType",
                    Long.class)
                    .setParameter("entityType", "PAYOUT_WEBHOOK_EVENT")
                    .setParameter("entityId", txId)
                    .setParameter("eventType", fpEventType)
                    .getSingleResult();
            return count != null && count > 0;
        }
        if (auditLogRepository == null || refundRequestId == null) {
            return false;
        }
        return auditLogRepository.findByRefundRequestIdOrderByCreatedAtDesc(refundRequestId).stream()
                .anyMatch(a -> "PAYOUT_WEBHOOK_EVENT".equals(a.getEntityType())
                        && Objects.equals(txId, a.getEntityId())
                        && fpEventType.equals(a.getEventType()));
    }

    private void recordWebhookFingerprint(RefundRequest req, PayOsPayoutTransaction tx,
                                          String fpEventType, String fullFingerprint, String outcome) {
        RefundAuditLog dedup = new RefundAuditLog();
        dedup.setRefundRequestId(req.getId());
        dedup.setEntityType("PAYOUT_WEBHOOK_EVENT");
        dedup.setEntityId(tx.getId());
        dedup.setEventType(fpEventType);
        dedup.setActorUserId(null);
        dedup.setCinemaId(req.getCinema() != null ? req.getCinema().getCinemaId() : null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("referenceId", tx.getReferenceId());
        details.put("fingerprint", fullFingerprint);
        details.put("outcome", outcome);
        dedup.setDetailsJson(serializeAuditDetails(details));
        auditLogRepository.save(dedup);
    }

    private record PrepareResult(
            boolean skipHttp,
            PayOsPayoutTransaction existingTx,
            Long reconcileExistingId,
            Long txId,
            String referenceId,
            String idempotencyKey,
            String signature,
            Map<String, Object> payloadMap) {}

    private record ReconcileSnapshot(PayOsPayoutTransaction tx, boolean alreadyTerminal, String payosPayoutId, String referenceId) {}

    private record ReconcileMeta(Long refundRequestId, Long bookingId) {}

    private record WebhookTarget(Long refundRequestId, Long txId, Long bookingId) {}

    private Long resolveBookingIdForRequest(Long refundRequestId) {
        if (bookingRepository == null || refundRequestId == null) {
            return null;
        }
        return readOnlyTxTemplate.execute(status -> refundRequestRepository.findById(refundRequestId)
                .map(r -> {
                    Long bId = r.getBooking() != null ? r.getBooking().getBookingId() : null;
                    if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
                        entityManager.clear();
                    }
                    return bId;
                })
                .orElse(null));
    }

    private RefundRequest lockRefundRequestInCanonicalOrder(Long refundRequestId, Long bookingId) {
        if (bookingId != null && bookingRepository != null) {
            bookingRepository.findByIdForUpdate(bookingId);
        }
        return refundRequestRepository.findByIdForUpdate(refundRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Refund request not found: " + refundRequestId));
    }

    private PrepareResult prepareAttemptTx1(Long refundRequestId, Long bookingId, Long actorUserId, String batchCode) {
        RefundRequest req = lockRefundRequestInCanonicalOrder(refundRequestId, bookingId);

        if (!Set.of("PAYOUT_APPROVED", "PAYOUT_PROCESSING", "PAYOUT_FAILED", "PAYOUT_BLOCKED", "PAYOUT_SUCCEEDED", "COMPLETED")
                .contains(req.getRequestStatus())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT, "REFUND_NOT_APPROVED",
                    "Cannot initiate payout when refund request status is " + req.getRequestStatus());
        }

        if (req.getApprovedCashTotal() != null && req.getApprovedCashTotal().compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_PAYOUT_AMOUNT", "Payout amount cannot be negative.");
        }

        long amountVnd = req.getApprovedCashTotal() != null
                ? PurchaseSnapshotService.toVndLong(req.getApprovedCashTotal())
                : 0L;
        if (amountVnd <= 0) {
            req.setPayoutStatus("NOT_REQUIRED");
            recomputeOverallStatus(req);
            refundRequestRepository.save(req);
            return null;
        }

        Optional<PayOsPayoutTransaction> activeOpt = payoutRepository.findByActiveRequestLock(refundRequestId);
        if (activeOpt.isPresent()) {
            PayOsPayoutTransaction active = activeOpt.get();
            if ("SUCCEEDED".equalsIgnoreCase(active.getStatus())) {
                return new PrepareResult(true, active, null, active.getId(), active.getReferenceId(), null, null, null);
            }
            if ("PENDING".equalsIgnoreCase(active.getStatus())
                    && active.getInitiatedAt() != null
                    && active.getInitiatedAt().isAfter(LocalDateTime.now().minusSeconds(15))) {
                return new PrepareResult(true, active, null, active.getId(), active.getReferenceId(), null, null, null);
            }
            return new PrepareResult(true, active, active.getId(), active.getId(), active.getReferenceId(), null, null, null);
        }

        if (isBlank(req.getToBin()) || isBlank(req.getToAccountNumberEncrypted())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_BANK_INFO",
                    "Recipient bank BIN and account number are missing on refund request " + req.getRequestCode());
        }

        final String plainAccount;
        try {
            bankEncryptionService.validateAndResolveBankName(req.getToBin());
            plainAccount = bankEncryptionService.validateAccountNumber(
                    bankEncryptionService.decryptAccountNumber(req.getToAccountNumberEncrypted()));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_BANK_INFO", ex.getMessage());
        }

        // Verify immutable recipient bank snapshot before any provider dispatch
        verifyRecipientSnapshotBeforePayout(req, amountVnd, plainAccount, actorUserId);

        Booking booking = req.getBooking();
        if (booking != null) {
            long effectiveCap = Math.max(0L,
                    PurchaseSnapshotService.toVndLong(booking.getTotalAmount())
                            - PurchaseSnapshotService.toVndLong(booking.getServiceFee()));
            if (paymentRepository != null) {
                Optional<Payment> paidOpt = paymentRepository
                        .findFirstByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(booking.getBookingId(), "PAID");
                if (paidOpt.isPresent() && paidOpt.get().getAmount() != null) {
                    effectiveCap = Math.min(effectiveCap, PurchaseSnapshotService.toVndLong(paidOpt.get().getAmount()));
                }
            }
            long committedOrInFlight = payoutRepository.sumCommittedOrInFlightAmountByBookingId(booking.getBookingId());
            if (committedOrInFlight + amountVnd > effectiveCap) {
                throw new BusinessException(HttpStatus.CONFLICT, "PAYOUT_CAP_EXCEEDED",
                        "Cumulative payouts would exceed the net amount paid for this booking.");
            }
        }

        if (refundLimitService != null) {
            refundLimitService.revalidateAndRolloverBeforePayout(
                    req, req.getPayoutDecidedBy(), amountVnd, null, null);
        }

        boolean isLive = "LIVE".equalsIgnoreCase(trimSafe(mode));
        boolean enforceStrictLive = isLive && (customTransportExecutor == null || enforceLiveGuardsWithCustomTransport);
        if (enforceStrictLive) {
            if (!livePayoutEnabled) {
                throw new BusinessException(HttpStatus.CONFLICT, "LIVE_PAYOUT_DISABLED",
                        "Live PayOS payout dispatch is disabled (LIVE_PAYOUT_ENABLED=false).");
            }
            String cid = trimSafe(clientId);
            String akey = trimSafe(apiKey);
            String ckey = trimSafe(checksumKey);
            String payCid = trimSafe(paymentClientId);
            String payAkey = trimSafe(paymentApiKey);
            String payCkey = trimSafe(paymentChecksumKey);
            if (isBlank(cid) || isBlank(akey) || isBlank(ckey)) {
                throw new BusinessException(HttpStatus.CONFLICT, "LIVE_PAYOUT_CREDENTIALS_MISSING",
                        "PAYOS_PAYOUT_CLIENT_ID, PAYOS_PAYOUT_API_KEY, and PAYOS_PAYOUT_CHECKSUM_KEY are required in LIVE mode.");
            }
            if ((!isBlank(payCid) && cid.equalsIgnoreCase(payCid))
                    || (!isBlank(payAkey) && akey.equals(payAkey))
                    || (!isBlank(payCkey) && ckey.equals(payCkey))) {
                throw new BusinessException(HttpStatus.CONFLICT, "LIVE_PAYOUT_CREDENTIALS_SHARED",
                        "PayOS Payout credentials must be strictly distinct from PayOS Collection credentials.");
            }
        }

        // Evaluate transfer fee policy BEFORE creating/submitting payout (Customer MUST receive 100% of approvedCashTotal)
        boolean assessAsLive = enforceStrictLive
                || (isLive && getPayoutProviderAdapter().hasExplicitFeeOverride());
        PayoutFeeAssessment feeAssessment = getPayoutProviderAdapter().assessTransferFee(req, amountVnd, assessAsLive);
        PayoutFeeAssessmentEntity feeEntity = persistFeeAssessment(req, null, feeAssessment, actorUserId);

        req.setFeeAssessmentStatus(feeAssessment.classification().name());
        req.setFeeAmountVnd(feeAssessment.estimatedFeeVnd());

        if (feeAssessment.blocked() || !feeAssessment.guaranteedFullReceipt()) {
            req.setPayoutBlockedReason(feeAssessment.reason());
            req.setWorkflowStatus("PAYOUT_BLOCKED");
            req.setRequestStatus("PAYOUT_BLOCKED");
            req.setPayoutStatus("PAYOUT_BLOCKED");
            refundRequestRepository.save(req);
            retainReservations(req.getId());
            recordFeeBlockedAudit(req, feeAssessment, feeEntity != null ? feeEntity.getId() : null, actorUserId, assessAsLive);
            return new PrepareResult(true, null, null, null, null, null, null, null);
        }

        // Admin runtime gate: a NEW real POST /v1/payouts requires the Admin LIVE switch, a configured maximum
        // test amount and a single-use per-refund authorization granted by the typed-confirmation workflow.
        // Production never injects a custom transport, so this is always enforced for real LIVE dispatch.
        if (enforceStrictLive && (customTransportExecutor == null || enforceAdminGateWithCustomTransport)) {
            if (livePayoutGate == null) {
                throw new BusinessException(HttpStatus.CONFLICT, "LIVE_ADMIN_GATE_UNAVAILABLE",
                        "Admin live payout gate is unavailable; live dispatch is blocked (fail-closed).");
            }
            livePayoutGate.consumeDispatchAuthorizationOrThrow(refundRequestId, amountVnd);
        }

        req.setPayoutBlockedReason(null);

        List<PayOsPayoutTransaction> priorAttempts = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(refundRequestId);
        int nextAttempt = priorAttempts.isEmpty() ? 1 : priorAttempts.get(0).getAttemptCount() + 1;
        String referenceId = "RFD-" + req.getId() + "-A" + nextAttempt;
        String idempotencyKey = UUID.randomUUID().toString();
        String description = sanitizeDescription("BOX REFUND " + req.getId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("referenceId", referenceId);
        payload.put("amount", amountVnd);
        payload.put("description", description);
        payload.put("toBin", req.getToBin().trim());
        payload.put("toAccountNumber", plainAccount);
        payload.put("category", List.of("refund"));

        String signingKey = !isBlank(trimSafe(checksumKey))
                ? trimSafe(checksumKey)
                : (isLive ? "" : "MOCK_PAYOUT_CHECKSUM_KEY");
        if (isBlank(signingKey)) {
            throw new IllegalStateException("PAYOS_PAYOUT_CHECKSUM_KEY is required in LIVE mode.");
        }
        String sig = PayOsPayoutSignatureUtil.signPayload(payload, signingKey);

        PayOsPayoutTransaction tx = new PayOsPayoutTransaction();
        tx.setRefundRequest(req);
        tx.setReferenceId(referenceId);
        tx.setBatchCode(batchCode);
        tx.setIdempotencyKey(idempotencyKey);
        tx.setPayoutMode("LIVE".equalsIgnoreCase(trimSafe(mode)) ? "LIVE" : "MOCK");
        tx.setAmount(amountVnd);
        tx.setDescription(description);
        tx.setToBin(req.getToBin().trim());
        tx.setToAccountNumberMasked(req.getToAccountNumberMasked());
        tx.setToAccountHolderName(req.getAccountHolderName());
        tx.setCategory("refund");
        tx.setStatus("PENDING");
        tx.setTransactionState("PENDING");
        tx.setAttemptCount(nextAttempt);
        tx.setActiveRequestLock(refundRequestId);
        tx.setInitiatedBy(actorUserId);
        tx = payoutRepository.save(tx);

        if (feeEntity != null && feeAssessmentRepository != null) {
            feeEntity.setPayoutTransactionId(tx.getId());
            feeAssessmentRepository.save(feeEntity);
        }

        req.setWorkflowStatus("PROCESSING");
        req.setPayoutStatus("PROCESSING");
        req.setRequestStatus("PAYOUT_PROCESSING");
        refundRequestRepository.save(req);

        return new PrepareResult(false, null, null, tx.getId(), referenceId, idempotencyKey, sig, payload);
    }

    private HttpOutcome executeHttpPost(Map<String, Object> payloadMap, String idempotencyKey, String signature) {
        String url = effectiveBaseUrl() + "/v1/payouts";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-client-id", trimSafe(clientId));
        headers.set("x-api-key", trimSafe(apiKey));
        headers.set("x-idempotency-key", idempotencyKey);
        headers.set("x-signature", signature);

        if (customTransportExecutor != null) {
            return customTransportExecutor.postPayout(url, headers, payloadMap);
        }
        try {
            String json = objectMapper.writeValueAsString(payloadMap);
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
            return new HttpOutcome(resp.getStatusCode().value(), resp.getBody(), resp.getHeaders().getFirst("x-signature"), false, null);
        } catch (ResourceAccessException ex) {
            return new HttpOutcome(0, null, null, true, "Timeout calling PayOS payout API");
        } catch (HttpStatusCodeException ex) {
            return new HttpOutcome(ex.getStatusCode().value(), ex.getResponseBodyAsString(),
                    ex.getResponseHeaders() != null ? ex.getResponseHeaders().getFirst("x-signature") : null, false, ex.getStatusText());
        } catch (Exception ex) {
            return new HttpOutcome(500, null, null, true, "Unexpected payout transport error: " + ex.getClass().getSimpleName());
        }
    }

    private HttpOutcome executeBalanceGet(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-client-id", trimSafe(clientId));
        headers.set("x-api-key", trimSafe(apiKey));

        if (customTransportExecutor != null) {
            return customTransportExecutor.getBalance(url, headers);
        }
        return performRestGet(url, headers);
    }

    private HttpOutcome executeHttpGet(String url) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-client-id", trimSafe(clientId));
        headers.set("x-api-key", trimSafe(apiKey));

        if (customTransportExecutor != null) {
            return customTransportExecutor.getPayout(url, headers);
        }
        return performRestGet(url, headers);
    }

    private HttpOutcome performRestGet(String url, HttpHeaders headers) {
        try {
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return new HttpOutcome(resp.getStatusCode().value(), resp.getBody(), resp.getHeaders().getFirst("x-signature"), false, null);
        } catch (ResourceAccessException ex) {
            return new HttpOutcome(0, null, null, true, "Timeout contacting PayOS payout API");
        } catch (HttpStatusCodeException ex) {
            return new HttpOutcome(ex.getStatusCode().value(), ex.getResponseBodyAsString(),
                    ex.getResponseHeaders() != null ? ex.getResponseHeaders().getFirst("x-signature") : null, false, ex.getStatusText());
        } catch (Exception ex) {
            return new HttpOutcome(500, null, null, true, "Unexpected payout GET error: " + ex.getClass().getSimpleName());
        }
    }

    private HttpOutcome executeMockPayout(Map<String, Object> payload, String referenceId) {
        try {
            Map<String, Object> txn = new LinkedHashMap<>();
            txn.put("id", "mock_txn_" + UUID.randomUUID().toString().substring(0, 8));
            txn.put("referenceId", referenceId);
            txn.put("amount", payload.get("amount"));
            txn.put("description", payload.get("description"));
            txn.put("toBin", payload.get("toBin"));
            txn.put("toAccountNumber", payload.get("toAccountNumber"));
            txn.put("toAccountName", "MOCK RECIPIENT");
            txn.put("reference", "FT-MOCK-" + System.currentTimeMillis());
            txn.put("transactionDatetime", LocalDateTime.now().toString());
            txn.put("errorMessage", null);
            txn.put("errorCode", null);
            txn.put("state", "SUCCEEDED");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", "MOCK-PO-" + referenceId);
            data.put("referenceId", referenceId);
            data.put("transactions", List.of(txn));
            data.put("category", List.of("refund"));
            data.put("approvalState", "COMPLETED");
            data.put("createdAt", LocalDateTime.now().toString());

            String key = !isBlank(trimSafe(checksumKey)) ? trimSafe(checksumKey) : "MOCK_PAYOUT_CHECKSUM_KEY";
            String sig = PayOsPayoutSignatureUtil.signPayload(data, key);

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("code", "00");
            envelope.put("desc", "success");
            envelope.put("data", data);
            return new HttpOutcome(200, objectMapper.writeValueAsString(envelope), sig, false, null);
        } catch (Exception e) {
            return new HttpOutcome(500, null, null, true, e.getMessage());
        }
    }

    private HttpOutcome executeMockGet(String payoutId, String referenceId, Long amount, String toBin) {
        try {
            Map<String, Object> txn = new LinkedHashMap<>();
            txn.put("id", "mock_txn_" + referenceId);
            txn.put("referenceId", referenceId);
            txn.put("amount", amount);
            txn.put("state", "SUCCEEDED");

            Map<String, Object> payout = new LinkedHashMap<>();
            payout.put("id", payoutId != null ? payoutId : "MOCK-PO-" + referenceId);
            payout.put("referenceId", referenceId);
            payout.put("transactions", List.of(txn));
            payout.put("approvalState", "COMPLETED");

            Map<String, Object> data = payoutId != null
                    ? payout
                    : Map.of("payouts", List.of(payout));
            String key = !isBlank(trimSafe(checksumKey)) ? trimSafe(checksumKey) : "MOCK_PAYOUT_CHECKSUM_KEY";
            String sig = PayOsPayoutSignatureUtil.signPayload(data, key);

            Map<String, Object> envelope = Map.of("code", "00", "desc", "success", "data", data);
            return new HttpOutcome(200, objectMapper.writeValueAsString(envelope), sig, false, null);
        } catch (Exception e) {
            return new HttpOutcome(500, null, null, true, e.getMessage());
        }
    }

    private PayOsPayoutTransaction finalizeOutcomeTx2(Long txId, Long refundRequestId, Long bookingId, HttpOutcome outcome, Long actorUserId) {
        RefundRequest req = lockRefundRequestInCanonicalOrder(refundRequestId, bookingId);
        PayOsPayoutTransaction tx = payoutRepository.findByIdForUpdate(txId).orElseThrow();

        if ("SUCCEEDED".equalsIgnoreCase(tx.getStatus())) {
            return tx;
        }

        if (outcome.body() != null) {
            tx.setRawResponseSanitized(sanitizeErrorBody(outcome.body()));
        }

        if (outcome.networkTimeout() || outcome.httpStatus() >= 500 || outcome.httpStatus() == 429) {
            tx.setStatus("PROCESSING");
            tx.setTransactionState("UNKNOWN_OUTCOME");
            tx.setErrorMessage(sanitizeErrorBody(outcome.errorMessage() != null ? outcome.errorMessage() : ("PayOS HTTP " + outcome.httpStatus() + "; reconciliation required.")));
            tx.setLastReconciledAt(LocalDateTime.now());
            req.setWorkflowStatus("UNKNOWN");
            req.setPayoutStatus("PROCESSING");
            recomputeOverallStatus(req);
            payoutRepository.save(tx);
            refundRequestRepository.save(req);
            retainReservations(req.getId());
            recordAudit(req, tx, "PAYOUT_UNKNOWN_OUTCOME", actorUserId);
            return tx;
        }

        if (outcome.httpStatus() == 401 || outcome.httpStatus() == 403) {
            tx.setLastReconciledAt(LocalDateTime.now());
            markPayoutFailed(tx, req, String.valueOf(outcome.httpStatus()), "PayOS rejected credentials or IP whitelist.");
            recordAudit(req, tx, "PAYOUT_" + tx.getStatus(), actorUserId);
            return tx;
        }

        boolean is2xx = outcome.httpStatus() >= 200 && outcome.httpStatus() < 300;
        boolean isClientError4xx = outcome.httpStatus() >= 400 && outcome.httpStatus() < 500;

        try {
            if (isBlank(outcome.body())) {
                if (isClientError4xx) {
                    tx.setLastReconciledAt(LocalDateTime.now());
                    markPayoutFailed(tx, req, String.valueOf(outcome.httpStatus()),
                            "PayOS rejected payout request with HTTP " + outcome.httpStatus());
                    recordAudit(req, tx, "PAYOUT_" + tx.getStatus(), actorUserId);
                    return tx;
                }
                throw new IllegalStateException("Empty PayOS response body.");
            }
            JsonNode root = objectMapper.readTree(outcome.body());
            String code = root.path("code").asText("");
            String desc = root.path("desc").asText("");
            tx.setPayosResponseCode(code);
            tx.setPayosResponseDesc(sanitizeErrorBody(desc));

            if (!is2xx) {
                if (INTERRUPTED_CODES.contains(code)) {
                    tx.setStatus("PROCESSING");
                    tx.setTransactionState("UNKNOWN_OUTCOME");
                    tx.setErrorMessage(sanitizeErrorBody("PayOS interrupted (" + code + "): " + desc));
                    req.setWorkflowStatus("UNKNOWN");
                    req.setPayoutStatus("PROCESSING");
                    retainReservations(req.getId());
                } else if (isClientError4xx) {
                    tx.setLastReconciledAt(LocalDateTime.now());
                    String failCode = !isBlank(code) ? code : String.valueOf(outcome.httpStatus());
                    String failReason = !isBlank(desc) ? desc : ("PayOS rejected payout request with HTTP " + outcome.httpStatus());
                    markPayoutFailed(tx, req, failCode, failReason);
                    recordAudit(req, tx, "PAYOUT_" + tx.getStatus(), actorUserId);
                    return tx;
                } else {
                    tx.setStatus("PROCESSING");
                    tx.setTransactionState("UNKNOWN_OUTCOME");
                    tx.setErrorMessage("Unexpected PayOS HTTP status " + outcome.httpStatus() + "; awaiting reconciliation.");
                    req.setWorkflowStatus("UNKNOWN");
                    req.setPayoutStatus("PROCESSING");
                    retainReservations(req.getId());
                }
            } else if (!"00".equals(code)) {
                if (INTERRUPTED_CODES.contains(code)) {
                    tx.setStatus("PROCESSING");
                    tx.setTransactionState("UNKNOWN_OUTCOME");
                    tx.setErrorMessage(sanitizeErrorBody("PayOS interrupted (" + code + "): " + desc));
                    req.setWorkflowStatus("UNKNOWN");
                    req.setPayoutStatus("PROCESSING");
                    retainReservations(req.getId());
                } else {
                    tx.setLastReconciledAt(LocalDateTime.now());
                    markPayoutFailed(tx, req, code, desc);
                    recordAudit(req, tx, "PAYOUT_" + tx.getStatus(), actorUserId);
                    return tx;
                }
            } else {
                JsonNode dataNode = root.path("data");
                if (!verifyRestResponseSignature(root, dataNode, outcome.signatureHeader(), tx.getPayoutMode())) {
                    tx.setStatus("PROCESSING");
                    tx.setTransactionState("UNKNOWN_OUTCOME");
                    tx.setErrorMessage("PayOS response signature verification failed; queued for reconciliation.");
                    req.setWorkflowStatus("UNKNOWN");
                    req.setPayoutStatus("PROCESSING");
                    retainReservations(req.getId());
                } else {
                    applyPayOsPayoutData(tx, req, dataNode);
                }
            }
        } catch (Exception e) {
            if (isClientError4xx) {
                tx.setLastReconciledAt(LocalDateTime.now());
                markPayoutFailed(tx, req, String.valueOf(outcome.httpStatus()),
                        "PayOS rejected payout request with HTTP " + outcome.httpStatus());
                recordAudit(req, tx, "PAYOUT_" + tx.getStatus(), actorUserId);
                return tx;
            }
            tx.setStatus("PROCESSING");
            tx.setTransactionState("UNKNOWN_OUTCOME");
            tx.setErrorMessage("Unparseable PayOS response; awaiting reconciliation.");
            req.setWorkflowStatus("UNKNOWN");
            req.setPayoutStatus("PROCESSING");
            retainReservations(req.getId());
        }

        tx.setLastReconciledAt(LocalDateTime.now());
        recomputeOverallStatus(req);
        payoutRepository.save(tx);
        refundRequestRepository.save(req);
        recordAudit(req, tx, "UNKNOWN_OUTCOME".equalsIgnoreCase(tx.getTransactionState()) ? "PAYOUT_UNKNOWN_OUTCOME" : ("PAYOUT_" + tx.getStatus()), actorUserId);
        return tx;
    }

    private PayOsPayoutTransaction applyReconcileOutcomeTx(Long refundRequestId, Long bookingId, Long txId, String referenceId, HttpOutcome outcome, Long actorUserId, String triggerSource) {
        RefundRequest req = lockRefundRequestInCanonicalOrder(refundRequestId, bookingId);
        PayOsPayoutTransaction tx = payoutRepository.findByIdForUpdate(txId).orElseThrow();

        if ("SUCCEEDED".equalsIgnoreCase(tx.getStatus())) {
            return tx;
        }
        String prevState = tx.getStatus() + ":" + (tx.getTransactionState() != null ? tx.getTransactionState() : "");
        tx.setLastReconciledAt(LocalDateTime.now());
        if (outcome.body() != null) {
            tx.setRawResponseSanitized(sanitizeErrorBody(outcome.body()));
        }

        if (outcome.networkTimeout() || outcome.httpStatus() >= 500 || outcome.httpStatus() == 429 || isBlank(outcome.body())) {
            tx.setTransactionState("UNKNOWN_OUTCOME");
            req.setWorkflowStatus("UNKNOWN");
            req.setPayoutStatus("PROCESSING");
            payoutRepository.save(tx);
            refundRequestRepository.save(req);
            retainReservations(req.getId());
            recordReconciliationLog(tx, req, triggerSource, prevState, "UNKNOWN",
                    String.valueOf(outcome.httpStatus()),
                    outcome.errorMessage() != null ? outcome.errorMessage() : "Gateway unreachable/timeout during reconciliation",
                    actorUserId, Map.of("referenceId", referenceId, "httpStatus", outcome.httpStatus(), "timeout", outcome.networkTimeout()));
            return tx;
        }

        try {
            JsonNode root = objectMapper.readTree(outcome.body());
            String code = root.path("code").asText("");
            String desc = sanitizeErrorBody(root.path("desc").asText(""));
            tx.setPayosResponseCode(code);
            tx.setPayosResponseDesc(desc);

            boolean graceElapsed = tx.getInitiatedAt() != null
                    && tx.getInitiatedAt().isBefore(LocalDateTime.now().minusSeconds(Math.max(1, notFoundGraceSeconds)));
            boolean isLiveMode = "LIVE".equalsIgnoreCase(tx.getPayoutMode()) || "LIVE".equalsIgnoreCase(trimSafe(mode));
            boolean allowAutoFail = !isLiveMode || allowAutoFailOnNotFound;

            if ("608".equals(code)) {
                if (graceElapsed) {
                    if (allowAutoFail) {
                        markPayoutFailed(tx, req, code, "Payout not found on PayOS (" + referenceId + ")");
                    } else {
                        markManualReconcileRequired(tx, req,
                                "PayOS returned 608 (not found) after grace window; manual verification on my.payos.vn required before releasing lock.",
                                actorUserId);
                    }
                } else {
                    payoutRepository.save(tx);
                }
                recordReconciliationLog(tx, req, triggerSource, prevState,
                        tx.getStatus() + ":" + (tx.getTransactionState() != null ? tx.getTransactionState() : ""),
                        code, desc, actorUserId, Map.of("referenceId", referenceId, "graceElapsed", graceElapsed, "workflowStatus", req.getWorkflowStatus()));
                return tx;
            }
            if (!"00".equals(code)) {
                payoutRepository.save(tx);
                recordReconciliationLog(tx, req, triggerSource, prevState,
                        tx.getStatus() + ":" + (tx.getTransactionState() != null ? tx.getTransactionState() : ""),
                        code, desc, actorUserId, Map.of("referenceId", referenceId, "workflowStatus", req.getWorkflowStatus()));
                return tx;
            }

            JsonNode dataNode = root.path("data");
            if (!verifyRestResponseSignature(root, dataNode, outcome.signatureHeader(), tx.getPayoutMode())) {
                tx.setTransactionState("UNKNOWN_OUTCOME");
                tx.setErrorMessage("Response signature mismatch during reconciliation.");
                req.setWorkflowStatus("UNKNOWN");
                req.setPayoutStatus("PROCESSING");
                payoutRepository.save(tx);
                refundRequestRepository.save(req);
                retainReservations(req.getId());
                recordReconciliationLog(tx, req, triggerSource, prevState, "UNKNOWN",
                        "INVALID_SIGNATURE", "Response x-signature verification failed during reconciliation",
                        actorUserId, Map.of("referenceId", referenceId, "workflowStatus", req.getWorkflowStatus()));
                return tx;
            }

            JsonNode targetNode = dataNode;
            if (dataNode.has("payouts") && dataNode.get("payouts").isArray()) {
                targetNode = null;
                for (JsonNode p : dataNode.get("payouts")) {
                    if (referenceId.equals(p.path("referenceId").asText(""))) {
                        targetNode = p;
                        break;
                    }
                }
                if (targetNode == null) {
                    if (graceElapsed) {
                        if (allowAutoFail) {
                            markPayoutFailed(tx, req, "NOT_FOUND", "No matching PayOS payout for referenceId " + referenceId);
                        } else {
                            markManualReconcileRequired(tx, req,
                                    "No matching PayOS payout for referenceId " + referenceId + " after grace window; manual verification on my.payos.vn required.",
                                    actorUserId);
                        }
                    } else {
                        payoutRepository.save(tx);
                    }
                    recordReconciliationLog(tx, req, triggerSource, prevState,
                            tx.getStatus() + ":" + (tx.getTransactionState() != null ? tx.getTransactionState() : ""),
                            "NOT_FOUND", "No matching payout in PayOS list",
                            actorUserId, Map.of("referenceId", referenceId, "graceElapsed", graceElapsed, "workflowStatus", req.getWorkflowStatus()));
                    return tx;
                }
            }

            applyPayOsPayoutData(tx, req, targetNode);
            recomputeOverallStatus(req);
            payoutRepository.save(tx);
            refundRequestRepository.save(req);
            recordReconciliationLog(tx, req, triggerSource, prevState,
                    tx.getStatus() + ":" + (tx.getTransactionState() != null ? tx.getTransactionState() : ""),
                    code, desc, actorUserId,
                    Map.of("referenceId", referenceId, "approvalState", tx.getApprovalState() != null ? tx.getApprovalState() : "",
                            "transactionState", tx.getTransactionState() != null ? tx.getTransactionState() : "",
                            "workflowStatus", req.getWorkflowStatus()));
            recordAudit(req, tx, "PAYOUT_RECONCILED_" + tx.getStatus(), actorUserId);
            return tx;
        } catch (Exception e) {
            tx.setTransactionState("UNKNOWN_OUTCOME");
            tx.setErrorMessage("Reconciliation parse error; will retry.");
            req.setWorkflowStatus("UNKNOWN");
            req.setPayoutStatus("PROCESSING");
            payoutRepository.save(tx);
            refundRequestRepository.save(req);
            retainReservations(req.getId());
            recordReconciliationLog(tx, req, triggerSource, prevState, "UNKNOWN",
                    "PARSE_ERROR", "Reconciliation parse error",
                    actorUserId, Map.of("referenceId", referenceId, "error", e.getClass().getSimpleName()));
            return tx;
        }
    }

    private void applyPayOsPayoutData(PayOsPayoutTransaction tx, RefundRequest req, JsonNode dataNode) {
        applyPayOsPayoutData(tx, req, dataNode, null);
    }

    private void applyPayOsPayoutData(PayOsPayoutTransaction tx, RefundRequest req, JsonNode dataNode, JsonNode preMatchedTxn) {
        if (dataNode.hasNonNull("referenceId") && !tx.getReferenceId().equals(dataNode.path("referenceId").asText(""))) {
            markPayoutUnknownOutcome(tx, req, "Reference ID mismatch between local payout and PayOS response; manual review required.");
            return;
        }

        String payosId = textOrNull(dataNode.get("id"));
        if (!isBlank(payosId) && !isBlank(tx.getPayosPayoutId()) && !tx.getPayosPayoutId().equals(payosId)) {
            markPayoutUnknownOutcome(tx, req, "PayOS payout ID mismatch between local payout and PayOS response; manual review required.");
            return;
        }
        String approvalState = dataNode.path("approvalState").asText("");
        if (!isBlank(payosId)) tx.setPayosPayoutId(payosId);
        tx.setApprovalState(approvalState);

        String txnState = "";
        JsonNode matchedTxn = preMatchedTxn;
        if (matchedTxn == null) {
            JsonNode txns = dataNode.path("transactions");
            if (txns.isArray() && !txns.isEmpty()) {
                for (JsonNode item : txns) {
                    if (item.hasNonNull("referenceId") && tx.getReferenceId().equals(item.path("referenceId").asText(""))) {
                        matchedTxn = item;
                        break;
                    }
                }
                if (matchedTxn == null) {
                    matchedTxn = txns.get(0);
                }
            }
        }
        if (matchedTxn != null) {
            if (matchedTxn.hasNonNull("referenceId") && !tx.getReferenceId().equals(matchedTxn.path("referenceId").asText(""))) {
                markPayoutUnknownOutcome(tx, req, "Transaction reference ID mismatch between local payout and PayOS response; manual review required.");
                return;
            }
            String incomingToBin = textOrNull(matchedTxn.get("toBin"));
            if (!isBlank(incomingToBin) && !isBlank(tx.getToBin()) && !tx.getToBin().equals(incomingToBin)) {
                markPayoutUnknownOutcome(tx, req, "Recipient bank BIN mismatch between local payout and PayOS response; manual review required.");
                return;
            }
            String incomingToAcc = textOrNull(matchedTxn.get("toAccountNumber"));
            if (!matchesBeneficiaryAccount(req, incomingToAcc)) {
                markPayoutUnknownOutcome(tx, req, "Recipient bank account number mismatch between local payout and PayOS response; manual review required.");
                return;
            }
            String tId = textOrNull(matchedTxn.get("id"));
            String provRef = textOrNull(matchedTxn.get("reference"));
            txnState = matchedTxn.path("state").asText("");
            if (!matchedTxn.hasNonNull("amount") || !matchedTxn.path("amount").canConvertToLong()) {
                markPayoutUnknownOutcome(tx, req, "Amount missing or invalid between local payout and PayOS response; manual review required.");
                return;
            }
            long respAmt = matchedTxn.path("amount").asLong(-1L);
            if (!isBlank(tId)) tx.setPayosTransactionId(tId);
            if (!isBlank(provRef)) tx.setProviderReference(provRef);
            tx.setTransactionState(txnState);

            if (respAmt <= 0 || tx.getAmount() == null || respAmt != tx.getAmount()) {
                markPayoutUnknownOutcome(tx, req, "Amount mismatch between local payout and PayOS response; manual review required.");
                return;
            }
            if (!matchedTxn.path("errorMessage").isNull() && !isBlank(matchedTxn.path("errorMessage").asText())) {
                tx.setErrorMessage(sanitizeErrorBody(matchedTxn.path("errorMessage").asText()));
            }
        }

        String upperTxnState = txnState != null ? txnState.trim().toUpperCase(Locale.ROOT) : "";
        String upperApproval = approvalState != null ? approvalState.trim().toUpperCase(Locale.ROOT) : "";

        if ("SUCCEEDED".equals(upperTxnState) && isCompletedApprovalState(upperApproval)) {
            tx.setStatus("SUCCEEDED");
            tx.setTransactionState("SUCCEEDED");
            tx.setErrorMessage(null);
            tx.setSucceededAt(LocalDateTime.now());
            if (tx.getActiveRequestLock() == null) {
                boolean canTakeLock = payoutRepository.findByActiveRequestLock(req.getId())
                        .map(existing -> existing.getId().equals(tx.getId()))
                        .orElse(true);
                if (canTakeLock) {
                    tx.setActiveRequestLock(req.getId());
                }
            }
            req.setWorkflowStatus("SUCCESS");
            req.setPayoutStatus("SUCCEEDED");
            recomputeOverallStatus(req);
            commitReservations(req.getId());
            refundEmailService.enqueuePayoutSucceededEmail(req, tx);
        } else if (Set.of("FAILED", "CANCELLED", "REVERSED").contains(upperTxnState)
                || Set.of("FAILED", "REJECTED", "CANCELLED").contains(upperApproval)) {
            markPayoutFailed(tx, req, tx.getPayosResponseCode(),
                    !isBlank(tx.getErrorMessage()) ? tx.getErrorMessage() : ("PayOS state: " + approvalState + "/" + txnState));
        } else if ("ON_HOLD".equals(upperTxnState)) {
            // Official PayOS PayoutTransactionState.ON_HOLD: funds under review -> PROCESSING, never SUCCESS
            tx.setStatus("PROCESSING");
            tx.setTransactionState("ON_HOLD");
            req.setWorkflowStatus("PROCESSING");
            req.setPayoutStatus("PROCESSING");
            req.setRequestStatus("PAYOUT_PROCESSING");
            retainReservations(req.getId());
        } else if ("PARTIAL_COMPLETED".equals(upperApproval)) {
            // Official PayOS PayoutApprovalState.PARTIAL_COMPLETED without terminal item SUCCEEDED -> UNKNOWN until reconciled
            markPayoutUnknownOutcome(tx, req, "PayOS reported PARTIAL_COMPLETED without terminal SUCCEEDED transaction state; reconciliation required.");
        } else if (Set.of("RECEIVED", "PROCESSING").contains(upperTxnState)
                || Set.of("DRAFTING", "SUBMITTED", "APPROVED", "SCHEDULED", "PROCESSING").contains(upperApproval)) {
            tx.setStatus("PROCESSING");
            if (isBlank(tx.getTransactionState())) {
                tx.setTransactionState(!upperTxnState.isEmpty() ? upperTxnState : "PROCESSING");
            }
            req.setWorkflowStatus("PROCESSING");
            req.setPayoutStatus("PROCESSING");
            req.setRequestStatus("PAYOUT_PROCESSING");
            retainReservations(req.getId());
        } else {
            markPayoutUnknownOutcome(tx, req, "Ambiguous PayOS payout state (" + approvalState + "/" + txnState + "); reconciliation required.");
        }
    }

    private void markPayoutUnknownOutcome(PayOsPayoutTransaction tx, RefundRequest req, String reason) {
        tx.setStatus("PROCESSING");
        tx.setTransactionState("UNKNOWN_OUTCOME");
        tx.setErrorMessage(sanitizeErrorBody(reason));
        req.setWorkflowStatus("UNKNOWN");
        req.setPayoutStatus("PROCESSING");
        req.setRequestStatus("PAYOUT_PROCESSING");
        retainReservations(req.getId());
    }

    private void markManualReconcileRequired(PayOsPayoutTransaction tx, RefundRequest req, String reason, Long actorUserId) {
        tx.setStatus("PROCESSING");
        tx.setTransactionState("MANUAL_RECONCILE_REQUIRED");
        tx.setErrorMessage(sanitizeErrorBody(reason));
        req.setWorkflowStatus("UNKNOWN");
        req.setPayoutStatus("PROCESSING");
        recomputeOverallStatus(req);
        payoutRepository.save(tx);
        refundRequestRepository.save(req);
        retainReservations(req.getId());
        recordAudit(req, tx, "PAYOUT_608_MANUAL_REVIEW_REQUIRED", actorUserId);
    }

    private void markPayoutFailed(PayOsPayoutTransaction tx, RefundRequest req, String code, String reason) {
        tx.setStatus("FAILED");
        tx.setTransactionState("FAILED");
        tx.setPayosResponseCode(code);
        tx.setErrorMessage(sanitizeErrorBody(reason));
        tx.setFailedAt(LocalDateTime.now());
        tx.setActiveRequestLock(null);
        if (req != null) {
            req.setWorkflowStatus("FAILED");
            req.setPayoutStatus("FAILED");
            recomputeOverallStatus(req);
            refundRequestRepository.save(req);
            releaseReservations(req.getId(), "Payout failed (" + code + "): " + reason);
        }
        payoutRepository.save(tx);
    }

    private static boolean isCompletedApprovalState(String approvalState) {
        return "COMPLETED".equalsIgnoreCase(approvalState) || "SUCCEEDED".equalsIgnoreCase(approvalState);
    }

    private boolean matchesBeneficiaryAccount(RefundRequest req, String incomingAccountNumber) {
        if (isBlank(incomingAccountNumber) || incomingAccountNumber.contains("*") || req == null || bankEncryptionService == null) {
            return true;
        }
        String cleanIncoming = incomingAccountNumber.replaceAll("\\s+", "");
        if (!isBlank(req.getToAccountNumberHash())) {
            String incomingHash = bankEncryptionService.hashAccountNumber(cleanIncoming);
            return MessageDigest.isEqual(
                    req.getToAccountNumberHash().trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                    incomingHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        }
        if (!isBlank(req.getToAccountNumberEncrypted())) {
            try {
                String expectedPlain = bankEncryptionService.decryptAccountNumber(req.getToAccountNumberEncrypted()).replaceAll("\\s+", "");
                return MessageDigest.isEqual(
                        expectedPlain.getBytes(StandardCharsets.UTF_8),
                        cleanIncoming.getBytes(StandardCharsets.UTF_8));
            } catch (RuntimeException ex) {
                return false;
            }
        }
        return true;
    }

    /**
     * Official PayOS Payout v1 response x-signature verification (aligned with payos-lib-java v2.0.2):
     * - In LIVE mode: HTTP response header `x-signature` is REQUIRED on POST /v1/payouts,
     *   GET /v1/payouts/{id}, GET /v1/payouts, and GET /v1/payouts-account/balance (fail-closed if missing or invalid).
     * - If both header x-signature and body signature are present, require constant-time equality.
     * - In MOCK mode: if either header or body signature is present, verify strictly; if neither is present, allow.
     */
    private boolean verifyRestResponseSignature(JsonNode root, JsonNode dataNode, String signatureHeader, String payoutMode) {
        if (dataNode == null || !dataNode.isObject() || dataNode.isEmpty()) {
            return false;
        }
        String headerSig = !isBlank(signatureHeader) ? signatureHeader.trim() : null;
        String bodySig = null;
        if (root != null && root.has("signature") && !root.get("signature").isNull()) {
            JsonNode sigNode = root.get("signature");
            if (!sigNode.isTextual()) {
                return false;
            }
            String trimmedBodySig = sigNode.asText("").trim();
            if (!trimmedBodySig.isEmpty()) {
                bodySig = trimmedBodySig;
            }
        }
        if (headerSig != null && bodySig != null && !MessageDigest.isEqual(
                headerSig.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                bodySig.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        boolean isLive = "LIVE".equalsIgnoreCase(trimSafe(payoutMode)) || "LIVE".equalsIgnoreCase(trimSafe(mode));
        if (isLive && headerSig == null) {
            return false;
        }
        String effectiveSig = headerSig != null ? headerSig : bodySig;
        if (effectiveSig == null) {
            return true;
        }
        String key = !isBlank(trimSafe(checksumKey))
                ? trimSafe(checksumKey)
                : (isLive ? "" : "MOCK_PAYOUT_CHECKSUM_KEY");
        if (isBlank(key)) {
            return false;
        }
        Map<String, Object> dataMap = objectMapper.convertValue(dataNode, new TypeReference<>() {});
        return PayOsPayoutSignatureUtil.verifySignature(dataMap, effectiveSig, key);
    }

    private void verifyRecipientSnapshotBeforePayout(RefundRequest req, long approvedAmountVnd, String plainAccount, Long actorUserId) {
        String computedAccountHash = bankEncryptionService.hashAccountNumber(plainAccount.replaceAll("\\s+", ""));
        if (isBlank(req.getRecipientSnapshotHash())) {
            if (isBlank(req.getToAccountNumberHash())) {
                req.setToAccountNumberHash(computedAccountHash);
            }
            String json = RefundApprovalTxService.buildCanonicalRecipientSnapshotJson(req, approvedAmountVnd, computedAccountHash);
            req.setRecipientSnapshotJson(json);
            req.setRecipientSnapshotHash(CryptoUtils.sha256(json));
            return;
        }
        String expectedAccountHash = !isBlank(req.getToAccountNumberHash())
                ? req.getToAccountNumberHash().trim().toLowerCase(Locale.ROOT)
                : computedAccountHash.toLowerCase(Locale.ROOT);
        boolean encryptedMatchesStoredHash = MessageDigest.isEqual(
                computedAccountHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                expectedAccountHash.getBytes(StandardCharsets.UTF_8));
        String currentJson = RefundApprovalTxService.buildCanonicalRecipientSnapshotJson(req, approvedAmountVnd, computedAccountHash);
        String currentHash = CryptoUtils.sha256(currentJson);
        String storedHash = req.getRecipientSnapshotHash().trim();
        boolean hashMatches = MessageDigest.isEqual(
                storedHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                currentHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        boolean storedJsonMatches = isBlank(req.getRecipientSnapshotJson())
                || MessageDigest.isEqual(
                        CryptoUtils.sha256(req.getRecipientSnapshotJson()).toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                        storedHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        if (!encryptedMatchesStoredHash || !hashMatches || !storedJsonMatches) {
            Long cinemaId = req.getCinema() != null ? req.getCinema().getCinemaId() : null;
            String detailsJson = serializeAuditDetails(Map.of(
                    "storedHash", storedHash,
                    "currentHash", currentHash,
                    "encryptedMatchesStoredHash", encryptedMatchesStoredHash
            ));
            SnapshotTamperAbort abort = new SnapshotTamperAbort(req.getId(), cinemaId, actorUserId, detailsJson);
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                throw abort;
            }
            persistTamperAuditAfterRollback(abort);
            throw new BusinessException(HttpStatus.CONFLICT, "RECIPIENT_SNAPSHOT_TAMPERED",
                    "Recipient bank snapshot mismatch detected before PayOS payout submission; dispatch aborted.");
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

    private PayoutFeeAssessmentEntity persistFeeAssessment(
            RefundRequest req, Long payoutTxId, PayoutFeeAssessment assessment, Long actorUserId) {
        if (feeAssessmentRepository == null || req == null || assessment == null) {
            return null;
        }
        PayoutFeeAssessmentEntity entity = new PayoutFeeAssessmentEntity();
        entity.setRefundRequestId(req.getId());
        entity.setPayoutTransactionId(payoutTxId);
        entity.setProviderName(assessment.providerName());
        entity.setFeeMode(assessment.classification().name());
        entity.setApprovedRefundVnd(assessment.approvedRefundVnd());
        entity.setEstimatedFeeVnd(assessment.estimatedFeeVnd());
        entity.setNetRecipientVnd(assessment.netRecipientVnd());
        entity.setTotalDebitVnd(assessment.totalDebitVnd());
        entity.setGuaranteedFullReceipt(assessment.guaranteedFullReceipt());
        entity.setBlocked(assessment.blocked());
        entity.setBlockReason(assessment.blocked() ? assessment.reason() : null);
        entity.setAssessedBy(actorUserId);
        entity.setAssessedAt(LocalDateTime.now());
        return feeAssessmentRepository.save(entity);
    }

    private void recordFeeBlockedAudit(
            RefundRequest req, PayoutFeeAssessment assessment, Long feeAssessmentId, Long actorUserId, boolean liveMode) {
        if (auditLogRepository == null || req == null) {
            return;
        }
        RefundAuditLog audit = new RefundAuditLog();
        audit.setRefundRequestId(req.getId());
        audit.setEntityType("PAYOUT_FEE_ASSESSMENT");
        audit.setEntityId(feeAssessmentId != null ? feeAssessmentId : req.getId());
        audit.setEventType("PAYOUT_BLOCKED_FEE_POLICY");
        audit.setActorUserId(actorUserId);
        audit.setCinemaId(req.getCinema() != null ? req.getCinema().getCinemaId() : null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("provider", assessment.providerName());
        details.put("feeMode", assessment.classification().name());
        details.put("approvedRefundVnd", assessment.approvedRefundVnd());
        details.put("estimatedFeeVnd", assessment.estimatedFeeVnd());
        details.put("netRecipientVnd", assessment.netRecipientVnd());
        details.put("guaranteedFullReceipt", assessment.guaranteedFullReceipt());
        details.put("liveMode", liveMode);
        details.put("reason", assessment.reason());
        audit.setDetailsJson(serializeAuditDetails(details));
        auditLogRepository.save(audit);
    }

    private void recordReconciliationLog(
            PayOsPayoutTransaction tx,
            RefundRequest req,
            String triggerSource,
            String previousState,
            String newState,
            String providerCode,
            String providerDesc,
            Long actorUserId,
            Map<String, Object> details) {
        if (reconciliationLogRepository == null || tx == null || tx.getId() == null || req == null || req.getId() == null) {
            return;
        }
        PayoutReconciliationLog logEntry = new PayoutReconciliationLog();
        logEntry.setPayoutTransactionId(tx.getId());
        logEntry.setRefundRequestId(req.getId());
        logEntry.setTriggerSource(truncate(triggerSource, 30, "MANUAL"));
        logEntry.setPreviousState(truncate(previousState, 30, null));
        logEntry.setNewState(truncate(newState, 30, "UNKNOWN"));
        logEntry.setProviderCode(truncate(providerCode, 30, null));
        logEntry.setProviderDesc(truncate(sanitizeErrorBody(providerDesc), 255, null));
        logEntry.setActorUserId(actorUserId);
        logEntry.setDetailsJson(details != null ? serializeAuditDetails(details) : "{}");
        logEntry.setCreatedAt(LocalDateTime.now());
        reconciliationLogRepository.save(logEntry);
    }

    private void commitReservations(Long refundRequestId) {
        if (refundLimitService != null && refundRequestId != null) {
            refundLimitService.commitReservationsOnPayoutSuccess(refundRequestId);
        }
    }

    private void retainReservations(Long refundRequestId) {
        if (refundLimitService != null && refundRequestId != null) {
            refundLimitService.retainReservationsOnProcessingOrUnknown(refundRequestId);
        }
    }

    private void releaseReservations(Long refundRequestId, String reason) {
        if (refundLimitService != null && refundRequestId != null) {
            refundLimitService.releaseReservationsOnFailureCancelOrReject(refundRequestId, reason);
        }
    }

    private static String truncate(String val, int maxLen, String fallback) {
        if (val == null || val.isBlank()) {
            return fallback;
        }
        String t = val.trim();
        return t.length() > maxLen ? t.substring(0, maxLen) : t;
    }

    public static void recomputeOverallStatus(RefundRequest req) {
        String current = req.getRequestStatus();
        if (current == null || !Set.of(
                "AWAITING_PAYOUT_APPROVAL", "PAYOUT_APPROVED", "PAYOUT_PROCESSING",
                "PAYOUT_SUCCEEDED", "PAYOUT_FAILED", "APPROVED", "PARTIALLY_APPROVED", "COMPLETED"
        ).contains(current)) {
            return;
        }
        String p = req.getPayoutStatus();
        String v = req.getVoucherStatus();
        boolean payoutDone = "SUCCEEDED".equalsIgnoreCase(p) || "NOT_REQUIRED".equalsIgnoreCase(p);
        boolean voucherDone = "GENERATED".equalsIgnoreCase(v) || "NOT_REQUIRED".equalsIgnoreCase(v);
        if (payoutDone && voucherDone) {
            req.setRequestStatus("COMPLETED");
        } else if ("SUCCEEDED".equalsIgnoreCase(p)) {
            req.setRequestStatus("PAYOUT_SUCCEEDED");
        } else if ("FAILED".equalsIgnoreCase(p)) {
            req.setRequestStatus("PAYOUT_FAILED");
        } else if ("PROCESSING".equalsIgnoreCase(p)) {
            req.setRequestStatus("PAYOUT_PROCESSING");
        }
    }

    private void recordAudit(RefundRequest req, PayOsPayoutTransaction tx, String eventType, Long actorUserId) {
        RefundAuditLog audit = new RefundAuditLog();
        audit.setRefundRequestId(req.getId());
        audit.setEntityType("PAYOUT");
        audit.setEntityId(tx.getId());
        audit.setEventType(eventType);
        audit.setActorUserId(actorUserId);
        audit.setCinemaId(req.getCinema() != null ? req.getCinema().getCinemaId() : null);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("referenceId", tx.getReferenceId());
        details.put("status", tx.getStatus());
        if (tx.getBatchCode() != null) {
            details.put("batchCode", tx.getBatchCode());
        }
        details.put("amount", tx.getAmount());
        audit.setDetailsJson(serializeAuditDetails(details));
        auditLogRepository.save(audit);
    }

    private String serializeAuditDetails(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String effectiveBaseUrl() {
        return !isBlank(trimSafe(baseUrl)) ? trimTrailingSlash(trimSafe(baseUrl)) : "https://api-merchant.payos.vn";
    }

    private static String encodeQueryParam(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String encodeUrlSegment(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sanitizeDescription(String raw) {
        String clean = raw.replaceAll("[^A-Za-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        return clean.length() > 25 ? clean.substring(0, 25).trim() : clean;
    }

    private String sanitizeWebhookRawBody(JsonNode root, String rawBody) {
        if (root != null && root.isObject()) {
            try {
                Map<String, Object> copy = objectMapper.convertValue(root, new TypeReference<>() {});
                maskSensitiveRawJsonRecursively(copy);
                return sanitizeErrorBody(objectMapper.writeValueAsString(copy));
            } catch (Exception ignored) {
                // Fall through to generic sanitizer
            }
        }
        return sanitizeErrorBody(rawBody);
    }

    private static final Set<String> SENSITIVE_RAW_JSON_KEYS = Set.of(
            "toaccountnumber", "accountnumber", "counteraccountnumber",
            "toaccountname", "accountname", "counteraccountname",
            "apikey", "x-api-key", "checksumkey", "x-checksum-key",
            "secret", "password", "token", "signature"
    );

    @SuppressWarnings("unchecked")
    private void maskSensitiveRawJsonRecursively(Object node) {
        if (node instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = (Map<String, Object>) rawMap;
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = entry.getKey() != null ? entry.getKey().toLowerCase(Locale.ROOT) : "";
                Object val = entry.getValue();
                if (SENSITIVE_RAW_JSON_KEYS.contains(key) && val != null) {
                    entry.setValue("******");
                } else if (val instanceof Map || val instanceof List) {
                    maskSensitiveRawJsonRecursively(val);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                maskSensitiveRawJsonRecursively(item);
            }
        }
    }

    private String sanitizeErrorBody(String raw) {
        if (raw == null) return null;
        String processed = raw;
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                Map<String, Object> parsed = objectMapper.readValue(trimmed, new TypeReference<>() {});
                maskSensitiveRawJsonRecursively(parsed);
                processed = objectMapper.writeValueAsString(parsed);
            } catch (Exception ignored) {
                // Keep raw text and apply regex masking below
            }
        }
        String masked = processed
                .replaceAll("\\b\\d{6,24}\\b", "******")
                .replaceAll("\\b\\d{2,6}(?:[-\\s]\\d{2,6}){2,5}\\b", "******");
        return masked.length() > 500 ? masked.substring(0, 500) : masked;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String s = node.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String sanitizeLogToken(String value) {
        if (value == null) return "null";
        String clean = value.replace("\r", "").replace("\n", "").trim();
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String trimSafe(String s) {
        return s == null ? "" : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
