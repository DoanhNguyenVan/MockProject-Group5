package com.boxcinema.admin.service.impl;

import com.boxcinema.admin.service.AdminAccountService;
import com.boxcinema.admin.service.AdminWorkspaceService;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.PasswordHashService;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.customer.service.FoodService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AdminWorkspaceServiceImpl implements AdminWorkspaceService {

    private static final Set<String> MOVIE_STATUSES = Set.of("NOW_SHOWING", "COMING_SOON", "ENDED", "INACTIVE");
    private static final Set<String> FACILITY_STATUSES = Set.of("ACTIVE", "INACTIVE", "STOP_BOOKING");
    private static final Set<String> SHOWTIME_STATUSES = Set.of("ACTIVE", "CANCELLED", "INACTIVE");
    private static final Set<String> PROMO_STATUSES = Set.of("ACTIVE", "INACTIVE");
    private static final Set<String> DISCOUNT_TYPES = Set.of("PERCENT", "FIXED");
    private static final Set<String> STAFF_ROLES = Set.of("STAFF", "MANAGER", "ADMIN");
    private static final Set<String> STAFF_STATUSES = Set.of("ACTIVE", "LOCKED", "INACTIVE");

    private final AdminWorkspaceJdbcRepository adminRepository;
    private final PasswordHashService passwordHashService;
    private final AdminAccountService adminAccountService;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final TicketRepository ticketRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final RefundRequestItemRepository refundRequestItemRepository;
    private final RefundApprovalHistoryRepository refundApprovalHistoryRepository;
    private final RefundAuditLogRepository refundAuditLogRepository;
    private final EmailOutboxRepository emailOutboxRepository;
    private final BookingFoodItemComponentRepository componentRepository;
    private final FoodService foodService;
    private final JdbcTemplate jdbcTemplate;

    public AdminWorkspaceServiceImpl(AdminWorkspaceJdbcRepository adminRepository,
                                     PasswordHashService passwordHashService) {
        this(adminRepository, passwordHashService, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Autowired
    public AdminWorkspaceServiceImpl(
            AdminWorkspaceJdbcRepository adminRepository,
            PasswordHashService passwordHashService,
            @Autowired(required = false) AdminAccountService adminAccountService,
            @Autowired(required = false) BookingRepository bookingRepository,
            @Autowired(required = false) PaymentRepository paymentRepository,
            @Autowired(required = false) TicketRepository ticketRepository,
            @Autowired(required = false) RefundRequestRepository refundRequestRepository,
            @Autowired(required = false) RefundRequestItemRepository refundRequestItemRepository,
            @Autowired(required = false) RefundApprovalHistoryRepository refundApprovalHistoryRepository,
            @Autowired(required = false) RefundAuditLogRepository refundAuditLogRepository,
            @Autowired(required = false) EmailOutboxRepository emailOutboxRepository,
            @Autowired(required = false) BookingFoodItemComponentRepository componentRepository,
            @Autowired(required = false) FoodService foodService,
            @Autowired(required = false) JdbcTemplate jdbcTemplate) {
        this.adminRepository = adminRepository;
        this.passwordHashService = passwordHashService;
        this.adminAccountService = adminAccountService;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.ticketRepository = ticketRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.refundRequestItemRepository = refundRequestItemRepository;
        this.refundApprovalHistoryRepository = refundApprovalHistoryRepository;
        this.refundAuditLogRepository = refundAuditLogRepository;
        this.emailOutboxRepository = emailOutboxRepository;
        this.componentRepository = componentRepository;
        this.foodService = foodService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getAdminWorkspaceData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        data.put("movies", adminRepository.findAllMoviesWithGenreIds());
        data.put("genres", adminRepository.findAllGenres());
        data.put("cinemas", adminRepository.findAllCinemas());
        data.put("rooms", adminRepository.findAllRooms());
        data.put("showtimes", adminRepository.findRecentShowtimes());
        data.put("pricing", adminRepository.findAllPricing());
        data.put("promotions", adminRepository.findAllPromotions());
        data.put("combos", adminRepository.findAllCombos());
        data.put("staff", adminRepository.findAllStaff());
        data.put("layouts", adminRepository.findSeatLayoutsByRoom());
        data.put("sales", adminRepository.findRecentSales());
        data.put("bookings", adminRepository.findRecentRichBookings());
        return data;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> checkDeactivationImpact(String section, Long id) {
        List<Map<String, Object>> bookings;
        int upcomingShowtimes;
        if ("cinemas".equalsIgnoreCase(section)) {
            bookings = adminRepository.findImpactedBookings("r.cinema_id", id);
            upcomingShowtimes = adminRepository.countUpcomingActiveShowtimesByCinema(id);
        } else if ("rooms".equalsIgnoreCase(section)) {
            bookings = adminRepository.findImpactedBookings("r.room_id", id);
            upcomingShowtimes = adminRepository.countUpcomingActiveShowtimesByRoom(id);
        } else {
            throw new IllegalArgumentException("Invalid section for impact check");
        }

        int totalTickets = bookings.stream().mapToInt(b -> ((Number) b.get("ticketCount")).intValue()).sum();
        BigDecimal totalAmount = bookings.stream()
                .map(b -> b.get("totalAmount") instanceof BigDecimal bd ? bd : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("hasImpact", !bookings.isEmpty());
        response.put("totalBookings", bookings.size());
        response.put("totalTickets", totalTickets);
        response.put("totalAmount", totalAmount);
        response.put("upcomingShowtimes", upcomingShowtimes);
        response.put("bookings", bookings);
        return response;
    }

    @Override
    @Transactional
    public Map<String, Object> resolveDeactivation(String section, Long id, String strategy) {
        Map<String, Object> result = new LinkedHashMap<>();
        if ("REFUND_AND_CANCEL".equalsIgnoreCase(strategy)) {
            List<Long> showtimeIds;
            if ("cinemas".equalsIgnoreCase(section)) {
                showtimeIds = adminRepository.findFutureShowtimeIdsByCinema(id);
                int createdRefunds = cancelShowtimesAndCreateRefundRequests(showtimeIds);
                adminRepository.deactivateCinemaAndRooms(id);
                result.put("success", true);
                result.put("createdRefundRequests", createdRefunds);
                result.put("message",
                        "Cinema deactivated. Future showtimes cancelled, pending holds released, and "
                                + createdRefunds + " real refund request(s) opened for affected paid bookings.");
            } else if ("rooms".equalsIgnoreCase(section)) {
                showtimeIds = adminRepository.findFutureShowtimeIdsByRoom(id);
                int createdRefunds = cancelShowtimesAndCreateRefundRequests(showtimeIds);
                adminRepository.deactivateRoom(id);
                result.put("success", true);
                result.put("createdRefundRequests", createdRefunds);
                result.put("message",
                        "Auditorium deactivated. Future showtimes cancelled, pending holds released, and "
                                + createdRefunds + " real refund request(s) opened for affected paid bookings.");
            } else {
                throw new IllegalArgumentException("Invalid section for deactivation");
            }
        } else if ("GRACEFUL_DECOMMISSION".equalsIgnoreCase(strategy)) {
            if ("cinemas".equalsIgnoreCase(section)) {
                adminRepository.gracefulDecommissionCinema(id);
                result.put("success", true);
                result.put("message",
                        "Cinema switched to Graceful Decommission mode (STOP_BOOKING). New bookings stopped; existing showtimes proceed normally.");
            } else if ("rooms".equalsIgnoreCase(section)) {
                adminRepository.gracefulDecommissionRoom(id);
                result.put("success", true);
                result.put("message",
                        "Auditorium switched to Stop Booking mode (STOP_BOOKING). Existing showtimes proceed normally.");
            } else {
                throw new IllegalArgumentException("Invalid section for deactivation");
            }
        } else {
            throw new IllegalArgumentException("Unknown deactivation strategy");
        }
        return result;
    }

    private int cancelShowtimesAndCreateRefundRequests(List<Long> showtimeIds) {
        if (showtimeIds == null || showtimeIds.isEmpty()) {
            return 0;
        }
        List<Long> bookingIds = adminRepository.findBookingsOnShowtimes(showtimeIds);
        int createdRefundCount = 0;
        Long adminUserId = SecurityContextUtils.resolveCurrentUserId();

        for (Long bookingId : bookingIds) {
            if (bookingRepository == null) {
                continue;
            }
            Booking b = bookingRepository.findByIdForUpdate(bookingId).orElse(null);
            if (b == null || b.getBookingStatus() == null) {
                continue;
            }
            String st = b.getBookingStatus().trim().toUpperCase(Locale.ROOT);
            if ("DRAFT".equals(st) || "HOLDING".equals(st) || "PENDING_PAYMENT".equals(st)) {
                cancelUnpaidBooking(b);
            } else if ("CONFIRMED".equals(st) || "PAID".equals(st)) {
                if (openShowtimeCancelledRefundRequest(b, adminUserId)) {
                    createdRefundCount++;
                }
            }
        }
        adminRepository.cancelShowtimesAndReleaseEmptySeats(showtimeIds);
        return createdRefundCount;
    }

    private void cancelUnpaidBooking(Booking b) {
        if (jdbcTemplate != null) {
            jdbcTemplate.update(
                    "UPDATE booking_seats SET status = 'CANCELLED', active_seat_key = NULL WHERE booking_id = ?",
                    b.getBookingId());
        }
        if (foodService != null && b.getBookingFoodItems() != null) {
            for (BookingFoodItem bfi : b.getBookingFoodItems()) {
                if (!"RELEASED".equalsIgnoreCase(bfi.getDeliveryStatus())) {
                    if ("FOOD".equalsIgnoreCase(bfi.getItemType()) && bfi.getFoodItem() != null) {
                        foodService.releaseFoodStock(bfi.getFoodItem().getId(), bfi.getQuantity());
                    } else if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getCombo() != null) {
                        foodService.releaseComboStock(bfi.getCombo().getId(), bfi.getQuantity());
                    }
                    bfi.setDeliveryStatus("RELEASED");
                }
            }
        }
        if (paymentRepository != null) {
            for (Payment p : paymentRepository.findByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(b.getBookingId(), "PENDING")) {
                p.setPaymentStatus("CANCELLED");
                paymentRepository.save(p);
            }
        }
        b.setBookingStatus("CANCELLED");
        b.setCancelledAt(LocalDateTime.now());
        bookingRepository.save(b);
    }

    private boolean openShowtimeCancelledRefundRequest(Booking b, Long adminUserId) {
        if (refundRequestRepository == null || jdbcTemplate == null) {
            return false;
        }
        Integer existing = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_requests WHERE booking_id = ? AND request_status NOT IN ('REJECTED', 'CANCELLED')",
                Integer.class, b.getBookingId());
        if (existing != null && existing > 0) {
            return false;
        }

        BigDecimal cashTotal = BigDecimal.ZERO;
        if (paymentRepository != null) {
            cashTotal = paymentRepository
                    .findFirstByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(b.getBookingId(), "PAID")
                    .map(Payment::getAmount)
                    .orElse(b.getTotalAmount() != null ? b.getTotalAmount() : BigDecimal.ZERO);
        } else if (b.getTotalAmount() != null) {
            cashTotal = b.getTotalAmount();
        }

        Cinema cinema = b.getCinema() != null ? b.getCinema()
                : (b.getShowtime() != null && b.getShowtime().getRoom() != null ? b.getShowtime().getRoom().getCinema() : null);
        if (cinema == null) {
            return false;
        }

        RefundRequest req = new RefundRequest();
        req.setRequestCode("RFD-SHW-" + b.getBookingId() + "-" + (System.currentTimeMillis() % 10000));
        req.setBooking(b);
        req.setCinema(cinema);
        req.setCustomerUser(b.getUser());
        req.setCustomerName(b.getGuestName());
        req.setCustomerPhone(b.getGuestPhone());
        req.setPurchaseEmail(b.getGuestEmail() != null && !b.getGuestEmail().isBlank() ? b.getGuestEmail() : "customer@boxcinema.vn");
        req.setSubmittedOrderCode(b.getBookingCode());
        req.setReasonCategory("SHOWTIME_CANCELLED");
        req.setDescription("Showtime cancelled during admin cinema/room deactivation. Customer bank details required to complete payout.");
        req.setRequestedCashTotal(cashTotal);
        req.setApprovedCashTotal(BigDecimal.ZERO);
        req.setApprovedVoucherTotal(BigDecimal.ZERO);
        req.setRequestStatus("MORE_INFO_REQUIRED");
        req.setPayoutStatus("NOT_REQUIRED");
        req.setVoucherStatus("NOT_REQUIRED");
        req = refundRequestRepository.save(req);

        if (ticketRepository != null && refundRequestItemRepository != null) {
            for (Ticket t : ticketRepository.findByBooking_BookingId(b.getBookingId())) {
                if ("REFUNDED".equalsIgnoreCase(t.getTicketStatus())) continue;
                RefundRequestItem item = new RefundRequestItem();
                item.setRefundRequest(req);
                item.setItemKind("TICKET");
                item.setTicketId(t.getTicketId());
                item.setBookingSeatId(t.getBookingSeatId());
                item.setItemLabelSnapshot(t.getTicketCode());
                item.setPurchasedQuantity(1);
                item.setRequestedQuantity(1);
                item.setGrossUnitPrice(t.getGrossPrice() != null ? t.getGrossPrice() : t.getPrice());
                item.setDiscountAllocatedAmount(t.getDiscountAllocated() != null ? t.getDiscountAllocated() : BigDecimal.ZERO);
                item.setNetPaidAmount(t.getNetPaidAmount() != null ? t.getNetPaidAmount() : t.getPrice());
                item.setItemStatus("PENDING");
                item.setActiveLockKey("TICKET:" + t.getTicketId());
                refundRequestItemRepository.save(item);
            }
        }

        if (refundApprovalHistoryRepository != null) {
            RefundApprovalHistory h = new RefundApprovalHistory();
            h.setRefundRequest(req);
            h.setActorRole("ADMIN");
            h.setAction("SHOWTIME_CANCELLED_OPENED");
            h.setPreviousStatus(null);
            h.setNewStatus("MORE_INFO_REQUIRED");
            h.setNote("Opened automatically on facility deactivation; awaiting customer bank confirmation.");
            refundApprovalHistoryRepository.save(h);
        }

        if (refundAuditLogRepository != null) {
            RefundAuditLog logRow = new RefundAuditLog();
            logRow.setRefundRequestId(req.getId());
            logRow.setEntityType("REFUND_REQUEST");
            logRow.setEntityId(req.getId());
            logRow.setEventType("SHOWTIME_DEACTIVATION_REFUND_OPENED");
            logRow.setActorUserId(adminUserId);
            logRow.setActorRole("ADMIN");
            logRow.setCinemaId(cinema.getCinemaId());
            refundAuditLogRepository.save(logRow);
        }

        if (emailOutboxRepository != null && b.getGuestEmail() != null && !b.getGuestEmail().isBlank()) {
            EmailOutbox mail = new EmailOutbox(b, b.getGuestEmail(), "SHOWTIME_CANCELLED_REFUND_NOTICE");
            mail.setRefundRequestId(req.getId());
            mail.setDedupKey("SHOW_CANCEL:" + b.getBookingId() + ":" + req.getId());
            emailOutboxRepository.save(mail);
        }
        return true;
    }

    @Override
    @Transactional
    public Map<String, Object> saveRecord(String section, Map<String, Object> body) {
        String rawId = body.get("id") != null ? String.valueOf(body.get("id")).trim() : null;
        boolean isNumericId = rawId != null && rawId.matches("^\\d+$");
        Long id = isNumericId ? Long.valueOf(rawId) : null;

        String savedId = switch (section) {
            case "movies" -> {
                String title = requireNonBlank(body.get("title"), "Movie title");
                int durationMinutes = parsePositiveInt(body.get("durationMinutes"), "Duration minutes");
                String ageRating = requireNonBlank(body.get("ageRating"), "Age rating");
                String language = requireNonBlank(body.get("language"), "Language");
                String releaseDate = requireNonBlank(body.get("releaseDate"), "Release date");
                String endDate = body.get("endDate") != null && !String.valueOf(body.get("endDate")).isBlank()
                        ? String.valueOf(body.get("endDate")).trim()
                        : null;
                String status = requireAllowed(body.get("status"), MOVIE_STATUSES, "Movie status");
                String synopsis = body.get("synopsis") != null ? String.valueOf(body.get("synopsis")) : null;
                String posterUrl = body.get("posterUrl") != null ? String.valueOf(body.get("posterUrl")) : null;
                String trailerUrl = body.get("trailerUrl") != null ? String.valueOf(body.get("trailerUrl")) : null;
                List<?> genreIds = body.get("genreIds") instanceof List<?> list ? list : null;
                yield String.valueOf(adminRepository.upsertMovie(id, title, durationMinutes, ageRating, language,
                        releaseDate, endDate, status, synopsis, posterUrl, trailerUrl, genreIds));
            }
            case "genres" -> {
                String genreName = requireNonBlank(body.get("genreName"), "Genre name");
                String description = body.get("description") != null ? String.valueOf(body.get("description")) : "";
                yield String.valueOf(adminRepository.upsertGenre(id, genreName, description));
            }
            case "cinemas" -> {
                String cinemaName = requireNonBlank(body.get("cinemaName"), "Cinema name");
                String city = requireNonBlank(body.get("city"), "City");
                String address = requireNonBlank(body.get("address"), "Address");
                String phone = requireNonBlank(body.get("phone"), "Phone");
                String email = requireNonBlank(body.get("email"), "Email");
                String status = requireAllowed(body.get("status"), FACILITY_STATUSES, "Cinema status");
                yield String.valueOf(adminRepository.upsertCinema(id, cinemaName, city, address, phone, email, status));
            }
            case "rooms" -> {
                String roomName = requireNonBlank(body.get("roomName"), "Room name");
                Long cinemaId = Long.valueOf(requireNonBlank(body.get("cinemaId"), "Cinema ID"));
                String roomType = requireNonBlank(body.get("roomType"), "Room type");
                String status = requireAllowed(body.get("status"), FACILITY_STATUSES, "Room status");
                yield String.valueOf(adminRepository.upsertRoom(id, roomName, cinemaId, roomType, status));
            }
            case "showtimes" -> {
                Long movieId = Long.valueOf(requireNonBlank(body.get("movieId"), "Movie ID"));
                Long roomId = Long.valueOf(requireNonBlank(body.get("roomId"), "Room ID"));
                String startAtStr = normalizeDateTime(requireNonBlank(body.get("startAt"), "Start time"));
                String endAtStr = normalizeDateTime(requireNonBlank(body.get("endAt"), "End time"));
                BigDecimal basePrice = parseNonNegativeMoney(body.get("basePrice"), "Base price");
                String status = requireAllowed(body.get("status"), SHOWTIME_STATUSES, "Showtime status");

                validateShowtimeSchedule(id, movieId, roomId, startAtStr, endAtStr, status);
                yield String.valueOf(adminRepository.upsertShowtime(id, movieId, roomId, startAtStr, endAtStr, basePrice, status));
            }
            case "pricing" -> {
                String typeName = requireNonBlank(body.get("typeName"), "Seat type name");
                BigDecimal priceSurcharge = parseNonNegativeMoney(body.get("priceSurcharge"), "Price surcharge");
                String description = body.get("description") != null ? String.valueOf(body.get("description")) : "";
                String shapeType = body.get("shapeType") != null && !String.valueOf(body.get("shapeType")).isBlank()
                        ? String.valueOf(body.get("shapeType")).trim()
                        : "standard";
                yield String.valueOf(adminRepository.upsertPricing(id, typeName, priceSurcharge, description, shapeType));
            }
            case "promotions" -> {
                String promotionName = requireNonBlank(body.get("promotionName"), "Promotion name");
                String voucherCode = requireNonBlank(body.get("voucherCode"), "Voucher code").toUpperCase(Locale.ROOT);
                String discountType = requireAllowed(body.get("discountType"), DISCOUNT_TYPES, "Discount type");
                BigDecimal discountValue = parseNonNegativeMoney(body.get("discountValue"), "Discount value");
                BigDecimal maxDiscount = parseNonNegativeMoney(body.get("maxDiscount"), "Max discount");
                BigDecimal minOrderAmount = parseNonNegativeMoney(body.get("minOrderAmount"), "Min order amount");
                String startAt = normalizeDateTime(requireNonBlank(body.get("startAt"), "Start time"));
                String endAt = normalizeDateTime(requireNonBlank(body.get("endAt"), "End time"));
                int usageLimit = parsePositiveInt(body.get("usageLimit"), "Usage limit");
                int perUserLimit = parsePositiveInt(body.get("perUserLimit"), "Per-user limit");
                String status = requireAllowed(body.get("status"), PROMO_STATUSES, "Promotion status");
                yield String.valueOf(adminRepository.upsertPromotion(id, promotionName, voucherCode, discountType,
                        discountValue, maxDiscount, minOrderAmount, startAt, endAt, usageLimit, perUserLimit, status));
            }
            case "combos" -> {
                String productName = requireNonBlank(body.get("productName"), "Product name");
                String productType = requireNonBlank(body.get("productType"), "Product type").toUpperCase(Locale.ROOT);
                BigDecimal price = parseNonNegativeMoney(body.get("price"), "Price");
                String description = body.get("description") != null ? String.valueOf(body.get("description")) : "";
                String status = requireAllowed(body.get("status"), PROMO_STATUSES, "Product status");
                yield adminRepository.upsertUnifiedCombo(rawId, productName, productType, price, description, status);
            }
            case "staff" -> {
                String fullName = requireNonBlank(body.get("fullName"), "Full name");
                String email = requireNonBlank(body.get("email"), "Email");
                String phone = body.get("phone") != null ? String.valueOf(body.get("phone")).trim() : "";
                Long cinemaId = body.get("cinemaId") != null && String.valueOf(body.get("cinemaId")).matches("^\\d+$")
                        ? Long.valueOf(String.valueOf(body.get("cinemaId")))
                        : null;
                String role = requireAllowed(body.get("role"), STAFF_ROLES, "Staff role");
                String status = requireAllowed(body.get("status"), STAFF_STATUSES, "Staff status");
                yield saveStaffViaAccountService(id, fullName, email, phone, cinemaId, role, status);
            }
            default -> throw new IllegalArgumentException("Unsupported section: " + section);
        };

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", savedId);
        result.put("success", true);
        result.put("message", "Record saved successfully.");
        return result;
    }

    private String saveStaffViaAccountService(Long id, String fullName, String email, String phone,
                                              Long cinemaId, String role, String status) {
        if (adminAccountService == null) {
            throw new IllegalStateException("AdminAccountService is unavailable.");
        }
        Long actorId = SecurityContextUtils.resolveCurrentUserId();
        if (actorId == null) {
            throw new IllegalStateException("Administrator identity is required.");
        }
        if (id == null) {
            String generatedPwd = CryptoUtils.generate256BitToken().substring(0, 16) + "A1!";
            adminAccountService.createAccount(fullName, email, phone, role, cinemaId, generatedPwd, actorId);
            if (jdbcTemplate != null) {
                Long createdId = jdbcTemplate.queryForObject(
                        "SELECT user_id FROM users WHERE email = ?", Long.class, email.trim().toLowerCase(Locale.ROOT));
                if (createdId != null) {
                    if ("LOCKED".equals(status) || "INACTIVE".equals(status)) {
                        adminAccountService.changeLockStatus(createdId, true, actorId);
                    }
                    return String.valueOf(createdId);
                }
            }
            return "";
        }

        adminAccountService.updateAccount(id, fullName, phone, role, cinemaId, actorId);
        if ("LOCKED".equals(status)) {
            adminAccountService.changeLockStatus(id, true, actorId);
        } else if ("ACTIVE".equals(status)) {
            if (jdbcTemplate != null) {
                String currentStatus = jdbcTemplate.queryForObject(
                        "SELECT account_status FROM users WHERE user_id = ?", String.class, id);
                if ("LOCKED".equalsIgnoreCase(currentStatus)) {
                    adminAccountService.changeLockStatus(id, false, actorId);
                }
            }
        }
        return String.valueOf(id);
    }

    private void validateShowtimeSchedule(Long id, Long movieId, Long roomId,
                                          String startAtStr, String endAtStr, String status) {
        LocalDateTime startAt = LocalDateTime.parse(startAtStr.replace(' ', 'T'));
        LocalDateTime endAt = LocalDateTime.parse(endAtStr.replace(' ', 'T'));
        if (!endAt.isAfter(startAt)) {
            throw new IllegalArgumentException("Showtime end time must be after start time.");
        }
        if (id == null && !startAt.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("New showtimes must be scheduled in the future.");
        }

        Map<String, Object> movieInfo = adminRepository.findMovieScheduleInfo(movieId);
        if (movieInfo == null) {
            throw new IllegalArgumentException("Movie not found: " + movieId);
        }
        String movieStatus = String.valueOf(movieInfo.get("status"));
        if ("ACTIVE".equals(status) && ("INACTIVE".equalsIgnoreCase(movieStatus) || "ENDED".equalsIgnoreCase(movieStatus))) {
            throw new IllegalStateException("Cannot schedule an active showtime for an ENDED or INACTIVE movie.");
        }
        int durationMinutes = movieInfo.get("duration_minutes") instanceof Number n ? n.intValue() : 0;
        if (durationMinutes > 0 && endAt.isBefore(startAt.plusMinutes(durationMinutes))) {
            throw new IllegalArgumentException("Showtime window must be at least the movie duration (" + durationMinutes + " minutes).");
        }

        Map<String, Object> roomInfo = adminRepository.findRoomScheduleInfo(roomId);
        if (roomInfo == null) {
            throw new IllegalArgumentException("Room not found: " + roomId);
        }
        if ("ACTIVE".equals(status)) {
            String roomStatus = String.valueOf(roomInfo.get("room_status"));
            String cinemaStatus = String.valueOf(roomInfo.get("cinema_status"));
            if (!"ACTIVE".equalsIgnoreCase(roomStatus) || !"ACTIVE".equalsIgnoreCase(cinemaStatus)) {
                throw new IllegalStateException("Cannot schedule an active showtime in a non-ACTIVE room or cinema.");
            }
            if (adminRepository.hasOverlappingActiveShowtime(roomId, startAtStr, endAtStr, id)) {
                throw new IllegalStateException("Showtime overlaps with an existing active screening in this auditorium.");
            }
        }
    }

    @Override
    @Transactional
    public Map<String, Object> deleteRecord(String section, Long id) {
        return deleteRecord(section, String.valueOf(id));
    }

    @Override
    @Transactional
    public Map<String, Object> deleteRecord(String section, String id) {
        if ("staff".equals(section)) {
            Long actorId = SecurityContextUtils.resolveCurrentUserId();
            if (adminAccountService == null || actorId == null) {
                throw new IllegalStateException("AdminAccountService is required to deactivate staff accounts.");
            }
            adminAccountService.changeLockStatus(Long.valueOf(id), true, actorId);
        } else {
            adminRepository.safeDeleteOrDeactivate(section, id);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "Record deactivated/removed safely.");
        return response;
    }

    @Override
    @Transactional
    public Map<String, Object> saveRoomSeats(Long roomId, List<Map<String, Object>> seats) {
        adminRepository.replaceRoomSeats(roomId, seats);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "Seat layout updated successfully.");
        return response;
    }

    private String normalizeDateTime(String raw) {
        String formatted = raw.trim().replace('T', ' ');
        return formatted.length() == 16 ? formatted + ":00" : formatted;
    }

    private String requireNonBlank(Object val, String label) {
        if (val == null || String.valueOf(val).isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        return String.valueOf(val).trim();
    }

    private String requireAllowed(Object val, Set<String> allowed, String label) {
        String clean = requireNonBlank(val, label).toUpperCase(Locale.ROOT);
        if (!allowed.contains(clean)) {
            throw new IllegalArgumentException("Invalid " + label + ": " + clean);
        }
        return clean;
    }

    private BigDecimal parseNonNegativeMoney(Object raw, String label) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        try {
            BigDecimal bd = new BigDecimal(String.valueOf(raw).trim());
            if (bd.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException(label + " must not be negative.");
            }
            return bd;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid numeric amount for " + label + ".");
        }
    }

    private int parsePositiveInt(Object raw, String label) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new IllegalArgumentException(label + " is required.");
        }
        try {
            int val = Integer.parseInt(String.valueOf(raw).trim());
            if (val <= 0) {
                throw new IllegalArgumentException(label + " must be greater than zero.");
            }
            return val;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid integer for " + label + ".");
        }
    }
}
