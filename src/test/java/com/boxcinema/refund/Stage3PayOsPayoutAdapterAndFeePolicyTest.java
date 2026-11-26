package com.boxcinema.refund;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.enums.PayoutFeeClassification;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import com.boxcinema.common.service.BankDataEncryptionService;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.common.util.PayOsPayoutSignatureUtil;
import com.boxcinema.refund.dto.*;
import com.boxcinema.refund.provider.PayOsPayoutProviderAdapter;
import com.boxcinema.refund.provider.PayoutExecutionResult;
import com.boxcinema.refund.provider.PayoutFeeAssessment;
import com.boxcinema.refund.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive Stage 3 Independent QA Suite verifying:
 * 1. Original Payment First -> Bank Payout Fallback (PayOS VietQR supportsOriginalPaymentReversal=false,
 *    refundMethod=BANK_PAYOUT, originalPaymentId, originalPaymentRef, REFUND_METHOD_FALLBACK_SELECTED audit).
 * 2. All 8 Transfer Fee Policy Scenarios (SENDER_PAYS, RECIPIENT_DEDUCTED, NO_FEE, UNKNOWN, UNVERIFIED
 *    including default LIVE policy, supplier exception, missing/null fee info, inconsistent netRecipientVnd)
 *    with PAYOUT_BLOCKED transition, payout_fee_assessments persistence, RESERVED quota retention,
 *    PAYOUT_BLOCKED_FEE_POLICY audit, and ZERO provider submission calls when blocked.
 * 3. LIVE_PAYOUT_ENABLED=false Kill-Switch & Official PayOS Payout v1 Guardrails (409 LIVE_PAYOUT_DISABLED,
 *    409 LIVE_PAYOUT_CREDENTIALS_SHARED, POST /v1/payouts/estimate-credit request x-signature, LIVE HTTP
 *    response header x-signature fail-closed verification on POST /v1/payouts, GET /v1/payouts-account/balance,
 *    and GET /v1/payouts with fromDate/toDate ISO-8601 filters).
 * 4. State Machine, Timeouts -> canonical UNKNOWN, ON_HOLD -> PROCESSING, PARTIAL_COMPLETED -> UNKNOWN,
 *    idempotent reconciliation via referenceId + payout_reconciliation_logs, and immutable recipient snapshot
 *    tamper detection (409 RECIPIENT_SNAPSHOT_TAMPERED).
 * 5. Payout Webhooks & Idempotency (invalid/forged signature rejected, valid transition + WEBHOOK reconciliation
 *    log, duplicate replay causes zero duplicate financial effects or double reservation commits).
 * 6. One-Click & Bulk Payout Preview / Execution (preview fields & fee assessment, ALL_BLOCKED & mixed
 *    per-item independent outcomes without fake full success).
 *
 * SAFETY: All tests run strictly against box_cinema_test with LIVE_PAYOUT_ENABLED=false and zero external PayOS HTTP calls.
 */
@SpringBootTest
@AutoConfigureMockMvc
class Stage3PayOsPayoutAdapterAndFeePolicyTest {

    private static final ZoneId HCM_ZONE = RefundLimitService.VN_ZONE;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private CinemaRepository cinemaRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private ShowtimeRepository showtimeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingSeatRepository bookingSeatRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRequestRepository refundRequestRepository;
    @Autowired private PayOsPayoutTransactionRepository payoutRepository;
    @Autowired private PayoutFeeAssessmentRepository feeAssessmentRepository;
    @Autowired private PayoutReconciliationLogRepository reconciliationLogRepository;
    @Autowired private RefundLimitReservationRepository reservationRepository;
    @Autowired private RefundAuditLogRepository auditLogRepository;

    @Autowired private RefundService refundService;
    @Autowired private PayOsPayoutService payOsPayoutService;
    @Autowired private PayOsPayoutProviderAdapter payOsPayoutProviderAdapter;
    @Autowired private BankDataEncryptionService bankEncryptionService;
    @Autowired private RefundRateLimitService refundRateLimitService;
    @Autowired private RefundLimitService refundLimitService;
    @Autowired private RefundDeadlineService refundDeadlineService;

    @MockBean private JavaMailSender mailSender;

    private String origServiceMode;
    private String origClientId;
    private String origApiKey;
    private String origChecksumKey;
    private String origPayClientId;
    private String origPayApiKey;
    private String origPayChecksumKey;
    private String origAdapterMode;
    private String origAdapterChecksumKey;

    private Cinema cinema1;
    private Room room1;
    private Movie movie;

    private User customer1;
    private User customer2;
    private User manager1;
    private User admin1;

    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdShowtimeIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        SecurityContextHolder.clearContext();

        origServiceMode = (String) ReflectionTestUtils.getField(payOsPayoutService, "mode");
        origClientId = (String) ReflectionTestUtils.getField(payOsPayoutService, "clientId");
        origApiKey = (String) ReflectionTestUtils.getField(payOsPayoutService, "apiKey");
        origChecksumKey = (String) ReflectionTestUtils.getField(payOsPayoutService, "checksumKey");
        origPayClientId = (String) ReflectionTestUtils.getField(payOsPayoutService, "paymentClientId");
        origPayApiKey = (String) ReflectionTestUtils.getField(payOsPayoutService, "paymentApiKey");
        origPayChecksumKey = (String) ReflectionTestUtils.getField(payOsPayoutService, "paymentChecksumKey");
        origAdapterMode = (String) ReflectionTestUtils.getField(payOsPayoutProviderAdapter, "mode");
        origAdapterChecksumKey = (String) ReflectionTestUtils.getField(payOsPayoutProviderAdapter, "checksumKey");

        restoreSafeMockDefaults();

        cinema1 = cinemaRepository.findAll().get(0);
        room1 = roomRepository.findAll().stream()
                .filter(r -> r.getCinema() != null && r.getCinema().getCinemaId().equals(cinema1.getCinemaId()))
                .findFirst()
                .orElseGet(() -> roomRepository.findAll().get(0));
        Long resolvedCid = room1.getCinema().getCinemaId();
        cinema1 = cinemaRepository.findById(resolvedCid).orElseThrow();
        movie = movieRepository.findAll().get(0);

