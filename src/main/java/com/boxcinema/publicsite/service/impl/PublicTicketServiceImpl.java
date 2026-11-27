package com.boxcinema.publicsite.service.impl;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.publicsite.dto.response.PublicFoodItemDto;
import com.boxcinema.publicsite.dto.response.PublicTicketDto;
import com.boxcinema.publicsite.service.PublicTicketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional(readOnly = true)
public class PublicTicketServiceImpl implements PublicTicketService {

    private static final Pattern BOX_TOKEN_PATTERN = Pattern.compile("BOX1\\.[a-zA-Z0-9_-]{16,}");
    private static final DateTimeFormatter CHECKIN_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm - dd/MM/yyyy");

    private final BookingQrRepository bookingQrRepository;
    private final TicketRepository ticketRepository;
    private final BookingCheckinRepository checkinRepository;
    private final FoodFulfillmentRepository foodFulfillmentRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final CinemaRepository cinemaRepository;

    public PublicTicketServiceImpl(
            BookingQrRepository bookingQrRepository,
            TicketRepository ticketRepository,
            BookingCheckinRepository checkinRepository,
            FoodFulfillmentRepository foodFulfillmentRepository,
            PaymentRepository paymentRepository
    ) {
        this(bookingQrRepository, ticketRepository, checkinRepository, foodFulfillmentRepository,
                paymentRepository, null, null);
    }

    @Autowired
    public PublicTicketServiceImpl(
            BookingQrRepository bookingQrRepository,
            TicketRepository ticketRepository,
            BookingCheckinRepository checkinRepository,
            FoodFulfillmentRepository foodFulfillmentRepository,
            PaymentRepository paymentRepository,
            UserRepository userRepository,
            CinemaRepository cinemaRepository
    ) {
        this.bookingQrRepository = bookingQrRepository;
        this.ticketRepository = ticketRepository;
        this.checkinRepository = checkinRepository;
        this.foodFulfillmentRepository = foodFulfillmentRepository;
        this.paymentRepository = paymentRepository;
        this.userRepository = userRepository;
        this.cinemaRepository = cinemaRepository;
    }

