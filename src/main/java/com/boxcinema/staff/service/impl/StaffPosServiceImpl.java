package com.boxcinema.staff.service.impl;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BookingQrService;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.customer.dto.request.BookingComboRequestDto;
import com.boxcinema.customer.dto.request.BookingFoodItemRequestDto;
import com.boxcinema.customer.dto.response.PayOsCreatePaymentResponseDto;
import com.boxcinema.customer.service.FoodService;
import com.boxcinema.customer.service.PayOsService;
import com.boxcinema.customer.service.PromotionService;
import com.boxcinema.customer.service.SeatRealtimePushService;
import com.boxcinema.publicsite.dto.response.PublicFoodItemDto;
import com.boxcinema.staff.dto.request.StaffPosCreateBookingRequest;
import com.boxcinema.staff.dto.response.*;
import com.boxcinema.staff.service.StaffPosService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional
public class StaffPosServiceImpl implements StaffPosService {

    private final ShowtimeRepository showtimeRepository;
    private final CinemaRepository cinemaRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final PaymentRepository paymentRepository;
    private final TicketRepository ticketRepository;
    private final FoodItemRepository foodItemRepository;
    private final FoodVariantRepository foodVariantRepository;
    private final FoodComboRepository foodComboRepository;
    private final FoodFulfillmentRepository foodFulfillmentRepository;
    private final EmailOutboxRepository emailOutboxRepository;
    private final FoodService foodService;
    private final PromotionService promotionService;
    private final PayOsService payOsService;
    private final BookingQrService bookingQrService;
    private final SeatRealtimePushService seatRealtimePushService;
    private final JdbcTemplate jdbcTemplate;

    private final SecureRandom random = new SecureRandom();

