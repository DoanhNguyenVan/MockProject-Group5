package com.boxcinema.service.impl;

import com.boxcinema.dto.PublicFoodItemDto;
import com.boxcinema.dto.PublicTicketDto;
import com.boxcinema.entity.*;
import com.boxcinema.repository.*;
import com.boxcinema.service.PublicTicketService;
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
        String tokenHash = CryptoHelper.sha256(tokenClean);

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
                bQrOpt = bookingQrRepository.findByTokenHash(CryptoHelper.sha256(extracted));
                if (bQrOpt.isEmpty()) {
                    bQrOpt = bookingQrRepository.findByTokenHash(extracted);
                }
            }
        }

        // 4. Extract booking code (e.g. BOX-20261116-3484)
        if (bQrOpt.isEmpty()) {
            Matcher mCode = Pattern.compile("BOX-\\d{8}-\\d{4}").matcher(tokenClean);
            if (mCode.find()) {
                String bookingCode = mCode.group();
                Optional<Booking> bOpt = bookingRepository.findByBookingCode(bookingCode);
                if (bOpt.isPresent()) {
                    bQrOpt = bookingQrRepository.findByBooking_BookingId(bOpt.get().getBookingId());
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
        dto.setValid(true);
        dto.setBookingCode(booking.getBookingCode());
        dto.setBookingStatus(booking.getBookingStatus());

        // Payment status
        Optional<Payment> paymentOpt = paymentRepository.findByBooking_BookingId(booking.getBookingId());
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
                fDto.setStatus("Đã đặt");
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
            dto.setFoodFulfillmentStatus("Đang chờ nhận tại quầy");
        }

        return Optional.of(dto);
    }

    private String mapFulfillmentStatus(String status) {
        if (status == null) return "Chờ nhận tại quầy";
        return switch (status.toUpperCase()) {
            case "QUEUED" -> "Đang chờ chuẩn bị";
            case "PREPARING" -> "Đang chuẩn bị";
            case "READY" -> "Đã sẵn sàng tại quầy";
            case "PICKED_UP", "COMPLETED" -> "Đã nhận đồ ăn";
            case "CANCELLED" -> "Đã hủy";
            default -> status;
        };
    }
}
