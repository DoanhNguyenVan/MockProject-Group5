package com.boxcinema.customer.service.impl;

import com.boxcinema.common.dto.*;
import com.boxcinema.customer.dto.request.*;
import com.boxcinema.customer.dto.response.*;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BookingQrService;
import com.boxcinema.common.service.QrCodeService;
import com.boxcinema.customer.service.TicketService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@Transactional
public class TicketServiceImpl implements TicketService {

    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final BookingQrRepository bookingQrRepository;
    private final BookingQrService bookingQrService;
    private final BookingCheckinRepository checkinRepository;
    private final FoodFulfillmentRepository foodFulfillmentRepository;
    private final EmailOutboxRepository emailOutboxRepository;
    private final UserRepository userRepository;
    private final QrCodeService qrCodeService;
    private final PaymentRepository paymentRepository;

    @Value("${app.qr.encryption-key:${payos.collection.checksum-key:${payos.checksum-key:BOX_CINEMA_DEFAULT_SECURE_PAYOS_KEY_2026_@!#}}}")
    private String checksumKey;

    @Value("${app.public-base-url:http://localhost:8080}")
    private String publicBaseUrl;

    public TicketServiceImpl(
            BookingRepository bookingRepository,
            TicketRepository ticketRepository,
            BookingQrRepository bookingQrRepository,
            BookingQrService bookingQrService,
            BookingCheckinRepository checkinRepository,
            FoodFulfillmentRepository foodFulfillmentRepository,
            EmailOutboxRepository emailOutboxRepository,
            UserRepository userRepository,
            QrCodeService qrCodeService,
            PaymentRepository paymentRepository
    ) {
        this.bookingRepository = bookingRepository;
        this.ticketRepository = ticketRepository;
        this.bookingQrRepository = bookingQrRepository;
        this.bookingQrService = bookingQrService;
        this.checkinRepository = checkinRepository;
        this.foodFulfillmentRepository = foodFulfillmentRepository;
        this.emailOutboxRepository = emailOutboxRepository;
        this.userRepository = userRepository;
        this.qrCodeService = qrCodeService;
        this.paymentRepository = paymentRepository;
    }

    private void validateBookingAccess(Booking booking, Long currentUserId) {
        if (booking == null) {
            throw new IllegalArgumentException("Booking does not exist.");
        }
        // If booking belongs to a registered member
        if (booking.getUser() != null) {
            if (currentUserId == null) {
                throw new AccessDeniedException("Please sign in to access ticket details for this order.");
            }
            Long ownerId = booking.getUser().getUserId();
            if (!ownerId.equals(currentUserId)) {
                User user = userRepository.findById(currentUserId).orElse(null);
                if (user == null || !hasStaffOrAdminBranchAccess(user, booking)) {
                    throw new AccessDeniedException(
                            "You do not have permission to access QR code or ticket details for this order.");
                }
            }
            return;
        }
        // Guest booking: require ADMIN/branch-scoped MANAGER/STAFF, session BOX_GUEST_BOOKING_IDS, or X-Booking-Code
        if (currentUserId != null) {
            User user = userRepository.findById(currentUserId).orElse(null);
            if (user != null && hasStaffOrAdminBranchAccess(user, booking)) {
                return;
            }
        }
        if (com.boxcinema.common.util.SecurityContextUtils.hasGuestBookingAccess(booking)) {
            return;
        }
        throw new AccessDeniedException(
                "You do not have permission to access QR code or ticket details for this order.");
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
            Long branchId = actor.getCinemaId();
            Long bookingCinemaId = booking.resolveCinemaId();
            return branchId != null && branchId.equals(bookingCinemaId);
        }
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public BookingTicketViewDto getTicketViewForOutbox(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found with id: " + bookingId));
        return buildTicketViewDto(booking);
    }

    @Override
    @Transactional(readOnly = true)
    public BookingTicketViewDto getTicketView(Long bookingId, Long currentUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found with id: " + bookingId));

        validateBookingAccess(booking, currentUserId);
        return buildTicketViewDto(booking);
    }

