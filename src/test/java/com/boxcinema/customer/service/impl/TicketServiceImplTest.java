package com.boxcinema.customer.service.impl;

import com.boxcinema.common.entity.Booking;
import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BookingQrService;
import com.boxcinema.common.service.QrCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TicketServiceImplTest {

    private final BookingRepository bookingRepo = mock(BookingRepository.class);
    private final TicketRepository ticketRepo = mock(TicketRepository.class);
    private final BookingQrRepository bookingQrRepo = mock(BookingQrRepository.class);
    private final BookingQrService bookingQrService = mock(BookingQrService.class);
    private final BookingCheckinRepository checkinRepo = mock(BookingCheckinRepository.class);
    private final FoodFulfillmentRepository foodRepo = mock(FoodFulfillmentRepository.class);
    private final EmailOutboxRepository emailRepo = mock(EmailOutboxRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final QrCodeService qrCodeService = mock(QrCodeService.class);
    private final PaymentRepository paymentRepo = mock(PaymentRepository.class);

    private final TicketServiceImpl service = new TicketServiceImpl(
            bookingRepo, ticketRepo, bookingQrRepo, bookingQrService,
            checkinRepo, foodRepo, emailRepo, userRepo, qrCodeService, paymentRepo
    );

    @Test
    void blocksUnauthenticatedAndNonOwnerAccessToMemberAndGuestBookings() {
        User owner = new User();
        owner.setUserId(10L);
        owner.setRole("CUSTOMER");

        User stranger = new User();
        stranger.setUserId(99L);
        stranger.setRole("CUSTOMER");

        Booking memberBooking = new Booking();
        memberBooking.setBookingId(1L);
        memberBooking.setUser(owner);

        Booking guestBooking = new Booking();
        guestBooking.setBookingId(2L);
        guestBooking.setUser(null);

        when(bookingRepo.findById(1L)).thenReturn(Optional.of(memberBooking));
        when(bookingRepo.findById(2L)).thenReturn(Optional.of(guestBooking));
        when(userRepo.findById(99L)).thenReturn(Optional.of(stranger));
        when(bookingQrService.getBookingQrPngBytes(1L)).thenReturn(new byte[]{1, 2, 3});
        when(bookingQrService.getBookingQrPngBytes(2L)).thenReturn(new byte[]{4, 5, 6});

        // Unauthenticated IDOR attempts on member and guest bookings must be rejected
        assertThrows(AccessDeniedException.class, () -> service.getBookingQrImage(1L, null));
        assertThrows(AccessDeniedException.class, () -> service.getTicketView(1L, null));
        assertThrows(AccessDeniedException.class, () -> service.getBookingQrImage(2L, null));

        // Non-owner authenticated customer must be rejected for both member and guest bookings
        assertThrows(AccessDeniedException.class, () -> service.getBookingQrImage(1L, 99L));
        assertThrows(AccessDeniedException.class, () -> service.getBookingQrImage(2L, 99L));

        // Verified owner succeeds
        assertArrayEquals(new byte[]{1, 2, 3}, service.getBookingQrImage(1L, 10L));

        // Originating guest browser session succeeds
        assertArrayEquals(new byte[]{4, 5, 6}, service.getBookingQrImage(2L, null, Set.of(2L)));
    }
}
