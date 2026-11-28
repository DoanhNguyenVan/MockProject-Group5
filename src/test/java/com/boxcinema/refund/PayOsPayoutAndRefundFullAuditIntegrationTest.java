package com.boxcinema.refund;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.common.util.PayOsPayoutSignatureUtil;
import com.boxcinema.refund.dto.RefundReviewRequestDto;
import com.boxcinema.refund.dto.StaffFoodActionRequestDto;
import com.boxcinema.refund.service.PayOsPayoutService;
import com.boxcinema.refund.service.RefundApprovalTxService;
import com.boxcinema.staff.service.FoodFulfillmentService;
import com.boxcinema.staff.service.StaffCheckinService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PayOsPayoutAndRefundFullAuditIntegrationTest {

    private static final String TEST_TAG = "PAYOUT_AUD_" + System.currentTimeMillis();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private CinemaRepository cinemaRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingSeatRepository bookingSeatRepository;
    @Autowired private BookingFoodItemRepository bookingFoodItemRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private BookingQrRepository bookingQrRepository;
    @Autowired private FoodFulfillmentRepository foodFulfillmentRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PromotionRepository promotionRepository;
    @Autowired private RefundRequestRepository refundRequestRepository;
    @Autowired private RefundRequestItemRepository refundRequestItemRepository;
    @Autowired private PayOsPayoutTransactionRepository payoutRepository;
    @Autowired private EmailOutboxRepository emailOutboxRepository;
    @Autowired private PayOsPayoutService payOsPayoutService;
    @Autowired private RefundApprovalTxService approvalTxService;
    @Autowired private StaffCheckinService staffCheckinService;
    @Autowired private FoodFulfillmentService foodFulfillmentService;
    @Autowired private PurchaseSnapshotService snapshotService;
    @Autowired private com.boxcinema.refund.service.RefundRateLimitService refundRateLimitService;
    @MockBean private JavaMailSender mailSender;

    @Value("${payos.payout.mode:MOCK}")
    private String payoutMode;

    @Value("${payos.payout.client-id:}")
    private String payoutClientId;

    @Value("${payos.payout.api-key:}")
    private String payoutApiKey;

    @Value("${payos.payout.checksum-key:}")
    private String payoutChecksumKey;

    @Value("${payos.collection.client-id:${payos.client-id:}}")
    private String collectionClientId;

    @Value("${payos.collection.api-key:${payos.api-key:}}")
    private String collectionApiKey;

    @Value("${payos.collection.checksum-key:${payos.checksum-key:}}")
    private String collectionChecksumKey;

    private Cinema cinema;
    private User staff1;
    private User manager1;
    private User admin1;

    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdPromoIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    private String runTag;

    @BeforeEach
    void setup() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        refundRateLimitService.resetForTesting();
        payOsPayoutService.setCustomTransportExecutor(null);
        ReflectionTestUtils.setField(payOsPayoutService, "mode", "MOCK");

        runTag = TEST_TAG + "_" + UUID.randomUUID().toString().substring(0, 6);
        cinema = cinemaRepository.findAll().get(0);
        staff1 = ensureUser("pa_stf_" + runTag + "@box.test", "STAFF", cinema.getCinemaId());
        manager1 = ensureUser("pa_mgr_" + runTag + "@box.test", "MANAGER", cinema.getCinemaId());
        admin1 = ensureUser("pa_adm_" + runTag + "@box.test", "ADMIN", null);
    }

    @AfterEach
    void cleanup() {
        payOsPayoutService.setCustomTransportExecutor(null);
        ReflectionTestUtils.setField(payOsPayoutService, "mode", "MOCK");

        for (Long bid : createdBookingIds) {
            List<Long> rrIds = jdbcTemplate.queryForList("SELECT id FROM refund_requests WHERE booking_id = ?", Long.class, bid);
            List<Long> vrPromoIds = jdbcTemplate.queryForList(
                    "SELECT new_promotion_id FROM voucher_recoveries WHERE booking_id = ? AND new_promotion_id IS NOT NULL",
                    Long.class, bid);
            jdbcTemplate.update("DELETE FROM email_outbox WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM voucher_recoveries WHERE booking_id = ?", bid);
            for (Long pid : vrPromoIds) {
                if (!createdPromoIds.contains(pid)) {
                    createdPromoIds.add(pid);
                }
            }
            for (Long rrId : rrIds) {
                jdbcTemplate.update("DELETE FROM payos_payout_transactions WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE refund_request_id = ? OR (entity_type = 'REFUND_REQUEST' AND entity_id = ?)", rrId, rrId);
            }
            jdbcTemplate.update("DELETE FROM refund_requests WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_checkin WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_qr WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM food_fulfillment WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM payments WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM tickets WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM bookings WHERE booking_id = ?", bid);
        }
        createdBookingIds.clear();

        for (Long pid : createdPromoIds) {
            jdbcTemplate.update("DELETE FROM promotions WHERE promotion_id = ?", pid);
        }
        createdPromoIds.clear();

        for (Long uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE actor_user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", uid);
        }
        createdUserIds.clear();
    }

    @Test
    void payoutEnvConfigVerification_BooleanAndLengthAssertionsOnly() {
        assertEquals("MOCK", payoutMode);
        assertNotNull(payoutClientId);
        assertNotNull(payoutApiKey);
        assertNotNull(payoutChecksumKey);
        assertFalse(payoutClientId.isBlank());
        assertFalse(payoutApiKey.isBlank());
        assertFalse(payoutChecksumKey.isBlank());
        assertTrue(payoutClientId.trim().length() >= 10);
        assertTrue(payoutApiKey.trim().length() >= 10);
        assertTrue(payoutChecksumKey.trim().length() >= 32);

        assertFalse(payoutClientId.trim().equalsIgnoreCase(collectionClientId != null ? collectionClientId.trim() : ""));
        assertNotEquals(payoutApiKey.trim(), collectionApiKey != null ? collectionApiKey.trim() : "");
        assertNotEquals(payoutChecksumKey.trim(), collectionChecksumKey != null ? collectionChecksumKey.trim() : "");

        String sig = PayOsPayoutSignatureUtil.signPayload(Map.of("referenceId", "CFG-CHECK", "amount", 10000), effectivePayoutKey());
        assertNotNull(sig);
        assertEquals(64, sig.length());
        assertTrue(PayOsPayoutSignatureUtil.verifySignature(Map.of("referenceId", "CFG-CHECK", "amount", 10000), sig, effectivePayoutKey()));
    }

    @Test
    void payoutMatrix_Success_ProcessingReconcile_Failed_RetryA2_HttpErrors_MalformedJson_InvalidSig_Mismatch_And_RawBankMasking() throws Exception {
        // 1. SUCCESS + raw response bank masking
        Booking b1 = createConfirmedTicketBooking("pa1_" + TEST_TAG + "@box.test", 100000L, false);
        long rr1 = submitTicketRefund(b1, "0987654321");
        final String[] capturedRef1 = new String[1];
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                capturedRef1[0] = String.valueOf(bodyMap.get("referenceId"));
                return buildSignedPayoutOutcome(200, "po_ok_1", capturedRef1[0], 100000L, "COMPLETED", "SUCCEEDED", "0987654321", effectivePayoutKey());
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return buildSignedPayoutOutcome(200, "po_ok_1", capturedRef1[0], 100000L, "COMPLETED", "SUCCEEDED", "0987654321", effectivePayoutKey());
            }
        });
        approveRefund(rr1);
        PayOsPayoutTransaction tx1 = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rr1).get(0);
        assertEquals("SUCCEEDED", tx1.getStatus());
        assertEquals("COMPLETED", refundRequestRepository.findById(rr1).orElseThrow().getRequestStatus());
        assertNotNull(tx1.getRawResponseSanitized());
        assertFalse(tx1.getRawResponseSanitized().contains("0987654321"));
        assertTrue(tx1.getRawResponseSanitized().contains("******"));
        assertTrue(emailOutboxRepository.existsByDedupKey("REFUND_PAYOUT_SUCCEEDED:" + rr1));

        // 2. PROCESSING -> reconcile same referenceId -> SUCCEEDED
        Booking b2 = createConfirmedTicketBooking("pa2_" + TEST_TAG + "@box.test", 100000L, false);
        long rr2 = submitTicketRefund(b2, "0911223344");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                String ref = String.valueOf(bodyMap.get("referenceId"));
                return buildSignedPayoutOutcome(200, "po_proc_2", ref, 100000L, "PENDING", "PROCESSING", "0911223344", effectivePayoutKey());
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return buildSignedPayoutOutcome(200, "po_proc_2", "RFD-" + rr2 + "-A1", 100000L, "COMPLETED", "SUCCEEDED", "0911223344", effectivePayoutKey());
            }
        });
        approveRefund(rr2);
        assertEquals("PROCESSING", refundRequestRepository.findById(rr2).orElseThrow().getPayoutStatus());
        retryPayoutApi(rr2);
        List<PayOsPayoutTransaction> rr2Attempts = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rr2);
        assertEquals(1, rr2Attempts.size());
        assertEquals("SUCCEEDED", rr2Attempts.get(0).getStatus());
        assertEquals("COMPLETED", refundRequestRepository.findById(rr2).orElseThrow().getRequestStatus());

        // 3. HTTP 400/401/403 -> FAILED, retry -> A2 SUCCEEDED
        for (int httpErr : List.of(400, 401, 403)) {
            Booking bErr = createConfirmedTicketBooking("paerr_" + httpErr + "_" + TEST_TAG + "@box.test", 100000L, false);
            long rrErr = submitTicketRefund(bErr, "0922334455");
            payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
                @Override
                public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                    return new PayOsPayoutService.HttpOutcome(httpErr, "{\"code\":\"622\",\"desc\":\"Rejected\"}", null, false, "HTTP " + httpErr);
                }
                @Override
                public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                    return new PayOsPayoutService.HttpOutcome(404, "{\"code\":\"608\"}", null, false, "Not Found");
                }
            });
            approveRefund(rrErr);
            PayOsPayoutTransaction firstAttempt = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrErr).get(0);
            assertEquals("FAILED", firstAttempt.getStatus(), "HTTP " + httpErr + " must mark FAILED");
            assertNull(firstAttempt.getActiveRequestLock());

            // Retry -> A2 succeeds
            payOsPayoutService.setCustomTransportExecutor(null);
            retryPayoutApi(rrErr);
            List<PayOsPayoutTransaction> attempts = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrErr);
            assertEquals(2, attempts.size());
            assertEquals("RFD-" + rrErr + "-A2", attempts.get(0).getReferenceId());
            assertEquals("SUCCEEDED", attempts.get(0).getStatus());
            assertEquals("COMPLETED", refundRequestRepository.findById(rrErr).orElseThrow().getRequestStatus());
        }

        // 4. HTTP 500, Malformed JSON on 200, Invalid response signature (MOCK & LIVE), Mismatched amount & referenceId -> PROCESSING
        List<String> modesToTest = List.of("MOCK", "LIVE");
        for (String testMode : modesToTest) {
            Booking bSig = createConfirmedTicketBooking("pasig_" + testMode + "_" + TEST_TAG + "@box.test", 100000L, false);
            long rrSig = submitTicketRefund(bSig, "0933445566");
            ReflectionTestUtils.setField(payOsPayoutService, "mode", testMode);
            payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
                @Override
                public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                    return buildSignedPayoutOutcome(200, "po_badsig", String.valueOf(bodyMap.get("referenceId")), 100000L, "COMPLETED", "SUCCEEDED", "0933445566", "WRONG_SIG_KEY");
                }
                @Override
                public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                    return new PayOsPayoutService.HttpOutcome(500, "internal error", null, false, "500");
                }
            });
            approveRefund(rrSig);
            PayOsPayoutTransaction badSigTx = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrSig).get(0);
            assertEquals("PROCESSING", badSigTx.getStatus(), "Invalid signature in " + testMode + " must stay PROCESSING");
            ReflectionTestUtils.setField(payOsPayoutService, "mode", "MOCK");
        }

        // HTTP 500 & Malformed JSON on 200 -> PROCESSING
        Booking b500 = createConfirmedTicketBooking("pa500_" + TEST_TAG + "@box.test", 100000L, false);
        long rr500 = submitTicketRefund(b500, "0944556677");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(500, "gateway error", null, false, "500");
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(200, "{not-valid-json", null, false, null);
            }
        });
        approveRefund(rr500);
        assertEquals("PROCESSING", payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rr500).get(0).getStatus());

        Booking bMal = createConfirmedTicketBooking("pamal_" + TEST_TAG + "@box.test", 100000L, false);
        long rrMal = submitTicketRefund(bMal, "0955667788");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(200, "{broken_json", null, false, null);
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "err", null, false, "500");
            }
        });
        approveRefund(rrMal);
        assertEquals("PROCESSING", payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrMal).get(0).getStatus());

        // Mismatched amount (50,000 vs 100,000) & mismatched referenceId -> stay PROCESSING
        Booking bAmt = createConfirmedTicketBooking("paamt_" + TEST_TAG + "@box.test", 100000L, false);
        long rrAmt = submitTicketRefund(bAmt, "0966778899");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                String ref = String.valueOf(bodyMap.get("referenceId"));
                return buildSignedPayoutOutcome(200, "po_mis_amt", ref, 50000L, "COMPLETED", "SUCCEEDED", "0966778899", effectivePayoutKey());
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "500");
            }
        });
        approveRefund(rrAmt);
        assertEquals("PROCESSING", payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrAmt).get(0).getStatus());

        Booking bRef = createConfirmedTicketBooking("paref_" + TEST_TAG + "@box.test", 100000L, false);
        long rrRef = submitTicketRefund(bRef, "0977889900");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return buildSignedPayoutOutcome(200, "po_mis_ref", "RFD-WRONG-REF", 100000L, "COMPLETED", "SUCCEEDED", "0977889900", effectivePayoutKey());
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "500");
            }
        });
        approveRefund(rrRef);
        assertEquals("PROCESSING", payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrRef).get(0).getStatus());
    }

    @Test
    void payoutWebhook_SignatureValidation_Idempotency_And_MonotonicOutOfOrderProtection() throws Exception {
        // 1. Start with PROCESSING payout
        Booking b = createConfirmedTicketBooking("wh_" + TEST_TAG + "@box.test", 100000L, false);
        long rrId = submitTicketRefund(b, "0988112233");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                String ref = String.valueOf(bodyMap.get("referenceId"));
                return buildSignedPayoutOutcome(200, "po_wh_1", ref, 100000L, "PENDING", "PROCESSING", "0988112233", effectivePayoutKey());
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "500");
            }
        });
        approveRefund(rrId);
        String refId = "RFD-" + rrId + "-A1";

        Map<String, Object> okData = buildPayoutDataMap("po_wh_1", refId, 100000L, "COMPLETED", "SUCCEEDED", "0988112233");
        String okBody = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", okData));
        String validSig = PayOsPayoutSignatureUtil.signPayload(okData, effectivePayoutKey());
        String colForgedSig = PayOsPayoutSignatureUtil.signPayload(okData, collectionChecksumKey != null && !collectionChecksumKey.isBlank() ? collectionChecksumKey : "OTHER_KEY");

        // Missing / invalid / Collection-forged signature -> 400
        mockMvc.perform(post("/api/payments/payos/payout/webhook").contentType(MediaType.APPLICATION_JSON).content(okBody))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/payments/payos/payout/webhook").header("x-signature", "0".repeat(64)).contentType(MediaType.APPLICATION_JSON).content(okBody))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/payments/payos/payout/webhook").header("x-signature", colForgedSig).contentType(MediaType.APPLICATION_JSON).content(okBody))
                .andExpect(status().isBadRequest());
        assertEquals("PROCESSING", payoutRepository.findByReferenceId(refId).orElseThrow().getStatus());

        // Valid Payout signature -> SUCCEEDED + COMPLETED + Stage 5 email
        mockMvc.perform(post("/api/payments/payos/payout/webhook").header("x-signature", validSig).contentType(MediaType.APPLICATION_JSON).content(okBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"));
        assertEquals("SUCCEEDED", payoutRepository.findByReferenceId(refId).orElseThrow().getStatus());
        assertEquals("COMPLETED", refundRequestRepository.findById(rrId).orElseThrow().getRequestStatus());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE dedup_key = ?",
                Integer.class, "REFUND_PAYOUT_SUCCEEDED:" + rrId));

        // Duplicate webhook -> idempotent 200
        mockMvc.perform(post("/api/payments/payos/payout/webhook").header("x-signature", validSig).contentType(MediaType.APPLICATION_JSON).content(okBody))
                .andExpect(status().isOk());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE dedup_key = ?",
                Integer.class, "REFUND_PAYOUT_SUCCEEDED:" + rrId));

        // Out-of-order PROCESSING and FAILED after SUCCEEDED -> never downgrades
        for (String[] states : List.of(new String[]{"PENDING", "PROCESSING"}, new String[]{"FAILED", "FAILED"})) {
            Map<String, Object> lateData = buildPayoutDataMap("po_wh_1", refId, 100000L, states[0], states[1], "0988112233");
            String lateSig = PayOsPayoutSignatureUtil.signPayload(lateData, effectivePayoutKey());
            String lateBody = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "late", "data", lateData));
            mockMvc.perform(post("/api/payments/payos/payout/webhook").header("x-signature", lateSig).contentType(MediaType.APPLICATION_JSON).content(lateBody))
                    .andExpect(status().isOk());
            assertEquals("SUCCEEDED", payoutRepository.findByReferenceId(refId).orElseThrow().getStatus());
            assertEquals("COMPLETED", refundRequestRepository.findById(rrId).orElseThrow().getRequestStatus());
        }

        // Out-of-order PROCESSING after FAILED -> never reverts FAILED
        Booking bFail = createConfirmedTicketBooking("whfail_" + TEST_TAG + "@box.test", 100000L, false);
        long rrFailId = submitTicketRefund(bFail, "0988990011");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(400, "{\"code\":\"622\",\"desc\":\"Invalid account\"}", null, false, "400");
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(404, "{\"code\":\"608\"}", null, false, "404");
            }
        });
        approveRefund(rrFailId);
        String failRefId = "RFD-" + rrFailId + "-A1";
        assertEquals("FAILED", payoutRepository.findByReferenceId(failRefId).orElseThrow().getStatus());

        Map<String, Object> lateProcData = buildPayoutDataMap("po_wh_fail", failRefId, 100000L, "PENDING", "PROCESSING", "0988990011");
        String lateProcSig = PayOsPayoutSignatureUtil.signPayload(lateProcData, effectivePayoutKey());
        String lateProcBody = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "late", "data", lateProcData));
        mockMvc.perform(post("/api/payments/payos/payout/webhook").header("x-signature", lateProcSig).contentType(MediaType.APPLICATION_JSON).content(lateProcBody))
                .andExpect(status().isOk());
        assertEquals("FAILED", payoutRepository.findByReferenceId(failRefId).orElseThrow().getStatus());
    }

    @Test
    void payoutWebhook_FailedAndRecovery_UnknownRef_Http429AndCode632UnknownOutcome_And_RemotePayoutListing() throws Exception {
        // 1. PROCESSING -> FAILED via Payout Webhook (releases activeRequestLock) -> Admin retry A2 -> SUCCEEDED & COMPLETED
        Booking b1 = createConfirmedTicketBooking("whfail_rec_" + TEST_TAG + "@box.test", 100000L, false);
        long rr1 = submitTicketRefund(b1, "0988110001");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                String ref = String.valueOf(bodyMap.get("referenceId"));
                return buildSignedPayoutOutcome(200, "po_whf_1", ref, 100000L, "PENDING", "PROCESSING", "0988110001", effectivePayoutKey());
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "500");
            }
        });
        approveRefund(rr1);
        String ref1 = "RFD-" + rr1 + "-A1";
        assertEquals("PROCESSING", payoutRepository.findByReferenceId(ref1).orElseThrow().getStatus());

        Map<String, Object> failData = buildPayoutDataMap("po_whf_1", ref1, 100000L, "FAILED", "FAILED", "0988110001");
        String failSig = PayOsPayoutSignatureUtil.signPayload(failData, effectivePayoutKey());
        String failBody = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "failed", "data", failData));
        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .header("x-signature", failSig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(failBody))
                .andExpect(status().isOk());

        PayOsPayoutTransaction tx1AfterFail = payoutRepository.findByReferenceId(ref1).orElseThrow();
        assertEquals("FAILED", tx1AfterFail.getStatus());
        assertNull(tx1AfterFail.getActiveRequestLock(), "FAILED webhook must release activeRequestLock for retry");
        assertEquals("PAYOUT_FAILED", refundRequestRepository.findById(rr1).orElseThrow().getRequestStatus());

        payOsPayoutService.setCustomTransportExecutor(null);
        retryPayoutApi(rr1);
        List<PayOsPayoutTransaction> rr1Attempts = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rr1);
        assertEquals(2, rr1Attempts.size());
        assertEquals("RFD-" + rr1 + "-A2", rr1Attempts.get(0).getReferenceId());
        assertEquals("SUCCEEDED", rr1Attempts.get(0).getStatus());
        assertEquals("COMPLETED", refundRequestRepository.findById(rr1).orElseThrow().getRequestStatus());

        // 2. FAILED -> SUCCEEDED via late Payout Webhook (also tests JSON body signature fallback & unknown referenceId 200)
        Booking b2 = createConfirmedTicketBooking("whf2s_" + TEST_TAG + "@box.test", 100000L, false);
        long rr2 = submitTicketRefund(b2, "0988110002");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(400, "{\"code\":\"622\",\"desc\":\"Temporary error\"}", null, false, "400");
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(404, "{\"code\":\"608\"}", null, false, "404");
            }
        });
        approveRefund(rr2);
        String ref2 = "RFD-" + rr2 + "-A1";
        assertEquals("FAILED", payoutRepository.findByReferenceId(ref2).orElseThrow().getStatus());

        Map<String, Object> recData = buildPayoutDataMap("po_whf_2", ref2, 100000L, "COMPLETED", "SUCCEEDED", "0988110002");
        String recSig = PayOsPayoutSignatureUtil.signPayload(recData, effectivePayoutKey());
        String recBodyWithSig = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", recData, "signature", recSig));
        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(recBodyWithSig))
                .andExpect(status().isOk());
        assertEquals("SUCCEEDED", payoutRepository.findByReferenceId(ref2).orElseThrow().getStatus());
        assertEquals("COMPLETED", refundRequestRepository.findById(rr2).orElseThrow().getRequestStatus());
        assertTrue(emailOutboxRepository.existsByDedupKey("REFUND_PAYOUT_SUCCEEDED:" + rr2));

        Map<String, Object> unkData = buildPayoutDataMap("po_unk_99", "RFD-UNKNOWN-99999", 100000L, "COMPLETED", "SUCCEEDED", "0988110099");
        String unkSig = PayOsPayoutSignatureUtil.signPayload(unkData, effectivePayoutKey());
        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .header("x-signature", unkSig)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", unkData))))
                .andExpect(status().isOk());

        // 3. HTTP 429 and PayOS interrupted code 632 -> PROCESSING + UNKNOWN_OUTCOME
        Booking b429 = createConfirmedTicketBooking("po429_" + TEST_TAG + "@box.test", 100000L, false);
        long rr429 = submitTicketRefund(b429, "0988110003");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(429, "{\"code\":\"429\",\"desc\":\"Too Many Requests\"}", null, false, "429");
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "500");
            }
        });
        approveRefund(rr429);
        PayOsPayoutTransaction tx429 = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rr429).get(0);
        assertEquals("PROCESSING", tx429.getStatus());
        assertEquals("UNKNOWN_OUTCOME", tx429.getTransactionState());

        Booking b632 = createConfirmedTicketBooking("po632_" + TEST_TAG + "@box.test", 100000L, false);
        long rr632 = submitTicketRefund(b632, "0988110004");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(200, "{\"code\":\"632\",\"desc\":\"Interrupted transfer\"}", null, false, null);
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "500");
            }
        });
        approveRefund(rr632);
        PayOsPayoutTransaction tx632 = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rr632).get(0);
        assertEquals("PROCESSING", tx632.getStatus());
        assertEquals("UNKNOWN_OUTCOME", tx632.getTransactionState());

        // 4. GET /api/admin/payouts/payos-remote in MOCK mode & mocked LIVE mode
        payOsPayoutService.setCustomTransportExecutor(null);
        mockMvc.perform(get("/api/admin/payouts/payos-remote")
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .param("referenceId", "RFD-" + rr1 + "-A2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MOCK"))
                .andExpect(jsonPath("$.code").value("00"))
                .andExpect(jsonPath("$.payouts[0].referenceId").value("RFD-" + rr1 + "-A2"));

        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "500");
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(200,
                        "{\"code\":\"00\",\"desc\":\"success\",\"data\":{\"payouts\":[{\"id\":\"po_rem_1\",\"referenceId\":\"RFD-" + rr1 + "-A2\",\"approvalState\":\"COMPLETED\",\"transactions\":[{\"id\":\"tx_rem_1\",\"toAccountNumber\":\"0123456789\",\"accountNumber\":\"0998877665\",\"state\":\"SUCCEEDED\"}]}]}}",
                        null, false, null);
            }
        });
        mockMvc.perform(get("/api/admin/payouts/payos-remote")
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .param("referenceId", "RFD-" + rr1 + "-A2")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"))
                .andExpect(jsonPath("$.payouts[0].id").value("po_rem_1"))
                .andExpect(jsonPath("$.payouts[0].transactions[0].toAccountNumber").value("******6789"))
                .andExpect(content().string(not(containsString("0123456789"))))
                .andExpect(content().string(not(containsString("0998877665"))));
    }

    @Test
    void concurrency_GraceWindow608_ZeroNegativeCapValidation_RestartReconcile_AbBaCheckinLocking_And_FoodGuard() throws Exception {
        // 1. 12 concurrent refund submissions for same ticket -> 1 gets 201, 11 get 409
        Booking bSubRace = createConfirmedTicketBooking("sub12_" + TEST_TAG + "@box.test", 100000L, false);
        Ticket tSub = ticketRepository.findByBooking_BookingId(bSubRace.getBookingId()).get(0);
        String subPayload = objectMapper.writeValueAsString(Map.of(
                "orderCode", bSubRace.getBookingCode(),
                "purchaseEmail", bSubRace.getGuestEmail(),
                "reasonCategory", "OTHER",
                "description", "Concurrent submit",
                "toBin", "970422",
                "toAccountNumber", "0912345678",
                "confirmAccountNumber", "0912345678",
                "bankAccountName", "Audit Customer",
                "items", List.of(Map.of("itemKind", "TICKET", "targetId", tSub.getTicketId(), "quantity", 1))));

        ExecutorService subPool = Executors.newFixedThreadPool(12);
        CountDownLatch subReady = new CountDownLatch(12);
        CountDownLatch subStart = new CountDownLatch(1);
        List<Future<Integer>> subFutures = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                final String clientIp = "10.10.0." + (i + 1);
                subFutures.add(subPool.submit(() -> {
                    subReady.countDown();
                    subStart.await();
                    return mockMvc.perform(post("/api/refunds/public").with(csrf())
                                    .with(req -> { req.setRemoteAddr(clientIp); return req; })
                                    .contentType(MediaType.APPLICATION_JSON).content(subPayload))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(subReady.await(5, TimeUnit.SECONDS));
            subStart.countDown();
            int ok = 0, conflict = 0;
            for (Future<Integer> f : subFutures) {
                int st = f.get(20, TimeUnit.SECONDS);
                if (st == 201) ok++;
                else if (st == 409) conflict++;
                else fail("Unexpected status in 12-thread submit: " + st);
            }
            assertEquals(1, ok);
            assertEquals(11, conflict);
        } finally {
            subPool.shutdownNow();
        }

        // 2. 12 concurrent approvals for same discounted booking -> 1 payout & 1 recovery voucher
        Booking bAppRace = createDiscountedBooking("app12_" + TEST_TAG + "@box.test", 90000L, 10000L);
        long rrAppId = submitTicketRefund(bAppRace, "0923456789");
        long rrAppItemId = refundRequestItemRepository.findByRefundRequest_IdOrderByIdAsc(rrAppId).get(0).getId();
        String appBody = objectMapper.writeValueAsString(Map.of(
                "action", "DECIDE_ITEMS",
                "itemDecisions", List.of(Map.of("itemId", rrAppItemId, "decision", "APPROVED", "approvedQuantity", 1))));

        ExecutorService appPool = Executors.newFixedThreadPool(12);
        CountDownLatch appReady = new CountDownLatch(12);
        CountDownLatch appStart = new CountDownLatch(1);
        List<Future<Integer>> appFutures = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                appFutures.add(appPool.submit(() -> {
                    appReady.countDown();
                    appStart.await();
                    return mockMvc.perform(post("/api/manager/refunds/" + rrAppId + "/review").with(csrf())
                                    .sessionAttr("currentUser", dtoOf(manager1))
                                    .with(user(manager1.getEmail()).roles("MANAGER"))
                                    .contentType(MediaType.APPLICATION_JSON).content(appBody))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(appReady.await(5, TimeUnit.SECONDS));
            appStart.countDown();
            for (Future<Integer> f : appFutures) {
                assertEquals(200, f.get(20, TimeUnit.SECONDS));
            }
        } finally {
            appPool.shutdownNow();
        }
        assertEquals("AWAITING_PAYOUT_APPROVAL", refundRequestRepository.findById(rrAppId).orElseThrow().getRequestStatus());
        assertEquals(0, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrAppId).size());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM voucher_recoveries WHERE refund_request_id = ?", Integer.class, rrAppId));
        jdbcTemplate.queryForList("SELECT new_promotion_id FROM voucher_recoveries WHERE refund_request_id = ?", Long.class, rrAppId)
                .stream().filter(Objects::nonNull).forEach(createdPromoIds::add);

        // Concurrent 12-thread Admin payout approvals for the queued request -> EXACTLY 1 PayOS payout
        ExecutorService payoutPool = Executors.newFixedThreadPool(12);
        CountDownLatch payoutReady = new CountDownLatch(12);
        CountDownLatch payoutStart = new CountDownLatch(1);
        List<Future<Integer>> payoutFutures = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                payoutFutures.add(payoutPool.submit(() -> {
                    payoutReady.countDown();
                    payoutStart.await();
                    return mockMvc.perform(post("/api/admin/refunds/" + rrAppId + "/payout/approve").with(csrf())
                                    .sessionAttr("currentUser", dtoOf(admin1))
                                    .with(user(admin1.getEmail()).roles("ADMIN")))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(payoutReady.await(5, TimeUnit.SECONDS));
            payoutStart.countDown();
            for (Future<Integer> f : payoutFutures) {
                assertEquals(200, f.get(20, TimeUnit.SECONDS));
            }
        } finally {
            payoutPool.shutdownNow();
        }
        assertEquals(1, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrAppId).size());
        assertEquals("COMPLETED", refundRequestRepository.findById(rrAppId).orElseThrow().getRequestStatus());

        // 3. In-flight 608 during <120s grace window stays PROCESSING (no false FAILED, no duplicate payout), then fails after >120s
        Booking b608 = createConfirmedTicketBooking("g608_" + TEST_TAG + "@box.test", 100000L, false);
        long rr608Id = submitTicketRefund(b608, "0934567890");
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(0, null, null, true, "Read timed out");
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(404, "{\"code\":\"608\",\"desc\":\"Not found\"}", null, false, "404");
            }
        });
        approveRefund(rr608Id);
        // Operator calls retry-payout during grace window (<120s) -> must stay PROCESSING!
        retryPayoutApi(rr608Id);
        PayOsPayoutTransaction tx608 = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rr608Id).get(0);
        assertEquals("PROCESSING", tx608.getStatus());
        assertEquals(1, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rr608Id).size());

        // Move initiated_at past 120s grace window -> scheduledReconcileProcessingPayouts marks FAILED cleanly
        jdbcTemplate.update("UPDATE payos_payout_transactions SET initiated_at = DATE_SUB(NOW(), INTERVAL 180 SECOND) WHERE id = ?", tx608.getId());
        payOsPayoutService.scheduledReconcileProcessingPayouts();
        assertEquals("FAILED", payoutRepository.findById(tx608.getId()).orElseThrow().getStatus());

        // 4. JVM restart scheduledReconcileProcessingPayouts recovers stale PENDING (>30s) & PROCESSING, skips fresh PENDING (<30s)
        payOsPayoutService.setCustomTransportExecutor(null);
        Booking bRec = createConfirmedTicketBooking("rec_" + TEST_TAG + "@box.test", 100000L, false);
        long rrRecId = submitTicketRefund(bRec, "0945678901");
        RefundRequest rrRec = refundRequestRepository.findById(rrRecId).orElseThrow();
        rrRec.setRequestStatus("PAYOUT_PROCESSING");
        rrRec.setApprovedCashTotal(BigDecimal.valueOf(100000));
        rrRec.setPayoutStatus("PROCESSING");
        refundRequestRepository.save(rrRec);

        PayOsPayoutTransaction stalePending = new PayOsPayoutTransaction();
        stalePending.setRefundRequest(rrRec);
        stalePending.setReferenceId("RFD-" + rrRecId + "-A1");
        stalePending.setIdempotencyKey("IDEMP-" + rrRecId + "-STALE");
        stalePending.setAmount(100000L);
        stalePending.setDescription("Stale Pending");
        stalePending.setToBin("970422");
        stalePending.setToAccountNumberMasked("******8901");
        stalePending.setStatus("PENDING");
        stalePending.setActiveRequestLock(rrRecId);
        stalePending = payoutRepository.save(stalePending);
        jdbcTemplate.update("UPDATE payos_payout_transactions SET initiated_at = DATE_SUB(NOW(), INTERVAL 60 SECOND) WHERE id = ?", stalePending.getId());

        Booking bFresh = createConfirmedTicketBooking("fresh_" + runTag + "@box.test", 100000L, false);
        long rrFreshId = submitTicketRefund(bFresh, "0945678902");
        RefundRequest rrFresh = refundRequestRepository.findById(rrFreshId).orElseThrow();
        rrFresh.setRequestStatus("PAYOUT_PROCESSING");
        rrFresh.setApprovedCashTotal(BigDecimal.valueOf(100000));
        rrFresh.setPayoutStatus("PROCESSING");
        refundRequestRepository.save(rrFresh);

        PayOsPayoutTransaction freshPending = new PayOsPayoutTransaction();
        freshPending.setRefundRequest(rrFresh);
        freshPending.setReferenceId("RFD-" + rrFreshId + "-A1");
        freshPending.setIdempotencyKey("IDEMP-" + rrFreshId + "-FRESH");
        freshPending.setAmount(100000L);
        freshPending.setDescription("Fresh Pending");
        freshPending.setToBin("970422");
        freshPending.setToAccountNumberMasked("******8902");
        freshPending.setStatus("PENDING");
        freshPending.setActiveRequestLock(rrFreshId);
        freshPending = payoutRepository.save(freshPending);

        payOsPayoutService.scheduledReconcileProcessingPayouts();
        assertEquals("SUCCEEDED", payoutRepository.findById(stalePending.getId()).orElseThrow().getStatus());
        assertEquals("COMPLETED", refundRequestRepository.findById(rrRecId).orElseThrow().getRequestStatus());
        assertEquals("PENDING", payoutRepository.findById(freshPending.getId()).orElseThrow().getStatus());

        // 5. Zero cash (NOT_REQUIRED), negative amount (400), over-paid cap (409), invalid bank (400)
        Booking bZero = createConfirmedTicketBooking("zero_" + TEST_TAG + "@box.test", 100000L, false);
        long rrZeroId = submitTicketRefund(bZero, "0956789012");
        RefundRequest rrZero = refundRequestRepository.findById(rrZeroId).orElseThrow();
        rrZero.setRequestStatus("PAYOUT_APPROVED");
        rrZero.setApprovedCashTotal(BigDecimal.ZERO);
        rrZero.setPayoutStatus("PENDING");
        refundRequestRepository.save(rrZero);
        assertNull(payOsPayoutService.initiateOrReconcilePayout(rrZeroId, admin1.getUserId()));
        assertEquals("NOT_REQUIRED", refundRequestRepository.findById(rrZeroId).orElseThrow().getPayoutStatus());

        // v6 DB CHECK constraint chk_rr_totals_nonneg blocks negative approved_cash_total at persistence layer
        rrZero.setApprovedCashTotal(BigDecimal.valueOf(-1000));
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> refundRequestRepository.saveAndFlush(rrZero));
        rrZero.setApprovedCashTotal(BigDecimal.ZERO);

        // Over-paid cap when paid Payment.amount (50k) < booking.totalAmount (100k) -> 409 PAYOUT_CAP_EXCEEDED
        Booking bCap = createConfirmedTicketBooking("cap_" + TEST_TAG + "@box.test", 100000L, false);
        Payment partialPay = new Payment();
        partialPay.setBooking(bCap);
        partialPay.setOrderCode(System.nanoTime() % 9_000_000_000L + 1000L);
        partialPay.setAmount(BigDecimal.valueOf(50000));
        partialPay.setIdempotencyKey("IDEMP-CAP-" + UUID.randomUUID());
        partialPay.setPaymentStatus("PAID");
        paymentRepository.save(partialPay);
        long rrCapId = submitTicketRefund(bCap, "0967890123");
        long rrCapItem = refundRequestItemRepository.findByRefundRequest_IdOrderByIdAsc(rrCapId).get(0).getId();
        mockMvc.perform(post("/api/manager/refunds/" + rrCapId + "/review").with(csrf())
                        .sessionAttr("currentUser", dtoOf(manager1))
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "DECIDE_ITEMS",
                                "itemDecisions", List.of(Map.of("itemId", rrCapItem, "decision", "APPROVED", "approvedQuantity", 1))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("PAYOUT_CAP_EXCEEDED"));

        // Invalid bank BIN -> 400
        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", bCap.getBookingCode(),
                                "purchaseEmail", bCap.getGuestEmail(),
                                "reasonCategory", "OTHER",
                                "description", "Bad BIN",
                                "toBin", "000000",
                                "toAccountNumber", "0967890123",
                                "confirmAccountNumber", "0967890123",
                                "bankAccountName", "Audit Customer",
                                "items", List.of(Map.of("itemKind", "TICKET", "targetId", 1, "quantity", 1))))))
                .andExpect(status().isBadRequest());

        // 6. Concurrent StaffCheckinService.checkinByQrToken + RefundApprovalTxService.applyReviewDecision -> zero AB-BA deadlock
        for (int round = 0; round < 3; round++) {
            Booking bLock = createConfirmedTicketBooking("abba_" + round + "_" + TEST_TAG + "@box.test", 100000L, false);
            String qrToken = "BOX1.ABBA" + round + TEST_TAG;
            BookingQr qr = new BookingQr();
            qr.setBooking(bLock);
            qr.setTokenHash(CryptoUtils.sha256(qrToken));
            qr.setTokenEncrypted("enc");
            qr.setStatus("ACTIVE");
            bookingQrRepository.save(qr);

            long rrLockId = submitTicketRefund(bLock, "0911112222");
            long rrLockItem = refundRequestItemRepository.findByRefundRequest_IdOrderByIdAsc(rrLockId).get(0).getId();
            RefundReviewRequestDto revDto = new RefundReviewRequestDto();
            revDto.setAction("DECIDE_ITEMS");
            RefundReviewRequestDto.ItemDecisionDto itemDec = new RefundReviewRequestDto.ItemDecisionDto();
            itemDec.setItemId(rrLockItem);
            itemDec.setDecision("APPROVED");
            itemDec.setApprovedQuantity(1);
            revDto.setItemDecisions(List.of(itemDec));

            ExecutorService abPool = Executors.newFixedThreadPool(2);
            CountDownLatch abReady = new CountDownLatch(2);
            CountDownLatch abStart = new CountDownLatch(1);
            Future<?> fCheckin = abPool.submit(() -> {
                abReady.countDown();
                abStart.await();
                return staffCheckinService.checkinByQrToken(qrToken, staff1.getUserId());
            });
            Future<?> fApprove = abPool.submit(() -> {
                abReady.countDown();
                abStart.await();
                try {
                    return approvalTxService.applyReviewDecision(rrLockId, revDto, manager1.getUserId());
                } catch (BusinessException expectedWhenCheckedInFirst) {
                    return expectedWhenCheckedInFirst.getErrorCode();
                }
            });
            assertTrue(abReady.await(5, TimeUnit.SECONDS));
            abStart.countDown();
            assertDoesNotThrow(() -> fCheckin.get(15, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> fApprove.get(15, TimeUnit.SECONDS));
            abPool.shutdownNow();
        }

        // 7. Already-refunded food delivery rejected (EXCESS_QTY 400)
        Booking bFood = createFoodOnlyOrder("foodref_" + TEST_TAG + "@box.test");
        BookingFoodItem bfi = bookingFoodItemRepository.findByBooking_BookingId(bFood.getBookingId()).get(0);
        FoodFulfillment ff = foodFulfillmentRepository.findByBooking_BookingId(bFood.getBookingId()).orElseThrow();
        MvcResult subFood = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", bFood.getBookingCode(),
                                "purchaseEmail", bFood.getGuestEmail(),
                                "reasonCategory", "UNDELIVERED_FOOD",
                                "description", "Never delivered",
                                "toBin", "970422",
                                "toAccountNumber", "0944332211",
                                "confirmAccountNumber", "0944332211",
                                "bankAccountName", "Audit Customer",
                                "items", List.of(Map.of("itemKind", "FOOD_ITEM", "targetId", bfi.getId(), "quantity", 1))))))
                .andExpect(status().isCreated())
                .andReturn();
        long rrFoodId = objectMapper.readTree(subFood.getResponse().getContentAsString()).get("id").asLong();
        approveRefund(rrFoodId);

        StaffFoodActionRequestDto deliverReq = new StaffFoodActionRequestDto();
        deliverReq.setBookingFoodItemId(bfi.getId());
        deliverReq.setActionType("INITIAL_DELIVERY");
        deliverReq.setQuantity(1);
        BusinessException excessEx = assertThrows(BusinessException.class,
                () -> foodFulfillmentService.recordDeliveryAction(ff.getId(), deliverReq, staff1.getUserId()));
        assertEquals(HttpStatus.BAD_REQUEST, excessEx.getStatus());
        assertEquals("EXCESS_QTY", excessEx.getErrorCode());
    }

    private String effectivePayoutKey() {
        return (payoutChecksumKey != null && !payoutChecksumKey.isBlank())
                ? payoutChecksumKey.trim()
                : "MOCK_PAYOUT_CHECKSUM_KEY";
    }

    private Map<String, Object> buildPayoutDataMap(String poId, String refId, long amount,
                                                   String approvalState, String txnState, String accountNumber) {
        Map<String, Object> txn = new LinkedHashMap<>();
        txn.put("id", "txn_" + refId);
        txn.put("referenceId", refId);
        txn.put("amount", amount);
        txn.put("toAccountNumber", accountNumber);
        txn.put("state", txnState);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", poId);
        data.put("referenceId", refId);
        data.put("approvalState", approvalState);
        data.put("transactions", List.of(txn));
        return data;
    }

    private PayOsPayoutService.HttpOutcome buildSignedPayoutOutcome(int httpStatus, String poId, String refId, long amount,
                                                                    String approvalState, String txnState, String accountNumber, String key) {
        try {
            Map<String, Object> data = buildPayoutDataMap(poId, refId, amount, approvalState, txnState, accountNumber);
            String sig = PayOsPayoutSignatureUtil.signPayload(data, key);
            String json = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", data));
            return new PayOsPayoutService.HttpOutcome(httpStatus, json, sig, false, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Booking createConfirmedTicketBooking(String email, long priceVnd, boolean checkedIn) {
        Booking b = new Booking();
        b.setBookingCode("BOX-20261122-" + (1000 + new Random().nextInt(8999)));
        b.setCinema(cinema);
        b.setOrderType("TICKET_ONLY");
        b.setGuestName("Audit Guest");
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
        t.setTicketStatus(checkedIn ? "USED" : "VALID");
        t = ticketRepository.save(t);

        snapshotService.allocateOrderSnapshot(b, BigDecimal.valueOf(priceVnd), List.of(t));
        ticketRepository.save(t);
        b = bookingRepository.save(b);
        recordPaidPayment(b, priceVnd);
        return b;
    }

    private Booking createDiscountedBooking(String email, long netCashVnd, long discountVnd) {
        Promotion oldPromo = new Promotion();
        oldPromo.setPromotionName("AudDisc " + TEST_TAG);
        oldPromo.setVoucherCode("AUDD-" + Math.abs((email + TEST_TAG).hashCode() % 100000));
        oldPromo.setDiscountType("FIXED");
        oldPromo.setDiscountValue(BigDecimal.valueOf(discountVnd));
        oldPromo.setStartAt(LocalDateTime.now().minusDays(2));
        oldPromo.setEndAt(LocalDateTime.now().plusDays(10));
        oldPromo.setStatus("ACTIVE");
        oldPromo = promotionRepository.save(oldPromo);
        createdPromoIds.add(oldPromo.getPromotionId());

        long gross = netCashVnd + discountVnd;
        Booking b = new Booking();
        b.setBookingCode("BOX-20261122-" + (1000 + new Random().nextInt(8999)));
        b.setCinema(cinema);
        b.setOrderType("TICKET_ONLY");
        b.setGuestName("Disc Guest");
        b.setGuestEmail(email);
        b.setGuestPhone("0901234567");
        b.setTicketSubtotal(BigDecimal.valueOf(gross));
        b.setSubtotal(BigDecimal.valueOf(gross));
        b.setDiscountAmount(BigDecimal.valueOf(discountVnd));
        b.setTotalAmount(BigDecimal.valueOf(netCashVnd));
        b.setBookingStatus("CONFIRMED");
        b.setPromotion(oldPromo);
        b.setVoucherCodeSnapshot(oldPromo.getVoucherCode());
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());

        BookingSeat bs = bookingSeatRepository.save(new BookingSeat(b, 1L, BigDecimal.valueOf(gross), "A2", "BOOKED"));
        b.getBookingSeats().add(bs);

        Ticket t = new Ticket();
        t.setBooking(b);
        t.setBookingSeatId(bs.getBookingSeatId());
        t.setTicketCode("TCK-" + b.getBookingCode().replace("BOX-", "") + "-A2");
        t.setQrTokenHash(CryptoUtils.sha256(t.getTicketCode()));
        t.setPrice(BigDecimal.valueOf(gross));
        t.setTicketStatus("VALID");
        t = ticketRepository.save(t);

        snapshotService.allocateOrderSnapshot(b, BigDecimal.valueOf(netCashVnd), List.of(t));
        ticketRepository.save(t);
        b = bookingRepository.save(b);
        recordPaidPayment(b, netCashVnd);
        return b;
    }

    private Booking createFoodOnlyOrder(String email) {
        Booking b = new Booking();
        b.setBookingCode("BOX-20261122-" + (1000 + new Random().nextInt(8999)));
        b.setCinema(cinema);
        b.setOrderType("FOOD_ONLY");
        b.setGuestName("Food Guest");
        b.setGuestEmail(email);
        b.setGuestPhone("0901234567");
        b.setFoodSubtotal(BigDecimal.valueOf(50000));
        b.setSubtotal(BigDecimal.valueOf(50000));
        b.setDiscountAmount(BigDecimal.ZERO);
        b.setTotalAmount(BigDecimal.valueOf(50000));
        b.setBookingStatus("CONFIRMED");
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());
        recordPaidPayment(b, 50000L);

        BookingFoodItem bfi = new BookingFoodItem();
        bfi.setBooking(b);
        bfi.setItemType("FOOD");
        bfi.setProductNameSnapshot("Audit Popcorn");
        bfi.setQuantity(1);
        bfi.setUnitPriceSnapshot(BigDecimal.valueOf(50000));
        bfi.setSubtotal(BigDecimal.valueOf(50000));
        bfi.setNetPaidAmount(BigDecimal.valueOf(50000));
        bookingFoodItemRepository.save(bfi);

        FoodFulfillment ff = new FoodFulfillment(b);
        ff.setCinema(cinema);
        ff.setStatus("QUEUED");
        foodFulfillmentRepository.save(ff);
        return b;
    }

    private void recordPaidPayment(Booking b, long amountVnd) {
        Payment p = new Payment();
        p.setBooking(b);
        p.setOrderCode(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 9_000_000_000L + 100_000L);
        p.setAmount(BigDecimal.valueOf(amountVnd));
        p.setIdempotencyKey("IDEMP-PAA-" + UUID.randomUUID());
        p.setPaymentStatus("PAID");
        p.setPaidAt(LocalDateTime.now().minusMinutes(5));
        paymentRepository.save(p);
    }

    private long submitTicketRefund(Booking b, String accountNumber) throws Exception {
        Ticket t = ticketRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        MvcResult res = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", b.getBookingCode(),
                                "purchaseEmail", b.getGuestEmail(),
                                "reasonCategory", "OTHER",
                                "description", "Audit refund",
                                "toBin", "970422",
                                "toAccountNumber", accountNumber,
                                "confirmAccountNumber", accountNumber,
                                "bankAccountName", "Audit Customer",
                                "items", List.of(Map.of("itemKind", "TICKET", "targetId", t.getTicketId(), "quantity", 1))))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private void approveRefund(long refundId) throws Exception {
        long itemId = refundRequestItemRepository.findByRefundRequest_IdOrderByIdAsc(refundId).get(0).getId();
        mockMvc.perform(post("/api/manager/refunds/" + refundId + "/review").with(csrf())
                        .sessionAttr("currentUser", dtoOf(manager1))
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "DECIDE_ITEMS",
                                "itemDecisions", List.of(Map.of("itemId", itemId, "decision", "APPROVED", "approvedQuantity", 1))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("AWAITING_PAYOUT_APPROVAL"));

        mockMvc.perform(post("/api/admin/refunds/" + refundId + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk());
    }

    private void retryPayoutApi(long refundId) throws Exception {
        mockMvc.perform(post("/api/admin/refunds/" + refundId + "/retry-payout").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk());
    }

    private UserSummaryDto dtoOf(User u) {
        return new UserSummaryDto(u.getUserId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole());
    }

    private User ensureUser(String email, String role, Long cinemaId) {
        User u = new User();
        u.setFullName("Aud " + role);
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
