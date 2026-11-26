package com.boxcinema.refund;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BookingService;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.customer.service.PromotionService;
import com.boxcinema.refund.dto.IncidentCreateRequestDto;
import com.boxcinema.refund.dto.OrderLookupResponseDto;
import com.boxcinema.refund.dto.PublicRefundSubmitRequestDto;
import com.boxcinema.refund.dto.RefundRequestDetailDto;
import com.boxcinema.refund.dto.RefundReviewRequestDto;
import com.boxcinema.refund.service.*;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprehensive Stage 1 Verification Suite covering:
 * 1. One-voucher-per-order backend rejection, DB UNIQUE(booking_id) (uk_ovr_booking_id), and concurrent redemption race.
 * 2. Fixed voucher min(value, eligible), percentage voucher with cap & HALF_UP rounding, applicableScope filtering,
 *    Largest Remainder exact sum invariant, and deterministic ascending ticket_id tie-breaker.
 * 3. Single-ticket, multi-ticket, partial refund, previous refunded deduction, settled vs unsettled payment guard,
 *    and separate non-duplicated voucher restoration.
 * 4. RefundDeadlineService in Asia/Ho_Chi_Minh (72h exact boundary, late-incident formula, 30-day cap clamping,
 *    null/unverified fallback, Admin deadline exception with mandatory reason & audit, on-time submission delayed review,
 *    and USED / CHECKED_IN ticket reviewability).
 */
@SpringBootTest
@AutoConfigureMockMvc
class Stage1VoucherCalculationAndDeadlineTest {

    private static final String TEST_TAG = "STG1_" + System.currentTimeMillis();
    private static final ZoneId HCM_ZONE = RefundDeadlineService.HO_CHI_MINH_ZONE;

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
    @Autowired private BookingFoodItemRepository bookingFoodItemRepository;
    @Autowired private BookingFoodItemComponentRepository componentRepository;
    @Autowired private FoodComboRepository foodComboRepository;
    @Autowired private FoodComboItemRepository foodComboItemRepository;
    @Autowired private FoodItemRepository foodItemRepository;
    @Autowired private PromotionRepository promotionRepository;
    @Autowired private OrderVoucherRedemptionRepository redemptionRepository;
    @Autowired private OrderVoucherAllocationRepository allocationRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRequestRepository refundRequestRepository;
    @Autowired private RefundRequestItemRepository refundRequestItemRepository;
    @Autowired private RefundAuditLogRepository auditLogRepository;
    @Autowired private VoucherRecoveryRepository voucherRecoveryRepository;
    @Autowired private IncidentReportRepository incidentRepository;

    @Autowired private BookingService bookingService;
    @Autowired private PromotionService promotionService;
    @Autowired private PurchaseSnapshotService snapshotService;
    @Autowired private RefundDeadlineService refundDeadlineService;
    @Autowired private RefundService refundService;
    @Autowired private VoucherRecoveryService voucherRecoveryService;
    @Autowired private VoucherHistoricalScanService historicalScanService;
    @Autowired private IncidentService incidentService;

    @MockBean private JavaMailSender mailSender;

    private Cinema cinema;
    private Room room;
    private Movie movie;
    private User customer;
    private User staff;
    private User manager;
    private User admin;

    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdShowtimeIds = new ArrayList<>();
    private final List<Long> createdPromoIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdComboIds = new ArrayList<>();
    private final List<Long> createdFoodIds = new ArrayList<>();
    private final List<Long> createdCategoryIds = new ArrayList<>();
    private final List<Long> createdIncidentIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        refundDeadlineService.setClock(Clock.system(HCM_ZONE));

        cinema = cinemaRepository.findAll().get(0);
        room = roomRepository.findAll().stream()
                .filter(r -> r.getCinema() != null && r.getCinema().getCinemaId().equals(cinema.getCinemaId()))
                .findFirst()
                .orElseGet(() -> roomRepository.findAll().get(0));
        cinema = room.getCinema();
        movie = movieRepository.findAll().get(0);

        String uniq = UUID.randomUUID().toString().substring(0, 8);
        customer = createTestUser("cust_" + uniq + "@box.test", "CUSTOMER", null);
        staff = createTestUser("staff_" + uniq + "@box.test", "STAFF", cinema.getCinemaId());
        manager = createTestUser("mgr_" + uniq + "@box.test", "MANAGER", cinema.getCinemaId());
        admin = createTestUser("adm_" + uniq + "@box.test", "ADMIN", null);