    private BookingTicketViewDto buildTicketViewDto(Booking booking) {
        Long bookingId = booking.getBookingId();

        BookingTicketViewDto dto = new BookingTicketViewDto();
        dto.setBookingId(booking.getBookingId());
        dto.setBookingCode(booking.getBookingCode());

        if (booking.getShowtime() != null) {
            if (booking.getShowtime().getMovie() != null) {
                dto.setMovieTitle(booking.getShowtime().getMovie().getTitle());
                dto.setMoviePosterUrl(booking.getShowtime().getMovie().getPosterUrl());
            }
            if (booking.getShowtime().getRoom() != null) {
                dto.setRoomName(booking.getShowtime().getRoom().getRoomName());
                if (booking.getShowtime().getRoom().getCinema() != null) {
                    dto.setCinemaName(booking.getShowtime().getRoom().getCinema().getCinemaName());
                }
            }
            if (booking.getShowtime().getStartAt() != null) {
                dto.setShowtimeStart(
                        booking.getShowtime().getStartAt().format(DateTimeFormatter.ofPattern("HH:mm - dd/MM/yyyy")));
            }
        }

        dto.setGuestName(booking.getGuestName());
        dto.setGuestEmail(booking.getGuestEmail());
        dto.setTotalAmount(booking.getTotalAmount());
        dto.setBookingStatus(booking.getBookingStatus());

        // Checkin status
        Optional<BookingCheckin> checkinOpt = checkinRepository.findByBooking_BookingId(bookingId);
        if (checkinOpt.isPresent()) {
            dto.setCheckinStatus("CHECKED_IN");
            dto.setCheckedInAt(checkinOpt.get().getCheckedInAt());
        } else {
            dto.setCheckinStatus("PENDING");
        }

        // Single Booking QR status (Spec Section 22 & QR-05: do NOT leak secret raw
        // token in JSON DTO)
        Optional<BookingQr> bQrOpt = bookingQrRepository.findByBooking_BookingId(bookingId);
        if (bQrOpt.isPresent()) {
            BookingQr bQr = bQrOpt.get();
            dto.setBookingQrStatus(bQr.getStatus());
            // Do NOT leak raw token or hash to client JSON response
            dto.setBookingQrHash(null);
            dto.setBookingQrToken(null);
        }

        // Food fulfillment status
        Optional<FoodFulfillment> ffOpt = foodFulfillmentRepository.findByBooking_BookingId(bookingId);
        dto.setFoodFulfillmentStatus(ffOpt.map(FoodFulfillment::getStatus).orElse("NONE"));

        // Tickets per seat
        List<Ticket> tickets = ticketRepository.findByBooking_BookingId(bookingId);
        for (Ticket t : tickets) {
            String seatLabel = "";
            if (booking.getBookingSeats() != null) {
                seatLabel = booking.getBookingSeats().stream()
                        .filter(bs -> bs.getBookingSeatId().equals(t.getBookingSeatId()))
                        .map(BookingSeat::getSeatLabelSnapshot)
                        .findFirst().orElse("");
            }
            dto.getTickets().add(new BookingTicketViewDto.SeatTicketItemDto(
                    t.getTicketId(),
                    t.getTicketCode(),
                    seatLabel,
                    t.getPrice(),
                    t.getTicketStatus()));
        }

        // Food items snapshot
        if (booking.getBookingFoodItems() != null) {
            for (BookingFoodItem bfi : booking.getBookingFoodItems()) {
                BookingFoodItemDto bfiDto = new BookingFoodItemDto();
                bfiDto.setId(bfi.getId());
                bfiDto.setItemType(bfi.getItemType());
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

    @Override
    @Transactional
    public byte[] getBookingQrImage(Long bookingId, Long currentUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found with id: " + bookingId));

        validateBookingAccess(booking, currentUserId);

        return bookingQrService.getBookingQrPngBytes(bookingId);
    }

    @Override
    public void resendEmail(Long bookingId, Long currentUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        validateBookingAccess(booking, currentUserId);

        if (!"CONFIRMED".equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("Emails can only be resent for successfully confirmed bookings.");
        }

        String recipientEmail = (booking.getUser() != null && booking.getUser().getEmail() != null && !booking.getUser().getEmail().isBlank())
                ? booking.getUser().getEmail()
                : booking.getGuestEmail();
        EmailOutbox outbox = new EmailOutbox(booking, recipientEmail, "RESEND_CONFIRMATION");
        emailOutboxRepository.save(outbox);
    }

    private String formatVnd(BigDecimal amount) {
        if (amount == null)
            return "0 VND";
        java.text.DecimalFormatSymbols symbols = new java.text.DecimalFormatSymbols(Locale.US);
        symbols.setGroupingSeparator(',');
        java.text.DecimalFormat df = new java.text.DecimalFormat("#,###", symbols);
        return df.format(amount) + " VND";
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingHistoryItemDto> getBookingHistory(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }

        List<Booking> bookings = bookingRepository.findByUser_UserIdOrderByBookedAtDesc(userId);

        List<BookingHistoryItemDto> historyList = new ArrayList<>();
        for (Booking b : bookings) {
            BookingHistoryItemDto item = new BookingHistoryItemDto();
            item.setBookingId(b.getBookingId());
            item.setBookingCode(b.getBookingCode());

            if (b.getShowtime() != null && b.getShowtime().getMovie() != null) {
                item.setMovieTitle(b.getShowtime().getMovie().getTitle());
                item.setMoviePosterUrl(b.getShowtime().getMovie().getPosterUrl());
            } else {
                item.setMovieTitle("BOX Cinema Movie Ticket");
            }

            if (b.getShowtime() != null && b.getShowtime().getRoom() != null
                    && b.getShowtime().getRoom().getCinema() != null) {
                item.setCinemaName(b.getShowtime().getRoom().getCinema().getCinemaName());
            } else {
                item.setCinemaName("BOX Cinema Royal City");
            }

            if (b.getShowtime() != null && b.getShowtime().getStartAt() != null) {
                LocalDateTime startAt = b.getShowtime().getStartAt();
                item.setShowDate(startAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
                item.setShowTime(startAt.format(DateTimeFormatter.ofPattern("HH:mm")));
                item.setShowDateTimeText(startAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy | HH:mm")));
                item.setPointsExpiryDate(
                        startAt.toLocalDate().plusDays(60).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            } else {
                LocalDateTime dt = b.getBookedAt() != null ? b.getBookedAt() : LocalDateTime.now();
                item.setShowDate(dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
                item.setShowTime(dt.format(DateTimeFormatter.ofPattern("HH:mm")));
                item.setShowDateTimeText(dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy | HH:mm")));
                item.setPointsExpiryDate(
                        dt.toLocalDate().plusDays(60).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            }

            BigDecimal total = b.getTotalAmount() != null ? b.getTotalAmount() : BigDecimal.ZERO;
            item.setTotalAmount(total);
            item.setTotalAmountFormatted(formatVnd(total));

            boolean pointsEligible = "CONFIRMED".equalsIgnoreCase(b.getBookingStatus())
                    || "PAID".equalsIgnoreCase(b.getBookingStatus());
            long pts = pointsEligible ? Math.max(100L, Math.round(total.doubleValue() / 60.0)) : 0L;
            item.setRewardPointsFormatted(String.format(Locale.US, "%.1f", (double) pts));

            item.setBookingStatus(b.getBookingStatus());
            item.setBookedAt(b.getBookedAt());
            historyList.add(item);
        }

        return historyList;
    }

    @Override
    @Transactional(readOnly = true)
    public BookingHistoryDetailDto getBookingDetail(Long bookingId, Long currentUserId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found with id: " + bookingId));

        validateBookingAccess(booking, currentUserId);

        BookingHistoryDetailDto dto = new BookingHistoryDetailDto();
        dto.setBookingId(booking.getBookingId());
        dto.setBookingCode(booking.getBookingCode());
        dto.setBookingStatus(booking.getBookingStatus());
        dto.setBookedAt(booking.getBookedAt());

        if (booking.getShowtime() != null) {
            if (booking.getShowtime().getMovie() != null) {
                dto.setMovieTitle(booking.getShowtime().getMovie().getTitle());
            }
            if (booking.getShowtime().getRoom() != null) {
                dto.setRoomName(booking.getShowtime().getRoom().getRoomName());
                if (booking.getShowtime().getRoom().getCinema() != null) {
                    dto.setCinemaName(booking.getShowtime().getRoom().getCinema().getCinemaName());
                }
            }
            if (booking.getShowtime().getStartAt() != null) {
                LocalDateTime startAt = booking.getShowtime().getStartAt();
                dto.setShowDate(startAt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
                dto.setShowTime(startAt.format(DateTimeFormatter.ofPattern("HH:mm")));
                dto.setPointsExpiryDate(
                        startAt.toLocalDate().plusDays(60).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            }
        }

        if (dto.getMovieTitle() == null)
            dto.setMovieTitle("BOX Cinema Movie Ticket");
        if (dto.getCinemaName() == null)
            dto.setCinemaName("BOX Cinema Royal City");
        if (dto.getRoomName() == null)
            dto.setRoomName("Standard Hall");
        if (dto.getShowDate() == null) {
            LocalDateTime dt = booking.getBookedAt() != null ? booking.getBookedAt() : LocalDateTime.now();
            dto.setShowDate(dt.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
            dto.setShowTime(dt.format(DateTimeFormatter.ofPattern("HH:mm")));
            dto.setPointsExpiryDate(dt.toLocalDate().plusDays(60).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
        }

        // Seats
        List<String> seatLabels = new ArrayList<>();
        if (booking.getBookingSeats() != null && !booking.getBookingSeats().isEmpty()) {
            for (BookingSeat bs : booking.getBookingSeats()) {
                if (bs.getSeatLabelSnapshot() != null && !bs.getSeatLabelSnapshot().isBlank()) {
                    seatLabels.add(bs.getSeatLabelSnapshot());
                }
            }
        }
        if (seatLabels.isEmpty()) {
            List<Ticket> tickets = ticketRepository.findByBooking_BookingId(bookingId);
            for (Ticket t : tickets) {
                if (t.getTicketCode() != null) {
                    seatLabels.add(t.getTicketCode());
                }
            }
        }
        dto.setSeatCount(seatLabels.size());
        dto.setSeatsText(seatLabels.isEmpty() ? "No seats" : String.join(", ", seatLabels));

        // Combos / Food
        List<String> combos = new ArrayList<>();
        int totalComboQty = 0;
        if (booking.getBookingFoodItems() != null && !booking.getBookingFoodItems().isEmpty()) {
            for (BookingFoodItem bfi : booking.getBookingFoodItems()) {
                int qty = bfi.getQuantity();
                totalComboQty += qty;
                combos.add(bfi.getProductNameSnapshot() + "(" + qty + ")");
            }
        }
        dto.setComboCount(totalComboQty);
        dto.setComboText(combos.isEmpty() ? "None" : String.join(", ", combos));

        // Total & Points (only shown for CONFIRMED/PAID bookings per PBOA-04)
        BigDecimal total = booking.getTotalAmount() != null ? booking.getTotalAmount() : BigDecimal.ZERO;
        dto.setTotalAmount(total);
        dto.setTotalAmountFormatted(formatVnd(total));

        boolean pointsEligible = "CONFIRMED".equalsIgnoreCase(booking.getBookingStatus())
                || "PAID".equalsIgnoreCase(booking.getBookingStatus());
        long pts = pointsEligible ? Math.max(100L, Math.round(total.doubleValue() / 60.0)) : 0L;
        dto.setRewardPointsFormatted(String.valueOf(pts));

        // Payment info
        Optional<Payment> paymentOpt = paymentRepository
                .findFirstByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(bookingId, "PAID")
                .or(() -> paymentRepository.findTopByBooking_BookingIdOrderByPaymentIdDesc(bookingId));
        if (paymentOpt.isPresent()) {
            Payment p = paymentOpt.get();
            String method = p.getPaymentMethod();
            if (method == null || method.contains("QR") || method.contains("PAYOS")) {
                dto.setPaymentMethod("QR_PAYMENT");
            } else {
                dto.setPaymentMethod(method);
            }
            dto.setPaymentStatus(p.getPaymentStatus() != null ? p.getPaymentStatus() : "PAID");
        } else {
            dto.setPaymentMethod("QR_PAYMENT");
            dto.setPaymentStatus("CONFIRMED".equalsIgnoreCase(booking.getBookingStatus()) ? "PAID" : "UNPAID");
        }

        // Checkin status
        Optional<BookingCheckin> checkinOpt = checkinRepository.findByBooking_BookingId(bookingId);
        dto.setCheckinStatus(checkinOpt.isPresent() ? "CHECKED_IN" : "PENDING");

        // QR Code generation (pure read-only inside readOnly tx: never write DB/disk)
        if ("CONFIRMED".equalsIgnoreCase(booking.getBookingStatus())) {
            try {
                Optional<BookingQr> bQrOpt = bookingQrRepository.findByBooking_BookingId(bookingId);
                if (bQrOpt.isPresent() && !"REVOKED".equalsIgnoreCase(bQrOpt.get().getStatus())) {
                    BookingQr bQr = bQrOpt.get();
                    String url = bQr.getTicketUrl();
                    if ((url == null || url.isBlank()) && bQr.getTokenEncrypted() != null) {
                        String rawToken = com.boxcinema.common.util.CryptoUtils.decryptAes(bQr.getTokenEncrypted(), checksumKey);
                        String base = (publicBaseUrl != null && !publicBaseUrl.isBlank()) ? publicBaseUrl.replaceAll("/+$", "") : "http://localhost:8080";
                        url = base + "/t/" + rawToken;
                    }
                    if (url != null && !url.isBlank()) {
                        byte[] qrBytes = qrCodeService.generateQrPng(url, 300, 300);
                        if (qrBytes != null && qrBytes.length > 0) {
                            dto.setQrCodeDataUri("data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(qrBytes));
                        }
                    }
                }
            } catch (Exception ignored) {
                // QR might not exist
            }
        }

        return dto;
    }
}
