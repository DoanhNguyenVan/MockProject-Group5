package com.boxcinema.publicsite.service.impl;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.publicsite.dto.response.PublicTicketDto;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

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
    private final UserRepository userRepo = mock(UserRepository.class);
    private final CinemaRepository cinemaRepo = mock(CinemaRepository.class);

    private final PublicTicketServiceImpl service = new PublicTicketServiceImpl(
            bookingQrRepo, ticketRepo, checkinRepo, foodRepo, paymentRepo, userRepo, cinemaRepo
    );

    @Test
    void returnsCompleteTicketInfoForStaffAndOwnerAndRejectsUnauthOrNonOwnerOrWrongBranch() {
        String rawToken = "BOX1.ValidRandomCryptoToken_0123456789ABCDEF";
        String tokenHash = CryptoUtils.sha256(rawToken);
        Booking booking = buildSampleBooking("CONFIRMED", "ACTIVE", LocalDateTime.now().plusHours(3));
        User owner = new User();
        owner.setUserId(10L);
        owner.setFullName("Buyer A");
        owner.setEmail("buyer@boxcinema.vn");
        owner.setRole("CUSTOMER");
        booking.setUser(owner);

        User staffRight = new User();
        staffRight.setUserId(15L);
        staffRight.setRole("STAFF");
        staffRight.setCinemaId(1L);

        User staffWrong = new User();
        staffWrong.setUserId(16L);
        staffWrong.setRole("STAFF");
        staffWrong.setCinemaId(2L);

        Cinema timesCity = new Cinema();
        timesCity.setCinemaId(2L);
        timesCity.setCinemaName("BOX Cinema Times City");

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
        when(userRepo.findById(10L)).thenReturn(Optional.of(owner));
        when(userRepo.findById(15L)).thenReturn(Optional.of(staffRight));
        when(userRepo.findById(16L)).thenReturn(Optional.of(staffWrong));
        when(cinemaRepo.findById(2L)).thenReturn(Optional.of(timesCity));

        // Anonymous rejected
        assertThrows(AccessDeniedException.class, () -> service.getTicketByTokenForViewer(rawToken, null, ""));

        // Other customer rejected
        assertThrows(AccessDeniedException.class, () -> service.getTicketByTokenForViewer(rawToken, 99L, "CUSTOMER"));

        // Wrong-branch staff rejected without ticket data
        AccessDeniedException branchEx = assertThrows(AccessDeniedException.class,
                () -> service.getTicketByTokenForViewer(rawToken, 16L, "STAFF"));
        assertTrue(branchEx.getMessage().startsWith("WRONG_CINEMA_BRANCH|"));

        // Owner sees ticket + QR image, no staff confirmable
        PublicTicketDto ownerDto = service.getTicketByTokenForViewer(rawToken, 10L, "CUSTOMER").orElseThrow();
        assertTrue(ownerDto.isValid());
        assertFalse(ownerDto.isConfirmable());
        assertFalse(ownerDto.isViewerIsStaff());
        assertTrue(ownerDto.isCanDisplayQrImage());

        // Right-branch staff sees full details + buyer + confirmable, WITHOUT QR image
        PublicTicketDto staffDto = service.getTicketByTokenForViewer(rawToken, 15L, "STAFF").orElseThrow();
        assertTrue(staffDto.isValid());
        assertTrue(staffDto.isConfirmable());
        assertTrue(staffDto.isViewerIsStaff());
        assertFalse(staffDto.isCanDisplayQrImage());
        assertEquals("Buyer A", staffDto.getBuyerName());
        assertEquals("VALID", staffDto.getTicketStatus());
        assertEquals("Interstellar", staffDto.getMovieTitle());
        assertEquals("IMAX Laser 1", staffDto.getRoomName());
        assertEquals(List.of("F7", "F8"), staffDto.getSeats());
        assertEquals(List.of("TKT-20261127-101"), staffDto.getTicketCodes());
    }

    @Test
    void rejectsBookingCodeEnumerationAndUnknownTokens() {
        assertTrue(service.getTicketByTokenForViewer("BOX-20261127-0001", 15L, "STAFF").isEmpty());
        assertTrue(service.getTicketByTokenForViewer("123", 15L, "STAFF").isEmpty());
        verifyNoInteractions(bookingQrRepo);

        when(bookingQrRepo.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertTrue(service.getTicketByTokenForViewer("BOX1.UnknownToken_0123456789ABCDEF", 15L, "STAFF").isEmpty());
    }

    @Test
    void enforces30MinutePostShowtimeExpiryAndMarksRefundedCancelledUsedNotConfirmable() {
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

        PublicTicketDto refundedDto = service.getTicketByTokenForViewer(rawToken, 15L, "ADMIN").orElseThrow();
        assertFalse(refundedDto.isValid());
        assertFalse(refundedDto.isConfirmable());
        assertEquals("REFUNDED", refundedDto.getTicketStatus());

        // Cancelled
        when(paymentRepo.findByBooking_BookingId(10L)).thenReturn(Optional.empty());
        refundedBooking.setBookingStatus("CANCELLED");
        PublicTicketDto cancelledDto = service.getTicketByTokenForViewer(rawToken, 15L, "ADMIN").orElseThrow();
        assertFalse(cancelledDto.isConfirmable());
        assertEquals("CANCELLED", cancelledDto.getTicketStatus());

        // Within 30m grace after showtime end -> still VALID
        refundedBooking.setBookingStatus("CONFIRMED");
        refundedBooking.getShowtime().setEndAt(LocalDateTime.now().minusMinutes(15));
        qr.setExpiresAt(LocalDateTime.now().plusHours(3));
        PublicTicketDto graceDto = service.getTicketByTokenForViewer(rawToken, 15L, "ADMIN").orElseThrow();
        assertTrue(graceDto.isValid());
        assertTrue(graceDto.isConfirmable());

        // Past 30m after showtime end (even if DB expiresAt was +4h) -> EXPIRED / TICKET_EXPIRED
        refundedBooking.getShowtime().setEndAt(LocalDateTime.now().minusMinutes(35));
        PublicTicketDto expiredDto = service.getTicketByTokenForViewer(rawToken, 15L, "ADMIN").orElseThrow();
        assertFalse(expiredDto.isConfirmable());
        assertEquals("EXPIRED", expiredDto.getTicketStatus());
        assertEquals("TICKET_EXPIRED", expiredDto.getErrorCode());
        assertEquals("This ticket QR code has expired for the scheduled showtime.", expiredDto.getMessage());

        // Already USED after showtime end -> still reports USED first
        qr.setStatus("USED");
        qr.setUsedAt(LocalDateTime.now().minusHours(2));
        PublicTicketDto usedDto = service.getTicketByTokenForViewer(rawToken, 15L, "ADMIN").orElseThrow();
        assertFalse(usedDto.isConfirmable());
        assertEquals("USED", usedDto.getTicketStatus());
        assertNotNull(usedDto.getCheckedInAt());
    }

    private static Booking buildSampleBooking(String bookingStatus, String showtimeStatus, LocalDateTime endAt) {
        Movie movie = new Movie();
        movie.setTitle("Interstellar");
        movie.setPosterUrl("https://res.cloudinary.com/demo/interstellar.webp");

        Cinema cinema = new Cinema();
        cinema.setCinemaId(1L);
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
