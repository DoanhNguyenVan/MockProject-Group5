package com.boxcinema.customer.service.impl;

import com.boxcinema.common.constant.AppConstants;
import com.boxcinema.common.dto.*;
import com.boxcinema.customer.dto.request.*;
import com.boxcinema.customer.dto.response.*;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BookingService;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.customer.service.FoodService;
import com.boxcinema.customer.service.PromotionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional
public class BookingServiceImpl implements BookingService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final BookingFoodItemRepository bookingFoodItemRepository;
    private final ShowtimeRepository showtimeRepository;
    private final UserRepository userRepository;
    private final FoodService foodService;
    private final FoodItemRepository foodItemRepository;
    private final FoodVariantRepository foodVariantRepository;
    private final FoodComboRepository foodComboRepository;
    private final CinemaRepository cinemaRepository;
    private final BookingFoodItemComponentRepository componentRepository;
    private final PurchaseSnapshotService purchaseSnapshotService;
    private final JdbcTemplate jdbcTemplate;
    private final PromotionService promotionService;
    private final PaymentRepository paymentRepository;
    private final OrderVoucherRedemptionRepository redemptionRepository;
    private final OrderVoucherAllocationRepository allocationRepository;

    private final SecureRandom random = new SecureRandom();

    public BookingServiceImpl(
            BookingRepository bookingRepository,
            BookingSeatRepository bookingSeatRepository,
            BookingFoodItemRepository bookingFoodItemRepository,
            ShowtimeRepository showtimeRepository,
            UserRepository userRepository,
            FoodService foodService,
            FoodItemRepository foodItemRepository,
            FoodVariantRepository foodVariantRepository,
            FoodComboRepository foodComboRepository,
            CinemaRepository cinemaRepository,
            BookingFoodItemComponentRepository componentRepository,
            PurchaseSnapshotService purchaseSnapshotService,
            JdbcTemplate jdbcTemplate,
            PromotionService promotionService,
            PaymentRepository paymentRepository
    ) {
        this(bookingRepository, bookingSeatRepository, bookingFoodItemRepository, showtimeRepository,
                userRepository, foodService, foodItemRepository, foodVariantRepository, foodComboRepository,
                cinemaRepository, componentRepository, purchaseSnapshotService, jdbcTemplate,
                promotionService, paymentRepository, null, null);
    }

    @Autowired
    public BookingServiceImpl(
            BookingRepository bookingRepository,
            BookingSeatRepository bookingSeatRepository,
            BookingFoodItemRepository bookingFoodItemRepository,
            ShowtimeRepository showtimeRepository,
            UserRepository userRepository,
            FoodService foodService,
            FoodItemRepository foodItemRepository,
            FoodVariantRepository foodVariantRepository,
            FoodComboRepository foodComboRepository,
            CinemaRepository cinemaRepository,
            BookingFoodItemComponentRepository componentRepository,
            PurchaseSnapshotService purchaseSnapshotService,
            JdbcTemplate jdbcTemplate,
            PromotionService promotionService,
            PaymentRepository paymentRepository,
            @Autowired(required = false) OrderVoucherRedemptionRepository redemptionRepository,
            @Autowired(required = false) OrderVoucherAllocationRepository allocationRepository
    ) {
        this.bookingRepository = bookingRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.bookingFoodItemRepository = bookingFoodItemRepository;
        this.showtimeRepository = showtimeRepository;
        this.userRepository = userRepository;
        this.foodService = foodService;
        this.foodItemRepository = foodItemRepository;
        this.foodVariantRepository = foodVariantRepository;
        this.foodComboRepository = foodComboRepository;
        this.cinemaRepository = cinemaRepository;
        this.componentRepository = componentRepository;
        this.purchaseSnapshotService = purchaseSnapshotService;
        this.jdbcTemplate = jdbcTemplate;
        this.promotionService = promotionService;
        this.paymentRepository = paymentRepository;
        this.redemptionRepository = redemptionRepository;
        this.allocationRepository = allocationRepository;
    }

    @Override
    public BookingResponseDto createBooking(BookingCreateRequestDto request, Long currentUserId) {
        boolean hasFoodOrCombo = (request.getFoodItems() != null && !request.getFoodItems().isEmpty())
                || (request.getCombos() != null && !request.getCombos().isEmpty());

        Showtime showtime = null;
        Cinema cinema = null;
        if (request.getShowtimeId() != null) {
            if (request.getSeatIds() != null && !request.getSeatIds().isEmpty()) {
                // Acquire Showtime X-lock BEFORE saving Booking (FK S-lock) to prevent MySQL S->X upgrade & gap-lock deadlocks
                jdbcTemplate.queryForList("SELECT showtime_id FROM showtimes WHERE showtime_id = ? FOR UPDATE", request.getShowtimeId());
            }
            showtime = showtimeRepository.findById(request.getShowtimeId())
                    .orElseThrow(() -> new IllegalArgumentException("Showtime not found: " + request.getShowtimeId()));
            if (!"ACTIVE".equalsIgnoreCase(showtime.getStatus())) {
                throw new IllegalStateException("This screening is no longer active for booking.");
            }
            if (showtime.getStartAt() != null && !showtime.getStartAt().isAfter(LocalDateTime.now())) {
                throw new IllegalStateException("This screening has already started or ticket sales have closed.");
            }
            if (showtime.getRoom() == null || !"ACTIVE".equalsIgnoreCase(showtime.getRoom().getStatus())) {
                throw new IllegalStateException("The auditorium is currently unavailable for booking.");
            }
            cinema = showtime.getRoom().getCinema();
            if (cinema == null || !"ACTIVE".equalsIgnoreCase(cinema.getStatus())) {
                throw new IllegalStateException("The cinema branch is currently unavailable for booking.");
            }
        } else if (hasFoodOrCombo) {
            if (request.getCinemaId() != null) {
                cinema = cinemaRepository.findById(request.getCinemaId())
                        .orElseThrow(() -> new IllegalArgumentException("Cinema not found: " + request.getCinemaId()));
            } else {
                cinema = cinemaRepository.findAll().stream().findFirst().orElse(null);
            }
        } else {
            throw new IllegalArgumentException("Showtime ID is required.");
        }

        User user = currentUserId != null ? userRepository.findById(currentUserId).orElse(null) : null;

        String effectiveName;
        String effectiveEmail;
        String effectivePhone;
        if (user != null) {
            effectiveName = (user.getFullName() != null && !user.getFullName().isBlank()) ? user.getFullName() : "Customer";
            effectiveEmail = user.getEmail();
            effectivePhone = (user.getPhone() != null && !user.getPhone().isBlank()) ? user.getPhone() : "";
        } else {
            effectiveName = (request.getGuestName() != null && !request.getGuestName().isBlank()) ? request.getGuestName().trim() : "Guest";
            effectiveEmail = request.getGuestEmail() != null ? request.getGuestEmail().trim().toLowerCase(Locale.ROOT) : "";
            effectivePhone = request.getGuestPhone() != null ? request.getGuestPhone().trim() : "";
            if (effectiveEmail.isBlank() || !effectiveEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
                throw new IllegalArgumentException("A valid guest email address is required.");
            }
            if (!effectivePhone.isBlank() && !effectivePhone.matches("^\\+?[0-9 ()-]{8,20}$")) {
                throw new IllegalArgumentException("Please enter a valid guest phone number.");
            }
        }

        Booking booking = new Booking();
        booking.setShowtime(showtime);
        booking.setCinema(cinema);
        booking.setUser(user);
        booking.setBookingChannel("ONLINE");

        booking.setGuestName(effectiveName);
        booking.setGuestEmail(effectiveEmail);
        booking.setGuestPhone(effectivePhone);
        booking.setBookingStatus("DRAFT");
        booking.setBookedAt(LocalDateTime.now());
        booking.setPaymentDeadline(LocalDateTime.now().plusMinutes(10));
        booking.setExpiresAt(LocalDateTime.now().plusMinutes(10));

        // Generate unique booking code e.g. BOX-20261116-XXXX
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String code;
        do {
            int randSuffix = 1000 + random.nextInt(9000);
            code = "BOX-" + dateStr + "-" + randSuffix;
        } while (bookingRepository.findByBookingCode(code).isPresent());
        booking.setBookingCode(code);

        booking = bookingRepository.save(booking);

        // Process Seats (sorted ascending to prevent AB-BA deadlocks)
        BigDecimal ticketSubtotal = BigDecimal.ZERO;
        List<BookingSeat> bookingSeats = new ArrayList<>();
        if (showtime != null && request.getSeatIds() != null && !request.getSeatIds().isEmpty()) {
            BigDecimal basePrice = showtime.getBasePrice() != null ? showtime.getBasePrice() : BigDecimal.valueOf(90000);
            List<Long> sortedSeatIds = request.getSeatIds().stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
            if (sortedSeatIds.size() > AppConstants.MAX_SEATS_PER_BOOKING) {
                throw new IllegalArgumentException("You can only select up to " + AppConstants.MAX_SEATS_PER_BOOKING + " seats per booking.");
            }
            Long roomId = showtime.getRoom() != null ? showtime.getRoom().getRoomId() : null;

            // Validate Sweetbox couple-seat pair_id completeness
            if (!sortedSeatIds.isEmpty() && roomId != null) {
                String inPlaceholders = String.join(",", Collections.nCopies(sortedSeatIds.size(), "?"));
                List<Object> pairParams = new ArrayList<>();
                pairParams.add(roomId);
                pairParams.addAll(sortedSeatIds);
                List<String> pairIds = jdbcTemplate.queryForList(
                        "SELECT DISTINCT pair_id FROM seats WHERE room_id = ? AND seat_id IN (" + inPlaceholders + ") AND pair_id IS NOT NULL AND pair_id <> ''",
                        String.class, pairParams.toArray());
                for (String pid : pairIds) {
                    List<Long> partnerIds = jdbcTemplate.queryForList(
                            "SELECT seat_id FROM seats WHERE room_id = ? AND pair_id = ? AND status = 'ACTIVE'",
                            Long.class, roomId, pid);
                    if (!sortedSeatIds.containsAll(partnerIds)) {
                        throw new IllegalArgumentException("Couple seats (" + pid + ") must be booked together.");
                    }
                }
            }

            for (Long seatId : sortedSeatIds) {
                // Query seat row, number, surcharge — validate seat belongs to showtime room and is ACTIVE
                String sql = "SELECT s.seat_id, s.seat_row, s.seat_number, st.price_surcharge " +
                             "FROM seats s JOIN seat_types st ON s.seat_type_id = st.seat_type_id " +
                             "WHERE s.seat_id = ? AND s.room_id = ? AND s.status = 'ACTIVE'";
                List<Map<String, Object>> seatRows = jdbcTemplate.queryForList(sql, seatId, roomId);
                if (seatRows.isEmpty()) {
                    throw new IllegalArgumentException("Seat " + seatId + " is invalid or unavailable for this auditorium.");
                }
                Map<String, Object> row = seatRows.get(0);
                String label = row.get("seat_row") + String.valueOf(row.get("seat_number"));
                BigDecimal surcharge = row.get("price_surcharge") != null ? (BigDecimal) row.get("price_surcharge") : BigDecimal.ZERO;
                BigDecimal unitPrice = basePrice.add(surcharge);

                // Find or create showtime_seat record
                Long showtimeSeatId = ensureShowtimeSeat(showtime.getShowtimeId(), seatId, currentUserId, request.getHoldSessionId());

                BookingSeat bs = new BookingSeat(booking, showtimeSeatId, unitPrice, label, "HOLDING");
                bs.setActiveSeatKey(showtimeSeatId);
                bookingSeats.add(bs);
                ticketSubtotal = ticketSubtotal.add(unitPrice);
            }
            booking.getBookingSeats().clear();
            booking.getBookingSeats().addAll(bookingSeats);
        }
        booking.setTicketSubtotal(ticketSubtotal);

        // Process Food & Combos
        processFoodAndCombos(booking, request.getFoodItems(), request.getCombos());

        boolean hasSeats = !booking.getBookingSeats().isEmpty();
        boolean hasFood = !booking.getBookingFoodItems().isEmpty();
        if (hasSeats && hasFood) booking.setOrderType("TICKET_AND_FOOD");
        else if (hasFood) booking.setOrderType("FOOD_ONLY");
        else booking.setOrderType("TICKET_ONLY");

        booking.recalculateTotals();

        // Apply voucher discount if provided (with PESSIMISTIC_WRITE lock; fail explicitly if invalid)
        if (request.getVoucherCode() != null && !request.getVoucherCode().isBlank()) {
            String rawCode = request.getVoucherCode().trim();
            if (rawCode.contains(",") || rawCode.contains(";")) {
                throw new BusinessException(HttpStatus.CONFLICT, "ORDER_ALREADY_HAS_VOUCHER",
                        "Only one voucher can be applied per order.");
            }
            Promotion promo = promotionService.findByVoucherCode(rawCode);
            BigDecimal subtotalForVoucher = computeEligibleSubtotalForPromotion(booking, promo);
            BigDecimal discount = promotionService.validateAndLockForRedemption(
                    rawCode, subtotalForVoucher, currentUserId, effectiveEmail, booking.getBookingId());
            if (promo == null) {
                promo = promotionService.findByVoucherCode(rawCode);
            }
            booking.setPromotion(promo);
            booking.setVoucherCodeSnapshot(rawCode.toUpperCase(Locale.ROOT));
            booking.setDiscountAmount(discount);
            booking.recalculateTotals();
            persistOrUpdateOrderVoucherRedemption(booking, promo, subtotalForVoucher, discount, false);
        }

        booking = bookingRepository.save(booking);
        purchaseSnapshotService.allocateOrderSnapshot(booking, null, null);
        booking = bookingRepository.save(booking);

        return mapToDto(booking);
    }

    @Override
    public BookingResponseDto applyVoucherToBooking(Long bookingId, String voucherCode, Long currentUserId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
        enforceBookingAccess(booking, currentUserId);

        if (voucherCode == null || voucherCode.isBlank()) {
            throw new IllegalArgumentException("Please enter a voucher code.");
        }
        if (voucherCode.contains(",") || voucherCode.contains(";")) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_ALREADY_HAS_VOUCHER",
                    "Only one voucher can be applied per order.");
        }
        if (booking.getPromotion() != null
                || (booking.getVoucherCodeSnapshot() != null && !booking.getVoucherCodeSnapshot().isBlank())) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_ALREADY_HAS_VOUCHER",
                    "Order already has an applied voucher (" + booking.getVoucherCodeSnapshot() + "). Only one voucher is allowed per order.");
        }
        if (redemptionRepository != null) {
            Optional<OrderVoucherRedemption> existingOpt = redemptionRepository.findByBooking_BookingId(bookingId);
            if (existingOpt.isPresent() && !"CANCELLED".equalsIgnoreCase(existingOpt.get().getStatus())) {
                throw new BusinessException(HttpStatus.CONFLICT, "ORDER_ALREADY_HAS_VOUCHER",
                        "Order already has an applied voucher (" + existingOpt.get().getVoucherCode() + "). Only one voucher is allowed per order.");
            }
        }

        String rawCode = voucherCode.trim().toUpperCase(Locale.ROOT);
        Promotion promo = promotionService.findByVoucherCode(rawCode);
        BigDecimal eligibleSubtotal = computeEligibleSubtotalForPromotion(booking, promo);
        Long ownerUserId = booking.getUser() != null ? booking.getUser().getUserId() : currentUserId;
        BigDecimal discount = promotionService.validateAndLockForRedemption(
                rawCode, eligibleSubtotal, ownerUserId, booking.getGuestEmail(), booking.getBookingId());
        if (promo == null) {
            promo = promotionService.findByVoucherCode(rawCode);
        }

        booking.setPromotion(promo);
        booking.setVoucherCodeSnapshot(rawCode);
        booking.setDiscountAmount(discount);
        booking.recalculateTotals();
        persistOrUpdateOrderVoucherRedemption(booking, promo, eligibleSubtotal, discount, true);

        booking = bookingRepository.save(booking);
        purchaseSnapshotService.allocateOrderSnapshot(booking, null, null);
        booking = bookingRepository.save(booking);
        return mapToDto(booking);
    }

    private BigDecimal computeEligibleSubtotalForPromotion(Booking booking, Promotion promo) {
        if (booking == null) return BigDecimal.ZERO;
        if (promo == null || promo.getApplicableScope() == null) {
            return booking.getSubtotal() != null ? booking.getSubtotal() : BigDecimal.ZERO;
        }
        String scope = promo.getApplicableScope().trim().toUpperCase(Locale.ROOT);
        return switch (scope) {
            case "TICKET", "TICKETS", "TICKET_ONLY", "SEAT", "SEATS" ->
                    booking.getTicketSubtotal() != null ? booking.getTicketSubtotal() : BigDecimal.ZERO;
            case "FOOD", "FOOD_ONLY", "FOOD_ITEM", "FNB", "CONCESSION", "CONCESSIONS", "FOOD_AND_COMBO" ->
                    booking.getFoodSubtotal() != null ? booking.getFoodSubtotal() : BigDecimal.ZERO;
            default -> booking.getSubtotal() != null ? booking.getSubtotal() : BigDecimal.ZERO;
        };
    }

    private void persistOrUpdateOrderVoucherRedemption(
            Booking booking,
            Promotion promo,
            BigDecimal eligibleSubtotal,
            BigDecimal discountAmount,
            boolean strictRejectExisting) {
        if (redemptionRepository == null || booking == null || booking.getBookingId() == null || promo == null) {
            return;
        }
        try {
            Optional<OrderVoucherRedemption> existingOpt = redemptionRepository.findByBooking_BookingId(booking.getBookingId());
            if (strictRejectExisting && existingOpt.isPresent() && !"CANCELLED".equalsIgnoreCase(existingOpt.get().getStatus())) {
                throw new BusinessException(HttpStatus.CONFLICT, "ORDER_ALREADY_HAS_VOUCHER",
                        "Order already has an applied voucher.");
            }
            OrderVoucherRedemption ovr = existingOpt.orElseGet(OrderVoucherRedemption::new);
            ovr.setBooking(booking);
            ovr.setPromotion(promo);
            ovr.setVoucherCode(booking.getVoucherCodeSnapshot() != null ? booking.getVoucherCodeSnapshot() : promo.getVoucherCode());
            ovr.setDiscountType(promo.getDiscountType());
            ovr.setDiscountValue(promo.getDiscountValue());
            ovr.setMaxDiscount(promo.getMaxDiscount());
            ovr.setEligibleAmount(PurchaseSnapshotService.toVndLong(eligibleSubtotal));
            ovr.setActualDiscount(PurchaseSnapshotService.toVndLong(discountAmount));
            ovr.setStatus("APPLIED");
            ovr.setRedeemedAt(LocalDateTime.now());
            ovr.setRestoredAt(null);
            redemptionRepository.saveAndFlush(ovr);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(HttpStatus.CONFLICT, "ORDER_ALREADY_HAS_VOUCHER",
                    "Order already has an applied voucher (uk_ovr_booking_id constraint violation).");
        }
    }

    @Override
    public BookingResponseDto updateFoodItems(Long bookingId, UpdateFoodItemsRequestDto request, Long currentUserId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
        enforceBookingAccess(booking, currentUserId);

        String curStatus = booking.getBookingStatus() != null ? booking.getBookingStatus().toUpperCase(Locale.ROOT) : "";
        if (!"DRAFT".equals(curStatus) && !"PENDING_PAYMENT".equals(curStatus)) {
            throw new IllegalStateException("Cannot modify food items when booking status is " + booking.getBookingStatus() + ".");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = booking.getPaymentDeadline() != null ? booking.getPaymentDeadline() : booking.getExpiresAt();
        if (deadline != null && !deadline.isAfter(now)) {
            throw new IllegalStateException("Booking hold has expired. Please start a new booking.");
        }
        if (booking.getShowtime() != null && booking.getShowtime().getStartAt() != null
                && !booking.getShowtime().getStartAt().isAfter(now)) {
            throw new IllegalStateException("This screening has already started or ticket sales have closed.");
        }

        // Supersede any existing PENDING payments so stale QR codes cannot confirm modified cart
        for (Payment pendingPayment : paymentRepository.findByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(bookingId, "PENDING")) {
            pendingPayment.setPaymentStatus("SUPERSEDED");
            paymentRepository.save(pendingPayment);
        }

        // Release prior reserved food
        releaseBookingFood(booking);

        // Clear existing combo components and booking food items
        componentRepository.deleteByBooking_BookingId(bookingId);
        bookingFoodItemRepository.deleteByBooking_BookingId(bookingId);
        booking.getBookingFoodItems().clear();

        // Process new food items & combos
        processFoodAndCombos(booking, request.getFoodItems(), request.getCombos());

        boolean hasSeats = booking.getBookingSeats() != null && !booking.getBookingSeats().isEmpty();
        boolean hasFood = booking.getBookingFoodItems() != null && !booking.getBookingFoodItems().isEmpty();
        if (hasSeats && hasFood) booking.setOrderType("TICKET_AND_FOOD");
        else if (hasFood) booking.setOrderType("FOOD_ONLY");
        else booking.setOrderType("TICKET_ONLY");

        booking.recalculateTotals();

        // Re-validate and recompute voucher discount when cart subtotal changes (fixes D8)
        if (booking.getVoucherCodeSnapshot() != null && !booking.getVoucherCodeSnapshot().isBlank()) {
            try {
                BigDecimal eligibleSub = computeEligibleSubtotalForPromotion(booking, booking.getPromotion());
                BigDecimal recomputed = promotionService.validateAndLockForRedemption(
                        booking.getVoucherCodeSnapshot(),
                        eligibleSub,
                        booking.getUser() != null ? booking.getUser().getUserId() : currentUserId,
                        booking.getGuestEmail(),
                        booking.getBookingId());
                booking.setDiscountAmount(recomputed);
                persistOrUpdateOrderVoucherRedemption(booking, booking.getPromotion(), eligibleSub, recomputed, false);
            } catch (IllegalArgumentException ex) {
                booking.setPromotion(null);
                booking.setVoucherCodeSnapshot(null);
                booking.setDiscountAmount(BigDecimal.ZERO);
                if (redemptionRepository != null) {
                    redemptionRepository.findByBooking_BookingId(booking.getBookingId()).ifPresent(ovr -> {
                        ovr.setStatus("CANCELLED");
                        ovr.setActualDiscount(0L);
                        redemptionRepository.save(ovr);
                        if (allocationRepository != null && ovr.getId() != null) {
                            allocationRepository.deleteByRedemption_Id(ovr.getId());
                        }
                    });
                }
            }
            booking.recalculateTotals();
        }

        booking = bookingRepository.save(booking);
        purchaseSnapshotService.allocateOrderSnapshot(booking, null, null);
        booking = bookingRepository.save(booking);

        return mapToDto(booking);
    }

    @Override
    public BookingResponseDto confirmBooking(Long bookingId, Long currentUserId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
        enforceBookingAccess(booking, currentUserId);

        String curStatus = booking.getBookingStatus() != null ? booking.getBookingStatus().toUpperCase(Locale.ROOT) : "";
        if ("CONFIRMED".equals(curStatus)) {
            return mapToDto(booking);
        }
        if (!"DRAFT".equals(curStatus) && !"PENDING_PAYMENT".equals(curStatus)) {
            throw new IllegalStateException("Cannot confirm booking when status is " + booking.getBookingStatus() + ".");
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = booking.getPaymentDeadline() != null ? booking.getPaymentDeadline() : booking.getExpiresAt();
        if (deadline != null && !deadline.isAfter(now)) {
            throw new IllegalStateException("Booking hold has expired. Please start a new booking.");
        }
        if (booking.getShowtime() != null && booking.getShowtime().getStartAt() != null
                && !booking.getShowtime().getStartAt().isAfter(now)) {
            throw new IllegalStateException("This screening has already started or ticket sales have closed.");
        }

        if (booking.getVoucherCodeSnapshot() != null && !booking.getVoucherCodeSnapshot().isBlank()) {
            BigDecimal eligibleSub = computeEligibleSubtotalForPromotion(booking, booking.getPromotion());
            BigDecimal recomputed = promotionService.validateAndLockForRedemption(
                    booking.getVoucherCodeSnapshot(),
                    eligibleSub,
                    booking.getUser() != null ? booking.getUser().getUserId() : currentUserId,
                    booking.getGuestEmail(),
                    booking.getBookingId());
            booking.setDiscountAmount(recomputed);
            booking.recalculateTotals();
            persistOrUpdateOrderVoucherRedemption(booking, booking.getPromotion(), eligibleSub, recomputed, false);
        }

        booking.setBookingStatus("PENDING_PAYMENT");
        booking.setPaymentDeadline(now.plusMinutes(10));
        booking.setExpiresAt(now.plusMinutes(10));
        if (booking.getBookingSeats() != null && !booking.getBookingSeats().isEmpty()) {
            List<Long> stsIds = booking.getBookingSeats().stream()
                    .map(BookingSeat::getShowtimeSeatId)
                    .filter(Objects::nonNull)
                    .toList();
            if (!stsIds.isEmpty()) {
                String inSql = String.join(",", Collections.nCopies(stsIds.size(), "?"));
                jdbcTemplate.update(
                        "UPDATE showtime_seats SET hold_expires_at = DATE_ADD(NOW(), INTERVAL 10 MINUTE) " +
                                "WHERE showtime_seat_id IN (" + inSql + ") AND seat_status = 'HELD'",
                        stsIds.toArray());
            }
        }
        booking = bookingRepository.save(booking);

        return mapToDto(booking);
    }

    @Override
    @Transactional(readOnly = true)
    public BookingResponseDto getBookingById(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
        return mapToDto(booking);
    }

    @Override
    @Transactional(readOnly = true)
    public BookingResponseDto getBookingById(Long bookingId, Long currentUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
        enforceBookingAccess(booking, currentUserId);
        return mapToDto(booking);
    }

    private void enforceBookingAccess(Booking booking, Long currentUserId) {
        if (booking.getUser() == null) {
            if (currentUserId != null) {
                User actor = userRepository.findById(currentUserId).orElse(null);
                if (hasStaffOrAdminBranchAccess(actor, booking)) {
                    return;
                }
            }
            if (SecurityContextUtils.hasGuestBookingAccess(booking)) {
                return;
            }
            throw new BusinessException(HttpStatus.FORBIDDEN, "BOOKING_ACCESS_DENIED",
                    "You do not have permission to access or modify this guest booking.");
        }
        if (currentUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Please sign in to access or modify this booking.");
        }
        if (!booking.getUser().getUserId().equals(currentUserId)) {
            User actor = userRepository.findById(currentUserId).orElse(null);
            if (!hasStaffOrAdminBranchAccess(actor, booking)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "BOOKING_ACCESS_DENIED",
                        "You do not have permission to access or modify this booking.");
            }
        }
    }

    private boolean hasStaffOrAdminBranchAccess(User actor, Booking booking) {
        if (actor == null || actor.getRole() == null) {
            return false;
        }
        String role = actor.getRole().trim().toUpperCase(Locale.ROOT);
        if ("ADMIN".equals(role)) {
            return true;
        }
        if ("STAFF".equals(role) || "MANAGER".equals(role)) {
            Long staffCinemaId = actor.getCinemaId();
            Long bookingCinemaId = booking.resolveCinemaId();
            return staffCinemaId != null && staffCinemaId.equals(bookingCinemaId);
        }
        return false;
    }

    private void processFoodAndCombos(Booking booking, List<BookingFoodItemRequestDto> foodItems, List<BookingComboRequestDto> combos) {
        List<BookingFoodItem> bfiList = new ArrayList<>();

        if (foodItems != null) {
            List<BookingFoodItemRequestDto> sortedFood = foodItems.stream()
                    .filter(f -> f != null && f.getFoodItemId() != null && f.getQuantity() > 0)
                    .sorted(Comparator.comparing(BookingFoodItemRequestDto::getFoodItemId)
                            .thenComparing(f -> f.getVariantId() != null ? f.getVariantId() : 0L))
                    .toList();
            for (BookingFoodItemRequestDto fiReq : sortedFood) {
                FoodItem item = foodItemRepository.findById(fiReq.getFoodItemId())
                        .orElseThrow(() -> new IllegalArgumentException("Food item not found: " + fiReq.getFoodItemId()));
                if (!item.isAvailable() || !"ACTIVE".equalsIgnoreCase(item.getStatus())) {
                    throw new IllegalArgumentException("Food item '" + item.getName() + "' is currently unavailable.");
                }
                FoodVariant variant = null;
                if (fiReq.getVariantId() != null) {
                    variant = foodVariantRepository.findById(fiReq.getVariantId())
                            .orElseThrow(() -> new IllegalArgumentException("Food variant not found: " + fiReq.getVariantId()));
                    if (variant.getFoodItem() == null || !variant.getFoodItem().getId().equals(item.getId())
                            || !"ACTIVE".equalsIgnoreCase(variant.getStatus())) {
                        throw new IllegalArgumentException("Selected variant does not belong to food item '" + item.getName() + "' or is inactive.");
                    }
                }

                BigDecimal unitPrice = variant != null ? variant.getPrice() : item.getPrice();
                BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(fiReq.getQuantity()));

                // Reserve inventory
                foodService.checkAndReserveFoodStock(item.getId(), fiReq.getQuantity());

                BookingFoodItem bfi = new BookingFoodItem();
                bfi.setBooking(booking);
                bfi.setItemType("FOOD");
                bfi.setFoodItem(item);
                bfi.setVariant(variant);
                bfi.setProductNameSnapshot(item.getName());
                bfi.setVariantNameSnapshot(variant != null ? variant.getName() : null);
                bfi.setQuantity(fiReq.getQuantity());
                bfi.setUnitPriceSnapshot(unitPrice);
                bfi.setSubtotal(subtotal);

                bfiList.add(bfi);
            }
        }

        if (combos != null) {
            List<BookingComboRequestDto> sortedCombos = combos.stream()
                    .filter(c -> c != null && c.getComboId() != null && c.getQuantity() > 0)
                    .sorted(Comparator.comparing(BookingComboRequestDto::getComboId))
                    .toList();
            for (BookingComboRequestDto cbReq : sortedCombos) {
                FoodCombo combo = foodComboRepository.findById(cbReq.getComboId())
                        .orElseThrow(() -> new IllegalArgumentException("Food combo not found: " + cbReq.getComboId()));
                if (!combo.isAvailable() || !"ACTIVE".equalsIgnoreCase(combo.getStatus())) {
                    throw new IllegalArgumentException("Combo '" + combo.getName() + "' is currently unavailable.");
                }

                BigDecimal unitPrice = combo.getPrice();
                BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(cbReq.getQuantity()));

                // Reserve inventory
                foodService.checkAndReserveComboStock(combo.getId(), cbReq.getQuantity());

                BookingFoodItem bfi = new BookingFoodItem();
                bfi.setBooking(booking);
                bfi.setItemType("COMBO");
                bfi.setCombo(combo);
                bfi.setProductNameSnapshot(combo.getName());
                bfi.setVariantNameSnapshot(null);
                bfi.setQuantity(cbReq.getQuantity());
                bfi.setUnitPriceSnapshot(unitPrice);
                bfi.setSubtotal(subtotal);

                bfiList.add(bfi);
            }
        }

        booking.getBookingFoodItems().clear();
        if (!bfiList.isEmpty()) {
            booking.getBookingFoodItems().addAll(bfiList);
        }
    }

    private void releaseBookingFood(Booking booking) {
        if (booking.getBookingFoodItems() == null) return;
        List<BookingFoodItem> foodItems = booking.getBookingFoodItems().stream()
                .filter(bfi -> !"RELEASED".equalsIgnoreCase(bfi.getDeliveryStatus()))
                .sorted(Comparator.comparing(bfi -> {
                    if ("FOOD".equalsIgnoreCase(bfi.getItemType()) && bfi.getFoodItem() != null) {
                        return "A_" + bfi.getFoodItem().getId();
                    }
                    if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getCombo() != null) {
                        return "B_" + bfi.getCombo().getId();
                    }
                    return "Z";
                }))
                .toList();
        for (BookingFoodItem bfi : foodItems) {
            if ("FOOD".equalsIgnoreCase(bfi.getItemType()) && bfi.getFoodItem() != null) {
                foodService.releaseFoodStock(bfi.getFoodItem().getId(), bfi.getQuantity());
                bfi.setDeliveryStatus("RELEASED");
            } else if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getCombo() != null) {
                foodService.releaseComboStock(bfi.getCombo().getId(), bfi.getQuantity());
                bfi.setDeliveryStatus("RELEASED");
            }
        }
    }

    private Long ensureShowtimeSeat(Long showtimeId, Long seatId, Long currentUserId, String holdSessionId) {
        Long lockedSeatId = lockAndValidateExistingSeat(showtimeId, seatId, currentUserId, holdSessionId);
        if (lockedSeatId != null) {
            return lockedSeatId;
        }

        try {
            jdbcTemplate.update(
                    "INSERT INTO showtime_seats (showtime_id, seat_id, seat_status, held_by_user_id, hold_session_id, hold_expires_at) VALUES (?, ?, 'HELD', ?, ?, DATE_ADD(NOW(), INTERVAL 10 MINUTE))",
                    showtimeId, seatId, currentUserId, holdSessionId
            );
            return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            Long racedSeatId = lockAndValidateExistingSeat(showtimeId, seatId, currentUserId, holdSessionId);
            if (racedSeatId != null) {
                return racedSeatId;
            }
            throw new IllegalStateException("Seat was just reserved by another customer. Please choose another seat.");
        }
    }

    private Long lockAndValidateExistingSeat(Long showtimeId, Long seatId, Long currentUserId, String holdSessionId) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT showtime_seat_id, seat_status, held_by_user_id, hold_session_id, " +
                "       TIMESTAMPDIFF(SECOND, NOW(), hold_expires_at) AS rem_sec " +
                "FROM showtime_seats WHERE showtime_id = ? AND seat_id = ? FOR UPDATE",
                showtimeId, seatId
        );
        if (existing.isEmpty()) {
            return null;
        }

        Map<String, Object> row = existing.get(0);
        Long stsId = ((Number) row.get("showtime_seat_id")).longValue();
        String status = row.get("seat_status") != null ? String.valueOf(row.get("seat_status")) : "";
        Long heldUser = row.get("held_by_user_id") != null ? ((Number) row.get("held_by_user_id")).longValue() : null;
        String heldSession = row.get("hold_session_id") != null ? String.valueOf(row.get("hold_session_id")) : null;
        long remSec = row.get("rem_sec") != null ? ((Number) row.get("rem_sec")).longValue() : 0L;

        if ("BOOKED".equalsIgnoreCase(status) || "SOLD".equalsIgnoreCase(status)) {
            throw new IllegalStateException("Seat is already booked.");
        }

        Integer activeConfirmed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM booking_seats bs JOIN bookings b ON bs.booking_id = b.booking_id " +
                "WHERE bs.showtime_seat_id = ? AND (b.booking_status IN ('CONFIRMED', 'PAID') " +
                "  OR (b.booking_status IN ('PENDING', 'PENDING_PAYMENT', 'HOLDING') AND (b.payment_deadline IS NULL OR b.payment_deadline > NOW())))",
                Integer.class, stsId
        );
        if (activeConfirmed != null && activeConfirmed > 0) {
            throw new IllegalStateException("Seat is already booked by another order.");
        }

        if ("HELD".equalsIgnoreCase(status) && remSec > 0) {
            boolean sameOwner;
            if (heldUser != null) {
                sameOwner = currentUserId != null && currentUserId.equals(heldUser);
            } else {
                sameOwner = holdSessionId != null && !holdSessionId.isBlank() && holdSessionId.equals(heldSession);
            }
            if (!sameOwner) {
                throw new IllegalStateException("Seat is currently held by another customer.");
            }
        }

        jdbcTemplate.update(
                "UPDATE showtime_seats SET seat_status = 'HELD', held_by_user_id = ?, hold_session_id = ?, hold_expires_at = DATE_ADD(NOW(), INTERVAL 10 MINUTE) WHERE showtime_seat_id = ?",
                currentUserId, holdSessionId, stsId
        );
        return stsId;
    }

    private BookingResponseDto mapToDto(Booking b) {
        BookingResponseDto dto = new BookingResponseDto();
        dto.setBookingId(b.getBookingId());
        dto.setBookingCode(b.getBookingCode());
        if (b.getShowtime() != null) {
            dto.setShowtimeId(b.getShowtime().getShowtimeId());
            if (b.getShowtime().getMovie() != null) {
                dto.setMovieTitle(b.getShowtime().getMovie().getTitle());
                dto.setMoviePosterUrl(b.getShowtime().getMovie().getPosterUrl());
            }
            if (b.getShowtime().getRoom() != null) {
                dto.setRoomName(b.getShowtime().getRoom().getRoomName());
                if (b.getShowtime().getRoom().getCinema() != null) {
                    dto.setCinemaName(b.getShowtime().getRoom().getCinema().getCinemaName());
                }
            }
            if (b.getShowtime().getStartAt() != null) {
                dto.setShowtimeStart(b.getShowtime().getStartAt().format(DateTimeFormatter.ofPattern("HH:mm - dd/MM/yyyy")));
            }
        }
        dto.setGuestName(b.getGuestName());
        dto.setGuestEmail(b.getGuestEmail());
        dto.setGuestPhone(b.getGuestPhone());
        dto.setTicketSubtotal(b.getTicketSubtotal());
        dto.setFoodSubtotal(b.getFoodSubtotal());
        dto.setDiscountAmount(b.getDiscountAmount());
        dto.setVoucherCode(b.getVoucherCodeSnapshot());
        dto.setServiceFee(b.getServiceFee());
        dto.setTotalAmount(b.getTotalAmount());
        dto.setBookingStatus(b.getBookingStatus());
        dto.setPaymentDeadline(b.getPaymentDeadline());
        dto.setConfirmedAt(b.getConfirmedAt());

        if (b.getBookingSeats() != null) {
            for (BookingSeat bs : b.getBookingSeats()) {
                dto.getSeats().add(new BookingSeatDto(bs.getBookingSeatId(), bs.getShowtimeSeatId(), bs.getSeatLabelSnapshot(), bs.getUnitPrice(), bs.getStatus()));
            }
        }

        if (b.getBookingFoodItems() != null) {
            for (BookingFoodItem bfi : b.getBookingFoodItems()) {
                BookingFoodItemDto bfiDto = new BookingFoodItemDto();
                bfiDto.setId(bfi.getId());
                bfiDto.setItemType(bfi.getItemType());
                bfiDto.setFoodItemId(bfi.getFoodItem() != null ? bfi.getFoodItem().getId() : null);
                bfiDto.setVariantId(bfi.getVariant() != null ? bfi.getVariant().getId() : null);
                bfiDto.setComboId(bfi.getCombo() != null ? bfi.getCombo().getId() : null);
                bfiDto.setProductName(bfi.getProductNameSnapshot());
                bfiDto.setVariantName(bfi.getVariantNameSnapshot());
                bfiDto.setQuantity(bfi.getQuantity());
                bfiDto.setUnitPrice(bfi.getUnitPriceSnapshot());
                bfiDto.setSubtotal(bfi.getSubtotal());
                dto.getFoodItems().add(bfiDto);
            }
        }

        return dto;
    }
}
