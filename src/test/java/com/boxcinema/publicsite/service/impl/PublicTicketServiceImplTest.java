package com.boxcinema.publicsite.service.impl;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.publicsite.dto.response.PublicTicketDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PublicTicketServiceImplTest {

    private final BookingQrRepository bookingQrRepo = mock(BookingQrRepository.class);
    private final TicketRepository ticketRepo = mock(TicketRepository.class);
    private final BookingCheckinRepository checkinRepo = mock(BookingCheckinRepository.class);
    private final FoodFulfillmentRepository foodRepo = mock(FoodFulfillmentRepository.class);
    private final PaymentRepository paymentRepo = mock(PaymentRepository.class);

    private final PublicTicketServiceImpl service = new PublicTicketServiceImpl(
            bookingQrRepo, ticketRepo, checkinRepo, foodRepo, paymentRepo
    );

    @Test
    void returnsCompleteTicketInfoForValidCryptographicToken() {
        String rawToken = "BOX1.ValidRandomCryptoToken_0123456789ABCDEF";
        String tokenHash = CryptoUtils.sha256(rawToken);
        Booking booking = buildSampleBooking("CONFIRMED", "ACTIVE", LocalDateTime.now().plusHours(3));
        BookingQr qr = new BookingQr(booking, tokenHash, "enc", "ACTIVE");
        qr.setExpiresAt(LocalDateTime.now().plusHours(6));

        Ticket t = new Ticket();
        t.setTicketCode("TKT-20261127-101");
        t.setTicketStatus("VALID");

        when(bookingQrRepo.findByTokenHash(tokenHash)).thenReturn(Optional.of(qr));
        when(ticketRepo.findByBooking_BookingId(10L)).thenReturn(List.of(t));
        when(checkinRepo.findByBooking_BookingId(10L)).thenReturn(Optional.empty());
        when(paymentRepo.findByBooking_BookingId(10L)).thenReturn(Optional.empty());
        when(foodRepo.findByBooking_BookingId(10L)).thenReturn(Optional.empty());

        Optional<PublicTicketDto> result = service.getPublicTicketByToken(rawToken);

        assertTrue(result.isPresent());
        PublicTicketDto dto = result.get();
        assertTrue(dto.isValid());
        assertTrue(dto.isConfirmable());
        assertEquals("VALID", dto.getTicketStatus());
        assertEquals("Interstellar", dto.getMovieTitle());
        assertEquals("https://res.cloudinary.com/demo/interstellar.webp", dto.getMoviePosterUrl());
        assertEquals("BOX Cinema Royal City", dto.getCinemaName());
        assertEquals("72A Nguyen Trai, Hanoi", dto.getCinemaAddress());
        assertEquals("IMAX Laser 1", dto.getRoomName());
        assertEquals(List.of("F7", "F8"), dto.getSeats());
        assertEquals(List.of("TKT-20261127-101"), dto.getTicketCodes());
    }

    @Test
    void rejectsBookingCodeEnumerationAndUnknownTokens() {
        assertTrue(service.getPublicTicketByToken("BOX-20261127-0001").isEmpty());
        assertTrue(service.getPublicTicketByToken("123").isEmpty());
        verifyNoInteractions(bookingQrRepo);

        when(bookingQrRepo.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertTrue(service.getPublicTicketByToken("BOX1.UnknownToken_0123456789ABCDEF").isEmpty());
    }

    @Test
    void marksRefundedCancelledUsedAndExpiredTicketsNotConfirmable() {
        String rawToken = "BOX1.StateCheckToken_0123456789ABCDEF";
        String tokenHash = CryptoUtils.sha256(rawToken);

        // Refunded
        Booking refundedBooking = buildSampleBooking("CONFIRMED", "ACTIVE", LocalDateTime.now().plusHours(2));
        BookingQr qr = new BookingQr(refundedBooking, tokenHash, "enc", "ACTIVE");
        Payment refundedPayment = new Payment();
        refundedPayment.setPaymentStatus("REFUNDED");
        Ticket validTicket = new Ticket();
        validTicket.setTicketCode("TKT-1");
        validTicket.setTicketStatus("VALID");

        when(bookingQrRepo.findByTokenHash(tokenHash)).thenReturn(Optional.of(qr));
        when(ticketRepo.findByBooking_BookingId(10L)).thenReturn(List.of(validTicket));
        when(paymentRepo.findByBooking_BookingId(10L)).thenReturn(Optional.of(refundedPayment));

        PublicTicketDto refundedDto = service.getPublicTicketByToken(rawToken).orElseThrow();
        assertFalse(refundedDto.isValid());
        assertFalse(refundedDto.isConfirmable());
        assertEquals("REFUNDED", refundedDto.getTicketStatus());

        // Cancelled
        when(paymentRepo.findByBooking_BookingId(10L)).thenReturn(Optional.empty());
        refundedBooking.setBookingStatus("CANCELLED");
        PublicTicketDto cancelledDto = service.getPublicTicketByToken(rawToken).orElseThrow();
        assertFalse(cancelledDto.isConfirmable());
        assertEquals("CANCELLED", cancelledDto.getTicketStatus());

        // Expired
        refundedBooking.setBookingStatus("CONFIRMED");
        qr.setExpiresAt(LocalDateTime.now().minusMinutes(5));
        PublicTicketDto expiredDto = service.getPublicTicketByToken(rawToken).orElseThrow();
        assertFalse(expiredDto.isConfirmable());
        assertEquals("EXPIRED", expiredDto.getTicketStatus());

        // Used
        qr.setExpiresAt(LocalDateTime.now().plusHours(3));
        qr.setStatus("USED");
        qr.setUsedAt(LocalDateTime.now().minusMinutes(1));
        PublicTicketDto usedDto = service.getPublicTicketByToken(rawToken).orElseThrow();
        assertFalse(usedDto.isConfirmable());
        assertEquals("USED", usedDto.getTicketStatus());
        assertNotNull(usedDto.getCheckedInAt());
    }

    private static Booking buildSampleBooking(String bookingStatus, String showtimeStatus, LocalDateTime endAt) {
        Movie movie = new Movie();
        movie.setTitle("Interstellar");
        movie.setPosterUrl("https://res.cloudinary.com/demo/interstellar.webp");

        Cinema cinema = new Cinema();
        cinema.setCinemaName("BOX Cinema Royal City");
        cinema.setAddress("72A Nguyen Trai");
        cinema.setCity("Hanoi");

        Room room = new Room();
        room.setRoomName("IMAX Laser 1");
        room.setCinema(cinema);

        Showtime showtime = new Showtime();
        showtime.setMovie(movie);
        showtime.setRoom(room);
        showtime.setStartAt(endAt.minusHours(2));
        showtime.setEndAt(endAt);
        showtime.setStatus(showtimeStatus);

        BookingSeat s1 = new BookingSeat();
        s1.setSeatLabelSnapshot("F7");
        BookingSeat s2 = new BookingSeat();
        s2.setSeatLabelSnapshot("F8");

        Booking booking = new Booking();
        booking.setBookingId(10L);
        booking.setBookingCode("BOX-20261127-1010");
        booking.setBookingStatus(bookingStatus);
        booking.setShowtime(showtime);
        booking.setBookingSeats(List.of(s1, s2));
        return booking;
    }
}
