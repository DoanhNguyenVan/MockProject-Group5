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

    @Value("${app.public-base-url:https://absence-turret-chapped.ngrok-free.dev}")
    private String publicBaseUrl;

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

        String base = (publicBaseUrl != null && !publicBaseUrl.isBlank()) ? publicBaseUrl.replaceAll("/+$", "") : "http://localhost:8080";
        String ticketUrl = base + "/t/" + rawToken;

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
            bQr.setExpiresAt(booking.getShowtime().getEndAt().plusHours(4));
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
        byte[] png = reconstructAndPersistQrPng(bQr, filePath);
        return png;
    }

    @Override
    public String getBookingTicketUrl(Long bookingId) {
        BookingQr bQr = getOrCreateBookingQr(bookingId);
        return bQr.getTicketUrl();
    }

    private void ensureQrFileExists(BookingQr bQr) {
        boolean needsSave = false;

        // Ensure ticketUrl is populated
        if (bQr.getTicketUrl() == null || bQr.getTicketUrl().isBlank()) {
            String rawToken = com.boxcinema.common.util.CryptoUtils.decryptAes(bQr.getTokenEncrypted(), checksumKey);
            if (rawToken != null && !rawToken.isBlank()) {
                String base = (publicBaseUrl != null && !publicBaseUrl.isBlank()) ? publicBaseUrl.replaceAll("/+$", "") : "http://localhost:8080";
                bQr.setTicketUrl(base + "/t/" + rawToken);
                needsSave = true;
            }
        }

        // Ensure file exists on disk
        Path filePath = (bQr.getQrImagePath() != null && !bQr.getQrImagePath().isBlank())
                ? Paths.get(bQr.getQrImagePath())
                : resolveFilePath(bQr.getBooking().getBookingId());

        if (!Files.exists(filePath)) {
            reconstructAndPersistQrPng(bQr, filePath);
            bQr.setQrImagePath(filePath.toAbsolutePath().normalize().toString());
            needsSave = true;
        }

        if (needsSave) {
            bookingQrRepository.save(bQr);
        }
    }

    private byte[] reconstructAndPersistQrPng(BookingQr bQr, Path targetPath) {
        String url = bQr.getTicketUrl();
        if (url == null || url.isBlank()) {
            String rawToken = com.boxcinema.common.util.CryptoUtils.decryptAes(bQr.getTokenEncrypted(), checksumKey);
            String base = (publicBaseUrl != null && !publicBaseUrl.isBlank()) ? publicBaseUrl.replaceAll("/+$", "") : "http://localhost:8080";
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

    private Path resolveFilePath(Long bookingId) {
        return Paths.get(storageDir, "qr-booking-" + bookingId + ".png").toAbsolutePath().normalize();
    }
}
