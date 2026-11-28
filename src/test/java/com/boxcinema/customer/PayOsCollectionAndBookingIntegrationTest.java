package com.boxcinema.customer;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.customer.controller.PaymentApiController;
import com.boxcinema.customer.service.impl.PayOsServiceImpl;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PayOsCollectionAndBookingIntegrationTest {

    private static final String TEST_TAG = "PAYOS_COL_" + System.currentTimeMillis();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PayOsServiceImpl payOsService;
    @Autowired private PaymentApiController paymentApiController;
    @Autowired private CinemaRepository cinemaRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private ShowtimeRepository showtimeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private BookingQrRepository bookingQrRepository;
    @Autowired private FoodFulfillmentRepository foodFulfillmentRepository;
    @Autowired private EmailOutboxRepository emailOutboxRepository;
    @Autowired private PaymentWebhookEventRepository webhookEventRepository;
    @MockBean private JavaMailSender mailSender;

    @Value("${payos.checksum-key}")
    private String payosChecksumKey;

    private Cinema cinema;
    private Movie testMovie;
    private Room testRoom;
    private Showtime testShowtime;
    private Long seatA1Id;
    private Long seatA2Id;
    private Long seatA3Id;
    private User user1;
    private User user2;
    private User manager1;
    private User admin1;
    private Long foodItemId;
    private Long createdCategoryId;

    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        payOsService.setCustomTransportExecutorForTesting(null);

        cinema = cinemaRepository.findAll().get(0);
        user1 = ensureUser("col_u1_" + TEST_TAG + "@box.test", "CUSTOMER", null);
        user2 = ensureUser("col_u2_" + TEST_TAG + "@box.test", "CUSTOMER", null);
        manager1 = ensureUser("col_mgr_" + TEST_TAG + "@box.test", "MANAGER", cinema.getCinemaId());
        admin1 = ensureUser("col_adm_" + TEST_TAG + "@box.test", "ADMIN", null);

        Movie m = new Movie();
        m.setTitle("Test Movie " + TEST_TAG);
        m.setDurationMinutes(120);
        m.setAgeRating("P");
        m.setLanguage("Subtitles");
        m.setReleaseDate(LocalDate.now().minusDays(1));
        m.setStatus("NOW_SHOWING");
        testMovie = movieRepository.save(m);

        Room r = new Room(cinema, "T-" + (System.currentTimeMillis() % 10000), "2D");
        testRoom = roomRepository.save(r);

        Map<String, Object> stType = jdbcTemplate.queryForMap(
                "SELECT seat_type_id, price_surcharge FROM seat_types ORDER BY price_surcharge ASC, seat_type_id ASC LIMIT 1");
        Long seatTypeId = ((Number) stType.get("seat_type_id")).longValue();
        BigDecimal surcharge = stType.get("price_surcharge") != null ? new BigDecimal(String.valueOf(stType.get("price_surcharge"))) : BigDecimal.ZERO;
        seatA1Id = insertSeat(testRoom.getRoomId(), seatTypeId, "A", 1);
        seatA2Id = insertSeat(testRoom.getRoomId(), seatTypeId, "A", 2);
        seatA3Id = insertSeat(testRoom.getRoomId(), seatTypeId, "A", 3);

        Showtime st = new Showtime(testMovie, testRoom, LocalDateTime.now().plusDays(2),
                LocalDateTime.now().plusDays(2).plusHours(2), BigDecimal.valueOf(100000).subtract(surcharge));
        testShowtime = showtimeRepository.save(st);

        Long catId = jdbcTemplate.query("SELECT id FROM food_category LIMIT 1", (rs, i) -> rs.getLong(1))
                .stream().findFirst().orElseGet(() -> {
                    jdbcTemplate.update("INSERT INTO food_category (name, description, status, created_at, updated_at) VALUES (?, 'Col', 'ACTIVE', NOW(), NOW())",
                            "ColCat-" + TEST_TAG);
                    createdCategoryId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                    return createdCategoryId;
                });
        jdbcTemplate.update("INSERT INTO food_item (category_id, name, description, price, status, is_available, created_at, updated_at) VALUES (?, ?, 'Col', 50000.00, 'ACTIVE', 1, NOW(), NOW())",
                catId, "Col Popcorn " + TEST_TAG);
        foodItemId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update("INSERT INTO food_inventory (food_item_id, on_hand_quantity, reserved_quantity, updated_at) VALUES (?, 300, 0, NOW())",
                foodItemId);
    }

    @AfterEach
    void cleanup() {
        payOsService.setCustomTransportExecutorForTesting(null);

        // Also find any bookings created for testShowtime (e.g. from races)
        if (testShowtime != null) {
            List<Long> stBookingIds = jdbcTemplate.queryForList(
                    "SELECT booking_id FROM bookings WHERE showtime_id = ?", Long.class, testShowtime.getShowtimeId());
            for (Long bid : stBookingIds) {
                if (!createdBookingIds.contains(bid)) createdBookingIds.add(bid);
            }
        }
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
            jdbcTemplate.update("DELETE FROM payment_webhook_event WHERE payment_id IN (SELECT payment_id FROM payments WHERE booking_id = ?)", bid);
            jdbcTemplate.update("DELETE FROM payments WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM food_fulfillment WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM tickets WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_qr WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM bookings WHERE booking_id = ?", bid);
        }
        createdBookingIds.clear();
        if (testShowtime != null) {
            jdbcTemplate.update("DELETE FROM showtime_seats WHERE showtime_id = ?", testShowtime.getShowtimeId());
            jdbcTemplate.update("DELETE FROM showtimes WHERE showtime_id = ?", testShowtime.getShowtimeId());
            testShowtime = null;
        }
        if (testRoom != null) {
            jdbcTemplate.update("DELETE FROM seats WHERE room_id = ?", testRoom.getRoomId());
            jdbcTemplate.update("DELETE FROM rooms WHERE room_id = ?", testRoom.getRoomId());
            testRoom = null;
        }
        if (testMovie != null) {
            jdbcTemplate.update("DELETE FROM movies WHERE movie_id = ?", testMovie.getMovieId());
            testMovie = null;
        }
        if (foodItemId != null) {
            jdbcTemplate.update("DELETE FROM food_inventory WHERE food_item_id = ?", foodItemId);
            jdbcTemplate.update("DELETE FROM food_item WHERE id = ?", foodItemId);
            foodItemId = null;
        }
        if (createdCategoryId != null) {
            jdbcTemplate.update("DELETE FROM food_category WHERE id = ?", createdCategoryId);
            createdCategoryId = null;
        }
        for (Long uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", uid);
        }
        createdUserIds.clear();
    }

    @Test
    void scenarios1to5_CreatePayment_SuccessWebhook_Polling_TimeoutFallback_AmountRefresh_And_DuplicateCreate() throws Exception {
        // 1. M1: Mock PayOS collection transport with 30ms simulated network delay (TX1 -> HTTP -> TX2)
        payOsService.setCustomTransportExecutorForTesting(new PayOsServiceImpl.CollectionTransportExecutor() {
            @Override
            public ResponseEntity<String> post(String url, HttpHeaders headers, Map<String, Object> body) {
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
                Object oc = body != null ? body.get("orderCode") : "1";
                return ResponseEntity.ok("{\"code\":\"00\",\"desc\":\"success\",\"data\":{\"paymentLinkId\":\"plink_" + oc + "\",\"qrCode\":\"MOCK_QR_DATA\",\"checkoutUrl\":\"https://pay.payos.vn/web/plink_" + oc + "\",\"bin\":\"970422\",\"accountNumber\":\"0000111222\",\"accountName\":\"BOX CINEMA\"}}");
            }
            @Override
            public ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
                try {
                    return ResponseEntity.ok(objectMapper.readTree("{\"code\":\"00\",\"data\":{\"status\":\"PENDING\"}}"));
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        long booking1Id = createBookingViaApi(user1, List.of(seatA1Id), List.of(Map.of("foodItemId", foodItemId, "quantity", 1)), "S1");
        createdBookingIds.add(booking1Id);

        // Scenario 5: Create payment & verify duplicate createPayment returns same active PENDING payment (same orderCode)
        JsonNode p1 = createPaymentViaApi(user1, booking1Id);
        long orderCode1 = p1.get("orderCode").asLong();
        assertEquals("MOCK_QR_DATA", p1.get("qrCode").asText());
        assertEquals("PENDING", p1.get("status").asText());

        JsonNode p1Dup = createPaymentViaApi(user1, booking1Id);
        assertEquals(orderCode1, p1Dup.get("orderCode").asLong());

        // Scenario 4: Stale PENDING amount refreshed after updateFoodItems (150k -> 200k)
        UserSummaryDto u1Dto = new UserSummaryDto(user1.getUserId(), user1.getFullName(), user1.getEmail(), user1.getPhone(), "CUSTOMER");
        mockMvc.perform(post("/api/bookings/" + booking1Id + "/food-items").with(csrf())
                        .sessionAttr("currentUser", u1Dto)
                        .with(user(user1.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("foodItems", List.of(Map.of("foodItemId", foodItemId, "quantity", 2))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(200000));

        JsonNode p1Refreshed = createPaymentViaApi(user1, booking1Id);
        long refreshedOrderCode = p1Refreshed.get("orderCode").asLong();
        assertNotEquals(orderCode1, refreshedOrderCode);
        assertEquals(200000L, p1Refreshed.get("amount").asLong());

        // Scenario 10: Return URL GET /booking/buy-tickets?code=00&status=PAID&orderCode=... NEVER confirms booking!
        mockMvc.perform(get("/booking/buy-tickets")
                        .param("code", "00")
                        .param("status", "PAID")
                        .param("orderCode", String.valueOf(refreshedOrderCode)))
                .andExpect(status().isOk());
        assertEquals("PENDING_PAYMENT", bookingRepository.findById(booking1Id).orElseThrow().getBookingStatus());
        assertEquals("PENDING", paymentRepository.findByOrderCode(refreshedOrderCode).orElseThrow().getPaymentStatus());

        // Scenario 1 & 5: Valid HMAC-SHA256 webhook -> PAID + CONFIRMED + BOOKED seats + 1 Ticket + 1 BookingQr + 1 FoodFulfillment + 1 EmailOutbox
        String webhookBody = buildSignedWebhookJson(refreshedOrderCode, 200000L, "BOX" + refreshedOrderCode, "TXN-OK-1", "00");
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(webhookBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"));

        Booking b1 = bookingRepository.findById(booking1Id).orElseThrow();
        assertEquals("CONFIRMED", b1.getBookingStatus());
        assertEquals("PAID", paymentRepository.findByOrderCode(refreshedOrderCode).orElseThrow().getPaymentStatus());
        assertEquals("BOOKED", jdbcTemplate.queryForObject("SELECT seat_status FROM showtime_seats WHERE showtime_id = ? AND seat_id = ?",
                String.class, testShowtime.getShowtimeId(), seatA1Id));
        assertEquals(1, ticketRepository.findByBooking_BookingId(booking1Id).size());
        assertTrue(bookingQrRepository.findByBooking_BookingId(booking1Id).isPresent());
        assertTrue(foodFulfillmentRepository.findByBooking_BookingId(booking1Id).isPresent());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE booking_id = ? AND dedup_key = ?",
                Integer.class, booking1Id, "BOOKING_CONFIRMATION:" + booking1Id));

        // Calling createPayment after CONFIRMED rejected cleanly (409)
        mockMvc.perform(post("/api/payments/payos/create").with(csrf())
                        .sessionAttr("currentUser", u1Dto)
                        .with(user(user1.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json")
                        .content("{\"bookingId\":" + booking1Id + "}"))
                .andExpect(status().isConflict());

        // Scenario 2 & 3: Timeout on PayOS POST -> VietQR fallback; then proactive polling GET syncs PAID
        long booking2Id = createBookingViaApi(user2, List.of(seatA2Id), List.of(), "S2");
        createdBookingIds.add(booking2Id);

        payOsService.setCustomTransportExecutorForTesting(new PayOsServiceImpl.CollectionTransportExecutor() {
            @Override
            public ResponseEntity<String> post(String url, HttpHeaders headers, Map<String, Object> body) {
                throw new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out"));
            }
            @Override
            public ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
                try {
                    long oc = Long.parseLong(url.substring(url.lastIndexOf('/') + 1));
                    return ResponseEntity.ok(objectMapper.readTree(
                            "{\"code\":\"00\",\"data\":{\"id\":\"plink_fb\",\"status\":\"PAID\",\"amount\":100000,\"amountPaid\":100000,\"transactions\":[{\"reference\":\"POLL-REF-" + oc + "\"}]}}"));
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        JsonNode p2 = createPaymentViaApi(user2, booking2Id);
        long payment2Id = p2.get("paymentId").asLong();
        assertTrue(p2.get("qrCode").asText().startsWith("000201"));

        UserSummaryDto u2StatusDto = new UserSummaryDto(user2.getUserId(), user2.getFullName(), user2.getEmail(), user2.getPhone(), "CUSTOMER");
        mockMvc.perform(get("/api/payments/" + payment2Id + "/status")
                        .sessionAttr("currentUser", u2StatusDto)
                        .with(user(user2.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid").value(true))
                .andExpect(jsonPath("$.status").value("PAID"));
        assertEquals("CONFIRMED", bookingRepository.findById(booking2Id).orElseThrow().getBookingStatus());
        assertEquals(1, ticketRepository.findByBooking_BookingId(booking2Id).size());

        // Scenario 4: Zero-amount booking rejected with HTTP 400 (no 2,000 VND silent fallback!)
        Booking zeroB = new Booking();
        zeroB.setBookingCode("BOX-ZERO-" + (1000 + new Random().nextInt(8999)));
        zeroB.setCinema(cinema);
        zeroB.setUser(user1);
        zeroB.setGuestName(user1.getFullName());
        zeroB.setGuestEmail(user1.getEmail());
        zeroB.setGuestPhone("0901234567");
        zeroB.setBookingStatus("DRAFT");
        zeroB = bookingRepository.save(zeroB);
        createdBookingIds.add(zeroB.getBookingId());

        mockMvc.perform(post("/api/payments/payos/create").with(csrf())
                        .sessionAttr("currentUser", u1Dto)
                        .with(user(user1.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json")
                        .content("{\"bookingId\":" + zeroB.getBookingId() + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));
    }

    @Test
    void scenarios6to9_And_13_WebhookSignatureSecurity_Mismatch_Non00_Concurrency_And_SimulateGuard() throws Exception {
        long bookingId = createBookingViaApi(user1, List.of(seatA1Id), List.of(), "S6");
        createdBookingIds.add(bookingId);
        JsonNode p = createPaymentViaApi(user1, bookingId);
        long orderCode = p.get("orderCode").asLong();
        long paymentId = p.get("paymentId").asLong();

        // Scenario 6: Forged simulated_ signature or tampered payload rejected with HTTP 400
        String forgedJson = "{\"code\":\"00\",\"desc\":\"success\",\"data\":{\"orderCode\":" + orderCode
                + ",\"amount\":100000,\"reference\":\"FORGE-1\",\"code\":\"00\"},\"signature\":\"simulated_12345\"}";
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(forgedJson))
                .andExpect(status().isBadRequest());
        assertEquals("PENDING", paymentRepository.findById(paymentId).orElseThrow().getPaymentStatus());

        String realSigTampered = buildSignedWebhookJson(orderCode, 100000L, "BOX", "TXN-T", "00").replace("\"amount\":100000", "\"amount\":1000");
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(realSigTampered))
                .andExpect(status().isBadRequest());
        assertEquals("PENDING", paymentRepository.findById(paymentId).orElseThrow().getPaymentStatus());

        // Scenario 13 / H1: Default unconfigured allow-simulate is FAIL-CLOSED (false -> 403 SIMULATE_DISABLED)
        assertEquals(Boolean.FALSE, ReflectionTestUtils.getField(paymentApiController, "allowSimulate"));
        mockMvc.perform(post("/api/payments/payos/simulate-payment/" + orderCode).with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SIMULATE_DISABLED"));
        assertEquals("PENDING", paymentRepository.findById(paymentId).orElseThrow().getPaymentStatus());

        // Scenario 8a: Valid HMAC non-00 webhook -> IGNORED_NON_SUCCESS, stays PENDING/PENDING_PAYMENT
        String cancelWebhook = buildSignedWebhookJson(orderCode, 100000L, "BOX", "TXN-CANCEL", "01");
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(cancelWebhook))
                .andExpect(status().isOk());
        assertEquals("PENDING", paymentRepository.findById(paymentId).orElseThrow().getPaymentStatus());
        assertEquals("PENDING_PAYMENT", bookingRepository.findById(bookingId).orElseThrow().getBookingStatus());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment_webhook_event WHERE payment_id = ? AND processing_status = 'IGNORED_NON_SUCCESS'",
                Integer.class, paymentId));

        // Scenario 9: Concurrent identical valid webhooks -> idempotent single ticket, single BookingQr, single EmailOutbox
        String okWebhook = buildSignedWebhookJson(orderCode, 100000L, "BOX", "TXN-CONC", "00");
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(okWebhook))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Integer> f : results) {
                assertEquals(200, f.get(15, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals("CONFIRMED", bookingRepository.findById(bookingId).orElseThrow().getBookingStatus());
        assertEquals(1, ticketRepository.findByBooking_BookingId(bookingId).size());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking_qr WHERE booking_id = ?", Integer.class, bookingId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE booking_id = ?", Integer.class, bookingId));

        // Scenario 8b: Out-of-order late non-00 webhook AFTER PAID -> never downgrades CONFIRMED/PAID
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(cancelWebhook))
                .andExpect(status().isOk());
        assertEquals("CONFIRMED", bookingRepository.findById(bookingId).orElseThrow().getBookingStatus());
        assertEquals("PAID", paymentRepository.findById(paymentId).orElseThrow().getPaymentStatus());

        // Scenario 7 & M2a: Valid HMAC null-amount webhook -> REVIEW_REQUIRED / PAYMENT_REVIEW (AMOUNT_MISMATCH), NOT CONFIRMED!
        long bNullAmtId = createBookingViaApi(user2, List.of(seatA3Id), List.of(), "S7-NULL");
        createdBookingIds.add(bNullAmtId);
        JsonNode pNullAmt = createPaymentViaApi(user2, bNullAmtId);
        long nullAmtOrderCode = pNullAmt.get("orderCode").asLong();
        String nullAmtWebhook = buildSignedWebhookJson(nullAmtOrderCode, null, "BOX", "TXN-NULL", "00");
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(nullAmtWebhook))
                .andExpect(status().isOk());
        assertEquals("REVIEW_REQUIRED", paymentRepository.findByOrderCode(nullAmtOrderCode).orElseThrow().getPaymentStatus());
        assertEquals("PAYMENT_REVIEW", bookingRepository.findById(bNullAmtId).orElseThrow().getBookingStatus());
        assertEquals(0, ticketRepository.findByBooking_BookingId(bNullAmtId).size());

        // Scenario 7: Valid HMAC amount mismatch webhook -> REVIEW_REQUIRED / PAYMENT_REVIEW
        long bMisId = createBookingViaApi(user2, List.of(seatA2Id), List.of(), "S7");
        createdBookingIds.add(bMisId);
        JsonNode pMis = createPaymentViaApi(user2, bMisId);
        long misOrderCode = pMis.get("orderCode").asLong();
        String mismatchWebhook = buildSignedWebhookJson(misOrderCode, 50000L, "BOX", "TXN-MIS", "00");
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(mismatchWebhook))
                .andExpect(status().isOk());
        assertEquals("REVIEW_REQUIRED", paymentRepository.findByOrderCode(misOrderCode).orElseThrow().getPaymentStatus());
        assertEquals("PAYMENT_REVIEW", bookingRepository.findById(bMisId).orElseThrow().getBookingStatus());
        assertEquals(0, ticketRepository.findByBooking_BookingId(bMisId).size());

        // H2: PAYMENT_REVIEW rejects updateFoodItems and confirmBooking with 409 INVALID_BOOKING_STATE
        UserSummaryDto u2Dto = new UserSummaryDto(user2.getUserId(), user2.getFullName(), user2.getEmail(), user2.getPhone(), "CUSTOMER");
        mockMvc.perform(post("/api/bookings/" + bMisId + "/food-items").with(csrf())
                        .sessionAttr("currentUser", u2Dto).with(user(user2.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json").content("{\"foodItems\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_BOOKING_STATE"));
        mockMvc.perform(post("/api/bookings/" + bMisId + "/confirm").with(csrf())
                        .sessionAttr("currentUser", u2Dto).with(user(user2.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_BOOKING_STATE"));
    }

    @Test
    void scenarios11to12_ConcurrentSeatRace_And_ExpiredLatePaymentsRecoveryVsConflictRefund() throws Exception {
        // Scenario 11: Concurrent seat booking race (2 users book seatA3Id simultaneously -> 1 gets 201, 1 gets 409)
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> f1 = pool.submit(() -> {
            ready.countDown();
            start.await();
            return attemptCreateBookingRaw(user1, List.of(seatA3Id), "RACE-U1");
        });
        Future<Integer> f2 = pool.submit(() -> {
            ready.countDown();
            start.await();
            return attemptCreateBookingRaw(user2, List.of(seatA3Id), "RACE-U2");
        });
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        int s1 = f1.get(15, TimeUnit.SECONDS);
        int s2 = f2.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertEquals(Set.of(201, 409), Set.of(s1, s2), "One concurrent user must get 201 and the other 409; got " + s1 + " & " + s2);

        // L1: Contested seat returns errorCode SEAT_CONFLICT (not SHOWTIME_STARTED)
        User loser = (s1 == 201) ? user2 : user1;
        UserSummaryDto loserDto = new UserSummaryDto(loser.getUserId(), loser.getFullName(), loser.getEmail(), loser.getPhone(), "CUSTOMER");
        mockMvc.perform(post("/api/bookings").with(csrf())
                        .sessionAttr("currentUser", loserDto).with(user(loser.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "showtimeId", testShowtime.getShowtimeId(),
                                "seatIds", List.of(seatA3Id),
                                "holdSessionId", "L1-" + TEST_TAG))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SEAT_CONFLICT"));

        // Scenario 12a: Expired booking paid late when seatA1Id is still free -> recovers cleanly to CONFIRMED/BOOKED
        long expFreeId = createBookingViaApi(user1, List.of(seatA1Id), List.of(), "EXP-FREE");
        createdBookingIds.add(expFreeId);
        JsonNode pFree = createPaymentViaApi(user1, expFreeId);
        long freeOrderCode = pFree.get("orderCode").asLong();
        jdbcTemplate.update("UPDATE bookings SET booking_status = 'EXPIRED', expires_at = DATE_SUB(NOW(), INTERVAL 15 MINUTE), payment_deadline = DATE_SUB(NOW(), INTERVAL 15 MINUTE) WHERE booking_id = ?", expFreeId);
        jdbcTemplate.update("UPDATE booking_seats SET status = 'EXPIRED', active_seat_key = NULL WHERE booking_id = ?", expFreeId);
        jdbcTemplate.update("UPDATE showtime_seats SET seat_status = 'AVAILABLE', held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL WHERE showtime_id = ? AND seat_id = ?",
                testShowtime.getShowtimeId(), seatA1Id);

        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(buildSignedWebhookJson(freeOrderCode, 100000L, "BOX", "TXN-EXPFREE", "00")))
                .andExpect(status().isOk());
        assertEquals("CONFIRMED", bookingRepository.findById(expFreeId).orElseThrow().getBookingStatus());
        assertEquals("BOOKED", jdbcTemplate.queryForObject("SELECT seat_status FROM showtime_seats WHERE showtime_id = ? AND seat_id = ?",
                String.class, testShowtime.getShowtimeId(), seatA1Id));
        assertTrue(bookingQrRepository.findByBooking_BookingId(expFreeId).isPresent());

        // Scenario 12b & M2c & H2: Expired TICKET_AND_FOOD booking paid late AFTER seatA2Id booked by another user -> PAID_SEAT_CONFLICT, releases food stock, full refund, and blocks food-items/confirm!
        long lateBookingId = createBookingViaApi(user1, List.of(seatA2Id), List.of(Map.of("foodItemId", foodItemId, "quantity", 2)), "EXP-CONFLICT-U1");
        createdBookingIds.add(lateBookingId);
        JsonNode pLate = createPaymentViaApi(user1, lateBookingId);
        long lateOrderCode = pLate.get("orderCode").asLong();
        Integer reservedBeforeConflict = jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM food_inventory WHERE food_item_id = ?", Integer.class, foodItemId);
        assertEquals(2, reservedBeforeConflict);

        // Simulate booking expiry and hold release, then user2 takes & confirms seatA2Id
        jdbcTemplate.update("UPDATE bookings SET booking_status = 'EXPIRED', expires_at = DATE_SUB(NOW(), INTERVAL 15 MINUTE), payment_deadline = DATE_SUB(NOW(), INTERVAL 15 MINUTE) WHERE booking_id = ?", lateBookingId);
        jdbcTemplate.update("UPDATE booking_seats SET status = 'EXPIRED', active_seat_key = NULL WHERE booking_id = ?", lateBookingId);
        jdbcTemplate.update("UPDATE showtime_seats SET seat_status = 'AVAILABLE', held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL WHERE showtime_id = ? AND seat_id = ?",
                testShowtime.getShowtimeId(), seatA2Id);

        // H2: EXPIRED and CANCELLED bookings cannot confirm or update food items (409)
        UserSummaryDto u1Dto = new UserSummaryDto(user1.getUserId(), user1.getFullName(), user1.getEmail(), user1.getPhone(), "CUSTOMER");
        mockMvc.perform(post("/api/bookings/" + lateBookingId + "/confirm").with(csrf())
                        .sessionAttr("currentUser", u1Dto).with(user(user1.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_BOOKING_STATE"));
        mockMvc.perform(post("/api/bookings/" + lateBookingId + "/food-items").with(csrf())
                        .sessionAttr("currentUser", u1Dto).with(user(user1.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json").content("{\"foodItems\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_BOOKING_STATE"));

        long winnerBookingId = createBookingViaApi(user2, List.of(seatA2Id), List.of(), "EXP-WINNER-U2");
        createdBookingIds.add(winnerBookingId);
        JsonNode pWin = createPaymentViaApi(user2, winnerBookingId);
        long winOrderCode = pWin.get("orderCode").asLong();
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(buildSignedWebhookJson(winOrderCode, 100000L, "BOX", "TXN-WIN", "00")))
                .andExpect(status().isOk());
        assertEquals("CONFIRMED", bookingRepository.findById(winnerBookingId).orElseThrow().getBookingStatus());

        // Late webhook arrives for user1's expired booking (200k) -> PAID + PAID_SEAT_CONFLICT (winner seat untouched, food stock released, CONFLICT_UNISSUED ticket, no QR!)
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(buildSignedWebhookJson(lateOrderCode, 200000L, "BOX", "TXN-LATECONFLICT", "00")))
                .andExpect(status().isOk());
        Booking lateB = bookingRepository.findById(lateBookingId).orElseThrow();
        assertEquals("PAID_SEAT_CONFLICT", lateB.getBookingStatus());
        assertEquals("PAID", paymentRepository.findByOrderCode(lateOrderCode).orElseThrow().getPaymentStatus());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT reserved_quantity FROM food_inventory WHERE food_item_id = ?", Integer.class, foodItemId));
        assertEquals("BOOKED", jdbcTemplate.queryForObject("SELECT seat_status FROM showtime_seats WHERE showtime_id = ? AND seat_id = ?",
                String.class, testShowtime.getShowtimeId(), seatA2Id));
        assertTrue(bookingQrRepository.findByBooking_BookingId(lateBookingId).isEmpty());
        List<Ticket> conflictTickets = ticketRepository.findByBooking_BookingId(lateBookingId);
        assertEquals(1, conflictTickets.size());
        assertEquals("CONFLICT_UNISSUED", conflictTickets.get(0).getTicketStatus());

        // H2: PAID_SEAT_CONFLICT cannot updateFoodItems or confirmBooking (409) and preserves totalAmount = 200000
        mockMvc.perform(post("/api/bookings/" + lateBookingId + "/food-items").with(csrf())
                        .sessionAttr("currentUser", u1Dto).with(user(user1.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json").content("{\"foodItems\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_BOOKING_STATE"));
        mockMvc.perform(post("/api/bookings/" + lateBookingId + "/confirm").with(csrf())
                        .sessionAttr("currentUser", u1Dto).with(user(user1.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INVALID_BOOKING_STATE"));
        assertEquals(0, BigDecimal.valueOf(200000).compareTo(bookingRepository.findById(lateBookingId).orElseThrow().getTotalAmount()));

        // Refund flow works immediately on PAID_SEAT_CONFLICT / CONFLICT_UNISSUED without CINEMA_INCIDENT!
        MvcResult lookupRes = mockMvc.perform(post("/api/refunds/public/lookup-order").with(csrf())
                        .contentType("application/json")
                        .content("{\"orderCode\":\"" + lateB.getBookingCode() + "\",\"purchaseEmail\":\"" + user1.getEmail() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eligibleHint").value(true))
                .andReturn();
        assertTrue(lookupRes.getResponse().getContentAsString().contains("CONFLICT_UNISSUED"));

        MvcResult subRef = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .sessionAttr("currentUser", u1Dto)
                        .with(user(user1.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "orderCode", lateB.getBookingCode(),
                                "purchaseEmail", user1.getEmail(),
                                "reasonCategory", "CINEMA_INCIDENT",
                                "description", "Paid after seat conflict",
                                "toBin", "970422", "toAccountNumber", "0987654321",
                                "confirmAccountNumber", "0987654321",
                                "bankAccountName", "Nguyen Van Test",
                                "items", List.of(Map.of("itemKind", "TICKET", "targetId", conflictTickets.get(0).getTicketId(), "quantity", 1))))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode rNode = objectMapper.readTree(subRef.getResponse().getContentAsString());
        long rrId = rNode.get("id").asLong();
        long rrItemId = rNode.get("items").get(0).get("id").asLong();

        UserSummaryDto mDto = new UserSummaryDto(manager1.getUserId(), manager1.getFullName(), manager1.getEmail(), manager1.getPhone(), "MANAGER");
        mockMvc.perform(post("/api/manager/refunds/" + rrId + "/review").with(csrf())
                        .sessionAttr("currentUser", mDto)
                        .with(user(manager1.getEmail()).roles("MANAGER"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "action", "DECIDE_ITEMS",
                                "itemDecisions", List.of(Map.of("itemId", rrItemId, "decision", "APPROVED", "approvedQuantity", 1))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvedCashTotal").value(100000))
                .andExpect(jsonPath("$.payoutStatus").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.requestStatus").value("AWAITING_PAYOUT_APPROVAL"));

        UserSummaryDto aDto = new UserSummaryDto(admin1.getUserId(), admin1.getFullName(), admin1.getEmail(), admin1.getPhone(), "ADMIN");
        mockMvc.perform(post("/api/admin/refunds/" + rrId + "/payout/approve").with(csrf())
                        .sessionAttr("currentUser", aDto)
                        .with(user(admin1.getEmail()).roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payoutStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"));
    }

    private long createBookingViaApi(User actor, List<Long> seatIds, List<Map<String, Object>> foodItems, String holdSess) throws Exception {
        UserSummaryDto dto = new UserSummaryDto(actor.getUserId(), actor.getFullName(), actor.getEmail(), actor.getPhone(), actor.getRole());
        MvcResult res = mockMvc.perform(post("/api/bookings").with(csrf())
                        .sessionAttr("currentUser", dto)
                        .with(user(actor.getEmail()).roles(actor.getRole()))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "showtimeId", testShowtime.getShowtimeId(),
                                "seatIds", seatIds,
                                "foodItems", foodItems,
                                "holdSessionId", holdSess + "-" + TEST_TAG))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("bookingId").asLong();
    }

    private int attemptCreateBookingRaw(User actor, List<Long> seatIds, String holdSess) throws Exception {
        UserSummaryDto dto = new UserSummaryDto(actor.getUserId(), actor.getFullName(), actor.getEmail(), actor.getPhone(), actor.getRole());
        return mockMvc.perform(post("/api/bookings").with(csrf())
                        .sessionAttr("currentUser", dto)
                        .with(user(actor.getEmail()).roles(actor.getRole()))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "showtimeId", testShowtime.getShowtimeId(),
                                "seatIds", seatIds,
                                "holdSessionId", holdSess + "-" + TEST_TAG))))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode createPaymentViaApi(User actor, long bookingId) throws Exception {
        UserSummaryDto dto = new UserSummaryDto(actor.getUserId(), actor.getFullName(), actor.getEmail(), actor.getPhone(), actor.getRole());
        MvcResult res = mockMvc.perform(post("/api/payments/payos/create").with(csrf())
                        .sessionAttr("currentUser", dto)
                        .with(user(actor.getEmail()).roles(actor.getRole()))
                        .contentType("application/json")
                        .content("{\"bookingId\":" + bookingId + "}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private String buildSignedWebhookJson(long orderCode, Long amount, String desc, String ref, String code) throws Exception {
        Map<String, Object> data = new TreeMap<>();
        data.put("accountNumber", "0000111222");
        data.put("amount", amount);
        data.put("code", code);
        data.put("currency", "VND");
        data.put("desc", "00".equals(code) ? "success" : "cancelled");
        data.put("description", desc);
        data.put("orderCode", orderCode);
        data.put("paymentLinkId", "plink_" + orderCode);
        data.put("reference", ref);
        data.put("transactionDateTime", "2026-11-21 22:30:00");

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(e.getKey()).append("=").append(e.getValue() == null ? "" : String.valueOf(e.getValue()));
        }
        String sig = CryptoUtils.hmacSha256(sb.toString(), payosChecksumKey);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("desc", "00".equals(code) ? "success" : "cancelled");
        payload.put("data", data);
        payload.put("signature", sig);
        return objectMapper.writeValueAsString(payload);
    }

    private Long insertSeat(Long roomId, Long seatTypeId, String row, int num) {
        jdbcTemplate.update("INSERT INTO seats (room_id, seat_type_id, seat_row, seat_number, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                roomId, seatTypeId, row, num);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private User ensureUser(String email, String role, Long cinemaId) {
        User u = new User();
        u.setFullName("Col Test " + role);
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
