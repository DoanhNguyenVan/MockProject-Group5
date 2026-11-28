package com.boxcinema.refund;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.common.util.PayOsPayoutSignatureUtil;
import com.boxcinema.refund.service.PayOsPayoutService;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive integration tests covering Section 10 Groups A, B, C, D & F:
 * - Nhóm A: Intake screening, 13-point eligibility gate, multi-criteria filtering, Manager escalation & INELIGIBLE/CANCELLED exclusion
 * - Nhóm B: Admin single refund payout detail inspection, single approve, single reject + reason/audit/email
 * - Nhóm C: Admin batch payout (5/5 = 500,000 VND -> 5 independent payouts, partial 3/5, mixed outcomes A=SUCCEEDED/B=PROCESSING/C=FAILED/D=SUCCEEDED/E=UNKNOWN_OUTCOME, skipped invalid item, double-click & 2 concurrent admins)
 * - Nhóm D: PayOS account-balance, remote payout query, unknown outcome reconciliation on same referenceId
 */
@SpringBootTest
@AutoConfigureMockMvc
class RefundPayoutBatchWorkflowIntegrationTest {

    private static final String TEST_TAG = "BATCH_WF_" + System.currentTimeMillis();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
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
    @Autowired private PurchaseSnapshotService snapshotService;
    @Autowired private PayOsPayoutService payOsPayoutService;
    @Autowired private com.boxcinema.refund.service.RefundRateLimitService refundRateLimitService;
    @MockBean private JavaMailSender mailSender;

    @Value("${payos.payout.checksum-key:}")
    private String payoutChecksumKey;

    private Cinema cinema;
    private User manager1;
    private User admin1;
    private User admin2;

    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        refundRateLimitService.resetForTesting();
        payOsPayoutService.setCustomTransportExecutor(null);
        ReflectionTestUtils.setField(payOsPayoutService, "mode", "MOCK");