        var principal = new com.boxcinema.common.security.BoxCinemaUserDetails(manager);
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal, "", principal.getAuthorities());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        refundDeadlineService.setClock(Clock.system(HCM_ZONE));
        voucherRecoveryService.setCustomFailureInjectorForTesting(null);

        for (Long bid : createdBookingIds) {
            List<Long> rrIds = jdbcTemplate.queryForList("SELECT id FROM refund_requests WHERE booking_id = ?", Long.class, bid);
            List<Long> vrPromoIds = jdbcTemplate.queryForList(
                    "SELECT new_promotion_id FROM voucher_recoveries WHERE booking_id = ? AND new_promotion_id IS NOT NULL",
                    Long.class, bid);
            for (Long pid : vrPromoIds) {
                if (!createdPromoIds.contains(pid)) {
                    createdPromoIds.add(pid);
                }
            }
            jdbcTemplate.update("DELETE FROM email_outbox WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM voucher_recoveries WHERE booking_id = ?", bid);
            for (Long rrId : rrIds) {
                jdbcTemplate.update("DELETE FROM payos_payout_transactions WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE refund_request_id = ? OR (entity_type = 'REFUND_REQUEST' AND entity_id = ?)", rrId, rrId);
                jdbcTemplate.update("DELETE FROM refund_approval_history WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_communications WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_request_items WHERE refund_request_id = ?", rrId);
            }
            jdbcTemplate.update("DELETE FROM refund_requests WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM order_voucher_allocations WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM order_voucher_redemptions WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM staff_error_logs WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM incident_verifications WHERE incident_report_id IN (SELECT id FROM incident_reports WHERE booking_id = ?)", bid);
            jdbcTemplate.update("DELETE FROM incident_reports WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_food_item_component WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_food_item WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM payments WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM tickets WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_seats WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM bookings WHERE booking_id = ?", bid);
        }
        createdBookingIds.clear();

        for (Long incId : createdIncidentIds) {
            jdbcTemplate.update("DELETE FROM incident_verifications WHERE incident_report_id = ?", incId);
            jdbcTemplate.update("DELETE FROM incident_reports WHERE id = ?", incId);
        }
        createdIncidentIds.clear();

        for (Long stId : createdShowtimeIds) {
            jdbcTemplate.update("DELETE FROM incident_verifications WHERE incident_report_id IN (SELECT id FROM incident_reports WHERE showtime_id = ?)", stId);
            jdbcTemplate.update("DELETE FROM incident_reports WHERE showtime_id = ?", stId);
            jdbcTemplate.update("DELETE FROM showtime_seats WHERE showtime_id = ?", stId);
            jdbcTemplate.update("DELETE FROM showtimes WHERE showtime_id = ?", stId);
        }
        createdShowtimeIds.clear();

        for (Long cid : createdComboIds) {
            jdbcTemplate.update("DELETE FROM food_combo_item WHERE combo_id = ?", cid);
            jdbcTemplate.update("DELETE FROM food_combo_inventory WHERE combo_id = ?", cid);
            jdbcTemplate.update("DELETE FROM food_combo WHERE id = ?", cid);
        }
        createdComboIds.clear();

        for (Long fid : createdFoodIds) {
            jdbcTemplate.update("DELETE FROM food_inventory WHERE food_item_id = ?", fid);
            jdbcTemplate.update("DELETE FROM food_item WHERE id = ?", fid);
        }
        createdFoodIds.clear();

        for (Long catId : createdCategoryIds) {
            jdbcTemplate.update("DELETE FROM food_category WHERE id = ?", catId);
        }
        createdCategoryIds.clear();

        for (Long pid : createdPromoIds) {
            jdbcTemplate.update("DELETE FROM order_voucher_redemptions WHERE promotion_id = ?", pid);
            jdbcTemplate.update("DELETE FROM promotions WHERE promotion_id = ?", pid);
        }
        createdPromoIds.clear();

        for (Long uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE actor_user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", uid);
        }
        createdUserIds.clear();
    }

    // =========================================================================
    // 1. ONE-VOUCHER-PER-ORDER: Backend Guard, DB Unique Constraint & Concurrency
    // =========================================================================

    @Test
    void oneVoucherPerOrder_BackendAndHttpRejectSecondVoucherAndMultiCodeStrings() throws Exception {
        Promotion promo1 = createPromotion("V1A-" + randomCode(), "FIXED", 20_000, null, "ALL");
        Promotion promo2 = createPromotion("V1B-" + randomCode(), "FIXED", 30_000, null, "ALL");

        Showtime st = createShowtime(LocalDateTime.now(HCM_ZONE).plusDays(2), LocalDateTime.now(HCM_ZONE).plusDays(2).plusHours(2));
        Booking booking = createDraftBookingWithoutVoucher(st, customer, List.of(100_000L, 100_000L));

        // 1. Multi-code string (comma / semicolon) rejected immediately with 409 ORDER_ALREADY_HAS_VOUCHER
        BusinessException commaEx = assertThrows(BusinessException.class, () ->
                bookingService.applyVoucherToBooking(booking.getBookingId(),
                        promo1.getVoucherCode() + "," + promo2.getVoucherCode(), customer.getUserId()));
        assertEquals(HttpStatus.CONFLICT, commaEx.getStatus());
        assertEquals("ORDER_ALREADY_HAS_VOUCHER", commaEx.getErrorCode());

        // 2. First single voucher succeeds and persists OrderVoucherRedemption
        var appliedDto = bookingService.applyVoucherToBooking(booking.getBookingId(), promo1.getVoucherCode(), customer.getUserId());
        assertEquals(promo1.getVoucherCode(), appliedDto.getVoucherCode());
        assertEquals(0, BigDecimal.valueOf(20_000).compareTo(appliedDto.getDiscountAmount()));
        assertEquals(0, BigDecimal.valueOf(180_000).compareTo(appliedDto.getTotalAmount()));

        Optional<OrderVoucherRedemption> ovrOpt = redemptionRepository.findByBooking_BookingId(booking.getBookingId());
        assertTrue(ovrOpt.isPresent());
        assertEquals("APPLIED", ovrOpt.get().getStatus());
        assertEquals(20_000L, ovrOpt.get().getActualDiscount());

        // 3. Second voucher via BookingService is rejected with 409 ORDER_ALREADY_HAS_VOUCHER
        BusinessException secondEx = assertThrows(BusinessException.class, () ->
                bookingService.applyVoucherToBooking(booking.getBookingId(), promo2.getVoucherCode(), customer.getUserId()));
        assertEquals(HttpStatus.CONFLICT, secondEx.getStatus());
        assertEquals("ORDER_ALREADY_HAS_VOUCHER", secondEx.getErrorCode());

        // 4. Second voucher via PromotionService.redeemVoucherForOrder is also rejected with 409
        Booking reloaded = bookingRepository.findById(booking.getBookingId()).orElseThrow();
        BusinessException promoSvcEx = assertThrows(BusinessException.class, () ->
                promotionService.redeemVoucherForOrder(reloaded, promo2.getVoucherCode(),
                        BigDecimal.valueOf(200_000), customer.getUserId(), customer.getEmail()));
        assertEquals(HttpStatus.CONFLICT, promoSvcEx.getStatus());
        assertEquals("ORDER_ALREADY_HAS_VOUCHER", promoSvcEx.getErrorCode());

        // 5. HTTP POST /api/bookings/{id}/voucher returns 409 CONFLICT
        mockMvc.perform(post("/api/bookings/" + booking.getBookingId() + "/voucher")
                        .with(csrf())
                        .with(user(new com.boxcinema.common.security.BoxCinemaUserDetails(customer)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("voucherCode", promo2.getVoucherCode()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORDER_ALREADY_HAS_VOUCHER"));
    }

    @Test
    void oneVoucherPerOrder_DatabaseUniqueConstraint_UkOvrBookingId_RejectsDuplicateInsert() {
        // Verify uk_ovr_booking_id metadata in MySQL information_schema
        Integer uniqueIndexCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'order_voucher_redemptions' " +
                "AND INDEX_NAME = 'uk_ovr_booking_id' AND COLUMN_NAME = 'booking_id' AND NON_UNIQUE = 0",
                Integer.class);
        assertNotNull(uniqueIndexCount);
        assertEquals(1, uniqueIndexCount, "uk_ovr_booking_id UNIQUE(booking_id) must exist on order_voucher_redemptions");

        Promotion promo1 = createPromotion("DBU1-" + randomCode(), "FIXED", 15_000, null, "ALL");
        Promotion promo2 = createPromotion("DBU2-" + randomCode(), "FIXED", 25_000, null, "ALL");
        Showtime st = createShowtime(LocalDateTime.now(HCM_ZONE).plusDays(2), LocalDateTime.now(HCM_ZONE).plusDays(2).plusHours(2));
        Booking booking = createDraftBookingWithoutVoucher(st, customer, List.of(100_000L));

        jdbcTemplate.update(
                "INSERT INTO order_voucher_redemptions (booking_id, promotion_id, voucher_code, discount_type, discount_value, eligible_amount, actual_discount, status) " +
                "VALUES (?, ?, ?, 'FIXED', 15000, 100000, 15000, 'APPLIED')",
                booking.getBookingId(), promo1.getPromotionId(), promo1.getVoucherCode());

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update(
                        "INSERT INTO order_voucher_redemptions (booking_id, promotion_id, voucher_code, discount_type, discount_value, eligible_amount, actual_discount, status) " +
                        "VALUES (?, ?, ?, 'FIXED', 25000, 100000, 25000, 'APPLIED')",
                        booking.getBookingId(), promo2.getPromotionId(), promo2.getVoucherCode()),
                "MySQL uk_ovr_booking_id must reject a second voucher row for the same booking_id");
    }

    @Test
    void oneVoucherPerOrder_ConcurrentRedemptionRace_ExactlyOneSucceedsAndRestFailWithConflict() throws Exception {
        int threads = 8;
        List<Promotion> promos = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            promos.add(createPromotion("RACE" + i + "-" + randomCode(), "FIXED", 10_000 + (i * 1_000L), null, "ALL"));
        }

        Showtime st = createShowtime(LocalDateTime.now(HCM_ZONE).plusDays(3), LocalDateTime.now(HCM_ZONE).plusDays(3).plusHours(2));
        Booking bookingForBookingSvc = createDraftBookingWithoutVoucher(st, customer, List.of(100_000L, 100_000L));
        Booking bookingForPromoSvc = createDraftBookingWithoutVoucher(st, customer, List.of(100_000L, 100_000L));

        // Race A: 8 threads via BookingService.applyVoucherToBooking (pessimistic booking lock + uk_ovr_booking_id)
        runConcurrentVoucherRace(threads, idx -> {
            bookingService.applyVoucherToBooking(
                    bookingForBookingSvc.getBookingId(),
                    promos.get(idx).getVoucherCode(),
                    customer.getUserId());
        }, bookingForBookingSvc.getBookingId());

        // Race B: 8 threads via PromotionService.redeemVoucherForOrder (distinct promotions racing directly into uk_ovr_booking_id)
        runConcurrentVoucherRace(threads, idx -> {
            Booking detachedRef = new Booking();
            detachedRef.setBookingId(bookingForPromoSvc.getBookingId());
            detachedRef.setTicketSubtotal(BigDecimal.valueOf(200_000));
            detachedRef.setSubtotal(BigDecimal.valueOf(200_000));
            detachedRef.setTotalAmount(BigDecimal.valueOf(200_000));
            promotionService.redeemVoucherForOrder(
                    detachedRef,
                    promos.get(idx).getVoucherCode(),
                    BigDecimal.valueOf(200_000),
                    customer.getUserId(),
                    customer.getEmail());
        }, bookingForPromoSvc.getBookingId());
    }

    private interface IntTask {
        void run(int index);
    }

    private void runConcurrentVoucherRace(int threads, IntTask action, Long bookingId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger conflicts = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        action.run(idx);
                        successes.incrementAndGet();
                    } catch (BusinessException bex) {
                        if (bex.getStatus() == HttpStatus.CONFLICT && "ORDER_ALREADY_HAS_VOUCHER".equals(bex.getErrorCode())) {
                            conflicts.incrementAndGet();
                        } else {
                            unexpectedErrors.add(bex);
                        }
                    } catch (Throwable t) {
                        unexpectedErrors.add(t);
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertTrue(unexpectedErrors.isEmpty(), "Unexpected errors in concurrent voucher race: " + unexpectedErrors);
        assertEquals(1, successes.get(), "Exactly 1 concurrent voucher redemption must succeed");
        assertEquals(threads - 1, conflicts.get(), "All other concurrent attempts must fail with 409 ORDER_ALREADY_HAS_VOUCHER");

        Integer dbCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM order_voucher_redemptions WHERE booking_id = ?",
                Integer.class, bookingId);
        assertEquals(1, dbCount, "DB must contain strictly 1 order_voucher_redemptions row for the booking");
    }

    // =========================================================================
    // 2. VOUCHER MATH, SCOPE RESTRICTIONS, LARGEST REMAINDER & TICKET_ID TIE-BREAKER
    // =========================================================================

    @Test
    void fixedAndPercentageVoucherMath_RespectsCapsRoundingAndEligibilityScope() {
        // 1. Fixed voucher: min(voucher_value, total_eligible_amount)
        assertEquals(50_000L, PurchaseSnapshotService.computeConfiguredVoucherDiscountVnd(
                "FIXED", BigDecimal.valueOf(50_000), null, 120_000L));
        assertEquals(120_000L, PurchaseSnapshotService.computeConfiguredVoucherDiscountVnd(
                "FIXED", BigDecimal.valueOf(250_000), null, 120_000L),
                "Fixed voucher exceeding eligible amount must clamp to eligible amount (never negative net)");
        assertEquals(0L, PurchaseSnapshotService.computeConfiguredVoucherDiscountVnd(
                "FIXED", BigDecimal.valueOf(50_000), null, 0L));

        // 2. Percentage voucher: HALF_UP rounding + max_discount cap
        assertEquals(30_000L, PurchaseSnapshotService.computeConfiguredVoucherDiscountVnd(
                "PERCENT", BigDecimal.valueOf(15), BigDecimal.valueOf(40_000), 200_000L));
        assertEquals(40_000L, PurchaseSnapshotService.computeConfiguredVoucherDiscountVnd(
                "PERCENT", BigDecimal.valueOf(15), BigDecimal.valueOf(40_000), 400_000L),
                "15% of 400k = 60k -> must cap at max_discount = 40k");
        assertEquals(14_999L, PurchaseSnapshotService.computeConfiguredVoucherDiscountVnd(
                "PERCENT", BigDecimal.valueOf(15), null, 99_990L),
                "15% of 99,990 = 14,998.5 -> HALF_UP rounds to 14,999 VND");

        // 3. Eligibility scope restrictions: TICKET_ONLY vs FOOD_ONLY on mixed order
        Promotion ticketOnlyPromo = createPromotion("TCKONLY-" + randomCode(), "PERCENT", 20, 50_000L, "TICKET_ONLY");
        Showtime st = createShowtime(LocalDateTime.now(HCM_ZONE).plusDays(1), LocalDateTime.now(HCM_ZONE).plusDays(1).plusHours(2));
        Booking mixedOrder = createPaidMixedOrderWithScope(st, ticketOnlyPromo, List.of(100_000L, 100_000L), 60_000L);

        List<Ticket> tickets = ticketRepository.findByBooking_BookingId(mixedOrder.getBookingId());
        tickets.sort(Comparator.comparing(Ticket::getTicketId));
        BookingFoodItem food = bookingFoodItemRepository.findByBooking_BookingId(mixedOrder.getBookingId()).get(0);

        // Eligible = 200k tickets only -> 20% = 40k (100k-20k=80k each); Food 60k gets 0 discount (net 60k)
        assertEquals(2, tickets.size());
        assertEquals(20_000L, PurchaseSnapshotService.toVndLong(tickets.get(0).getDiscountAllocated()));
        assertEquals(80_000L, PurchaseSnapshotService.toVndLong(tickets.get(0).getNetPaidAmount()));
        assertEquals(20_000L, PurchaseSnapshotService.toVndLong(tickets.get(1).getDiscountAllocated()));
        assertEquals(80_000L, PurchaseSnapshotService.toVndLong(tickets.get(1).getNetPaidAmount()));
        assertEquals(0L, PurchaseSnapshotService.toVndLong(food.getDiscountAllocated()), "Ineligible food item must get 0 discount");
        assertEquals(60_000L, PurchaseSnapshotService.toVndLong(food.getNetPaidAmount()));

        // Verify persisted OrderVoucherRedemption & OrderVoucherAllocation rows
        OrderVoucherRedemption ovr = redemptionRepository.findByBooking_BookingId(mixedOrder.getBookingId()).orElseThrow();
        assertEquals(200_000L, ovr.getEligibleAmount());
        assertEquals(40_000L, ovr.getActualDiscount());
        List<OrderVoucherAllocation> allocs = allocationRepository.findByRedemption_IdOrderByIdAsc(ovr.getId());
        assertEquals(3, allocs.size());
        assertEquals(40_000L, allocs.stream().mapToLong(OrderVoucherAllocation::getAllocatedDiscount).sum());

        // 4. FOOD_ONLY scope: tickets get 0 discount, food gets full min(voucher, food_eligible)
        Promotion foodOnlyPromo = createPromotion("FDONLY-" + randomCode(), "FIXED", 25_000, null, "FOOD_ONLY");
        Booking foodScopedOrder = createPaidMixedOrderWithScope(st, foodOnlyPromo, List.of(90_000L, 90_000L), 60_000L);
        for (Ticket t : ticketRepository.findByBooking_BookingId(foodScopedOrder.getBookingId())) {
            assertEquals(0L, PurchaseSnapshotService.toVndLong(t.getDiscountAllocated()), "Ineligible ticket must get 0 discount under FOOD_ONLY");
            assertEquals(90_000L, PurchaseSnapshotService.toVndLong(t.getNetPaidAmount()));
        }
        BookingFoodItem scopedFood = bookingFoodItemRepository.findByBooking_BookingId(foodScopedOrder.getBookingId()).get(0);
        assertEquals(25_000L, PurchaseSnapshotService.toVndLong(scopedFood.getDiscountAllocated()));
        assertEquals(35_000L, PurchaseSnapshotService.toVndLong(scopedFood.getNetPaidAmount()));
    }

    @Test
    void largestRemainder_ExactSumInvariant_And_DeterministicAscendingTicketIdTieBreaker() {
        // Direct unit check with scrambled ticket_ids on equal remainders:
        // 3 equal tickets of 100,000 VND sharing 10,000 VND -> 3333 rem 100,000 each -> 1 VND leftover
        // Scrambled input order: ticket_id [9003, 9001, 9002] -> ticket_id 9001 (index 1) MUST win the +1 VND!
        long[] weights = new long[]{100_000L, 100_000L, 100_000L};
        long[] scrambledTicketIds = new long[]{9003L, 9001L, 9002L};
        long[] directAlloc = snapshotService.allocateProportional(10_000L, weights, scrambledTicketIds);
        assertEquals(10_000L, directAlloc[0] + directAlloc[1] + directAlloc[2]);
        assertEquals(3_333L, directAlloc[0], "ticket_id 9003 must NOT get tie-breaker VND");
        assertEquals(3_334L, directAlloc[1], "Lowest ticket_id 9001 MUST deterministically receive the +1 VND remainder");
        assertEquals(3_333L, directAlloc[2], "ticket_id 9002 must NOT get tie-breaker VND");

        // Full DB persistence check where seat creation order != ascending ticket_id order:
        // Seat 1 inserted first, Seat 2 second, Seat 3 third; Tickets inserted in reverse seat order so
        // Seat 3 gets the LOWEST ticket_id, Seat 2 gets middle ticket_id, Seat 1 gets HIGHEST ticket_id!
        Promotion promo = createPromotion("TIE10K-" + randomCode(), "FIXED", 10_000, null, "ALL");
        Showtime st = createShowtime(LocalDateTime.now(HCM_ZONE).plusDays(1), LocalDateTime.now(HCM_ZONE).plusDays(1).plusHours(2));

        Booking b = new Booking();
        b.setBookingCode("BOX-TIE-" + randomDigits());
        b.setShowtime(st);
        b.setCinema(cinema);
        b.setUser(customer);
        b.setOrderType("TICKET_ONLY");
        b.setGuestName(customer.getFullName());
        b.setGuestEmail(customer.getEmail());
        b.setGuestPhone("0901112222");
        b.setTicketSubtotal(BigDecimal.valueOf(300_000));
        b.setSubtotal(BigDecimal.valueOf(300_000));
        b.setDiscountAmount(BigDecimal.valueOf(10_000));
        b.setTotalAmount(BigDecimal.valueOf(290_000));
        b.setBookingStatus("CONFIRMED");
        b.setPromotion(promo);
        b.setVoucherCodeSnapshot(promo.getVoucherCode());
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());
        recordPaidPayment(b, BigDecimal.valueOf(290_000));

        BookingSeat bs1 = bookingSeatRepository.save(new BookingSeat(b, 1L, BigDecimal.valueOf(100_000), "A1", "BOOKED"));
        BookingSeat bs2 = bookingSeatRepository.save(new BookingSeat(b, 2L, BigDecimal.valueOf(100_000), "A2", "BOOKED"));
        BookingSeat bs3 = bookingSeatRepository.save(new BookingSeat(b, 3L, BigDecimal.valueOf(100_000), "A3", "BOOKED"));
        b.getBookingSeats().addAll(List.of(bs1, bs2, bs3));

        // Insert ticket for bs3 FIRST so bs3 has the smallest ticket_id!
        Ticket tLowestId = saveTicket(b, bs3.getBookingSeatId(), "A3", 100_000L, "VALID");
        Ticket tMiddleId = saveTicket(b, bs2.getBookingSeatId(), "A2", 100_000L, "VALID");
        Ticket tHighestId = saveTicket(b, bs1.getBookingSeatId(), "A1", 100_000L, "VALID");
        assertTrue(tLowestId.getTicketId() < tMiddleId.getTicketId() && tMiddleId.getTicketId() < tHighestId.getTicketId());

        // Pass tickets in scrambled order [highest, lowest, middle]
        snapshotService.allocateOrderSnapshot(b, BigDecimal.valueOf(290_000), List.of(tHighestId, tLowestId, tMiddleId));
        ticketRepository.saveAll(List.of(tLowestId, tMiddleId, tHighestId));
        bookingSeatRepository.saveAll(List.of(bs1, bs2, bs3));
        bookingRepository.save(b);

        // Lowest ticket_id (on bs3) MUST get 3,334 discount / 96,666 net; other two get 3,333 / 96,667
        assertEquals(3_334L, PurchaseSnapshotService.toVndLong(tLowestId.getDiscountAllocated()));
        assertEquals(96_666L, PurchaseSnapshotService.toVndLong(tLowestId.getNetPaidAmount()));
        assertEquals(3_333L, PurchaseSnapshotService.toVndLong(tMiddleId.getDiscountAllocated()));
        assertEquals(96_667L, PurchaseSnapshotService.toVndLong(tMiddleId.getNetPaidAmount()));
        assertEquals(3_333L, PurchaseSnapshotService.toVndLong(tHighestId.getDiscountAllocated()));
        assertEquals(96_667L, PurchaseSnapshotService.toVndLong(tHighestId.getNetPaidAmount()));

        // Property invariant check across 25 diverse weight/discount distributions (including >32-bit products)
        Random deterministicRng = new Random(20261126L);
        for (int trial = 0; trial < 25; trial++) {
            int count = 2 + deterministicRng.nextInt(7);
            long[] w = new long[count];
            long[] ids = new long[count];
            long wSum = 0;
            for (int i = 0; i < count; i++) {
                w[i] = 45_000L + deterministicRng.nextInt(500_000);
                ids[i] = 10_000L - i; // descending IDs to exercise tie-breaker
                wSum += w[i];
            }
            long disc = 1L + (Math.abs(deterministicRng.nextLong()) % wSum);
            long[] res = snapshotService.allocateProportional(disc, w, ids);
            long actualSum = 0;
            for (int i = 0; i < count; i++) {
                assertTrue(res[i] >= 0 && res[i] <= w[i], "Item discount must stay within [0, weight]");
                actualSum += res[i];
            }
            assertEquals(disc, actualSum, "Largest Remainder exact sum invariant must hold on trial " + trial);
        }
    }

    // =========================================================================
    // 3. PARTIAL REFUNDS, PREVIOUS REFUNDED DEDUCTION, SETTLED PAYMENT & VOUCHER RECOVERY
    // =========================================================================

    @Test
    void singleAndMultiTicketPartialRefunds_PreviousDeduction_And_SeparateNonDuplicatedVoucherRestoration() {
        // A. Single-ticket order with 20k fixed voucher: 100k gross -> 80k cash + 20k separate voucher restoration
        Promotion singlePromo = createPromotion("SGL20K-" + randomCode(), "FIXED", 20_000, null, "ALL");
        Showtime st = createShowtime(LocalDateTime.now(HCM_ZONE).plusHours(6), LocalDateTime.now(HCM_ZONE).plusHours(8));
        Booking singleBooking = createPaidTicketOrderWithPromo(st, customer, singlePromo, List.of(100_000L), "VALID");
        Ticket singleTicket = ticketRepository.findByBooking_BookingId(singleBooking.getBookingId()).get(0);

        RefundRequestDetailDto singleSub = submitCustomerTicketRefund(singleBooking, List.of(singleTicket.getTicketId()), "SCHEDULE_CHANGE");
        assertEquals(0, BigDecimal.valueOf(80_000).compareTo(singleSub.getRequestedCashTotal()));
        RefundRequestDetailDto singleReviewed = approveAllItems(singleSub.getId(), manager.getUserId());
        assertEquals(0, BigDecimal.valueOf(80_000).compareTo(singleReviewed.getApprovedCashTotal()),
                "Cash refund must equal net_paid (80k), never converting 20k voucher into cash");
        assertEquals(0, BigDecimal.valueOf(20_000).compareTo(singleReviewed.getApprovedVoucherTotal()));
        assertEquals("GENERATED", singleReviewed.getVoucherStatus());
        assertNotNull(singleReviewed.getRecoveryVoucher());
        assertEquals(0, BigDecimal.valueOf(20_000).compareTo(singleReviewed.getRecoveryVoucher().getRecoveryAmount()));
        assertEquals("RESTORED", redemptionRepository.findByBooking_BookingId(singleBooking.getBookingId()).orElseThrow().getStatus());

        // Strict double-restoration guard rejects second restoration attempt with 409 VOUCHER_ALREADY_RESTORED
        BusinessException dblRestoreEx = assertThrows(BusinessException.class, () ->
                voucherRecoveryService.restoreOrderVoucherStrict(
                        singleReviewed.getId(), singleReviewed.getItems().get(0).getId(), manager.getUserId()));
        assertEquals(HttpStatus.CONFLICT, dblRestoreEx.getStatus());
        assertEquals("VOUCHER_ALREADY_RESTORED", dblRestoreEx.getErrorCode());

        // Idempotent retry returns same voucher without creating duplicate Promotion row
        VoucherRecovery idempotentVr = voucherRecoveryService.generateRecoveryVoucher(
                singleReviewed.getId(), singleReviewed.getItems().get(0).getId(), manager.getUserId());
        assertEquals(singleReviewed.getRecoveryVoucher().getNewVoucherCode(), idempotentVr.getNewVoucherCode());
        assertEquals(1, voucherRecoveryRepository.findByRefundRequest_IdOrderByIdAsc(singleReviewed.getId()).size());

        // B. Multi-ticket (3 tickets x 100k = 300k, 10k voucher -> 290k settled) sequential partial refunds
        Promotion multiPromo = createPromotion("MLT10K-" + randomCode(), "FIXED", 10_000, null, "ALL");
        Booking multiBooking = createPaidTicketOrderWithPromo(st, customer, multiPromo, List.of(100_000L, 100_000L, 100_000L), "VALID");
        List<Ticket> mTickets = ticketRepository.findByBooking_BookingId(multiBooking.getBookingId()).stream()
                .sorted(Comparator.comparing(Ticket::getTicketId))
                .toList();
        assertEquals(3, mTickets.size());
        // Lowest ticket_id got 3,334 discount (96,666 net); other two got 3,333 discount (96,667 net)
        assertEquals(96_666L, PurchaseSnapshotService.toVndLong(mTickets.get(0).getNetPaidAmount()));
        assertEquals(96_667L, PurchaseSnapshotService.toVndLong(mTickets.get(1).getNetPaidAmount()));
        assertEquals(96_667L, PurchaseSnapshotService.toVndLong(mTickets.get(2).getNetPaidAmount()));

        // Partial Refund 1: Ticket 0 only
        RefundRequestDetailDto part1 = submitCustomerTicketRefund(multiBooking, List.of(mTickets.get(0).getTicketId()), "CUSTOMER_EMERGENCY");
        part1 = approveAllItems(part1.getId(), manager.getUserId());
        assertEquals(0, BigDecimal.valueOf(96_666).compareTo(part1.getApprovedCashTotal()));
        assertEquals(0, BigDecimal.valueOf(3_334).compareTo(part1.getApprovedVoucherTotal()));
        assertEquals("PARTIALLY_RESTORED", redemptionRepository.findByBooking_BookingId(multiBooking.getBookingId()).orElseThrow().getStatus());

        // Remaining refundable cash calculation with previous refunded deduction
        assertEquals(0L, snapshotService.calculateRemainingRefundableCash(BigDecimal.valueOf(96_666), BigDecimal.valueOf(96_666)));
        assertEquals(66_666L, snapshotService.calculateRemainingRefundableCash(BigDecimal.valueOf(96_666), BigDecimal.valueOf(30_000)));
        assertThrows(IllegalArgumentException.class, () ->
                snapshotService.calculateRemainingRefundableCash(BigDecimal.valueOf(96_666), BigDecimal.valueOf(96_667)));

        // Partial Refund 2: Remaining Tickets 1 & 2 -> exact remaining cash (193,334) and voucher (6,666)
        RefundRequestDetailDto part2 = submitCustomerTicketRefund(
                multiBooking, List.of(mTickets.get(1).getTicketId(), mTickets.get(2).getTicketId()), "CUSTOMER_EMERGENCY");
        part2 = approveAllItems(part2.getId(), manager.getUserId());
        assertEquals(0, BigDecimal.valueOf(193_334).compareTo(part2.getApprovedCashTotal()));
        assertEquals(0, BigDecimal.valueOf(6_666).compareTo(part2.getApprovedVoucherTotal()));
        assertEquals(290_000L,
                PurchaseSnapshotService.toVndLong(part1.getApprovedCashTotal())
                        + PurchaseSnapshotService.toVndLong(part2.getApprovedCashTotal()),
                "Cumulative partial cash refunds must equal exact settled payment (290,000 VND)");
        assertEquals("RESTORED", redemptionRepository.findByBooking_BookingId(multiBooking.getBookingId()).orElseThrow().getStatus());

        // Historical scan confirms zero violations across all orders created so far
        VoucherHistoricalScanService.HistoricalVoucherScanReport scan = historicalScanService.runFullScan();
        assertEquals(0, scan.duplicateVoucherViolationsCount());
        assertEquals(0, scan.allocationSumMismatchCount());
        assertEquals(0, scan.doubleRestorationViolationsCount());
    }

    @Test
    void settledVsUnsettledPaymentGuard_RejectsUnpaidMissingOrMismatchedPayments() {
        Showtime st = createShowtime(LocalDateTime.now(HCM_ZONE).plusHours(5), LocalDateTime.now(HCM_ZONE).plusHours(7));

        // 1. Unsettled PENDING_PAYMENT booking -> rejected at refund submission with ORDER_NOT_PAID
        Booking unpaidBooking = createDraftBookingWithoutVoucher(st, customer, List.of(100_000L));
        unpaidBooking.setBookingStatus("PENDING_PAYMENT");
        bookingRepository.save(unpaidBooking);
        BookingSeat unpaidSeat = bookingSeatRepository.findByBooking_BookingId(unpaidBooking.getBookingId()).get(0);
        Ticket unpaidTicket = saveTicket(unpaidBooking, unpaidSeat.getBookingSeatId(), "U1", 100_000L, "VALID");

        Payment pendingPay = new Payment();
        pendingPay.setBooking(unpaidBooking);
        pendingPay.setOrderCode(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 8_000_000_000L + 100_000L);
        pendingPay.setAmount(BigDecimal.valueOf(100_000));
        pendingPay.setIdempotencyKey("IDEMP-PEND-" + UUID.randomUUID());
        pendingPay.setPaymentStatus("PENDING");
        paymentRepository.save(pendingPay);

        assertFalse(snapshotService.verifySettledPaymentReconciliation(unpaidBooking, pendingPay, 100_000L));

        BusinessException unpaidSubmitEx = assertThrows(BusinessException.class, () ->
                submitCustomerTicketRefund(unpaidBooking, List.of(unpaidTicket.getTicketId()), "CUSTOMER_TEST"));
        assertEquals("ORDER_NOT_PAID", unpaidSubmitEx.getErrorCode());

        // 2. Booking marked CONFIRMED but has NO PAID payment row -> blocked at approval with NO_PAID_PAYMENT
        Booking noPayBooking = createDraftBookingWithoutVoucher(st, customer, List.of(100_000L));
        noPayBooking.setBookingStatus("CONFIRMED");
        bookingRepository.save(noPayBooking);
        BookingSeat noPaySeat = bookingSeatRepository.findByBooking_BookingId(noPayBooking.getBookingId()).get(0);
        Ticket noPayTicket = saveTicket(noPayBooking, noPaySeat.getBookingSeatId(), "NP1", 100_000L, "VALID");
        snapshotService.allocateOrderSnapshot(noPayBooking, null, List.of(noPayTicket));
        ticketRepository.save(noPayTicket);

        RefundRequestDetailDto noPayRefund = submitCustomerTicketRefund(noPayBooking, List.of(noPayTicket.getTicketId()), "CUSTOMER_TEST");
        BusinessException noPaidApprovalEx = assertThrows(BusinessException.class, () ->
                approveAllItems(noPayRefund.getId(), manager.getUserId()));
        assertEquals(HttpStatus.CONFLICT, noPaidApprovalEx.getStatus());
        assertEquals("NO_PAID_PAYMENT", noPaidApprovalEx.getErrorCode());

        // 3. Settled PAID payment (70,000 VND) mismatches booking total (100,000 VND) -> SNAPSHOT_REVIEW_REQUIRED
        Booking mismatchBooking = createDraftBookingWithoutVoucher(st, customer, List.of(100_000L));
        mismatchBooking.setBookingStatus("CONFIRMED");
        bookingRepository.save(mismatchBooking);
        BookingSeat mmSeat = bookingSeatRepository.findByBooking_BookingId(mismatchBooking.getBookingId()).get(0);
        Ticket mmTicket = saveTicket(mismatchBooking, mmSeat.getBookingSeatId(), "MM1", 100_000L, "VALID");
        mmTicket.setGrossPrice(BigDecimal.valueOf(100_000));
        mmTicket.setDiscountAllocated(BigDecimal.ZERO);
        mmTicket.setNetPaidAmount(BigDecimal.valueOf(100_000));
        ticketRepository.save(mmTicket);
        Payment mismatchedPaid = recordPaidPayment(mismatchBooking, BigDecimal.valueOf(70_000));

        assertFalse(snapshotService.verifySettledPaymentReconciliation(mismatchBooking, mismatchedPaid, 70_000L));
        assertTrue(mismatchBooking.isSnapshotReviewRequired());
        bookingRepository.save(mismatchBooking);

        RefundRequestDetailDto mmRefund = submitCustomerTicketRefund(mismatchBooking, List.of(mmTicket.getTicketId()), "CUSTOMER_TEST");
        BusinessException mmApprovalEx = assertThrows(BusinessException.class, () ->
                approveAllItems(mmRefund.getId(), manager.getUserId()));
        assertEquals(HttpStatus.CONFLICT, mmApprovalEx.getStatus());
        assertEquals("SNAPSHOT_REVIEW_REQUIRED", mmApprovalEx.getErrorCode());

        // Even if snapshotReviewRequired is cleared, cumulative cash (100k) exceeding settled PAID payment (70k) is blocked with 409 PAYOUT_CAP_EXCEEDED
        mismatchBooking.setSnapshotReviewRequired(false);
        bookingRepository.save(mismatchBooking);
        BusinessException capExceededEx = assertThrows(BusinessException.class, () ->
                approveAllItems(mmRefund.getId(), manager.getUserId()));
        assertEquals(HttpStatus.CONFLICT, capExceededEx.getStatus());
        assertEquals("PAYOUT_CAP_EXCEEDED", capExceededEx.getErrorCode());
    }

    // =========================================================================
    // 4. REFUND DEADLINES (Asia/Ho_Chi_Minh), ADMIN EXCEPTION & USED TICKETS
    // =========================================================================

    @Test
    void refundDeadlineService_AsiaHoChiMinh_72hExactBoundary_LateIncidentFormula_30DayCap_And_Fallbacks() {
        LocalDateTime showEnd = LocalDateTime.of(2026, 6, 1, 20, 0, 0); // 2026-06-01 20:00:00 ICT
        LocalDateTime expected72h = LocalDateTime.of(2026, 6, 4, 20, 0, 0);

        // Verify UTC Instant -> Asia/Ho_Chi_Minh (+07:00) conversion inside RefundDeadlineService
        // 2026-06-04T13:00:00Z == 2026-06-04T20:00:00+07:00 (exact 72h boundary!)
        RefundDeadlineService utcInjected = new RefundDeadlineService(
                Clock.fixed(Instant.parse("2026-06-04T13:00:00Z"), ZoneOffset.UTC));
        assertEquals(expected72h, utcInjected.now());
        RefundDeadlineService.DeadlineEvaluation exactFromUtc = utcInjected.evaluate(showEnd, null);
        assertEquals(expected72h, exactFromUtc.deadlineAt());
        assertEquals(RefundDeadlineService.BASIS_NORMAL_72H, exactFromUtc.deadlineBasis());
        assertFalse(exactFromUtc.deadlineExceeded(), "Exact 72h boundary in Asia/Ho_Chi_Minh must be within deadline");

        // 1s before 72h -> within deadline
        var before72h = utcInjected.evaluate(showEnd, null, expected72h.minusSeconds(1));
        assertFalse(before72h.deadlineExceeded());

        // 1s after 72h -> exceeded
        var after72h = utcInjected.evaluate(showEnd, null, expected72h.plusSeconds(1));
        assertTrue(after72h.deadlineExceeded());

        // Late-incident formula: min(show_end + 30d, max(show_end + 72h, notified + 7d))
        // Case A: notified at show_end + 2 days -> notified + 7d = show_end + 9d (> 72h, < 30d)
        IncidentReport verifiedInc = new IncidentReport();
        verifiedInc.setStatus("VERIFIED");
        verifiedInc.setCustomerNotifiedAt(showEnd.plusDays(2));
        var lateEval = utcInjected.evaluate(showEnd, verifiedInc, showEnd.plusDays(9));
        assertEquals(showEnd.plusDays(9), lateEval.deadlineAt());
        assertEquals(RefundDeadlineService.BASIS_LATE_INCIDENT, lateEval.deadlineBasis());
        assertTrue(lateEval.lateIncidentFormulaApplied());
        assertFalse(lateEval.cappedAt30Days());
        assertFalse(lateEval.deadlineExceeded(), "Exact notified+7d boundary must be within deadline");
        assertTrue(utcInjected.evaluate(showEnd, verifiedInc, showEnd.plusDays(9).plusSeconds(1)).deadlineExceeded());

        // Case B: notified early (show_end - 5d -> notified + 7d = show_end + 48h < 72h) -> max() keeps show_end + 72h
        IncidentReport earlyNotifiedInc = new IncidentReport();
        earlyNotifiedInc.setStatus("VERIFIED");
        earlyNotifiedInc.setCustomerNotifiedAt(showEnd.minusDays(5));
        var earlyEval = utcInjected.evaluate(showEnd, earlyNotifiedInc, expected72h);
        assertEquals(expected72h, earlyEval.deadlineAt(), "max(show_end+72h, notified+7d) must never shorten normal 72h window");
        assertFalse(earlyEval.deadlineExceeded());

        // Case C: 30-day cap clamping (notified at show_end + 26d -> notified + 7d = +33d -> clamped to show_end + 30d)
        IncidentReport veryLateInc = new IncidentReport();
        veryLateInc.setStatus("VERIFIED");
        veryLateInc.setCustomerNotifiedAt(showEnd.plusDays(26));
        LocalDateTime cap30d = showEnd.plusDays(30);
        var clampedEval = utcInjected.evaluate(showEnd, veryLateInc, cap30d);
        assertEquals(cap30d, clampedEval.deadlineAt());
        assertTrue(clampedEval.cappedAt30Days());
        assertFalse(clampedEval.deadlineExceeded(), "Exact 30d cap boundary must be within deadline");
        assertTrue(utcInjected.evaluate(showEnd, veryLateInc, cap30d.plusSeconds(1)).deadlineExceeded(),
                "1 second past 30-day cap must exceed deadline");

        // Case D: null customerNotifiedAt on verified incident -> fallback to normal 72h
        IncidentReport nullNotifiedInc = new IncidentReport();
        nullNotifiedInc.setStatus("VERIFIED");
        nullNotifiedInc.setCustomerNotifiedAt(null);
        var nullFallback = utcInjected.evaluate(showEnd, nullNotifiedInc, expected72h.plusSeconds(1));
        assertEquals(expected72h, nullFallback.deadlineAt());
        assertEquals(RefundDeadlineService.BASIS_NORMAL_72H, nullFallback.deadlineBasis());
        assertFalse(nullFallback.lateIncidentFormulaApplied());
        assertTrue(nullFallback.deadlineExceeded());

        // Case E: unverified incident (PENDING_VERIFICATION) with customerNotifiedAt -> fallback to normal 72h
        IncidentReport unverifiedInc = new IncidentReport();
        unverifiedInc.setStatus("PENDING_VERIFICATION");
        unverifiedInc.setCustomerNotifiedAt(showEnd.plusDays(5));
        var unverifiedFallback = utcInjected.evaluate(showEnd, unverifiedInc, expected72h.plusSeconds(1));
        assertEquals(expected72h, unverifiedFallback.deadlineAt());
        assertEquals(RefundDeadlineService.BASIS_NORMAL_72H, unverifiedFallback.deadlineBasis());
        assertTrue(unverifiedFallback.deadlineExceeded());
    }

    @Test
    void refundDeadlineEnforcement_OnTimeDelayedReviewPasses_PastDeadlineRequiresAdminExceptionWithReasonAndAudit() {
        LocalDateTime showStart = LocalDateTime.of(2026, 7, 10, 18, 0, 0);
        LocalDateTime showEnd = LocalDateTime.of(2026, 7, 10, 20, 0, 0); // 72h deadline = 2026-07-13 20:00:00
        Showtime st = createShowtime(showStart, showEnd);

        // 1. On-time customer submission (at exact 72h boundary) reviewed by Manager 2 days later -> MUST SUCCEED
        Booking onTimeBooking = createPaidTicketOrderWithPromo(st, customer, null, List.of(100_000L), "VALID");
        Ticket onTimeTicket = ticketRepository.findByBooking_BookingId(onTimeBooking.getBookingId()).get(0);

        setFixedHcmClock(LocalDateTime.of(2026, 7, 13, 20, 0, 0)); // exact 72h at submission
        RefundRequestDetailDto onTimeReq = submitCustomerTicketRefund(onTimeBooking, List.of(onTimeTicket.getTicketId()), "SCHEDULE_CHANGE");
        assertFalse(onTimeReq.isDeadlineExceeded());
        assertEquals(LocalDateTime.of(2026, 7, 13, 20, 0, 0), onTimeReq.getRefundDeadlineAt());

        setFixedHcmClock(LocalDateTime.of(2026, 7, 15, 14, 0, 0)); // Manager reviews 42h after deadline
        RefundRequestDetailDto onTimeApproved = approveAllItems(onTimeReq.getId(), manager.getUserId());
        assertEquals("AWAITING_PAYOUT_APPROVAL", onTimeApproved.getRequestStatus());
        assertFalse(onTimeApproved.isDeadlineExceeded());

        // 2. Past-deadline submission (1 second after 72h) -> blocked unless ADMIN grants exception with mandatory reason & audit
        Booking lateBooking = createPaidTicketOrderWithPromo(st, customer, null, List.of(110_000L), "VALID");
        Ticket lateTicket = ticketRepository.findByBooking_BookingId(lateBooking.getBookingId()).get(0);

        setFixedHcmClock(LocalDateTime.of(2026, 7, 13, 20, 0, 1)); // 1s past 72h
        RefundRequestDetailDto lateReq = submitCustomerTicketRefund(lateBooking, List.of(lateTicket.getTicketId()), "LATE_REQUEST");
        assertTrue(lateReq.isDeadlineExceeded());

        // 2a. Manager normal approval -> 409 REFUND_DEADLINE_EXCEEDED
        BusinessException mgrBlocked = assertThrows(BusinessException.class, () ->
                approveAllItems(lateReq.getId(), manager.getUserId()));
        assertEquals(HttpStatus.CONFLICT, mgrBlocked.getStatus());
        assertEquals("REFUND_DEADLINE_EXCEEDED", mgrBlocked.getErrorCode());

        // 2b. Manager attempting deadlineExceptionApproved=true -> 403 ADMIN_DEADLINE_EXCEPTION_ONLY
        RefundReviewRequestDto mgrOverrideAttempt = buildApproveReviewDto(lateReq, true, "Manager trying override");
        BusinessException mgrForbidden = assertThrows(BusinessException.class, () ->
                refundService.reviewRefundRequest(lateReq.getId(), mgrOverrideAttempt, manager.getUserId()));
        assertEquals(HttpStatus.FORBIDDEN, mgrForbidden.getStatus());
        assertEquals("ADMIN_DEADLINE_EXCEPTION_ONLY", mgrForbidden.getErrorCode());

        // 2c. Admin attempting deadlineExceptionApproved=true with blank reason -> 400 DEADLINE_EXCEPTION_REASON_REQUIRED
        RefundReviewRequestDto adminBlankReason = buildApproveReviewDto(lateReq, true, "   ");
        adminBlankReason.setNote(null);
        BusinessException blankReasonEx = assertThrows(BusinessException.class, () ->
                refundService.reviewRefundRequest(lateReq.getId(), adminBlankReason, admin.getUserId()));
        assertEquals(HttpStatus.BAD_REQUEST, blankReasonEx.getStatus());
        assertEquals("DEADLINE_EXCEPTION_REASON_REQUIRED", blankReasonEx.getErrorCode());

        // 2d. Admin approves with explicit deadline exception & mandatory reason -> succeeds + audit logged
        String exceptionReason = "Verified hospitalized customer medical certificate for late submission";
        RefundReviewRequestDto adminValidOverride = buildApproveReviewDto(lateReq, true, exceptionReason);
        RefundRequestDetailDto adminApproved = refundService.reviewRefundRequest(
                lateReq.getId(), adminValidOverride, admin.getUserId());

        assertEquals("AWAITING_PAYOUT_APPROVAL", adminApproved.getRequestStatus());
        assertEquals(admin.getUserId(), adminApproved.getDeadlineExceptionBy());
        assertEquals(exceptionReason, adminApproved.getDeadlineExceptionReason());
        assertNotNull(adminApproved.getDeadlineExceptionAt());
        assertTrue(adminApproved.isPayoutEligible(), "Admin deadline exception must unlock payout eligibility");

        List<RefundAuditLog> audits = auditLogRepository.findByRefundRequestIdOrderByCreatedAtDesc(lateReq.getId());
        assertTrue(audits.stream().anyMatch(a ->
                "REFUND_DEADLINE_EXCEPTION_GRANTED".equals(a.getEventType())
                        && admin.getUserId().equals(a.getActorUserId())
                        && a.getDetailsJson() != null
                        && a.getDetailsJson().contains(exceptionReason)),
                "REFUND_DEADLINE_EXCEPTION_GRANTED audit log with actor and reason must be persisted");
    }

    @Test
    void usedAndCheckedInTickets_NeverAutoRejectedAtIntake_And_ReviewableWithVerifiedIncident() {
        LocalDateTime showStart = LocalDateTime.of(2026, 8, 1, 18, 0, 0);
        LocalDateTime showEnd = LocalDateTime.of(2026, 8, 1, 20, 0, 0);
        setFixedHcmClock(LocalDateTime.of(2026, 8, 2, 10, 0, 0)); // within 72h

        Showtime st = createShowtime(showStart, showEnd);
        Promotion promo = createPromotion("USED10-" + randomCode(), "PERCENT", 10, null, "ALL");
        Booking booking = createPaidTicketOrderWithPromo(st, customer, promo, List.of(100_000L, 100_000L), "USED");

        List<Ticket> tickets = ticketRepository.findByBooking_BookingId(booking.getBookingId());
        assertEquals(2, tickets.size());
        // Test BOTH "USED" and "CHECKED_IN" ticket statuses explicitly
        Ticket usedTicket = tickets.get(0);
        usedTicket.setTicketStatus("USED");
        usedTicket.setCheckedInAt(showStart.plusMinutes(5));
        Ticket checkedInTicket = tickets.get(1);
        checkedInTicket.setTicketStatus("CHECKED_IN");
        checkedInTicket.setCheckedInAt(showStart.plusMinutes(6));
        ticketRepository.saveAll(List.of(usedTicket, checkedInTicket));

        // 1. Order lookup surfaces both USED and CHECKED_IN tickets as selectable (remainingRefundableQuantity = 1)
        OrderLookupResponseDto lookup = refundService.lookupPublicOrder(
                booking.getBookingCode(), customer.getEmail(), "127.0.0.10");
        assertEquals(2, lookup.getItems().size());
        assertTrue(lookup.getItems().stream().allMatch(i -> i.getRemainingRefundableQuantity() == 1 && i.isCheckedIn()));

        // 2. Customer refund submission for USED + CHECKED_IN tickets succeeds (PENDING_REVIEW, NOT auto-rejected!)
        RefundRequestDetailDto submitted = submitCustomerTicketRefund(
                booking, List.of(usedTicket.getTicketId(), checkedInTicket.getTicketId()), "CINEMA_INCIDENT");
        assertEquals("PENDING_REVIEW", submitted.getRequestStatus());
        assertEquals("REQUESTED", submitted.getWorkflowStatus());
        assertEquals(0, BigDecimal.valueOf(180_000).compareTo(submitted.getRequestedCashTotal()));

        // 3. Without a verified incident, approval is guarded (request stays PENDING_REVIEW for investigation)
        BusinessException requiresIncEx = assertThrows(BusinessException.class, () ->
                approveAllItems(submitted.getId(), manager.getUserId()));
        assertEquals("CHECKED_IN_TICKET_REQUIRES_VERIFIED_INCIDENT", requiresIncEx.getErrorCode());

        // 4. Staff reports CINEMA_INCIDENT with customerNotifiedAt and Manager verifies it
        IncidentCreateRequestDto incDto = new IncidentCreateRequestDto();
        incDto.setCinemaId(cinema.getCinemaId());
        incDto.setShowtimeId(st.getShowtimeId());
        incDto.setBookingCode(booking.getBookingCode());
        incDto.setIncidentGroup("CINEMA_INCIDENT");
        incDto.setIncidentType("POWER_OUTAGE");
        incDto.setSeverity("HIGH");
        incDto.setTitle("Auditorium power failure mid-screening");
        incDto.setDescription("Projector and HVAC lost power 40 minutes into screening after check-in.");
        incDto.setCustomerNotifiedAt(showEnd.plusHours(1));
        var createdInc = incidentService.createIncident(incDto, staff.getUserId());
        createdIncidentIds.add(createdInc.getId());
        incidentService.verifyByManager(createdInc.getId(), "VERIFIED", "Confirmed via facility log", manager.getUserId());

        // 5. Manager reviews & approves USED + CHECKED_IN tickets -> succeeds with exact cash (180k) & voucher recovery (20k)
        RefundRequestDetailDto approved = approveAllItems(submitted.getId(), manager.getUserId());
        assertEquals("AWAITING_PAYOUT_APPROVAL", approved.getRequestStatus());
        assertEquals("PAYOUT_PENDING", approved.getWorkflowStatus());
        assertEquals(0, BigDecimal.valueOf(180_000).compareTo(approved.getApprovedCashTotal()));
        assertEquals(0, BigDecimal.valueOf(20_000).compareTo(approved.getApprovedVoucherTotal()));
        assertEquals("GENERATED", approved.getVoucherStatus());
        assertEquals(RefundDeadlineService.BASIS_LATE_INCIDENT, approved.getDeadlineBasis());
    }

    // =========================================================================
    // Helper Builders
    // =========================================================================

    private void setFixedHcmClock(LocalDateTime hcmDateTime) {
        Instant instant = hcmDateTime.atZone(HCM_ZONE).toInstant();
        refundDeadlineService.setClock(Clock.fixed(instant, HCM_ZONE));
    }

    private User createTestUser(String email, String role, Long cinemaId) {
        User u = new User();
        u.setFullName("Stage1 " + role);
        u.setEmail(email);
        u.setPhone("09" + String.format("%08d", Math.abs(email.hashCode() % 100_000_000)));
        u.setRole(role);
        u.setCinemaId(cinemaId);
        u.setAccountStatus("ACTIVE");
        u = userRepository.save(u);
        createdUserIds.add(u.getUserId());
        return u;
    }

    private Showtime createShowtime(LocalDateTime startAt, LocalDateTime endAt) {
        Showtime st = new Showtime(movie, room, startAt, endAt, BigDecimal.valueOf(100_000));
        st.setStatus("ACTIVE");
        st = showtimeRepository.save(st);
        createdShowtimeIds.add(st.getShowtimeId());
        return st;
    }

    private Promotion createPromotion(String code, String type, long value, Long maxDiscount, String scope) {
        Promotion p = new Promotion();
        p.setPromotionName("Stage1 Promo " + code);
        p.setVoucherCode(code);
        p.setDiscountType(type);
        p.setDiscountValue(BigDecimal.valueOf(value));
        if (maxDiscount != null) {
            p.setMaxDiscount(BigDecimal.valueOf(maxDiscount));
        }
        p.setMinOrderAmount(BigDecimal.ZERO);
        p.setApplicableScope(scope);
        p.setStartAt(LocalDateTime.of(2025, 1, 1, 0, 0));
        p.setEndAt(LocalDateTime.of(2028, 12, 31, 23, 59));
        p.setStatus("ACTIVE");
        p.setPublic(true);
        p = promotionRepository.save(p);
        createdPromoIds.add(p.getPromotionId());
        return p;
    }

    private Booking createDraftBookingWithoutVoucher(Showtime st, User owner, List<Long> seatPrices) {
        long sub = seatPrices.stream().mapToLong(Long::longValue).sum();
        Booking b = new Booking();
        b.setBookingCode("BOX-STG1-" + randomDigits());
        b.setShowtime(st);
        b.setCinema(cinema);
        b.setUser(owner);
        b.setOrderType("TICKET_ONLY");
        b.setGuestName(owner.getFullName());
        b.setGuestEmail(owner.getEmail());
        b.setGuestPhone(owner.getPhone());
        b.setTicketSubtotal(BigDecimal.valueOf(sub));
        b.setFoodSubtotal(BigDecimal.ZERO);
        b.setSubtotal(BigDecimal.valueOf(sub));
        b.setDiscountAmount(BigDecimal.ZERO);
        b.setServiceFee(BigDecimal.ZERO);
        b.setTotalAmount(BigDecimal.valueOf(sub));
        b.setBookingStatus("DRAFT");
        b.setBookedAt(LocalDateTime.now(HCM_ZONE));
        b.setPaymentDeadline(LocalDateTime.now(HCM_ZONE).plusMinutes(15));
        b.setExpiresAt(LocalDateTime.now(HCM_ZONE).plusMinutes(15));
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());

        int idx = 0;
        for (Long price : seatPrices) {
            idx++;
            BookingSeat bs = new BookingSeat(b, (long) idx, BigDecimal.valueOf(price), "S" + idx, "HOLDING");
            bs = bookingSeatRepository.save(bs);
            b.getBookingSeats().add(bs);
        }
        return bookingRepository.save(b);
    }

    private Booking createPaidTicketOrderWithPromo(
            Showtime st, User owner, Promotion promo, List<Long> seatPrices, String ticketStatus) {
        long ticketSub = seatPrices.stream().mapToLong(Long::longValue).sum();
        long disc = promo != null ? PurchaseSnapshotService.computeConfiguredVoucherDiscountVnd(promo, ticketSub) : 0L;
        long netTotal = ticketSub - disc;

        Booking b = new Booking();
        b.setBookingCode("BOX-STG1-" + randomDigits());
        b.setShowtime(st);
        b.setCinema(cinema);
        b.setUser(owner);
        b.setOrderType("TICKET_ONLY");
        b.setGuestName(owner.getFullName());
        b.setGuestEmail(owner.getEmail());
        b.setGuestPhone(owner.getPhone());
        b.setTicketSubtotal(BigDecimal.valueOf(ticketSub).setScale(2, RoundingMode.HALF_UP));
        b.setFoodSubtotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        b.setSubtotal(BigDecimal.valueOf(ticketSub).setScale(2, RoundingMode.HALF_UP));
        b.setDiscountAmount(BigDecimal.valueOf(disc).setScale(2, RoundingMode.HALF_UP));
        b.setServiceFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        b.setTotalAmount(BigDecimal.valueOf(netTotal).setScale(2, RoundingMode.HALF_UP));
        b.setBookingStatus("CONFIRMED");
        if (promo != null) {
            b.setPromotion(promo);
            b.setVoucherCodeSnapshot(promo.getVoucherCode());
        }
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());
        recordPaidPayment(b, BigDecimal.valueOf(netTotal));

        List<Ticket> issued = new ArrayList<>();
        int idx = 0;
        for (Long price : seatPrices) {
            idx++;
            BookingSeat bs = bookingSeatRepository.save(
                    new BookingSeat(b, 100L + idx, BigDecimal.valueOf(price), "R" + idx, "BOOKED"));
            b.getBookingSeats().add(bs);
            Ticket t = saveTicket(b, bs.getBookingSeatId(), "R" + idx, price, ticketStatus);
            issued.add(t);
        }

        snapshotService.allocateOrderSnapshot(b, BigDecimal.valueOf(netTotal), issued);
        ticketRepository.saveAll(issued);
        bookingSeatRepository.saveAll(b.getBookingSeats());
        return bookingRepository.save(b);
    }

    private Booking createPaidMixedOrderWithScope(
            Showtime st, Promotion promo, List<Long> seatPrices, long foodPrice) {
        long ticketSub = seatPrices.stream().mapToLong(Long::longValue).sum();
        long grossSub = ticketSub + foodPrice;
        long eligibleSub = switch (promo.getApplicableScope()) {
            case "TICKET_ONLY" -> ticketSub;
            case "FOOD_ONLY" -> foodPrice;
            default -> grossSub;
        };
        long disc = PurchaseSnapshotService.computeConfiguredVoucherDiscountVnd(promo, eligibleSub);
        long netTotal = grossSub - disc;

        Booking b = new Booking();
        b.setBookingCode("BOX-MIX-" + randomDigits());
        b.setShowtime(st);
        b.setCinema(cinema);
        b.setUser(customer);
        b.setOrderType("TICKET_AND_FOOD");
        b.setGuestName(customer.getFullName());
        b.setGuestEmail(customer.getEmail());
        b.setGuestPhone(customer.getPhone());
        b.setTicketSubtotal(BigDecimal.valueOf(ticketSub).setScale(2, RoundingMode.HALF_UP));
        b.setFoodSubtotal(BigDecimal.valueOf(foodPrice).setScale(2, RoundingMode.HALF_UP));
        b.setSubtotal(BigDecimal.valueOf(grossSub).setScale(2, RoundingMode.HALF_UP));
        b.setDiscountAmount(BigDecimal.valueOf(disc).setScale(2, RoundingMode.HALF_UP));
        b.setServiceFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        b.setTotalAmount(BigDecimal.valueOf(netTotal).setScale(2, RoundingMode.HALF_UP));
        b.setBookingStatus("CONFIRMED");
        b.setPromotion(promo);
        b.setVoucherCodeSnapshot(promo.getVoucherCode());
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());
        recordPaidPayment(b, BigDecimal.valueOf(netTotal));

        List<Ticket> issued = new ArrayList<>();
        int idx = 0;
        for (Long price : seatPrices) {
            idx++;
            BookingSeat bs = bookingSeatRepository.save(
                    new BookingSeat(b, 200L + idx, BigDecimal.valueOf(price), "M" + idx, "BOOKED"));
            b.getBookingSeats().add(bs);
            issued.add(saveTicket(b, bs.getBookingSeatId(), "M" + idx, price, "VALID"));
        }

        BookingFoodItem bfi = new BookingFoodItem();
        bfi.setBooking(b);
        bfi.setItemType("FOOD");
        bfi.setProductNameSnapshot("Popcorn Stage1");
        bfi.setQuantity(1);
        bfi.setUnitPriceSnapshot(BigDecimal.valueOf(foodPrice));
        bfi.setSubtotal(BigDecimal.valueOf(foodPrice));
        bfi = bookingFoodItemRepository.save(bfi);
        b.getBookingFoodItems().add(bfi);

        snapshotService.allocateOrderSnapshot(b, BigDecimal.valueOf(netTotal), issued);
        ticketRepository.saveAll(issued);
        bookingSeatRepository.saveAll(b.getBookingSeats());
        bookingFoodItemRepository.save(bfi);
        return bookingRepository.save(b);
    }

    private Ticket saveTicket(Booking b, Long bookingSeatId, String seatCode, long priceVnd, String status) {
        Ticket t = new Ticket();
        t.setBooking(b);
        t.setBookingSeatId(bookingSeatId);
        t.setShowtimeId(b.getShowtime() != null ? b.getShowtime().getShowtimeId() : null);
        t.setTicketCode("TCK-" + b.getBookingCode().replace("BOX-", "") + "-" + seatCode + "-" + randomCode().substring(0, 4));
        t.setQrTokenHash(CryptoUtils.sha256(t.getTicketCode()));
        t.setPrice(BigDecimal.valueOf(priceVnd).setScale(2, RoundingMode.HALF_UP));
        t.setTicketStatus(status);
        if ("USED".equalsIgnoreCase(status) || "CHECKED_IN".equalsIgnoreCase(status)) {
            t.setCheckedInAt(LocalDateTime.now(HCM_ZONE).minusMinutes(15));
        }
        return ticketRepository.save(t);
    }

    private Payment recordPaidPayment(Booking b, BigDecimal paidAmount) {
        Payment p = new Payment();
        p.setBooking(b);
        p.setOrderCode(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 8_000_000_000L + 100_000L);
        p.setAmount(paidAmount.setScale(2, RoundingMode.HALF_UP));
        p.setIdempotencyKey("IDEMP-STG1-" + UUID.randomUUID());
        p.setPaymentStatus("PAID");
        p.setPaymentMethod("PAYOS_VIETQR");
        p.setProviderTransactionId("TXN-" + UUID.randomUUID().toString().substring(0, 10));
        p.setPaidAt(LocalDateTime.now(HCM_ZONE).minusMinutes(10));
        return paymentRepository.save(p);
    }

    private RefundRequestDetailDto submitCustomerTicketRefund(Booking b, List<Long> ticketIds, String reasonCategory) {
        PublicRefundSubmitRequestDto req = new PublicRefundSubmitRequestDto();
        req.setOrderCode(b.getBookingCode());
        req.setPurchaseEmail(b.getGuestEmail() != null && !b.getGuestEmail().isBlank() ? b.getGuestEmail() : customer.getEmail());
        req.setReasonCategory(reasonCategory);
        req.setDescription("Stage 1 automated verification request for " + b.getBookingCode());
        req.setToBin("970422");
        req.setToAccountNumber("0987654321");
        req.setConfirmAccountNumber("0987654321");
        req.setBankAccountName("NGUYEN VAN STAGE ONE");

        List<PublicRefundSubmitRequestDto.RequestedItemDto> items = new ArrayList<>();
        for (Long tid : ticketIds) {
            PublicRefundSubmitRequestDto.RequestedItemDto it = new PublicRefundSubmitRequestDto.RequestedItemDto();
            it.setItemKind("TICKET");
            it.setTargetId(tid);
            it.setQuantity(1);
            items.add(it);
        }
        req.setItems(items);
        Long ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM bookings WHERE booking_id = ?", Long.class, b.getBookingId());
        return refundService.submitPublicRefundRequest(req, ownerId, "10.99.0." + (1 + (int) (Math.abs(b.getBookingId()) % 200)));
    }

    private RefundRequestDetailDto approveAllItems(Long refundId, Long reviewerUserId) {
        RefundRequestDetailDto detail = refundService.getRefundDetailForStaffOrManager(refundId, reviewerUserId);
        RefundReviewRequestDto reviewDto = buildApproveReviewDto(detail, false, null);
        return refundService.reviewRefundRequest(refundId, reviewDto, reviewerUserId);
    }

    private RefundReviewRequestDto buildApproveReviewDto(
            RefundRequestDetailDto detail, boolean deadlineException, String deadlineReason) {
        RefundReviewRequestDto dto = new RefundReviewRequestDto();
        dto.setAction("DECIDE_ITEMS");
        dto.setNote("Approved in Stage 1 test");
        if (deadlineException) {
            dto.setDeadlineExceptionApproved(true);
            dto.setDeadlineExceptionReason(deadlineReason);
        }
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
        return dto;
    }

    private static String randomCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
    }

    private static String randomDigits() {
        return String.valueOf(100000 + ThreadLocalRandom.current().nextInt(900000));
    }
}