        String u = UUID.randomUUID().toString().substring(0, 8);
        customer1 = createUser("s3_c1_" + u + "@box.test", "CUSTOMER", null);
        customer2 = createUser("s3_c2_" + u + "@box.test", "CUSTOMER", null);
        manager1 = createUser("s3_m1_" + u + "@box.test", "MANAGER", cinema1.getCinemaId());
        admin1 = createUser("s3_adm1_" + u + "@box.test", "ADMIN", null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        restoreSafeMockDefaults();

        for (Long bid : createdBookingIds) {
            List<Long> rrIds = jdbcTemplate.queryForList("SELECT id FROM refund_requests WHERE booking_id = ?", Long.class, bid);
            jdbcTemplate.update("DELETE FROM email_outbox WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM voucher_recoveries WHERE booking_id = ?", bid);
            for (Long rrId : rrIds) {
                jdbcTemplate.update("DELETE FROM payout_reconciliation_logs WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM payout_fee_assessments WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM payos_payout_transactions WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_limit_overrides WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_limit_reservations WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE refund_request_id = ? OR (entity_type = 'REFUND_REQUEST' AND entity_id = ?)", rrId, rrId);
                jdbcTemplate.update("DELETE FROM refund_approval_history WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_communications WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_request_items WHERE refund_request_id = ?", rrId);
            }
            jdbcTemplate.update("DELETE FROM refund_requests WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM payments WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM tickets WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_qr WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_seats WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM bookings WHERE booking_id = ?", bid);
        }
        createdBookingIds.clear();

        for (Long stId : createdShowtimeIds) {
            jdbcTemplate.update("DELETE FROM showtime_seats WHERE showtime_id = ?", stId);
            jdbcTemplate.update("DELETE FROM showtimes WHERE showtime_id = ?", stId);
        }
        createdShowtimeIds.clear();

        jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE entity_type IN ('PAYOUT_FEE_ASSESSMENT', 'PAYOUT_BATCH', 'PAYOUT_WEBHOOK_EVENT')");
        jdbcTemplate.update("DELETE FROM refund_limit_buckets WHERE bucket_type IN ('CINEMA_DAILY', 'MANAGER_DAILY') AND reserved_amount_vnd = 0 AND committed_amount_vnd = 0");

        for (Long uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM payout_reconciliation_logs WHERE actor_user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM payout_fee_assessments WHERE assessed_by = ?", uid);
            jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE actor_user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", uid);
        }
        createdUserIds.clear();
    }

    private void restoreSafeMockDefaults() {
        ReflectionTestUtils.setField(payOsPayoutService, "mode", origServiceMode != null ? origServiceMode : "MOCK");
        ReflectionTestUtils.setField(payOsPayoutService, "clientId", origClientId != null ? origClientId : "");
        ReflectionTestUtils.setField(payOsPayoutService, "apiKey", origApiKey != null ? origApiKey : "");
        ReflectionTestUtils.setField(payOsPayoutService, "checksumKey", origChecksumKey != null ? origChecksumKey : "");
        ReflectionTestUtils.setField(payOsPayoutService, "paymentClientId", origPayClientId != null ? origPayClientId : "");
        ReflectionTestUtils.setField(payOsPayoutService, "paymentApiKey", origPayApiKey != null ? origPayApiKey : "");
        ReflectionTestUtils.setField(payOsPayoutService, "paymentChecksumKey", origPayChecksumKey != null ? origPayChecksumKey : "");
        payOsPayoutService.setLivePayoutEnabled(false);
        payOsPayoutService.setEnforceLiveGuardsWithCustomTransport(false);
        payOsPayoutService.setCustomTransportExecutor(null);

        ReflectionTestUtils.setField(payOsPayoutProviderAdapter, "mode", origAdapterMode != null ? origAdapterMode : "MOCK");
        ReflectionTestUtils.setField(payOsPayoutProviderAdapter, "checksumKey", origAdapterChecksumKey != null ? origAdapterChecksumKey : "");
        payOsPayoutProviderAdapter.resetOverridesForTesting();

        refundRateLimitService.setClock(Clock.systemUTC());
        refundRateLimitService.resetForTesting();
        refundLimitService.setClock(Clock.system(HCM_ZONE));
        refundDeadlineService.setClock(Clock.system(HCM_ZONE));
    }

    // =========================================================================
    // 1. ORIGINAL PAYMENT FIRST -> BANK PAYOUT FALLBACK
    // =========================================================================

