package com.boxcinema.customer.service.impl;

import com.boxcinema.common.dto.BookingTicketViewDto;
import com.boxcinema.common.entity.Booking;
import com.boxcinema.common.entity.EmailOutbox;
import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.EmailOutboxRepository;
import com.boxcinema.common.service.BookingQrService;
import com.boxcinema.common.service.EmailService;
import com.boxcinema.customer.service.TicketService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailOutboxWorkerServiceTest {

    private final EmailOutboxRepository outboxRepo = mock(EmailOutboxRepository.class);
    private final TicketService ticketService = mock(TicketService.class);
    private final BookingQrService qrService = mock(BookingQrService.class);
    private final EmailService emailService = mock(EmailService.class);

    private final EmailOutboxWorkerService worker = new EmailOutboxWorkerService(
            outboxRepo, ticketService, qrService, emailService
    );

    @Test
    void dispatchesPendingBookingEmailViaEmailServiceWithRenderUrlAndQrBytes() {
        EmailOutbox outbox = sampleOutbox(1L, 55L, "customer@gmail.com");
        BookingTicketViewDto view = sampleView(55L, "BOX-20261127-1234");
        byte[] qrBytes = new byte[]{1, 2, 3};

        when(outboxRepo.findByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(List.of(outbox));
        when(outboxRepo.findByStatusAndRetryCountLessThan("RETRYING", 3)).thenReturn(Collections.emptyList());
        when(ticketService.getTicketView(55L, 9L)).thenReturn(view);
        when(qrService.getBookingQrPngBytes(55L)).thenReturn(qrBytes);
        when(qrService.getBookingTicketUrl(55L)).thenReturn("https://box-cinema.onrender.com/t/BOX1.Token");
        when(emailService.isMailTransportConfigured()).thenReturn(true);
        when(emailService.sendTicketConfirmationEmail(
                eq("customer@gmail.com"),
                contains("#BOX-20261127-1234"),
                contains("https://box-cinema.onrender.com/t/BOX1.Token"),
                eq(qrBytes)
        )).thenReturn(true);

        worker.processOutbox();

        assertEquals("SENT", outbox.getStatus());
        assertNotNull(outbox.getSentAt());
        assertNull(outbox.getLastError());
        verify(outboxRepo).save(outbox);
    }

    @Test
    void marksRetryingAndFailedWhenMailTransportFails() {
        EmailOutbox outbox = sampleOutbox(2L, 56L, "customer@gmail.com");
        outbox.setRetryCount(2);
        BookingTicketViewDto view = sampleView(56L, "BOX-20261127-9999");

        when(outboxRepo.findByStatusOrderByCreatedAtAsc("PENDING")).thenReturn(Collections.emptyList());
        when(outboxRepo.findByStatusAndRetryCountLessThan("RETRYING", 3)).thenReturn(List.of(outbox));
        when(ticketService.getTicketView(56L, 9L)).thenReturn(view);
        when(qrService.getBookingQrPngBytes(56L)).thenReturn(new byte[]{1, 2});
        when(qrService.getBookingTicketUrl(56L)).thenReturn("https://box-cinema.onrender.com/t/BOX1.Token2");
        when(emailService.isMailTransportConfigured()).thenReturn(true);
        when(emailService.sendTicketConfirmationEmail(anyString(), anyString(), anyString(), any())).thenReturn(false);

        worker.processOutbox();

        assertEquals("FAILED", outbox.getStatus());
        assertEquals(3, outbox.getRetryCount());
        assertNotNull(outbox.getLastError());
        verify(outboxRepo).save(outbox);
    }

    private static EmailOutbox sampleOutbox(Long id, Long bookingId, String email) {
        User u = new User();
        u.setUserId(9L);
        Booking b = new Booking();
        b.setBookingId(bookingId);
        b.setUser(u);
        EmailOutbox o = new EmailOutbox(b, email, "BOOKING_CONFIRMATION");
        o.setId(id);
        return o;
    }

    private static BookingTicketViewDto sampleView(Long bookingId, String code) {
        BookingTicketViewDto dto = new BookingTicketViewDto();
        dto.setBookingId(bookingId);
        dto.setBookingCode(code);
        dto.setMovieTitle("Interstellar");
        dto.setCinemaName("BOX Cinema Royal City");
        dto.setRoomName("IMAX 1");
        dto.setShowtimeStart("19:30 - 27/11/2026");
        dto.setTotalAmount(new BigDecimal("180000"));
        return dto;
    }
}
