package com.boxcinema.refund;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BankDataEncryptionService;
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
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * End-to-end integration tests for Customer Bank Account Management, Pre-Approval Update,
 * Admin Hold-for-Reverify, Multi-Customer Batch Payout Verification (17 mandatory cases),
 * plus Defect #1 (608 MANUAL_RECONCILE_REQUIRED + confirm-failed), Defect #2 (SEC-04 guest IDOR),
 * and Defect #3 (SEC-02 staff branch isolation).
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerBankAccountAndPayoutIntegrationTest {

    private static final String TEST_TAG = "CBA_PO_" + System.currentTimeMillis();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private CinemaRepository cinemaRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private ShowtimeRepository showtimeRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingSeatRepository bookingSeatRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private BookingQrRepository bookingQrRepository;
    @Autowired private BookingCheckinRepository bookingCheckinRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRequestRepository refundRequestRepository;
    @Autowired private RefundRequestItemRepository refundRequestItemRepository;
    @Autowired private PayOsPayoutTransactionRepository payoutRepository;
    @Autowired private CustomerBankAccountRepository bankAccountRepository;
    @Autowired private PurchaseSnapshotService snapshotService;
    @Autowired private PayOsPayoutService payOsPayoutService;
    @Autowired private BankDataEncryptionService bankDataEncryptionService;
    @Autowired private com.boxcinema.refund.service.RefundRateLimitService refundRateLimitService;
    @MockBean private JavaMailSender mailSender;

    @Value("${payos.payout.checksum-key:}")
    private String payoutChecksumKey;

    private Cinema cinema1;
    private Cinema cinema2;
    private User customerA;
    private User customerB;
    private User customerC;
    private User staff1;
    private User staff2;
    private User manager1;
    private User admin1;

    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdShowtimeIds = new ArrayList<>();
    private final List<Long> createdRoomIds = new ArrayList<>();
    private final List<Long> createdMovieIds = new ArrayList<>();
    private final List<Long> createdCinemaIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        refundRateLimitService.resetForTesting();
        payOsPayoutService.setCustomTransportExecutor(null);
        ReflectionTestUtils.setField(payOsPayoutService, "mode", "MOCK");
        ReflectionTestUtils.setField(payOsPayoutService, "allowAutoFailOnNotFound", false);
        ReflectionTestUtils.setField(payOsPayoutService, "notFoundGraceSeconds", 120);

        List<Cinema> cinemas = cinemaRepository.findAll();
        cinema1 = cinemas.get(0);
        if (cinemas.size() > 1) {
            cinema2 = cinemas.get(1);
        } else {
            Cinema extra = new Cinema("Branch 2 " + TEST_TAG, "789 Side St", "Hanoi", "0909998877", "b2_" + TEST_TAG + "@box.test");
            cinema2 = cinemaRepository.save(extra);
            createdCinemaIds.add(cinema2.getCinemaId());
        }

        String sfx = TEST_TAG + "_" + UUID.randomUUID().toString().substring(0, 6);
        customerA = ensureUser("cba_custA_" + sfx + "@box.test", "CUSTOMER", null, "Customer Alpha");
        customerB = ensureUser("cba_custB_" + sfx + "@box.test", "CUSTOMER", null, "Customer Beta");
        customerC = ensureUser("cba_custC_" + sfx + "@box.test", "CUSTOMER", null, "Customer Gamma");
        staff1 = ensureUser("cba_stf1_" + sfx + "@box.test", "STAFF", cinema1.getCinemaId(), "Staff One");
        staff2 = ensureUser("cba_stf2_" + sfx + "@box.test", "STAFF", cinema2.getCinemaId(), "Staff Two");
        manager1 = ensureUser("cba_mgr1_" + sfx + "@box.test", "MANAGER", cinema1.getCinemaId(), "Manager One");
        admin1 = ensureUser("cba_adm1_" + sfx + "@box.test", "ADMIN", null, "Admin One");
    }

    @AfterEach
    void cleanup() {
        payOsPayoutService.setCustomTransportExecutor(null);
        ReflectionTestUtils.setField(payOsPayoutService, "mode", "MOCK");
        ReflectionTestUtils.setField(payOsPayoutService, "allowAutoFailOnNotFound", false);
        ReflectionTestUtils.setField(payOsPayoutService, "notFoundGraceSeconds", 120);

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
            jdbcTemplate.update("DELETE FROM booking_checkin WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_qr WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM payments WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM tickets WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM bookings WHERE booking_id = ?", bid);
        }
        createdBookingIds.clear();

        for (Long stId : createdShowtimeIds) {
            jdbcTemplate.update("DELETE FROM showtime_seats WHERE showtime_id = ?", stId);
            jdbcTemplate.update("DELETE FROM showtimes WHERE showtime_id = ?", stId);
        }
        createdShowtimeIds.clear();

        for (Long rId : createdRoomIds) {
            jdbcTemplate.update("DELETE FROM seats WHERE room_id = ?", rId);
            jdbcTemplate.update("DELETE FROM rooms WHERE room_id = ?", rId);
        }
        createdRoomIds.clear();

        for (Long mId : createdMovieIds) {
            jdbcTemplate.update("DELETE FROM movies WHERE movie_id = ?", mId);
        }
        createdMovieIds.clear();

        for (Long uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM customer_bank_accounts WHERE user_id = ?", uid);
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
    void cases1to4_ValidationConfirmSaveAndReuseBankAccount() throws Exception {
        Booking b1 = createConfirmedTicketBooking(cinema1, customerA, customerA.getEmail(), 100000L, true);
        Ticket t1 = ticketRepository.findByBooking_BookingId(b1.getBookingId()).get(0);

        // Case 2: Invalid format (unsupported BIN, short/non-digit account, invalid symbol-only holder) -> 400
        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundPayload(b1.getBookingCode(), customerA.getEmail(), t1.getTicketId(),
                                "999999", "0912345678", "0912345678", "NGUYEN VAN A", null, false, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_BANK_INFO"));

        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundPayload(b1.getBookingCode(), customerA.getEmail(), t1.getTicketId(),
                                "970422", "123", "123", "NGUYEN VAN A", null, false, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_BANK_INFO"));

        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundPayload(b1.getBookingCode(), customerA.getEmail(), t1.getTicketId(),
                                "970422", "09AB345678", "09AB345678", "NGUYEN VAN A", null, false, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_BANK_INFO"));

        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundPayload(b1.getBookingCode(), customerA.getEmail(), t1.getTicketId(),
                                "970422", "0912345678", "0912345678", "!!!---", null, false, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_HOLDER_REQUIRED"));

        // Case 3: Missing bank BIN, account, or holder -> 400
        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundPayload(b1.getBookingCode(), customerA.getEmail(), t1.getTicketId(),
                                "", "0912345678", "0912345678", "NGUYEN VAN A", null, false, null)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundPayload(b1.getBookingCode(), customerA.getEmail(), t1.getTicketId(),
                                "970422", "", "", "NGUYEN VAN A", null, false, null)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundPayload(b1.getBookingCode(), customerA.getEmail(), t1.getTicketId(),
                                "970422", "0912345678", "0912345678", "", null, false, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_HOLDER_REQUIRED"));

        // Case 4: Mismatched confirmAccountNumber -> 400 ACCOUNT_NUMBER_CONFIRM_MISMATCH
        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundPayload(b1.getBookingCode(), customerA.getEmail(), t1.getTicketId(),
                                "970422", "0912345678", "0912345679", "NGUYEN VAN A", null, false, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NUMBER_CONFIRM_MISMATCH"));

        // Also verify confirm mismatch on /api/customer/bank-accounts
        mockMvc.perform(post("/api/customer/bank-accounts").with(csrf())
                        .sessionAttr("currentUser", dtoOf(customerA))
                        .with(user(customerA.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankBin", "970436",
                                "accountNumber", "1029384756",
                                "confirmAccountNumber", "1029384757",
                                "accountHolderName", "Customer Alpha"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NUMBER_CONFIRM_MISMATCH"));

        // Case 1: Customer enters valid bank details (with confirm + holder + note + saveBankAccount=true)
        String rawAccountA = "0987654321";
        MvcResult createRes = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .sessionAttr("currentUser", dtoOf(customerA))
                        .with(user(customerA.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundPayload(b1.getBookingCode(), customerA.getEmail(), t1.getTicketId(),
                                "970422", rawAccountA, rawAccountA, "Nguyễn Văn Alpha", "Chi nhánh Hoàn Kiếm", true, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.toAccountNumberMasked").value("******4321"))
                .andExpect(jsonPath("$.bankNote").value("Chi nhánh Hoàn Kiếm"))
                .andExpect(jsonPath("$.bankAccountEditable").value(true))
                .andExpect(jsonPath("$.savedBankAccountId").isNumber())
                .andExpect(content().string(not(containsString(rawAccountA))))
                .andReturn();

        JsonNode created = objectMapper.readTree(createRes.getResponse().getContentAsString());
        long savedId = created.get("savedBankAccountId").asLong();

        // Verify saved account appears masked in GET /api/customer/bank-accounts & can be reused via savedBankAccountId
        MvcResult listRes = mockMvc.perform(get("/api/customer/bank-accounts")
                        .sessionAttr("currentUser", dtoOf(customerA))
                        .with(user(customerA.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(savedId))
                .andExpect(jsonPath("$[0].accountNumberMasked").value("******4321"))
                .andExpect(jsonPath("$[0].accountHolderName").value("NGUYEN VAN ALPHA"))
                .andExpect(content().string(not(containsString(rawAccountA))))
                .andReturn();
        assertEquals(1, objectMapper.readTree(listRes.getResponse().getContentAsString()).size());

        Booking b2 = createConfirmedTicketBooking(cinema1, customerA, customerA.getEmail(), 90000L, true);
        Ticket t2 = ticketRepository.findByBooking_BookingId(b2.getBookingId()).get(0);
        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .sessionAttr("currentUser", dtoOf(customerA))
                        .with(user(customerA.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundPayload(b2.getBookingCode(), customerA.getEmail(), t2.getTicketId(),
                                null, null, null, null, null, false, savedId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.savedBankAccountId").value(savedId))
                .andExpect(jsonPath("$.toAccountNumberMasked").value("******4321"))
                .andExpect(content().string(not(containsString(rawAccountA))));
    }

    @Test
    void case5_PreApprovalUpdate_PostApprovalLock_And_AdminHoldForReverifyCycle() throws Exception {
        Booking b = createConfirmedTicketBooking(cinema1, customerA, customerA.getEmail(), 100000L, true);
        long rrId = submitPublicRefund(b, customerA, "970422", "0911223344", "Alpha One", "Old note");
        RefundRequest rr = refundRequestRepository.findById(rrId).orElseThrow();
        String code = rr.getRequestCode();

        // Pre-approval bank update -> 200 OK, masked, audit logged
        String newRawAcc = "1900887766";
        mockMvc.perform(put("/api/refunds/public/" + code + "/bank-account").with(csrf())
                        .sessionAttr("currentUser", dtoOf(customerA))
                        .with(user(customerA.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purchaseEmail", customerA.getEmail(),
                                "toBin", "970436",
                                "toAccountNumber", newRawAcc,
                                "confirmAccountNumber", newRawAcc,
                                "bankAccountName", "Alpha Updated",
                                "bankNote", "VCB Ha Noi"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toBin").value("970436"))
                .andExpect(jsonPath("$.toAccountNumberMasked").value("******7766"))
                .andExpect(jsonPath("$.bankNote").value("VCB Ha Noi"))
                .andExpect(jsonPath("$.bankAccountEditable").value(true))
                .andExpect(content().string(not(containsString(newRawAcc))));

        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_audit_logs WHERE refund_request_id = ? AND event_type = 'REFUND_BANK_ACCOUNT_UPDATED'",
                Integer.class, rrId);
        assertEquals(1, auditCount);

        // Manager approves business stage -> AWAITING_PAYOUT_APPROVAL, bankAccountEditable=false
        approveBusinessStage(rrId);

        mockMvc.perform(get("/api/refunds/public/lookup")
                        .param("refundCode", code)
                        .param("email", customerA.getEmail()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bankAccountEditable").value(false));

        // Post-approval bank update blocked with 409 BANK_ACCOUNT_LOCKED_AFTER_APPROVAL
        mockMvc.perform(put("/api/refunds/public/" + code + "/bank-account").with(csrf())
                        .sessionAttr("currentUser", dtoOf(customerA))
                        .with(user(customerA.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purchaseEmail", customerA.getEmail(),
                                "toBin", "970407",
                                "toAccountNumber", "9988776655",
                                "confirmAccountNumber", "9988776655",
                                "bankAccountName", "Should Fail"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("BANK_ACCOUNT_LOCKED_AFTER_APPROVAL"));

        // Admin hold-for-reverify requires reason -> 400 when blank, 200 unlocks to MORE_INFO_REQUIRED
        mockMvc.perform(post("/api/admin/refunds/" + rrId + "/payout/hold-for-reverify").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("HOLD_REASON_REQUIRED"));

        mockMvc.perform(post("/api/admin/refunds/" + rrId + "/payout/hold-for-reverify").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Customer entered wrong account name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("MORE_INFO_REQUIRED"))
                .andExpect(jsonPath("$.payoutStatus").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.bankAccountEditable").value(true));

        // Customer updates bank cleanly after hold-for-reverify -> Manager re-verifies -> Admin re-approves
        String finalAcc = "5544332211";
        mockMvc.perform(put("/api/refunds/public/" + code + "/bank-account").with(csrf())
                        .sessionAttr("currentUser", dtoOf(customerA))
                        .with(user(customerA.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purchaseEmail", customerA.getEmail(),
                                "toBin", "970407",
                                "toAccountNumber", finalAcc,
                                "confirmAccountNumber", finalAcc,
                                "bankAccountName", "Alpha Reverified",
                                "bankNote", "TCB Reverified"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toAccountNumberMasked").value("******2211"));

        approveBusinessStage(rrId);
        mockMvc.perform(post("/api/admin/refunds/" + rrId + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.payoutStatus").value("SUCCEEDED"));
    }

    @Test
    void case6and7and8_IdorAuthZ_SavedAccounts_StaffBranchIsolation_And_ManagerCannotPayout() throws Exception {
        // Unauthenticated -> 401 on saved bank accounts
        mockMvc.perform(get("/api/customer/bank-accounts")).andExpect(status().isUnauthorized());

        // Customer B creates a saved bank account
        MvcResult createB = mockMvc.perform(post("/api/customer/bank-accounts").with(csrf())
                        .sessionAttr("currentUser", dtoOf(customerB))
                        .with(user(customerB.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bankBin", "970416",
                                "accountNumber", "2233445566",
                                "confirmAccountNumber", "2233445566",
                                "accountHolderName", "Customer Beta",
                                "note", "Beta ACB",
                                "isDefault", true))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumberMasked").value("******5566"))
                .andReturn();
        long accBId = objectMapper.readTree(createB.getResponse().getContentAsString()).get("id").asLong();

        // Case 6: Customer A tries GET/PUT/POST default/DELETE on Customer B's saved account -> 403
        mockMvc.perform(get("/api/customer/bank-accounts/" + accBId)
                        .sessionAttr("currentUser", dtoOf(customerA))
                        .with(user(customerA.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BANK_ACCOUNT_ACCESS_DENIED"));

        mockMvc.perform(put("/api/customer/bank-accounts/" + accBId).with(csrf())
                        .sessionAttr("currentUser", dtoOf(customerA))
                        .with(user(customerA.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Hijacked\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BANK_ACCOUNT_ACCESS_DENIED"));

        mockMvc.perform(post("/api/customer/bank-accounts/" + accBId + "/default").with(csrf())
                        .sessionAttr("currentUser", dtoOf(customerA))
                        .with(user(customerA.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BANK_ACCOUNT_ACCESS_DENIED"));

        mockMvc.perform(delete("/api/customer/bank-accounts/" + accBId).with(csrf())
                        .sessionAttr("currentUser", dtoOf(customerA))
                        .with(user(customerA.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BANK_ACCOUNT_ACCESS_DENIED"));

        // Customer A tries using Customer B's savedBankAccountId on refund submit -> 403
        Booking bA = createConfirmedTicketBooking(cinema1, customerA, customerA.getEmail(), 100000L, true);
        Ticket tA = ticketRepository.findByBooking_BookingId(bA.getBookingId()).get(0);
        mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .sessionAttr("currentUser", dtoOf(customerA))
                        .with(user(customerA.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundPayload(bA.getBookingCode(), customerA.getEmail(), tA.getTicketId(),
                                null, null, null, null, null, false, accBId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BANK_ACCOUNT_ACCESS_DENIED"));

        // Customer A tries PUT /api/refunds/public/{code}/bank-account on Customer B's refund -> 403
        Booking bB = createConfirmedTicketBooking(cinema1, customerB, customerB.getEmail(), 100000L, true);
        long rrBId = submitPublicRefund(bB, customerB, "970416", "2233445566", "Customer Beta", "Beta note");
        String codeB = refundRequestRepository.findById(rrBId).orElseThrow().getRequestCode();
        mockMvc.perform(put("/api/refunds/public/" + codeB + "/bank-account").with(csrf())
                        .sessionAttr("currentUser", dtoOf(customerA))
                        .with(user(customerA.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purchaseEmail", customerB.getEmail(),
                                "toBin", "970422",
                                "toAccountNumber", "0911111111",
                                "confirmAccountNumber", "0911111111",
                                "bankAccountName", "Attacker"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BANK_ACCOUNT_ACCESS_DENIED"));

        // Case 7: Staff tries Customer B's saved bank account & Admin payout endpoints -> 403
        mockMvc.perform(get("/api/customer/bank-accounts/" + accBId)
                        .sessionAttr("currentUser", dtoOf(staff1))
                        .with(user(staff1.getEmail()).roles("STAFF")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/refunds/" + rrBId + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(staff1))
                        .with(user(staff1.getEmail()).roles("STAFF")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/payouts")
                        .sessionAttr("currentUser", dtoOf(staff1))
                        .with(user(staff1.getEmail()).roles("STAFF")))
                .andExpect(status().isForbidden());

        // Case 7 (SEC-02): Staff branch isolation on /api/staff/bookings/{id} & /api/staff/check-ins
        Booking bBranch2 = createConfirmedTicketBooking(cinema2, customerC, customerC.getEmail(), 100000L, true);
        BookingCheckin chk1 = bookingCheckinRepository.save(new BookingCheckin(bA, staff1, "SUCCESS"));
        BookingCheckin chk2 = bookingCheckinRepository.save(new BookingCheckin(bBranch2, staff2, "SUCCESS"));

        mockMvc.perform(get("/api/staff/bookings/" + bBranch2.getBookingId())
                        .sessionAttr("currentUser", dtoOf(staff1))
                        .with(user(staff1.getEmail()).roles("STAFF")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BRANCH_ACCESS_DENIED"));

        mockMvc.perform(get("/api/staff/bookings/" + bA.getBookingId())
                        .sessionAttr("currentUser", dtoOf(staff1))
                        .with(user(staff1.getEmail()).roles("STAFF")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bA.getBookingId()));

        MvcResult staff1Checkins = mockMvc.perform(get("/api/staff/check-ins")
                        .sessionAttr("currentUser", dtoOf(staff1))
                        .with(user(staff1.getEmail()).roles("STAFF")))
                .andExpect(status().isOk())
                .andReturn();
        String s1Json = staff1Checkins.getResponse().getContentAsString();
        assertTrue(s1Json.contains(bA.getBookingCode()));
        assertFalse(s1Json.contains(bBranch2.getBookingCode()));

        // Case 8: Manager approves business stage (AWAITING_PAYOUT_APPROVAL), cannot execute payout -> 403
        approveBusinessStage(rrBId);
        assertEquals("AWAITING_PAYOUT_APPROVAL", refundRequestRepository.findById(rrBId).orElseThrow().getRequestStatus());
        mockMvc.perform(post("/api/admin/refunds/" + rrBId + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(manager1))
                        .with(user(manager1.getEmail()).roles("MANAGER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void cases9to12_SingleBatchMultiCustomerPayloadIsolation_And_MixedPartialSuccess() throws Exception {
        // Case 9: Admin single payout approval
        Booking bSingle = createConfirmedTicketBooking(cinema1, customerA, customerA.getEmail(), 80000L, true);
        long rrSingle = submitPublicRefund(bSingle, customerA, "970422", "0900111222", "Single Alpha", "Single note");
        approveBusinessStage(rrSingle);

        mockMvc.perform(post("/api/admin/refunds/" + rrSingle + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.payoutStatus").value("SUCCEEDED"));

        // Cases 10 & 11: Multi-customer batch payout (3 distinct customers with 3 different banks/accounts)
        // Inspect captured outbound PayOS HTTP POST /v1/payouts payloads proving exact per-customer routing!
        Booking b1 = createConfirmedTicketBooking(cinema1, customerA, customerA.getEmail(), 100000L, true);
        Booking b2 = createConfirmedTicketBooking(cinema1, customerB, customerB.getEmail(), 120000L, true);
        Booking b3 = createConfirmedTicketBooking(cinema1, customerC, customerC.getEmail(), 150000L, true);

        long rr1 = submitPublicRefund(b1, customerA, "970422", "1111222233", "Customer Alpha", "MB Bank");
        long rr2 = submitPublicRefund(b2, customerB, "970436", "4444555566", "Customer Beta", "VCB Bank");
        long rr3 = submitPublicRefund(b3, customerC, "970418", "7777888899", "Customer Gamma", "BIDV Bank");
        approveBusinessStage(rr1);
        approveBusinessStage(rr2);
        approveBusinessStage(rr3);

        List<Map<String, Object>> capturedPosts = Collections.synchronizedList(new ArrayList<>());
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                capturedPosts.add(new LinkedHashMap<>(bodyMap));
                return buildMockPayoutOk(bodyMap);
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                throw new UnsupportedOperationException("Not expected");
            }
        });

        MvcResult batchRes = mockMvc.perform(post("/api/admin/refunds/payout/batch-approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refundRequestIds", List.of(rr1, rr2, rr3),
                                "expectedTotalCash", 370000,
                                "note", "Multi-customer batch"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.succeededCount").value(3))
                .andExpect(jsonPath("$.failedCount").value(0))
                .andReturn();

        assertEquals(3, capturedPosts.size(), "Each customer refund must emit its own PayOS POST /v1/payouts payload");
        Map<String, Map<String, Object>> byRef = new HashMap<>();
        for (Map<String, Object> p : capturedPosts) {
            byRef.put(String.valueOf(p.get("referenceId")), p);
        }
        assertEquals("970422", byRef.get("RFD-" + rr1 + "-A1").get("toBin"));
        assertEquals("1111222233", byRef.get("RFD-" + rr1 + "-A1").get("toAccountNumber"));
        assertEquals(100000L, ((Number) byRef.get("RFD-" + rr1 + "-A1").get("amount")).longValue());

        assertEquals("970436", byRef.get("RFD-" + rr2 + "-A1").get("toBin"));
        assertEquals("4444555566", byRef.get("RFD-" + rr2 + "-A1").get("toAccountNumber"));
        assertEquals(120000L, ((Number) byRef.get("RFD-" + rr2 + "-A1").get("amount")).longValue());

        assertEquals("970418", byRef.get("RFD-" + rr3 + "-A1").get("toBin"));
        assertEquals("7777888899", byRef.get("RFD-" + rr3 + "-A1").get("toAccountNumber"));
        assertEquals(150000L, ((Number) byRef.get("RFD-" + rr3 + "-A1").get("amount")).longValue());

        String batchJson = batchRes.getResponse().getContentAsString();
        assertFalse(batchJson.contains("1111222233"));
        assertFalse(batchJson.contains("4444555566"));
        assertFalse(batchJson.contains("7777888899"));

        // Case 12: Mixed batch with 1 PayOS failure doesn't corrupt or rollback other customers' succeeded transfers (PARTIAL_SUCCESS)
        Booking bm1 = createConfirmedTicketBooking(cinema1, customerA, customerA.getEmail(), 70000L, true);
        Booking bm2 = createConfirmedTicketBooking(cinema1, customerB, customerB.getEmail(), 85000L, true);
        long rrm1 = submitPublicRefund(bm1, customerA, "970422", "3011223344", "Customer Alpha", null);
        long rrm2 = submitPublicRefund(bm2, customerB, "970436", "4011223344", "Customer Beta", null);
        approveBusinessStage(rrm1);
        approveBusinessStage(rrm2);

        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                String ref = String.valueOf(bodyMap.get("referenceId"));
                if (ref.equals("RFD-" + rrm2 + "-A1")) {
                    return new PayOsPayoutService.HttpOutcome(400,
                            "{\"code\":\"400\",\"desc\":\"Beneficiary account invalid\"}", null, false, null);
                }
                return buildMockPayoutOk(bodyMap);
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                throw new UnsupportedOperationException("Not expected");
            }
        });

        mockMvc.perform(post("/api/admin/refunds/payout/batch-approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "refundRequestIds", List.of(rrm1, rrm2),
                                "expectedTotalCash", 155000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("PARTIAL_SUCCESS"))
                .andExpect(jsonPath("$.succeededCount").value(1))
                .andExpect(jsonPath("$.failedCount").value(1));

        assertEquals("COMPLETED", refundRequestRepository.findById(rrm1).orElseThrow().getRequestStatus());
        assertEquals("SUCCEEDED", refundRequestRepository.findById(rrm1).orElseThrow().getPayoutStatus());
        assertEquals("PAYOUT_FAILED", refundRequestRepository.findById(rrm2).orElseThrow().getRequestStatus());
        assertEquals("FAILED", refundRequestRepository.findById(rrm2).orElseThrow().getPayoutStatus());
    }

    @Test
    void cases13to15_And_Defect1_TimeoutReconcile_608ManualConfirmFailed_WebhookReplay_And_Concurrency() throws Exception {
        // Case 13: Timeout -> UNKNOWN_OUTCOME -> reconcile recovers cleanly without duplicate transfer
        Booking bTo = createConfirmedTicketBooking(cinema1, customerA, customerA.getEmail(), 100000L, true);
        long rrTo = submitPublicRefund(bTo, customerA, "970422", "6611223344", "Timeout Customer", null);
        approveBusinessStage(rrTo);

        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(0, null, null, true, "SocketTimeoutException");
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return buildMockGetSucceeded("RFD-" + rrTo + "-A1", 100000L);
            }
        });

        mockMvc.perform(post("/api/admin/refunds/" + rrTo + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutStatus").value("PROCESSING"))
                .andExpect(jsonPath("$.latestPayout.transactionState").value("UNKNOWN_OUTCOME"));

        PayOsPayoutTransaction txTo = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrTo).get(0);
        mockMvc.perform(post("/api/admin/payouts/" + txTo.getId() + "/reconcile").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertEquals(1, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrTo).size());

        // Defect #1: LIVE 608 past grace MUST NOT auto-fail or release lock; sets MANUAL_RECONCILE_REQUIRED;
        // Admin POST /api/admin/payouts/{txId}/confirm-failed requires note and releases lock cleanly!
        Booking b608 = createConfirmedTicketBooking(cinema1, customerB, customerB.getEmail(), 95000L, true);
        long rr608 = submitPublicRefund(b608, customerB, "970436", "7711223344", "608 Customer", null);
        approveBusinessStage(rr608);

        ReflectionTestUtils.setField(payOsPayoutService, "mode", "LIVE");
        try {
            payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
                @Override
                public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                    return new PayOsPayoutService.HttpOutcome(0, null, null, true, "Read timed out");
                }
                @Override
                public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                    return new PayOsPayoutService.HttpOutcome(200,
                            "{\"code\":\"608\",\"desc\":\"Payout not found\"}", null, false, null);
                }
            });

            mockMvc.perform(post("/api/admin/refunds/" + rr608 + "/payout/approve").with(csrf())
                            .sessionAttr("currentUser", dtoOf(admin1))
                            .with(user(admin1.getEmail()).roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.payoutStatus").value("PROCESSING"));

            PayOsPayoutTransaction tx608 = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rr608).get(0);
            jdbcTemplate.update("UPDATE payos_payout_transactions SET initiated_at = DATE_SUB(NOW(), INTERVAL 30 MINUTE) WHERE id = ?", tx608.getId());

            mockMvc.perform(post("/api/admin/payouts/" + tx608.getId() + "/reconcile").with(csrf())
                            .sessionAttr("currentUser", dtoOf(admin1))
                            .with(user(admin1.getEmail()).roles("ADMIN")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PROCESSING"))
                    .andExpect(jsonPath("$.transactionState").value("MANUAL_RECONCILE_REQUIRED"));

            PayOsPayoutTransaction after608 = payoutRepository.findById(tx608.getId()).orElseThrow();
            assertEquals(rr608, after608.getActiveRequestLock(), "Lock must NOT be auto-released on LIVE 608");

            // confirm-failed rejects blank reason -> 400 CONFIRM_FAILED_REASON_REQUIRED
            mockMvc.perform(post("/api/admin/payouts/" + tx608.getId() + "/confirm-failed").with(csrf())
                            .sessionAttr("currentUser", dtoOf(admin1))
                            .with(user(admin1.getEmail()).roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"  \"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("CONFIRM_FAILED_REASON_REQUIRED"));

            // confirm-failed with verified note -> FAILED + lock released
            mockMvc.perform(post("/api/admin/payouts/" + tx608.getId() + "/confirm-failed").with(csrf())
                            .sessionAttr("currentUser", dtoOf(admin1))
                            .with(user(admin1.getEmail()).roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"Verified on my.payos.vn: no transfer recorded\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FAILED"));
            assertNull(payoutRepository.findById(tx608.getId()).orElseThrow().getActiveRequestLock());
        } finally {
            ReflectionTestUtils.setField(payOsPayoutService, "mode", "MOCK");
            payOsPayoutService.setCustomTransportExecutor(null);
        }

        // Case 14: Duplicate payout webhook replay -> idempotent
        Booking bWh = createConfirmedTicketBooking(cinema1, customerA, customerA.getEmail(), 110000L, true);
        long rrWh = submitPublicRefund(bWh, customerA, "970422", "8811223344", "Webhook Customer", null);
        approveBusinessStage(rrWh);
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(0, null, null, true, "Timeout");
            }
            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(500, "err", null, false, null);
            }
        });
        mockMvc.perform(post("/api/admin/refunds/" + rrWh + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutStatus").value("PROCESSING"));
        payOsPayoutService.setCustomTransportExecutor(null);

        String whRef = "RFD-" + rrWh + "-A1";
        Map<String, Object> whData = new LinkedHashMap<>();
        whData.put("id", "po_wh_" + rrWh);
        whData.put("referenceId", whRef);
        whData.put("approvalState", "COMPLETED");
        whData.put("transactions", List.of(Map.of(
                "id", "txn_wh_" + rrWh,
                "referenceId", whRef,
                "amount", 110000,
                "state", "SUCCEEDED")));
        String whSig = PayOsPayoutSignatureUtil.signPayload(whData, effectivePayoutKey());
        String whJson = objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", whData, "signature", whSig));

        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .header("x-signature", whSig)
                        .contentType(MediaType.APPLICATION_JSON).content(whJson))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/payments/payos/payout/webhook")
                        .header("x-signature", whSig)
                        .contentType(MediaType.APPLICATION_JSON).content(whJson))
                .andExpect(status().isOk());
        assertEquals("COMPLETED", refundRequestRepository.findById(rrWh).orElseThrow().getRequestStatus());
        assertEquals(1, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrWh).size());

        // Case 15: Concurrent double-click/duplicate payout -> single transaction only
        Booking bRace = createConfirmedTicketBooking(cinema1, customerC, customerC.getEmail(), 100000L, true);
        long rrRace = submitPublicRefund(bRace, customerC, "970418", "9911223344", "Race Customer", null);
        approveBusinessStage(rrRace);

        ExecutorService pool = Executors.newFixedThreadPool(6);
        CountDownLatch ready = new CountDownLatch(6);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mockMvc.perform(post("/api/admin/refunds/" + rrRace + "/payout/approve").with(csrf())
                                    .sessionAttr("currentUser", dtoOf(admin1))
                                    .with(user(admin1.getEmail()).roles("ADMIN")))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Integer> f : futures) {
                int st = f.get(20, TimeUnit.SECONDS);
                assertTrue(st == 200 || st == 409, "Expected 200 or 409 on concurrent payout, got " + st);
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(rrRace).size());
        assertEquals("SUCCEEDED", refundRequestRepository.findById(rrRace).orElseThrow().getPayoutStatus());
    }

    @Test
    void cases16and17_And_Defect2_EncryptionEnvKey_ZeroPlaintextLeaks_And_GuestBookingIdor() throws Exception {
        // Case 16: BankDataEncryptionService AES-256-GCM round-trip + distinct random IV under .env 32-byte BANK_DATA_ENC_KEY
        Path envPath = Path.of(".env");
        assertTrue(Files.exists(envPath), ".env must exist in workspace");
        String envKeyLine = Files.readAllLines(envPath, StandardCharsets.UTF_8).stream()
                .filter(l -> l.startsWith("BANK_DATA_ENC_KEY="))
                .findFirst()
                .orElseThrow(() -> new AssertionError("BANK_DATA_ENC_KEY missing from .env"));
        String encKeyValue = envKeyLine.substring("BANK_DATA_ENC_KEY=".length()).trim();
        byte[] decodedKey = Base64.getDecoder().decode(encKeyValue);
        assertTrue(decodedKey.length >= 32, "BANK_DATA_ENC_KEY must decode to >= 32 bytes");

        BankDataEncryptionService liveEnc = new BankDataEncryptionService();
        ReflectionTestUtils.setField(liveEnc, "payoutMode", "LIVE");
        ReflectionTestUtils.setField(liveEnc, "configuredEncryptionKey", encKeyValue);
        ReflectionTestUtils.setField(liveEnc, "devFallbackSeed", "unused");
        liveEnc.init();

        String sampleRaw = "01234567890123";
        String c1 = liveEnc.encryptAccountNumber(sampleRaw);
        String c2 = liveEnc.encryptAccountNumber(sampleRaw);
        assertTrue(c1.startsWith("GCM1:"));
        assertTrue(c2.startsWith("GCM1:"));
        assertNotEquals(c1, c2, "AES-GCM must generate distinct random 12-byte IVs");
        assertEquals(sampleRaw, liveEnc.decryptAccountNumber(c1));
        assertEquals(sampleRaw, liveEnc.decryptAccountNumber(c2));

        // Case 17: Zero plaintext bank data in API responses, audit logs, or sanitized payout responses
        Booking b = createConfirmedTicketBooking(cinema1, customerA, customerA.getEmail(), 100000L, true);
        String secretAccount = "889900112233";
        long rrId = submitPublicRefund(b, customerA, "970422", secretAccount, "Zero Leak Customer", "Check privacy");
        RefundRequest rr = refundRequestRepository.findById(rrId).orElseThrow();
        assertTrue(rr.getToAccountNumberEncrypted().startsWith("GCM1:"));
        assertEquals("********2233", rr.getToAccountNumberMasked());
        assertFalse(rr.getToAccountNumberEncrypted().contains(secretAccount));

        approveBusinessStage(rrId);
        MvcResult payoutRes = mockMvc.perform(post("/api/admin/refunds/" + rrId + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(payoutRes.getResponse().getContentAsString().contains(secretAccount));

        MvcResult publicLookup = mockMvc.perform(get("/api/refunds/public/lookup")
                        .param("refundCode", rr.getRequestCode())
                        .param("email", customerA.getEmail()))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(publicLookup.getResponse().getContentAsString().contains(secretAccount));

        MvcResult adminDetail = mockMvc.perform(get("/api/admin/refunds/" + rrId)
                        .sessionAttr("currentUser", dtoOf(admin1))
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(adminDetail.getResponse().getContentAsString().contains(secretAccount));

        List<Map<String, Object>> audits = jdbcTemplate.queryForList(
                "SELECT details_json FROM refund_audit_logs WHERE refund_request_id = ?", rrId);
        for (Map<String, Object> row : audits) {
            assertFalse(String.valueOf(row.get("details_json")).contains(secretAccount));
        }

        // Defect #2 (SEC-04): Guest Booking IDOR — session-bound guest booking works; other guest without session/header gets 403
        Movie m = new Movie();
        m.setTitle("Guest Idor Movie " + TEST_TAG);
        m.setDurationMinutes(100);
        m.setAgeRating("P");
        m.setLanguage("Subtitles");
        m.setReleaseDate(LocalDate.now().minusDays(1));
        m.setStatus("NOW_SHOWING");
        m = movieRepository.save(m);
        createdMovieIds.add(m.getMovieId());

        Room room = roomRepository.save(new Room(cinema1, "GI-" + (System.currentTimeMillis() % 10000), "2D"));
        createdRoomIds.add(room.getRoomId());
        Long seatTypeId = jdbcTemplate.queryForObject("SELECT seat_type_id FROM seat_types ORDER BY seat_type_id ASC LIMIT 1", Long.class);
        jdbcTemplate.update("INSERT INTO seats (room_id, seat_type_id, seat_row, seat_number, status) VALUES (?, ?, 'G', 1, 'ACTIVE')",
                room.getRoomId(), seatTypeId);
        Long seatId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Showtime st = showtimeRepository.save(new Showtime(m, room, LocalDateTime.now().plusDays(2), LocalDateTime.now().plusDays(2).plusHours(2), BigDecimal.valueOf(90000)));
        createdShowtimeIds.add(st.getShowtimeId());

        MockHttpSession guestSession = new MockHttpSession();
        MvcResult guestBookRes = mockMvc.perform(post("/api/bookings").with(csrf())
                        .session(guestSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "showtimeId", st.getShowtimeId(),
                                "seatIds", List.of(seatId),
                                "guestName", "Guest Owner",
                                "guestEmail", "guest_idor_" + TEST_TAG + "@box.test",
                                "guestPhone", "0901234567",
                                "holdSessionId", "GUEST-" + TEST_TAG))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode gbNode = objectMapper.readTree(guestBookRes.getResponse().getContentAsString());
        long guestBookingId = gbNode.get("bookingId").asLong();
        String guestBookingCode = gbNode.get("bookingCode").asText();
        createdBookingIds.add(guestBookingId);

        // Owner session succeeds
        mockMvc.perform(get("/api/bookings/" + guestBookingId).session(guestSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingCode").value(guestBookingCode));

        // Attacker without session / wrong X-Booking-Code gets 403 BOOKING_ACCESS_DENIED
        mockMvc.perform(get("/api/bookings/" + guestBookingId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_ACCESS_DENIED"));
        mockMvc.perform(get("/api/bookings/" + guestBookingId)
                        .header("X-Booking-Code", "BOX-99999999-0000"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BOOKING_ACCESS_DENIED"));

        // Matching X-Booking-Code succeeds
        mockMvc.perform(get("/api/bookings/" + guestBookingId)
                        .header("X-Booking-Code", guestBookingCode))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingCode").value(guestBookingCode));
    }

    private PayOsPayoutService.HttpOutcome buildMockPayoutOk(Map<String, Object> bodyMap) {
        try {
            String refId = String.valueOf(bodyMap.get("referenceId"));
            Map<String, Object> txn = new LinkedHashMap<>();
            txn.put("id", "tx_" + refId);
            txn.put("referenceId", refId);
            txn.put("amount", bodyMap.get("amount"));
            txn.put("toBin", bodyMap.get("toBin"));
            txn.put("toAccountNumber", bodyMap.get("toAccountNumber"));
            txn.put("state", "SUCCEEDED");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", "po_" + refId);
            data.put("referenceId", refId);
            data.put("approvalState", "COMPLETED");
            data.put("transactions", List.of(txn));
            String sig = PayOsPayoutSignatureUtil.signPayload(data, effectivePayoutKey());
            return new PayOsPayoutService.HttpOutcome(200,
                    objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", data)),
                    sig, false, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private PayOsPayoutService.HttpOutcome buildMockGetSucceeded(String refId, long amount) {
        try {
            Map<String, Object> payout = new LinkedHashMap<>();
            payout.put("id", "po_rec_" + refId);
            payout.put("referenceId", refId);
            payout.put("approvalState", "COMPLETED");
            payout.put("transactions", List.of(Map.of("id", "tx_rec_" + refId, "referenceId", refId, "amount", amount, "state", "SUCCEEDED")));
            Map<String, Object> data = Map.of("payouts", List.of(payout));
            String sig = PayOsPayoutSignatureUtil.signPayload(data, effectivePayoutKey());
            return new PayOsPayoutService.HttpOutcome(200,
                    objectMapper.writeValueAsString(Map.of("code", "00", "desc", "success", "data", data)),
                    sig, false, null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String effectivePayoutKey() {
        return (payoutChecksumKey != null && !payoutChecksumKey.isBlank())
                ? payoutChecksumKey.trim()
                : "MOCK_PAYOUT_CHECKSUM_KEY";
    }

    private String buildRefundPayload(String orderCode, String email, Long ticketId,
                                      String toBin, String acc, String confirmAcc, String holder,
                                      String note, boolean saveBank, Long savedBankId) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderCode", orderCode);
        m.put("purchaseEmail", email);
        m.put("reasonCategory", "CINEMA_INCIDENT");
        m.put("description", "Customer bank account test refund");
        if (savedBankId != null) m.put("savedBankAccountId", savedBankId);
        if (toBin != null) m.put("toBin", toBin);
        if (acc != null) m.put("toAccountNumber", acc);
        if (confirmAcc != null) m.put("confirmAccountNumber", confirmAcc);
        if (holder != null) m.put("bankAccountName", holder);
        if (note != null) m.put("bankNote", note);
        m.put("saveBankAccount", saveBank);
        m.put("items", List.of(Map.of("itemKind", "TICKET", "targetId", ticketId, "quantity", 1)));
        return objectMapper.writeValueAsString(m);
    }

    private long submitPublicRefund(Booking b, User actor, String bin, String acc, String holder, String note) throws Exception {
        Ticket t = ticketRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        MvcResult res = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .sessionAttr("currentUser", dtoOf(actor))
                        .with(user(actor.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundPayload(b.getBookingCode(), actor.getEmail(), t.getTicketId(),
                                bin, acc, acc, holder, note, false, null)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asLong();
    }

    private void approveBusinessStage(long refundId) throws Exception {
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
    }

    private Booking createConfirmedTicketBooking(Cinema cinema, User owner, String email, long priceVnd, boolean withPaidPayment) {
        Booking b = new Booking();
        b.setBookingCode("BOX-20261122-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT));
        b.setCinema(cinema);
        b.setUser(owner);
        b.setOrderType("TICKET_ONLY");
        b.setGuestName(owner != null ? owner.getFullName() : "Guest");
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
            p.setIdempotencyKey("IDEMP-CBA-" + UUID.randomUUID());
            p.setPaymentStatus("PAID");
            p.setPaidAt(LocalDateTime.now().minusMinutes(10));
            paymentRepository.save(p);
        }

        BookingSeat bs = new BookingSeat(b, 1L, BigDecimal.valueOf(priceVnd), "A1", "BOOKED");
        bs = bookingSeatRepository.save(bs);
        b.getBookingSeats().add(bs);
        Ticket t = new Ticket();
        t.setBooking(b);
        t.setBookingSeatId(bs.getBookingSeatId());
        t.setTicketCode("TCK-" + b.getBookingCode().replace("BOX-", "") + "-A1");
        t.setQrTokenHash(CryptoUtils.sha256(t.getTicketCode()));
        t.setPrice(BigDecimal.valueOf(priceVnd));
        t.setGrossPrice(BigDecimal.valueOf(priceVnd));
        t.setNetPaidAmount(BigDecimal.valueOf(priceVnd));
        t.setDiscountAllocated(BigDecimal.ZERO);
        t.setTicketStatus("VALID");
        t = ticketRepository.save(t);
        snapshotService.allocateOrderSnapshot(b, BigDecimal.valueOf(priceVnd), List.of(t));
        ticketRepository.save(t);
        return bookingRepository.save(b);
    }

    private User ensureUser(String email, String role, Long cinemaId, String fullName) {
        User u = new User();
        u.setFullName(fullName);
        u.setEmail(email);
        u.setPhone("09" + Math.abs(email.hashCode() % 100000000));
        u.setRole(role);
        u.setCinemaId(cinemaId);
        u.setAccountStatus("ACTIVE");
        u = userRepository.save(u);
        createdUserIds.add(u.getUserId());
        return u;
    }

    private UserSummaryDto dtoOf(User u) {
        return new UserSummaryDto(u.getUserId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole());
    }
}