    @Override
    public Optional<PublicTicketDto> getTicketByTokenForViewer(String token, Long viewerUserId, String viewerRole) {
        if (viewerUserId == null) {
            throw new AccessDeniedException("Authentication required to view ticket details.");
        }
        if (token == null || token.trim().isBlank()) {
            return Optional.empty();
        }

        String tokenClean = token.trim();
        String cryptographicToken = extractCryptographicToken(tokenClean);
        if (cryptographicToken == null) {
            return Optional.empty();
        }

        String tokenHash = CryptoUtils.sha256(cryptographicToken);
        Optional<BookingQr> bQrOpt = bookingQrRepository.findByTokenHash(tokenHash);
        if (bQrOpt.isEmpty()) {
            return Optional.empty();
        }

        BookingQr bookingQr = bQrOpt.get();
        Booking booking = bookingQr.getBooking();
        if (booking == null) {
            return Optional.empty();
        }

        User viewerUser = userRepository != null ? userRepository.findById(viewerUserId).orElse(null) : null;
        String resolvedRole = viewerUser != null && viewerUser.getRole() != null
                ? viewerUser.getRole().trim().toUpperCase()
                : (viewerRole != null ? viewerRole.replace("ROLE_", "").trim().toUpperCase() : "");
        boolean isStaff = "STAFF".equals(resolvedRole)
                || "MANAGER".equals(resolvedRole)
                || "ADMIN".equals(resolvedRole);

        Showtime showtime = booking.getShowtime();
        Cinema targetCinema = (showtime != null && showtime.getRoom() != null)
                ? showtime.getRoom().getCinema()
                : null;

        if (!isStaff) {
            Long ownerUserId = booking.getUser() != null ? booking.getUser().getUserId() : null;
            if (ownerUserId == null || !ownerUserId.equals(viewerUserId)) {
                throw new AccessDeniedException("You do not have permission to view this ticket.");
            }
        } else if (!"ADMIN".equals(resolvedRole) && viewerUser != null && viewerUser.getCinemaId() != null) {
            Long targetCinemaId = targetCinema != null ? targetCinema.getCinemaId() : null;
            if (targetCinemaId != null && !viewerUser.getCinemaId().equals(targetCinemaId)) {
                String assignedName = cinemaRepository != null
                        ? cinemaRepository.findById(viewerUser.getCinemaId())
                                .map(Cinema::getCinemaName)
                                .orElse("your assigned cinema branch")
                        : "your assigned cinema branch";
                String targetName = targetCinema.getCinemaName() != null
                        ? targetCinema.getCinemaName()
                        : "another cinema branch";
                throw new AccessDeniedException(
                        "WRONG_CINEMA_BRANCH|This ticket belongs to " + targetName
                                + " and cannot be viewed, printed, or checked in from " + assignedName + ".");
            }
        }

        PublicTicketDto dto = new PublicTicketDto();
        dto.setBookingId(booking.getBookingId());
        dto.setBookingCode(booking.getBookingCode());
        dto.setBookingStatus(booking.getBookingStatus());
        dto.setViewerIsStaff(isStaff);
        // Staff counter/gate UI never renders a duplicate QR image; only verified ticket owner gets QR PNG
        dto.setCanDisplayQrImage(!isStaff);

        if (isStaff) {
            User buyer = booking.getUser();
            dto.setBuyerName(buyer != null && buyer.getFullName() != null && !buyer.getFullName().isBlank()
                    ? buyer.getFullName()
                    : booking.getGuestName());
            dto.setBuyerEmail(buyer != null && buyer.getEmail() != null && !buyer.getEmail().isBlank()
                    ? buyer.getEmail()
                    : booking.getGuestEmail());
            dto.setBuyerPhone(buyer != null && buyer.getPhone() != null && !buyer.getPhone().isBlank()
                    ? buyer.getPhone()
                    : booking.getGuestPhone());
        }

        Optional<Payment> paymentOpt = paymentRepository.findByBooking_BookingId(booking.getBookingId());
        String paymentStatus = paymentOpt.map(Payment::getPaymentStatus)
                .filter(s -> s != null && !s.isBlank())
                .orElse("CONFIRMED".equalsIgnoreCase(booking.getBookingStatus()) ? "COMPLETED" : "PENDING");
        dto.setPaymentStatus(paymentStatus);

        if (showtime != null) {
            dto.setShowtimeId(showtime.getShowtimeId());
            if (showtime.getMovie() != null) {
                dto.setMovieTitle(showtime.getMovie().getTitle());
                dto.setMoviePosterUrl(showtime.getMovie().getPosterUrl());
            }
            if (showtime.getRoom() != null) {
                dto.setRoomName(showtime.getRoom().getRoomName());
                if (targetCinema != null) {
                    dto.setCinemaId(targetCinema.getCinemaId());
                    dto.setCinemaName(targetCinema.getCinemaName());
                    String address = targetCinema.getAddress() != null ? targetCinema.getAddress().trim() : "";
                    String city = targetCinema.getCity() != null ? targetCinema.getCity().trim() : "";
                    if (!address.isEmpty() && !city.isEmpty() && !address.toLowerCase().contains(city.toLowerCase())) {
                        dto.setCinemaAddress(address + ", " + city);
                    } else if (!address.isEmpty()) {
                        dto.setCinemaAddress(address);
                    }
                }
            }
            if (showtime.getStartAt() != null) {
                dto.setShowDate(showtime.getStartAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
                dto.setShowTime(showtime.getStartAt().format(DateTimeFormatter.ofPattern("HH:mm")));
            }
        }

        List<Ticket> tickets = ticketRepository.findByBooking_BookingId(booking.getBookingId());
        List<String> ticketCodes = new ArrayList<>();
        for (Ticket t : tickets) {
            if (t.getTicketCode() != null && !t.getTicketCode().isBlank()) {
                ticketCodes.add(t.getTicketCode());
            }
        }
        dto.setTicketCodes(ticketCodes);

        List<String> seatLabels = new ArrayList<>();
        if (booking.getBookingSeats() != null) {
            for (BookingSeat bs : booking.getBookingSeats()) {
                if (bs.getSeatLabelSnapshot() != null && !bs.getSeatLabelSnapshot().isBlank()) {
                    seatLabels.add(bs.getSeatLabelSnapshot());
                }
            }
        }
        dto.setSeats(seatLabels);
        dto.setTicketCount(Math.max(seatLabels.size(), ticketCodes.size()));

        List<PublicFoodItemDto> foodList = new ArrayList<>();
        if (booking.getBookingFoodItems() != null) {
            for (BookingFoodItem bfi : booking.getBookingFoodItems()) {
                PublicFoodItemDto fDto = new PublicFoodItemDto();
                fDto.setProductName(bfi.getProductNameSnapshot());
                fDto.setVariantName(bfi.getVariantNameSnapshot());
                fDto.setQuantity(bfi.getQuantity());
                fDto.setStatus("Ordered");
                foodList.add(fDto);
            }
        }
        dto.setFoodItems(foodList);

        Optional<FoodFulfillment> ffOpt = foodFulfillmentRepository.findByBooking_BookingId(booking.getBookingId());
        if (ffOpt.isPresent()) {
            dto.setFoodFulfillmentStatus(mapFulfillmentStatus(ffOpt.get().getStatus()));
            for (PublicFoodItemDto f : foodList) {
                f.setStatus(dto.getFoodFulfillmentStatus());
            }
        } else if (!foodList.isEmpty()) {
            dto.setFoodFulfillmentStatus("Awaiting Pickup at Concession");
        }

        evaluateTicketState(dto, bookingQr, booking, showtime, paymentStatus, tickets);
        return Optional.of(dto);
    }

    private String extractCryptographicToken(String rawInput) {
        if (BOX_TOKEN_PATTERN.matcher(rawInput).matches()) {
            return rawInput;
        }
        Matcher matcher = BOX_TOKEN_PATTERN.matcher(rawInput);
        return matcher.find() ? matcher.group() : null;
    }

    private void evaluateTicketState(
            PublicTicketDto dto,
            BookingQr bookingQr,
            Booking booking,
            Showtime showtime,
            String paymentStatus,
            List<Ticket> tickets
    ) {
        Optional<BookingCheckin> checkinOpt = checkinRepository.findByBooking_BookingId(booking.getBookingId());
        LocalDateTime checkedAt = bookingQr.getUsedAt() != null
                ? bookingQr.getUsedAt()
                : checkinOpt.map(BookingCheckin::getCheckedInAt).orElse(null);
        if (checkedAt != null) {
            dto.setCheckedInAt(checkedAt.format(CHECKIN_TIME_FMT));
        }

        boolean hasRefundedTicket = tickets.stream().anyMatch(t -> "REFUNDED".equalsIgnoreCase(t.getTicketStatus()));
        if ("REFUNDED".equalsIgnoreCase(paymentStatus) || hasRefundedTicket) {
            dto.setTicketStatus("REFUNDED");
            dto.setValid(false);
            dto.setConfirmable(false);
            dto.setStatusReason("This booking has been refunded and cannot be used for entry.");
            dto.setMessage(dto.getStatusReason());
            return;
        }

        boolean hasCancelledTicket = tickets.stream().anyMatch(t -> "CANCELLED".equalsIgnoreCase(t.getTicketStatus()));
        if ("CANCELLED".equalsIgnoreCase(booking.getBookingStatus())
                || (showtime != null && "CANCELLED".equalsIgnoreCase(showtime.getStatus()))
                || hasCancelledTicket) {
            dto.setTicketStatus("CANCELLED");
            dto.setValid(false);
            dto.setConfirmable(false);
            dto.setStatusReason(showtime != null && "CANCELLED".equalsIgnoreCase(showtime.getStatus())
                    ? "This showtime has been cancelled."
                    : "This booking has been cancelled and is no longer valid.");
            dto.setMessage(dto.getStatusReason());
            return;
        }

        boolean allTicketsUsed = !tickets.isEmpty()
                && tickets.stream().allMatch(t -> "USED".equalsIgnoreCase(t.getTicketStatus()));
        if ("USED".equalsIgnoreCase(bookingQr.getStatus()) || checkinOpt.isPresent() || allTicketsUsed) {
            dto.setTicketStatus("USED");
            dto.setValid(false);
            dto.setConfirmable(false);
            dto.setStatusReason(dto.getCheckedInAt() != null
                    ? "This ticket was already checked in at " + dto.getCheckedInAt() + "."
                    : "This ticket has already been used.");
            dto.setMessage(dto.getStatusReason());
            return;
        }

        if ("REVOKED".equalsIgnoreCase(bookingQr.getStatus())) {
            dto.setTicketStatus("REVOKED");
            dto.setValid(false);
            dto.setConfirmable(false);
            dto.setStatusReason("This ticket QR code has been revoked.");
            dto.setMessage(dto.getStatusReason());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime thirtyMinGrace = (showtime != null && showtime.getEndAt() != null)
                ? showtime.getEndAt().plusMinutes(30)
                : null;
        LocalDateTime qrExpiry = bookingQr.getExpiresAt();
        LocalDateTime effectiveExpiry = (qrExpiry != null && thirtyMinGrace != null)
                ? (qrExpiry.isBefore(thirtyMinGrace) ? qrExpiry : thirtyMinGrace)
                : (thirtyMinGrace != null ? thirtyMinGrace : qrExpiry);
        if ("EXPIRED".equalsIgnoreCase(booking.getBookingStatus())
                || (effectiveExpiry != null && now.isAfter(effectiveExpiry))) {
            dto.setTicketStatus("EXPIRED");
            dto.setValid(false);
            dto.setConfirmable(false);
            dto.setErrorCode("TICKET_EXPIRED");
            dto.setStatusReason("This ticket QR code has expired for the scheduled showtime.");
            dto.setMessage(dto.getStatusReason());
            return;
        }

        if (!"CONFIRMED".equalsIgnoreCase(booking.getBookingStatus())
                || !"ACTIVE".equalsIgnoreCase(bookingQr.getStatus())
                || tickets.isEmpty()) {
            dto.setTicketStatus("INVALID");
            dto.setValid(false);
            dto.setConfirmable(false);
            dto.setStatusReason("Ticket is not active or payment has not been confirmed.");
            dto.setMessage(dto.getStatusReason());
            return;
        }

        dto.setTicketStatus("VALID");
        dto.setValid(true);
        dto.setConfirmable(dto.isViewerIsStaff());
        dto.setMessage(dto.isViewerIsStaff()
                ? "Valid e-ticket ready for gate verification."
                : "Valid e-ticket ready for counter presentation.");
    }

    private String mapFulfillmentStatus(String status) {
        if (status == null) return "Awaiting Pickup";
        return switch (status.toUpperCase()) {
            case "QUEUED" -> "Queued for Preparation";
            case "PREPARING" -> "In Preparation";
            case "READY" -> "Ready for Pickup";
            case "PICKED_UP", "COMPLETED" -> "Picked Up";
            case "CANCELLED" -> "Cancelled";
            default -> status;
        };
    }
}
