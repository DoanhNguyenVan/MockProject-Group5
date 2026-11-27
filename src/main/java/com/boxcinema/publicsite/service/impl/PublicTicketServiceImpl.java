package com.boxcinema.publicsite.service.impl;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.publicsite.dto.response.PublicFoodItemDto;
import com.boxcinema.publicsite.dto.response.PublicTicketDto;
import com.boxcinema.publicsite.service.PublicTicketService;
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

    public PublicTicketServiceImpl(
            BookingQrRepository bookingQrRepository,
            TicketRepository ticketRepository,
            BookingCheckinRepository checkinRepository,
            FoodFulfillmentRepository foodFulfillmentRepository,
            PaymentRepository paymentRepository
    ) {
        this.bookingQrRepository = bookingQrRepository;
        this.ticketRepository = ticketRepository;
        this.checkinRepository = checkinRepository;
        this.foodFulfillmentRepository = foodFulfillmentRepository;
        this.paymentRepository = paymentRepository;
    }

    @Override
    public Optional<PublicTicketDto> getPublicTicketByToken(String token) {
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

        PublicTicketDto dto = new PublicTicketDto();
        dto.setBookingCode(booking.getBookingCode());
        dto.setBookingStatus(booking.getBookingStatus());

        Optional<Payment> paymentOpt = paymentRepository.findByBooking_BookingId(booking.getBookingId());
        String paymentStatus = paymentOpt.map(Payment::getPaymentStatus)
                .filter(s -> s != null && !s.isBlank())
                .orElse("CONFIRMED".equalsIgnoreCase(booking.getBookingStatus()) ? "COMPLETED" : "PENDING");
        dto.setPaymentStatus(paymentStatus);

        Showtime showtime = booking.getShowtime();
        if (showtime != null) {
            if (showtime.getMovie() != null) {
                dto.setMovieTitle(showtime.getMovie().getTitle());
                dto.setMoviePosterUrl(showtime.getMovie().getPosterUrl());
            }
            if (showtime.getRoom() != null) {
                dto.setRoomName(showtime.getRoom().getRoomName());
                Cinema cinema = showtime.getRoom().getCinema();
                if (cinema != null) {
                    dto.setCinemaName(cinema.getCinemaName());
                    String address = cinema.getAddress() != null ? cinema.getAddress().trim() : "";
                    String city = cinema.getCity() != null ? cinema.getCity().trim() : "";
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
        LocalDateTime effectiveExpiry = bookingQr.getExpiresAt() != null
                ? bookingQr.getExpiresAt()
                : (showtime != null && showtime.getEndAt() != null ? showtime.getEndAt().plusHours(4) : null);
        if ("EXPIRED".equalsIgnoreCase(booking.getBookingStatus())
                || (effectiveExpiry != null && now.isAfter(effectiveExpiry))) {
            dto.setTicketStatus("EXPIRED");
            dto.setValid(false);
            dto.setConfirmable(false);
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
        dto.setConfirmable(true);
        dto.setMessage("Valid e-ticket ready for gate verification.");
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
