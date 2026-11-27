package com.boxcinema.common.service.impl;

import com.boxcinema.common.entity.Booking;
import com.boxcinema.common.entity.BookingQr;
import com.boxcinema.common.entity.Showtime;
import com.boxcinema.common.repository.BookingQrRepository;
import com.boxcinema.common.repository.BookingRepository;
import com.boxcinema.common.service.QrCodeService;
import com.boxcinema.common.util.CryptoUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookingQrServiceImplTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesTicketQrWithConfiguredRenderHttpsUrlAndUnpredictableToken() {
        BookingQrRepository qrRepo = mock(BookingQrRepository.class);
        BookingRepository bookingRepo = mock(BookingRepository.class);
        QrCodeService qrCodeService = new QrCodeServiceImpl();

        Booking booking = confirmedBooking(42L);
        when(qrRepo.findByBooking_BookingId(42L)).thenReturn(Optional.empty());
        when(bookingRepo.findByIdForUpdate(42L)).thenReturn(Optional.of(booking));
        when(qrRepo.save(any(BookingQr.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingQrServiceImpl service = new BookingQrServiceImpl(qrRepo, bookingRepo, qrCodeService);
        ReflectionTestUtils.setField(service, "ticketPublicBaseUrl", "https://box-cinema.onrender.com/");
        ReflectionTestUtils.setField(service, "storageDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "checksumKey", "test-checksum-secret-2026");

        BookingQr created = service.getOrCreateBookingQr(42L);

        assertNotNull(created.getTicketUrl());
        assertTrue(created.getTicketUrl().startsWith("https://box-cinema.onrender.com/t/BOX1."));
        assertFalse(created.getTicketUrl().toLowerCase().contains("ngrok"));
        assertFalse(created.getTicketUrl().toLowerCase().contains("localhost"));
        assertNotNull(created.getTokenHash());
        assertEquals(64, created.getTokenHash().length());
        assertTrue(Files.exists(Path.of(created.getQrImagePath())));
    }

    @Test
    void generatesCryptographicallyUniqueTokensAcrossTickets() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String token = CryptoUtils.generateBookingQrToken();
            assertTrue(token.startsWith("BOX1."));
            assertTrue(token.length() >= 45);
            assertTrue(tokens.add(token));
        }
    }

    @Test
    void migratesLegacyNgrokTicketUrlToRenderHttpsWhilePreservingExistingToken() {
        BookingQrRepository qrRepo = mock(BookingQrRepository.class);
        BookingRepository bookingRepo = mock(BookingRepository.class);
        QrCodeService qrCodeService = new QrCodeServiceImpl();

        Booking booking = confirmedBooking(77L);
        String rawToken = "BOX1.LegacyExistingRandomToken_1234567890ABCDEF";
        String checksum = "test-checksum-secret-2026";
        BookingQr legacyQr = new BookingQr(
                booking,
                CryptoUtils.sha256(rawToken),
                CryptoUtils.encryptAes(rawToken, checksum),
                "ACTIVE"
        );
        legacyQr.setTicketUrl("https://absence-turret-chapped.ngrok-free.dev/t/" + rawToken);

        when(qrRepo.findByBooking_BookingId(77L)).thenReturn(Optional.of(legacyQr));
        when(qrRepo.save(any(BookingQr.class))).thenAnswer(inv -> inv.getArgument(0));

        BookingQrServiceImpl service = new BookingQrServiceImpl(qrRepo, bookingRepo, qrCodeService);
        ReflectionTestUtils.setField(service, "ticketPublicBaseUrl", "https://box-cinema.onrender.com");
        ReflectionTestUtils.setField(service, "storageDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "checksumKey", checksum);

        byte[] png = service.getBookingQrPngBytes(77L);

        assertNotNull(png);
        assertTrue(png.length > 100);
        assertEquals("https://box-cinema.onrender.com/t/" + rawToken, legacyQr.getTicketUrl());
        assertEquals(CryptoUtils.sha256(rawToken), legacyQr.getTokenHash());
        verify(qrRepo).save(legacyQr);
    }

    @Test
    void rejectsNgrokConfigurationForTicketQrUrl() {
        BookingQrRepository qrRepo = mock(BookingQrRepository.class);
        BookingRepository bookingRepo = mock(BookingRepository.class);
        BookingQrServiceImpl service = new BookingQrServiceImpl(qrRepo, bookingRepo, new QrCodeServiceImpl());
        ReflectionTestUtils.setField(service, "ticketPublicBaseUrl", "https://bad.ngrok-free.dev");
        ReflectionTestUtils.setField(service, "storageDir", tempDir.toString());

        Booking booking = confirmedBooking(99L);
        when(qrRepo.findByBooking_BookingId(99L)).thenReturn(Optional.empty());
        when(bookingRepo.findByIdForUpdate(99L)).thenReturn(Optional.of(booking));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.getOrCreateBookingQr(99L));
        assertTrue(ex.getMessage().contains("Ngrok"));
    }

    private static Booking confirmedBooking(Long id) {
        Booking b = new Booking();
        b.setBookingId(id);
        b.setBookingCode("BOX-20261127-00" + id);
        b.setBookingStatus("CONFIRMED");
        Showtime st = new Showtime();
        st.setStartAt(LocalDateTime.now().plusHours(2));
        st.setEndAt(LocalDateTime.now().plusHours(4));
        b.setShowtime(st);
        return b;
    }
}
