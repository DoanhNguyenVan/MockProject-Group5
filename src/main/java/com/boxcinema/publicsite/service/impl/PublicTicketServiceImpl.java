package com.boxcinema.publicsite.service.impl;

import com.boxcinema.publicsite.dto.response.PublicFoodItemDto;
import com.boxcinema.publicsite.dto.response.PublicTicketDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.publicsite.service.PublicTicketService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional(readOnly = true)
public class PublicTicketServiceImpl implements PublicTicketService {

    private final BookingQrRepository bookingQrRepository;
    private final BookingRepository bookingRepository;
    private final FoodFulfillmentRepository foodFulfillmentRepository;
    private final PaymentRepository paymentRepository;

    public PublicTicketServiceImpl(
            BookingQrRepository bookingQrRepository,
            BookingRepository bookingRepository,
            FoodFulfillmentRepository foodFulfillmentRepository,
            PaymentRepository paymentRepository
    ) {
        this.bookingQrRepository = bookingQrRepository;
        this.bookingRepository = bookingRepository;
        this.foodFulfillmentRepository = foodFulfillmentRepository;
        this.paymentRepository = paymentRepository;
    }

    @Override
    public Optional<PublicTicketDto> getPublicTicketByToken(String token) {
        if (token == null || token.trim().isBlank()) {
            return Optional.empty();
        }

        String tokenClean = token.trim();
        String tokenHash = com.boxcinema.common.util.CryptoUtils.sha256(tokenClean);

        // 1. Search directly by hash
        Optional<BookingQr> bQrOpt = bookingQrRepository.findByTokenHash(tokenHash);
        
        // 2. Search directly by clean token (in case DB has raw token or hash matches tokenClean)
        if (bQrOpt.isEmpty()) {
            bQrOpt = bookingQrRepository.findByTokenHash(tokenClean);
        }

        // 3. Extract BOX1.<token> if URL or extra string is passed
        if (bQrOpt.isEmpty()) {
            Matcher m = Pattern.compile("BOX1\\.[a-zA-Z0-9_-]+").matcher(tokenClean);
            if (m.find()) {
                String extracted = m.group();
                bQrOpt = bookingQrRepository.findByTokenHash(com.boxcinema.common.util.CryptoUtils.sha256(extracted));
                if (bQrOpt.isEmpty()) {
                    bQrOpt = bookingQrRepository.findByTokenHash(extracted);
                }
            }
        }

        if (bQrOpt.isEmpty()) {
            return Optional.empty();
        }

        BookingQr bookingQr = bQrOpt.get();
        Booking booking = bookingQr.getBooking();
        if (booking == null) {
            return Optional.empty();
        }

        PublicTicketDto dto = new PublicTicketDto();
        boolean isExpired = bookingQr.getExpiresAt() != null
                && bookingQr.getExpiresAt().isBefore(java.time.LocalDateTime.now());
        boolean isValidQr = "CONFIRMED".equalsIgnoreCase(booking.getBookingStatus())
                && !"REVOKED".equalsIgnoreCase(bookingQr.getStatus())
                && !isExpired;
        dto.setValid(isValidQr);
        if (isExpired) {
            dto.setMessage("This ticket QR code has expired.");
        }
        dto.setBookingCode(booking.getBookingCode());
        dto.setBookingStatus(booking.getBookingStatus());

        // Payment status
        Optional<Payment> paymentOpt = paymentRepository
                .findFirstByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(booking.getBookingId(), "PAID")
                .or(() -> paymentRepository.findTopByBooking_BookingIdOrderByPaymentIdDesc(booking.getBookingId()));
        if (paymentOpt.isPresent()) {
            dto.setPaymentStatus(paymentOpt.get().getPaymentStatus());
        } else if ("CONFIRMED".equalsIgnoreCase(booking.getBookingStatus())) {
            dto.setPaymentStatus("COMPLETED");
        } else {
            dto.setPaymentStatus("PENDING");
        }

        // Showtime & Movie
        if (booking.getShowtime() != null) {
            Showtime st = booking.getShowtime();
            if (st.getMovie() != null) {
                dto.setMovieTitle(st.getMovie().getTitle());
                dto.setMoviePosterUrl(st.getMovie().getPosterUrl());
            }
            if (st.getRoom() != null) {
                dto.setRoomName(st.getRoom().getRoomName());
                if (st.getRoom().getCinema() != null) {
                    dto.setCinemaName(st.getRoom().getCinema().getCinemaName());
                }
            }
            if (st.getStartAt() != null) {
                dto.setShowDate(st.getStartAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy")));
                dto.setShowTime(st.getStartAt().format(DateTimeFormatter.ofPattern("HH:mm")));
            }
        }

        // Seats
        List<String> seatLabels = new ArrayList<>();
        if (booking.getBookingSeats() != null) {
            for (BookingSeat bs : booking.getBookingSeats()) {
                if (bs.getSeatLabelSnapshot() != null && !bs.getSeatLabelSnapshot().isBlank()) {
                    seatLabels.add(bs.getSeatLabelSnapshot());
                }
            }
        }
        dto.setSeats(seatLabels);
        dto.setTicketCount(seatLabels.size());

        // Food & Concessions
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

        // Food fulfillment status
        Optional<FoodFulfillment> ffOpt = foodFulfillmentRepository.findByBooking_BookingId(booking.getBookingId());
        if (ffOpt.isPresent()) {
            String ffStatus = ffOpt.get().getStatus();
            dto.setFoodFulfillmentStatus(mapFulfillmentStatus(ffStatus));
            for (PublicFoodItemDto f : foodList) {
                f.setStatus(dto.getFoodFulfillmentStatus());
            }
        } else if (!foodList.isEmpty()) {
            dto.setFoodFulfillmentStatus("Awaiting Pickup at Concession");
        }

        return Optional.of(dto);
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
