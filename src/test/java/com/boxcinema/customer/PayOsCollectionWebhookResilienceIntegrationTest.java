package com.boxcinema.customer;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BookingQrService;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.customer.controller.PaymentApiController;
import com.boxcinema.customer.service.FoodService;
import com.boxcinema.customer.service.impl.PayOsServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
class PayOsCollectionWebhookResilienceIntegrationTest {

    private static final String TEST_TAG = "COL_RES_" + System.currentTimeMillis();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PayOsServiceImpl payOsService;
    @Autowired private PaymentApiController paymentApiController;
    @Autowired private BookingQrService bookingQrService;
    @Autowired private FoodService foodService;
    @Autowired private PurchaseSnapshotService purchaseSnapshotService;
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
    @PersistenceContext private EntityManager entityManager;
    @MockBean private JavaMailSender mailSender;

    @Value("${payos.collection.checksum-key:${payos.checksum-key}}")
    private String collectionChecksumKey;

    private Cinema cinema;
    private Movie testMovie;
    private Room testRoom;
    private Showtime testShowtime;
    private Long seatA1Id;
    private Long seatA2Id;
    private Long seatA3Id;
    private Long seatA4Id;
    private User user1;
    private User user2;
    private Long foodItemId;
    private Long createdCategoryId;

    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> trackedOrderCodes = new ArrayList<>();

    @BeforeEach
    void setup() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        payOsService.setCustomTransportExecutorForTesting(null);
        ReflectionTestUtils.setField(payOsService, "bookingQrService", bookingQrService);
        ReflectionTestUtils.setField(payOsService, "collectionMode", "MOCK");
        ReflectionTestUtils.setField(paymentApiController, "collectionMode", "MOCK");
        ReflectionTestUtils.setField(paymentApiController, "allowSimulate", false);

        cinema = cinemaRepository.findAll().get(0);
        user1 = ensureUser("cr_u1_" + TEST_TAG + "@box.test", "CUSTOMER", null);
        user2 = ensureUser("cr_u2_" + TEST_TAG + "@box.test", "CUSTOMER", null);

        Movie m = new Movie();
        m.setTitle("Resilience Movie " + TEST_TAG);
        m.setDurationMinutes(120);
        m.setAgeRating("P");
        m.setLanguage("Subtitles");
        m.setReleaseDate(LocalDate.now().minusDays(1));
        m.setStatus("NOW_SHOWING");
        testMovie = movieRepository.save(m);

        testRoom = roomRepository.save(new Room(cinema, "CR-" + (System.currentTimeMillis() % 10000), "2D"));

        Map<String, Object> stType = jdbcTemplate.queryForMap(
                "SELECT seat_type_id, price_surcharge FROM seat_types ORDER BY price_surcharge ASC, seat_type_id ASC LIMIT 1");
        Long seatTypeId = ((Number) stType.get("seat_type_id")).longValue();
        BigDecimal surcharge = stType.get("price_surcharge") != null
                ? new BigDecimal(String.valueOf(stType.get("price_surcharge")))
                : BigDecimal.ZERO;

        seatA1Id = insertSeat(testRoom.getRoomId(), seatTypeId, "A", 1);
        seatA2Id = insertSeat(testRoom.getRoomId(), seatTypeId, "A", 2);
        seatA3Id = insertSeat(testRoom.getRoomId(), seatTypeId, "A", 3);
        seatA4Id = insertSeat(testRoom.getRoomId(), seatTypeId, "A", 4);

        testShowtime = showtimeRepository.save(new Showtime(
                testMovie,
                testRoom,
                LocalDateTime.now().plusDays(2),
                LocalDateTime.now().plusDays(2).plusHours(2),
                BigDecimal.valueOf(100000).subtract(surcharge)
        ));