    public StaffPosServiceImpl(
            ShowtimeRepository showtimeRepository,
            CinemaRepository cinemaRepository,
            BookingRepository bookingRepository,
            UserRepository userRepository,
            PaymentRepository paymentRepository,
            TicketRepository ticketRepository,
            FoodItemRepository foodItemRepository,
            FoodVariantRepository foodVariantRepository,
            FoodComboRepository foodComboRepository,
            FoodFulfillmentRepository foodFulfillmentRepository,
            EmailOutboxRepository emailOutboxRepository,
            FoodService foodService,
            PromotionService promotionService,
            PayOsService payOsService,
            BookingQrService bookingQrService,
            SeatRealtimePushService seatRealtimePushService,
            JdbcTemplate jdbcTemplate
    ) {
        this.showtimeRepository = showtimeRepository;
        this.cinemaRepository = cinemaRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.paymentRepository = paymentRepository;
        this.ticketRepository = ticketRepository;
        this.foodItemRepository = foodItemRepository;
        this.foodVariantRepository = foodVariantRepository;
        this.foodComboRepository = foodComboRepository;
        this.foodFulfillmentRepository = foodFulfillmentRepository;
        this.emailOutboxRepository = emailOutboxRepository;
        this.foodService = foodService;
        this.promotionService = promotionService;
        this.payOsService = payOsService;
        this.bookingQrService = bookingQrService;
        this.seatRealtimePushService = seatRealtimePushService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffPosShowtimeDto> getUpcomingShowtimes(Long staffUserId) {
        User staffUser = staffUserId != null ? userRepository.findById(staffUserId).orElse(null) : null;
        Long assignedCinemaId = resolveAssignedCinemaId(staffUser);

        LocalDateTime now = LocalDateTime.now();
        List<Showtime> all = showtimeRepository.findAllForAdmin(assignedCinemaId);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm · dd/MM");
        List<StaffPosShowtimeDto> result = new ArrayList<>();
        for (Showtime s : all) {
            if (!"ACTIVE".equalsIgnoreCase(s.getStatus())) continue;
            if (s.getRoom() == null || !"ACTIVE".equalsIgnoreCase(s.getRoom().getStatus())) continue;
            if (s.getRoom().getCinema() == null || !"ACTIVE".equalsIgnoreCase(s.getRoom().getCinema().getStatus())) continue;
            if (assignedCinemaId != null && !assignedCinemaId.equals(s.getRoom().getCinema().getCinemaId())) continue;
            if (s.getStartAt() == null || !s.getStartAt().isAfter(now)) continue;
            StaffPosShowtimeDto dto = new StaffPosShowtimeDto();
            dto.setShowtimeId(s.getShowtimeId());
            dto.setMovieId(s.getMovie() != null ? s.getMovie().getMovieId() : null);
            dto.setMovieTitle(s.getMovie() != null ? s.getMovie().getTitle() : "Unknown Movie");
            dto.setPosterUrl(s.getMovie() != null ? s.getMovie().getPosterUrl() : null);
            dto.setAgeRating(s.getMovie() != null ? s.getMovie().getAgeRating() : null);
            dto.setDurationMinutes(s.getMovie() != null ? s.getMovie().getDurationMinutes() : null);
            dto.setRoomName(s.getRoom().getRoomName());
            dto.setRoomType(s.getRoom().getRoomType());
            dto.setCinemaName(s.getRoom().getCinema().getCinemaName());
            dto.setStartAt(s.getStartAt());
            dto.setFormattedStart(s.getStartAt().format(fmt));
            dto.setBasePrice(s.getBasePrice() != null ? s.getBasePrice() : BigDecimal.valueOf(90000));
            result.add(dto);
        }
        result.sort(Comparator.comparing(StaffPosShowtimeDto::getStartAt));
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public String getAssignedCinemaName(Long staffUserId) {
        if (staffUserId == null) {
            return null;
        }
        User staffUser = userRepository.findById(staffUserId).orElse(null);
        Long assignedCinemaId = resolveAssignedCinemaId(staffUser);
        if (assignedCinemaId == null) {
            return null;
        }
        return cinemaRepository.findById(assignedCinemaId)
                .map(Cinema::getCinemaName)
                .orElse(null);
    }

    @Override
    public StaffPosBookingResultDto createCounterBooking(StaffPosCreateBookingRequest request, Long staffUserId) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        String guestName = request.getGuestName() != null ? request.getGuestName().trim() : "";
        String guestPhone = request.getGuestPhone() != null ? request.getGuestPhone().trim() : "";
        if (guestName.isEmpty() || guestPhone.isEmpty()) {
            throw new IllegalArgumentException("Customer name and phone number are required.");
        }

        boolean hasSeats = request.getSeatIds() != null && !request.getSeatIds().isEmpty();
        boolean hasFood = (request.getFoodItems() != null && !request.getFoodItems().isEmpty())
                || (request.getCombos() != null && !request.getCombos().isEmpty());
        if (!hasSeats && !hasFood) {
            throw new IllegalArgumentException("Please select at least 1 seat or 1 food/combo item.");
        }
        if (hasSeats && request.getSeatIds().size() > 8) {
            throw new IllegalArgumentException("Maximum 8 seats per transaction.");
        }

        String method = request.getPaymentMethod() != null
                ? request.getPaymentMethod().trim().toUpperCase() : "CASH";
        if (!Set.of("CASH", "PAYOS_QR").contains(method)) {
            throw new IllegalArgumentException("Unsupported payment method: " + method);
        }

        User staffUser = staffUserId != null ? userRepository.findById(staffUserId).orElse(null) : null;
        Long assignedCinemaId = resolveAssignedCinemaId(staffUser);
        Showtime showtime = resolveShowtime(request.getShowtimeId(), hasSeats, assignedCinemaId);

        Booking booking = new Booking();
        booking.setShowtime(showtime);
        booking.setSoldByStaff(staffUser);
        booking.setBookingChannel("COUNTER");
        booking.setGuestName(guestName);
        booking.setGuestPhone(guestPhone);
        booking.setGuestEmail(request.getGuestEmail() != null && !request.getGuestEmail().isBlank()
                ? request.getGuestEmail().trim() : null);
        booking.setBookedAt(LocalDateTime.now());
        booking.setBookingStatus("CASH".equals(method) ? "CONFIRMED" : "PENDING_PAYMENT");
        if ("CONFIRMED".equals(booking.getBookingStatus())) {
            booking.setConfirmedAt(LocalDateTime.now());
        } else {
            booking.setPaymentDeadline(LocalDateTime.now().plusMinutes(10));
            booking.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        }

        booking.setBookingCode(generateUniqueBookingCode());
        // Flush immediately so cascaded BookingSeat entities receive generated IDs before Ticket creation
        booking = bookingRepository.saveAndFlush(booking);

        boolean instantConfirm = "CONFIRMED".equals(booking.getBookingStatus());
        String targetSeatStatus = instantConfirm ? "BOOKED" : "HELD";
        String bookingSeatStatus = instantConfirm ? "BOOKED" : "HOLDING";

        // Seats
        BigDecimal ticketSubtotal = BigDecimal.ZERO;
        List<String> seatLabels = new ArrayList<>();
        if (hasSeats) {
            BigDecimal basePrice = showtime.getBasePrice() != null ? showtime.getBasePrice() : BigDecimal.valueOf(90000);
            for (Long seatId : request.getSeatIds()) {
                List<Map<String, Object>> seatRows = jdbcTemplate.queryForList(
                        "SELECT s.seat_id, s.seat_row, s.seat_number, st.price_surcharge " +
                        "FROM seats s JOIN seat_types st ON s.seat_type_id = st.seat_type_id WHERE s.seat_id = ?",
                        seatId
                );
                if (seatRows.isEmpty()) {
                    throw new IllegalArgumentException("Seat not found: " + seatId);
                }
                Map<String, Object> row = seatRows.get(0);
                String label = row.get("seat_row") + String.valueOf(row.get("seat_number"));
                BigDecimal surcharge = row.get("price_surcharge") != null
                        ? (BigDecimal) row.get("price_surcharge") : BigDecimal.ZERO;
                BigDecimal unitPrice = basePrice.add(surcharge);

                Long stsId = claimShowtimeSeat(showtime.getShowtimeId(), seatId, label, targetSeatStatus, staffUserId);
                booking.getBookingSeats().add(new BookingSeat(booking, stsId, unitPrice, label, bookingSeatStatus));
                seatLabels.add(label);
                ticketSubtotal = ticketSubtotal.add(unitPrice);
            }
        }
        booking.setTicketSubtotal(ticketSubtotal);

        // Food & Combos
        processFoodAndCombos(booking, request.getFoodItems(), request.getCombos(), instantConfirm);
        booking.recalculateTotals();

        // Voucher
        if (request.getVoucherCode() != null && !request.getVoucherCode().isBlank()) {
            BigDecimal discount = promotionService.validateAndCalculateDiscount(
                    request.getVoucherCode(), booking.getSubtotal(), staffUserId);
            Promotion promo = promotionService.findByVoucherCode(request.getVoucherCode());
            booking.setPromotion(promo);
            booking.setVoucherCodeSnapshot(request.getVoucherCode().trim().toUpperCase());
            booking.setDiscountAmount(discount);
            booking.recalculateTotals();
        }

        // Flush immediately so cascaded BookingSeat entities receive generated IDs before Ticket creation
        booking = bookingRepository.saveAndFlush(booking);

        if (instantConfirm) {
            recordCounterPayment(booking, method);
            issueTicketsAndFulfillment(booking);
            seatRealtimePushService.broadcastSeatUpdate(showtime.getShowtimeId(), "SEAT_BOOKED");
            return buildResultDto(booking, method, seatLabels, null);
        }

        // 2-step online transfer at counter: delegate QR generation to PayOsService
        PayOsCreatePaymentResponseDto payOsResp = payOsService.createPayment(booking.getBookingId(), staffUserId);
        seatRealtimePushService.broadcastSeatUpdate(showtime.getShowtimeId(), "SEAT_HELD");
        return buildResultDto(booking, method, seatLabels, payOsResp);
    }

    @Override
    public void cancelPendingPosBooking(Long bookingId, Long staffUserId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        if (!"COUNTER".equalsIgnoreCase(booking.getBookingChannel())) {
            throw new IllegalStateException("Only counter bookings can be cancelled from POS.");
        }
        if ("CONFIRMED".equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Cannot cancel an already confirmed booking.");
        }
        if ("CANCELLED".equalsIgnoreCase(booking.getBookingStatus())) {
            return;
        }

        booking.setBookingStatus("CANCELLED");
        booking.setCancelledAt(LocalDateTime.now());

        if (booking.getBookingSeats() != null) {
            for (BookingSeat bs : booking.getBookingSeats()) {
                bs.setStatus("CANCELLED");
                jdbcTemplate.update(
                        "UPDATE showtime_seats SET seat_status = 'AVAILABLE', held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL WHERE showtime_seat_id = ?",
                        bs.getShowtimeSeatId()
                );
            }
        }

        if (booking.getBookingFoodItems() != null) {
            for (BookingFoodItem bfi : booking.getBookingFoodItems()) {
                if ("FOOD".equalsIgnoreCase(bfi.getItemType()) && bfi.getFoodItem() != null) {
                    foodService.releaseFoodStock(bfi.getFoodItem().getId(), bfi.getQuantity());
                } else if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getCombo() != null) {
                    foodService.releaseComboStock(bfi.getCombo().getId(), bfi.getQuantity());
                }
            }
        }

        paymentRepository.findByBooking_BookingId(bookingId).ifPresent(p -> {
            if ("PENDING".equalsIgnoreCase(p.getPaymentStatus())) {
                p.setPaymentStatus("CANCELLED");
                paymentRepository.save(p);
            }
        });

        bookingRepository.save(booking);
        if (booking.getShowtime() != null) {
            seatRealtimePushService.broadcastSeatUpdate(booking.getShowtime().getShowtimeId(), "SEAT_RELEASED");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] getBookingQrPng(Long bookingId) {
        return bookingQrService.getBookingQrPngBytes(bookingId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffPosOrderSummaryDto> lookupBookings(Long staffUserId, String query) {
        if (staffUserId == null) {
            return List.of();
        }
        String q = query != null ? query.trim() : "";
        List<Booking> list = bookingRepository.searchConfirmedForStaff(staffUserId, q);
        List<StaffPosOrderSummaryDto> out = new ArrayList<>();
        int limit = Math.min(list.size(), 40);
        for (int i = 0; i < limit; i++) {
            out.add(mapToOrderSummary(list.get(i)));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public StaffPosShiftSummaryDto getShiftSummary(Long staffUserId, LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        LocalDateTime start = target.atStartOfDay();
        LocalDateTime end = target.atTime(LocalTime.MAX);

        StaffPosShiftSummaryDto summary = new StaffPosShiftSummaryDto();
        summary.setDate(target.toString());
        if (staffUserId != null) {
            userRepository.findById(staffUserId).ifPresent(u -> summary.setStaffName(u.getFullName()));
        }

        List<Booking> bookings = staffUserId != null
                ? bookingRepository.findBySoldByStaffAndDateRange(staffUserId, start, end)
                : List.of();

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO;
        BigDecimal card = BigDecimal.ZERO;
        BigDecimal payOs = BigDecimal.ZERO;
        int ticketCount = 0;

        for (Booking b : bookings) {
            StaffPosOrderSummaryDto dto = mapToOrderSummary(b);
            summary.getOrders().add(dto);
            BigDecimal amt = b.getTotalAmount() != null ? b.getTotalAmount() : BigDecimal.ZERO;
            total = total.add(amt);
            ticketCount += b.getBookingSeats() != null ? b.getBookingSeats().size() : 0;

            String pm = dto.getPaymentMethod();
            if ("CASH".equalsIgnoreCase(pm)) {
                cash = cash.add(amt);
            } else if ("CARD".equalsIgnoreCase(pm)) {
                card = card.add(amt);
            } else {
                payOs = payOs.add(amt);
            }
        }

        summary.setTotalRevenue(total);
        summary.setCashRevenue(cash);
        summary.setCardRevenue(card);
        summary.setPayOsRevenue(payOs);
        summary.setOrderCount(bookings.size());
        summary.setTicketCount(ticketCount);
        return summary;
    }

    @Override
    @Transactional(readOnly = true)
    public StaffPosBookingResultDto getBookingResult(Long bookingId) {
        Booking b = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
        String method = paymentRepository.findByBooking_BookingId(bookingId)
                .map(Payment::getPaymentMethod)
                .orElse("CASH");
        List<String> seats = b.getBookingSeats().stream()
                .map(BookingSeat::getSeatLabelSnapshot)
                .toList();
        return buildResultDto(b, method, seats, null);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Long resolveAssignedCinemaId(User user) {
        if (user == null) {
            return null;
        }
        if (!"ADMIN".equalsIgnoreCase(user.getRole()) && user.getCinemaId() != null) {
            return user.getCinemaId();
        }
        return null;
    }

    private Showtime resolveShowtime(Long showtimeId, boolean hasSeats, Long assignedCinemaId) {
        if (showtimeId != null) {
            Showtime showtime = showtimeRepository.findById(showtimeId)
                    .orElseThrow(() -> new IllegalArgumentException("Showtime not found: " + showtimeId));
            Long showtimeCinemaId = (showtime.getRoom() != null && showtime.getRoom().getCinema() != null)
                    ? showtime.getRoom().getCinema().getCinemaId() : null;
            if (assignedCinemaId != null && !assignedCinemaId.equals(showtimeCinemaId)) {
                String branchName = cinemaRepository.findById(assignedCinemaId)
                        .map(Cinema::getCinemaName)
                        .orElse("your assigned cinema");
                throw new AccessDeniedException("You are assigned to " + branchName + " and cannot book showtimes at another cinema.");
            }
            return showtime;
        }
        if (hasSeats) {
            throw new IllegalArgumentException("Showtime ID is required when selecting seats.");
        }
        // Food-only purchase at counter: attach to nearest active showtime in the staff's assigned cinema to satisfy NOT NULL FK
        return showtimeRepository.findAllForAdmin(assignedCinemaId).stream()
                .filter(s -> "ACTIVE".equalsIgnoreCase(s.getStatus()))
                .findFirst()
                .orElseGet(() -> showtimeRepository.findAllForAdmin(null).stream()
                        .filter(s -> "ACTIVE".equalsIgnoreCase(s.getStatus()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No active showtime available to attach concession order.")));
    }

    private Long claimShowtimeSeat(Long showtimeId, Long seatId, String label, String targetStatus, Long staffUserId) {
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT showtime_seat_id, seat_status, held_by_user_id, hold_expires_at FROM showtime_seats WHERE showtime_id = ? AND seat_id = ?",
                showtimeId, seatId
        );
        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            Long stsId = ((Number) row.get("showtime_seat_id")).longValue();
            String currentStatus = String.valueOf(row.get("seat_status"));
            Long heldBy = row.get("held_by_user_id") != null ? ((Number) row.get("held_by_user_id")).longValue() : null;
            Object rawExp = row.get("hold_expires_at");
            LocalDateTime expTime = null;
            if (rawExp instanceof LocalDateTime ldt) {
                expTime = ldt;
            } else if (rawExp instanceof Timestamp ts) {
                expTime = ts.toLocalDateTime();
            }
            boolean activeHold = "HELD".equalsIgnoreCase(currentStatus)
                    && expTime != null && expTime.isAfter(LocalDateTime.now());
            boolean heldByOther = activeHold && (heldBy == null || staffUserId == null || !heldBy.equals(staffUserId));
            if ("BOOKED".equalsIgnoreCase(currentStatus) || heldByOther) {
                throw new IllegalStateException("Seat " + label + " is already booked or held by another user.");
            }
            if ("BOOKED".equals(targetStatus)) {
                jdbcTemplate.update(
                        "UPDATE showtime_seats SET seat_status = 'BOOKED', held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL WHERE showtime_seat_id = ?",
                        stsId
                );
            } else {
                jdbcTemplate.update(
                        "UPDATE showtime_seats SET seat_status = 'HELD', held_by_user_id = ?, hold_session_id = ?, hold_expires_at = DATE_ADD(NOW(), INTERVAL 10 MINUTE) WHERE showtime_seat_id = ?",
                        staffUserId, "POS_" + staffUserId, stsId
                );
            }
            return stsId;
        }

        if ("BOOKED".equals(targetStatus)) {
            jdbcTemplate.update(
                    "INSERT INTO showtime_seats (showtime_id, seat_id, seat_status) VALUES (?, ?, 'BOOKED')",
                    showtimeId, seatId
            );
        } else {
            jdbcTemplate.update(
                    "INSERT INTO showtime_seats (showtime_id, seat_id, seat_status, held_by_user_id, hold_session_id, hold_expires_at) VALUES (?, ?, 'HELD', ?, ?, DATE_ADD(NOW(), INTERVAL 10 MINUTE))",
                    showtimeId, seatId, staffUserId, "POS_" + staffUserId
            );
        }
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private void processFoodAndCombos(
            Booking booking,
            List<BookingFoodItemRequestDto> foodItems,
            List<BookingComboRequestDto> combos,
            boolean commitImmediately
    ) {
        if (foodItems != null) {
            for (BookingFoodItemRequestDto fiReq : foodItems) {
                if (fiReq.getFoodItemId() == null || fiReq.getQuantity() <= 0) continue;
                FoodItem item = foodItemRepository.findById(fiReq.getFoodItemId()).orElseThrow();
                FoodVariant variant = fiReq.getVariantId() != null
                        ? foodVariantRepository.findById(fiReq.getVariantId()).orElse(null) : null;
                BigDecimal unitPrice = variant != null ? variant.getPrice() : item.getPrice();
                BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(fiReq.getQuantity()));

                foodService.checkAndReserveFoodStock(item.getId(), fiReq.getQuantity());
                if (commitImmediately) {
                    foodService.commitFoodStock(item.getId(), fiReq.getQuantity());
                }

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
                booking.getBookingFoodItems().add(bfi);
            }
        }

        if (combos != null) {
            for (BookingComboRequestDto cbReq : combos) {
                if (cbReq.getComboId() == null || cbReq.getQuantity() <= 0) continue;
                FoodCombo combo = foodComboRepository.findById(cbReq.getComboId()).orElseThrow();
                BigDecimal unitPrice = combo.getPrice();
                BigDecimal subtotal = unitPrice.multiply(BigDecimal.valueOf(cbReq.getQuantity()));

                foodService.checkAndReserveComboStock(combo.getId(), cbReq.getQuantity());
                if (commitImmediately) {
                    foodService.commitComboStock(combo.getId(), cbReq.getQuantity());
                }

                BookingFoodItem bfi = new BookingFoodItem();
                bfi.setBooking(booking);
                bfi.setItemType("COMBO");
                bfi.setCombo(combo);
                bfi.setProductNameSnapshot(combo.getName());
                bfi.setQuantity(cbReq.getQuantity());
                bfi.setUnitPriceSnapshot(unitPrice);
                bfi.setSubtotal(subtotal);
                booking.getBookingFoodItems().add(bfi);
            }
        }
    }

    private void recordCounterPayment(Booking booking, String method) {
        Payment p = new Payment();
        p.setBooking(booking);
        p.setPaymentProvider("COUNTER");
        p.setPaymentMethod(method);
        p.setAmount(booking.getTotalAmount());
        p.setCurrency("VND");
        p.setPaymentStatus("PAID");
        p.setPaidAt(LocalDateTime.now());
        p.setIdempotencyKey("COUNTER-" + booking.getBookingCode());
        p.setDescription("Counter POS " + method);
        paymentRepository.save(p);
    }

    private void issueTicketsAndFulfillment(Booking booking) {
        if (booking.getBookingSeats() != null && !booking.getBookingSeats().isEmpty()) {
            List<Ticket> tickets = new ArrayList<>();
            for (BookingSeat bs : booking.getBookingSeats()) {
                Ticket t = new Ticket();
                t.setBooking(booking);
                t.setBookingSeatId(bs.getBookingSeatId());
                t.setShowtimeId(booking.getShowtime().getShowtimeId());
                t.setTicketCode("TCK-" + booking.getBookingCode().replace("BOX-", "") + "-" + bs.getSeatLabelSnapshot());
                t.setQrTokenHash(CryptoUtils.sha256(t.getTicketCode() + "_" + System.nanoTime()));
                t.setPrice(bs.getUnitPrice());
                t.setTicketStatus("VALID");
                t.setIssuedAt(LocalDateTime.now());
                tickets.add(t);
            }
            ticketRepository.saveAll(tickets);
        }

        bookingQrService.getOrCreateBookingQr(booking.getBookingId());

        if (booking.getBookingFoodItems() != null && !booking.getBookingFoodItems().isEmpty()) {
            if (foodFulfillmentRepository.findByBooking_BookingId(booking.getBookingId()).isEmpty()) {
                FoodFulfillment ff = new FoodFulfillment(booking);
                ff.setStatus("QUEUED");
                String cinemaName = (booking.getShowtime() != null && booking.getShowtime().getRoom() != null
                        && booking.getShowtime().getRoom().getCinema() != null)
                        ? booking.getShowtime().getRoom().getCinema().getCinemaName() + " - Concession Counter"
                        : "Concession Counter";
                ff.setPickupLocation(cinemaName);
                foodFulfillmentRepository.save(ff);
            }
        }

        if (booking.getGuestEmail() != null && !booking.getGuestEmail().isBlank()) {
            emailOutboxRepository.save(new EmailOutbox(booking, booking.getGuestEmail(), "BOOKING_CONFIRMATION"));
        }
    }

    private String generateUniqueBookingCode() {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String code;
        do {
            code = "BOX-" + dateStr + "-" + (1000 + random.nextInt(9000));
        } while (bookingRepository.findByBookingCode(code).isPresent());
        return code;
    }

    private StaffPosBookingResultDto buildResultDto(
            Booking b,
            String method,
            List<String> seatLabels,
            PayOsCreatePaymentResponseDto payOsResp
    ) {
        StaffPosBookingResultDto dto = new StaffPosBookingResultDto();
        dto.setBookingId(b.getBookingId());
        dto.setBookingCode(b.getBookingCode());
        dto.setBookingStatus(b.getBookingStatus());
        dto.setPaymentMethod(method);
        dto.setTotalAmount(b.getTotalAmount());
        dto.setGuestName(b.getGuestName());
        dto.setGuestPhone(b.getGuestPhone());
        dto.setGuestEmail(b.getGuestEmail());
        dto.setSeatLabels(seatLabels);
        dto.setEmailSent(b.getGuestEmail() != null && !b.getGuestEmail().isBlank());

        List<PublicFoodItemDto> foodDtos = new ArrayList<>();
        if (b.getBookingFoodItems() != null) {
            for (BookingFoodItem bfi : b.getBookingFoodItems()) {
                PublicFoodItemDto fDto = new PublicFoodItemDto();
                fDto.setProductName(bfi.getProductNameSnapshot());
                fDto.setVariantName(bfi.getVariantNameSnapshot());
                fDto.setQuantity(bfi.getQuantity());
                fDto.setStatus("Ordered");
                foodDtos.add(fDto);
            }
        }
        dto.setFoodItems(foodDtos);

        if (b.getShowtime() != null) {
            dto.setMovieTitle(b.getBookingSeats() == null || b.getBookingSeats().isEmpty()
                    ? "Concessions Only"
                    : (b.getShowtime().getMovie() != null ? b.getShowtime().getMovie().getTitle() : ""));
            if (b.getShowtime().getStartAt() != null) {
                dto.setShowtimeStart(b.getShowtime().getStartAt().format(DateTimeFormatter.ofPattern("HH:mm · dd/MM/yyyy")));
            }
            if (b.getShowtime().getRoom() != null) {
                dto.setRoomName(b.getShowtime().getRoom().getRoomName());
                if (b.getShowtime().getRoom().getCinema() != null) {
                    String cName = b.getShowtime().getRoom().getCinema().getCinemaName();
                    dto.setCinemaName(cName);
                    dto.setPickupLocation(cName + " - Concession Counter");
                }
            }
        }
        if ("CONFIRMED".equalsIgnoreCase(b.getBookingStatus())) {
            try {
                dto.setQrToken(bookingQrService.getBookingTicketUrl(b.getBookingId()));
            } catch (Exception ignored) {
            }
        }
        if (payOsResp != null) {
            dto.setPaymentId(payOsResp.getPaymentId());
            dto.setOrderCode(payOsResp.getOrderCode());
            dto.setPayOsQrPayload(payOsResp.getQrCode());
            dto.setCheckoutUrl(payOsResp.getCheckoutUrl());
            dto.setAccountNumber(payOsResp.getAccountNumber());
            dto.setAccountName(payOsResp.getAccountName());
            dto.setBankName(payOsResp.getBankName());
        }
        return dto;
    }

    private StaffPosOrderSummaryDto mapToOrderSummary(Booking b) {
        StaffPosOrderSummaryDto dto = new StaffPosOrderSummaryDto();
        dto.setBookingId(b.getBookingId());
        dto.setBookingCode(b.getBookingCode());
        dto.setGuestName(b.getGuestName());
        dto.setGuestPhone(b.getGuestPhone());
        dto.setGuestEmail(b.getGuestEmail());
        dto.setBookingStatus(b.getBookingStatus());
        dto.setBookingChannel(b.getBookingChannel());
        dto.setTotalAmount(b.getTotalAmount());
        dto.setBookedAt(b.getBookedAt());

        boolean hasSeats = b.getBookingSeats() != null && !b.getBookingSeats().isEmpty();
        if (hasSeats) {
            dto.setSeatLabels(b.getBookingSeats().stream().map(BookingSeat::getSeatLabelSnapshot).toList());
            if (b.getShowtime() != null && b.getShowtime().getMovie() != null) {
                dto.setMovieTitle(b.getShowtime().getMovie().getTitle());
            }
            if (b.getShowtime() != null && b.getShowtime().getStartAt() != null) {
                dto.setShowtimeStart(b.getShowtime().getStartAt().format(DateTimeFormatter.ofPattern("HH:mm · dd/MM")));
            }
        } else {
            dto.setMovieTitle("Concessions Only");
            dto.setShowtimeStart("At Counter");
        }

        String method = paymentRepository.findByBooking_BookingId(b.getBookingId())
                .map(Payment::getPaymentMethod)
                .orElse("CASH");
        dto.setPaymentMethod(method);
        return dto;
    }
}
