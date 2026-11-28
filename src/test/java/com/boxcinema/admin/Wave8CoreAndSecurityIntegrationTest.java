package com.boxcinema.admin;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
class Wave8CoreAndSecurityIntegrationTest {

    private static final String TAG = "W8CORE_" + System.currentTimeMillis();

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
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private PromotionRepository promotionRepository;
    @Autowired private InquiryRepository inquiryRepository;
    @MockBean private JavaMailSender mailSender;

    private User adminUser;
    private User customerUser;
    private User staffUser;
    private Cinema testCinema;
    private Room testRoom;
    private Movie testMovie;
    private Showtime testShowtime;
    private Long seat1Id;
    private Long seat2Id;
    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdShowtimeIds = new ArrayList<>();
    private final List<Long> createdPromoIds = new ArrayList<>();
    private final List<Long> createdInquiryIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));

        testCinema = cinemaRepository.save(new Cinema("Wave8 Cinema " + TAG, "123 QA Ave", "Hanoi", "0901112223", "w8_" + TAG + "@box.test"));
        adminUser = createUser("w8_adm_" + TAG + "@box.test", "ADMIN", null);
        customerUser = createUser("w8_cust_" + TAG + "@box.test", "CUSTOMER", null);
        staffUser = createUser("w8_staff_" + TAG + "@box.test", "STAFF", testCinema.getCinemaId());

        testRoom = roomRepository.save(new Room(testCinema, "W8-R1", "2D Standard"));
        Long seatTypeId = jdbcTemplate.queryForObject("SELECT seat_type_id FROM seat_types ORDER BY seat_type_id ASC LIMIT 1", Long.class);
        seat1Id = insertSeat(testRoom.getRoomId(), seatTypeId, "A", 1);
        seat2Id = insertSeat(testRoom.getRoomId(), seatTypeId, "A", 2);

        Movie m = new Movie();
        m.setTitle("Wave8 Movie " + TAG);
        m.setDurationMinutes(120);
        m.setAgeRating("P");
        m.setLanguage("English");
        m.setReleaseDate(LocalDate.now().minusDays(2));
        m.setStatus("NOW_SHOWING");
        testMovie = movieRepository.save(m);

        Showtime st = new Showtime(testMovie, testRoom, LocalDateTime.now().plusDays(3), LocalDateTime.now().plusDays(3).plusHours(2), BigDecimal.valueOf(100000));
        testShowtime = showtimeRepository.save(st);
        createdShowtimeIds.add(testShowtime.getShowtimeId());
    }

    @AfterEach
    void cleanup() {
        for (Long bid : createdBookingIds) {
            List<Long> rrIds = jdbcTemplate.queryForList("SELECT id FROM refund_requests WHERE booking_id = ?", Long.class, bid);
            jdbcTemplate.update("DELETE FROM email_outbox WHERE booking_id = ?", bid);
            for (Long rrId : rrIds) {
                jdbcTemplate.update("DELETE FROM payos_payout_transactions WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE refund_request_id = ? OR (entity_type = 'REFUND_REQUEST' AND entity_id = ?)", rrId, rrId);
            }
            jdbcTemplate.update("DELETE FROM refund_requests WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM payment_webhook_event WHERE payment_id IN (SELECT payment_id FROM payments WHERE booking_id = ?)", bid);
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
        for (Long pid : createdPromoIds) {
            jdbcTemplate.update("DELETE FROM promotions WHERE promotion_id = ?", pid);
        }
        createdPromoIds.clear();
        for (Long iid : createdInquiryIds) {
            jdbcTemplate.update("DELETE FROM inquiries WHERE inquiry_id = ?", iid);
        }
        createdInquiryIds.clear();
        if (testRoom != null) {
            jdbcTemplate.update("DELETE FROM seats WHERE room_id = ?", testRoom.getRoomId());
            jdbcTemplate.update("DELETE FROM rooms WHERE room_id = ?", testRoom.getRoomId());
        }
        if (testMovie != null) {
            jdbcTemplate.update("DELETE FROM movies WHERE movie_id = ?", testMovie.getMovieId());
        }
        if (testCinema != null) {
            jdbcTemplate.update("DELETE FROM cinemas WHERE cinema_id = ?", testCinema.getCinemaId());
        }
        for (Long uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE actor_user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", uid);
        }
        createdUserIds.clear();
    }

    @Test
    void adminWorkspace_DeactivationCreatesRealRefund_ShowtimeOverlapBlocked_And_SeatHistoryPreserved() throws Exception {
        UserSummaryDto admDto = dto(adminUser);

        // Non-admin blocked; missing CSRF blocked
        mockMvc.perform(get("/admin/api/data").with(user(staffUser.getEmail()).roles("STAFF")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/api/save/showtimes").with(user(adminUser.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());

        // Overlapping showtime in same room rejected
        String startOverlap = testShowtime.getStartAt().plusMinutes(30).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        String endOverlap = testShowtime.getStartAt().plusMinutes(160).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"));
        mockMvc.perform(post("/admin/api/save/showtimes").with(csrf())
                        .sessionAttr("currentUser", admDto)
                        .with(user(adminUser.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "movieId", testMovie.getMovieId(),
                                "roomId", testRoom.getRoomId(),
                                "startAt", startOverlap,
                                "endAt", endOverlap,
                                "basePrice", 100000,
                                "status", "ACTIVE"))))
                .andExpect(status().isConflict());

        // Create a real PAID/CONFIRMED booking on testShowtime
        Booking b = createConfirmedBooking(BigDecimal.valueOf(100000));

        // Impact check detects affected booking
        mockMvc.perform(get("/admin/api/impact-check")
                        .param("section", "rooms")
                        .param("id", String.valueOf(testRoom.getRoomId()))
                        .with(user(adminUser.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasImpact").value(true))
                .andExpect(jsonPath("$.totalBookings").value(1));

        // Saving seats omitting seat2Id soft-deactivates instead of wiping historical booking_seats/tickets
        mockMvc.perform(post("/admin/api/seats/" + testRoom.getRoomId()).with(csrf())
                        .sessionAttr("currentUser", admDto)
                        .with(user(adminUser.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(
                                Map.of("row", "A", "number", 1, "typeId", jdbcTemplate.queryForObject("SELECT seat_type_id FROM seat_types ORDER BY seat_type_id ASC LIMIT 1", Long.class), "status", "ACTIVE")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        assertEquals(1, ticketRepository.findByBooking_BookingId(b.getBookingId()).size());

        // REFUND_AND_CANCEL opens a real RefundRequest (MORE_INFO_REQUIRED) rather than silently marking payment REFUNDED
        mockMvc.perform(post("/admin/api/resolve-deactivation").with(csrf())
                        .sessionAttr("currentUser", admDto)
                        .with(user(adminUser.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "section", "rooms",
                                "id", testRoom.getRoomId(),
                                "strategy", "REFUND_AND_CANCEL"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdRefundRequests").value(1));

        Integer rrCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_requests WHERE booking_id = ? AND request_status = 'MORE_INFO_REQUIRED' AND reason_category = 'SHOWTIME_CANCELLED'",
                Integer.class, b.getBookingId());
        assertEquals(1, rrCount);
        Payment latestPay = paymentRepository.findTopByBooking_BookingIdOrderByPaymentIdDesc(b.getBookingId()).orElseThrow();
        assertEquals("PAID", latestPay.getPaymentStatus());
    }

    @Test
    void publicTicketEndpoints_RequireCryptographicToken_RejectBookingCodeEnumeration_And_EnforceExpiry() throws Exception {
        Booking b = createConfirmedBooking(BigDecimal.valueOf(100000));
        String rawToken = "BOX1.W8_" + UUID.randomUUID().toString().replace("-", "");
        BookingQr qr = new BookingQr();
        qr.setBooking(b);
        qr.setTokenHash(CryptoUtils.sha256(rawToken));
        qr.setTokenEncrypted("enc");
        qr.setStatus("ACTIVE");
        qr.setExpiresAt(LocalDateTime.now().plusHours(4));
        qr = bookingQrRepository.save(qr);

        // Predictable booking code BOX-YYYYMMDD-XXXX MUST return 404 (no enumeration)
        mockMvc.perform(get("/api/public/tickets/" + b.getBookingCode()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.valid").value(false));

        // Valid BOX1. token returns 200 valid=true
        mockMvc.perform(get("/api/public/tickets/" + rawToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.bookingCode").value(b.getBookingCode()));

        mockMvc.perform(get("/t/" + rawToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(b.getBookingCode())));

        // Expired QR returns valid=false
        qr.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        bookingQrRepository.save(qr);
        mockMvc.perform(get("/api/public/tickets/" + rawToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.message").value(containsString("expired")));
    }

    @Test
    void adminInquiries_And_PaymentReconciliationQueue_WorkEndToEnd() throws Exception {
        UserSummaryDto admDto = dto(adminUser);

        Inquiry inq = new Inquiry();
        inq.setFullName("QA Guest");
        inq.setEmail("qa_inq_" + TAG + "@box.test");
        inq.setInquiryTopic("Booking Support");
        inq.setMessage("Need invoice confirmation");
        inq.setInquiryStatus("PENDING");
        inq = inquiryRepository.save(inq);
        createdInquiryIds.add(inq.getInquiryId());

        mockMvc.perform(get("/api/admin/inquiries")
                        .param("status", "PENDING")
                        .param("search", "qa_inq_" + TAG)
                        .with(user(adminUser.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        // Resolving without response text fails 400
        mockMvc.perform(post("/api/admin/inquiries/" + inq.getInquiryId() + "/respond").with(csrf())
                        .sessionAttr("currentUser", admDto)
                        .with(user(adminUser.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inquiryStatus\":\"RESOLVED\",\"adminResponse\":\"\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/admin/inquiries/" + inq.getInquiryId() + "/respond").with(csrf())
                        .sessionAttr("currentUser", admDto)
                        .with(user(adminUser.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inquiryStatus\":\"RESOLVED\",\"adminResponse\":\"Invoice verified and re-sent.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inquiryStatus").value("RESOLVED"));

        // Collection payment reconciliation queue for REVIEW_REQUIRED
        Booking b = createConfirmedBooking(BigDecimal.valueOf(100000));
        b.setBookingStatus("PAYMENT_REVIEW");
        bookingRepository.save(b);
        Payment pay = paymentRepository.findTopByBooking_BookingIdOrderByPaymentIdDesc(b.getBookingId()).orElseThrow();
        pay.setPaymentStatus("REVIEW_REQUIRED");
        pay = paymentRepository.save(pay);

        mockMvc.perform(get("/api/admin/payments/reconciliation-queue")
                        .with(user(adminUser.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(b.getBookingCode())));

        mockMvc.perform(post("/api/admin/payments/" + pay.getPaymentId() + "/reconcile-decision").with(csrf())
                        .sessionAttr("currentUser", admDto)
                        .with(user(adminUser.getEmail()).roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONVERT_TO_REFUNDABLE\",\"note\":\"Manual QA check: convert to refundable\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingStatus").value("PAID_SEAT_CONFLICT"));
    }

    @Test
    void vouchers_MyVouchers_NegativeAmountRejection_And_DraftDoubleSpendBlocked() throws Exception {
        UserSummaryDto custDto = dto(customerUser);

        Promotion priv = new Promotion();
        priv.setPromotionName("Private Recovery " + TAG);
        priv.setVoucherCode("W8PRIV_" + System.currentTimeMillis());
        priv.setDiscountType("FIXED");
        priv.setDiscountValue(BigDecimal.valueOf(20000));
        priv.setMaxDiscount(BigDecimal.valueOf(20000));
        priv.setMinOrderAmount(BigDecimal.valueOf(50000));
        priv.setStartAt(LocalDateTime.now().minusDays(1));
        priv.setEndAt(LocalDateTime.now().plusDays(10));
        priv.setUsageLimit(1);
        priv.setPerUserLimit(1);
        priv.setStatus("ACTIVE");
        priv.setPublic(false);
        priv.setTargetUserId(customerUser.getUserId());
        priv.setTargetEmail(customerUser.getEmail());
        priv = promotionRepository.save(priv);
        createdPromoIds.add(priv.getPromotionId());

        // Anonymous /my returns 401; authenticated returns customer's private voucher
        mockMvc.perform(get("/api/vouchers/my"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/vouchers/my")
                        .sessionAttr("currentUser", custDto)
                        .with(user(customerUser.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(priv.getVoucherCode())));

        // Negative amount rejected on /validate and /available
        mockMvc.perform(post("/api/vouchers/validate").with(csrf())
                        .sessionAttr("currentUser", custDto)
                        .with(user(customerUser.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("voucherCode", priv.getVoucherCode(), "orderAmount", -1000))))
                .andExpect(status().isBadRequest());

        // Booking 1 creates DRAFT holding this single-use voucher -> Booking 2 CANNOT double-spend it
        MvcResult b1Res = mockMvc.perform(post("/api/bookings").with(csrf())
                        .sessionAttr("currentUser", custDto)
                        .with(user(customerUser.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "showtimeId", testShowtime.getShowtimeId(),
                                "seatIds", List.of(seat1Id),
                                "voucherCode", priv.getVoucherCode()))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode b1Json = objectMapper.readTree(b1Res.getResponse().getContentAsString());
        createdBookingIds.add(b1Json.get("bookingId").asLong());

        mockMvc.perform(post("/api/bookings").with(csrf())
                        .sessionAttr("currentUser", custDto)
                        .with(user(customerUser.getEmail()).roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "showtimeId", testShowtime.getShowtimeId(),
                                "seatIds", List.of(seat2Id),
                                "voucherCode", priv.getVoucherCode()))))
                .andExpect(status().isBadRequest());
    }

    private Booking createConfirmedBooking(BigDecimal amount) {
        Booking b = new Booking();
        b.setBookingCode("BOX-20261125-" + (1000 + new Random().nextInt(8999)));
        b.setCinema(testCinema);
        b.setShowtime(testShowtime);
        b.setUser(customerUser);
        b.setOrderType("TICKET_ONLY");
        b.setGuestName(customerUser.getFullName());
        b.setGuestEmail(customerUser.getEmail());
        b.setGuestPhone(customerUser.getPhone());
        b.setTicketSubtotal(amount);
        b.setFoodSubtotal(BigDecimal.ZERO);
        b.setSubtotal(amount);
        b.setDiscountAmount(BigDecimal.ZERO);
        b.setServiceFee(BigDecimal.ZERO);
        b.setTotalAmount(amount);
        b.setBookingStatus("CONFIRMED");
        b.setConfirmedAt(LocalDateTime.now());
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());

        jdbcTemplate.update(
                "INSERT INTO showtime_seats (showtime_id, seat_id, seat_status) VALUES (?, ?, 'BOOKED')",
                testShowtime.getShowtimeId(), seat1Id);
        Long showtimeSeatId = jdbcTemplate.queryForObject(
                "SELECT showtime_seat_id FROM showtime_seats WHERE showtime_id = ? AND seat_id = ?",
                Long.class, testShowtime.getShowtimeId(), seat1Id);
        BookingSeat bs = new BookingSeat(b, showtimeSeatId, amount, "A1", "BOOKED");
        bs = bookingSeatRepository.save(bs);

        Ticket t = new Ticket();
        t.setBooking(b);
        t.setShowtimeId(testShowtime.getShowtimeId());
        t.setSeatId(seat1Id);
        t.setBookingSeatId(bs.getBookingSeatId());
        t.setTicketCode("TCK-" + b.getBookingCode().replace("BOX-", "") + "-A1");
        t.setQrTokenHash(CryptoUtils.sha256(t.getTicketCode()));
        t.setPrice(amount);
        t.setGrossPrice(amount);
        t.setDiscountAllocated(BigDecimal.ZERO);
        t.setNetPaidAmount(amount);
        t.setTicketStatus("VALID");
        ticketRepository.save(t);

        Payment p = new Payment();
        p.setBooking(b);
        p.setOrderCode(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 900000000L + 1000000L);
        p.setIdempotencyKey("W8C-" + UUID.randomUUID());
        p.setPaymentProvider("PAYOS");
        p.setPaymentMethod("PAYOS_QR");
        p.setAmount(amount);
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

    private Long insertSeat(Long roomId, Long seatTypeId, String row, int number) {
        jdbcTemplate.update(
                "INSERT INTO seats (room_id, seat_type_id, seat_row, seat_number, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                roomId, seatTypeId, row, number);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private UserSummaryDto dto(User u) {
        return new UserSummaryDto(u.getUserId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole());
    }
}