        Long catId = jdbcTemplate.query("SELECT id FROM food_category LIMIT 1", (rs, i) -> rs.getLong(1))
                .stream().findFirst().orElseGet(() -> {
                    jdbcTemplate.update(
                            "INSERT INTO food_category (name, description, status, created_at, updated_at) VALUES (?, 'Res', 'ACTIVE', NOW(), NOW())",
                            "ColResCat-" + TEST_TAG);
                    createdCategoryId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                    return createdCategoryId;
                });
        jdbcTemplate.update(
                "INSERT INTO food_item (category_id, name, description, price, status, is_available, created_at, updated_at) VALUES (?, ?, 'Res', 50000.00, 'ACTIVE', 1, NOW(), NOW())",
                catId, "ResPop " + TEST_TAG);
        foodItemId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbcTemplate.update(
                "INSERT INTO food_inventory (food_item_id, on_hand_quantity, reserved_quantity, updated_at) VALUES (?, 300, 0, NOW())",
                foodItemId);
    }

    @AfterEach
    void cleanup() {
        payOsService.setCustomTransportExecutorForTesting(null);
        ReflectionTestUtils.setField(payOsService, "bookingQrService", bookingQrService);
        ReflectionTestUtils.setField(payOsService, "collectionMode", "MOCK");
        ReflectionTestUtils.setField(paymentApiController, "collectionMode", "MOCK");
        ReflectionTestUtils.setField(paymentApiController, "allowSimulate", false);

        for (Long oc : trackedOrderCodes) {
            jdbcTemplate.update("DELETE FROM payment_webhook_event WHERE order_code = ?", oc);
        }
        trackedOrderCodes.clear();

        if (testShowtime != null) {
            List<Long> stBookingIds = jdbcTemplate.queryForList(
                    "SELECT booking_id FROM bookings WHERE showtime_id = ?", Long.class, testShowtime.getShowtimeId());
            for (Long bid : stBookingIds) {
                if (!createdBookingIds.contains(bid)) {
                    createdBookingIds.add(bid);
                }
            }
        }
        for (Long bid : createdBookingIds) {
            jdbcTemplate.update("DELETE FROM email_outbox WHERE booking_id = ?", bid);
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
    void malformedPayloads_InvalidSignatures_ConfirmPing_StaleCookieBypass_And_CurrencyAnomaly() throws Exception {
        long bId = createBookingViaApi(user1, List.of(seatA1Id), List.of(), "RES-VAL");
        createdBookingIds.add(bId);
        JsonNode p = createPaymentViaApi(user1, bId);
        long orderCode = p.get("orderCode").asLong();
        long paymentId = p.get("paymentId").asLong();
        trackedOrderCodes.add(orderCode);

        // 1. Empty body and malformed JSON -> structured 400 {"code":"01",...}
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content("{broken_json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"))
                .andExpect(jsonPath("$.desc").value("Malformed webhook payload"));

        // 2. Missing data, non-object data ([] and string) -> 400
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content("{\"code\":\"00\",\"desc\":\"ok\",\"signature\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content("{\"code\":\"00\",\"data\":[\"bad\"],\"signature\":\"" + signDataMap(Map.of()) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content("{\"code\":\"00\",\"data\":\"not-an-object\",\"signature\":\"" + signDataMap(Map.of()) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        // 3. Valid HMAC but missing orderCode / orderCode <= 0 / missing status code -> 400
        Map<String, Object> noOrderData = new TreeMap<>();
        noOrderData.put("amount", 100000);
        noOrderData.put("code", "00");
        noOrderData.put("reference", "NO-OC");
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(wrapSignedPayload("00", "ok", noOrderData, signDataMap(noOrderData))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        Map<String, Object> zeroOrderData = new TreeMap<>();
        zeroOrderData.put("orderCode", 0);
        zeroOrderData.put("amount", 100000);
        zeroOrderData.put("code", "00");
        zeroOrderData.put("reference", "ZERO-OC");
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(wrapSignedPayload("00", "ok", zeroOrderData, signDataMap(zeroOrderData))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        Map<String, Object> missingCodeData = new TreeMap<>();
        missingCodeData.put("orderCode", orderCode);
        missingCodeData.put("amount", 100000);
        missingCodeData.put("reference", "MISS-CODE");
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(wrapSignedPayload(null, "ok", missingCodeData, signDataMap(missingCodeData))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        // 4. Empty / truncated signature -> 400; stale JSESSIONID cookie still bypasses SessionValidationFilter cleanly
        String validWebhook = buildSignedWebhookJson(orderCode, 100000L, "BOX", "TXN-VALID-STALE", "00", "VND");
        String emptySigWebhook = validWebhook.replaceAll("\"signature\":\"[^\"]+\"", "\"signature\":\"\"");
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .cookie(new Cookie("JSESSIONID", "STALE_REVOKED_SESSION_999"))
                        .contentType("application/json")
                        .content(emptySigWebhook))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("01"));

        String truncatedSigWebhook = validWebhook.replaceAll("\"signature\":\"([0-9a-f]{10})[^\"]+\"", "\"signature\":\"$1\"");
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(truncatedSigWebhook))
                .andExpect(status().isBadRequest());

        assertEquals("PENDING", paymentRepository.findById(paymentId).orElseThrow().getPaymentStatus());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_event WHERE order_code = ?", Integer.class, orderCode));

        // 5. PayOS confirm-webhook verification ping (orderCode=123) -> 200 CONFIRM_WEBHOOK_TEST, zero payment rows created
        trackedOrderCodes.add(123L);
        int paymentsBeforeConfirm = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Integer.class);
        Map<String, Object> confirmData = new TreeMap<>();
        confirmData.put("accountNumber", "12345678");
        confirmData.put("amount", 3000);
        confirmData.put("code", "00");
        confirmData.put("currency", "VND");
        confirmData.put("desc", "success");
        confirmData.put("description", "VQRIO123");
        confirmData.put("orderCode", 123);
        confirmData.put("paymentLinkId", "124c33293c43417ab7879e14c8d9eb18");
        confirmData.put("reference", "TF230204212323");
        confirmData.put("transactionDateTime", "2023-02-04 18:25:00");
        confirmData.put("counterAccountBankName", "MBBank");
        Map<String, Object> confirmEnvelope = new LinkedHashMap<>();
        confirmEnvelope.put("code", "00");
        confirmEnvelope.put("desc", "success");
        confirmEnvelope.put("success", true);
        confirmEnvelope.put("data", confirmData);
        confirmEnvelope.put("signature", signDataMap(confirmData));

        mockMvc.perform(post("/api/payments/payos/webhook")
                        .cookie(new Cookie("JSESSIONID", "STALE_REVOKED_SESSION_999"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(confirmEnvelope)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"));
        assertEquals(paymentsBeforeConfirm, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Integer.class));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_event WHERE order_code = 123 AND processing_status = 'CONFIRM_WEBHOOK_TEST'",
                Integer.class) >= 1);

        // 6. Valid HMAC with non-VND currency ("USD") -> quarantined REVIEW_REQUIRED / PAYMENT_REVIEW (CURRENCY_MISMATCH)
        String usdWebhook = buildSignedWebhookJson(orderCode, 100000L, "BOX", "TXN-USD-1", "00", "USD");
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(usdWebhook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"));
        assertEquals("REVIEW_REQUIRED", paymentRepository.findById(paymentId).orElseThrow().getPaymentStatus());
        assertEquals("PAYMENT_REVIEW", bookingRepository.findById(bId).orElseThrow().getBookingStatus());
        assertEquals(0, ticketRepository.findByBooking_BookingId(bId).size());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_event WHERE payment_id = ? AND processing_status = 'CURRENCY_MISMATCH'",
                Integer.class, paymentId));

        // 7. Out-of-order valid 00 VND webhook arriving AFTER PAYMENT_REVIEW -> must NEVER auto-confirm!
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(validWebhook))
                .andExpect(status().isOk());
        assertEquals("REVIEW_REQUIRED", paymentRepository.findById(paymentId).orElseThrow().getPaymentStatus());
        assertEquals("PAYMENT_REVIEW", bookingRepository.findById(bId).orElseThrow().getBookingStatus());
        assertEquals(0, ticketRepository.findByBooking_BookingId(bId).size());
    }

    @Test
    void rapidFireBurst_BlankReferenceDeterminism_TransientFailureRetry_And_OutOfOrderAfterCancel() throws Exception {
        // 1. 10-thread rapid-fire concurrent burst with mixed identical & distinct references -> 0 500s, 1 ticket/QR/email/event
        long bBurstId = createBookingViaApi(user1, List.of(seatA1Id), List.of(Map.of("foodItemId", foodItemId, "quantity", 1)), "BURST");
        createdBookingIds.add(bBurstId);
        JsonNode pBurst = createPaymentViaApi(user1, bBurstId);
        long burstOc = pBurst.get("orderCode").asLong();
        long burstPayId = pBurst.get("paymentId").asLong();
        trackedOrderCodes.add(burstOc);

        String whBlankRef = buildSignedWebhookJson(burstOc, 150000L, "BOX", null, "00", "VND");
        String whRefA = buildSignedWebhookJson(burstOc, 150000L, "BOX", "BURST-REF-A", "00", "VND");
        String whRefB = buildSignedWebhookJson(burstOc, 150000L, "BOX", "BURST-REF-B", "00", "VND");

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final String payload = (i % 3 == 0) ? whBlankRef : (i % 3 == 1 ? whRefA : whRefB);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return mockMvc.perform(post("/api/payments/payos/webhook")
                                    .contentType("application/json")
                                    .content(payload))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<Integer> f : futures) {
                assertEquals(200, f.get(20, TimeUnit.SECONDS), "Every concurrent burst webhook must return 200 (zero UnexpectedRollbackException)");
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals("CONFIRMED", bookingRepository.findById(bBurstId).orElseThrow().getBookingStatus());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE booking_id = ? AND payment_status = 'PAID'", Integer.class, bBurstId));
        assertEquals(1, ticketRepository.findByBooking_BookingId(bBurstId).size());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM booking_qr WHERE booking_id = ?", Integer.class, bBurstId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM food_fulfillment WHERE booking_id = ?", Integer.class, bBurstId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_outbox WHERE booking_id = ?", Integer.class, bBurstId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_event WHERE payment_id = ? AND processing_status = 'PROCESSED'",
                Integer.class, burstPayId));

        // 2. Deterministic fallback reference when reference is blank on non-00 retries -> single IGNORED_NON_SUCCESS row
        long bNoRefId = createBookingViaApi(user2, List.of(seatA2Id), List.of(), "NOREF");
        createdBookingIds.add(bNoRefId);
        JsonNode pNoRef = createPaymentViaApi(user2, bNoRefId);
        long noRefOc = pNoRef.get("orderCode").asLong();
        long noRefPayId = pNoRef.get("paymentId").asLong();
        trackedOrderCodes.add(noRefOc);

        String non00BlankRef = buildSignedWebhookJson(noRefOc, 100000L, "BOX", "", "01", "VND");
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(non00BlankRef))
                .andExpect(status().isOk());
        Thread.sleep(15);
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(non00BlankRef))
                .andExpect(status().isOk());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_event WHERE payment_id = ? AND processing_status = 'IGNORED_NON_SUCCESS'",
                Integer.class, noRefPayId));

        // 3. Transient processing failure (HTTP 500 masked, full TX rollback) -> retry same webhook -> 200 CONFIRMED
        long bRetryId = createBookingViaApi(user2, List.of(seatA3Id), List.of(), "RETRY-500");
        createdBookingIds.add(bRetryId);
        JsonNode pRetry = createPaymentViaApi(user2, bRetryId);
        long retryOc = pRetry.get("orderCode").asLong();
        long retryPayId = pRetry.get("paymentId").asLong();
        trackedOrderCodes.add(retryOc);
        String retryWebhook = buildSignedWebhookJson(retryOc, 100000L, "BOX", "TXN-RETRY-500", "00", "VND");

        AtomicBoolean failOnce = new AtomicBoolean(true);
        ReflectionTestUtils.setField(payOsService, "bookingQrService", new BookingQrService() {
            @Override
            public BookingQr getOrCreateBookingQr(Long bookingId) {
                if (failOnce.compareAndSet(true, false)) {
                    throw new RuntimeException("SENSITIVE_DB_POOL_EXHAUSTED_10.0.0.5");
                }
                return bookingQrService.getOrCreateBookingQr(bookingId);
            }
            @Override
            public byte[] getBookingQrPngBytes(Long bookingId) {
                return bookingQrService.getBookingQrPngBytes(bookingId);
            }
            @Override
            public String getBookingTicketUrl(Long bookingId) {
                return bookingQrService.getBookingTicketUrl(bookingId);
            }
        });
        try {
            MvcResult errRes = mockMvc.perform(post("/api/payments/payos/webhook")
                            .contentType("application/json")
                            .content(retryWebhook))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("99"))
                    .andExpect(jsonPath("$.desc").value("Internal webhook error"))
                    .andReturn();
            assertFalse(errRes.getResponse().getContentAsString().contains("SENSITIVE_DB_POOL_EXHAUSTED"));
        } finally {
            ReflectionTestUtils.setField(payOsService, "bookingQrService", bookingQrService);
        }

        // Verify clean rollback: still PENDING/PENDING_PAYMENT, 0 tickets, 0 QR, 0 email, 0 webhook event
        assertEquals("PENDING", paymentRepository.findById(retryPayId).orElseThrow().getPaymentStatus());
        assertEquals("PENDING_PAYMENT", bookingRepository.findById(bRetryId).orElseThrow().getBookingStatus());
        assertEquals(0, ticketRepository.findByBooking_BookingId(bRetryId).size());
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_event WHERE payment_id = ?", Integer.class, retryPayId));

        // Retry identical webhook -> 200 CONFIRMED
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(retryWebhook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"));
        assertEquals("PAID", paymentRepository.findById(retryPayId).orElseThrow().getPaymentStatus());
        assertEquals("CONFIRMED", bookingRepository.findById(bRetryId).orElseThrow().getBookingStatus());
        assertEquals(1, ticketRepository.findByBooking_BookingId(bRetryId).size());
        assertTrue(bookingQrRepository.findByBooking_BookingId(bRetryId).isPresent());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM email_outbox WHERE booking_id = ?", Integer.class, bRetryId));

        // 4. Out-of-order 00 webhook arriving after CANCELLED payment -> quarantined REVIEW_REQUIRED / PAYMENT_REVIEW
        long bCxlId = createBookingViaApi(user1, List.of(seatA4Id), List.of(), "OOO-CXL");
        createdBookingIds.add(bCxlId);
        JsonNode pCxl = createPaymentViaApi(user1, bCxlId);
        long cxlOc = pCxl.get("orderCode").asLong();
        long cxlPayId = pCxl.get("paymentId").asLong();
        trackedOrderCodes.add(cxlOc);

        jdbcTemplate.update("UPDATE payments SET payment_status = 'CANCELLED' WHERE payment_id = ?", cxlPayId);
        jdbcTemplate.update("UPDATE bookings SET booking_status = 'CANCELLED' WHERE booking_id = ?", bCxlId);

        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(buildSignedWebhookJson(cxlOc, 100000L, "BOX", "TXN-AFTER-CXL", "00", "VND")))
                .andExpect(status().isOk());
        assertEquals("REVIEW_REQUIRED", paymentRepository.findById(cxlPayId).orElseThrow().getPaymentStatus());
        assertEquals("PAYMENT_REVIEW", bookingRepository.findById(bCxlId).orElseThrow().getBookingStatus());
        assertEquals(0, ticketRepository.findByBooking_BookingId(bCxlId).size());
    }

    @Test
    void restartDurability_FreshServiceStateRecovery_ReconcilerGate_And_TunnelUrlDisconnectReconnect() throws Exception {
        // 1. Env-driven returnUrl / cancelUrl propagation in outbound POST /v2/payment-requests
        long bUrlId = createBookingViaApi(user1, List.of(seatA1Id), List.of(), "NGROK-URL");
        createdBookingIds.add(bUrlId);

        List<Map<String, Object>> capturedRequests = Collections.synchronizedList(new ArrayList<>());
        PayOsServiceImpl freshSvc = new PayOsServiceImpl(
                bookingRepository, paymentRepository, webhookEventRepository, ticketRepository,
                bookingQrRepository, bookingQrService, foodFulfillmentRepository, emailOutboxRepository,
                foodService, purchaseSnapshotService, jdbcTemplate, transactionManager, userRepository);
        ReflectionTestUtils.setField(freshSvc, "collectionMode", "LIVE");
        ReflectionTestUtils.setField(freshSvc, "clientId", "live-test-cid");
        ReflectionTestUtils.setField(freshSvc, "apiKey", "live-test-api");
        ReflectionTestUtils.setField(freshSvc, "checksumKey", collectionChecksumKey);
        ReflectionTestUtils.setField(freshSvc, "returnUrl", "https://reconnected-tunnel.ngrok-free.dev/booking/buy-tickets");
        ReflectionTestUtils.setField(freshSvc, "cancelUrl", "https://reconnected-tunnel.ngrok-free.dev/booking/buy-tickets");
        ReflectionTestUtils.setField(freshSvc, "entityManager", entityManager);
        freshSvc.setCustomTransportExecutorForTesting(new PayOsServiceImpl.CollectionTransportExecutor() {
            @Override
            public ResponseEntity<String> post(String url, HttpHeaders headers, Map<String, Object> body) {
                capturedRequests.add(new LinkedHashMap<>(body));
                long oc = ((Number) body.get("orderCode")).longValue();
                return ResponseEntity.ok("{\"code\":\"00\",\"desc\":\"success\",\"data\":{\"paymentLinkId\":\"plink_" + oc
                        + "\",\"qrCode\":\"MOCK_TUNNEL_QR\",\"checkoutUrl\":\"https://pay.payos.vn/web/plink_" + oc
                        + "\",\"bin\":\"970422\",\"accountNumber\":\"0000111222\",\"accountName\":\"BOX CINEMA\"}}");
            }
            @Override
            public ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
                try {
                    long oc = Long.parseLong(url.substring(url.lastIndexOf('/') + 1));
                    return ResponseEntity.ok(objectMapper.readTree(
                            "{\"code\":\"00\",\"data\":{\"id\":\"plink_" + oc + "\",\"status\":\"PAID\",\"amount\":100000,\"amountPaid\":100000,\"transactions\":[{\"reference\":\"TUNNEL-FALLBACK-" + oc + "\"}]}}"));
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        var createdPay = freshSvc.createPayment(bUrlId, user1.getUserId());
        long tunnelOc = createdPay.getOrderCode();
        trackedOrderCodes.add(tunnelOc);
        assertEquals(1, capturedRequests.size());
        assertEquals("https://reconnected-tunnel.ngrok-free.dev/booking/buy-tickets", capturedRequests.get(0).get("returnUrl"));
        assertEquals("https://reconnected-tunnel.ngrok-free.dev/booking/buy-tickets", capturedRequests.get(0).get("cancelUrl"));
        assertFalse(String.valueOf(capturedRequests.get(0).get("returnUrl")).contains("absence-turret-chapped"));

        // 2. Tunnel disconnected (webhook unreachable) -> scheduledReconcileCollectionPayments (LIVE mode) recovers payment cleanly
        // First confirm MOCK mode gate skips reconciliation without touching payment
        ReflectionTestUtils.setField(freshSvc, "collectionMode", "MOCK");
        freshSvc.scheduledReconcileCollectionPayments();
        assertEquals("PENDING", paymentRepository.findByOrderCode(tunnelOc).orElseThrow().getPaymentStatus());

        // Enable LIVE mode on freshSvc (simulating fresh JVM / scheduler tick after tunnel drop)
        ReflectionTestUtils.setField(freshSvc, "collectionMode", "LIVE");
        freshSvc.scheduledReconcileCollectionPayments();
        assertEquals("CONFIRMED", bookingRepository.findById(bUrlId).orElseThrow().getBookingStatus());
        assertEquals("PAID", paymentRepository.findByOrderCode(tunnelOc).orElseThrow().getPaymentStatus());
        assertEquals(1, ticketRepository.findByBooking_BookingId(bUrlId).size());

        // 3. Restart durability: clear JPA L1 cache & build a brand-new PayOsServiceImpl instance (fresh in-memory maps)
        // Replay late webhook after tunnel reconnect + restart -> 200 idempotent, zero duplicate ticket/QR/email
        entityManager.clear();
        PayOsServiceImpl restartedSvc = new PayOsServiceImpl(
                bookingRepository, paymentRepository, webhookEventRepository, ticketRepository,
                bookingQrRepository, bookingQrService, foodFulfillmentRepository, emailOutboxRepository,
                foodService, purchaseSnapshotService, jdbcTemplate, transactionManager, userRepository);
        ReflectionTestUtils.setField(restartedSvc, "collectionMode", "MOCK");
        ReflectionTestUtils.setField(restartedSvc, "checksumKey", collectionChecksumKey);
        ReflectionTestUtils.setField(restartedSvc, "entityManager", entityManager);

        String lateReconnectWebhook = buildSignedWebhookJson(tunnelOc, 100000L, "BOX", "TXN-RECONNECT-1", "00", "VND");
        var parsedPayload = objectMapper.readValue(lateReconnectWebhook, com.boxcinema.customer.dto.response.PayOsWebhookPayloadDto.class);
        assertTrue(restartedSvc.verifyWebhookSignature(parsedPayload));
        restartedSvc.processWebhook(parsedPayload);

        assertEquals(1, ticketRepository.findByBooking_BookingId(bUrlId).size());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking_qr WHERE booking_id = ?", Integer.class, bUrlId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE booking_id = ?", Integer.class, bUrlId));

        // 4. Mid-processing EXPIRED payment created before restart is recovered by restartedSvc (empty lastExpiredReconcileCheckMs)
        long bMidId = createBookingViaApi(user2, List.of(seatA2Id), List.of(), "MID-RESTART");
        createdBookingIds.add(bMidId);
        JsonNode pMid = createPaymentViaApi(user2, bMidId);
        long midPayId = pMid.get("paymentId").asLong();
        long midOc = pMid.get("orderCode").asLong();
        trackedOrderCodes.add(midOc);
        jdbcTemplate.update("UPDATE payments SET payment_status = 'EXPIRED', expired_at = DATE_SUB(NOW(), INTERVAL 3 MINUTE) WHERE payment_id = ?", midPayId);
        jdbcTemplate.update("UPDATE bookings SET booking_status = 'EXPIRED', expires_at = DATE_SUB(NOW(), INTERVAL 3 MINUTE), payment_deadline = DATE_SUB(NOW(), INTERVAL 3 MINUTE) WHERE booking_id = ?", bMidId);
        jdbcTemplate.update("UPDATE booking_seats SET status = 'EXPIRED', active_seat_key = NULL WHERE booking_id = ?", bMidId);
        jdbcTemplate.update("UPDATE showtime_seats SET seat_status = 'AVAILABLE', held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL WHERE showtime_id = ? AND seat_id = ?",
                testShowtime.getShowtimeId(), seatA2Id);

        entityManager.clear();
        restartedSvc.setCustomTransportExecutorForTesting(new PayOsServiceImpl.CollectionTransportExecutor() {
            @Override
            public ResponseEntity<String> post(String url, HttpHeaders headers, Map<String, Object> body) {
                return ResponseEntity.ok("{}");
            }
            @Override
            public ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
                try {
                    long oc = Long.parseLong(url.substring(url.lastIndexOf('/') + 1));
                    if (oc == midOc) {
                        return ResponseEntity.ok(objectMapper.readTree(
                                "{\"code\":\"00\",\"data\":{\"id\":\"plink_" + oc + "\",\"status\":\"PAID\",\"amount\":100000,\"amountPaid\":100000,\"transactions\":[{\"reference\":\"RESTART-REC-" + oc + "\"}]}}"));
                    }
                    return ResponseEntity.ok(objectMapper.readTree("{\"code\":\"00\",\"data\":{\"status\":\"PENDING\"}}"));
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        assertTrue(restartedSvc.reconcilePendingCollectionPayments() >= 1);
        assertEquals("CONFIRMED", bookingRepository.findById(bMidId).orElseThrow().getBookingStatus());
        assertEquals("PAID", paymentRepository.findById(midPayId).orElseThrow().getPaymentStatus());
        assertEquals(1, ticketRepository.findByBooking_BookingId(bMidId).size());
    }

    private UserSummaryDto dtoOf(User u) {
        return new UserSummaryDto(u.getUserId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole());
    }

    private long createBookingViaApi(User actor, List<Long> seatIds, List<Map<String, Object>> foodItems, String holdSess) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/bookings").with(csrf())
                        .sessionAttr("currentUser", dtoOf(actor))
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

    private JsonNode createPaymentViaApi(User actor, long bookingId) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/payments/payos/create").with(csrf())
                        .sessionAttr("currentUser", dtoOf(actor))
                        .with(user(actor.getEmail()).roles(actor.getRole()))
                        .contentType("application/json")
                        .content("{\"bookingId\":" + bookingId + "}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private String buildSignedWebhookJson(long orderCode, Long amount, String desc, String ref, String code, String currency) throws Exception {
        Map<String, Object> data = new TreeMap<>();
        data.put("accountNumber", "0000111222");
        data.put("amount", amount);
        data.put("code", code);
        data.put("currency", currency);
        data.put("desc", "00".equals(code) ? "success" : "cancelled");
        data.put("description", desc);
        data.put("orderCode", orderCode);
        data.put("paymentLinkId", "plink_" + orderCode);
        if (ref != null) {
            data.put("reference", ref);
        }
        data.put("transactionDateTime", "2026-11-23 09:00:00");
        return wrapSignedPayload(code, "00".equals(code) ? "success" : "cancelled", data, signDataMap(data));
    }

    private String wrapSignedPayload(String code, String desc, Map<String, Object> data, String sig) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (code != null) {
            payload.put("code", code);
        }
        payload.put("desc", desc);
        payload.put("data", data);
        payload.put("signature", sig);
        return objectMapper.writeValueAsString(payload);
    }

    private String signDataMap(Map<String, Object> data) throws Exception {
        TreeMap<String, Object> sorted = new TreeMap<>(data);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            Object val = e.getValue();
            String strVal;
            if (val == null) strVal = "";
            else if (val instanceof Map || val instanceof Collection) strVal = objectMapper.writeValueAsString(val);
            else strVal = String.valueOf(val);
            sb.append(e.getKey()).append("=").append(strVal);
        }
        return CryptoUtils.hmacSha256(sb.toString(), collectionChecksumKey);
    }

    private Long insertSeat(Long roomId, Long seatTypeId, String row, int num) {
        jdbcTemplate.update("INSERT INTO seats (room_id, seat_type_id, seat_row, seat_number, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                roomId, seatTypeId, row, num);
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private User ensureUser(String email, String role, Long cinemaId) {
        User u = new User();
        u.setFullName("ColRes " + role);
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