        cinema = cinemaRepository.findAll().get(0);
        String suffix = TEST_TAG + "_" + UUID.randomUUID().toString().substring(0, 6);
        manager1 = ensureUser("bw_mgr_" + suffix + "@box.test", "MANAGER", cinema.getCinemaId());
        admin1 = ensureUser("bw_adm1_" + suffix + "@box.test", "ADMIN", null);
        admin2 = ensureUser("bw_adm2_" + suffix + "@box.test", "ADMIN", null);
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
                jdbcTemplate.update("DELETE FROM promotions WHERE promotion_id = ?", pid);
            }
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
    void groupA_ScreeningAndFiltering_PreApprovalNeverReachesPayoutQueue_AndFiltersWork() throws Exception {
        // 1. Fresh submission (PENDING_REVIEW) -> NOT in payout-queue, cannot approve payout
        Booking bPending = createConfirmedTicketBooking("ga_pend_" + TEST_TAG + "@box.test", 50000L, true);
        long rrPendingId = submitTicketRefund(bPending, "CINEMA_INCIDENT", "0911111111");
        String codePending = refundRequestRepository.findById(rrPendingId).orElseThrow().getRequestCode();

        // 2. Under verification (START_VERIFICATION -> ESCALATE_TO_ADMIN -> VERIFIED) -> still NOT in payout-queue until business DECIDE_ITEMS!
        Booking bVerified = createConfirmedTicketBooking("ga_ver_" + TEST_TAG + "@box.test", 80000L, true);
        long rrVerifiedId = submitTicketRefund(bVerified, "PRODUCT_INCIDENT", "0922222222");
        String codeVerified = refundRequestRepository.findById(rrVerifiedId).orElseThrow().getRequestCode();
        managerAction(rrVerifiedId, "START_VERIFICATION", "Checking cinema log", null);
        managerAction(rrVerifiedId, "ESCALATE_TO_ADMIN", "Beyond branch threshold", null);
        managerAction(rrVerifiedId, "MARK_VERIFIED", "Verified by manager", null);
        assertEquals("VERIFIED", refundRequestRepository.findById(rrVerifiedId).orElseThrow().getRequestStatus());

        // 3. INELIGIBLE & CANCELLED requests -> NEVER enter payout-queue and cannot approve payout
        Booking bIneligible = createConfirmedTicketBooking("ga_inel_" + TEST_TAG + "@box.test", 60000L, true);
        long rrInelId = submitTicketRefund(bIneligible, "OTHER", "0933333333");
        managerAction(rrInelId, "MARK_INELIGIBLE", "Policy excluded", null);
        assertEquals("INELIGIBLE", refundRequestRepository.findById(rrInelId).orElseThrow().getRequestStatus());

        Booking bCancelled = createConfirmedTicketBooking("ga_canc_" + TEST_TAG + "@box.test", 70000L, true);
        long rrCancId = submitTicketRefund(bCancelled, "OTHER", "0944444444");
        managerAction(rrCancId, "CANCEL", "Customer withdrew request", null);
        assertEquals("CANCELLED", refundRequestRepository.findById(rrCancId).orElseThrow().getRequestStatus());

        // 4. Booking WITHOUT PAID Payment -> Manager DECIDE_ITEMS blocked cleanly (409 NO_PAID_PAYMENT)
        Booking bNoPay = createConfirmedTicketBooking("ga_nopay_" + TEST_TAG + "@box.test", 90000L, false);
        long rrNoPayId = submitTicketRefund(bNoPay, "CINEMA_INCIDENT", "0955555555");
        long rrNoPayItemId = refundRequestItemRepository.findByRefundRequest_IdOrderByIdAsc(rrNoPayId).get(0).getId();
        mockMvc.perform(post("/api/manager/refunds/" + rrNoPayId + "/review").with(csrf())
                        .sessionAttr("currentUser", dtoOf(manager1))
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "DECIDE_ITEMS",
                                "itemDecisions", List.of(Map.of("itemId", rrNoPayItemId, "decision", "APPROVED", "approvedQuantity", 1))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NO_PAID_PAYMENT"));

        // 5. Business-approved valid request -> enters Admin payout queue!
        Booking bReady = createConfirmedTicketBooking("ga_ready_" + TEST_TAG + "@box.test", 120000L, true);
        long rrReadyId = submitTicketRefund(bReady, "STAFF_ERROR", "0966666666");
        String codeReady = refundRequestRepository.findById(rrReadyId).orElseThrow().getRequestCode();
        approveBusinessStage(rrReadyId);

        // Queue shows ONLY codeReady; excludes Pending, Verified, Ineligible, Cancelled
        mockMvc.perform(get("/api/admin/refunds/payout-queue")
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].requestCode", hasItem(codeReady)))
                .andExpect(jsonPath("$.content[*].requestCode", not(hasItem(codePending))))
                .andExpect(jsonPath("$.content[*].requestCode", not(hasItem(codeVerified))));

        // Multi-criteria filtering (verifier, minAmount/maxAmount, reasonCategory, date range, search)
        String today = LocalDate.now().toString();
        mockMvc.perform(get("/api/admin/refunds/payout-queue")
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .param("reasonCategory", "STAFF_ERROR")
                        .param("verifier", String.valueOf(manager1.getUserId()))
                        .param("minAmount", "100000")
                        .param("maxAmount", "150000")
                        .param("fromDate", today)
                        .param("toDate", today)
                        .param("search", codeReady))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].requestCode").value(codeReady));

        // Direct Admin payout approve on PENDING_REVIEW / CANCELLED / INELIGIBLE fails with 409 PAYOUT_INELIGIBLE
        for (long blockedId : List.of(rrPendingId, rrVerifiedId, rrInelId, rrCancId)) {
            mockMvc.perform(post("/api/admin/refunds/" + blockedId + "/payout/approve").with(csrf())
                            .sessionAttr("currentUser", dtoOf(admin1))
                            .with(user(admin1.getEmail()).roles("ADMIN")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("PAYOUT_INELIGIBLE"));
        }
    }

    @Test
    void groupB_SingleAdminApprovalAndRejection_DetailInspectionHistoryAndEmail() throws Exception {
        Booking b1 = createConfirmedTicketBooking("gb_ok_" + TEST_TAG + "@box.test", 75000L, true);
        long rr1 = submitTicketRefund(b1, "CINEMA_INCIDENT", "0912000111");
        approveBusinessStage(rr1);

        // Full detail contains originalPayment, verifier, masked bank, eligible=true
        mockMvc.perform(get("/api/admin/refunds/" + rr1)
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalPayment.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.originalPayment.amount").value(75000))
                .andExpect(jsonPath("$.reviewedById").value(manager1.getUserId()))
                .andExpect(jsonPath("$.payoutEligible").value(true));

        // Admin rejects single without reason -> 400
        mockMvc.perform(post("/api/admin/refunds/" + rr1 + "/payout/reject").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest());

        // Single approve succeeds -> COMPLETED, payoutDecidedBy=admin1, history & 1 payout tx
        mockMvc.perform(post("/api/admin/refunds/" + rr1 + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Single payout verified by Finance Admin\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.payoutStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.payoutDecidedById").value(admin1.getUserId()))
                .andExpect(jsonPath("$.payoutTransactions.length()").value(1));

        // Reject second request with reason -> PAYOUT_REJECTED, email queued, 0 payouts, cannot approve afterwards
        Booking b2 = createConfirmedTicketBooking("gb_rej_" + TEST_TAG + "@box.test", 85000L, true);
        long rr2 = submitTicketRefund(b2, "OTHER", "0912000222");
        approveBusinessStage(rr2);

        mockMvc.perform(post("/api/admin/refunds/" + rr2 + "/payout/reject").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Bank account holder name mismatch\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("PAYOUT_REJECTED"))
                .andExpect(jsonPath("$.payoutStatus").value("REJECTED"))
                .andExpect(jsonPath("$.payoutDecisionNote").value("Bank account holder name mismatch"));

        assertEquals(0, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rr2).size());
        assertTrue(emailOutboxRepository.existsByDedupKey("REFUND_PAYOUT_REJECTED:" + rr2));

        mockMvc.perform(post("/api/admin/refunds/" + rr2 + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isConflict());
    }

    @Test
    void groupC_BatchPayout_FullFive500k_PartialSelection_CancelledItem_MixedPayOsOutcomes_And_ConcurrentAdmins() throws Exception {
        // 1. Create 5 eligible requests matching 50k, 80k, 100k, 120k, 150k (Total 500,000 VND)
        long[] amounts = {50000L, 80000L, 100000L, 120000L, 150000L};
        List<Long> fiveIds = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            Booking b = createConfirmedTicketBooking("gc5_" + i + "_" + TEST_TAG + "@box.test", amounts[i], true);
            long rrId = submitTicketRefund(b, "CINEMA_INCIDENT", "098000000" + i);
            approveBusinessStage(rrId);
            fiveIds.add(rrId);
        }

        // Preview all 5 -> eligibleCount=5, totalAmountVnd=500000
        mockMvc.perform(post("/api/admin/refunds/payout/batch-preview").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refundRequestIds", fiveIds))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eligibleCount").value(5))
                .andExpect(jsonPath("$.totalAmountVnd").value(500000));

        // Partial batch: execute ONLY 3/5 (indices 0, 1, 2 = 230,000 VND)
        List<Long> firstThree = List.of(fiveIds.get(0), fiveIds.get(1), fiveIds.get(2));
        MvcResult partialRes = mockMvc.perform(post("/api/admin/refunds/payout/batch-approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refundRequestIds", firstThree,
                                "expectedTotalVnd", 230000L,
                                "strictTotalMatch", true,
                                "note", "Partial 3/5 test"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("ALL_SUCCEEDED"))
                .andExpect(jsonPath("$.succeededCount").value(3))
                .andExpect(jsonPath("$.succeededTotalAmount").value(230000))
                .andExpect(jsonPath("$.results.length()").value(3))
                .andReturn();

        String batch1 = objectMapper.readTree(partialRes.getResponse().getContentAsString()).get("batchCode").asText();
        List<PayOsPayoutTransaction> batch1Txs = payoutRepository.findByBatchCodeOrderByIdAsc(batch1);
        assertEquals(3, batch1Txs.size());
        assertEquals(3, batch1Txs.stream().map(PayOsPayoutTransaction::getIdempotencyKey).distinct().count());
        assertEquals(3, batch1Txs.stream().map(PayOsPayoutTransaction::getReferenceId).distinct().count());
        assertEquals("AWAITING_PAYOUT_APPROVAL", refundRequestRepository.findById(fiveIds.get(3)).orElseThrow().getRequestStatus());
        assertEquals("AWAITING_PAYOUT_APPROVAL", refundRequestRepository.findById(fiveIds.get(4)).orElseThrow().getRequestStatus());

        // 2. Full 5-item batch with 500,000 VND total & mixed independent PayOS outcomes (A=SUCCEEDED, B=PROCESSING, C=FAILED, D=SUCCEEDED, E=UNKNOWN_OUTCOME timeout)
        List<Long> mixIds = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            Booking b = createConfirmedTicketBooking("gcmix_" + i + "_" + TEST_TAG + "@box.test", amounts[i], true);
            long rrId = submitTicketRefund(b, "PRODUCT_INCIDENT", "098111111" + i);
            approveBusinessStage(rrId);
            mixIds.add(rrId);
        }
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                String ref = String.valueOf(bodyMap.get("referenceId"));
                long amt = ((Number) bodyMap.get("amount")).longValue();
                if (ref.startsWith("RFD-" + mixIds.get(0) + "-")) {
                    return signedOutcome(200, "po_mix_0", ref, amt, "COMPLETED", "SUCCEEDED");
                }
                if (ref.startsWith("RFD-" + mixIds.get(1) + "-")) {
                    return signedOutcome(200, "po_mix_1", ref, amt, "PENDING", "PROCESSING");
                }
                if (ref.startsWith("RFD-" + mixIds.get(2) + "-")) {
                    return new PayOsPayoutService.HttpOutcome(400, "{\"code\":\"622\",\"desc\":\"Invalid bank\"}", null, false, "400");
                }
                if (ref.startsWith("RFD-" + mixIds.get(3) + "-")) {
                    return signedOutcome(200, "po_mix_3", ref, amt, "COMPLETED", "SUCCEEDED");
                }
                return new PayOsPayoutService.HttpOutcome(0, null, null, true, "Read timed out after dispatch");
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "", null, false, "500");
            }
        });

        MvcResult mixRes = mockMvc.perform(post("/api/admin/refunds/payout/batch-approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refundRequestIds", mixIds,
                                "expectedTotalVnd", 500000L,
                                "strictTotalMatch", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("PARTIAL_SUCCESS"))
                .andExpect(jsonPath("$.eligibleCount").value(5))
                .andExpect(jsonPath("$.succeededCount").value(2))
                .andExpect(jsonPath("$.processingCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(1))
                .andExpect(jsonPath("$.unknownOutcomeCount").value(1))
                .andExpect(jsonPath("$.succeededTotalAmount").value(170000)) // 50k + 120k
                .andReturn();

        JsonNode mixJson = objectMapper.readTree(mixRes.getResponse().getContentAsString());
        assertEquals("SUCCEEDED", mixJson.get("results").get(0).get("resultStatus").asText());
        assertEquals("PROCESSING", mixJson.get("results").get(1).get("resultStatus").asText());
        assertEquals("FAILED", mixJson.get("results").get(2).get("resultStatus").asText());
        assertEquals("SUCCEEDED", mixJson.get("results").get(3).get("resultStatus").asText());
        assertEquals("UNKNOWN_OUTCOME", mixJson.get("results").get(4).get("resultStatus").asText());
        assertTrue(mixJson.get("results").get(4).get("unknownOutcome").asBoolean());

        // 3. Batch where 1 item was cancelled before execution + double-click / 2 concurrent Admins submitting same batch
        payOsPayoutService.setCustomTransportExecutor(null);
        List<Long> concIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Booking b = createConfirmedTicketBooking("gconc_" + i + "_" + TEST_TAG + "@box.test", 50000L, true);
            long rrId = submitTicketRefund(b, "CINEMA_INCIDENT", "098222222" + i);
            approveBusinessStage(rrId);
            concIds.add(rrId);
        }
        // Cancel index 3 right before batch runs (simulates stale selection in UI without strictTotalMatch)
        managerAction(concIds.get(3), "CANCEL", "Customer cancelled before batch", null);

        String concPayload = objectMapper.writeValueAsString(Map.of(
                "refundRequestIds", concIds,
                "strictTotalMatch", false,
                "note", "Concurrent double-admin check"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            User[] admins = {admin1, admin2};
            for (User adm : admins) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mockMvc.perform(post("/api/admin/refunds/payout/batch-approve").with(csrf())
                                    .sessionAttr("currentUser", dtoOf(adm))
                                    .with(user(adm.getEmail()).roles("ADMIN"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(concPayload))
                            .andExpect(status().isOk())
                            .andReturn();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int totalSucceededAcrossAdmins = 0;
            for (Future<MvcResult> f : futures) {
                JsonNode j = objectMapper.readTree(f.get(20, TimeUnit.SECONDS).getResponse().getContentAsString());
                totalSucceededAcrossAdmins += j.get("succeededCount").asInt();
            }
            assertEquals(3, totalSucceededAcrossAdmins, "Across two concurrent Admins, exact 3 valid items must execute once total");
        } finally {
            pool.shutdownNow();
        }
        for (int i = 0; i < 3; i++) {
            assertEquals(1, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(concIds.get(i)).size());
        }
        assertEquals(0, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(concIds.get(3)).size());
    }

    @Test
    void groupD_PayOsBalanceQuery_PayoutHistory_And_TimeoutReconcileWithoutDuplicateAttempt() throws Exception {
        // 1. Account balance MOCK & mocked LIVE
        mockMvc.perform(get("/api/admin/payouts/account-balance")
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MOCK"))
                .andExpect(jsonPath("$.currency").value("VND"));

        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(0, null, null, true, "Socket timeout after dispatch");
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                String ref = url.contains("referenceId=") ? url.split("referenceId=")[1].split("&")[0] : "";
                return signedOutcome(200, "po_rec_gd", ref, 90000L, "COMPLETED", "SUCCEEDED");
            }
            @Override
            public PayOsPayoutService.HttpOutcome getBalance(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(200,
                        "{\"code\":\"00\",\"desc\":\"success\",\"data\":{\"accountNumber\":\"9988776655\",\"accountName\":\"BOX CINEMA\",\"currency\":\"VND\",\"balance\":\"5000000\"}}",
                        null, false, null);
            }
        });

        mockMvc.perform(get("/api/admin/payouts/account-balance")
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveVerified").value(true))
                .andExpect(jsonPath("$.accountNumberMasked").value("******6655"))
                .andExpect(jsonPath("$.balance").value("5000000"));

        // 2. Timeout leaves UNKNOWN_OUTCOME -> reconcile via /api/admin/payouts/{txId}/reconcile -> SUCCEEDED without A2!
        Booking b = createConfirmedTicketBooking("gd_rec_" + TEST_TAG + "@box.test", 90000L, true);
        long rrId = submitTicketRefund(b, "CINEMA_INCIDENT", "0983333333");
        approveBusinessStage(rrId);

        mockMvc.perform(post("/api/admin/refunds/" + rrId + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.latestPayout.unknownOutcome").value(true));

        PayOsPayoutTransaction tx = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrId).get(0);

        mockMvc.perform(get("/api/admin/payouts")
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .param("search", tx.getReferenceId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].unknownOutcome").value(true));

        mockMvc.perform(post("/api/admin/payouts/" + tx.getId() + "/reconcile").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.unknownOutcome").value(false))
                .andExpect(jsonPath("$.referenceId").value(tx.getReferenceId()));

        assertEquals(1, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrId).size());
        assertEquals("COMPLETED", refundRequestRepository.findById(rrId).orElseThrow().getRequestStatus());
    }

    @Test
    void groupC_BatchGuards_Empty_Oversized_StrictTotalMismatch_And_RetryFailedInBatch() throws Exception {
        // 1. Empty IDs -> 400 BATCH_IDS_REQUIRED
        mockMvc.perform(post("/api/admin/refunds/payout/batch-approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refundRequestIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BATCH_IDS_REQUIRED"));

        // 2. >100 IDs -> 400 BATCH_SIZE_EXCEEDED
        List<Long> overIds = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            overIds.add(i);
        }
        mockMvc.perform(post("/api/admin/refunds/payout/batch-approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refundRequestIds", overIds))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("BATCH_SIZE_EXCEEDED"));

        // 3. strictTotalMatch mismatch -> 409 BATCH_TOTAL_MISMATCH, 0 payouts created
        Booking b = createConfirmedTicketBooking("gguard_" + TEST_TAG + "@box.test", 95000L, true);
        long rrId = submitTicketRefund(b, "CINEMA_INCIDENT", "0984444444");
        approveBusinessStage(rrId);

        mockMvc.perform(post("/api/admin/refunds/payout/batch-approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refundRequestIds", List.of(rrId),
                                "expectedTotalVnd", 120000L,
                                "strictTotalMatch", true))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BATCH_TOTAL_MISMATCH"));
        assertEquals(0, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrId).size());

        // 4. First attempt fails (HTTP 400 -> PAYOUT_FAILED); batch-approve skips PAYOUT_FAILED as SKIPPED_INELIGIBLE, then retry-payout creates A2 -> SUCCEEDED & COMPLETED
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(400, "{\"code\":\"622\",\"desc\":\"Temp bank reject\"}", null, false, "400");
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(404, "{\"code\":\"608\"}", null, false, "404");
            }
        });
        mockMvc.perform(post("/api/admin/refunds/payout/batch-approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refundRequestIds", List.of(rrId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("ALL_FAILED"))
                .andExpect(jsonPath("$.failedCount").value(1));
        assertEquals("PAYOUT_FAILED", refundRequestRepository.findById(rrId).orElseThrow().getRequestStatus());

        payOsPayoutService.setCustomTransportExecutor(null);
        mockMvc.perform(post("/api/admin/refunds/payout/batch-approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refundRequestIds", List.of(rrId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("NO_ELIGIBLE_ITEMS"))
                .andExpect(jsonPath("$.skippedCount").value(1))
                .andExpect(jsonPath("$.results[0].resultStatus").value("SKIPPED_INELIGIBLE"));

        mockMvc.perform(post("/api/admin/refunds/" + rrId + "/retry-payout").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"));
        List<PayOsPayoutTransaction> attempts = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrId);
        assertEquals(2, attempts.size());
        assertEquals("RFD-" + rrId + "-A2", attempts.get(0).getReferenceId());
        assertEquals("SUCCEEDED", attempts.get(0).getStatus());
        assertEquals("COMPLETED", refundRequestRepository.findById(rrId).orElseThrow().getRequestStatus());
    }

    private PayOsPayoutService.HttpOutcome signedOutcome(int httpStatus, String poId, String refId, long amount,
                                                         String approvalState, String txnState) {
        try {
            Map<String, Object> txn = new LinkedHashMap<>();
            txn.put("id", "txn_" + refId);
            txn.put("referenceId", refId);
            txn.put("amount", amount);
            txn.put("state", txnState);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", poId);
            data.put("referenceId", refId);
            data.put("approvalState", approvalState);
            data.put("transactions", List.of(txn));

            String key = (payoutChecksumKey != null && !payoutChecksumKey.isBlank())
                    ? payoutChecksumKey.trim()
                    : "MOCK_PAYOUT_CHECKSUM_KEY";
            String sig = PayOsPayoutSignatureUtil.signPayload(data, key);
            String json = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", data));
            return new PayOsPayoutService.HttpOutcome(httpStatus, json, sig, false, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void managerAction(long refundId, String action, String note, List<Map<String, Object>> decisions) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        if (note != null) body.put("note", note);
        if (decisions != null) body.put("itemDecisions", decisions);
        mockMvc.perform(post("/api/manager/refunds/" + refundId + "/review").with(csrf())
                        .sessionAttr("currentUser", dtoOf(manager1))
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    private void approveBusinessStage(long refundId) throws Exception {
        long itemId = refundRequestItemRepository.findByRefundRequest_IdOrderByIdAsc(refundId).get(0).getId();
        managerAction(refundId, "DECIDE_ITEMS", "Business check passed",
                List.of(Map.of("itemId", itemId, "decision", "APPROVED", "approvedQuantity", 1)));
    }

    private long submitTicketRefund(Booking b, String reasonCategory, String accountNumber) throws Exception {
        Ticket t = ticketRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        MvcResult res = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", b.getBookingCode(),
                                "purchaseEmail", b.getGuestEmail(),
                                "customerName", b.getGuestName(),
                                "reasonCategory", reasonCategory,
                                "description", "Batch workflow test refund",
                                "toBin", "970422",
                                "toAccountNumber", accountNumber,
                                "confirmAccountNumber", accountNumber,
                                "bankAccountName", b.getGuestName(),
                                "items", List.of(Map.of("itemKind", "TICKET", "targetId", t.getTicketId(), "quantity", 1))))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private Booking createConfirmedTicketBooking(String email, long priceVnd, boolean withPaidPayment) {
        Booking b = new Booking();
        b.setBookingCode("BOX-20261122-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        b.setCinema(cinema);
        b.setOrderType("TICKET_ONLY");
        b.setGuestName("Batch Test Customer");
        b.setGuestEmail(email);
        b.setGuestPhone("0901234567");
        b.setTicketSubtotal(BigDecimal.valueOf(priceVnd));
        b.setSubtotal(BigDecimal.valueOf(priceVnd));
        b.setDiscountAmount(BigDecimal.ZERO);
        b.setTotalAmount(BigDecimal.valueOf(priceVnd));
        b.setBookingStatus("CONFIRMED");
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());

        if (withPaidPayment) {
            Payment p = new Payment();
            p.setBooking(b);
            p.setOrderCode(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 9_000_000_000L + 100_000L);
            p.setAmount(BigDecimal.valueOf(priceVnd));
            p.setIdempotencyKey("IDEMP-BW-" + UUID.randomUUID());
            p.setPaymentStatus("PAID");
            p.setPaidAt(LocalDateTime.now().minusMinutes(10));
            paymentRepository.save(p);
        }

        BookingSeat bs = bookingSeatRepository.save(new BookingSeat(b, 1L, BigDecimal.valueOf(priceVnd), "B1", "BOOKED"));
        b.getBookingSeats().add(bs);

        Ticket t = new Ticket();
        t.setBooking(b);
        t.setBookingSeatId(bs.getBookingSeatId());
        t.setTicketCode("TCK-" + b.getBookingCode().replace("BOX-", "") + "-B1");
        t.setQrTokenHash(CryptoUtils.sha256(t.getTicketCode()));
        t.setPrice(BigDecimal.valueOf(priceVnd));
        t.setTicketStatus("VALID");
        t = ticketRepository.save(t);

        snapshotService.allocateOrderSnapshot(b, BigDecimal.valueOf(priceVnd), List.of(t));
        ticketRepository.save(t);
        return bookingRepository.save(b);
    }

    private UserSummaryDto dtoOf(User u) {
        return new UserSummaryDto(u.getUserId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole());
    }

    private User ensureUser(String email, String role, Long cinemaId) {
        User u = new User();
        u.setFullName("BW " + role + " " + email.substring(0, 6));
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
