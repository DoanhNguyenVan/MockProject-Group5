package com.boxcinema.service.impl;

import com.boxcinema.dto.*;
import com.boxcinema.entity.*;
import com.boxcinema.repository.*;
import com.boxcinema.service.BookingService;
import com.boxcinema.service.FoodService;
import com.boxcinema.service.PromotionService;
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
    private final JdbcTemplate jdbcTemplate;
    private final PromotionService promotionService;

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
            JdbcTemplate jdbcTemplate,
            PromotionService promotionService
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
        this.jdbcTemplate = jdbcTemplate;
        this.promotionService = promotionService;
    }

    @Override
    public BookingResponseDto createBooking(BookingCreateRequestDto request, Long currentUserId) {
        if (request.getShowtimeId() == null) {
            throw new IllegalArgumentException("Showtime ID is required.");
        }
        Showtime showtime = showtimeRepository.findById(request.getShowtimeId())
                .orElseThrow(() -> new IllegalArgumentException("Showtime not found: " + request.getShowtimeId()));

        if (showtime.getStartAt() != null && !showtime.getStartAt().isAfter(LocalDateTime.now())) {
            throw new IllegalStateException("This screening has already started or ticket sales have closed.");
        }

        User user = currentUserId != null ? userRepository.findById(currentUserId).orElse(null) : null;

        Booking booking = new Booking();
        booking.setShowtime(showtime);
        booking.setUser(user);
        booking.setBookingChannel("ONLINE");
        String effectiveName = (user != null && user.getFullName() != null && !user.getFullName().isBlank())
                ? user.getFullName()
                : (request.getGuestName() != null && !request.getGuestName().isBlank() ? request.getGuestName() : "Customer");
        String effectiveEmail = (user != null && user.getEmail() != null && !user.getEmail().isBlank())
                ? user.getEmail()
                : (request.getGuestEmail() != null && !request.getGuestEmail().isBlank() ? request.getGuestEmail() : "customer@boxcinema.vn");
        String effectivePhone = (user != null && user.getPhone() != null && !user.getPhone().isBlank())
                ? user.getPhone()
                : (request.getGuestPhone() != null && !request.getGuestPhone().isBlank() ? request.getGuestPhone() : "0900000000");

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

        // Process Seats
        BigDecimal ticketSubtotal = BigDecimal.ZERO;
        List<BookingSeat> bookingSeats = new ArrayList<>();
        if (request.getSeatIds() != null && !request.getSeatIds().isEmpty()) {
            BigDecimal basePrice = showtime.getBasePrice() != null ? showtime.getBasePrice() : BigDecimal.valueOf(90000);

            for (Long seatId : request.getSeatIds()) {
                // Query seat row, number, surcharge
                String sql = "SELECT s.seat_id, s.seat_row, s.seat_number, st.price_surcharge " +
                             "FROM seats s JOIN seat_types st ON s.seat_type_id = st.seat_type_id WHERE s.seat_id = ?";
                List<Map<String, Object>> seatRows = jdbcTemplate.queryForList(sql, seatId);
                if (!seatRows.isEmpty()) {
                    Map<String, Object> row = seatRows.get(0);
                    String label = row.get("seat_row") + String.valueOf(row.get("seat_number"));
                    BigDecimal surcharge = row.get("price_surcharge") != null ? (BigDecimal) row.get("price_surcharge") : BigDecimal.ZERO;
                    BigDecimal unitPrice = basePrice.add(surcharge);

                    // Find or create showtime_seat record
                    Long showtimeSeatId = ensureShowtimeSeat(showtime.getShowtimeId(), seatId, currentUserId, request.getHoldSessionId());

                    BookingSeat bs = new BookingSeat(booking, showtimeSeatId, unitPrice, label, "HOLDING");
                    bookingSeats.add(bs);
                    ticketSubtotal = ticketSubtotal.add(unitPrice);
                }
            }
            booking.getBookingSeats().clear();
            booking.getBookingSeats().addAll(bookingSeats);
        }
        booking.setTicketSubtotal(ticketSubtotal);

        // Process Food & Combos
        processFoodAndCombos(booking, request.getFoodItems(), request.getCombos());

        booking.recalculateTotals();

        // Apply voucher discount if provided
        if (request.getVoucherCode() != null && !request.getVoucherCode().isBlank()) {
            try {
                BigDecimal subtotalForVoucher = booking.getSubtotal();
                BigDecimal discount = promotionService.validateAndCalculateDiscount(
                        request.getVoucherCode(), subtotalForVoucher, currentUserId);
                Promotion promo = promotionService.findByVoucherCode(request.getVoucherCode());
                booking.setPromotion(promo);
                booking.setVoucherCodeSnapshot(request.getVoucherCode().toUpperCase());
                booking.setDiscountAmount(discount);
                booking.recalculateTotals();
            } catch (IllegalArgumentException e) {
                // Voucher invalid — ignore silently (frontend validated first, this is a safety net)
            }
        }

        booking = bookingRepository.save(booking);

        return mapToDto(booking);
    }

    @Override
    public BookingResponseDto updateFoodItems(Long bookingId, UpdateFoodItemsRequestDto request, Long currentUserId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        if ("CONFIRMED".equalsIgnoreCase(booking.getBookingStatus()) || "PAID".equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Cannot modify food items for an already paid booking.");
        }

        // Release prior reserved food
        releaseBookingFood(booking);

        // Clear existing booking food items
        bookingFoodItemRepository.deleteByBooking_BookingId(bookingId);
        booking.getBookingFoodItems().clear();

        // Process new food items & combos
        processFoodAndCombos(booking, request.getFoodItems(), request.getCombos());

        booking.recalculateTotals();
        booking = bookingRepository.save(booking);

        return mapToDto(booking);
    }

    @Override
    public BookingResponseDto confirmBooking(Long bookingId, Long currentUserId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        if ("CONFIRMED".equalsIgnoreCase(booking.getBookingStatus())) {
            return mapToDto(booking);
        }

        booking.setBookingStatus("PENDING_PAYMENT");
        booking.setPaymentDeadline(LocalDateTime.now().plusMinutes(10));
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

    private void processFoodAndCombos(Booking booking, List<BookingFoodItemRequestDto> foodItems, List<BookingComboRequestDto> combos) {
        List<BookingFoodItem> bfiList = new ArrayList<>();

        if (foodItems != null) {
            for (BookingFoodItemRequestDto fiReq : foodItems) {
                if (fiReq.getFoodItemId() == null || fiReq.getQuantity() <= 0) continue;
                FoodItem item = foodItemRepository.findById(fiReq.getFoodItemId()).orElseThrow();
                FoodVariant variant = fiReq.getVariantId() != null ? foodVariantRepository.findById(fiReq.getVariantId()).orElse(null) : null;

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
            for (BookingComboRequestDto cbReq : combos) {
                if (cbReq.getComboId() == null || cbReq.getQuantity() <= 0) continue;
                FoodCombo combo = foodComboRepository.findById(cbReq.getComboId()).orElseThrow();

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
        for (BookingFoodItem bfi : booking.getBookingFoodItems()) {
            if ("FOOD".equalsIgnoreCase(bfi.getItemType()) && bfi.getFoodItem() != null) {
                foodService.releaseFoodStock(bfi.getFoodItem().getId(), bfi.getQuantity());
            } else if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getCombo() != null) {
                foodService.releaseComboStock(bfi.getCombo().getId(), bfi.getQuantity());
            }
        }
    }

    private Long ensureShowtimeSeat(Long showtimeId, Long seatId, Long currentUserId, String holdSessionId) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT showtime_seat_id FROM showtime_seats WHERE showtime_id = ? AND seat_id = ?",
                showtimeId, seatId
        );
        if (!existing.isEmpty()) {
            Long stsId = ((Number) existing.get(0).get("showtime_seat_id")).longValue();
            jdbcTemplate.update(
                    "UPDATE showtime_seats SET seat_status = 'HELD', held_by_user_id = ?, hold_session_id = ?, hold_expires_at = DATE_ADD(NOW(), INTERVAL 10 MINUTE) WHERE showtime_seat_id = ?",
                    currentUserId, holdSessionId, stsId
            );
            return stsId;
        }

        jdbcTemplate.update(
                "INSERT INTO showtime_seats (showtime_id, seat_id, seat_status, held_by_user_id, hold_session_id, hold_expires_at) VALUES (?, ?, 'HELD', ?, ?, DATE_ADD(NOW(), INTERVAL 10 MINUTE))",
                showtimeId, seatId, currentUserId, holdSessionId
        );
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
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
