package com.boxcinema.customer;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.customer.controller.PaymentApiController;
import com.boxcinema.customer.dto.request.BookingComboRequestDto;
import com.boxcinema.customer.dto.request.BookingCreateRequestDto;
import com.boxcinema.customer.dto.request.BookingFoodItemRequestDto;
import com.boxcinema.customer.dto.response.PayOsCreatePaymentResponseDto;
import com.boxcinema.customer.dto.response.PayOsWebhookPayloadDto;
import com.boxcinema.customer.dto.response.PaymentStatusDto;
import com.boxcinema.customer.service.PayOsService;
import com.boxcinema.customer.service.SeatHoldCleanupService;
import com.boxcinema.customer.service.impl.EmailOutboxWorkerService;
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
import java.util.concurrent.atomic.AtomicInteger;

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
class PayOsCollectionFullAuditIntegrationTest {

    private static final String TEST_TAG = "COL_AUD_" + System.currentTimeMillis();

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PayOsServiceImpl payOsService;
    @Autowired private PaymentApiController paymentApiController;
    @Autowired private SeatHoldCleanupService seatHoldCleanupService;
    @Autowired private EmailOutboxWorkerService emailOutboxWorkerService;
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
    @Autowired private FoodItemRepository foodItemRepository;
    @Autowired private FoodVariantRepository foodVariantRepository;
    @Autowired private FoodComboRepository foodComboRepository;
    @Autowired private FoodComboItemRepository foodComboItemRepository;
    @Autowired private FoodInventoryRepository foodInventoryRepository;
    @Autowired private PromotionRepository promotionRepository;
    @Autowired private EmailOutboxRepository emailOutboxRepository;
    @Autowired private PaymentWebhookEventRepository webhookEventRepository;
    @MockBean private JavaMailSender mailSender;

    @Value("${payos.collection.checksum-key:${payos.checksum-key}}")
    private String collectionChecksumKey;

    @Value("${payos.payout.checksum-key:MOCK_PAYOUT_CHECKSUM_KEY}")
    private String payoutChecksumKey;

    private Cinema cinema;
    private Movie testMovie;
    private Room testRoom;
    private Room otherRoom;
    private Showtime testShowtime;
    private Long seatA1Id;
    private Long seatA2Id;
    private Long seatA3Id;
    private Long seatA4Id;
    private Long seatOtherRoomId;
    private User user1;
    private User user2;
    private Long foodItem1Id;
    private Long foodItem2Id;
    private Long createdCategoryId;
    private Long variantActiveId;
    private Long variantInactiveId;
    private Long comboId;

    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdPromoIds = new ArrayList<>();
    private final List<Long> unknownOrderCodes = new ArrayList<>();

    @BeforeEach
    void setup() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        payOsService.setCustomTransportExecutorForTesting(null);
        ReflectionTestUtils.setField(payOsService, "collectionMode", "MOCK");
        ReflectionTestUtils.setField(paymentApiController, "collectionMode", "MOCK");
        ReflectionTestUtils.setField(paymentApiController, "allowSimulate", false);
        emailOutboxWorkerService.setLeaseTimeoutSecondsForTesting(300L);

        cinema = cinemaRepository.findAll().get(0);
        user1 = ensureUser("ca_u1_" + TEST_TAG + "@box.test", "CUSTOMER", null);
        user2 = ensureUser("ca_u2_" + TEST_TAG + "@box.test", "CUSTOMER", null);

        Movie m = new Movie();
        m.setTitle("Audit Movie " + TEST_TAG);
        m.setDurationMinutes(120);
        m.setAgeRating("P");
        m.setLanguage("Subtitles");
        m.setReleaseDate(LocalDate.now().minusDays(1));
        m.setStatus("NOW_SHOWING");
        testMovie = movieRepository.save(m);