    @Test
    void originalPaymentReversalCheck_PayOsVietQrReturnsFalse_RecordsBankPayoutFallbackAndAudit() {
        Showtime st = createShowtime(LocalDateTime.now(HCM_ZONE).plusHours(5), LocalDateTime.now(HCM_ZONE).plusHours(7));
        Booking b = createPaidBooking(st, customer1, 120_000L, "PAYOS_TXN_ORIG_991");
        Payment paid = paymentRepository.findFirstByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(b.getBookingId(), "PAID").orElseThrow();

        // Direct adapter check: PayOS VietQR pay-in NEVER supports direct automated reversal
        assertFalse(payOsPayoutProviderAdapter.supportsOriginalPaymentReversal(paid));
        String rationale = payOsPayoutProviderAdapter.getOriginalPaymentFallbackReason(paid);
        assertTrue(rationale.contains("BANK_PAYOUT"));

        Ticket t = ticketRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        RefundRequestDetailDto submitted = submitCustomerRefund(b, t.getTicketId(), customer1, "0987111222");
        RefundRequestDetailDto approved = approveStage1ByManager(submitted.getId());
        assertEquals("AWAITING_PAYOUT_APPROVAL", approved.getRequestStatus());

        RefundRequest persisted = refundRequestRepository.findById(submitted.getId()).orElseThrow();
        assertEquals("BANK_PAYOUT", persisted.getRefundMethod());
        assertEquals(paid.getPaymentId(), persisted.getOriginalPaymentId());
        assertEquals("PAYOS_TXN_ORIG_991", persisted.getOriginalPaymentRef());
        assertNotNull(persisted.getRecipientSnapshotJson());
        assertNotNull(persisted.getRecipientSnapshotHash());

        List<RefundAuditLog> audits = auditLogRepository.findByRefundRequestIdOrderByCreatedAtDesc(submitted.getId());
        RefundAuditLog fallbackAudit = audits.stream()
                .filter(a -> "REFUND_METHOD_FALLBACK_SELECTED".equals(a.getEventType()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected REFUND_METHOD_FALLBACK_SELECTED audit entry"));
        assertTrue(fallbackAudit.getDetailsJson().contains("BANK_PAYOUT"));
        assertTrue(fallbackAudit.getDetailsJson().contains("PAYOS_TXN_ORIG_991"));
    }

    // =========================================================================
    // 2. ALL 8 TRANSFER FEE POLICY SCENARIOS & CUSTOMER 100% RECEIPT GUARANTEE
    // =========================================================================

    @Test
    void transferFeePolicy_AllEightScenarios_EnforceFullCustomerReceipt_BlockUnsafeWithoutProviderCall_AndRetainReservation() throws Exception {
        Showtime st = createShowtime(LocalDateTime.now(HCM_ZONE).plusHours(5), LocalDateTime.now(HCM_ZONE).plusHours(7));
        AtomicInteger providerPostCalls = new AtomicInteger(0);

        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                providerPostCalls.incrementAndGet();
                String ref = String.valueOf(bodyMap.get("referenceId"));
                long amt = ((Number) bodyMap.get("amount")).longValue();
                String toAcc = String.valueOf(bodyMap.get("toAccountNumber"));
                return buildSignedPayoutOutcome(200, "po_fee_" + ref, ref, amt, "COMPLETED", "SUCCEEDED", toAcc, effectiveSigningKey(false), true);
            }

            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "not used");
            }
        });

        // --- Scenario 1: SENDER_PAYS (allowed, fee recorded separately, customer receives 100% = 100,000 VND) ---
        payOsPayoutProviderAdapter.setCustomFeeAssessmentSupplier((req, amt, live) ->
                PayoutFeeAssessment.senderPays("PAYOS", amt, 1_100L, "Sender pays 1,100 VND fee; customer gets 100%"));
        long rrSenderPays = createAndApproveStage1(st, customer1, 100_000L, "0987000001");
        mockMvc.perform(post("/api/admin/refunds/" + rrSenderPays + "/payout/approve").with(csrf()).with(authOf(admin1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"SENDER_PAYS check\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"));
        assertEquals(1, providerPostCalls.get());

        PayoutFeeAssessmentEntity fa1 = feeAssessmentRepository.findTopByRefundRequestIdOrderByAssessedAtDescIdDesc(rrSenderPays).orElseThrow();
        assertEquals("SENDER_PAYS", fa1.getFeeMode());
        assertEquals(100_000L, fa1.getApprovedRefundVnd());
        assertEquals(1_100L, fa1.getEstimatedFeeVnd());
        assertEquals(100_000L, fa1.getNetRecipientVnd());
        assertEquals(101_100L, fa1.getTotalDebitVnd());
        assertTrue(fa1.isGuaranteedFullReceipt());
        assertFalse(fa1.isBlocked());
        assertNotNull(fa1.getPayoutTransactionId());
        assertEquals(100_000L, payoutRepository.findById(fa1.getPayoutTransactionId()).orElseThrow().getAmount());
        assertEquals("COMMITTED", reservationRepository.findByRefundRequestIdOrderByIdAsc(rrSenderPays).get(0).getStatus());

        // --- Scenario 3: NO_FEE (allowed, fee = 0, customer receives 100% = 95,000 VND) ---
        payOsPayoutProviderAdapter.resetOverridesForTesting();
        payOsPayoutProviderAdapter.setMockFeePolicyOverride(PayoutFeeClassification.NO_FEE);
        providerPostCalls.set(0);
        long rrNoFee = createAndApproveStage1(st, customer2, 95_000L, "0987000003");
        mockMvc.perform(post("/api/admin/refunds/" + rrNoFee + "/payout/approve").with(csrf()).with(authOf(admin1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"NO_FEE check\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"));
        assertEquals(1, providerPostCalls.get());
        PayoutFeeAssessmentEntity faNoFee = feeAssessmentRepository.findTopByRefundRequestIdOrderByAssessedAtDescIdDesc(rrNoFee).orElseThrow();
        assertEquals("NO_FEE", faNoFee.getFeeMode());
        assertEquals(0L, faNoFee.getEstimatedFeeVnd());
        assertEquals(95_000L, faNoFee.getNetRecipientVnd());
        assertFalse(faNoFee.isBlocked());

        // --- Blocked Scenarios 2, 4, 5, 6, 7, 8: MUST transition to PAYOUT_BLOCKED, retain RESERVED, NEVER call provider ---
        // Scenario 2: RECIPIENT_DEDUCTED
        payOsPayoutProviderAdapter.resetOverridesForTesting();
        payOsPayoutProviderAdapter.setMockFeePolicyOverride(PayoutFeeClassification.RECIPIENT_DEDUCTED);
        assertBlockedScenarioNeverSubmits(st, 80_000L, "0987000002", "RECIPIENT_DEDUCTED", providerPostCalls);

        // Scenario 4: UNKNOWN
        payOsPayoutProviderAdapter.resetOverridesForTesting();
        payOsPayoutProviderAdapter.setMockFeePolicyOverride(PayoutFeeClassification.UNKNOWN);
        assertBlockedScenarioNeverSubmits(st, 80_000L, "0987000004", "UNKNOWN", providerPostCalls);

        // Scenario 5a: UNVERIFIED via explicit override
        payOsPayoutProviderAdapter.resetOverridesForTesting();
        payOsPayoutProviderAdapter.setMockFeePolicyOverride(PayoutFeeClassification.UNVERIFIED);
        assertBlockedScenarioNeverSubmits(st, 80_000L, "0987000005", "UNVERIFIED", providerPostCalls);

        // Scenario 5b: Default LIVE policy is UNVERIFIED (even with LIVE_PAYOUT_ENABLED=true + distinct dummy creds!)
        payOsPayoutProviderAdapter.resetOverridesForTesting();
        try {
            configureDummyDistinctLiveCredentials();
            ReflectionTestUtils.setField(payOsPayoutService, "mode", "LIVE");
            payOsPayoutService.setLivePayoutEnabled(true);
            payOsPayoutService.setEnforceLiveGuardsWithCustomTransport(true);
            assertBlockedScenarioNeverSubmits(st, 80_000L, "0987000055", "UNVERIFIED", providerPostCalls);
        } finally {
            ReflectionTestUtils.setField(payOsPayoutService, "mode", "MOCK");
            payOsPayoutService.setLivePayoutEnabled(false);
            payOsPayoutService.setEnforceLiveGuardsWithCustomTransport(false);
        }

        // Scenario 6: Fee assessment supplier throws runtime exception -> fail-closed PAYOUT_BLOCKED (UNKNOWN)
        payOsPayoutProviderAdapter.resetOverridesForTesting();
        payOsPayoutProviderAdapter.setCustomFeeAssessmentSupplier((req, amt, live) -> {
            throw new IllegalStateException("Simulated estimate-credit gateway failure");
        });
        assertBlockedScenarioNeverSubmits(st, 80_000L, "0987000006", "UNKNOWN", providerPostCalls);

        // Scenario 7: Missing fee info / null assessment -> fail-closed PAYOUT_BLOCKED (UNKNOWN)
        payOsPayoutProviderAdapter.resetOverridesForTesting();
        payOsPayoutProviderAdapter.setCustomFeeAssessmentSupplier((req, amt, live) -> null);
        assertBlockedScenarioNeverSubmits(st, 80_000L, "0987000007", "UNKNOWN", providerPostCalls);

        // Scenario 8: Inconsistent provider response (SENDER_PAYS claiming netRecipientVnd != approvedRefundVnd) -> PAYOUT_BLOCKED
        payOsPayoutProviderAdapter.resetOverridesForTesting();
        payOsPayoutProviderAdapter.setCustomFeeAssessmentSupplier((req, amt, live) ->
                new PayoutFeeAssessment("PAYOS", PayoutFeeClassification.SENDER_PAYS, amt, 2_000L, amt - 2_000L, amt, true, false, "Inconsistent math"));
        long blocked8 = assertBlockedScenarioNeverSubmits(st, 80_000L, "0987000008", "UNKNOWN", providerPostCalls);

        // Recovery verification: once fee policy is restored to SENDER_PAYS, Admin retry-payout unblocks & completes!
        payOsPayoutProviderAdapter.resetOverridesForTesting();
        providerPostCalls.set(0);
        mockMvc.perform(post("/api/admin/refunds/" + blocked8 + "/retry-payout").with(csrf()).with(authOf(admin1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"));
        assertEquals(1, providerPostCalls.get());
        assertEquals("COMMITTED", reservationRepository.findByRefundRequestIdOrderByIdAsc(blocked8).get(0).getStatus());
    }

    private long assertBlockedScenarioNeverSubmits(
            Showtime st, long amountVnd, String bankAccount, String expectedFeeMode, AtomicInteger providerPostCalls) throws Exception {
        refundRateLimitService.resetForTesting();
        providerPostCalls.set(0);
        long rrId = createAndApproveStage1(st, customer1, amountVnd, bankAccount);

        mockMvc.perform(post("/api/admin/refunds/" + rrId + "/payout/approve").with(csrf()).with(authOf(admin1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Fee block check\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("PAYOUT_BLOCKED"))
                .andExpect(jsonPath("$.payoutStatus").value("PAYOUT_BLOCKED"))
                .andExpect(jsonPath("$.workflowStatus").value("PAYOUT_BLOCKED"));

        assertEquals(0, providerPostCalls.get(), "Blocked fee policy must NEVER invoke PayOS submission");
        assertTrue(payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrId).isEmpty(),
                "Blocked fee policy must NOT create a PayOsPayoutTransaction row");

        PayoutFeeAssessmentEntity fa = feeAssessmentRepository.findTopByRefundRequestIdOrderByAssessedAtDescIdDesc(rrId).orElseThrow();
        assertEquals(expectedFeeMode, fa.getFeeMode());
        assertTrue(fa.isBlocked());
        assertFalse(fa.isGuaranteedFullReceipt());
        assertNotNull(fa.getBlockReason());

        RefundLimitReservation res = reservationRepository.findByActiveLockKey("REQ:" + rrId).orElseThrow();
        assertEquals("RESERVED", res.getStatus(), "PAYOUT_BLOCKED must retain RESERVED financial limit quota");

        assertTrue(auditLogRepository.findByRefundRequestIdOrderByCreatedAtDesc(rrId).stream()
                .anyMatch(a -> "PAYOUT_BLOCKED_FEE_POLICY".equals(a.getEventType())),
                "PAYOUT_BLOCKED must write PAYOUT_BLOCKED_FEE_POLICY audit log");
        return rrId;
    }

    // =========================================================================
    // 3. KILL-SWITCH (LIVE_PAYOUT_ENABLED=false), ESTIMATE-CREDIT & LIVE HEADER x-signature
    // =========================================================================

    @Test
    void liveKillSwitch_EstimateCreditSignature_And_LiveResponseHeaderSignatureFailClosed() throws Exception {
        // 1. Default preflight status on Admin & Manager endpoints confirms livePayoutEnabled=false, payoutMode=MOCK
        mockMvc.perform(get("/api/admin/payouts/preflight-status").with(authOf(admin1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.livePayoutEnabled").value(false))
                .andExpect(jsonPath("$.payoutMode").value("MOCK"))
                .andExpect(jsonPath("$.liveDispatchReady").value(false));
        mockMvc.perform(get("/api/manager/payouts/preflight-status").with(authOf(manager1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.livePayoutEnabled").value(false));

        Showtime st = createShowtime(LocalDateTime.now(HCM_ZONE).plusHours(5), LocalDateTime.now(HCM_ZONE).plusHours(7));
        long rrKill = createAndApproveStage1(st, customer1, 110_000L, "0987222333");
        AtomicInteger spyHttpCalls = new AtomicInteger(0);

        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                spyHttpCalls.incrementAndGet();
                throw new AssertionError("HTTP transport must NEVER be invoked when LIVE_PAYOUT_ENABLED=false");
            }

            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                spyHttpCalls.incrementAndGet();
                throw new AssertionError("HTTP transport must NEVER be invoked when LIVE_PAYOUT_ENABLED=false");
            }
        });

        try {
            // 2. Hard Kill-Switch: mode=LIVE + livePayoutEnabled=false -> 409 LIVE_PAYOUT_DISABLED before any HTTP call!
            configureDummyDistinctLiveCredentials();
            ReflectionTestUtils.setField(payOsPayoutService, "mode", "LIVE");
            payOsPayoutService.setLivePayoutEnabled(false);
            payOsPayoutService.setEnforceLiveGuardsWithCustomTransport(true);
            payOsPayoutProviderAdapter.setLiveFeePolicyOverride(PayoutFeeClassification.SENDER_PAYS);

            mockMvc.perform(post("/api/admin/refunds/" + rrKill + "/payout/approve").with(csrf()).with(authOf(admin1))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Attempt with kill switch off\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("LIVE_PAYOUT_DISABLED"));
            assertEquals(0, spyHttpCalls.get());

            // 3. Shared credentials guard: payout creds == collection creds -> 409 LIVE_PAYOUT_CREDENTIALS_SHARED
            payOsPayoutService.setLivePayoutEnabled(true);
            ReflectionTestUtils.setField(payOsPayoutService, "paymentClientId", "DUMMY_PAYOUT_CID");
            long rrSharedCreds = createAndApproveStage1(st, customer2, 110_000L, "0987222334");
            mockMvc.perform(post("/api/admin/refunds/" + rrSharedCreds + "/payout/approve").with(csrf()).with(authOf(admin1))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Attempt with shared creds\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("LIVE_PAYOUT_CREDENTIALS_SHARED"));
            assertEquals(0, spyHttpCalls.get());

            // 4. POST /v1/payouts/estimate-credit helper signs x-signature header accurately (via local transport seam, zero network)
            String testKey = "STAGE3_TEST_PAYOUT_CHECKSUM_KEY";
            ReflectionTestUtils.setField(payOsPayoutProviderAdapter, "mode", "LIVE");
            ReflectionTestUtils.setField(payOsPayoutProviderAdapter, "checksumKey", testKey);
            AtomicReference<String> capturedEstSig = new AtomicReference<>();
            AtomicReference<Map<String, Object>> capturedEstPayload = new AtomicReference<>();

            payOsPayoutProviderAdapter.setCustomEstimateCreditTransport((url, headers, payload) -> {
                assertTrue(url.endsWith("/v1/payouts/estimate-credit"));
                capturedEstSig.set(headers.getFirst("x-signature"));
                capturedEstPayload.set(payload);
                return new PayoutExecutionResult(200, "{\"code\":\"00\",\"desc\":\"success\",\"data\":{\"estimateCredit\":101100}}", null, false, null);
            });
            Long estimated = payOsPayoutProviderAdapter.estimateCredit(
                    "RFD-EST-1", 100_000L, "BOX REFUND EST", "970422", "0987222333", List.of("refund"));
            assertEquals(101_100L, estimated);
            assertNotNull(capturedEstSig.get());
            assertTrue(PayOsPayoutSignatureUtil.verifySignature(capturedEstPayload.get(), capturedEstSig.get(), testKey),
                    "POST /v1/payouts/estimate-credit request x-signature must match official PayOsPayoutSignatureUtil");

            // 5. Official LIVE HTTP response header x-signature fail-closed verification (using spy transport, NEVER real network):
            configureDummyDistinctLiveCredentials();
            ReflectionTestUtils.setField(payOsPayoutService, "checksumKey", testKey);
            payOsPayoutService.setEnforceLiveGuardsWithCustomTransport(true);
            payOsPayoutService.setLivePayoutEnabled(true);
            payOsPayoutProviderAdapter.setLiveFeePolicyOverride(PayoutFeeClassification.SENDER_PAYS);

            AtomicReference<String> capturedListUrl = new AtomicReference<>();
            payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
                @Override
                public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                    String ref = String.valueOf(bodyMap.get("referenceId"));
                    long amt = ((Number) bodyMap.get("amount")).longValue();
                    String acc = String.valueOf(bodyMap.get("toAccountNumber"));
                    // Return HTTP 200 code="00" WITHOUT x-signature response header -> LIVE mode MUST reject fail-closed!
                    return buildSignedPayoutOutcome(200, "po_nosig_1", ref, amt, "COMPLETED", "SUCCEEDED", acc, testKey, false);
                }

                @Override
                public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                    capturedListUrl.set(url);
                    Map<String, Object> data = Map.of("payouts", List.of(), "pagination", Map.of("total", 0));
                    String body = "{\"code\":\"00\",\"desc\":\"ok\",\"data\":{\"payouts\":[],\"pagination\":{\"total\":0}}}";
                    return new PayOsPayoutService.HttpOutcome(200, body, "deadbeef_tampered_sig", false, null);
                }

                @Override
                public PayOsPayoutService.HttpOutcome getBalance(String url, HttpHeaders headers) {
                    String body = "{\"code\":\"00\",\"desc\":\"ok\",\"data\":{\"accountNumber\":\"1234567890\",\"accountName\":\"BOX\",\"currency\":\"VND\",\"balance\":\"9000000\"}}";
                    return new PayOsPayoutService.HttpOutcome(200, body, null, false, null); // missing x-signature header in LIVE mode
                }
            });

            long rrLiveMissingSig = createAndApproveStage1(st, customer1, 90_000L, "0987222335");
            mockMvc.perform(post("/api/admin/refunds/" + rrLiveMissingSig + "/payout/approve").with(csrf()).with(authOf(admin1))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Missing header sig in LIVE\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requestStatus").value("PAYOUT_PROCESSING"))
                    .andExpect(jsonPath("$.workflowStatus").value("UNKNOWN"));
            PayOsPayoutTransaction liveTx = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrLiveMissingSig).get(0);
            assertEquals("PROCESSING", liveTx.getStatus(), "Missing LIVE response x-signature must NEVER mark payout SUCCEEDED");
            assertEquals("UNKNOWN_OUTCOME", liveTx.getTransactionState());

            // Balance without x-signature header in LIVE mode -> liveVerified=false
            mockMvc.perform(get("/api/admin/payouts/account-balance").with(authOf(admin1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.liveVerified").value(false));

            // Remote list with fromDate/toDate ISO-8601 filters & tampered x-signature -> INVALID_SIGNATURE + URL params present
            mockMvc.perform(get("/api/admin/payouts/payos-remote")
                            .param("fromDate", "2026-09-01T00:00:00Z")
                            .param("toDate", "2026-09-30T23:59:59Z")
                            .with(authOf(admin1)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));
            assertNotNull(capturedListUrl.get());
            assertTrue(capturedListUrl.get().contains("fromDate="));
            assertTrue(capturedListUrl.get().contains("toDate="));
        } finally {
            restoreSafeMockDefaults();
        }
    }

    // =========================================================================
    // 4. STATE MACHINE: TIMEOUT -> UNKNOWN, ON_HOLD, PARTIAL_COMPLETED, RECONCILIATION & SNAPSHOT TAMPER
    // =========================================================================

    @Test
    void stateMachine_TimeoutUnknown_OnHold_PartialCompleted_ReconciliationLogs_And_RecipientSnapshotTamperGuard() throws Exception {
        Showtime st = createShowtime(LocalDateTime.now(HCM_ZONE).plusHours(5), LocalDateTime.now(HCM_ZONE).plusHours(7));

        // 1. Timeout during dispatch -> canonical workflowStatus=UNKNOWN, retains RESERVED, blocks blind duplicate, reconciles cleanly
        long rrTimeout = createAndApproveStage1(st, customer1, 100_000L, "0987333441");
        AtomicInteger postCount = new AtomicInteger(0);
        AtomicInteger getCount = new AtomicInteger(0);

        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                postCount.incrementAndGet();
                return new PayOsPayoutService.HttpOutcome(0, null, null, true, "Read timed out after socket write");
            }

            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                getCount.incrementAndGet();
                String ref = "RFD-" + rrTimeout + "-A1";
                return buildSignedPayoutOutcome(200, "po_rec_ok", ref, 100_000L, "COMPLETED", "SUCCEEDED", "0987333441", effectiveSigningKey(false), true);
            }
        });

        mockMvc.perform(post("/api/admin/refunds/" + rrTimeout + "/payout/approve").with(csrf()).with(authOf(admin1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Timeout test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.requestStatus").value("PAYOUT_PROCESSING"));

        PayOsPayoutTransaction txTimeout = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrTimeout).get(0);
        assertEquals(1, postCount.get());
        assertEquals("UNKNOWN_OUTCOME", txTimeout.getTransactionState());
        assertEquals("RESERVED", reservationRepository.findByActiveLockKey("REQ:" + rrTimeout).orElseThrow().getStatus());

        // Retry payout does NOT blind-submit A2 (postCount stays 1!); instead reconciles existing referenceId via GET -> COMPLETED + COMMITTED!
        mockMvc.perform(post("/api/admin/refunds/" + rrTimeout + "/retry-payout").with(csrf()).with(authOf(admin1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.workflowStatus").value("SUCCESS"));

        assertEquals(1, postCount.get(), "Ambiguous UNKNOWN payout must NEVER blind-submit a duplicate POST");
        assertEquals(1, getCount.get(), "Retry on UNKNOWN must reconcile via GET on existing referenceId");
        assertEquals(1, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrTimeout).size());
        assertEquals("COMMITTED", reservationRepository.findByRefundRequestIdOrderByIdAsc(rrTimeout).get(0).getStatus());

        List<PayoutReconciliationLog> recLogs = reconciliationLogRepository.findByPayoutTransactionIdOrderByCreatedAtDescIdDesc(txTimeout.getId());
        assertFalse(recLogs.isEmpty(), "Reconciliation must persist payout_reconciliation_logs entry");
        assertEquals("PRE_RETRY", recLogs.get(0).getTriggerSource());

        mockMvc.perform(get("/api/admin/payouts/" + txTimeout.getId() + "/reconciliation-logs").with(authOf(admin1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].triggerSource").value("PRE_RETRY"));

        // 2. Official ON_HOLD transaction state -> maps to PROCESSING (never SUCCESS!), retains RESERVED
        long rrOnHold = createAndApproveStage1(st, customer2, 85_000L, "0987333442");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                String ref = String.valueOf(bodyMap.get("referenceId"));
                return buildSignedPayoutOutcome(200, "po_hold_1", ref, 85_000L, "PROCESSING", "ON_HOLD", "0987333442", effectiveSigningKey(false), true);
            }

            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "500");
            }
        });
        mockMvc.perform(post("/api/admin/refunds/" + rrOnHold + "/payout/approve").with(csrf()).with(authOf(admin1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"ON_HOLD check\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.requestStatus").value("PAYOUT_PROCESSING"));
        PayOsPayoutTransaction txOnHold = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrOnHold).get(0);
        assertEquals("PROCESSING", txOnHold.getStatus());
        assertEquals("ON_HOLD", txOnHold.getTransactionState());
        assertEquals("RESERVED", reservationRepository.findByActiveLockKey("REQ:" + rrOnHold).orElseThrow().getStatus());

        // 3. Official PARTIAL_COMPLETED approvalState without terminal SUCCEEDED txn -> maps to UNKNOWN, retains RESERVED
        long rrPartial = createAndApproveStage1(st, customer1, 90_000L, "0987333443");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                String ref = String.valueOf(bodyMap.get("referenceId"));
                return buildSignedPayoutOutcome(200, "po_part_1", ref, 90_000L, "PARTIAL_COMPLETED", "PROCESSING", "0987333443", effectiveSigningKey(false), true);
            }

            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "500");
            }
        });
        mockMvc.perform(post("/api/admin/refunds/" + rrPartial + "/payout/approve").with(csrf()).with(authOf(admin1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"PARTIAL_COMPLETED check\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("UNKNOWN"))
                .andExpect(jsonPath("$.requestStatus").value("PAYOUT_PROCESSING"));

        // 4. Immutable Recipient Snapshot Tampering -> 409 RECIPIENT_SNAPSHOT_TAMPERED before any provider submission!
        payOsPayoutService.setCustomTransportExecutor(null);
        long rrTamper = createAndApproveStage1(st, customer2, 105_000L, "0987333444");
        String attackerEncrypted = bankEncryptionService.encryptAccountNumber("9999888877");
        jdbcTemplate.update("UPDATE refund_requests SET to_account_number_encrypted = ? WHERE id = ?", attackerEncrypted, rrTamper);

        mockMvc.perform(post("/api/admin/refunds/" + rrTamper + "/payout/approve").with(csrf()).with(authOf(admin1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Attempt after DB account tamper\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RECIPIENT_SNAPSHOT_TAMPERED"));

        assertTrue(payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrTamper).isEmpty(),
                "Tampered recipient snapshot must abort before creating any payout transaction");
        assertTrue(auditLogRepository.findByRefundRequestIdOrderByCreatedAtDesc(rrTamper).stream()
                .anyMatch(a -> "RECIPIENT_SNAPSHOT_TAMPERED".equals(a.getEventType())));
    }

    // =========================================================================
    // 5. PAYOUT WEBHOOKS, SIGNATURE VALIDATION, IDEMPOTENCY & RECONCILIATION LOG
    // =========================================================================

    @Test
    void payoutWebhooks_InvalidSignatureRejected_ValidTransitionLogsReconciliation_And_ReplayCausesZeroDuplicateEffects() throws Exception {
        Showtime st = createShowtime(LocalDateTime.now(HCM_ZONE).plusHours(5), LocalDateTime.now(HCM_ZONE).plusHours(7));
        long rrWh = createAndApproveStage1(st, customer1, 100_000L, "0987444555");

        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                String ref = String.valueOf(bodyMap.get("referenceId"));
                return buildSignedPayoutOutcome(200, "po_wh_s3", ref, 100_000L, "PROCESSING", "ON_HOLD", "0987444555", effectiveSigningKey(false), true);
            }

            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "500");
            }
        });

        mockMvc.perform(post("/api/admin/refunds/" + rrWh + "/payout/approve").with(csrf()).with(authOf(admin1))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Dispatch ON_HOLD for webhook\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowStatus").value("PROCESSING"));

        PayOsPayoutTransaction tx = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrWh).get(0);
        String refId = tx.getReferenceId();
        Map<String, Object> whData = buildPayoutDataMap("po_wh_s3", refId, 100_000L, "COMPLETED", "SUCCEEDED", "0987444555");
        String whBody = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", whData));
        String validSig = PayOsPayoutSignatureUtil.signPayload(whData, effectiveSigningKey(false));

        // 1. Invalid / missing / tampered x-signature -> 400 Bad Request, state stays PROCESSING & RESERVED
        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .contentType(MediaType.APPLICATION_JSON).content(whBody))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .header("x-signature", "bad_sig_000000")
                        .contentType(MediaType.APPLICATION_JSON).content(whBody))
                .andExpect(status().isBadRequest());
        assertEquals("PROCESSING", payoutRepository.findById(tx.getId()).orElseThrow().getStatus());
        assertEquals("RESERVED", reservationRepository.findByActiveLockKey("REQ:" + rrWh).orElseThrow().getStatus());

        // 2. Valid webhook -> 200 OK, transitions to SUCCEEDED / SUCCESS / COMPLETED, commits reservation, writes WEBHOOK log
        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .header("x-signature", validSig)
                        .contentType(MediaType.APPLICATION_JSON).content(whBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"));

        RefundRequest afterValid = refundRequestRepository.findById(rrWh).orElseThrow();
        assertEquals("SUCCESS", afterValid.getWorkflowStatus());
        assertEquals("COMPLETED", afterValid.getRequestStatus());
        assertEquals("COMMITTED", reservationRepository.findByRefundRequestIdOrderByIdAsc(rrWh).get(0).getStatus());

        List<PayoutReconciliationLog> whLogs = reconciliationLogRepository.findByPayoutTransactionIdOrderByCreatedAtDescIdDesc(tx.getId());
        assertEquals(1, whLogs.size());
        assertEquals("WEBHOOK", whLogs.get(0).getTriggerSource());

        Long committedBucketBeforeReplay = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_vnd), 0) FROM refund_limit_reservations WHERE refund_request_id = ? AND status = 'COMMITTED'",
                Long.class, rrWh);
        assertEquals(100_000L, committedBucketBeforeReplay);

        // 3. Duplicate / replayed webhook -> 200 OK idempotently, zero extra reconciliation logs, zero extra emails, zero double commit
        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .header("x-signature", validSig)
                        .contentType(MediaType.APPLICATION_JSON).content(whBody))
                .andExpect(status().isOk());

        assertEquals(1, reconciliationLogRepository.findByPayoutTransactionIdOrderByCreatedAtDescIdDesc(tx.getId()).size(),
                "Duplicate webhook replay must NOT create duplicate payout_reconciliation_logs rows");
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE dedup_key = ?",
                Integer.class, "REFUND_PAYOUT_SUCCEEDED:" + rrWh));
        Long committedBucketAfterReplay = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_vnd), 0) FROM refund_limit_reservations WHERE refund_request_id = ? AND status = 'COMMITTED'",
                Long.class, rrWh);
        assertEquals(100_000L, committedBucketAfterReplay);
    }

    // =========================================================================
    // 6. ONE-CLICK & BULK PAYOUT PREVIEW / EXECUTION (INDEPENDENT PER-ITEM OUTCOMES)
    // =========================================================================

    @Test
    void oneClickAndBulkPayout_PreviewExposesMetadataAndFeeAssessment_And_MixedBulkExecutesIndependently() throws Exception {
        Showtime st = createShowtime(LocalDateTime.now(HCM_ZONE).plusHours(5), LocalDateTime.now(HCM_ZONE).plusHours(7));
        payOsPayoutProviderAdapter.setCustomFeeAssessmentSupplier((req, amt, live) ->
                PayoutFeeAssessment.senderPays("PAYOS", amt, 1_500L, "Sender bears 1,500 VND fee"));

        long rrPreview = createAndApproveStage1(st, customer1, 120_000L, "0987555001");

        // 1. Single preview & fee-preflight endpoints expose full bank, cinema, description, refundMethod, fee & kill-switch fields
        mockMvc.perform(get("/api/admin/refunds/" + rrPreview + "/payout/preview").with(authOf(admin1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.livePayoutEnabled").value(false))
                .andExpect(jsonPath("$.payoutMode").value("MOCK"))
                .andExpect(jsonPath("$.eligibleCount").value(1))
                .andExpect(jsonPath("$.eligibleItems[0].amountVnd").value(120_000))
                .andExpect(jsonPath("$.eligibleItems[0].toAccountNumberMasked").value("******5001"))
                .andExpect(jsonPath("$.eligibleItems[0].toBin").value("970422"))
                .andExpect(jsonPath("$.eligibleItems[0].cinemaName").value(cinema1.getCinemaName()))
                .andExpect(jsonPath("$.eligibleItems[0].transferDescription").value("BOX REFUND " + rrPreview))
                .andExpect(jsonPath("$.eligibleItems[0].refundMethod").value("BANK_PAYOUT"))
                .andExpect(jsonPath("$.eligibleItems[0].feeClassification").value("SENDER_PAYS"))
                .andExpect(jsonPath("$.eligibleItems[0].estimatedFeeVnd").value(1_500))
                .andExpect(jsonPath("$.eligibleItems[0].netRecipientVnd").value(120_000))
                .andExpect(jsonPath("$.eligibleItems[0].guaranteedFullReceipt").value(true));

        mockMvc.perform(post("/api/admin/refunds/" + rrPreview + "/payout/assess-fee").with(csrf()).with(authOf(admin1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classification").value("SENDER_PAYS"))
                .andExpect(jsonPath("$.netRecipientVnd").value(120_000));

        // 2. Bulk execution where ALL items are blocked by fee policy -> overallStatus = ALL_BLOCKED (never fake ALL_SUCCEEDED!)
        long rrAllBlock2 = createAndApproveStage1(st, customer2, 90_000L, "0987555002");
        payOsPayoutProviderAdapter.resetOverridesForTesting();
        payOsPayoutProviderAdapter.setMockFeePolicyOverride(PayoutFeeClassification.RECIPIENT_DEDUCTED);

        mockMvc.perform(post("/api/admin/refunds/payout/batch-approve").with(csrf()).with(authOf(admin1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refundRequestIds", List.of(rrPreview, rrAllBlock2),
                                "strictTotalMatch", false,
                                "note", "All blocked batch test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("ALL_BLOCKED"))
                .andExpect(jsonPath("$.blockedCount").value(2))
                .andExpect(jsonPath("$.succeededCount").value(0));

        // 3. Mixed 3-item bulk execution:
        //    - Item A (70k): SENDER_PAYS -> SUCCEEDED
        //    - Item B (80k): RECIPIENT_DEDUCTED -> PAYOUT_BLOCKED (provider NOT called for Item B!)
        //    - Item C (90k): SENDER_PAYS + provider 400 -> FAILED
        refundRateLimitService.resetForTesting();
        long itemA = createAndApproveStage1(st, customer1, 70_000L, "0987666001");
        long itemB = createAndApproveStage1(st, customer2, 80_000L, "0987666002");
        User customer3 = createUser("s3_c3_" + UUID.randomUUID().toString().substring(0, 6) + "@box.test", "CUSTOMER", null);
        long itemC = createAndApproveStage1(st, customer3, 90_000L, "0987666003");

        payOsPayoutProviderAdapter.resetOverridesForTesting();
        payOsPayoutProviderAdapter.setCustomFeeAssessmentSupplier((req, amt, live) -> {
            if (req.getId().equals(itemB)) {
                return PayoutFeeAssessment.recipientDeducted("PAYOS", amt, 1_100L, "Recipient fee deducted on itemB");
            }
            return PayoutFeeAssessment.senderPays("PAYOS", amt, 500L, "Sender pays on itemA/itemC");
        });

        List<String> dispatchedRefs = Collections.synchronizedList(new ArrayList<>());
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                String ref = String.valueOf(bodyMap.get("referenceId"));
                dispatchedRefs.add(ref);
                long amt = ((Number) bodyMap.get("amount")).longValue();
                String acc = String.valueOf(bodyMap.get("toAccountNumber"));
                if (ref.startsWith("RFD-" + itemC + "-")) {
                    return new PayOsPayoutService.HttpOutcome(400, "{\"code\":\"622\",\"desc\":\"Invalid destination account\"}", null, false, "400");
                }
                return buildSignedPayoutOutcome(200, "po_mix_" + ref, ref, amt, "COMPLETED", "SUCCEEDED", acc, effectiveSigningKey(false), true);
            }

            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "500");
            }
        });

        MvcResult mixBatchRes = mockMvc.perform(post("/api/admin/refunds/payout/batch-approve").with(csrf()).with(authOf(admin1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refundRequestIds", List.of(itemA, itemB, itemC),
                                "strictTotalMatch", false,
                                "note", "Mixed eligible/blocked/failed batch"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("PARTIAL_SUCCESS"))
                .andExpect(jsonPath("$.succeededCount").value(1))
                .andExpect(jsonPath("$.blockedCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(1))
                .andExpect(jsonPath("$.succeededTotalAmount").value(70_000))
                .andReturn();

        JsonNode results = objectMapper.readTree(mixBatchRes.getResponse().getContentAsString()).get("results");
        assertEquals("SUCCEEDED", results.get(0).get("resultStatus").asText());
        assertEquals("PAYOUT_BLOCKED", results.get(1).get("resultStatus").asText());
        assertTrue(results.get(1).get("blocked").asBoolean());
        assertEquals("FAILED", results.get(2).get("resultStatus").asText());

        assertEquals(2, dispatchedRefs.size(), "Only itemA and itemC may reach PayOS transport; itemB must be blocked pre-dispatch");
        assertFalse(dispatchedRefs.stream().anyMatch(r -> r.startsWith("RFD-" + itemB + "-")));

        assertEquals("COMMITTED", reservationRepository.findByRefundRequestIdOrderByIdAsc(itemA).get(0).getStatus());
        assertEquals("RESERVED", reservationRepository.findByActiveLockKey("REQ:" + itemB).orElseThrow().getStatus());
        assertEquals("RELEASED", reservationRepository.findByRefundRequestIdOrderByIdAsc(itemC).get(0).getStatus());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void configureDummyDistinctLiveCredentials() {
        ReflectionTestUtils.setField(payOsPayoutService, "clientId", "DUMMY_PAYOUT_CID");
        ReflectionTestUtils.setField(payOsPayoutService, "apiKey", "DUMMY_PAYOUT_AKEY");
        ReflectionTestUtils.setField(payOsPayoutService, "checksumKey", "DUMMY_PAYOUT_CKEY");
        ReflectionTestUtils.setField(payOsPayoutService, "paymentClientId", "DUMMY_COLLECTION_CID");
        ReflectionTestUtils.setField(payOsPayoutService, "paymentApiKey", "DUMMY_COLLECTION_AKEY");
        ReflectionTestUtils.setField(payOsPayoutService, "paymentChecksumKey", "DUMMY_COLLECTION_CKEY");
    }

    private String effectiveSigningKey(boolean live) {
        String k = (String) ReflectionTestUtils.getField(payOsPayoutService, "checksumKey");
        if (k != null && !k.isBlank()) {
            return k.trim();
        }
        return live ? "" : "MOCK_PAYOUT_CHECKSUM_KEY";
    }

    private Map<String, Object> buildPayoutDataMap(
            String payosId, String referenceId, long amountVnd, String approvalState, String txnState, String toAccount) {
        Map<String, Object> txn = new LinkedHashMap<>();
        txn.put("id", "txn_" + referenceId);
        txn.put("referenceId", referenceId);
        txn.put("amount", amountVnd);
        txn.put("toBin", "970422");
        txn.put("toAccountNumber", toAccount);
        txn.put("state", txnState);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", payosId);
        data.put("referenceId", referenceId);
        data.put("approvalState", approvalState);
        data.put("transactions", List.of(txn));
        return data;
    }

    private PayOsPayoutService.HttpOutcome buildSignedPayoutOutcome(
            int httpStatus, String payosId, String referenceId, long amountVnd,
            String approvalState, String txnState, String toAccount, String signingKey, boolean includeHeaderSig) {
        try {
            Map<String, Object> data = buildPayoutDataMap(payosId, referenceId, amountVnd, approvalState, txnState, toAccount);
            String sig = PayOsPayoutSignatureUtil.signPayload(data, signingKey);
            String body = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", data));
            return new PayOsPayoutService.HttpOutcome(httpStatus, body, includeHeaderSig ? sig : null, false, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private RequestPostProcessor authOf(User u) {
        BoxCinemaUserDetails details = new BoxCinemaUserDetails(u);
        UserSummaryDto summary = new UserSummaryDto(u.getUserId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole());
        RequestPostProcessor userProc = user(details);
        return request -> {
            Objects.requireNonNull(request.getSession(true)).setAttribute("currentUser", summary);
            return userProc.postProcessRequest(request);
        };
    }

    private <T> T runAs(User actor, java.util.function.Supplier<T> action) {
        BoxCinemaUserDetails details = new BoxCinemaUserDetails(actor);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, "", details.getAuthorities()));
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private User createUser(String email, String role, Long cinemaId) {
        User u = new User("Stage3 " + role, email,
                "09" + String.format("%08d", Math.abs(email.hashCode()) % 100_000_000), "hash", role);
        u.setCinemaId(cinemaId);
        u.setAccountStatus("ACTIVE");
        u = userRepository.save(u);
        createdUserIds.add(u.getUserId());
        return u;
    }

    private Showtime createShowtime(LocalDateTime startAt, LocalDateTime endAt) {
        Showtime st = new Showtime(movie, room1, startAt, endAt, BigDecimal.valueOf(100_000));
        st.setStatus("ACTIVE");
        st = showtimeRepository.save(st);
        createdShowtimeIds.add(st.getShowtimeId());
        return st;
    }

    private Booking createPaidBooking(Showtime st, User owner, long amountVnd, String providerTxRef) {
        BigDecimal amt = BigDecimal.valueOf(amountVnd).setScale(2, RoundingMode.HALF_UP);
        Booking b = new Booking();
        b.setBookingCode("BOX-S3-" + (100_000 + ThreadLocalRandom.current().nextInt(900_000)));
        b.setShowtime(st);
        b.setCinema(cinema1);
        b.setUser(owner);
        b.setOrderType("TICKET_ONLY");
        b.setGuestName(owner.getFullName());
        b.setGuestEmail(owner.getEmail());
        b.setGuestPhone("0901234567");
        b.setTicketSubtotal(amt);
        b.setFoodSubtotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        b.setSubtotal(amt);
        b.setDiscountAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        b.setServiceFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        b.setTotalAmount(amt);
        b.setBookingStatus("CONFIRMED");
        b.setConfirmedAt(LocalDateTime.now(HCM_ZONE));
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());

        BookingSeat bs = bookingSeatRepository.save(new BookingSeat(b, 1L, amt, "A1", "BOOKED"));
        Ticket t = new Ticket();
        t.setBooking(b);
        t.setBookingSeatId(bs.getBookingSeatId());
        t.setShowtimeId(st.getShowtimeId());
        t.setTicketCode("TCK-" + b.getBookingCode().replace("BOX-", "") + "-" + UUID.randomUUID().toString().substring(0, 4));
        t.setQrTokenHash(CryptoUtils.sha256(t.getTicketCode()));
        t.setPrice(amt);
        t.setGrossPrice(amt);
        t.setDiscountAllocated(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        t.setNetPaidAmount(amt);
        t.setTicketStatus("VALID");
        ticketRepository.save(t);

        Payment p = new Payment();
        p.setBooking(b);
        p.setOrderCode(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 8_000_000_000L + 100_000L);
        p.setIdempotencyKey("IDEMP-S3-" + UUID.randomUUID());
        p.setPaymentProvider("PAYOS");
        p.setPaymentMethod("PAYOS_VIETQR");
        p.setProviderTransactionId(providerTxRef);
        p.setPaymentLinkId("plink_" + UUID.randomUUID().toString().substring(0, 8));
        p.setAmount(amt);
        p.setCurrency("VND");
        p.setPaymentStatus("PAID");
        p.setPaidAt(LocalDateTime.now(HCM_ZONE).minusMinutes(5));
        paymentRepository.save(p);
        return b;
    }

    private long createAndApproveStage1(Showtime st, User owner, long amountVnd, String bankAccount) {
        refundRateLimitService.resetForTesting();
        Booking b = createPaidBooking(st, owner, amountVnd, "TXREF_" + UUID.randomUUID().toString().substring(0, 8));
        Ticket t = ticketRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        RefundRequestDetailDto sub = submitCustomerRefund(b, t.getTicketId(), owner, bankAccount);
        return approveStage1ByManager(sub.getId()).getId();
    }

    private RefundRequestDetailDto submitCustomerRefund(Booking b, Long ticketId, User owner, String bankAccount) {
        PublicRefundSubmitRequestDto req = new PublicRefundSubmitRequestDto();
        req.setOrderCode(b.getBookingCode());
        req.setPurchaseEmail(owner.getEmail());
        req.setReasonCategory("CINEMA_INCIDENT");
        req.setDescription("Stage 3 PayOS payout adapter & fee policy verification");
        req.setToBin("970422");
        req.setToAccountNumber(bankAccount);
        req.setConfirmAccountNumber(bankAccount);
        req.setBankAccountName("NGUYEN STAGE THREE");

        PublicRefundSubmitRequestDto.RequestedItemDto item = new PublicRefundSubmitRequestDto.RequestedItemDto();
        item.setItemKind("TICKET");
        item.setTargetId(ticketId);
        item.setQuantity(1);
        req.setItems(List.of(item));

        return refundService.submitPublicRefundRequest(req, owner.getUserId(), "10.30.0.1");
    }

    private RefundRequestDetailDto approveStage1ByManager(Long refundId) {
        return runAs(manager1, () -> {
            RefundRequestDetailDto detail = refundService.getRefundDetailForStaffOrManager(refundId, manager1.getUserId());
            RefundReviewRequestDto dto = new RefundReviewRequestDto();
            dto.setAction("DECIDE_ITEMS");
            dto.setNote("Stage 3 business approval");

            List<RefundReviewRequestDto.ItemDecisionDto> decs = new ArrayList<>();
            for (RefundRequestDetailDto.RefundItemDto item : detail.getItems()) {
                RefundReviewRequestDto.ItemDecisionDto d = new RefundReviewRequestDto.ItemDecisionDto();
                d.setItemId(item.getId());
                d.setDecision("APPROVED");
                d.setApprovedQuantity(item.getRequestedQuantity());
                d.setReason("Verified eligible");
                decs.add(d);
            }
            dto.setItemDecisions(decs);
            return refundService.reviewRefundRequest(refundId, dto, manager1.getUserId());
        });
    }
}
