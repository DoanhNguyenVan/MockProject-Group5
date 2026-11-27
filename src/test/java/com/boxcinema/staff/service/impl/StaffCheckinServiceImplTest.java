package com.boxcinema.staff.service.impl;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BookingService;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.staff.dto.response.StaffCheckinResponseDto;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StaffCheckinServiceImplTest {

    private final BookingQrRepository qrRepo = mock(BookingQrRepository.class);
    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final TicketRepository ticketRepo = mock(TicketRepository.class);
    private final BookingCheckinRepository checkinRepo = mock(BookingCheckinRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final BookingService bookingService = mock(BookingService.class);
    private final PaymentRepository paymentRepo = mock(PaymentRepository.class);

    private final StaffCheckinServiceImpl service = new StaffCheckinServiceImpl(
            qrRepo, bookingRepo, ticketRepo, checkinRepo, userRepo, bookingService, paymentRepo
    );

    @Test
    void confirmsValidTicketAtomicallyAndSavesCheckin() {
        User staff = user(5L, "STAFF", "Gate Staff");
        String rawToken = "BOX1.GateValidToken_0123456789ABCDEF";
        String tokenHash = CryptoUtils.sha256(rawToken);
        Booking booking = buildBooking(100L, "CONFIRMED", "ACTIVE", LocalDateTime.now().plusHours(3));
        BookingQr qr = new BookingQr(booking, tokenHash, "enc", "ACTIVE");
        qr.setId(88L);
        qr.setExpiresAt(LocalDateTime.now().plusHours(6));

        Ticket ticket = new Ticket();
        ticket.setTicketId(300L);
        ticket.setBookingSeatId(500L);
        ticket.setTicketCode("TKT-300");
        ticket.setTicketStatus("VALID");

        when(userRepo.findById(5L)).thenReturn(Optional.of(staff));
        when(qrRepo.findByTokenHashForUpdate(tokenHash)).thenReturn(Optional.of(qr));
        when(bookingRepo.findByIdForUpdate(100L)).thenReturn(Optional.of(booking));
        when(checkinRepo.findByBooking_BookingId(100L)).thenReturn(Optional.empty());
        when(paymentRepo.findByBooking_BookingId(100L)).thenReturn(Optional.empty());
        when(ticketRepo.findByBookingIdForUpdate(100L)).thenReturn(List.of(ticket));
        when(qrRepo.markUsedIfActive(eq(88L), any(LocalDateTime.class))).thenReturn(1);
        when(ticketRepo.markBookingTicketsUsed(eq(100L), any(LocalDateTime.class), eq(staff))).thenReturn(1);

        StaffCheckinResponseDto res = service.checkinByQrToken(rawToken, 5L);

        assertTrue(res.isSuccess());
        assertEquals("SUCCESS", res.getCode());
        assertEquals("USED", res.getCheckInStatus());
        assertEquals("Gate Staff", res.getStaffName());
        assertEquals(List.of("B5"), res.getSeats());
        assertNotNull(res.getCheckedInAt());
        verify(checkinRepo).saveAndFlush(any(BookingCheckin.class));
    }

    @Test
    void rejectsUnauthorizedCustomerOrUnauthenticatedUser() {
        User customer = user(9L, "CUSTOMER", "Customer");
        when(userRepo.findById(9L)).thenReturn(Optional.of(customer));

        assertThrows(AccessDeniedException.class, () -> service.checkinByQrToken("BOX1.Token_0123456789ABCDEF", null));
        assertThrows(AccessDeniedException.class, () -> service.checkinByQrToken("BOX1.Token_0123456789ABCDEF", 9L));
        verifyNoInteractions(qrRepo, bookingRepo, ticketRepo, checkinRepo);
    }

    @Test
    void rejectsDuplicateAndConcurrentCheckinAttempts() {
        User staff = user(5L, "STAFF", "Gate Staff");
        String rawToken = "BOX1.RaceConditionToken_0123456789ABCDEF";
        String tokenHash = CryptoUtils.sha256(rawToken);
        Booking booking = buildBooking(101L, "CONFIRMED", "ACTIVE", LocalDateTime.now().plusHours(3));
        BookingQr qr = new BookingQr(booking, tokenHash, "enc", "ACTIVE");
        qr.setId(89L);
        qr.setExpiresAt(LocalDateTime.now().plusHours(6));
        Ticket ticket = new Ticket();
        ticket.setTicketId(301L);
        ticket.setTicketStatus("VALID");

        when(userRepo.findById(5L)).thenReturn(Optional.of(staff));
        when(qrRepo.findByTokenHashForUpdate(tokenHash)).thenReturn(Optional.of(qr));
        when(bookingRepo.findByIdForUpdate(101L)).thenReturn(Optional.of(booking));
        when(checkinRepo.findByBooking_BookingId(101L)).thenReturn(Optional.empty());
        when(paymentRepo.findByBooking_BookingId(101L)).thenReturn(Optional.empty());
        when(ticketRepo.findByBookingIdForUpdate(101L)).thenReturn(List.of(ticket));
        // Simulate another concurrent staff thread already flipped ACTIVE -> USED
        when(qrRepo.markUsedIfActive(eq(89L), any(LocalDateTime.class))).thenReturn(0);

        StaffCheckinResponseDto res = service.checkinByQrToken(rawToken, 5L);

        assertFalse(res.isSuccess());
        assertEquals("BOOKING_ALREADY_CHECKED_IN", res.getCode());
        verify(ticketRepo, never()).markBookingTicketsUsed(anyLong(), any(), any());
        verify(checkinRepo, never()).saveAndFlush(any());
    }

    @Test
    void rejectsRefundedCancelledAndExpiredTickets() {
        User staff = user(5L, "MANAGER", "Floor Manager");
        String rawToken = "BOX1.InvalidStateToken_0123456789ABCDEF";
        String tokenHash = CryptoUtils.sha256(rawToken);
        Booking booking = buildBooking(102L, "CONFIRMED", "ACTIVE", LocalDateTime.now().plusHours(3));
        BookingQr qr = new BookingQr(booking, tokenHash, "enc", "ACTIVE");
        qr.setId(90L);
        qr.setExpiresAt(LocalDateTime.now().plusHours(6));

        when(userRepo.findById(5L)).thenReturn(Optional.of(staff));
        when(qrRepo.findByTokenHashForUpdate(tokenHash)).thenReturn(Optional.of(qr));
        when(bookingRepo.findByIdForUpdate(102L)).thenReturn(Optional.of(booking));
        when(checkinRepo.findByBooking_BookingId(102L)).thenReturn(Optional.empty());

        // Refunded
        Payment payment = new Payment();
        payment.setPaymentStatus("REFUNDED");
        when(paymentRepo.findByBooking_BookingId(102L)).thenReturn(Optional.of(payment));
        StaffCheckinResponseDto refunded = service.checkinByQrToken(rawToken, 5L);
        assertFalse(refunded.isSuccess());
        assertEquals("BOOKING_REFUNDED", refunded.getCode());

        // Cancelled
        when(paymentRepo.findByBooking_BookingId(102L)).thenReturn(Optional.empty());
        booking.setBookingStatus("CANCELLED");
        StaffCheckinResponseDto cancelled = service.checkinByQrToken(rawToken, 5L);
        assertFalse(cancelled.isSuccess());
        assertEquals("BOOKING_CANCELLED", cancelled.getCode());

        // Expired
        booking.setBookingStatus("CONFIRMED");
        qr.setExpiresAt(LocalDateTime.now().minusMinutes(10));
        StaffCheckinResponseDto expired = service.checkinByQrToken(rawToken, 5L);
        assertFalse(expired.isSuccess());
        assertEquals("TICKET_EXPIRED", expired.getCode());
    }

    @Test
    void propagatesExceptionWhenDatabaseCheckinInsertFailsWithoutReturningFakeSuccess() {
        User staff = user(5L, "STAFF", "Gate Staff");
        String rawToken = "BOX1.DbFailureToken_0123456789ABCDEF";
        String tokenHash = CryptoUtils.sha256(rawToken);
        Booking booking = buildBooking(103L, "CONFIRMED", "ACTIVE", LocalDateTime.now().plusHours(3));
        BookingQr qr = new BookingQr(booking, tokenHash, "enc", "ACTIVE");
        qr.setId(91L);
        qr.setExpiresAt(LocalDateTime.now().plusHours(6));
        Ticket ticket = new Ticket();
        ticket.setTicketId(302L);
        ticket.setBookingSeatId(500L);
        ticket.setTicketStatus("VALID");

        when(userRepo.findById(5L)).thenReturn(Optional.of(staff));
        when(qrRepo.findByTokenHashForUpdate(tokenHash)).thenReturn(Optional.of(qr));
        when(bookingRepo.findByIdForUpdate(103L)).thenReturn(Optional.of(booking));
        when(checkinRepo.findByBooking_BookingId(103L)).thenReturn(Optional.empty());
        when(paymentRepo.findByBooking_BookingId(103L)).thenReturn(Optional.empty());
        when(ticketRepo.findByBookingIdForUpdate(103L)).thenReturn(List.of(ticket));
        when(qrRepo.markUsedIfActive(eq(91L), any(LocalDateTime.class))).thenReturn(1);
        when(ticketRepo.markBookingTicketsUsed(eq(103L), any(LocalDateTime.class), eq(staff))).thenReturn(1);
        when(checkinRepo.saveAndFlush(any(BookingCheckin.class))).thenThrow(new RuntimeException("DB write error"));

        assertThrows(RuntimeException.class, () -> service.checkinByQrToken(rawToken, 5L));
    }

    private static User user(Long id, String role, String name) {
        User u = new User();
        u.setUserId(id);
        u.setRole(role);
        u.setFullName(name);
        return u;
    }

    private static Booking buildBooking(Long id, String bookingStatus, String showtimeStatus, LocalDateTime endAt) {
        Showtime st = new Showtime();
        st.setStartAt(endAt.minusHours(2));
        st.setEndAt(endAt);
        st.setStatus(showtimeStatus);

        BookingSeat bs = new BookingSeat();
        bs.setBookingSeatId(500L);
        bs.setSeatLabelSnapshot("B5");

        Booking b = new Booking();
        b.setBookingId(id);
        b.setBookingCode("BOX-20261127-9001");
        b.setBookingStatus(bookingStatus);
        b.setShowtime(st);
        b.setBookingSeats(List.of(bs));
        return b;
    }
}
