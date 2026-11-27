package com.boxcinema.common.service.impl;

import com.boxcinema.common.entity.Booking;
import com.boxcinema.common.entity.BookingQr;
import com.boxcinema.common.repository.BookingQrRepository;
import com.boxcinema.common.repository.BookingRepository;
import com.boxcinema.common.service.BookingQrService;
import com.boxcinema.common.service.QrCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@Transactional
public class BookingQrServiceImpl implements BookingQrService {

    private static final Logger log = LoggerFactory.getLogger(BookingQrServiceImpl.class);

    private final BookingQrRepository bookingQrRepository;
    private final BookingRepository bookingRepository;
    private final QrCodeService qrCodeService;

    @Value("${app.ticket.public-base-url:${app.public-base-url:}}")
    private String ticketPublicBaseUrl;

    @Value("${app.qr.storage-dir:./uploads/booking-qr}")
    private String storageDir;

    @Value("${payos.checksum-key:BOX_CINEMA_DEFAULT_SECURE_PAYOS_KEY_2026_@!#}")
    private String checksumKey;

    public BookingQrServiceImpl(
            BookingQrRepository bookingQrRepository,
            BookingRepository bookingRepository,
            QrCodeService qrCodeService
    ) {
        this.bookingQrRepository = bookingQrRepository;
        this.bookingRepository = bookingRepository;
        this.qrCodeService = qrCodeService;
    }

    @Override
    public BookingQr getOrCreateBookingQr(Long bookingId) {
        if (bookingId == null) {
            throw new IllegalArgumentException("Booking ID cannot be null.");
        }

        // 1. Check if Booking QR already exists in DB
        Optional<BookingQr> existingOpt = bookingQrRepository.findByBooking_BookingId(bookingId);
        if (existingOpt.isPresent()) {
            BookingQr existing = existingOpt.get();
            ensureQrFileExists(existing);
            return existing;
        }

        // 2. Fetch booking and verify confirmation
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found with id: " + bookingId));

        if (!"CONFIRMED".equalsIgnoreCase(booking.getBookingStatus())) {
            throw new IllegalStateException("QR code can only be issued for confirmed bookings (CONFIRMED).");
        }

        // 3. Double-check inside locked transaction
        existingOpt = bookingQrRepository.findByBooking_BookingId(bookingId);
        if (existingOpt.isPresent()) {
            BookingQr existing = existingOpt.get();
            ensureQrFileExists(existing);
            return existing;
        }

        // 4. Generate 1 single secure random token (256-bit entropy, BOX1. prefix)
        String rawToken = com.boxcinema.common.util.CryptoUtils.generateBookingQrToken();
        String tokenHash = com.boxcinema.common.util.CryptoUtils.sha256(rawToken);
        String tokenEncrypted = com.boxcinema.common.util.CryptoUtils.encryptAes(rawToken, checksumKey);

        String ticketUrl = buildTicketUrl(rawToken);

        // 5. Generate ZXing PNG bytes
        byte[] pngBytes = qrCodeService.generateQrPng(ticketUrl, 300, 300);

        // 6. Write file to shared disk storage
        Path filePath = resolveFilePath(bookingId);
        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, pngBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Saved unified Booking QR PNG file to {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to save QR file to disk: {}", e.getMessage(), e);
            throw new RuntimeException("Could not save QR code image file.", e);
        }

        // 7. Persist BookingQr metadata to MySQL
        BookingQr bQr = new BookingQr(booking, tokenHash, tokenEncrypted, "ACTIVE");
        bQr.setTicketUrl(ticketUrl);
        bQr.setQrImagePath(filePath.toAbsolutePath().normalize().toString());
        bQr.setCreatedAt(LocalDateTime.now());
        if (booking.getShowtime() != null && booking.getShowtime().getEndAt() != null) {
            bQr.setExpiresAt(booking.getShowtime().getEndAt().plusMinutes(30));
        }

        return bookingQrRepository.save(bQr);
    }

    @Override
    public byte[] getBookingQrPngBytes(Long bookingId) {
        BookingQr bQr = getOrCreateBookingQr(bookingId);

        if ("REVOKED".equalsIgnoreCase(bQr.getStatus())) {
            throw new IllegalStateException("QR code has been revoked.");
        }

        // Read file directly from disk
        Path filePath = (bQr.getQrImagePath() != null && !bQr.getQrImagePath().isBlank())
                ? Paths.get(bQr.getQrImagePath())
                : resolveFilePath(bookingId);

        if (Files.exists(filePath)) {
            try {
                return Files.readAllBytes(filePath);
            } catch (IOException e) {
                log.warn("Failed reading existing QR file {}. Attempting recovery...", filePath, e);
            }
        }

        // Recovery path: Reconstruct the EXACT same PNG from the existing token/ticketUrl (never generate a new token!)
        return reconstructAndPersistQrPng(bQr, filePath);
    }