        testRoom = roomRepository.save(new Room(cinema, "CA1-" + (System.currentTimeMillis() % 10000), "2D"));
        otherRoom = roomRepository.save(new Room(cinema, "CA2-" + (System.currentTimeMillis() % 10000), "2D"));

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
        seatOtherRoomId = insertSeat(otherRoom.getRoomId(), seatTypeId, "Z", 9);

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
                            "INSERT INTO food_category (name, description, status, created_at, updated_at) VALUES (?, 'Audit', 'ACTIVE', NOW(), NOW())",
                            "ColAudCat-" + TEST_TAG);
                    createdCategoryId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
                    return createdCategoryId;
                });

        jdbcTemplate.update(
                "INSERT INTO food_item (category_id, name, description, price, status, is_available, created_at, updated_at) VALUES (?, ?, 'Audit1', 50000.00, 'ACTIVE', 1, NOW(), NOW())",
                catId, "AudPop1 " + TEST_TAG);
        foodItem1Id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        jdbcTemplate.update(
                "INSERT INTO food_item (category_id, name, description, price, status, is_available, created_at, updated_at) VALUES (?, ?, 'Audit2', 30000.00, 'ACTIVE', 1, NOW(), NOW())",
                catId, "AudDrink2 " + TEST_TAG);
        foodItem2Id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        FoodItem f1 = foodItemRepository.findById(foodItem1Id).orElseThrow();
        FoodItem f2 = foodItemRepository.findById(foodItem2Id).orElseThrow();
        foodInventoryRepository.save(new FoodInventory(f1, 300, 0));
        foodInventoryRepository.save(new FoodInventory(f2, 300, 0));

        FoodVariant vActive = foodVariantRepository.save(new FoodVariant(f1, "XL", BigDecimal.valueOf(75000)));
        variantActiveId = vActive.getId();
        FoodVariant vInactive = new FoodVariant(f2, "Discontinued", BigDecimal.valueOf(10000));
        vInactive.setStatus("INACTIVE");
        variantInactiveId = foodVariantRepository.save(vInactive).getId();

        FoodCombo combo = new FoodCombo();
        combo.setName("AudCombo " + TEST_TAG);
        combo.setPrice(BigDecimal.valueOf(70000));
        combo.setStatus("ACTIVE");
        combo.setAvailable(true);
        combo = foodComboRepository.save(combo);
        comboId = combo.getId();

        FoodComboItem ci2 = new FoodComboItem();
        ci2.setCombo(combo);
        ci2.setFoodItem(f2);
        ci2.setQuantity(1);
        foodComboItemRepository.save(ci2);

        FoodComboItem ci1 = new FoodComboItem();
        ci1.setCombo(combo);
        ci1.setFoodItem(f1);
        ci1.setQuantity(1);
        foodComboItemRepository.save(ci1);
    }

    @AfterEach
    void cleanup() {
        payOsService.setCustomTransportExecutorForTesting(null);
        ReflectionTestUtils.setField(payOsService, "collectionMode", "MOCK");
        ReflectionTestUtils.setField(paymentApiController, "collectionMode", "MOCK");
        ReflectionTestUtils.setField(paymentApiController, "allowSimulate", false);
        emailOutboxWorkerService.setLeaseTimeoutSecondsForTesting(300L);

        for (Long oc : unknownOrderCodes) {
            jdbcTemplate.update("DELETE FROM payment_webhook_event WHERE order_code = ?", oc);
        }
        unknownOrderCodes.clear();

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

        for (Long pid : createdPromoIds) {
            jdbcTemplate.update("DELETE FROM promotions WHERE promotion_id = ?", pid);
        }
        createdPromoIds.clear();

        if (comboId != null) {
            jdbcTemplate.update("DELETE FROM food_combo_item WHERE combo_id = ?", comboId);
            jdbcTemplate.update("DELETE FROM food_combo WHERE id = ?", comboId);
            comboId = null;
        }
        if (variantActiveId != null) {
            jdbcTemplate.update("DELETE FROM food_variant WHERE id = ?", variantActiveId);
            variantActiveId = null;
        }
        if (variantInactiveId != null) {
            jdbcTemplate.update("DELETE FROM food_variant WHERE id = ?", variantInactiveId);
            variantInactiveId = null;
        }
        if (foodItem1Id != null) {
            jdbcTemplate.update("DELETE FROM food_inventory WHERE food_item_id = ?", foodItem1Id);
            jdbcTemplate.update("DELETE FROM food_item WHERE id = ?", foodItem1Id);
            foodItem1Id = null;
        }
        if (foodItem2Id != null) {
            jdbcTemplate.update("DELETE FROM food_inventory WHERE food_item_id = ?", foodItem2Id);
            jdbcTemplate.update("DELETE FROM food_item WHERE id = ?", foodItem2Id);
            foodItem2Id = null;
        }
        if (createdCategoryId != null) {
            jdbcTemplate.update("DELETE FROM food_category WHERE id = ?", createdCategoryId);
            createdCategoryId = null;
        }
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
        if (otherRoom != null) {
            jdbcTemplate.update("DELETE FROM seats WHERE room_id = ?", otherRoom.getRoomId());
            jdbcTemplate.update("DELETE FROM rooms WHERE room_id = ?", otherRoom.getRoomId());
            otherRoom = null;
        }
        if (testMovie != null) {
            jdbcTemplate.update("DELETE FROM movies WHERE movie_id = ?", testMovie.getMovieId());
            testMovie = null;
        }
        for (Long uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", uid);
        }
        createdUserIds.clear();
    }

    @Test
    void collectionMatrix_MockQrMasking_Success_NonSuccess_ExpiredQr_SignatureTampering_UnknownOrder_And_AmountAnomalies() throws Exception {
        // 1. Isolated MOCK mode generates local VietQR without calling external HTTP and without personal bank info
        long bOkId = createBookingViaApi(user1, List.of(seatA1Id), List.of(Map.of("foodItemId", foodItem1Id, "quantity", 1)), "M-OK");
        createdBookingIds.add(bOkId);
        JsonNode pOk = createPaymentViaApi(user1, bOkId);
        long okPaymentId = pOk.get("paymentId").asLong();
        long okOrderCode = pOk.get("orderCode").asLong();
        assertTrue(pOk.get("qrCode").asText().startsWith("000201"));
        assertEquals("0000000000", pOk.get("accountNumber").asText());
        assertEquals("BOX CINEMA MOCK", pOk.get("accountName").asText());

        // Unpaid status poll
        mockMvc.perform(get("/api/payments/" + okPaymentId + "/status")
                        .sessionAttr("currentUser", dtoOf(user1))
                        .with(user(user1.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid").value(false))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.bookingStatus").value("PENDING_PAYMENT"));

        // Forged / missing / Payout-key-signed / tampered webhook -> 400
        String missingSig = "{\"code\":\"00\",\"desc\":\"success\",\"data\":{\"orderCode\":" + okOrderCode + ",\"amount\":150000,\"code\":\"00\"}}";
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(missingSig))
                .andExpect(status().isBadRequest());

        String payoutForgedSig = buildSignedWebhookJson(okOrderCode, 150000L, "BOX", "TXN-PO-FORGE", "00", payoutChecksumKey, null);
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(payoutForgedSig))
                .andExpect(status().isBadRequest());

        // Non-success webhook (code=01) -> 200 IGNORED_NON_SUCCESS, stays PENDING/PENDING_PAYMENT
        String cancelWebhook = buildSignedWebhookJson(okOrderCode, 150000L, "BOX", "TXN-CXL", "01", collectionChecksumKey, null);
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(cancelWebhook))
                .andExpect(status().isOk());
        assertEquals("PENDING", paymentRepository.findById(okPaymentId).orElseThrow().getPaymentStatus());
        assertEquals("PENDING_PAYMENT", bookingRepository.findById(bOkId).orElseThrow().getBookingStatus());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_event WHERE payment_id = ? AND processing_status = 'IGNORED_NON_SUCCESS'",
                Integer.class, okPaymentId));

        // Unknown orderCode -> 200 & UNKNOWN_ORDER_CODE audit event
        long unknownOc = 999888777666L;
        unknownOrderCodes.add(unknownOc);
        String unknownWebhook = buildSignedWebhookJson(unknownOc, 100000L, "BOX", "TXN-UNK", "00", collectionChecksumKey, null);
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(unknownWebhook))
                .andExpect(status().isOk());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_event WHERE order_code = ? AND processing_status = 'UNKNOWN_ORDER_CODE' AND payment_id IS NULL",
                Integer.class, unknownOc));

        // Valid webhook with nested object/array in rawData (tests computeWebhookSignature JSON serialization) -> CONFIRMED + Ticket.seatId populated
        String validNestedWebhook = buildSignedWebhookJson(okOrderCode, 150000L, "BOX", "TXN-OK-1", "00",
                collectionChecksumKey, Map.of("extraMeta", List.of("a", "b")));
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(validNestedWebhook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"));
        assertEquals("CONFIRMED", bookingRepository.findById(bOkId).orElseThrow().getBookingStatus());
        List<Ticket> issuedTickets = ticketRepository.findByBooking_BookingId(bOkId);
        assertEquals(1, issuedTickets.size());
        assertEquals(seatA1Id, issuedTickets.get(0).getSeatId());
        assertTrue(bookingQrRepository.findByBooking_BookingId(bOkId).isPresent());
        assertTrue(foodFulfillmentRepository.findByBooking_BookingId(bOkId).isPresent());
        assertTrue(emailOutboxRepository.existsByDedupKey("BOOKING_CONFIRMATION:" + bOkId));

        // Expired QR via gateway GET EXPIRED
        long bExpId = createBookingViaApi(user2, List.of(seatA2Id), List.of(), "M-EXPQR");
        createdBookingIds.add(bExpId);
        JsonNode pExp = createPaymentViaApi(user2, bExpId);
        long expPaymentId = pExp.get("paymentId").asLong();
        payOsService.setCustomTransportExecutorForTesting(new PayOsServiceImpl.CollectionTransportExecutor() {
            @Override
            public ResponseEntity<String> post(String url, HttpHeaders headers, Map<String, Object> body) {
                return ResponseEntity.ok("{}");
            }
            @Override
            public ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
                try {
                    return ResponseEntity.ok(objectMapper.readTree("{\"code\":\"00\",\"data\":{\"status\":\"EXPIRED\"}}"));
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        mockMvc.perform(get("/api/payments/" + expPaymentId + "/status")
                        .sessionAttr("currentUser", dtoOf(user2))
                        .with(user(user2.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid").value(false))
                .andExpect(jsonPath("$.expired").value(true))
                .andExpect(jsonPath("$.status").value("EXPIRED"));
        payOsService.setCustomTransportExecutorForTesting(null);

        // Booking totalAmount vs payment amount mismatch -> REVIEW_REQUIRED / PAYMENT_REVIEW
        long bMisId = createBookingViaApi(user2, List.of(seatA3Id), List.of(), "M-BOOKMIS");
        createdBookingIds.add(bMisId);
        JsonNode pMis = createPaymentViaApi(user2, bMisId);
        long misOrderCode = pMis.get("orderCode").asLong();
        jdbcTemplate.update("UPDATE bookings SET total_amount = 120000.00 WHERE booking_id = ?", bMisId);
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(buildSignedWebhookJson(misOrderCode, 100000L, "BOX", "TXN-BMIS", "00", collectionChecksumKey, null)))
                .andExpect(status().isOk());
        assertEquals("REVIEW_REQUIRED", paymentRepository.findByOrderCode(misOrderCode).orElseThrow().getPaymentStatus());
        assertEquals("PAYMENT_REVIEW", bookingRepository.findById(bMisId).orElseThrow().getBookingStatus());
        assertEquals(0, ticketRepository.findByBooking_BookingId(bMisId).size());
    }

    @Test
    void collectionMatrix_ConcurrentDoubleClicks_CartChangeSuperseded_PollVsWebhookRace_And_LiveTimeoutVs503() throws Exception {
        // 1. 20-thread concurrent createPayment double-click -> all 20 get valid non-null QR & exact same single orderCode
        long bDoubleId = createBookingViaApi(user1, List.of(seatA1Id), List.of(), "M-DBL");
        createdBookingIds.add(bDoubleId);

        payOsService.setCustomTransportExecutorForTesting(new PayOsServiceImpl.CollectionTransportExecutor() {
            @Override
            public ResponseEntity<String> post(String url, HttpHeaders headers, Map<String, Object> body) {
                try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                long oc = ((Number) body.get("orderCode")).longValue();
                return ResponseEntity.ok("{\"code\":\"00\",\"desc\":\"success\",\"data\":{\"paymentLinkId\":\"plink_" + oc
                        + "\",\"qrCode\":\"QR_DBL_" + oc + "\",\"checkoutUrl\":\"https://pay.payos.vn/web/" + oc
                        + "\",\"bin\":\"970422\",\"accountNumber\":\"0000111222\",\"accountName\":\"BOX CINEMA\"}}");
            }
            @Override
            public ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
                try { return ResponseEntity.ok(objectMapper.readTree("{\"code\":\"00\",\"data\":{\"status\":\"PENDING\"}}")); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        });

        int clickThreads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(clickThreads);
        CountDownLatch ready = new CountDownLatch(clickThreads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<PayOsCreatePaymentResponseDto>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < clickThreads; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return payOsService.createPayment(bDoubleId, user1.getUserId());
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            Set<Long> observedOrderCodes = new HashSet<>();
            for (Future<PayOsCreatePaymentResponseDto> f : futures) {
                PayOsCreatePaymentResponseDto res = f.get(20, TimeUnit.SECONDS);
                assertNotNull(res.getQrCode());
                assertFalse(res.getQrCode().isBlank());
                observedOrderCodes.add(res.getOrderCode());
            }
            assertEquals(1, observedOrderCodes.size(), "All 20 concurrent createPayment calls must converge to 1 orderCode");
            assertEquals(1, paymentRepository.findByBooking_BookingIdOrderByPaymentIdDesc(bDoubleId).size());
        } finally {
            pool.shutdownNow();
        }

        // 2. Cart change while PENDING -> old QR marked SUPERSEDED (and cancel called), paying old QR flags REVIEW_REQUIRED / SUPERSEDED_PAYMENT_PAID
        long oldOrderCode = paymentRepository.findTopByBooking_BookingIdOrderByPaymentIdDesc(bDoubleId).orElseThrow().getOrderCode();
        List<Long> cancelledOrderCodes = Collections.synchronizedList(new ArrayList<>());
        payOsService.setCustomTransportExecutorForTesting(new PayOsServiceImpl.CollectionTransportExecutor() {
            @Override
            public ResponseEntity<String> post(String url, HttpHeaders headers, Map<String, Object> body) {
                long oc = ((Number) body.get("orderCode")).longValue();
                return ResponseEntity.ok("{\"code\":\"00\",\"desc\":\"success\",\"data\":{\"paymentLinkId\":\"plink_" + oc
                        + "\",\"qrCode\":\"QR_NEW_" + oc + "\",\"checkoutUrl\":\"https://pay.payos.vn/web/" + oc
                        + "\",\"bin\":\"970422\",\"accountNumber\":\"0000111222\",\"accountName\":\"BOX CINEMA\"}}");
            }
            @Override
            public ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
                try { return ResponseEntity.ok(objectMapper.readTree("{\"code\":\"00\",\"data\":{\"status\":\"PENDING\"}}")); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override
            public ResponseEntity<String> cancel(String url, HttpHeaders headers, Map<String, Object> body) {
                String tail = url.replace("/cancel", "");
                cancelledOrderCodes.add(Long.parseLong(tail.substring(tail.lastIndexOf('/') + 1)));
                return ResponseEntity.ok("{\"code\":\"00\"}");
            }
        });

        mockMvc.perform(post("/api/bookings/" + bDoubleId + "/food-items").with(csrf())
                        .sessionAttr("currentUser", dtoOf(user1))
                        .with(user(user1.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of("foodItems", List.of(Map.of("foodItemId", foodItem1Id, "quantity", 1))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalAmount").value(150000));
        assertEquals("SUPERSEDED", paymentRepository.findByOrderCode(oldOrderCode).orElseThrow().getPaymentStatus());

        JsonNode newPay = createPaymentViaApi(user1, bDoubleId);
        long newOrderCode = newPay.get("orderCode").asLong();
        assertNotEquals(oldOrderCode, newOrderCode);

        // Customer pays OLD superseded QR -> REVIEW_REQUIRED + PAYMENT_REVIEW + SUPERSEDED_PAYMENT_PAID!
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(buildSignedWebhookJson(oldOrderCode, 100000L, "BOX", "TXN-SUP-OLD", "00", collectionChecksumKey, null)))
                .andExpect(status().isOk());
        assertEquals("REVIEW_REQUIRED", paymentRepository.findByOrderCode(oldOrderCode).orElseThrow().getPaymentStatus());
        assertEquals("PAYMENT_REVIEW", bookingRepository.findById(bDoubleId).orElseThrow().getBookingStatus());
        assertEquals(0, ticketRepository.findByBooking_BookingId(bDoubleId).size());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payment_webhook_event WHERE order_code = ? AND processing_status = 'SUPERSEDED_PAYMENT_PAID'",
                Integer.class, oldOrderCode));

        // 3. Polling vs Webhook concurrent race -> single CONFIRMED, single Ticket, single BookingQr, single EmailOutbox
        long bRaceId = createBookingViaApi(user2, List.of(seatA2Id), List.of(), "M-PW-RACE");
        createdBookingIds.add(bRaceId);
        JsonNode pRace = createPaymentViaApi(user2, bRaceId);
        long racePayId = pRace.get("paymentId").asLong();
        long raceOc = pRace.get("orderCode").asLong();

        payOsService.setCustomTransportExecutorForTesting(new PayOsServiceImpl.CollectionTransportExecutor() {
            @Override
            public ResponseEntity<String> post(String url, HttpHeaders headers, Map<String, Object> body) {
                return ResponseEntity.ok("{}");
            }
            @Override
            public ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
                try {
                    return ResponseEntity.ok(objectMapper.readTree(
                            "{\"code\":\"00\",\"data\":{\"id\":\"plink_race\",\"status\":\"PAID\",\"amount\":100000,\"amountPaid\":100000,\"transactions\":[{\"reference\":\"RACE-POLL-REF\"}]}}"));
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        String raceWebhook = buildSignedWebhookJson(raceOc, 100000L, "BOX", "RACE-WH-REF", "00", collectionChecksumKey, null);
        ExecutorService pwPool = Executors.newFixedThreadPool(4);
        CountDownLatch pwReady = new CountDownLatch(4);
        CountDownLatch pwStart = new CountDownLatch(1);
        List<Future<?>> pwFutures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                pwFutures.add(pwPool.submit(() -> {
                    pwReady.countDown();
                    pwStart.await();
                    return payOsService.getPaymentStatus(racePayId, user2.getUserId());
                }));
                pwFutures.add(pwPool.submit(() -> {
                    pwReady.countDown();
                    pwStart.await();
                    return mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(raceWebhook))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(pwReady.await(5, TimeUnit.SECONDS));
            pwStart.countDown();
            for (Future<?> f : pwFutures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pwPool.shutdownNow();
        }
        assertEquals("CONFIRMED", bookingRepository.findById(bRaceId).orElseThrow().getBookingStatus());
        assertEquals(1, ticketRepository.findByBooking_BookingId(bRaceId).size());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM booking_qr WHERE booking_id = ?", Integer.class, bRaceId));
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE booking_id = ?", Integer.class, bRaceId));

        // 4. LIVE mode timeout -> GET recovery vs LIVE error -> 503 (no fake VietQR!)
        ReflectionTestUtils.setField(payOsService, "collectionMode", "LIVE");
        try {
            long bLiveRecId = createBookingViaApi(user1, List.of(seatA3Id), List.of(), "M-LIVEREC");
            createdBookingIds.add(bLiveRecId);
            payOsService.setCustomTransportExecutorForTesting(new PayOsServiceImpl.CollectionTransportExecutor() {
                @Override
                public ResponseEntity<String> post(String url, HttpHeaders headers, Map<String, Object> body) {
                    throw new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out"));
                }
                @Override
                public ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
                    try {
                        return ResponseEntity.ok(objectMapper.readTree(
                                "{\"code\":\"00\",\"data\":{\"id\":\"plink_rec\",\"qrCode\":\"LIVE_RECOVERED_QR\",\"checkoutUrl\":\"https://pay.payos.vn/web/rec\",\"bin\":\"970422\",\"accountNumber\":\"0000111222\",\"accountName\":\"BOX CINEMA\"}}"));
                    } catch (Exception e) { throw new RuntimeException(e); }
                }
            });
            PayOsCreatePaymentResponseDto recDto = payOsService.createPayment(bLiveRecId, user1.getUserId());
            assertEquals("LIVE_RECOVERED_QR", recDto.getQrCode());

            // LIVE timeout + GET fails -> FAILED + 503 PAYMENT_GATEWAY_ERROR (no fake VietQR)
            long bLiveFailId = createBookingViaApi(user2, List.of(seatA4Id), List.of(), "M-LIVE503");
            createdBookingIds.add(bLiveFailId);
            payOsService.setCustomTransportExecutorForTesting(new PayOsServiceImpl.CollectionTransportExecutor() {
                @Override
                public ResponseEntity<String> post(String url, HttpHeaders headers, Map<String, Object> body) {
                    throw new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out"));
                }
                @Override
                public ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
                    throw new ResourceAccessException("GET timed out");
                }
            });
            BusinessException be = assertThrows(BusinessException.class,
                    () -> payOsService.createPayment(bLiveFailId, user2.getUserId()));
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, be.getStatus());
            assertEquals("PAYMENT_GATEWAY_ERROR", be.getErrorCode());
            Payment failedP = paymentRepository.findTopByBooking_BookingIdOrderByPaymentIdDesc(bLiveFailId).orElseThrow();
            assertEquals("FAILED", failedP.getPaymentStatus());
            assertNull(failedP.getQrCodePayload());
        } finally {
            ReflectionTestUtils.setField(payOsService, "collectionMode", "MOCK");
            payOsService.setCustomTransportExecutorForTesting(null);
        }
    }

    @Test
    void collectionMatrix_SeatHoldCleanup_LateConflictSingleFoodRelease_And_EmailOutboxStaleLeaseRecovery() throws Exception {
        // 1. Single-use voucher + SeatHoldCleanupService expires DRAFT & PENDING_PAYMENT, releases food once, frees voucher
        Promotion singleVoucher = new Promotion();
        singleVoucher.setPromotionName("AudSingle " + TEST_TAG);
        String vCode = "AUD1-" + Math.abs(TEST_TAG.hashCode() % 100000);
        singleVoucher.setVoucherCode(vCode);
        singleVoucher.setDiscountType("FIXED");
        singleVoucher.setDiscountValue(BigDecimal.valueOf(20000));
        singleVoucher.setMaxDiscount(BigDecimal.valueOf(20000));
        singleVoucher.setMinOrderAmount(BigDecimal.ZERO);
        singleVoucher.setUsageLimit(1);
        singleVoucher.setPerUserLimit(1);
        singleVoucher.setStartAt(LocalDateTime.now().minusMinutes(5));
        singleVoucher.setEndAt(LocalDateTime.now().plusDays(10));
        singleVoucher.setStatus("ACTIVE");
        singleVoucher.setPublic(true);
        singleVoucher = promotionRepository.save(singleVoucher);
        createdPromoIds.add(singleVoucher.getPromotionId());

        BookingCreateRequestDto draftReq = new BookingCreateRequestDto();
        draftReq.setShowtimeId(testShowtime.getShowtimeId());
        draftReq.setSeatIds(List.of(seatA1Id));
        draftReq.setFoodItems(List.of(new BookingFoodItemRequestDto(foodItem1Id, null, 2)));
        draftReq.setVoucherCode(vCode);
        draftReq.setHoldSessionId("CLN-1-" + TEST_TAG);

        MvcResult rDraft = mockMvc.perform(post("/api/bookings").with(csrf())
                        .sessionAttr("currentUser", dtoOf(user1))
                        .with(user(user1.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(draftReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discountAmount").value(20000))
                .andReturn();
        long draftBookingId = objectMapper.readTree(rDraft.getResponse().getContentAsString()).get("bookingId").asLong();
        createdBookingIds.add(draftBookingId);

        long pendingBookingId = createBookingViaApi(user2, List.of(seatA2Id), List.of(Map.of("foodItemId", foodItem1Id, "quantity", 1)), "CLN-2");
        createdBookingIds.add(pendingBookingId);
        JsonNode pendingPay = createPaymentViaApi(user2, pendingBookingId);
        long pendingPayId = pendingPay.get("paymentId").asLong();

        assertEquals(3, jdbcTemplate.queryForObject("SELECT reserved_quantity FROM food_inventory WHERE food_item_id = ?", Integer.class, foodItem1Id));

        // Expire deadlines and run SeatHoldCleanupService TWICE (verifies idempotent single food stock release)
        jdbcTemplate.update("UPDATE bookings SET expires_at = DATE_SUB(NOW(), INTERVAL 10 MINUTE), payment_deadline = DATE_SUB(NOW(), INTERVAL 10 MINUTE) WHERE booking_id IN (?, ?)",
                draftBookingId, pendingBookingId);
        jdbcTemplate.update("UPDATE showtime_seats SET hold_expires_at = DATE_SUB(NOW(), INTERVAL 10 MINUTE) WHERE showtime_id = ?", testShowtime.getShowtimeId());

        seatHoldCleanupService.releaseExpiredHolds();
        seatHoldCleanupService.releaseExpiredHolds();

        assertEquals("EXPIRED", bookingRepository.findById(draftBookingId).orElseThrow().getBookingStatus());
        assertEquals("EXPIRED", bookingRepository.findById(pendingBookingId).orElseThrow().getBookingStatus());
        assertEquals("EXPIRED", paymentRepository.findById(pendingPayId).orElseThrow().getPaymentStatus());
        assertEquals(0, jdbcTemplate.queryForObject("SELECT reserved_quantity FROM food_inventory WHERE food_item_id = ?", Integer.class, foodItem1Id));
        assertEquals("AVAILABLE", jdbcTemplate.queryForObject("SELECT seat_status FROM showtime_seats WHERE showtime_id = ? AND seat_id = ?",
                String.class, testShowtime.getShowtimeId(), seatA1Id));

        // Single-use voucher is now free for user2!
        BookingCreateRequestDto reuseVoucherReq = new BookingCreateRequestDto();
        reuseVoucherReq.setShowtimeId(testShowtime.getShowtimeId());
        reuseVoucherReq.setSeatIds(List.of(seatA1Id));
        reuseVoucherReq.setVoucherCode(vCode);
        reuseVoucherReq.setHoldSessionId("CLN-REUSE-" + TEST_TAG);
        MvcResult reuseRes = mockMvc.perform(post("/api/bookings").with(csrf())
                        .sessionAttr("currentUser", dtoOf(user2))
                        .with(user(user2.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(reuseVoucherReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.discountAmount").value(20000))
                .andReturn();
        long winnerId = objectMapper.readTree(reuseRes.getResponse().getContentAsString()).get("bookingId").asLong();
        createdBookingIds.add(winnerId);
        JsonNode winnerPay = createPaymentViaApi(user2, winnerId);
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(buildSignedWebhookJson(winnerPay.get("orderCode").asLong(), 80000L, "BOX", "TXN-WIN1", "00", collectionChecksumKey, null)))
                .andExpect(status().isOk());

        // 2. Late webhook recovery when seatA2Id is still free (food was RELEASED -> re-reserved + committed!)
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(buildSignedWebhookJson(pendingPay.get("orderCode").asLong(), 150000L, "BOX", "TXN-REC2", "00", collectionChecksumKey, null)))
                .andExpect(status().isOk());
        assertEquals("CONFIRMED", bookingRepository.findById(pendingBookingId).orElseThrow().getBookingStatus());
        assertEquals(299, jdbcTemplate.queryForObject("SELECT on_hand_quantity FROM food_inventory WHERE food_item_id = ?", Integer.class, foodItem1Id));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT reserved_quantity FROM food_inventory WHERE food_item_id = ?", Integer.class, foodItem1Id));

        // 3. Late webhook conflict on already-booked seatA1Id -> PAID_SEAT_CONFLICT, winner preserved, CONFLICT_UNISSUED ticket, no QR, BOOKING_SEAT_CONFLICT email
        long lateConflictId = createBookingViaApi(user1, List.of(seatA3Id), List.of(Map.of("foodItemId", foodItem1Id, "quantity", 2)), "CONF-U1");
        createdBookingIds.add(lateConflictId);
        JsonNode pLateConflict = createPaymentViaApi(user1, lateConflictId);
        long lateOrderCode = pLateConflict.get("orderCode").asLong();
        jdbcTemplate.update("UPDATE bookings SET booking_status = 'EXPIRED', expires_at = DATE_SUB(NOW(), INTERVAL 15 MINUTE), payment_deadline = DATE_SUB(NOW(), INTERVAL 15 MINUTE) WHERE booking_id = ?", lateConflictId);
        jdbcTemplate.update("UPDATE booking_seats SET status = 'EXPIRED', active_seat_key = NULL WHERE booking_id = ?", lateConflictId);
        jdbcTemplate.update("UPDATE showtime_seats SET seat_status = 'AVAILABLE', held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL WHERE showtime_id = ? AND seat_id = ?",
                testShowtime.getShowtimeId(), seatA3Id);

        long winner3Id = createBookingViaApi(user2, List.of(seatA3Id), List.of(), "CONF-WIN3");
        createdBookingIds.add(winner3Id);
        JsonNode pWin3 = createPaymentViaApi(user2, winner3Id);
        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(buildSignedWebhookJson(pWin3.get("orderCode").asLong(), 100000L, "BOX", "TXN-WIN3", "00", collectionChecksumKey, null)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/payments/payos/webhook")
                        .contentType("application/json")
                        .content(buildSignedWebhookJson(lateOrderCode, 200000L, "BOX", "TXN-CONF3", "00", collectionChecksumKey, null)))
                .andExpect(status().isOk());
        assertEquals("PAID_SEAT_CONFLICT", bookingRepository.findById(lateConflictId).orElseThrow().getBookingStatus());
        assertEquals("BOOKED", jdbcTemplate.queryForObject("SELECT seat_status FROM showtime_seats WHERE showtime_id = ? AND seat_id = ?",
                String.class, testShowtime.getShowtimeId(), seatA3Id));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT reserved_quantity FROM food_inventory WHERE food_item_id = ?", Integer.class, foodItem1Id));
        assertTrue(bookingQrRepository.findByBooking_BookingId(lateConflictId).isEmpty());
        assertEquals("CONFLICT_UNISSUED", ticketRepository.findByBooking_BookingId(lateConflictId).get(0).getTicketStatus());
        assertTrue(emailOutboxRepository.existsByDedupKey("BOOKING_SEAT_CONFLICT:" + lateConflictId));

        // 4. EmailOutboxWorkerService stale PROCESSING lease recovery
        Booking conflictBooking = bookingRepository.findById(lateConflictId).orElseThrow();
        EmailOutbox staleRow = new EmailOutbox(conflictBooking, user1.getEmail(), "BOOKING_SEAT_CONFLICT");
        staleRow.setSubject("STALE-LEASE-" + TEST_TAG);
        staleRow.setBodyHtml("<p>Recovered stale lease email</p>");
        staleRow.setDedupKey("STALE_LEASE_" + TEST_TAG);
        staleRow.setStatus("PROCESSING");
        staleRow.setLastError("PROCESSING_LEASE:" + LocalDateTime.now().minusMinutes(10));
        staleRow = emailOutboxRepository.save(staleRow);

        emailOutboxWorkerService.setLeaseTimeoutSecondsForTesting(60L);
        emailOutboxWorkerService.processOutbox();

        EmailOutbox afterOutbox = emailOutboxRepository.findById(staleRow.getId()).orElseThrow();
        assertEquals("SENT", afterOutbox.getStatus());
        assertEquals(1, afterOutbox.getRetryCount());
        assertNotNull(afterOutbox.getSentAt());
        verify(mailSender, atLeastOnce()).send(any(MimeMessage.class));
    }

    @Test
    void collectionMatrix_20ThreadSeatRace_16ThreadFoodComboDeadlockFree_Idor_VariantSpoofing_CrossRoom_And_LiveSimulateBlock() throws Exception {
        // 1. 20-thread seat race on seatA1Id across 20 distinct users -> exactly 1 gets 201, 19 get 409
        int seatThreads = 20;
        List<User> raceUsers = new ArrayList<>(seatThreads);
        for (int i = 0; i < seatThreads; i++) {
            raceUsers.add(ensureUser("ca_race_" + i + "_" + TEST_TAG + "@box.test", "CUSTOMER", null));
        }
        ExecutorService seatPool = Executors.newFixedThreadPool(seatThreads);
        CountDownLatch seatReady = new CountDownLatch(seatThreads);
        CountDownLatch seatStart = new CountDownLatch(1);
        List<Future<Integer>> seatFutures = new ArrayList<>();
        try {
            for (int i = 0; i < seatThreads; i++) {
                final int idx = i;
                final User actor = raceUsers.get(i);
                seatFutures.add(seatPool.submit(() -> {
                    seatReady.countDown();
                    seatStart.await();
                    return mockMvc.perform(post("/api/bookings").with(csrf())
                                    .sessionAttr("currentUser", dtoOf(actor))
                                    .with(user(actor.getEmail()).roles("CUSTOMER"))
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(Map.of(
                                            "showtimeId", testShowtime.getShowtimeId(),
                                            "seatIds", List.of(seatA1Id),
                                            "holdSessionId", "SR20-" + idx + "-" + TEST_TAG))))
                            .andReturn().getResponse().getStatus();
                }));
            }
            assertTrue(seatReady.await(5, TimeUnit.SECONDS));
            seatStart.countDown();
            int createdCount = 0;
            int conflictCount = 0;
            for (Future<Integer> f : seatFutures) {
                int st = f.get(20, TimeUnit.SECONDS);
                if (st == 201) createdCount++;
                else if (st == 409) conflictCount++;
                else fail("Unexpected HTTP status in 20-thread seat race: " + st);
            }
            assertEquals(1, createdCount);
            assertEquals(19, conflictCount);
        } finally {
            seatPool.shutdownNow();
        }

        // 2. 16-thread overlapping food + combo reverse-order reservation (zero deadlocks!)
        int foodBefore1 = jdbcTemplate.queryForObject("SELECT reserved_quantity FROM food_inventory WHERE food_item_id = ?", Integer.class, foodItem1Id);
        int foodBefore2 = jdbcTemplate.queryForObject("SELECT reserved_quantity FROM food_inventory WHERE food_item_id = ?", Integer.class, foodItem2Id);

        int foodThreads = 16;
        ExecutorService foodPool = Executors.newFixedThreadPool(foodThreads);
        CountDownLatch foodReady = new CountDownLatch(foodThreads);
        CountDownLatch foodStart = new CountDownLatch(1);
        List<Future<Integer>> foodFutures = new ArrayList<>();
        try {
            for (int i = 0; i < foodThreads; i++) {
                final boolean reverse = (i % 2 == 1);
                final User actor = (i % 2 == 0) ? user1 : user2;
                foodFutures.add(foodPool.submit(() -> {
                    BookingCreateRequestDto req = new BookingCreateRequestDto();
                    req.setCinemaId(cinema.getCinemaId());
                    if (reverse) {
                        req.setFoodItems(List.of(
                                new BookingFoodItemRequestDto(foodItem2Id, null, 1),
                                new BookingFoodItemRequestDto(foodItem1Id, null, 1)
                        ));
                    } else {
                        req.setFoodItems(List.of(
                                new BookingFoodItemRequestDto(foodItem1Id, null, 1),
                                new BookingFoodItemRequestDto(foodItem2Id, null, 1)
                        ));
                    }
                    req.setCombos(List.of(new BookingComboRequestDto(comboId, 1)));
                    foodReady.countDown();
                    foodStart.await();
                    MvcResult res = mockMvc.perform(post("/api/bookings").with(csrf())
                                    .sessionAttr("currentUser", dtoOf(actor))
                                    .with(user(actor.getEmail()).roles("CUSTOMER"))
                                    .contentType("application/json")
                                    .content(objectMapper.writeValueAsString(req)))
                            .andReturn();
                    if (res.getResponse().getStatus() == 201) {
                        long bid = objectMapper.readTree(res.getResponse().getContentAsString()).get("bookingId").asLong();
                        synchronized (createdBookingIds) {
                            createdBookingIds.add(bid);
                        }
                    }
                    return res.getResponse().getStatus();
                }));
            }
            assertTrue(foodReady.await(5, TimeUnit.SECONDS));
            foodStart.countDown();
            for (Future<Integer> f : foodFutures) {
                assertEquals(201, f.get(25, TimeUnit.SECONDS));
            }
        } finally {
            foodPool.shutdownNow();
        }
        int foodAfter1 = jdbcTemplate.queryForObject("SELECT reserved_quantity FROM food_inventory WHERE food_item_id = ?", Integer.class, foodItem1Id);
        int foodAfter2 = jdbcTemplate.queryForObject("SELECT reserved_quantity FROM food_inventory WHERE food_item_id = ?", Integer.class, foodItem2Id);
        assertEquals(foodBefore1 + 32, foodAfter1);
        assertEquals(foodBefore2 + 32, foodAfter2);

        // 3. Variant price spoofing & inactive/mismatched variant rejection
        MvcResult varRes = mockMvc.perform(post("/api/bookings").with(csrf())
                        .sessionAttr("currentUser", dtoOf(user1))
                        .with(user(user1.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json")
                        .content("{\"cinemaId\":" + cinema.getCinemaId()
                                + ",\"foodItems\":[{\"foodItemId\":" + foodItem1Id + ",\"variantId\":" + variantActiveId
                                + ",\"quantity\":1,\"unitPrice\":1,\"subtotal\":1}]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(75000))
                .andReturn();
        createdBookingIds.add(objectMapper.readTree(varRes.getResponse().getContentAsString()).get("bookingId").asLong());

        // Inactive variant rejected (400) & variant belonging to different food item rejected (400)
        mockMvc.perform(post("/api/bookings").with(csrf())
                        .sessionAttr("currentUser", dtoOf(user1))
                        .with(user(user1.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json")
                        .content("{\"cinemaId\":" + cinema.getCinemaId()
                                + ",\"foodItems\":[{\"foodItemId\":" + foodItem2Id + ",\"variantId\":" + variantInactiveId + ",\"quantity\":1}]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/bookings").with(csrf())
                        .sessionAttr("currentUser", dtoOf(user1))
                        .with(user(user1.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json")
                        .content("{\"cinemaId\":" + cinema.getCinemaId()
                                + ",\"foodItems\":[{\"foodItemId\":" + foodItem2Id + ",\"variantId\":" + variantActiveId + ",\"quantity\":1}]}"))
                .andExpect(status().isBadRequest());

        // 4. Cross-room seat rejection -> 400
        mockMvc.perform(post("/api/bookings").with(csrf())
                        .sessionAttr("currentUser", dtoOf(user1))
                        .with(user(user1.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "showtimeId", testShowtime.getShowtimeId(),
                                "seatIds", List.of(seatOtherRoomId),
                                "holdSessionId", "XROOM-" + TEST_TAG))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST"));

        // 5. IDOR (401/403) across booking, payment, ticket, and QR
        long bIdorId = createBookingViaApi(user1, List.of(seatA2Id), List.of(), "IDOR-U1");
        createdBookingIds.add(bIdorId);
        JsonNode pIdor = createPaymentViaApi(user1, bIdorId);
        long pIdorId = pIdor.get("paymentId").asLong();
        long pIdorOc = pIdor.get("orderCode").asLong();

        // Unauthenticated -> 401 on booking & payment endpoints, 403 on ticket/QR endpoints
        mockMvc.perform(get("/api/bookings/" + bIdorId)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/bookings/" + bIdorId + "/food-items").with(csrf()).contentType("application/json").content("{\"foodItems\":[]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/bookings/" + bIdorId + "/confirm").with(csrf())).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/payments/payos/create").with(csrf()).contentType("application/json").content("{\"bookingId\":" + bIdorId + "}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/payments/" + pIdorId + "/status")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/bookings/" + bIdorId + "/ticket")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/bookings/" + bIdorId + "/ticket/qr")).andExpect(status().isForbidden());

        // user2 (non-owner) -> 403 on all user1 booking/payment/ticket/QR endpoints
        mockMvc.perform(get("/api/bookings/" + bIdorId).sessionAttr("currentUser", dtoOf(user2)).with(user(user2.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/bookings/" + bIdorId + "/food-items").with(csrf())
                        .sessionAttr("currentUser", dtoOf(user2)).with(user(user2.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json").content("{\"foodItems\":[]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/bookings/" + bIdorId + "/confirm").with(csrf())
                        .sessionAttr("currentUser", dtoOf(user2)).with(user(user2.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/payments/payos/create").with(csrf())
                        .sessionAttr("currentUser", dtoOf(user2)).with(user(user2.getEmail()).roles("CUSTOMER"))
                        .contentType("application/json").content("{\"bookingId\":" + bIdorId + "}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/payments/" + pIdorId + "/status")
                        .sessionAttr("currentUser", dtoOf(user2)).with(user(user2.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/bookings/" + bIdorId + "/ticket")
                        .sessionAttr("currentUser", dtoOf(user2)).with(user(user2.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/bookings/" + bIdorId + "/ticket/qr")
                        .sessionAttr("currentUser", dtoOf(user2)).with(user(user2.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isForbidden());

        // 6. LIVE mode blocks simulate-payment even if allowSimulate=true -> 403 SIMULATE_DISABLED
        ReflectionTestUtils.setField(paymentApiController, "allowSimulate", true);
        ReflectionTestUtils.setField(paymentApiController, "collectionMode", "LIVE");
        try {
            mockMvc.perform(post("/api/payments/payos/simulate-payment/" + pIdorOc).with(csrf()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("SIMULATE_DISABLED"));
        } finally {
            ReflectionTestUtils.setField(paymentApiController, "allowSimulate", false);
            ReflectionTestUtils.setField(paymentApiController, "collectionMode", "MOCK");
        }
    }

    @Test
    void collectionMatrix_GatewayCancelSync_ScheduledReconciler_SequentialWebhookReplay_MultiTabHttpCreateVsFood_Webhook500Masking_And_BookingHistorySsr() throws Exception {
        // 1. Gateway GET CANCELLED sync -> Payment CANCELLED, Booking CANCELLED, seats & food released
        long bCancelId = createBookingViaApi(user1, List.of(seatA1Id), List.of(Map.of("foodItemId", foodItem1Id, "quantity", 1)), "CXL-1");
        createdBookingIds.add(bCancelId);
        JsonNode pCancel = createPaymentViaApi(user1, bCancelId);
        long pCancelId = pCancel.get("paymentId").asLong();
        assertEquals(1, jdbcTemplate.queryForObject("SELECT reserved_quantity FROM food_inventory WHERE food_item_id = ?", Integer.class, foodItem1Id));

        payOsService.setCustomTransportExecutorForTesting(new PayOsServiceImpl.CollectionTransportExecutor() {
            @Override
            public ResponseEntity<String> post(String url, HttpHeaders headers, Map<String, Object> body) {
                return ResponseEntity.ok("{}");
            }
            @Override
            public ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
                try {
                    return ResponseEntity.ok(objectMapper.readTree("{\"code\":\"00\",\"data\":{\"status\":\"CANCELLED\"}}"));
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        mockMvc.perform(get("/api/payments/" + pCancelId + "/status")
                        .sessionAttr("currentUser", dtoOf(user1))
                        .with(user(user1.getEmail()).roles("CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paid").value(false))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.bookingStatus").value("CANCELLED"));
        assertEquals("CANCELLED", bookingRepository.findById(bCancelId).orElseThrow().getBookingStatus());
        assertEquals("AVAILABLE", jdbcTemplate.queryForObject(
                "SELECT seat_status FROM showtime_seats WHERE showtime_id = ? AND seat_id = ?",
                String.class, testShowtime.getShowtimeId(), seatA1Id));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT reserved_quantity FROM food_inventory WHERE food_item_id = ?", Integer.class, foodItem1Id));
        payOsService.setCustomTransportExecutorForTesting(null);

        // 2. SSR /booking/history/{id} while PENDING_PAYMENT NEVER shows fake PAID or Check-in QR; reload return URL never confirms
        long bPendId = createBookingViaApi(user1, List.of(seatA2Id), List.of(), "REC-PEND");
        createdBookingIds.add(bPendId);
        JsonNode pPend = createPaymentViaApi(user1, bPendId);
        long pPendOrderCode = pPend.get("orderCode").asLong();

        mockMvc.perform(get("/booking/buy-tickets")
                        .param("code", "00")
                        .param("status", "PAID")
                        .param("orderCode", String.valueOf(pPendOrderCode)))
                .andExpect(status().isOk());
        assertEquals("PENDING_PAYMENT", bookingRepository.findById(bPendId).orElseThrow().getBookingStatus());

        mockMvc.perform(get("/booking/history/" + bPendId)
                        .with(user(new BoxCinemaUserDetails(dtoOf(user1)))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("PENDING")))
                .andExpect(content().string(containsString("Payment Pending")))
                .andExpect(content().string(not(containsString(">Check-in QR Code<"))))
                .andExpect(content().string(not(containsString("View Full E-Ticket"))));

        // 3. Scheduled Collection reconciler recovers PENDING and recent EXPIRED (<24h) payments cleanly and is idempotent
        long bExpRecId = createBookingViaApi(user1, List.of(seatA3Id), List.of(), "REC-EXP");
        createdBookingIds.add(bExpRecId);
        JsonNode pExpRec = createPaymentViaApi(user1, bExpRecId);
        long pExpRecId = pExpRec.get("paymentId").asLong();
        long pExpRecOrderCode = pExpRec.get("orderCode").asLong();
        jdbcTemplate.update("UPDATE payments SET payment_status = 'EXPIRED', expired_at = DATE_SUB(NOW(), INTERVAL 5 MINUTE) WHERE payment_id = ?", pExpRecId);
        jdbcTemplate.update("UPDATE bookings SET booking_status = 'EXPIRED', expires_at = DATE_SUB(NOW(), INTERVAL 5 MINUTE), payment_deadline = DATE_SUB(NOW(), INTERVAL 5 MINUTE) WHERE booking_id = ?", bExpRecId);
        jdbcTemplate.update("UPDATE showtime_seats SET seat_status = 'AVAILABLE', held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL WHERE showtime_id = ? AND seat_id = ?",
                testShowtime.getShowtimeId(), seatA3Id);

        payOsService.setCustomTransportExecutorForTesting(new PayOsServiceImpl.CollectionTransportExecutor() {
            @Override
            public ResponseEntity<String> post(String url, HttpHeaders headers, Map<String, Object> body) {
                return ResponseEntity.ok("{}");
            }
            @Override
            public ResponseEntity<JsonNode> get(String url, HttpHeaders headers) {
                try {
                    long oc = Long.parseLong(url.substring(url.lastIndexOf('/') + 1));
                    if (oc == pPendOrderCode || oc == pExpRecOrderCode) {
                        return ResponseEntity.ok(objectMapper.readTree(
                                "{\"code\":\"00\",\"data\":{\"id\":\"plink_" + oc + "\",\"status\":\"PAID\",\"amount\":100000,\"amountPaid\":100000,\"transactions\":[{\"reference\":\"RECON-" + oc + "\"}]}}"));
                    }
                    return ResponseEntity.ok(objectMapper.readTree("{\"code\":\"00\",\"data\":{\"status\":\"PENDING\"}}"));
                } catch (Exception e) { throw new RuntimeException(e); }
            }
        });
        int updated = payOsService.reconcilePendingCollectionPayments();
        assertTrue(updated >= 2, "Reconciler must recover both PENDING and recent EXPIRED payments");
        assertEquals("CONFIRMED", bookingRepository.findById(bPendId).orElseThrow().getBookingStatus());
        assertEquals("PAID", paymentRepository.findByOrderCode(pPendOrderCode).orElseThrow().getPaymentStatus());
        assertEquals("CONFIRMED", bookingRepository.findById(bExpRecId).orElseThrow().getBookingStatus());
        assertEquals("PAID", paymentRepository.findByOrderCode(pExpRecOrderCode).orElseThrow().getPaymentStatus());
        assertEquals(1, ticketRepository.findByBooking_BookingId(bPendId).size());
        assertEquals(1, ticketRepository.findByBooking_BookingId(bExpRecId).size());
        assertEquals(0, payOsService.reconcilePendingCollectionPayments());
        payOsService.setCustomTransportExecutorForTesting(null);

        // 4. Sequential identical 00 webhook replay -> 200 idempotent (1 ticket, 1 webhook event, 1 email)
        String replayWebhook = buildSignedWebhookJson(pPendOrderCode, 100000L, "BOX", "TXN-SEQ-REPLAY", "00", collectionChecksumKey, null);
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(replayWebhook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"));
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(replayWebhook))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00"));
        assertEquals(1, ticketRepository.findByBooking_BookingId(bPendId).size());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE booking_id = ?", Integer.class, bPendId));

        // Confirmed detail page renders PAID + Check-in QR Code + View Full E-Ticket
        mockMvc.perform(get("/booking/history/" + bPendId)
                        .with(user(new BoxCinemaUserDetails(dtoOf(user1)))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("PAID")))
                .andExpect(content().string(containsString("Check-in QR Code")))
                .andExpect(content().string(containsString("View Full E-Ticket")));

        // 5. PAYMENT_REVIEW and PAID_SEAT_CONFLICT SSR detail & history semantic badges (no QR, /contact notice)
        long bRevId = createBookingViaApi(user1, List.of(seatA1Id), List.of(), "SSR-REV");
        createdBookingIds.add(bRevId);
        JsonNode pRev = createPaymentViaApi(user1, bRevId);
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json")
                        .content(buildSignedWebhookJson(pRev.get("orderCode").asLong(), 50000L, "BOX", "TXN-REVSSR", "00", collectionChecksumKey, null)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/booking/history/" + bRevId)
                        .with(user(new BoxCinemaUserDetails(dtoOf(user1)))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("REVIEW_REQUIRED")))
                .andExpect(content().string(containsString("Payment Under Verification")))
                .andExpect(content().string(not(containsString(">Check-in QR Code<"))));

        long bConfId = createBookingViaApi(user1, List.of(seatA4Id), List.of(), "SSR-CONF");
        createdBookingIds.add(bConfId);
        JsonNode pConf = createPaymentViaApi(user1, bConfId);
        long confOc = pConf.get("orderCode").asLong();
        jdbcTemplate.update("UPDATE bookings SET booking_status = 'EXPIRED', expires_at = DATE_SUB(NOW(), INTERVAL 15 MINUTE), payment_deadline = DATE_SUB(NOW(), INTERVAL 15 MINUTE) WHERE booking_id = ?", bConfId);
        jdbcTemplate.update("UPDATE booking_seats SET status = 'EXPIRED', active_seat_key = NULL WHERE booking_id = ?", bConfId);
        jdbcTemplate.update("UPDATE showtime_seats SET seat_status = 'AVAILABLE', held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL WHERE showtime_id = ? AND seat_id = ?",
                testShowtime.getShowtimeId(), seatA4Id);
        long bWin4Id = createBookingViaApi(user2, List.of(seatA4Id), List.of(), "SSR-WIN4");
        createdBookingIds.add(bWin4Id);
        JsonNode pWin4 = createPaymentViaApi(user2, bWin4Id);
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json")
                        .content(buildSignedWebhookJson(pWin4.get("orderCode").asLong(), 100000L, "BOX", "TXN-WIN4", "00", collectionChecksumKey, null)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json")
                        .content(buildSignedWebhookJson(confOc, 100000L, "BOX", "TXN-CONF4", "00", collectionChecksumKey, null)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/booking/history/" + bConfId)
                        .with(user(new BoxCinemaUserDetails(dtoOf(user1)))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Payment Received")))
                .andExpect(content().string(containsString("/contact")))
                .andExpect(content().string(not(containsString(">Check-in QR Code<"))));

        mockMvc.perform(get("/booking/history")
                        .with(user(new BoxCinemaUserDetails(dtoOf(user1)))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CONFIRMED")))
                .andExpect(content().string(containsString("PAID_SEAT_CONFLICT")))
                .andExpect(content().string(containsString("PAYMENT_REVIEW")))
                .andExpect(content().string(containsString("bg-success")))
                .andExpect(content().string(containsString("bg-warning")));

        // 6. Multi-tab concurrent HTTP POST /api/payments/payos/create racing with POST /api/bookings/{id}/food-items
        long bMultiId = createBookingViaApi(user2, List.of(), List.of(Map.of("foodItemId", foodItem1Id, "quantity", 1)), "MTAB-FOOD");
        createdBookingIds.add(bMultiId);
        ExecutorService tabPool = Executors.newFixedThreadPool(5);
        CountDownLatch tabReady = new CountDownLatch(5);
        CountDownLatch tabStart = new CountDownLatch(1);
        List<Future<Integer>> tabFutures = new ArrayList<>();
        try {
            for (int i = 0; i < 4; i++) {
                tabFutures.add(tabPool.submit(() -> {
                    tabReady.countDown();
                    tabStart.await();
                    return mockMvc.perform(post("/api/payments/payos/create").with(csrf())
                                    .sessionAttr("currentUser", dtoOf(user2))
                                    .with(user(user2.getEmail()).roles("CUSTOMER"))
                                    .contentType("application/json")
                                    .content("{\"bookingId\":" + bMultiId + "}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            tabFutures.add(tabPool.submit(() -> {
                tabReady.countDown();
                tabStart.await();
                return mockMvc.perform(post("/api/bookings/" + bMultiId + "/food-items").with(csrf())
                                .sessionAttr("currentUser", dtoOf(user2))
                                .with(user(user2.getEmail()).roles("CUSTOMER"))
                                .contentType("application/json")
                                .content(objectMapper.writeValueAsString(Map.of("foodItems", List.of(Map.of("foodItemId", foodItem1Id, "quantity", 2))))))
                        .andReturn().getResponse().getStatus();
            }));
            assertTrue(tabReady.await(5, TimeUnit.SECONDS));
            tabStart.countDown();
            for (Future<Integer> f : tabFutures) {
                int st = f.get(20, TimeUnit.SECONDS);
                assertTrue(st == 200 || st == 409, "Expected clean 200 or 409, got " + st);
            }
        } finally {
            tabPool.shutdownNow();
        }
        // After concurrent food update + create, calling create once more converges to single active PENDING payment matching final booking total
        JsonNode finalPay = createPaymentViaApi(user2, bMultiId);
        Booking finalMultiB = bookingRepository.findById(bMultiId).orElseThrow();
        assertEquals(0, finalMultiB.getTotalAmount().compareTo(new BigDecimal(finalPay.get("amount").asText())));
        assertEquals(1, paymentRepository.findByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(bMultiId, "PENDING").size());

        // 7. SEC-01: Attacker injects valid signed rawData JSON alongside forged unpaid data -> MUST fail with 400 (never bypass signature!)
        long bSec01Id = createBookingViaApi(user1, List.of(), List.of(Map.of("foodItemId", foodItem1Id, "quantity", 2)), "SEC01");
        createdBookingIds.add(bSec01Id);
        JsonNode pSec01 = createPaymentViaApi(user1, bSec01Id);
        long sec01Oc = pSec01.get("orderCode").asLong();
        // Use valid signature computed for a completely different payload (pPendOrderCode) injected as "rawData"
        JsonNode validSignedNode = objectMapper.readTree(replayWebhook);
        Map<String, Object> forgedData = new LinkedHashMap<>();
        forgedData.put("orderCode", sec01Oc);
        forgedData.put("amount", 100000L);
        forgedData.put("code", "00");
        forgedData.put("reference", "SEC01-FORGED");
        String sec01Payload = objectMapper.writeValueAsString(Map.of(
                "code", "00",
                "desc", "success",
                "data", forgedData,
                "rawData", validSignedNode.get("data"),
                "signature", validSignedNode.get("signature").asText()
        ));
        mockMvc.perform(post("/api/payments/payos/webhook").contentType("application/json").content(sec01Payload))
                .andExpect(status().isBadRequest());
        assertEquals("PENDING", paymentRepository.findByOrderCode(sec01Oc).orElseThrow().getPaymentStatus());
        assertEquals("PENDING_PAYMENT", bookingRepository.findById(bSec01Id).orElseThrow().getBookingStatus());

        // 8. Collection webhook 500 masks internal exception message
        PaymentApiController isolatedController = new PaymentApiController(new PayOsService() {
            @Override public PayOsCreatePaymentResponseDto createPayment(Long bookingId, Long currentUserId) { return null; }
            @Override public PaymentStatusDto getPaymentStatus(Long paymentId) { return null; }
            @Override public PaymentStatusDto getPaymentStatus(Long paymentId, Long currentUserId) { return null; }
            @Override public boolean verifyWebhookSignature(PayOsWebhookPayloadDto payload) { return true; }
            @Override public void processWebhook(PayOsWebhookPayloadDto payload) {
                throw new RuntimeException("SENSITIVE_DB_HOST_LEAK_192.168.1.50");
            }
            @Override public void simulatePayment(Long orderCode) {}
        });
        ResponseEntity<Map<String, Object>> errResp = isolatedController.handlePayOsWebhook(new PayOsWebhookPayloadDto());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, errResp.getStatusCode());
        assertEquals("Internal webhook error", errResp.getBody().get("desc"));
        assertFalse(String.valueOf(errResp.getBody()).contains("SENSITIVE_DB_HOST_LEAK"));
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

    private String buildSignedWebhookJson(long orderCode, Long amount, String desc, String ref, String code,
                                          String signingKey, Map<String, Object> extraFields) throws Exception {
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
        data.put("transactionDateTime", "2026-11-22 10:00:00");
        if (extraFields != null) {
            data.putAll(extraFields);
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            Object val = e.getValue();
            String strVal;
            if (val == null) strVal = "";
            else if (val instanceof Map || val instanceof Collection) strVal = objectMapper.writeValueAsString(val);
            else strVal = String.valueOf(val);
            sb.append(e.getKey()).append("=").append(strVal);
        }
        String sig = CryptoUtils.hmacSha256(sb.toString(), signingKey);
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
        u.setFullName("ColAud " + role);
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
