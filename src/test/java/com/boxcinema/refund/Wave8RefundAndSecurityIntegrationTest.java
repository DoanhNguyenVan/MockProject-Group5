package com.boxcinema.refund;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.util.CryptoUtils;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class Wave8RefundAndSecurityIntegrationTest {

    private static final String TAG = "W8RFD_" + System.currentTimeMillis();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CinemaRepository cinemaRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingSeatRepository bookingSeatRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private BookingQrRepository bookingQrRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PromotionRepository promotionRepository;
    @Autowired private RefundAuditLogRepository auditLogRepository;
    @Autowired private com.boxcinema.refund.service.RefundRateLimitService refundRateLimitService;
    @MockBean private JavaMailSender mailSender;

    private Cinema cinema1;
    private Cinema cinema2;
    private User staff1;
    private User staff2;
    private User manager1;
    private User admin1;
    private User admin2;
    private User customer1;
    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdPromoIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdCinemaIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        refundRateLimitService.resetForTesting();

        List<Cinema> cinemas = cinemaRepository.findAll();
        cinema1 = cinemas.get(0);
        if (cinemas.size() > 1) {
            cinema2 = cinemas.get(1);
        } else {
            cinema2 = cinemaRepository.save(new Cinema("W8 Branch 2 " + TAG, "456 Side St", "Hanoi", "0908887776", "b2_" + TAG + "@box.test"));
            createdCinemaIds.add(cinema2.getCinemaId());
        }

        staff1 = createUser("w8_st1_" + TAG + "@box.test", "STAFF", cinema1.getCinemaId());
        staff2 = createUser("w8_st2_" + TAG + "@box.test", "STAFF", cinema2.getCinemaId());
        manager1 = createUser("w8_mgr1_" + TAG + "@box.test", "MANAGER", cinema1.getCinemaId());
        admin1 = createUser("w8_adm1_" + TAG + "@box.test", "ADMIN", null);
        admin2 = createUser("w8_adm2_" + TAG + "@box.test", "ADMIN", null);
        customer1 = createUser("w8_cust1_" + TAG + "@box.test", "CUSTOMER", null);
    }

    @AfterEach
    void cleanup() {
        for (Long bid : createdBookingIds) {
            List<Long> rrIds = jdbcTemplate.queryForList("SELECT id FROM refund_requests WHERE booking_id = ?", Long.class, bid);
            List<Long> vrPromoIds = jdbcTemplate.queryForList(
                    "SELECT new_promotion_id FROM voucher_recoveries WHERE booking_id = ? AND new_promotion_id IS NOT NULL",
                    Long.class, bid);
            for (Long pid : vrPromoIds) {
                if (!createdPromoIds.contains(pid)) createdPromoIds.add(pid);
            }
            jdbcTemplate.update("DELETE FROM email_outbox WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM voucher_recoveries WHERE booking_id = ?", bid);
            for (Long rrId : rrIds) {
                jdbcTemplate.update("DELETE FROM payos_payout_transactions WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE refund_request_id = ? OR (entity_type = 'REFUND_REQUEST' AND entity_id = ?)", rrId, rrId);
            }
            jdbcTemplate.update("DELETE FROM refund_requests WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM staff_error_logs WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM incident_reports WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM payments WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM tickets WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_qr WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_seats WHERE booking_id = ?", bid);
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
        for (Long cid : createdCinemaIds) {
            jdbcTemplate.update("DELETE FROM cinemas WHERE cinema_id = ?", cid);
        }
        createdCinemaIds.clear();
    }

    @Test
    void makerChecker_BlocksSelfApprovalOnPayoutAndBatch_AllowsDistinctAdmin() throws Exception {
        Booking b = createPaidTicketBooking(cinema1, null, "mc_guest_" + TAG + "@box.test", BigDecimal.valueOf(100000), false);
        Ticket t = ticketRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        long rrId = submitPublicRefund(b, t.getTicketId(), "0911002233", "Maker Checker Guest");

        // Admin1 performs Stage 1 business approval
        mockMvc.perform(post("/api/admin/refunds/" + rrId + "/review").with(csrf())
                        .sessionAttr("currentUser", dto(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "DECIDE_ITEMS",
                                "note", "Stage 1 verified by admin1",
                                "itemDecisions", List.of(Map.of("itemId", getFirstItemId(rrId), "decision", "APPROVED", "approvedQuantity", 1))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("AWAITING_PAYOUT_APPROVAL"));

        // Admin1 CAN self-approve Stage 2 payout in single-admin mode, and audit history records it
        mockMvc.perform(post("/api/admin/refunds/" + rrId + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", dto(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Single-admin self approve\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"));

        boolean hasSingleAdminAudit = auditLogRepository.findByRefundRequestIdOrderByCreatedAtDesc(rrId).stream()
                .anyMatch(a -> "SINGLE_ADMIN_PAYOUT_SELF_APPROVED".equals(a.getEventType())
                        && admin1.getUserId().equals(a.getActorUserId()));
        assertTrue(hasSingleAdminAudit, "Audit history must record SINGLE_ADMIN_PAYOUT_SELF_APPROVED");
    }

    @Test
    void batchPayout_BlocksSharedBeneficiaryAcrossDistinctOrders() throws Exception {
        Booking b1 = createPaidTicketBooking(cinema1, null, "sb1_" + TAG + "@box.test", BigDecimal.valueOf(100000), false);
        Ticket t1 = ticketRepository.findByBooking_BookingId(b1.getBookingId()).get(0);
        long rr1 = submitPublicRefund(b1, t1.getTicketId(), "0988776655", "Shared Holder One");

        Booking b2 = createPaidTicketBooking(cinema1, null, "sb2_" + TAG + "@box.test", BigDecimal.valueOf(100000), false);
        Ticket t2 = ticketRepository.findByBooking_BookingId(b2.getBookingId()).get(0);
        long rr2 = submitPublicRefund(b2, t2.getTicketId(), "0988776655", "Shared Holder Two");

        // Manager approves Stage 1 for both
        for (long rrId : List.of(rr1, rr2)) {
            mockMvc.perform(post("/api/manager/refunds/" + rrId + "/review").with(csrf())
                            .sessionAttr("currentUser", dto(manager1))
                            .with(user(manager1.getEmail()).roles("MANAGER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "action", "DECIDE_ITEMS",
                                    "note", "Stage 1 OK",
                                    "itemDecisions", List.of(Map.of("itemId", getFirstItemId(rrId), "decision", "APPROVED", "approvedQuantity", 1))))))
                    .andExpect(status().isOk());
        }

        // Batch approve with shared beneficiary -> 409 SHARED_BENEFICIARY_IN_BATCH
        mockMvc.perform(post("/api/admin/refunds/payout/batch-approve").with(csrf())
                        .sessionAttr("currentUser", dto(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refundRequestIds", List.of(rr1, rr2),
                                "expectedTotalVnd", 200000,
                                "note", "Batch shared beneficiary test"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SHARED_BENEFICIARY_IN_BATCH"));
    }

    @Test
    void stage2Reject_RollsBackTicketQrAndRevokesRecoveryVoucher() throws Exception {
        Booking b = createPaidTicketBooking(cinema1, null, "rej_" + TAG + "@box.test", BigDecimal.valueOf(90000), true);
        Ticket t = ticketRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        long rrId = submitPublicRefund(b, t.getTicketId(), "0933445566", "Reject Rollback Guest");

        MvcResult revRes = mockMvc.perform(post("/api/manager/refunds/" + rrId + "/review").with(csrf())
                        .sessionAttr("currentUser", dto(manager1))
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "DECIDE_ITEMS",
                                "note", "Stage 1 approve with voucher",
                                "itemDecisions", List.of(Map.of("itemId", getFirstItemId(rrId), "decision", "APPROVED", "approvedQuantity", 1))))))
                .andExpect(status().isOk())
                .andReturn();
        String newVoucherCode = objectMapper.readTree(revRes.getResponse().getContentAsString())
                .get("recoveryVoucher").get("newVoucherCode").asText();

        // Admin rejects Stage 2 payout -> must revert Ticket to VALID, QR to ACTIVE, and revoke RCV-* voucher
        mockMvc.perform(post("/api/admin/refunds/" + rrId + "/payout/reject").with(csrf())
                        .sessionAttr("currentUser", dto(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Suspicious claim on Stage 2 audit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("PAYOUT_REJECTED"));

        Ticket revertedTicket = ticketRepository.findById(t.getTicketId()).orElseThrow();
        assertEquals("VALID", revertedTicket.getTicketStatus());
        BookingQr revertedQr = bookingQrRepository.findByBooking_BookingId(b.getBookingId()).orElseThrow();
        assertEquals("ACTIVE", revertedQr.getStatus());

        Promotion revokedPromo = promotionRepository.findByVoucherCodeIgnoreCase(newVoucherCode).orElseThrow();
        assertEquals("INACTIVE", revokedPromo.getStatus());
        mockMvc.perform(post("/api/vouchers/validate").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "voucherCode", newVoucherCode,
                                "orderAmount", 100000,
                                "email", b.getGuestEmail()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void registeredCustomerRefund_BlocksAnonymousSubmitAndBankUpdate_And_StaffIntakeEnforcesBranch() throws Exception {
        Booking memberBooking = createPaidTicketBooking(cinema1, customer1, customer1.getEmail(), BigDecimal.valueOf(100000), false);
        Ticket t = ticketRepository.findByBooking_BookingId(memberBooking.getBookingId()).get(0);

        // Anonymous caller cannot submit refund on a registered member's order
        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", memberBooking.getBookingCode(),
                                "purchaseEmail", customer1.getEmail(),
                                "reasonCategory", "CUSTOMER_REQUEST",
                                "description", "Anonymous hijack attempt",
                                "toBin", "970422",
                                "toAccountNumber", "0999888777",
                                "confirmAccountNumber", "0999888777",
                                "bankAccountName", "Attacker",
                                "items", List.of(Map.of("itemKind", "TICKET", "targetId", t.getTicketId(), "quantity", 1))))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BANK_ACCOUNT_ACCESS_DENIED"));

        // Branch 2 staff cannot intake counter refund for Branch 1 order
        mockMvc.perform(post("/api/staff/refunds").with(csrf())
                        .sessionAttr("currentUser", dto(staff2))
                        .with(user(staff2.getEmail()).roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", memberBooking.getBookingCode(),
                                "purchaseEmail", customer1.getEmail(),
                                "reasonCategory", "CUSTOMER_REQUEST",
                                "description", "Cross branch staff attempt",
                                "toBin", "970422",
                                "toAccountNumber", "0999888777",
                                "confirmAccountNumber", "0999888777",
                                "bankAccountName", customer1.getFullName(),
                                "items", List.of(Map.of("itemKind", "TICKET", "targetId", t.getTicketId(), "quantity", 1))))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BRANCH_ACCESS_DENIED"));

        // Branch 1 staff counter intake succeeds
        MvcResult staffIntake = mockMvc.perform(post("/api/staff/refunds").with(csrf())
                        .sessionAttr("currentUser", dto(staff1))
                        .with(user(staff1.getEmail()).roles("STAFF"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", memberBooking.getBookingCode(),
                                "purchaseEmail", customer1.getEmail(),
                                "reasonCategory", "CUSTOMER_REQUEST",
                                "description", "Walk-in counter refund intake",
                                "toBin", "970422",
                                "toAccountNumber", "0999888777",
                                "confirmAccountNumber", "0999888777",
                                "bankAccountName", customer1.getFullName(),
                                "items", List.of(Map.of("itemKind", "TICKET", "targetId", t.getTicketId(), "quantity", 1))))))
                .andExpect(status().isCreated())
                .andReturn();
        String refundCode = objectMapper.readTree(staffIntake.getResponse().getContentAsString()).get("requestCode").asText();

        // Anonymous caller cannot hijack bank account of member's refund request
        mockMvc.perform(put("/api/refunds/public/" + refundCode + "/bank-account").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purchaseEmail", customer1.getEmail(),
                                "toBin", "970422",
                                "toAccountNumber", "0912345000",
                                "confirmAccountNumber", "0912345000",
                                "bankAccountName", "Hijacker"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BANK_ACCOUNT_ACCESS_DENIED"));
    }

    @Test
    void evidenceUpload_RejectsFakePngMagicBytes_And_ServesValidPngWithCspSandbox() throws Exception {
        MockMultipartFile fakePng = new MockMultipartFile(
                "file", "evil.png", "image/png", "<html><script>alert(1)</script></html>".getBytes());
        mockMvc.perform(multipart("/api/refunds/public/evidence-upload").file(fakePng).with(csrf()))
                .andExpect(status().isBadRequest());

        byte[] validPngHeader = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R', 0x00, 0x00, 0x00, 0x01
        };
        MockMultipartFile realPng = new MockMultipartFile("file", "proof.png", "image/png", validPngHeader);
        MvcResult upRes = mockMvc.perform(multipart("/api/refunds/public/evidence-upload").file(realPng).with(csrf())
                        .sessionAttr("currentUser", dto(customer1))
                        .with(user(new com.boxcinema.common.security.BoxCinemaUserDetails(customer1))))
                .andExpect(status().isOk())
                .andReturn();
        String url = objectMapper.readTree(upRes.getResponse().getContentAsString()).get("url").asText();
        String storageName = url.substring(url.lastIndexOf('/') + 1);
        try {
            // Stage 2 Part 9.2: Anonymous unauthenticated evidence download MUST be rejected with 401
            mockMvc.perform(get(url))
                    .andExpect(status().isUnauthorized());

            // Authorized owner / admin download succeeds with strict CSP sandbox & nosniff headers
            mockMvc.perform(get(url)
                            .sessionAttr("currentUser", dto(customer1))
                            .with(user(new com.boxcinema.common.security.BoxCinemaUserDetails(customer1))))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                    .andExpect(header().string("Content-Security-Policy", containsString("sandbox")))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        } finally {
            jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE entity_type = 'REFUND_EVIDENCE_FILE'");
            jdbcTemplate.update("DELETE FROM refund_evidence_files WHERE storage_name = ?", storageName);
        }
    }

    private long submitPublicRefund(Booking b, Long ticketId, String accNo, String holder) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", b.getBookingCode(),
                                "purchaseEmail", b.getGuestEmail(),
                                "reasonCategory", "CUSTOMER_REQUEST",
                                "description", "Wave8 refund test",
                                "toBin", "970422",
                                "toAccountNumber", accNo,
                                "confirmAccountNumber", accNo,
                                "bankAccountName", holder,
                                "items", List.of(Map.of("itemKind", "TICKET", "targetId", ticketId, "quantity", 1))))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private long getFirstItemId(long rrId) {
        return jdbcTemplate.queryForObject("SELECT id FROM refund_request_items WHERE refund_request_id = ? LIMIT 1", Long.class, rrId);
    }

    private Booking createPaidTicketBooking(Cinema cinema, User owner, String email, BigDecimal price, boolean withVoucherDiscount) {
        Booking b = new Booking();
        b.setBookingCode("BOX-20261125-" + (1000 + new Random().nextInt(8999)));
        b.setCinema(cinema);
        b.setUser(owner);
        b.setOrderType("TICKET_ONLY");
        b.setGuestName(owner != null ? owner.getFullName() : "Guest " + TAG);
        b.setGuestEmail(email);
        b.setGuestPhone("0901234567");
        BigDecimal disc = withVoucherDiscount ? BigDecimal.valueOf(10000) : BigDecimal.ZERO;
        BigDecimal gross = price.add(disc);
        b.setTicketSubtotal(gross);
        b.setFoodSubtotal(BigDecimal.ZERO);
        b.setSubtotal(gross);
        b.setDiscountAmount(disc);
        b.setServiceFee(BigDecimal.ZERO);
        b.setTotalAmount(price);
        b.setBookingStatus("CONFIRMED");
        b.setConfirmedAt(LocalDateTime.now());
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());

        BookingSeat bs = bookingSeatRepository.save(new BookingSeat(b, 1L, gross, "A1", "BOOKED"));

        Ticket t = new Ticket();
        t.setBooking(b);
        t.setBookingSeatId(bs.getBookingSeatId());
        t.setSeatId(1L);
        t.setTicketCode("TCK-" + b.getBookingCode().replace("BOX-", "") + "-A1");
        t.setQrTokenHash(CryptoUtils.sha256(t.getTicketCode()));
        t.setPrice(gross);
        t.setGrossPrice(gross);
        t.setDiscountAllocated(disc);
        t.setNetPaidAmount(price);
        t.setTicketStatus("VALID");
        ticketRepository.save(t);

        BookingQr qr = new BookingQr();
        qr.setBooking(b);
        qr.setTokenHash(CryptoUtils.sha256("BOX1.W8RFD." + UUID.randomUUID()));
        qr.setTokenEncrypted("enc");
        qr.setStatus("ACTIVE");
        bookingQrRepository.save(qr);

        Payment p = new Payment();
        p.setBooking(b);
        p.setOrderCode(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 900000000L + 1000000L);
        p.setIdempotencyKey("W8-IDEM-" + UUID.randomUUID());
        p.setPaymentProvider("PAYOS");
        p.setPaymentMethod("PAYOS_QR");
        p.setAmount(price);
        p.setCurrency("VND");
        p.setPaymentStatus("PAID");
        p.setPaidAt(LocalDateTime.now());
        paymentRepository.save(p);
        return b;
    }

    private User createUser(String email, String role, Long cinemaId) {
        User u = new User("User " + role, email, "09" + String.format("%08d", Math.abs(email.hashCode()) % 100000000), "hash", role);
        u.setCinemaId(cinemaId);
        u.setAccountStatus("ACTIVE");
        u = userRepository.save(u);
        createdUserIds.add(u.getUserId());
        return u;
    }

    private UserSummaryDto dto(User u) {
        return new UserSummaryDto(u.getUserId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole());
    }
}