    @Override
    public String getBookingTicketUrl(Long bookingId) {
        BookingQr bQr = getOrCreateBookingQr(bookingId);
        return bQr.getTicketUrl();
    }

    private void ensureQrFileExists(BookingQr bQr) {
        boolean urlMigrated = false;
        String base = resolveTicketBaseUrl();

        // Populate missing URL or safely migrate legacy Ngrok/localhost URLs to active Render HTTPS base URL
        String currentUrl = bQr.getTicketUrl();
        if (currentUrl == null || currentUrl.isBlank() || shouldMigrateTicketUrl(currentUrl, base)) {
            String rawToken = decryptTokenSafely(bQr);
            if (rawToken != null && !rawToken.isBlank()) {
                bQr.setTicketUrl(base + "/t/" + rawToken);
                urlMigrated = true;
            }
        }

        // Ensure file exists on disk (or overwrite stale PNG if ticketUrl just migrated off Ngrok)
        Path filePath = (bQr.getQrImagePath() != null && !bQr.getQrImagePath().isBlank())
                ? Paths.get(bQr.getQrImagePath())
                : resolveFilePath(bQr.getBooking().getBookingId());

        if (urlMigrated || !Files.exists(filePath)) {
            reconstructAndPersistQrPng(bQr, filePath);
            bQr.setQrImagePath(filePath.toAbsolutePath().normalize().toString());
            bookingQrRepository.save(bQr);
        }
    }

    private byte[] reconstructAndPersistQrPng(BookingQr bQr, Path targetPath) {
        String url = bQr.getTicketUrl();
        String base = resolveTicketBaseUrl();
        if (url == null || url.isBlank() || shouldMigrateTicketUrl(url, base)) {
            String rawToken = decryptTokenSafely(bQr);
            if (rawToken == null || rawToken.isBlank()) {
                throw new IllegalStateException("Cannot reconstruct QR code without a valid encrypted token.");
            }
            url = base + "/t/" + rawToken;
            bQr.setTicketUrl(url);
        }

        byte[] pngBytes = qrCodeService.generateQrPng(url, 300, 300);
        try {
            Files.createDirectories(targetPath.getParent());
            Files.write(targetPath, pngBytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Reconstructed and saved QR PNG file to {}", targetPath.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to write reconstructed QR file to disk: {}", e.getMessage(), e);
        }
        return pngBytes;
    }

    private String resolveTicketBaseUrl() {
        String raw = ticketPublicBaseUrl != null ? ticketPublicBaseUrl.trim() : "";
        if (raw.isEmpty()) {
            throw new IllegalStateException("Ticket public base URL (app.ticket.public-base-url / TICKET_PUBLIC_BASE_URL) must be configured.");
        }
        String normalized = raw.replaceAll("/+$", "");
        String lower = normalized.toLowerCase(java.util.Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            throw new IllegalStateException("Ticket public base URL must start with https:// or http://.");
        }
        if (lower.contains("ngrok")) {
            throw new IllegalStateException("Ngrok URLs are disallowed for ticket QR verification. Configure TICKET_PUBLIC_BASE_URL to your Render HTTPS URL.");
        }
        return normalized;
    }

    private String buildTicketUrl(String rawToken) {
        return resolveTicketBaseUrl() + "/t/" + rawToken;
    }

    private boolean shouldMigrateTicketUrl(String existingUrl, String targetBaseUrl) {
        String lowerExisting = existingUrl.trim().toLowerCase(java.util.Locale.ROOT);
        if (lowerExisting.contains("ngrok")) {
            return true;
        }
        String lowerTarget = targetBaseUrl.toLowerCase(java.util.Locale.ROOT);
        return lowerTarget.startsWith("https://")
                && !lowerTarget.contains("localhost")
                && !lowerTarget.contains("127.0.0.1")
                && (lowerExisting.startsWith("http://localhost") || lowerExisting.startsWith("http://127.0.0.1"));
    }

    private String decryptTokenSafely(BookingQr bQr) {
        if (bQr.getTokenEncrypted() == null || bQr.getTokenEncrypted().isBlank()) {
            return null;
        }
        return com.boxcinema.common.util.CryptoUtils.decryptAes(bQr.getTokenEncrypted(), checksumKey);
    }

    private Path resolveFilePath(Long bookingId) {
        return Paths.get(storageDir, "qr-booking-" + bookingId + ".png").toAbsolutePath().normalize();
    }
}
