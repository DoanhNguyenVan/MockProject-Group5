package com.boxcinema.refund;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BankDataEncryptionService;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.common.util.PayOsPayoutSignatureUtil;
import com.boxcinema.refund.service.PayOsPayoutService;
import com.boxcinema.refund.service.RefundEmailService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PayOsPayoutWebhookResilienceIntegrationTest {

    private static final String TEST_TAG = "PO_RES_" + System.currentTimeMillis();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository userRepository;
    @Autowired private CinemaRepository cinemaRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingSeatRepository bookingSeatRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRequestRepository refundRequestRepository;
    @Autowired private RefundRequestItemRepository refundRequestItemRepository;
    @Autowired private PayOsPayoutTransactionRepository payoutRepository;
    @Autowired private RefundAuditLogRepository auditLogRepository;
    @Autowired private EmailOutboxRepository emailOutboxRepository;
    @Autowired private BankDataEncryptionService bankEncryptionService;
    @Autowired private RefundEmailService refundEmailService;
    @Autowired private PayOsPayoutService payOsPayoutService;
    @Autowired private PurchaseSnapshotService snapshotService;
    @Autowired private com.boxcinema.refund.service.RefundRateLimitService refundRateLimitService;
    @PersistenceContext private EntityManager entityManager;
    @MockBean private JavaMailSender mailSender;

    @Value("${payos.payout.checksum-key:}")
    private String payoutChecksumKey;

    private Cinema cinema;
    private User manager1;
    private User admin1;
    private String runTag;

    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        refundRateLimitService.resetForTesting();
        payOsPayoutService.setCustomTransportExecutor(null);
        ReflectionTestUtils.setField(payOsPayoutService, "mode", "MOCK");
        ReflectionTestUtils.setField(payOsPayoutService, "refundEmailService", refundEmailService);
        ReflectionTestUtils.setField(payOsPayoutService, "allowAutoFailOnNotFound", false);
        ReflectionTestUtils.setField(payOsPayoutService, "notFoundGraceSeconds", 120);

        runTag = TEST_TAG + "_" + UUID.randomUUID().toString().substring(0, 6);
        cinema = cinemaRepository.findAll().get(0);
        manager1 = ensureUser("pr_mgr_" + runTag + "@box.test", "MANAGER", cinema.getCinemaId());
        admin1 = ensureUser("pr_adm_" + runTag + "@box.test", "ADMIN", null);
    }

    @AfterEach
    void cleanup() {
        payOsPayoutService.setCustomTransportExecutor(null);
        ReflectionTestUtils.setField(payOsPayoutService, "mode", "MOCK");
        ReflectionTestUtils.setField(payOsPayoutService, "refundEmailService", refundEmailService);
        ReflectionTestUtils.setField(payOsPayoutService, "allowAutoFailOnNotFound", false);
        ReflectionTestUtils.setField(payOsPayoutService, "notFoundGraceSeconds", 120);

        for (Long bid : createdBookingIds) {
            List<Long> rrIds = jdbcTemplate.queryForList("SELECT id FROM refund_requests WHERE booking_id = ?", Long.class, bid);
            jdbcTemplate.update("DELETE FROM email_outbox WHERE booking_id = ?", bid);
            for (Long rrId : rrIds) {
                jdbcTemplate.update("DELETE FROM payos_payout_transactions WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE refund_request_id = ? OR (entity_type = 'REFUND_REQUEST' AND entity_id = ?)", rrId, rrId);
            }
            jdbcTemplate.update("DELETE FROM refund_requests WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM payments WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM tickets WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM bookings WHERE booking_id = ?", bid);
        }
        createdBookingIds.clear();

        for (Long uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE actor_user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", uid);
        }
        createdUserIds.clear();
    }

    @Test
    void malformedMissingFields_SignatureConflicts_FieldMismatches_And_UnknownReference() throws Exception {
        Booking b = createConfirmedTicketBooking("pr_val_" + runTag + "@box.test", 100000L);
        long rrId = submitAndApproveBusiness(b, "0988112201");
        installProcessingTransport("po_res_val", 100000L, "0988112201");
        approvePayout(rrId);
        String refId = "RFD-" + rrId + "-A1";

        int txCountBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payos_payout_transactions", Integer.class);
        int rrCountBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_requests", Integer.class);

        // 1. Empty, malformed JSON, missing/empty data, invalid envelope code, non-textual signature -> 400
        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .contentType(MediaType.APPLICATION_JSON).content("{broken_json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"00\",\"data\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        Map<String, Object> baseData = buildFullPayoutDataMap("po_res_val", refId, 100000L, "COMPLETED", "SUCCEEDED", "0988112201");
        String baseSig = PayOsPayoutSignatureUtil.signPayload(baseData, effectivePayoutKey());

        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .header("x-signature", baseSig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "01", "data", baseData))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "00", "data", baseData, "signature", 12345))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        // 2. Conflicting x-signature header vs body signature & body tampered after signing -> 400
        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .header("x-signature", baseSig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "00", "data", baseData, "signature", "f".repeat(64)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        String tamperedBody = objectMapper.writeValueAsString(Map.of("code", "00", "data", baseData))
                .replace("\"amount\":100000", "\"amount\":50000");
        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .header("x-signature", baseSig)
                        .contentType("application/json")
                        .content(tamperedBody))
                .andExpect(status().isBadRequest());

        // 3. Non-array transactions, missing ref+id, missing status/state, COMPLETED missing transactions[] -> 400
        Map<String, Object> badTxnsData = new LinkedHashMap<>(baseData);
        badTxnsData.put("transactions", Map.of("id", "not_array"));
        postSignedPayoutWebhook(badTxnsData).andExpect(status().isBadRequest());

        Map<String, Object> noRefNoIdData = new LinkedHashMap<>();
        noRefNoIdData.put("approvalState", "COMPLETED");
        postSignedPayoutWebhook(noRefNoIdData).andExpect(status().isBadRequest());

        Map<String, Object> noStateData = new LinkedHashMap<>();
        noStateData.put("id", "po_res_val");
        noStateData.put("referenceId", refId);
        postSignedPayoutWebhook(noStateData).andExpect(status().isBadRequest());

        Map<String, Object> completedNoTxns = new LinkedHashMap<>();
        completedNoTxns.put("id", "po_res_val");
        completedNoTxns.put("referenceId", refId);
        completedNoTxns.put("approvalState", "COMPLETED");
        completedNoTxns.put("transactions", List.of());
        postSignedPayoutWebhook(completedNoTxns).andExpect(status().isBadRequest());

        // 4. Cross-field / record mismatches on known referenceId -> 400 and ZERO DB mutation
        Map<String, Object> wrongPayoutId = buildFullPayoutDataMap("po_WRONG_ID", refId, 100000L, "COMPLETED", "SUCCEEDED", "0988112201");
        postSignedPayoutWebhook(wrongPayoutId).andExpect(status().isBadRequest());

        Map<String, Object> wrongAmt = buildFullPayoutDataMap("po_res_val", refId, 50000L, "COMPLETED", "SUCCEEDED", "0988112201");
        postSignedPayoutWebhook(wrongAmt).andExpect(status().isBadRequest());

        Map<String, Object> wrongBin = buildFullPayoutDataMap("po_res_val", refId, 100000L, "COMPLETED", "SUCCEEDED", "0988112201");
        ((Map<String, Object>) ((List<?>) wrongBin.get("transactions")).get(0)).put("toBin", "970436");
        postSignedPayoutWebhook(wrongBin).andExpect(status().isBadRequest());

        Map<String, Object> wrongTxnRef = buildFullPayoutDataMap("po_res_val", refId, 100000L, "COMPLETED", "SUCCEEDED", "0988112201");
        ((Map<String, Object>) ((List<?>) wrongTxnRef.get("transactions")).get(0)).put("referenceId", "RFD-OTHER-A1");
        postSignedPayoutWebhook(wrongTxnRef).andExpect(status().isBadRequest());

        assertEquals("PROCESSING", payoutRepository.findByReferenceId(refId).orElseThrow().getStatus());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_audit_logs WHERE refund_request_id = ? AND entity_type = 'PAYOUT_WEBHOOK_EVENT'",
                Integer.class, rrId));

        // 5. Unknown referenceId -> honest 200 IGNORED_UNKNOWN JSON, zero rows created
        Map<String, Object> unkData = buildFullPayoutDataMap("po_unk_888", "RFD-UNKNOWN-888", 100000L, "COMPLETED", "SUCCEEDED", "0988112201");
        postSignedPayoutWebhook(unkData)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"))
                .andExpect(jsonPath("$.ignored").value(true))
                .andExpect(jsonPath("$.status").value("UNKNOWN_REFERENCE"));
        assertEquals(txCountBefore, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payos_payout_transactions", Integer.class));
        assertEquals(rrCountBefore, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refund_requests", Integer.class));

        // 6. Valid full PayOS v1 payload with stale JSESSIONID cookie -> 200 SUCCEEDED + masked rawResponseSanitized
        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .cookie(new Cookie("JSESSIONID", "STALE_REVOKED_COOKIE"))
                        .header("x-signature", baseSig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", baseData))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"))
                .andExpect(jsonPath("$.desc").value("Success"));

        PayOsPayoutTransaction settled = payoutRepository.findByReferenceId(refId).orElseThrow();
        assertEquals("SUCCEEDED", settled.getStatus());
        assertEquals("COMPLETED", refundRequestRepository.findById(rrId).orElseThrow().getRequestStatus());
        assertNotNull(settled.getRawResponseSanitized());
        assertFalse(settled.getRawResponseSanitized().contains("0988112201"));
    }

    @Test
    void concurrentWebhookBurst_TransientFailureRetry_OutOfOrder608AndStaleA1Conflict_And_RestartDurability() throws Exception {
        // 1. 8-thread rapid-fire concurrent SUCCEEDED webhooks -> all 200, 1 email, 1 FP dedup event
        Booking bConc = createConfirmedTicketBooking("pr_conc_" + runTag + "@box.test", 100000L);
        long rrConc = submitAndApproveBusiness(bConc, "0988113301");
        installProcessingTransport("po_conc_1", 100000L, "0988113301");
        approvePayout(rrConc);
        String refConc = "RFD-" + rrConc + "-A1";

        Map<String, Object> concData = buildFullPayoutDataMap("po_conc_1", refConc, 100000L, "COMPLETED", "SUCCEEDED", "0988113301");
        String concSig = PayOsPayoutSignatureUtil.signPayload(concData, effectivePayoutKey());
        String concBody = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", concData));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mockMvc.perform(post("/api/payments/payos/payout/webhook")
                                    .header("x-signature", concSig)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(concBody))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Integer> f : futures) {
                assertEquals(200, f.get(20, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals("SUCCEEDED", payoutRepository.findByReferenceId(refConc).orElseThrow().getStatus());
        assertEquals("COMPLETED", refundRequestRepository.findById(rrConc).orElseThrow().getRequestStatus());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_outbox WHERE dedup_key = ?", Integer.class, "REFUND_PAYOUT_SUCCEEDED:" + rrConc));
        assertEquals(1, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrConc).size());

        // 2. Restart durability check: clear L1 cache + instantiate fresh PayOsPayoutService -> replay returns DUPLICATE, zero extra email
        entityManager.clear();
        PayOsPayoutService restartedPayoutSvc = new PayOsPayoutService(
                refundRequestRepository, payoutRepository, auditLogRepository,
                bankEncryptionService, refundEmailService, transactionManager, paymentRepository, bookingRepository);
        ReflectionTestUtils.setField(restartedPayoutSvc, "mode", "MOCK");
        ReflectionTestUtils.setField(restartedPayoutSvc, "checksumKey", effectivePayoutKey());
        ReflectionTestUtils.setField(restartedPayoutSvc, "entityManager", entityManager);
        assertEquals(PayOsPayoutService.PayoutWebhookOutcome.DUPLICATE,
                restartedPayoutSvc.processPayoutWebhookWithOutcome(concBody, concSig));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_outbox WHERE dedup_key = ?", Integer.class, "REFUND_PAYOUT_SUCCEEDED:" + rrConc));

        // 3. Transient processing failure -> HTTP 500 (never fake 200), clean rollback -> retry succeeds 200 SUCCEEDED
        Booking bRetry = createConfirmedTicketBooking("pr_retry_" + runTag + "@box.test", 100000L);
        long rrRetry = submitAndApproveBusiness(bRetry, "0988113302");
        installProcessingTransport("po_retry_1", 100000L, "0988113302");
        approvePayout(rrRetry);
        String refRetry = "RFD-" + rrRetry + "-A1";

        Map<String, Object> retryData = buildFullPayoutDataMap("po_retry_1", refRetry, 100000L, "COMPLETED", "SUCCEEDED", "0988113302");
        String retrySig = PayOsPayoutSignatureUtil.signPayload(retryData, effectivePayoutKey());
        String retryBody = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", retryData));

        AtomicBoolean failEmailOnce = new AtomicBoolean(true);
        ReflectionTestUtils.setField(payOsPayoutService, "refundEmailService", new RefundEmailService(emailOutboxRepository) {
            @Override
            public void enqueuePayoutSucceededEmail(RefundRequest req, PayOsPayoutTransaction tx) {
                if (failEmailOnce.compareAndSet(true, false)) {
                    throw new RuntimeException("TRANSIENT_DB_OUTBOX_ERROR_10.0.0.99");
                }
                refundEmailService.enqueuePayoutSucceededEmail(req, tx);
            }
        });
        try {
            MvcResult err500 = mockMvc.perform(post("/api/payments/payos/payout/webhook")
                            .header("x-signature", retrySig)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(retryBody))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("99"))
                    .andExpect(jsonPath("$.desc").value("Internal error"))
                    .andReturn();
            assertFalse(err500.getResponse().getContentAsString().contains("TRANSIENT_DB_OUTBOX_ERROR"));
        } finally {
            ReflectionTestUtils.setField(payOsPayoutService, "refundEmailService", refundEmailService);
        }

        // Verify rollback: still PROCESSING, no FP audit log, no email
        assertEquals("PROCESSING", payoutRepository.findByReferenceId(refRetry).orElseThrow().getStatus());
        assertEquals("PAYOUT_PROCESSING", refundRequestRepository.findById(rrRetry).orElseThrow().getRequestStatus());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_audit_logs WHERE refund_request_id = ? AND entity_type = 'PAYOUT_WEBHOOK_EVENT'",
                Integer.class, rrRetry));

        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .header("x-signature", retrySig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(retryBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"));
        assertEquals("SUCCEEDED", payoutRepository.findByReferenceId(refRetry).orElseThrow().getStatus());
        assertEquals("COMPLETED", refundRequestRepository.findById(rrRetry).orElseThrow().getRequestStatus());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_outbox WHERE dedup_key = ?", Integer.class, "REFUND_PAYOUT_SUCCEEDED:" + rrRetry));

        // 4. Out-of-order SUCCEEDED arriving during MANUAL_RECONCILE_REQUIRED (608) -> recovers & clears stale errorMessage
        Booking b608 = createConfirmedTicketBooking("pr_608_" + runTag + "@box.test", 100000L);
        long rr608 = submitAndApproveBusiness(b608, "0988113303");
        installProcessingTransport("po_608_1", 100000L, "0988113303");
        approvePayout(rr608);
        String ref608 = "RFD-" + rr608 + "-A1";
        PayOsPayoutTransaction tx608 = payoutRepository.findByReferenceId(ref608).orElseThrow();
        jdbcTemplate.update("UPDATE payos_payout_transactions SET transaction_state = 'MANUAL_RECONCILE_REQUIRED', error_message = 'PayOS 608 manual review required' WHERE id = ?",
                tx608.getId());

        Map<String, Object> rec608Data = buildFullPayoutDataMap("po_608_1", ref608, 100000L, "COMPLETED", "SUCCEEDED", "0988113303");
        postSignedPayoutWebhook(rec608Data)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"));
        PayOsPayoutTransaction tx608After = payoutRepository.findById(tx608.getId()).orElseThrow();
        assertEquals("SUCCEEDED", tx608After.getStatus());
        assertNull(tx608After.getErrorMessage(), "Stale errorMessage must be cleared when payout settles SUCCEEDED");
        assertEquals("COMPLETED", refundRequestRepository.findById(rr608).orElseThrow().getRequestStatus());

        // 5. Out-of-order late SUCCEEDED for FAILED A1 AFTER A2 already initiated/succeeded -> CONFLICT_IGNORED (200), A2 untouched, 1 email
        Booking bConf = createConfirmedTicketBooking("pr_conf_" + runTag + "@box.test", 100000L);
        long rrConf = submitAndApproveBusiness(bConf, "0988113304");
        installProcessingTransport("po_a1_fail", 100000L, "0988113304");
        approvePayout(rrConf);
        String refA1 = "RFD-" + rrConf + "-A1";

        // A1 fails via duplicate FAILED webhooks (also verifies duplicate FAILED replay idempotency)
        Map<String, Object> failA1Data = buildFullPayoutDataMap("po_a1_fail", refA1, 100000L, "FAILED", "FAILED", "0988113304");
        postSignedPayoutWebhook(failA1Data).andExpect(status().isOk());
        postSignedPayoutWebhook(failA1Data).andExpect(status().isOk());
        assertNull(payoutRepository.findByReferenceId(refA1).orElseThrow().getActiveRequestLock());

        // Retry creates A2 in MOCK mode -> SUCCEEDED & COMPLETED
        payOsPayoutService.setCustomTransportExecutor(null);
        mockMvc.perform(post("/api/admin/refunds/" + rrConf + "/retry-payout").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk());
        assertEquals(2, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrConf).size());

        // Late SUCCEEDED arrives for stale A1 -> must return CONFLICT_IGNORED without corrupting A2 or adding extra email
        Map<String, Object> lateA1Success = buildFullPayoutDataMap("po_a1_fail", refA1, 100000L, "COMPLETED", "SUCCEEDED", "0988113304");
        postSignedPayoutWebhook(lateA1Success)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"))
                .andExpect(jsonPath("$.ignored").value(true))
                .andExpect(jsonPath("$.status").value("CONFLICT_IGNORED"));
        assertEquals("FAILED", payoutRepository.findByReferenceId(refA1).orElseThrow().getStatus());
        assertEquals("SUCCEEDED", payoutRepository.findByReferenceId("RFD-" + rrConf + "-A2").orElseThrow().getStatus());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_audit_logs WHERE refund_request_id = ? AND event_type = 'PAYOUT_WEBHOOK_LATE_SUCCESS_CONFLICT'",
                Integer.class, rrConf));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_outbox WHERE dedup_key = ?", Integer.class, "REFUND_PAYOUT_SUCCEEDED:" + rrConf));

        // 6. Simultaneous race across /payout/webhook + /reconcile + /retry-payout -> single PayOsPayoutTransaction, 1 email
        Booking bRace = createConfirmedTicketBooking("pr_race_" + runTag + "@box.test", 100000L);
        long rrRace = submitAndApproveBusiness(bRace, "0988113305");
        installProcessingTransport("po_race_1", 100000L, "0988113305");
        approvePayout(rrRace);
        String refRace = "RFD-" + rrRace + "-A1";
        PayOsPayoutTransaction txRace = payoutRepository.findByReferenceId(refRace).orElseThrow();
        payOsPayoutService.setCustomTransportExecutor(null); // MOCK GET returns SUCCEEDED during reconcile

        Map<String, Object> raceWhData = buildFullPayoutDataMap("po_race_1", refRace, 100000L, "COMPLETED", "SUCCEEDED", "0988113305");
        String raceWhSig = PayOsPayoutSignatureUtil.signPayload(raceWhData, effectivePayoutKey());
        String raceWhBody = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", raceWhData));

        ExecutorService rPool = Executors.newFixedThreadPool(6);
        CountDownLatch rReady = new CountDownLatch(6);
        CountDownLatch rStart = new CountDownLatch(1);
        List<Future<Integer>> rFutures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                rFutures.add(rPool.submit(() -> {
                    rReady.countDown();
                    rStart.await();
                    return mockMvc.perform(post("/api/payments/payos/payout/webhook")
                                    .header("x-signature", raceWhSig)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(raceWhBody))
                            .andReturn().getResponse().getStatus();
                }));
                rFutures.add(rPool.submit(() -> {
                    rReady.countDown();
                    rStart.await();
                    return mockMvc.perform(post("/api/admin/payouts/" + txRace.getId() + "/reconcile").with(csrf())
                                    .sessionAttr("currentUser", dtoOf(admin1))
                                    .with(user(admin1.getEmail()).roles("ADMIN")))
                            .andReturn().getResponse().getStatus();
                }));
                rFutures.add(rPool.submit(() -> {
                    rReady.countDown();
                    rStart.await();
                    return mockMvc.perform(post("/api/admin/refunds/" + rrRace + "/retry-payout").with(csrf())
                                    .sessionAttr("currentUser", dtoOf(admin1))
                                    .with(user(admin1.getEmail()).roles("ADMIN")))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(rReady.await(5, TimeUnit.SECONDS));
            rStart.countDown();
            for (Future<Integer> f : rFutures) {
                int st = f.get(20, TimeUnit.SECONDS);
                assertTrue(st == 200 || st == 409, "Expected clean 200 or 409 on concurrent payout race, got " + st);
            }
        } finally {
            rPool.shutdownNow();
        }
        assertEquals(1, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrRace).size());
        assertEquals("SUCCEEDED", payoutRepository.findByReferenceId(refRace).orElseThrow().getStatus());
        assertEquals("COMPLETED", refundRequestRepository.findById(rrRace).orElseThrow().getRequestStatus());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_outbox WHERE dedup_key = ?", Integer.class, "REFUND_PAYOUT_SUCCEEDED:" + rrRace));
    }

    private org.springframework.test.web.servlet.ResultActions postSignedPayoutWebhook(Map<String, Object> data) throws Exception {
        String sig = PayOsPayoutSignatureUtil.signPayload(data, effectivePayoutKey());
        String body = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "ok", "data", data));
        return mockMvc.perform(post("/api/payments/payos/payout/webhook")
                .header("x-signature", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void installProcessingTransport(String poId, long amount, String acc) {
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                try {
                    String ref = String.valueOf(bodyMap.get("referenceId"));
                    Map<String, Object> data = buildFullPayoutDataMap(poId, ref, amount, "PENDING", "PROCESSING", acc);
                    String sig = PayOsPayoutSignatureUtil.signPayload(data, effectivePayoutKey());
                    String json = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", data));
                    return new PayOsPayoutService.HttpOutcome(200, json, sig, false, null);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "500");
            }
        });
    }

    private Map<String, Object> buildFullPayoutDataMap(String poId, String refId, long amount,
                                                       String approvalState, String txnState, String accountNumber) {
        Map<String, Object> txn = new LinkedHashMap<>();
        txn.put("id", "txn_" + refId);
        txn.put("referenceId", refId);
        txn.put("amount", amount);
        txn.put("description", "BOX REFUND");
        txn.put("toBin", "970422");
        txn.put("toAccountNumber", accountNumber);
        txn.put("toAccountName", "RESILIENCE CUSTOMER");
        txn.put("reference", "FT-" + refId);
        txn.put("transactionDatetime", "2026-11-23T09:00:00+07:00");
        txn.put("errorMessage", null);
        txn.put("errorCode", null);
        txn.put("state", txnState);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", poId);
        data.put("referenceId", refId);
        data.put("transactions", List.of(txn));
        data.put("category", List.of("refund"));
        data.put("approvalState", approvalState);
        data.put("createdAt", "2026-11-23T09:00:00+07:00");
        return data;
    }

    private String effectivePayoutKey() {
        return (payoutChecksumKey != null && !payoutChecksumKey.isBlank())
                ? payoutChecksumKey.trim()
                : "MOCK_PAYOUT_CHECKSUM_KEY";
    }

    private Booking createConfirmedTicketBooking(String email, long priceVnd) {
        Booking b = new Booking();
        b.setBookingCode("BOX-20261123-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT));
        b.setCinema(cinema);
        b.setOrderType("TICKET_ONLY");
        b.setGuestName("Resilience Guest");
        b.setGuestEmail(email);
        b.setGuestPhone("0901234567");
        b.setTicketSubtotal(BigDecimal.valueOf(priceVnd));
        b.setSubtotal(BigDecimal.valueOf(priceVnd));
        b.setDiscountAmount(BigDecimal.ZERO);
        b.setTotalAmount(BigDecimal.valueOf(priceVnd));
        b.setBookingStatus("CONFIRMED");
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());

        BookingSeat bs = bookingSeatRepository.save(new BookingSeat(b, 1L, BigDecimal.valueOf(priceVnd), "A1", "BOOKED"));
        b.getBookingSeats().add(bs);

        Ticket t = new Ticket();
        t.setBooking(b);
        t.setBookingSeatId(bs.getBookingSeatId());
        t.setTicketCode("TCK-" + b.getBookingCode().replace("BOX-", "") + "-A1");
        t.setQrTokenHash(CryptoUtils.sha256(t.getTicketCode()));
        t.setPrice(BigDecimal.valueOf(priceVnd));
        t.setTicketStatus("VALID");
        t = ticketRepository.save(t);

        snapshotService.allocateOrderSnapshot(b, BigDecimal.valueOf(priceVnd), List.of(t));
        ticketRepository.save(t);
        b = bookingRepository.save(b);

        Payment p = new Payment();
        p.setBooking(b);
        p.setOrderCode(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 9_000_000_000L + 100_000L);
        p.setAmount(BigDecimal.valueOf(priceVnd));
        p.setIdempotencyKey("IDEMP-PR-" + UUID.randomUUID());
        p.setPaymentStatus("PAID");
        p.setPaidAt(LocalDateTime.now().minusMinutes(5));
        paymentRepository.save(p);
        return b;
    }

    private long submitAndApproveBusiness(Booking b, String accountNumber) throws Exception {
        Ticket t = ticketRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        MvcResult subRes = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", b.getBookingCode(),
                                "purchaseEmail", b.getGuestEmail(),
                                "reasonCategory", "OTHER",
                                "description", "Resilience refund",
                                "toBin", "970422",
                                "toAccountNumber", accountNumber,
                                "confirmAccountNumber", accountNumber,
                                "bankAccountName", "Resilience Customer",
                                "items", List.of(Map.of("itemKind", "TICKET", "targetId", t.getTicketId(), "quantity", 1))))))
                .andExpect(status().isCreated())
                .andReturn();
        long rrId = objectMapper.readTree(subRes.getResponse().getContentAsString()).get("id").asLong();
        long itemId = refundRequestItemRepository.findByRefundRequest_IdOrderByIdAsc(rrId).get(0).getId();

        mockMvc.perform(post("/api/manager/refunds/" + rrId + "/review").with(csrf())
                        .sessionAttr("currentUser", dtoOf(manager1))
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "DECIDE_ITEMS",
                                "itemDecisions", List.of(Map.of("itemId", itemId, "decision", "APPROVED", "approvedQuantity", 1))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("AWAITING_PAYOUT_APPROVAL"));
        return rrId;
    }

    private void approvePayout(long rrId) throws Exception {
        mockMvc.perform(post("/api/admin/refunds/" + rrId + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk());
    }

    private UserSummaryDto dtoOf(User u) {
        return new UserSummaryDto(u.getUserId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole());
    }

    private User ensureUser(String email, String role, Long cinemaId) {
        User u = new User();
        u.setFullName("PoRes " + role);
        u.setEmail(email);
        u.setPhone("09" + Math.abs(email.hashCode() % 100000000));
        u.setRole(role);
        u.setCinemaId(cinemaId);
        u.setAccountStatus("ACTIVE");
        u = userRepository.save(u);
        createdUserIds.add(u.getUserId());
        return u;
    }
}
