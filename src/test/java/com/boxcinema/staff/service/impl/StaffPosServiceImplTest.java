package com.boxcinema.staff.service.impl;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BookingQrService;
import com.boxcinema.customer.service.FoodService;
import com.boxcinema.customer.service.PayOsService;
import com.boxcinema.customer.service.PromotionService;
import com.boxcinema.customer.service.SeatRealtimePushService;
import com.boxcinema.staff.dto.response.StaffPosBookingResultDto;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StaffPosServiceImplTest {

    @Test
    void buildResultDtoIncludesCinemaRoomFoodItemsQrTokenAndEmailFlag() {
        BookingRepository bookingRepo = mock(BookingRepository.class);
        PaymentRepository paymentRepo = mock(PaymentRepository.class);
        BookingQrService qrService = mock(BookingQrService.class);

        StaffPosServiceImpl service = new StaffPosServiceImpl(
                mock(ShowtimeRepository.class),
                mock(CinemaRepository.class),
                bookingRepo,
                mock(UserRepository.class),
                paymentRepo,
                mock(TicketRepository.class),
                mock(FoodItemRepository.class),
                mock(FoodVariantRepository.class),
                mock(FoodComboRepository.class),
                mock(FoodFulfillmentRepository.class),
                mock(EmailOutboxRepository.class),
                mock(FoodService.class),
                mock(PromotionService.class),
                mock(PayOsService.class),
                qrService,
                mock(SeatRealtimePushService.class),
                mock(JdbcTemplate.class)
        );

        Cinema cinema = new Cinema();
        cinema.setCinemaId(1L);
        cinema.setCinemaName("BOX Cinema Royal City");

        Room room = new Room();
        room.setRoomName("Screen 01 · IMAX");
        room.setCinema(cinema);

        Movie movie = new Movie();
        movie.setTitle("Interstellar");

        Showtime showtime = new Showtime();
        showtime.setShowtimeId(20L);
        showtime.setRoom(room);
        showtime.setMovie(movie);
        showtime.setStartAt(LocalDateTime.now().plusHours(2));

        BookingSeat seat = new BookingSeat();
        seat.setSeatLabelSnapshot("F7");

        BookingFoodItem bfi = new BookingFoodItem();
        bfi.setProductNameSnapshot("BOX Couple Combo");
        bfi.setVariantNameSnapshot("Caramel L");
        bfi.setQuantity(1);

        Booking booking = new Booking();
        booking.setBookingId(77L);
        booking.setBookingCode("BOX-20261127-7777");
        booking.setBookingStatus("CONFIRMED");
        booking.setGuestName("Walk-in Guest");
        booking.setGuestPhone("0901234567");
        booking.setGuestEmail("guest@boxcinema.vn");
        booking.setTotalAmount(BigDecimal.valueOf(250000));
        booking.setShowtime(showtime);
        booking.setBookingSeats(List.of(seat));
        booking.setBookingFoodItems(List.of(bfi));

        Payment p = new Payment();
        p.setPaymentMethod("CASH");

        when(bookingRepo.findById(77L)).thenReturn(Optional.of(booking));
        when(paymentRepo.findByBooking_BookingId(77L)).thenReturn(Optional.of(p));
        when(qrService.getBookingTicketUrl(77L)).thenReturn("https://box-cinema.onrender.com/t/BOX1.ValidToken_1234567890");

        StaffPosBookingResultDto dto = service.getBookingResult(77L);

        assertEquals("BOX Cinema Royal City", dto.getCinemaName());
        assertEquals("Screen 01 · IMAX", dto.getRoomName());
        assertEquals("BOX Cinema Royal City - Concession Counter", dto.getPickupLocation());
        assertTrue(dto.isEmailSent());
        assertEquals(List.of("F7"), dto.getSeatLabels());
        assertEquals(1, dto.getFoodItems().size());
        assertEquals("BOX Couple Combo", dto.getFoodItems().get(0).getProductName());
        assertEquals("https://box-cinema.onrender.com/t/BOX1.ValidToken_1234567890", dto.getQrToken());
    }
}
