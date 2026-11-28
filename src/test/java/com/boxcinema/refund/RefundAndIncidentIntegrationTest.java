package com.boxcinema.refund;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BankDataEncryptionService;
import com.boxcinema.common.service.BookingQrService;
import com.boxcinema.common.service.BookingService;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.common.util.PayOsPayoutSignatureUtil;
import com.boxcinema.customer.dto.request.BookingFoodItemRequestDto;
import com.boxcinema.customer.dto.request.UpdateFoodItemsRequestDto;
import com.boxcinema.customer.service.PromotionService;
import com.boxcinema.customer.service.impl.EmailOutboxWorkerService;
import com.boxcinema.refund.dto.IncidentCreateRequestDto;
import com.boxcinema.refund.dto.RefundReviewRequestDto;
import com.boxcinema.refund.dto.StaffFoodActionRequestDto;
import com.boxcinema.refund.service.IncidentService;
import com.boxcinema.refund.service.PayOsPayoutService;
import com.boxcinema.refund.service.RefundEmailService;
import com.boxcinema.refund.service.RefundService;
import com.boxcinema.refund.service.VoucherRecoveryService;
import com.boxcinema.staff.service.FoodFulfillmentService;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RefundAndIncidentIntegrationTest {

    private static final String TEST_TAG = "RFD_IT_" + System.currentTimeMillis();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private CinemaRepository cinemaRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private BookingSeatRepository bookingSeatRepository;
    @Autowired private BookingFoodItemRepository bookingFoodItemRepository;
    @Autowired private BookingFoodItemComponentRepository componentRepository;
    @Autowired private FoodFulfillmentRepository foodFulfillmentRepository;
    @Autowired private PromotionRepository promotionRepository;
    @Autowired private EmailOutboxRepository emailOutboxRepository;
    @Autowired private IncidentService incidentService;
    @Autowired private RefundService refundService;
    @Autowired private FoodFulfillmentService foodFulfillmentService;
    @Autowired private PayOsPayoutService payOsPayoutService;
    @Autowired private PurchaseSnapshotService snapshotService;
    @Autowired private BookingQrRepository bookingQrRepository;
    @Autowired private com.boxcinema.staff.service.StaffCheckinService staffCheckinService;
    @Autowired private StaffErrorLogRepository staffErrorLogRepository;
    @Autowired private VoucherRecoveryRepository voucherRecoveryRepository;
    @Autowired private FoodItemRepository foodItemRepository;
    @Autowired private RefundEmailService refundEmailService;
    @Autowired private RefundRequestRepository refundRequestRepository;
    @Autowired private EmailOutboxWorkerService emailOutboxWorkerService;
    @Autowired private BookingService bookingService;
    @Autowired private PromotionService promotionService;
    @Autowired private VoucherRecoveryService voucherRecoveryService;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private com.boxcinema.refund.service.RefundRateLimitService refundRateLimitService;
    @MockBean private JavaMailSender mailSender;

    @Value("${payos.payout.checksum-key:}")
    private String payoutChecksumKey;

    private User staff1;
    private User manager1;
    private User manager2;
    private User admin1;
    private Cinema cinema1;
    private Cinema cinema2;

    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdPromoIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdFoodItemIds = new ArrayList<>();
    private final List<Long> createdCategoryIds = new ArrayList<>();
    private final List<Long> createdCinemaIds = new ArrayList<>();

    @BeforeEach
    void setupActors() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        refundRateLimitService.resetForTesting();
        List<Cinema> cinemas = cinemaRepository.findAll();
        cinema1 = cinemas.get(0);
        if (cinemas.size() > 1) {
            cinema2 = cinemas.get(1);
        } else {
            Cinema extra = new Cinema("Branch 2 " + TEST_TAG, "456 Side St", "Hanoi", "0909998888", "branch2_" + TEST_TAG + "@box.test");
            cinema2 = cinemaRepository.save(extra);
            createdCinemaIds.add(cinema2.getCinemaId());
        }

        staff1 = ensureUser("staff_it_" + TEST_TAG + "@box.test", "STAFF", cinema1.getCinemaId());
        manager1 = ensureUser("mgr1_it_" + TEST_TAG + "@box.test", "MANAGER", cinema1.getCinemaId());
        manager2 = ensureUser("mgr2_it_" + TEST_TAG + "@box.test", "MANAGER", cinema2.getCinemaId());
        admin1 = ensureUser("adm_it_" + TEST_TAG + "@box.test", "ADMIN", null);
    }

    @AfterEach
    void cleanup() {
        payOsPayoutService.setCustomTransportExecutor(null);
        voucherRecoveryService.setCustomFailureInjectorForTesting(null);
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
            jdbcTemplate.update("DELETE FROM staff_error_logs WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM incident_reports WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM food_fulfillment WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM payments WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM tickets WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM bookings WHERE booking_id = ?", bid);
        }
        for (Long pid : createdPromoIds) {
            jdbcTemplate.update("DELETE FROM promotions WHERE promotion_id = ?", pid);
        }
        for (Long uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE actor_user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", uid);
        }
        for (Long fid : createdFoodItemIds) {
            jdbcTemplate.update("DELETE FROM food_inventory WHERE food_item_id = ?", fid);
            jdbcTemplate.update("DELETE FROM food_item WHERE id = ?", fid);
        }
        for (Long catId : createdCategoryIds) {
            jdbcTemplate.update("DELETE FROM food_category WHERE id = ?", catId);
        }
        for (Long cid : createdCinemaIds) {
            jdbcTemplate.update("DELETE FROM cinemas WHERE cinema_id = ?", cid);
        }
    }

    @Test
    void routePermissionsAndCsrfProtection_EnforcedAcrossRoles() throws Exception {
        // /staff and /manager redirects/pages exist (no 404 bug!)
        mockMvc.perform(get("/staff").with(user(staff1.getEmail()).roles("STAFF")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/staff/checkin"));

        mockMvc.perform(get("/manager").with(user(manager1.getEmail()).roles("MANAGER")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/refunds").with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/incidents").with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk());

        // STAFF cannot open manager/admin pages or approve refunds
        mockMvc.perform(get("/manager").with(user(staff1.getEmail()).roles("STAFF")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/manager/refunds/999999/review").with(csrf()).with(user(staff1.getEmail()).roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        // Public refund submit requires CSRF
        mockMvc.perform(post("/api/refunds/public")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void endToEnd_PublicRefundCheckedInTicketWithIncident_BranchIsolation_PayoutAndVoucherRecovery() throws Exception {
        Booking booking = createPaidTicketAndFoodBooking(cinema1, "guest_" + TEST_TAG + "@example.com", true);
        Ticket ticket = ticketRepository.findByBooking_BookingId(booking.getBookingId()).get(0);
        BookingFoodItem popcorn = bookingFoodItemRepository.findByBooking_BookingId(booking.getBookingId()).get(0);

        // 1. Public lookup wrong email -> generic error
        mockMvc.perform(post("/api/refunds/public/lookup-order").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderCode\":\"" + booking.getBookingCode() + "\",\"purchaseEmail\":\"wrong@example.com\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Order not found or email mismatch."));

        // 2. Public refund submit on /api/refunds/public -> masked bank account, RECEIVED email queued
        String submitJson = objectMapper.writeValueAsString(Map.of(
                "orderCode", ticket.getTicketCode(),
                "purchaseEmail", booking.getGuestEmail(),
                "reasonCategory", "CINEMA_INCIDENT",
                "description", "Projector died halfway through the movie",
                "toBin", "970422",
                "toAccountNumber", "0987654321",
                "confirmAccountNumber", "0987654321",
                "bankAccountName", "Nguyen Van Test",
                "items", List.of(
                        Map.of("itemKind", "TICKET", "targetId", ticket.getTicketId(), "quantity", 1),
                        Map.of("itemKind", "FOOD_ITEM", "targetId", popcorn.getId(), "quantity", 1)
                )
        ));

        MvcResult submitRes = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(submitJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestStatus").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.toAccountNumberMasked").value("******4321"))
                .andExpect(content().string(not(containsString("0987654321"))))
                .andReturn();

        JsonNode createdNode = objectMapper.readTree(submitRes.getResponse().getContentAsString());
        Long refundId = createdNode.get("id").asLong();
        String requestCode = createdNode.get("requestCode").asText();

        // Duplicate request for same ticket -> 409 Conflict
        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(submitJson))
                .andExpect(status().isConflict());

        // Verify RECEIVED email queued stating NOT yet approved or refunded
        assertTrue(emailOutboxRepository.existsByDedupKey("REFUND_RECEIVED:" + refundId));

        // 3. Branch isolation: manager2 from a different cinema unconditionally gets 403
        assertNotEquals(cinema1.getCinemaId(), cinema2.getCinemaId());
        com.boxcinema.common.dto.UserSummaryDto m2Dto = new com.boxcinema.common.dto.UserSummaryDto(
                manager2.getUserId(), manager2.getFullName(), manager2.getEmail(), manager2.getPhone(), "MANAGER");
        mockMvc.perform(get("/api/manager/refunds/" + refundId)
                        .sessionAttr("currentUser", m2Dto)
                        .with(user(manager2.getEmail()).roles("MANAGER")))
                .andExpect(status().isForbidden());

        // 4. Manager cannot approve checked-in ticket before verified CINEMA_INCIDENT
        Long ticketItemId = createdNode.get("items").get(0).get("id").asLong();
        Long foodItemId = createdNode.get("items").get(1).get("id").asLong();
        com.boxcinema.common.dto.UserSummaryDto m1Dto = new com.boxcinema.common.dto.UserSummaryDto(
                manager1.getUserId(), manager1.getFullName(), manager1.getEmail(), manager1.getPhone(), "MANAGER");

        String approveBody = objectMapper.writeValueAsString(Map.of(
                "action", "DECIDE_ITEMS",
                "note", "Verified projector failure",
                "itemDecisions", List.of(
                        Map.of("itemId", ticketItemId, "decision", "APPROVED", "approvedQuantity", 1),
                        Map.of("itemId", foodItemId, "decision", "APPROVED", "approvedQuantity", 1)
                )
        ));
        mockMvc.perform(post("/api/manager/refunds/" + refundId + "/review").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("CHECKED_IN_TICKET_REQUIRES_VERIFIED_INCIDENT"));

        // 5. Staff submits CINEMA_INCIDENT -> Manager rejects -> Staff appeals -> Admin overturns to VERIFIED (preserving Manager's conclusion!)
        com.boxcinema.common.dto.UserSummaryDto s1Dto = new com.boxcinema.common.dto.UserSummaryDto(
                staff1.getUserId(), staff1.getFullName(), staff1.getEmail(), staff1.getPhone(), "STAFF");
        com.boxcinema.common.dto.UserSummaryDto a1Dto = new com.boxcinema.common.dto.UserSummaryDto(
                admin1.getUserId(), admin1.getFullName(), admin1.getEmail(), admin1.getPhone(), "ADMIN");

        IncidentCreateRequestDto incReq = new IncidentCreateRequestDto();
        incReq.setCinemaId(cinema1.getCinemaId());
        incReq.setBookingCode(booking.getBookingCode());
        incReq.setIncidentGroup("CINEMA_INCIDENT");
        incReq.setIncidentType("PROJECTOR_FAILURE");
        incReq.setTitle("Hall 1 Projector Fault");
        incReq.setDescription("Projector lamp failed at 45m");
        MvcResult incCreateRes = mockMvc.perform(post("/api/staff/incidents").with(csrf())
                        .sessionAttr("currentUser", s1Dto)
                        .with(user(staff1.getEmail()).roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(incReq)))
                .andExpect(status().isCreated())
                .andReturn();
        Long incId = objectMapper.readTree(incCreateRes.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/manager/incidents/" + incId + "/verify").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECTED\",\"note\":\"Need lamp log\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/staff/incidents/" + incId + "/appeal").with(csrf())
                        .sessionAttr("currentUser", s1Dto)
                        .with(user(staff1.getEmail()).roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appealReason\":\"Lamp log attached\"}"))
                .andExpect(status().isOk());

        MvcResult resolvedRes = mockMvc.perform(post("/api/admin/incidents/" + incId + "/resolve-appeal").with(csrf())
                        .sessionAttr("currentUser", a1Dto)
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"OVERRIDDEN_VERIFIED\",\"note\":\"Confirmed projector outage\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode resolvedNode = objectMapper.readTree(resolvedRes.getResponse().getContentAsString());
        assertEquals("REJECTED", resolvedNode.get("managerDecision").asText());
        assertEquals("Need lamp log", resolvedNode.get("managerNote").asText());
        assertEquals("OVERRIDDEN_VERIFIED", resolvedNode.get("adminDecision").asText());
        assertEquals("VERIFIED", resolvedNode.get("status").asText());

        // 6. Stage 1 Manager approves -> queued in AWAITING_PAYOUT_APPROVAL (no payout yet) + independent recovery voucher created;
        //    then Stage 2 Admin dispatches PayOS MOCK payout -> COMPLETED + SUCCEEDED
        mockMvc.perform(post("/api/manager/refunds/" + refundId + "/review").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(approveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("AWAITING_PAYOUT_APPROVAL"))
                .andExpect(jsonPath("$.payoutStatus").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.voucherStatus").value("GENERATED"));

        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payos_payout_transactions WHERE refund_request_id = ?", Integer.class, refundId));
        mockMvc.perform(post("/api/manager/refunds/" + refundId + "/retry-payout").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER")))
                .andExpect(status().isForbidden());

        MvcResult approvedRes = mockMvc.perform(post("/api/admin/refunds/" + refundId + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", a1Dto)
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Admin verified bank & approved payout\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.payoutStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.voucherStatus").value("GENERATED"))
                .andReturn();

        JsonNode approvedNode = objectMapper.readTree(approvedRes.getResponse().getContentAsString());
        assertEquals(135000, approvedNode.get("approvedCashTotal").asInt());
        assertEquals(15000, approvedNode.get("approvedVoucherTotal").asInt());
        String newVoucherCode = approvedNode.get("recoveryVoucher").get("newVoucherCode").asText();
        assertTrue(newVoucherCode.startsWith("RCV-"));

        Promotion recoveryPromo = promotionRepository.findByVoucherCodeIgnoreCase(newVoucherCode).orElseThrow();
        createdPromoIds.add(recoveryPromo.getPromotionId());
        assertFalse(recoveryPromo.isPublic());
        assertEquals(1, recoveryPromo.getUsageLimit());

        // All 3 approval-phase emails queued
        assertTrue(emailOutboxRepository.existsByDedupKey("REFUND_APPROVED:" + refundId));
        assertTrue(emailOutboxRepository.existsByDedupKey("REFUND_PAYOUT_SUCCEEDED:" + refundId));
        assertTrue(emailOutboxRepository.existsByDedupKey("REFUND_VOUCHER_ISSUED:" + refundId));

        // 7. Public status lookup shows masked account & new voucher
        mockMvc.perform(get("/api/refunds/public/lookup")
                        .param("refundCode", requestCode)
                        .param("email", booking.getGuestEmail()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryVoucher.newVoucherCode").value(newVoucherCode))
                .andExpect(content().string(not(containsString("0987654321"))));
    }

    @Test
    void foodOnlyOrder_ReplacementAndTimeoutReconciliation_DoesNotDoublePay() throws Exception {
        Booking foodOnly = createFoodOnlyBooking(cinema1, "foodonly_" + TEST_TAG + "@example.com");
        BookingFoodItem bfi = bookingFoodItemRepository.findByBooking_BookingId(foodOnly.getBookingId()).get(0);
        FoodFulfillment ff = foodFulfillmentRepository.findByBooking_BookingId(foodOnly.getBookingId()).orElseThrow();

        // Customer accepts replacement on 1 of 2 items -> only 1 remains refundable
        StaffFoodActionRequestDto repl = new StaffFoodActionRequestDto();
        repl.setBookingFoodItemId(bfi.getId());
        repl.setActionType("REPLACEMENT_CONFIRMED");
        repl.setQuantity(1);
        repl.setCustomerAcceptedReplacement(true);
        repl.setReplacementDescription("Swapped 1 Caramel to Cheese Popcorn");
        var ffDto = foodFulfillmentService.recordDeliveryAction(ff.getId(), repl, staff1.getUserId());
        assertEquals(1, ffDto.getHistory().size());

        // Submit public refund for remaining 1 undelivered item (no ticket code needed!)
        String submitJson = objectMapper.writeValueAsString(Map.of(
                "orderCode", foodOnly.getBookingCode(),
                "purchaseEmail", foodOnly.getGuestEmail(),
                "reasonCategory", "UNDELIVERED_FOOD",
                "description", "1 popcorn missing",
                "toBin", "970436",
                "toAccountNumber", "1234567890",
                "confirmAccountNumber", "1234567890",
                "bankAccountName", "Food Only Customer",
                "items", List.of(Map.of("itemKind", "FOOD_ITEM", "targetId", bfi.getId(), "quantity", 1))
        ));
        MvcResult subRes = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(submitJson))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode reqNode = objectMapper.readTree(subRes.getResponse().getContentAsString());
        Long refundId = reqNode.get("id").asLong();
        Long itemId = reqNode.get("items").get(0).get("id").asLong();

        // Simulate PayOS network timeout on POST, then SUCCEEDED on GET ?referenceId=...
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(0, null, null, true, "Read timed out");
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                try {
                    String refId = "RFD-" + refundId + "-A1";
                    Map<String, Object> payout = new LinkedHashMap<>();
                    payout.put("id", "po_reconciled_1");
                    payout.put("referenceId", refId);
                    payout.put("approvalState", "COMPLETED");
                    payout.put("transactions", List.of(Map.of("id", "tx_1", "referenceId", refId, "amount", 50000, "state", "SUCCEEDED")));
                    Map<String, Object> data = Map.of("payouts", List.of(payout));
                    String effectiveKey = (payoutChecksumKey != null && !payoutChecksumKey.isBlank())
                            ? payoutChecksumKey.trim()
                            : "MOCK_PAYOUT_CHECKSUM_KEY";
                    String sig = PayOsPayoutSignatureUtil.signPayload(data, effectiveKey);
                    return new PayOsPayoutService.HttpOutcome(200, objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", data)), sig, false, null);
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        String reviewJson = objectMapper.writeValueAsString(Map.of(
                "action", "DECIDE_ITEMS",
                "itemDecisions", List.of(Map.of("itemId", itemId, "decision", "APPROVED", "approvedQuantity", 1))
        ));
        com.boxcinema.common.dto.UserSummaryDto m1Dto = new com.boxcinema.common.dto.UserSummaryDto(
                manager1.getUserId(), manager1.getFullName(), manager1.getEmail(), manager1.getPhone(), "MANAGER");
        com.boxcinema.common.dto.UserSummaryDto a1Dto = new com.boxcinema.common.dto.UserSummaryDto(
                admin1.getUserId(), admin1.getFullName(), admin1.getEmail(), admin1.getPhone(), "ADMIN");

        mockMvc.perform(post("/api/manager/refunds/" + refundId + "/review").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON).content(reviewJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("AWAITING_PAYOUT_APPROVAL"))
                .andExpect(jsonPath("$.payoutStatus").value("AWAITING_APPROVAL"));

        mockMvc.perform(post("/api/admin/refunds/" + refundId + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", a1Dto)
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.requestStatus").value("PAYOUT_PROCESSING"))
                .andExpect(jsonPath("$.latestPayout.unknownOutcome").value(true))
                .andExpect(jsonPath("$.latestPayout.referenceId").value("RFD-" + refundId + "-A1"));

        // Reconcile via retry-payout -> stays same referenceId and settles to SUCCEEDED
        mockMvc.perform(post("/api/admin/refunds/" + refundId + "/retry-payout").with(csrf())
                        .sessionAttr("currentUser", a1Dto)
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.latestPayout.referenceId").value("RFD-" + refundId + "-A1"));
    }

    @Test
    void comboComponentPartialRefund_RequestMoreInfo_And_IndependentVoucherRetry() throws Exception {
        // Combo 80,000 (Popcorn 50k + Coca 30k) + Ticket 100k = 180k, 10% voucher = 18k -> Net 162k
        Booking b = new Booking();
        b.setBookingCode("BOX-20261121-" + (1000 + new Random().nextInt(8999)));
        b.setCinema(cinema1);
        b.setOrderType("TICKET_AND_FOOD");
        b.setGuestName("Combo Guest");
        b.setGuestEmail("combo_" + TEST_TAG + "@example.com");
        b.setGuestPhone("0901234567");
        b.setTicketSubtotal(BigDecimal.valueOf(100000));
        b.setFoodSubtotal(BigDecimal.valueOf(80000));
        b.setSubtotal(BigDecimal.valueOf(180000));
        b.setDiscountAmount(BigDecimal.valueOf(18000));
        b.setTotalAmount(BigDecimal.valueOf(162000));
        b.setBookingStatus("CONFIRMED");
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());
        recordPaidPayment(b);

        BookingSeat bs = new BookingSeat(b, 2L, BigDecimal.valueOf(100000), "C3", "BOOKED");
        bs = bookingSeatRepository.save(bs);
        b.getBookingSeats().add(bs);

        BookingFoodItem comboBfi = new BookingFoodItem();
        comboBfi.setBooking(b);
        comboBfi.setItemType("COMBO");
        comboBfi.setProductNameSnapshot("Solo Combo");
        comboBfi.setQuantity(1);
        comboBfi.setUnitPriceSnapshot(BigDecimal.valueOf(80000));
        comboBfi.setSubtotal(BigDecimal.valueOf(80000));
        comboBfi = bookingFoodItemRepository.save(comboBfi);
        b.getBookingFoodItems().add(comboBfi);

        BookingFoodItemComponent popcornComp = new BookingFoodItemComponent();
        popcornComp.setBookingFoodItem(comboBfi);
        popcornComp.setBooking(b);
        popcornComp.setComponentNameSnapshot("Butter Popcorn");
        popcornComp.setQuantityPerCombo(1);
        popcornComp.setTotalQuantity(1);
        popcornComp.setCatalogUnitPriceSnapshot(BigDecimal.valueOf(50000));
        popcornComp.setAllocatedUnitPrice(BigDecimal.valueOf(50000));
        popcornComp.setAllocatedSubtotal(BigDecimal.valueOf(50000));
        popcornComp.setDiscountAllocated(BigDecimal.valueOf(5000));
        popcornComp.setNetPaidAmount(BigDecimal.valueOf(45000));
        popcornComp = componentRepository.save(popcornComp);

        BookingFoodItemComponent cocaComp = new BookingFoodItemComponent();
        cocaComp.setBookingFoodItem(comboBfi);
        cocaComp.setBooking(b);
        cocaComp.setComponentNameSnapshot("Coca-Cola");
        cocaComp.setQuantityPerCombo(1);
        cocaComp.setTotalQuantity(1);
        cocaComp.setCatalogUnitPriceSnapshot(BigDecimal.valueOf(30000));
        cocaComp.setAllocatedUnitPrice(BigDecimal.valueOf(30000));
        cocaComp.setAllocatedSubtotal(BigDecimal.valueOf(30000));
        cocaComp.setDiscountAllocated(BigDecimal.valueOf(3000));
        cocaComp.setNetPaidAmount(BigDecimal.valueOf(27000));
        cocaComp = componentRepository.save(cocaComp);

        // Customer submits refund for ONLY the Popcorn component inside Solo Combo
        String submitJson = objectMapper.writeValueAsString(Map.of(
                "orderCode", b.getBookingCode(),
                "purchaseEmail", b.getGuestEmail(),
                "reasonCategory", "PRODUCT_INCIDENT",
                "description", "Popcorn in combo was stale",
                "toBin", "970422",
                "toAccountNumber", "0999888777",
                "confirmAccountNumber", "0999888777",
                "bankAccountName", "Combo Customer",
                "items", List.of(Map.of("itemKind", "COMBO_COMPONENT", "targetId", popcornComp.getId(), "quantity", 1))
        ));
        MvcResult subRes = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(submitJson))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode reqNode = objectMapper.readTree(subRes.getResponse().getContentAsString());
        Long refundId = reqNode.get("id").asLong();
        String reqCode = reqNode.get("requestCode").asText();
        Long compItemId = reqNode.get("items").get(0).get("id").asLong();

        com.boxcinema.common.dto.UserSummaryDto m1Dto = new com.boxcinema.common.dto.UserSummaryDto(
                manager1.getUserId(), manager1.getFullName(), manager1.getEmail(), manager1.getPhone(), "MANAGER");

        // Manager requests more info -> REFUND_MORE_INFO email queued -> Customer replies -> back to PENDING_REVIEW
        mockMvc.perform(post("/api/manager/refunds/" + refundId + "/review").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"REQUEST_MORE_INFO\",\"note\":\"Please share photo of popcorn cup\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("MORE_INFO_REQUIRED"));

        List<Map<String, Object>> moreInfoRows = jdbcTemplate.queryForList(
                "SELECT dedup_key, status, body_html FROM email_outbox WHERE booking_id = ? AND dedup_key LIKE ?",
                b.getBookingId(), "REFUND_MORE_INFO:" + refundId + ":%");
        assertEquals(1, moreInfoRows.size());
        assertTrue(Set.of("PENDING", "SENT").contains(moreInfoRows.get(0).get("status")));
        String moreInfoHtml = String.valueOf(moreInfoRows.get(0).get("body_html"));
        assertTrue(moreInfoHtml.contains("Please share photo of popcorn cup"));
        assertFalse(moreInfoHtml.contains("0999888777"));

        mockMvc.perform(post("/api/refunds/public/" + reqCode + "/respond").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purchaseEmail", b.getGuestEmail(),
                                "message", "Attached photo of stale popcorn",
                                "evidenceUrl", "https://example.com/popcorn.jpg"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("PENDING_REVIEW"));

        // L4: Inject real VoucherRecoveryService fault on Manager review, then Admin dispatches payout -> PayOS payout succeeds once, voucher records FAILED, retry-voucher recovers cleanly without re-payout
        String approveJson = objectMapper.writeValueAsString(Map.of(
                "action", "DECIDE_ITEMS",
                "itemDecisions", List.of(Map.of("itemId", compItemId, "decision", "APPROVED", "approvedQuantity", 1))
        ));
        com.boxcinema.common.dto.UserSummaryDto a1Dto = new com.boxcinema.common.dto.UserSummaryDto(
                admin1.getUserId(), admin1.getFullName(), admin1.getEmail(), admin1.getPhone(), "ADMIN");
        voucherRecoveryService.setCustomFailureInjectorForTesting(vr -> {
            throw new RuntimeException("Simulated recovery DB constraint failure");
        });
        try {
            mockMvc.perform(post("/api/manager/refunds/" + refundId + "/review").with(csrf())
                            .sessionAttr("currentUser", m1Dto)
                            .with(user(manager1.getEmail()).roles("MANAGER"))
                            .contentType(MediaType.APPLICATION_JSON).content(approveJson))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.approvedCashTotal").value(45000))
                    .andExpect(jsonPath("$.approvedVoucherTotal").value(5000))
                    .andExpect(jsonPath("$.payoutStatus").value("AWAITING_APPROVAL"))
                    .andExpect(jsonPath("$.voucherStatus").value("FAILED"))
                    .andExpect(jsonPath("$.requestStatus").value("AWAITING_PAYOUT_APPROVAL"));

            mockMvc.perform(post("/api/admin/refunds/" + refundId + "/payout/approve").with(csrf())
                            .sessionAttr("currentUser", a1Dto)
                            .with(user(admin1.getEmail()).roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.payoutStatus").value("SUCCEEDED"))
                    .andExpect(jsonPath("$.voucherStatus").value("FAILED"))
                    .andExpect(jsonPath("$.requestStatus").value("PAYOUT_SUCCEEDED"));
        } finally {
            voucherRecoveryService.setCustomFailureInjectorForTesting(null);
        }

        VoucherRecovery failedVr = voucherRecoveryRepository.findByRefundRequest_IdOrderByIdAsc(refundId).get(0);
        assertEquals("FAILED", failedVr.getStatus());
        assertEquals(1, failedVr.getRetryCount());
        assertEquals("Simulated recovery DB constraint failure", failedVr.getLastError());
        assertNull(failedVr.getNewVoucherCode());
        Integer payoutAttemptsBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payos_payout_transactions WHERE refund_request_id = ?", Integer.class, refundId);
        assertEquals(1, payoutAttemptsBefore);

        // Retry voucher without touching PayOS payout -> GENERATED, COMPLETED, REFUND_VOUCHER_ISSUED queued, payout count stays 1
        MvcResult retryVRes = mockMvc.perform(post("/api/manager/refunds/" + refundId + "/retry-voucher").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.voucherStatus").value("GENERATED"))
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"))
                .andReturn();
        Integer payoutAttemptsAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payos_payout_transactions WHERE refund_request_id = ?", Integer.class, refundId);
        assertEquals(1, payoutAttemptsAfter);
        String newCode = objectMapper.readTree(retryVRes.getResponse().getContentAsString())
                .get("recoveryVoucher").get("newVoucherCode").asText();
        assertTrue(newCode.startsWith("RCV-"));
        assertTrue(emailOutboxRepository.existsByDedupKey("REFUND_VOUCHER_ISSUED:" + refundId));
        promotionRepository.findByVoucherCodeIgnoreCase(newCode).ifPresent(p -> createdPromoIds.add(p.getPromotionId()));

        // Calling retry-voucher when already GENERATED is idempotent and does NOT create a second voucher or payout
        mockMvc.perform(post("/api/manager/refunds/" + refundId + "/retry-voucher").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recoveryVoucher.newVoucherCode").value(newCode));

        // V3: Guest recovery voucher validates cleanly at /api/vouchers/validate with guest email
        mockMvc.perform(post("/api/vouchers/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "voucherCode", newCode,
                                "orderAmount", 100000,
                                "email", b.getGuestEmail()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.discountAmount").value(5000));

        // Wrong guest email rejected for private recovery voucher
        mockMvc.perform(post("/api/vouchers/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "voucherCode", newCode,
                                "orderAmount", 100000,
                                "email", "other@example.com"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void edgeCases_DeliveredFoodOnCinemaIncident_SequentialPartials_RetryGuards_PartialTicketCheckin_RejectEmail_And_VoucherRetry() throws Exception {
        // 1. V7: Invalid bank BIN/account returns HTTP 400 (not 500)
        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", "BOX-0000", "purchaseEmail", "a@b.com",
                                "reasonCategory", "OTHER", "description", "test",
                                "toBin", "999999", "toAccountNumber", "123",
                                "confirmAccountNumber", "123",
                                "bankAccountName", "Invalid Test",
                                "items", List.of(Map.of("itemKind", "TICKET", "targetId", 1, "quantity", 1))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_BANK_INFO"));

        // 2. Build booking with 2 seats (A1, A2), 2 units of Popcorn (already delivered 1, undelivered 1), and a verified CINEMA_INCIDENT
        Booking b = createPaidTicketAndFoodBooking(cinema1, "edge_" + TEST_TAG + "@example.com", false);
        BookingSeat bs2 = new BookingSeat(b, 3L, BigDecimal.valueOf(100000), "B3", "BOOKED");
        bs2 = bookingSeatRepository.save(bs2);
        b.getBookingSeats().add(bs2);
        Ticket t2 = new Ticket();
        t2.setBooking(b);
        t2.setBookingSeatId(bs2.getBookingSeatId());
        t2.setTicketCode("TCK-" + b.getBookingCode().replace("BOX-", "") + "-B3");
        t2.setQrTokenHash(CryptoUtils.sha256(t2.getTicketCode()));
        t2.setPrice(BigDecimal.valueOf(100000));
        t2.setGrossPrice(BigDecimal.valueOf(100000));
        t2.setNetPaidAmount(BigDecimal.valueOf(90000));
        t2.setDiscountAllocated(BigDecimal.valueOf(10000));
        t2.setTicketStatus("VALID");
        t2 = ticketRepository.save(t2);
        b.setTotalAmount(BigDecimal.valueOf(225000));
        bookingRepository.save(b);

        BookingQr qr = new BookingQr();
        qr.setBooking(b);
        qr.setTokenHash(CryptoUtils.sha256("BOX1.EDGE" + TEST_TAG));
        qr.setTokenEncrypted("enc");
        qr.setStatus("ACTIVE");
        bookingQrRepository.save(qr);

        BookingFoodItem bfi = bookingFoodItemRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        bfi.setQuantity(2);
        bfi.setSubtotal(BigDecimal.valueOf(100000));
        bfi.setDiscountAllocated(BigDecimal.valueOf(10000));
        bfi.setNetPaidAmount(BigDecimal.valueOf(90000));
        bfi.setDeliveredQuantity(1);
        bfi = bookingFoodItemRepository.save(bfi);

        FoodFulfillment ff = new FoodFulfillment(b);
        ff.setCinema(cinema1);
        ff.setStatus("READY");
        ff = foodFulfillmentRepository.save(ff);

        // Staff performs WRONG_ITEM_EXCHANGE + MISSING_REDELIVERY on Popcorn -> immutable history + staff_error_logs
        com.boxcinema.common.dto.UserSummaryDto s1Dto = new com.boxcinema.common.dto.UserSummaryDto(
                staff1.getUserId(), staff1.getFullName(), staff1.getEmail(), staff1.getPhone(), "STAFF");
        mockMvc.perform(post("/api/staff/food-fulfillments/" + ff.getId() + "/deliver").with(csrf())
                        .sessionAttr("currentUser", s1Dto)
                        .with(user(staff1.getEmail()).roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookingFoodItemId", bfi.getId(),
                                "actionType", "WRONG_ITEM_EXCHANGE",
                                "quantity", 1,
                                "wrongItemDescription", "Gave salted instead of caramel"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(1));

        assertEquals(1, staffErrorLogRepository.findFiltered(cinema1.getCinemaId(), staff1.getUserId(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getTotalElements());

        // Reset deliveredQuantity to 1 for food refund test, and mark 1 seat REFUNDED directly via refund flow
        bfi = bookingFoodItemRepository.findById(bfi.getId()).orElseThrow();
        bfi.setDeliveredQuantity(1);
        bfi = bookingFoodItemRepository.save(bfi);

        // Create verified CINEMA_INCIDENT
        IncidentCreateRequestDto incReq = new IncidentCreateRequestDto();
        incReq.setCinemaId(cinema1.getCinemaId());
        incReq.setBookingCode(b.getBookingCode());
        incReq.setIncidentGroup("CINEMA_INCIDENT");
        incReq.setIncidentType("PROJECTOR_FAILURE");
        incReq.setTitle("Projector Blur");
        incReq.setDescription("Screen flicker");
        MvcResult incCreate = mockMvc.perform(post("/api/staff/incidents").with(csrf())
                        .sessionAttr("currentUser", s1Dto)
                        .with(user(staff1.getEmail()).roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(incReq)))
                .andExpect(status().isCreated())
                .andReturn();
        long incId = objectMapper.readTree(incCreate.getResponse().getContentAsString()).get("id").asLong();
        com.boxcinema.common.dto.UserSummaryDto m1Dto = new com.boxcinema.common.dto.UserSummaryDto(
                manager1.getUserId(), manager1.getFullName(), manager1.getEmail(), manager1.getPhone(), "MANAGER");
        mockMvc.perform(post("/api/manager/incidents/" + incId + "/verify").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"VERIFIED\",\"note\":\"Confirmed\"}"))
                .andExpect(status().isOk());

        // V1: Delivered food CANNOT be refunded even with verified CINEMA_INCIDENT (2 requested when 1 delivered -> 1 undelivered ok, 2 fails!)
        Ticket t1 = ticketRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        MvcResult rSub = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", b.getBookingCode(),
                                "purchaseEmail", b.getGuestEmail(),
                                "reasonCategory", "CINEMA_INCIDENT",
                                "description", "Want refund for everything",
                                "toBin", "970422", "toAccountNumber", "0912345678",
                                "confirmAccountNumber", "0912345678",
                                "bankAccountName", "Edge Customer",
                                "items", List.of(
                                        Map.of("itemKind", "TICKET", "targetId", t1.getTicketId(), "quantity", 1),
                                        Map.of("itemKind", "FOOD_ITEM", "targetId", bfi.getId(), "quantity", 2))))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode rNode = objectMapper.readTree(rSub.getResponse().getContentAsString());
        long rrId = rNode.get("id").asLong();
        long rrT1 = rNode.get("items").get(0).get("id").asLong();
        long rrFood = rNode.get("items").get(1).get("id").asLong();

        // V2: Manager cannot retry-payout (ADMIN-only -> 403); Admin retrying on PENDING_REVIEW fails with 409 CONFLICT
        com.boxcinema.common.dto.UserSummaryDto a1Dto = new com.boxcinema.common.dto.UserSummaryDto(
                admin1.getUserId(), admin1.getFullName(), admin1.getEmail(), admin1.getPhone(), "ADMIN");
        mockMvc.perform(post("/api/manager/refunds/" + rrId + "/retry-payout").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/manager/refunds/" + rrId + "/retry-payout").with(csrf())
                        .sessionAttr("currentUser", a1Dto)
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isConflict());

        // Approving qty=2 food (1 of which is already delivered) must fail with DELIVERED_FOOD_NOT_REFUNDABLE even with verified CINEMA_INCIDENT!
        mockMvc.perform(post("/api/manager/refunds/" + rrId + "/review").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "DECIDE_ITEMS",
                                "itemDecisions", List.of(
                                        Map.of("itemId", rrT1, "decision", "APPROVED", "approvedQuantity", 1),
                                        Map.of("itemId", rrFood, "decision", "APPROVED", "approvedQuantity", 2))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("DELIVERED_FOOD_NOT_REFUNDABLE"));

        // Approve only t1 (undelivered food rejected with mandatory reason -> queued AWAITING_PAYOUT_APPROVAL), then Admin dispatches payout
        mockMvc.perform(post("/api/manager/refunds/" + rrId + "/review").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "DECIDE_ITEMS",
                                "itemDecisions", List.of(
                                        Map.of("itemId", rrT1, "decision", "APPROVED", "approvedQuantity", 1),
                                        Map.of("itemId", rrFood, "decision", "REJECTED", "reason", "1 unit already delivered"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("AWAITING_PAYOUT_APPROVAL"));

        // Double-click approval idempotency -> no-op, stays AWAITING_PAYOUT_APPROVAL
        mockMvc.perform(post("/api/manager/refunds/" + rrId + "/review").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "DECIDE_ITEMS",
                                "itemDecisions", List.of(Map.of("itemId", rrT1, "decision", "APPROVED", "approvedQuantity", 1))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("AWAITING_PAYOUT_APPROVAL"));

        mockMvc.perform(post("/api/admin/refunds/" + rrId + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", a1Dto)
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"));

        // V5: Check-in on booking with 1 REFUNDED ticket (t1) and 1 VALID ticket (t2) leaves t1 as REFUNDED and marks only t2 USED!
        var checkinRes = staffCheckinService.checkinByQrToken("BOX1.EDGE" + TEST_TAG, staff1.getUserId());
        assertTrue(checkinRes.isSuccess());
        assertEquals(1, checkinRes.getCheckedInTickets());
        assertEquals("REFUNDED", ticketRepository.findById(t1.getTicketId()).orElseThrow().getTicketStatus());
        assertEquals("USED", ticketRepository.findById(t2.getTicketId()).orElseThrow().getTicketStatus());

        // V4: Sequential partial refund on remaining 1 undelivered unit of bfi (after prior rejection released food)
        MvcResult rSub2 = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", b.getBookingCode(),
                                "purchaseEmail", b.getGuestEmail(),
                                "reasonCategory", "UNDELIVERED_FOOD",
                                "description", "1 unit still undelivered",
                                "toBin", "970422", "toAccountNumber", "0912345678",
                                "confirmAccountNumber", "0912345678",
                                "bankAccountName", "Edge Customer",
                                "items", List.of(Map.of("itemKind", "FOOD_ITEM", "targetId", bfi.getId(), "quantity", 1))))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode r2Node = objectMapper.readTree(rSub2.getResponse().getContentAsString());
        long rr2Id = r2Node.get("id").asLong();
        long rr2Food = r2Node.get("items").get(0).get("id").asLong();

        // Rejecting without reason -> 400 REJECTION_REASON_REQUIRED
        mockMvc.perform(post("/api/manager/refunds/" + rr2Id + "/review").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "DECIDE_ITEMS",
                                "itemDecisions", List.of(Map.of("itemId", rr2Food, "decision", "REJECTED", "reason", ""))))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REJECTION_REASON_REQUIRED"));

        // Reject with reason -> REFUND_REJECTED email queued
        mockMvc.perform(post("/api/manager/refunds/" + rr2Id + "/review").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "DECIDE_ITEMS",
                                "note", "Counter confirmed pickup",
                                "itemDecisions", List.of(Map.of("itemId", rr2Food, "decision", "REJECTED", "reason", "Picked up later"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("REJECTED"));
        assertTrue(emailOutboxRepository.existsByDedupKey("REFUND_REJECTED:" + rr2Id));

        // Cleanup extra created promos
        voucherRecoveryRepository.findByRefundRequest_IdOrderByIdAsc(rrId).forEach(vr -> {
            if (vr.getNewPromotionId() != null) createdPromoIds.add(vr.getNewPromotionId());
        });
    }

    @Test
    void secondaryFlows_RedeliveryCannotFulfill_FullTicketRevokesQr_SnapshotGuard_CatalogPriceImmutability_SequentialApprovedPartials_And_OutboxDispatch() throws Exception {
        // M2d setup: create isolated FoodCategory/FoodItem priced at 50,000 VND
        Long catId = jdbcTemplate.query("SELECT id FROM food_category LIMIT 1", (rs, idx) -> rs.getLong(1))
                .stream().findFirst().orElseGet(() -> {
                    jdbcTemplate.update(
                            "INSERT INTO food_category (name, description, status, created_at, updated_at) VALUES (?, 'Test Cat', 'ACTIVE', NOW(), NOW())",
                            "Cat-" + TEST_TAG);
                    Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                    createdCategoryIds.add(id);
                    return id;
                });
        jdbcTemplate.update(
                "INSERT INTO food_item (category_id, name, description, price, status, is_available, created_at, updated_at) VALUES (?, ?, 'Snapshot Test', 50000.00, 'ACTIVE', 1, NOW(), NOW())",
                catId, "Anchor Popcorn " + TEST_TAG);
        Long testFoodItemId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdFoodItemIds.add(testFoodItemId);
        FoodItem catalogPopcorn = foodItemRepository.findById(testFoodItemId).orElseThrow();

        // Build booking with 1 ticket (90k cash + 10k disc), 1 food linked to catalogPopcorn (135k cash + 15k disc for qty=3), 1 secondary odd-split food (99,999 cash + 10,001 disc for qty=2)
        Booking b = createPaidTicketAndFoodBooking(cinema1, "sec_" + TEST_TAG + "@example.com", false);
        b.setTotalAmount(BigDecimal.valueOf(324999));
        b = bookingRepository.save(b);
        jdbcTemplate.update("UPDATE payments SET amount = 324999.00 WHERE booking_id = ? AND payment_status = 'PAID'", b.getBookingId());

        Ticket ticket = ticketRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        BookingFoodItem popcornBfi = bookingFoodItemRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        popcornBfi.setFoodItem(catalogPopcorn);
        popcornBfi.setQuantity(3);
        popcornBfi.setSubtotal(BigDecimal.valueOf(150000));
        popcornBfi.setDiscountAllocated(BigDecimal.valueOf(15000));
        popcornBfi.setNetPaidAmount(BigDecimal.valueOf(135000));
        popcornBfi = bookingFoodItemRepository.save(popcornBfi);

        BookingFoodItem oddFood = new BookingFoodItem();
        oddFood.setBooking(b);
        oddFood.setItemType("FOOD");
        oddFood.setProductNameSnapshot("Odd Split Drink");
        oddFood.setQuantity(2);
        oddFood.setUnitPriceSnapshot(BigDecimal.valueOf(55000));
        oddFood.setSubtotal(BigDecimal.valueOf(110000));
        oddFood.setDiscountAllocated(BigDecimal.valueOf(10001));
        oddFood.setNetPaidAmount(BigDecimal.valueOf(99999));
        oddFood = bookingFoodItemRepository.save(oddFood);

        String qrToken = "BOX1.SEC" + TEST_TAG;
        BookingQr qr = new BookingQr();
        qr.setBooking(b);
        qr.setTokenHash(CryptoUtils.sha256(qrToken));
        qr.setTokenEncrypted("enc");
        qr.setStatus("ACTIVE");
        bookingQrRepository.save(qr);

        FoodFulfillment ff = new FoodFulfillment(b);
        ff.setCinema(cinema1);
        ff.setStatus("QUEUED");
        ff = foodFulfillmentRepository.save(ff);

        // M2a: Staff performs INITIAL_DELIVERY (qty 1), MISSING_REDELIVERY (qty 1 -> DELIVERED), and CANNOT_FULFILL on oddFood -> immutable history
        com.boxcinema.common.dto.UserSummaryDto s1Dto = new com.boxcinema.common.dto.UserSummaryDto(
                staff1.getUserId(), staff1.getFullName(), staff1.getEmail(), staff1.getPhone(), "STAFF");
        mockMvc.perform(post("/api/staff/food-fulfillments/" + ff.getId() + "/deliver").with(csrf())
                        .sessionAttr("currentUser", s1Dto)
                        .with(user(staff1.getEmail()).roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookingFoodItemId", popcornBfi.getId(),
                                "actionType", "INITIAL_DELIVERY",
                                "quantity", 1,
                                "note", "First cup delivered"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/staff/food-fulfillments/" + ff.getId() + "/deliver").with(csrf())
                        .sessionAttr("currentUser", s1Dto)
                        .with(user(staff1.getEmail()).roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookingFoodItemId", popcornBfi.getId(),
                                "actionType", "MISSING_REDELIVERY",
                                "quantity", 1,
                                "note", "Giao bu second cup"))))
                .andExpect(status().isOk());

        MvcResult cannotRes = mockMvc.perform(post("/api/staff/food-fulfillments/" + ff.getId() + "/deliver").with(csrf())
                        .sessionAttr("currentUser", s1Dto)
                        .with(user(staff1.getEmail()).roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookingFoodItemId", oddFood.getId(),
                                "actionType", "CANNOT_FULFILL",
                                "quantity", 1,
                                "note", "Out of stock at counter"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(3))
                .andReturn();
        String historyText = cannotRes.getResponse().getContentAsString();
        assertTrue(historyText.contains("INITIAL_DELIVERY"));
        assertTrue(historyText.contains("MISSING_REDELIVERY"));
        assertTrue(historyText.contains("CANNOT_FULFILL"));
        assertEquals("PARTIAL", bookingFoodItemRepository.findById(popcornBfi.getId()).orElseThrow().getDeliveryStatus());
        assertEquals("ISSUE_REPORTED", bookingFoodItemRepository.findById(oddFood.getId()).orElseThrow().getDeliveryStatus());

        // M2d: Mutate catalog FoodItem price in DB from 50,000 -> 199,000 POST-PURCHASE
        jdbcTemplate.update("UPDATE food_item SET price = 199000.00 WHERE id = ?", testFoodItemId);

        // M2c: Mark booking snapshotReviewRequired = true -> submission succeeds, Manager approval blocked with 409 SNAPSHOT_REVIEW_REQUIRED
        jdbcTemplate.update("UPDATE bookings SET snapshot_review_required = 1 WHERE booking_id = ?", b.getBookingId());

        MvcResult r1Sub = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", b.getBookingCode(),
                                "purchaseEmail", b.getGuestEmail(),
                                "reasonCategory", "PRODUCT_INCIDENT",
                                "description", "Full ticket + 1 undelivered catalog food + 1 of 2 odd drinks out of stock",
                                "toBin", "970422", "toAccountNumber", "0988777666",
                                "confirmAccountNumber", "0988777666",
                                "bankAccountName", "Snapshot Customer",
                                "items", List.of(
                                        Map.of("itemKind", "TICKET", "targetId", ticket.getTicketId(), "quantity", 1),
                                        Map.of("itemKind", "FOOD_ITEM", "targetId", popcornBfi.getId(), "quantity", 1),
                                        Map.of("itemKind", "FOOD_ITEM", "targetId", oddFood.getId(), "quantity", 1))))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode r1Node = objectMapper.readTree(r1Sub.getResponse().getContentAsString());
        long rr1Id = r1Node.get("id").asLong();
        long rr1TicketItem = r1Node.get("items").get(0).get("id").asLong();
        long rr1PopcornItem = r1Node.get("items").get(1).get("id").asLong();
        long rr1FoodItem = r1Node.get("items").get(2).get("id").asLong();

        com.boxcinema.common.dto.UserSummaryDto m1Dto = new com.boxcinema.common.dto.UserSummaryDto(
                manager1.getUserId(), manager1.getFullName(), manager1.getEmail(), manager1.getPhone(), "MANAGER");
        String rr1ApproveJson = objectMapper.writeValueAsString(Map.of(
                "action", "DECIDE_ITEMS",
                "itemDecisions", List.of(
                        Map.of("itemId", rr1TicketItem, "decision", "APPROVED", "approvedQuantity", 1),
                        Map.of("itemId", rr1PopcornItem, "decision", "APPROVED", "approvedQuantity", 1),
                        Map.of("itemId", rr1FoodItem, "decision", "APPROVED", "approvedQuantity", 1))));

        mockMvc.perform(post("/api/manager/refunds/" + rr1Id + "/review").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rr1ApproveJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SNAPSHOT_REVIEW_REQUIRED"));

        // Clear snapshotReviewRequired and approve -> ticket (90k+10k) + 1/3 popcornBfi (45k+5k, anchored to snapshot, NOT 199k!) + 1/2 oddFood (50,000+5,001)
        jdbcTemplate.update("UPDATE bookings SET snapshot_review_required = 0 WHERE booking_id = ?", b.getBookingId());

        com.boxcinema.common.dto.UserSummaryDto a1Dto = new com.boxcinema.common.dto.UserSummaryDto(
                admin1.getUserId(), admin1.getFullName(), admin1.getEmail(), admin1.getPhone(), "ADMIN");
        MvcResult rr1Biz = mockMvc.perform(post("/api/manager/refunds/" + rr1Id + "/review").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rr1ApproveJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("AWAITING_PAYOUT_APPROVAL"))
                .andExpect(jsonPath("$.approvedCashTotal").value(185000)) // 90,000 + 45,000 + 50,000 (snapshot anchored, NOT 199,000 catalog)
                .andExpect(jsonPath("$.approvedVoucherTotal").value(20001)) // 10,000 + 5,000 + 5,001
                .andReturn();
        MvcResult rr1Approved = mockMvc.perform(post("/api/admin/refunds/" + rr1Id + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", a1Dto)
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"))
                .andReturn();
        String rr1VoucherCode = objectMapper.readTree(rr1Approved.getResponse().getContentAsString())
                .get("recoveryVoucher").get("newVoucherCode").asText();
        promotionRepository.findByVoucherCodeIgnoreCase(rr1VoucherCode).ifPresent(p -> createdPromoIds.add(p.getPromotionId()));

        // M2b: Full-ticket refund revokes BookingQr -> check-in rejects with QR_NOT_ACTIVE
        assertEquals("REVOKED", bookingQrRepository.findByBooking_BookingId(b.getBookingId()).orElseThrow().getStatus());
        var revokedCheckin = staffCheckinService.checkinByQrToken(qrToken, staff1.getUserId());
        assertFalse(revokedCheckin.isSuccess());
        assertEquals("QR_NOT_ACTIVE", revokedCheckin.getCode());

        // M3 + L3: Sequential APPROVED partial refund on remaining 1 of oddFood (exact remainder 49,999 cash + 5,000 voucher!)
        MvcResult r2Sub = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", b.getBookingCode(),
                                "purchaseEmail", b.getGuestEmail(),
                                "reasonCategory", "UNDELIVERED_FOOD",
                                "description", "Second odd drink still unfulfilled",
                                "toBin", "970422", "toAccountNumber", "0988777666",
                                "confirmAccountNumber", "0988777666",
                                "bankAccountName", "Snapshot Customer",
                                "items", List.of(Map.of("itemKind", "FOOD_ITEM", "targetId", oddFood.getId(), "quantity", 1))))))
                .andExpect(status().isCreated())
                // L3 parity: submission preview absorbs exact remaining cash 49,999 (NOT 50,000!)
                .andExpect(jsonPath("$.requestedCashTotal").value(49999))
                .andReturn();
        JsonNode r2Node = objectMapper.readTree(r2Sub.getResponse().getContentAsString());
        long rr2Id = r2Node.get("id").asLong();
        long rr2FoodItem = r2Node.get("items").get(0).get("id").asLong();

        mockMvc.perform(post("/api/manager/refunds/" + rr2Id + "/review").with(csrf())
                        .sessionAttr("currentUser", m1Dto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "DECIDE_ITEMS",
                                "itemDecisions", List.of(Map.of("itemId", rr2FoodItem, "decision", "APPROVED", "approvedQuantity", 1))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedCashTotal").value(49999))
                .andExpect(jsonPath("$.approvedVoucherTotal").value(5000))
                .andExpect(jsonPath("$.requestStatus").value("AWAITING_PAYOUT_APPROVAL"));
        MvcResult rr2Approved = mockMvc.perform(post("/api/admin/refunds/" + rr2Id + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", a1Dto)
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"))
                .andReturn();
        String rr2VoucherCode = objectMapper.readTree(rr2Approved.getResponse().getContentAsString())
                .get("recoveryVoucher").get("newVoucherCode").asText();
        promotionRepository.findByVoucherCodeIgnoreCase(rr2VoucherCode).ifPresent(p -> createdPromoIds.add(p.getPromotionId()));

        BookingFoodItem updatedOdd = bookingFoodItemRepository.findById(oddFood.getId()).orElseThrow();
        assertEquals(2, updatedOdd.getRefundedQuantity());
        assertEquals("REFUNDED", updatedOdd.getDeliveryStatus());

        // 3rd attempt on exhausted oddFood must fail with 409 DUPLICATE_OR_EXCESS_QTY
        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", b.getBookingCode(),
                                "purchaseEmail", b.getGuestEmail(),
                                "reasonCategory", "UNDELIVERED_FOOD",
                                "description", "Over-refund check",
                                "toBin", "970422", "toAccountNumber", "0988777666",
                                "confirmAccountNumber", "0988777666",
                                "bankAccountName", "Snapshot Customer",
                                "items", List.of(Map.of("itemKind", "FOOD_ITEM", "targetId", oddFood.getId(), "quantity", 1))))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_OR_EXCESS_QTY"));

        // M2d follow-up: Verify popcornBfi (linked to mutated FoodItem) still shows snapshot 135k netPaidAmount on public lookup (not mutated 199k!)
        MvcResult lookupRes = mockMvc.perform(post("/api/refunds/public/lookup-order").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderCode\":\"" + b.getBookingCode() + "\",\"purchaseEmail\":\"" + b.getGuestEmail() + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode lookupJson = objectMapper.readTree(lookupRes.getResponse().getContentAsString());
        JsonNode popcornOpt = null;
        for (JsonNode opt : lookupJson.get("items")) {
            if ("FOOD_ITEM".equals(opt.get("itemKind").asText()) && opt.get("targetId").asLong() == popcornBfi.getId()) {
                popcornOpt = opt;
                break;
            }
        }
        assertNotNull(popcornOpt);
        assertEquals(135000L, popcornOpt.get("netPaidAmount").asLong());

        // L2: Voucher-only approval Stage 4 email omits "Funds have NOT yet arrived" and explains no bank transfer needed
        RefundRequest voucherOnlyReq = new RefundRequest();
        voucherOnlyReq.setRequestCode("RFD-VONLY-" + System.currentTimeMillis());
        voucherOnlyReq.setBooking(b);
        voucherOnlyReq.setCinema(cinema1);
        voucherOnlyReq.setSubmittedOrderCode(b.getBookingCode());
        voucherOnlyReq.setCustomerName("Voucher Only Guest");
        voucherOnlyReq.setPurchaseEmail(b.getGuestEmail());
        voucherOnlyReq.setReasonCategory("OTHER");
        voucherOnlyReq.setDescription("Voucher-only recovery check");
        voucherOnlyReq.setToBin("970422");
        voucherOnlyReq.setToBankName("MB Bank");
        voucherOnlyReq.setToAccountNumberEncrypted("enc");
        voucherOnlyReq.setToAccountNumberMasked("******7666");
        voucherOnlyReq.setRequestedCashTotal(BigDecimal.ZERO);
        voucherOnlyReq.setApprovedCashTotal(BigDecimal.ZERO);
        voucherOnlyReq.setApprovedVoucherTotal(BigDecimal.valueOf(15000));
        voucherOnlyReq.setRequestStatus("APPROVED");
        voucherOnlyReq.setPayoutStatus("NOT_REQUIRED");
        voucherOnlyReq.setVoucherStatus("PENDING");
        voucherOnlyReq = refundRequestRepository.save(voucherOnlyReq);
        refundEmailService.enqueueApprovedEmail(voucherOnlyReq);

        Map<String, Object> vOnlyEmailRow = jdbcTemplate.queryForMap(
                "SELECT body_html FROM email_outbox WHERE dedup_key = ?", "REFUND_APPROVED:" + voucherOnlyReq.getId());
        String vOnlyHtml = String.valueOf(vOnlyEmailRow.get("body_html"));
        assertTrue(vOnlyHtml.contains("no bank transfer is required"));
        assertFalse(vOnlyHtml.contains("Funds have NOT yet arrived"));

        // M1: Verify Stage 1, Stage 4 (cash), Stage 5, Stage 6 HTML wording & dispatch via EmailOutboxWorkerService -> SENT without leaking raw account
        List<Map<String, Object>> bookingEmailsBefore = jdbcTemplate.queryForList(
                "SELECT dedup_key, status, body_html FROM email_outbox WHERE booking_id = ?", b.getBookingId());
        assertFalse(bookingEmailsBefore.isEmpty());
        for (Map<String, Object> row : bookingEmailsBefore) {
            String key = String.valueOf(row.get("dedup_key"));
            String html = String.valueOf(row.get("body_html"));
            assertFalse(html.contains("0988777666"), "Raw bank account must never appear in " + key);
            if (key.startsWith("REFUND_RECEIVED:")) {
                assertTrue(html.contains("NOT yet been approved or refunded"));
            } else if (key.equals("REFUND_APPROVED:" + rr1Id)) {
                assertTrue(html.contains("Funds have NOT yet arrived"));
            } else if (key.startsWith("REFUND_PAYOUT_SUCCEEDED:")) {
                assertTrue(html.contains("******7666"));
                assertTrue(html.contains("SUCCEEDED"));
            } else if (key.equals("REFUND_VOUCHER_ISSUED:" + rr1Id)) {
                assertTrue(html.contains(rr1VoucherCode));
            }
        }

        jdbcTemplate.update("UPDATE email_outbox SET status = 'PENDING', sent_at = NULL WHERE booking_id = ?", b.getBookingId());
        emailOutboxWorkerService.processOutbox();

        verify(mailSender, atLeast(5)).send(any(MimeMessage.class));
        List<Map<String, Object>> bookingEmailsAfter = jdbcTemplate.queryForList(
                "SELECT dedup_key, status, sent_at FROM email_outbox WHERE booking_id = ?", b.getBookingId());
        for (Map<String, Object> row : bookingEmailsAfter) {
            assertEquals("SENT", row.get("status"), "Outbox row should be SENT for " + row.get("dedup_key"));
            assertNotNull(row.get("sent_at"), "sent_at should be populated for " + row.get("dedup_key"));
        }
    }

    @Test
    void voucherUpdateFoodItems_PendingPaymentPreservation_And_ConcurrentEmailOutboxDispatch() throws Exception {
        // Task 1 (L1): Single-use REFUND_RECOVERY voucher preserved when updating food on DRAFT & PENDING_PAYMENT
        User customer = ensureUser("l1_cust_" + TEST_TAG + "@box.test", "CUSTOMER", null);

        Long catId = jdbcTemplate.query("SELECT id FROM food_category LIMIT 1", (rs, idx) -> rs.getLong(1))
                .stream().findFirst().orElseGet(() -> {
                    jdbcTemplate.update(
                            "INSERT INTO food_category (name, description, status, created_at, updated_at) VALUES (?, 'Test Cat', 'ACTIVE', NOW(), NOW())",
                            "CatL1-" + TEST_TAG);
                    Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                    createdCategoryIds.add(id);
                    return id;
                });
        jdbcTemplate.update(
                "INSERT INTO food_item (category_id, name, description, price, status, is_available, created_at, updated_at) VALUES (?, ?, 'L1 Food', 50000.00, 'ACTIVE', 1, NOW(), NOW())",
                catId, "L1 Popcorn " + TEST_TAG);
        Long foodItemId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        createdFoodItemIds.add(foodItemId);
        jdbcTemplate.update("INSERT INTO food_inventory (food_item_id, on_hand_quantity, reserved_quantity, updated_at) VALUES (?, 300, 0, NOW())",
                foodItemId);

        Promotion rcvPromo = new Promotion();
        rcvPromo.setPromotionName("Recovery L1 " + TEST_TAG);
        String rcvCode = "RCV-L1-" + Math.abs(TEST_TAG.hashCode() % 100000);
        rcvPromo.setVoucherCode(rcvCode);
        rcvPromo.setDiscountType("FIXED");
        rcvPromo.setDiscountValue(BigDecimal.valueOf(15000));
        rcvPromo.setMaxDiscount(BigDecimal.valueOf(15000));
        rcvPromo.setMinOrderAmount(BigDecimal.ZERO);
        rcvPromo.setUsageLimit(1);
        rcvPromo.setPerUserLimit(1);
        rcvPromo.setStartAt(LocalDateTime.now().minusMinutes(5));
        rcvPromo.setEndAt(LocalDateTime.now().plusDays(30));
        rcvPromo.setStatus("ACTIVE");
        rcvPromo.setPublic(false);
        rcvPromo.setPromotionSource("REFUND_RECOVERY");
        rcvPromo.setTargetUserId(customer.getUserId());
        rcvPromo.setTargetEmail(customer.getEmail());
        rcvPromo = promotionRepository.save(rcvPromo);
        createdPromoIds.add(rcvPromo.getPromotionId());

        Booking b1 = new Booking();
        b1.setBookingCode("BOX-L1A-" + (1000 + new Random().nextInt(8999)));
        b1.setCinema(cinema1);
        b1.setUser(customer);
        b1.setOrderType("TICKET_ONLY");
        b1.setGuestName(customer.getFullName());
        b1.setGuestEmail(customer.getEmail());
        b1.setGuestPhone("0901234567");
        b1.setTicketSubtotal(BigDecimal.valueOf(100000));
        b1.setSubtotal(BigDecimal.valueOf(100000));
        b1.setDiscountAmount(BigDecimal.valueOf(15000));
        b1.setTotalAmount(BigDecimal.valueOf(85000));
        b1.setBookingStatus("DRAFT");
        b1.setPromotion(rcvPromo);
        b1.setVoucherCodeSnapshot(rcvCode);
        b1 = bookingRepository.save(b1);
        createdBookingIds.add(b1.getBookingId());

        BookingSeat bs1 = new BookingSeat(b1, 1L, BigDecimal.valueOf(100000), "D1", "BOOKED");
        bs1 = bookingSeatRepository.save(bs1);
        b1.getBookingSeats().add(bs1);
        b1 = bookingRepository.save(b1);

        // 1a. DRAFT updateFoodItems preserves single-use recovery voucher
        UpdateFoodItemsRequestDto req1 = new UpdateFoodItemsRequestDto();
        req1.setFoodItems(List.of(new BookingFoodItemRequestDto(foodItemId, null, 1)));
        var resDraft = bookingService.updateFoodItems(b1.getBookingId(), req1, customer.getUserId());
        assertEquals(rcvCode, resDraft.getVoucherCode());
        assertEquals(0, BigDecimal.valueOf(15000).compareTo(resDraft.getDiscountAmount()));
        assertEquals(0, BigDecimal.valueOf(135000).compareTo(resDraft.getTotalAmount()));

        // 1b. PENDING_PAYMENT updateFoodItems preserves single-use recovery voucher (no self-count!)
        jdbcTemplate.update("UPDATE bookings SET booking_status = 'PENDING_PAYMENT' WHERE booking_id = ?", b1.getBookingId());
        UpdateFoodItemsRequestDto req2 = new UpdateFoodItemsRequestDto();
        req2.setFoodItems(List.of(new BookingFoodItemRequestDto(foodItemId, null, 2)));
        var resPending = bookingService.updateFoodItems(b1.getBookingId(), req2, customer.getUserId());
        assertEquals(rcvCode, resPending.getVoucherCode());
        assertEquals(0, BigDecimal.valueOf(15000).compareTo(resPending.getDiscountAmount()));
        assertEquals(0, BigDecimal.valueOf(185000).compareTo(resPending.getTotalAmount()));

        // 1c. When a 2nd CONFIRMED booking consumes the single-use voucher, b1 strips/rejects it cleanly
        Booking b2 = new Booking();
        b2.setBookingCode("BOX-L1B-" + (1000 + new Random().nextInt(8999)));
        b2.setCinema(cinema1);
        b2.setUser(customer);
        b2.setOrderType("TICKET_ONLY");
        b2.setGuestName(customer.getFullName());
        b2.setGuestEmail(customer.getEmail());
        b2.setGuestPhone("0901234567");
        b2.setTicketSubtotal(BigDecimal.valueOf(100000));
        b2.setSubtotal(BigDecimal.valueOf(100000));
        b2.setDiscountAmount(BigDecimal.valueOf(15000));
        b2.setTotalAmount(BigDecimal.valueOf(85000));
        b2.setBookingStatus("CONFIRMED");
        b2.setPromotion(rcvPromo);
        b2.setVoucherCodeSnapshot(rcvCode);
        b2 = bookingRepository.save(b2);
        createdBookingIds.add(b2.getBookingId());

        final Long b1Id = b1.getBookingId();
        assertThrows(IllegalArgumentException.class, () ->
                promotionService.validateAndLockForRedemption(rcvCode, BigDecimal.valueOf(150000), customer.getUserId(), customer.getEmail(), b1Id));
        var resStripped = bookingService.updateFoodItems(b1.getBookingId(), req1, customer.getUserId());
        assertNull(resStripped.getVoucherCode());
        assertEquals(0, BigDecimal.ZERO.compareTo(resStripped.getDiscountAmount()));
        assertEquals(0, BigDecimal.valueOf(150000).compareTo(resStripped.getTotalAmount()));

        // 1d. CONFIRMED booking calling updateFoodItems still throws IllegalStateException
        final Long b2Id = b2.getBookingId();
        assertThrows(IllegalStateException.class, () ->
                bookingService.updateFoodItems(b2Id, req1, customer.getUserId()));

        // Task 2: 6 concurrent EmailOutboxWorkerService workers claim & send 12 PENDING emails with FOR UPDATE SKIP LOCKED
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        Map<String, Integer> sentCounts = new ConcurrentHashMap<>();
        doAnswer(inv -> {
            MimeMessage msg = inv.getArgument(0);
            String subj = msg.getSubject();
            if (subj != null && subj.startsWith("CONC-" + TEST_TAG + "-")) {
                sentCounts.merge(subj, 1, Integer::sum);
            }
            return null;
        }).when(mailSender).send(any(MimeMessage.class));

        int totalRows = 12;
        for (int i = 0; i < totalRows; i++) {
            EmailOutbox eo = new EmailOutbox(b1, "conc_" + i + "_" + TEST_TAG + "@box.test", "REFUND_RECEIVED");
            eo.setSubject("CONC-" + TEST_TAG + "-" + i);
            eo.setBodyHtml("<p>Concurrent dispatch body " + i + "</p>");
            eo.setDedupKey("CONC_" + TEST_TAG + "_" + i);
            emailOutboxRepository.save(eo);
        }

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    emailOutboxWorkerService.processOutbox();
                    return null;
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> f : futures) {
                assertDoesNotThrow(() -> f.get(15, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(totalRows, sentCounts.size());
        for (int i = 0; i < totalRows; i++) {
            String key = "CONC-" + TEST_TAG + "-" + i;
            assertEquals(1, sentCounts.getOrDefault(key, 0), "Expected exact-once send for " + key);
        }
        List<Map<String, Object>> dbOutbox = jdbcTemplate.queryForList(
                "SELECT dedup_key, status, sent_at FROM email_outbox WHERE booking_id = ? AND dedup_key LIKE ?",
                b1.getBookingId(), "CONC_" + TEST_TAG + "_%");
        assertEquals(totalRows, dbOutbox.size());
        for (Map<String, Object> row : dbOutbox) {
            assertEquals("SENT", row.get("status"));
            assertNotNull(row.get("sent_at"));
        }
    }

    @Test
    void concurrentFoodItemRefundSubmit_EnforcesRemainingRefundableQuantity() throws Exception {
        Booking b = createPaidTicketAndFoodBooking(cinema1, "conc_food_" + TEST_TAG + "@example.com", false);
        BookingFoodItem bfi = bookingFoodItemRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        assertEquals(1, bfi.getQuantity());

        String payload = objectMapper.writeValueAsString(Map.of(
                "orderCode", b.getBookingCode(),
                "purchaseEmail", b.getGuestEmail(),
                "reasonCategory", "UNDELIVERED_FOOD",
                "description", "Concurrent food refund submit race",
                "toBin", "970422",
                "toAccountNumber", "0912345678",
                "confirmAccountNumber", "0912345678",
                "bankAccountName", "Concurrent Customer",
                "items", List.of(Map.of("itemKind", "FOOD_ITEM", "targetId", bfi.getId(), "quantity", 1))));

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String clientIp = "10.20.0." + (i + 1);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mockMvc.perform(post("/api/refunds/public").with(csrf())
                                    .with(req -> { req.setRemoteAddr(clientIp); return req; })
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(payload))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            int created = 0;
            int conflict = 0;
            for (Future<Integer> f : futures) {
                int st = f.get(20, TimeUnit.SECONDS);
                if (st == 201) created++;
                else if (st == 409) conflict++;
                else fail("Unexpected status in concurrent FOOD_ITEM submit: " + st);
            }
            assertEquals(1, created, "Exactly one concurrent FOOD_ITEM refund submit should succeed");
            assertEquals(3, conflict, "Remaining concurrent FOOD_ITEM submits must fail with 409");
        } finally {
            pool.shutdownNow();
        }
    }

    private User ensureUser(String email, String role, Long cinemaId) {
        User u = new User();
        u.setFullName("Test " + role);
        u.setEmail(email);
        u.setPhone("09" + Math.abs(email.hashCode() % 100000000));
        u.setRole(role);
        u.setCinemaId(cinemaId);
        u.setAccountStatus("ACTIVE");
        u = userRepository.save(u);
        createdUserIds.add(u.getUserId());
        return u;
    }

    private Booking createPaidTicketAndFoodBooking(Cinema cinema, String email, boolean checkedInTicket) {
        Promotion oldPromo = new Promotion();
        oldPromo.setPromotionName("Expired 10% " + TEST_TAG);
        oldPromo.setVoucherCode("OLD10-" + Math.abs(TEST_TAG.hashCode() % 10000));
        oldPromo.setDiscountType("PERCENT");
        oldPromo.setDiscountValue(BigDecimal.valueOf(10));
        oldPromo.setStartAt(LocalDateTime.now().minusDays(10));
        oldPromo.setEndAt(LocalDateTime.now().minusDays(1));
        oldPromo.setStatus("ACTIVE");
        oldPromo = promotionRepository.save(oldPromo);
        createdPromoIds.add(oldPromo.getPromotionId());

        Booking b = new Booking();
        b.setBookingCode("BOX-20261121-" + (1000 + new Random().nextInt(8999)));
        b.setCinema(cinema);
        b.setOrderType("TICKET_AND_FOOD");
        b.setGuestName("Guest Test");
        b.setGuestEmail(email);
        b.setGuestPhone("0901234567");
        b.setTicketSubtotal(BigDecimal.valueOf(100000));
        b.setFoodSubtotal(BigDecimal.valueOf(50000));
        b.setSubtotal(BigDecimal.valueOf(150000));
        b.setDiscountAmount(BigDecimal.valueOf(15000));
        b.setTotalAmount(BigDecimal.valueOf(135000));
        b.setBookingStatus("CONFIRMED");
        b.setPromotion(oldPromo);
        b.setVoucherCodeSnapshot(oldPromo.getVoucherCode());
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());
        recordPaidPayment(b);

        BookingSeat bs = new BookingSeat(b, 1L, BigDecimal.valueOf(100000), "B2", "BOOKED");
        bs = bookingSeatRepository.save(bs);
        b.getBookingSeats().add(bs);

        BookingFoodItem bfi = new BookingFoodItem();
        bfi.setBooking(b);
        bfi.setItemType("FOOD");
        bfi.setProductNameSnapshot("Popcorn Large");
        bfi.setQuantity(1);
        bfi.setUnitPriceSnapshot(BigDecimal.valueOf(50000));
        bfi.setSubtotal(BigDecimal.valueOf(50000));
        bfi = bookingFoodItemRepository.save(bfi);
        b.getBookingFoodItems().add(bfi);

        Ticket t = new Ticket();
        t.setBooking(b);
        t.setBookingSeatId(bs.getBookingSeatId());
        t.setTicketCode("TCK-" + b.getBookingCode().replace("BOX-", "") + "-B2");
        t.setQrTokenHash(CryptoUtils.sha256(t.getTicketCode()));
        t.setPrice(BigDecimal.valueOf(100000));
        t.setTicketStatus(checkedInTicket ? "USED" : "VALID");
        if (checkedInTicket) t.setCheckedInAt(LocalDateTime.now().minusMinutes(30));
        t = ticketRepository.save(t);

        snapshotService.allocateOrderSnapshot(b, BigDecimal.valueOf(135000), List.of(t));
        ticketRepository.save(t);
        bookingSeatRepository.save(bs);
        bookingFoodItemRepository.save(bfi);
        return bookingRepository.save(b);
    }

    private Booking createFoodOnlyBooking(Cinema cinema, String email) {
        Booking b = new Booking();
        b.setBookingCode("BOX-20261121-" + (1000 + new Random().nextInt(8999)));
        b.setCinema(cinema);
        b.setOrderType("FOOD_ONLY");
        b.setGuestName("Food Only Guest");
        b.setGuestEmail(email);
        b.setGuestPhone("0901234567");
        b.setFoodSubtotal(BigDecimal.valueOf(100000));
        b.setSubtotal(BigDecimal.valueOf(100000));
        b.setDiscountAmount(BigDecimal.ZERO);
        b.setTotalAmount(BigDecimal.valueOf(100000));
        b.setBookingStatus("CONFIRMED");
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());
        recordPaidPayment(b);

        BookingFoodItem bfi = new BookingFoodItem();
        bfi.setBooking(b);
        bfi.setItemType("FOOD");
        bfi.setProductNameSnapshot("Caramel Popcorn");
        bfi.setQuantity(2);
        bfi.setUnitPriceSnapshot(BigDecimal.valueOf(50000));
        bfi.setSubtotal(BigDecimal.valueOf(100000));
        bfi.setNetPaidAmount(BigDecimal.valueOf(100000));
        bookingFoodItemRepository.save(bfi);

        FoodFulfillment ff = new FoodFulfillment(b);
        ff.setCinema(cinema);
        ff.setStatus("QUEUED");
        foodFulfillmentRepository.save(ff);
        return b;
    }

    private void recordPaidPayment(Booking b) {
        Payment p = new Payment();
        p.setBooking(b);
        p.setOrderCode(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 9_000_000_000L + 100_000L);
        p.setAmount(b.getTotalAmount());
        p.setIdempotencyKey("IDEMP-RFDIT-" + UUID.randomUUID());
        p.setPaymentStatus("PAID");
        p.setPaidAt(LocalDateTime.now().minusMinutes(10));
        paymentRepository.save(p);
    }
}
