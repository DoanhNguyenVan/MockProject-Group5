package com.boxcinema.customer.service.impl;

import com.boxcinema.common.dto.BookingTicketViewDto;
import com.boxcinema.common.entity.EmailOutbox;
import com.boxcinema.common.repository.EmailOutboxRepository;
import com.boxcinema.common.service.BookingQrService;
import com.boxcinema.customer.service.TicketService;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Service
public class EmailOutboxWorkerService {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxWorkerService.class);
    private static final int CLAIM_BATCH_SIZE = 50;
    private static final int MAX_RETRIES = 3;
    private static final long DEFAULT_LEASE_TIMEOUT_SECONDS = 300L;
    private static final String PROCESSING_LEASE_PREFIX = "PROCESSING_LEASE:";

    private final EmailOutboxRepository emailOutboxRepository;
    private final TicketService ticketService;
    private final BookingQrService bookingQrService;
    private final JavaMailSender mailSender;
    private final TransactionTemplate txTemplate;
    private final TransactionTemplate readOnlyTxTemplate;
    private volatile long leaseTimeoutSeconds = DEFAULT_LEASE_TIMEOUT_SECONDS;

    @Value("${spring.mail.username:}")
    private String fromEmail;

    @Value("${app.mail.from-name:BOX Cinema}")
    private String fromName;

    public EmailOutboxWorkerService(
            EmailOutboxRepository emailOutboxRepository,
            TicketService ticketService,
            BookingQrService bookingQrService,
            @Autowired(required = false) JavaMailSender mailSender
    ) {
        this(emailOutboxRepository, ticketService, bookingQrService, mailSender, null);
    }

    @Autowired
    public EmailOutboxWorkerService(
            EmailOutboxRepository emailOutboxRepository,
            TicketService ticketService,
            BookingQrService bookingQrService,
            @Autowired(required = false) JavaMailSender mailSender,
            @Autowired(required = false) PlatformTransactionManager transactionManager
    ) {
        this.emailOutboxRepository = emailOutboxRepository;
        this.ticketService = ticketService;
        this.bookingQrService = bookingQrService;
        this.mailSender = mailSender;
        if (transactionManager != null) {
            TransactionTemplate writeTpl = new TransactionTemplate(transactionManager);
            writeTpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.txTemplate = writeTpl;

            TransactionTemplate readTpl = new TransactionTemplate(transactionManager);
            readTpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            readTpl.setReadOnly(true);
            this.readOnlyTxTemplate = readTpl;
        } else {
            this.txTemplate = null;
            this.readOnlyTxTemplate = null;
        }
    }

    public void setLeaseTimeoutSecondsForTesting(long seconds) {
        this.leaseTimeoutSeconds = Math.max(0L, seconds);
    }

    @Scheduled(fixedDelay = 5000)
    public void processOutbox() {
        runInTx(txTemplate, this::recoverStaleProcessingLeasesTx);
        for (int batch = 0; batch < 20; batch++) {
            List<Long> claimedIds = runInTx(txTemplate, this::claimNextBatchTx);
            if (claimedIds == null || claimedIds.isEmpty()) {
                return;
            }
            boolean hadFailure = false;
            for (Long id : claimedIds) {
                if (!processClaimedItem(id)) {
                    hadFailure = true;
                }
            }
            if (hadFailure || claimedIds.size() < CLAIM_BATCH_SIZE) {
                return;
            }
        }
    }

    private <T> T runInTx(TransactionTemplate tpl, Supplier<T> action) {
        if (tpl == null) {
            return action.get();
        }
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return tpl.execute(status -> action.get());
            } catch (org.springframework.dao.CannotAcquireLockException | org.springframework.dao.DeadlockLoserDataAccessException ex) {
                if (attempt == maxAttempts) {
                    throw ex;
                }
                log.debug("Transient DB lock contention in EmailOutboxWorkerService (attempt {}/{}), retrying...", attempt, maxAttempts);
                try {
                    Thread.sleep(15L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        return null;
    }

    private int recoverStaleProcessingLeasesTx() {
        List<Long> processingIds = emailOutboxRepository.findProcessingIdsForUpdateSkipLocked(CLAIM_BATCH_SIZE);
        if (processingIds.isEmpty()) {
            return 0;
        }
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(Math.max(0L, leaseTimeoutSeconds));
        List<EmailOutbox> rows = emailOutboxRepository.findAllById(processingIds);
        List<EmailOutbox> recovered = new ArrayList<>();
        for (EmailOutbox row : rows) {
            if (!"PROCESSING".equalsIgnoreCase(row.getStatus())) {
                continue;
            }
            if (isLeaseStale(row, cutoff)) {
                int nextRetries = row.getRetryCount() + 1;
                row.setRetryCount(nextRetries);
                row.setStatus(nextRetries >= MAX_RETRIES ? "FAILED" : "RETRYING");
                row.setLastError("Recovered stale PROCESSING lease");
                recovered.add(row);
            }
        }
        if (!recovered.isEmpty()) {
            emailOutboxRepository.saveAllAndFlush(recovered);
            log.warn("Recovered {} stale PROCESSING email outbox lease(s).", recovered.size());
        }
        return recovered.size();
    }

    private boolean isLeaseStale(EmailOutbox row, LocalDateTime cutoff) {
        String lastError = row.getLastError();
        if (lastError != null && lastError.startsWith(PROCESSING_LEASE_PREFIX)) {
            String rawTs = lastError.substring(PROCESSING_LEASE_PREFIX.length()).trim();
            try {
                LocalDateTime leaseAt = LocalDateTime.parse(rawTs, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return !leaseAt.isAfter(cutoff);
            } catch (Exception ignored) {
                return true;
            }
        }
        return row.getCreatedAt() == null || !row.getCreatedAt().isAfter(cutoff);
    }

    private List<Long> claimNextBatchTx() {
        List<Long> ids = emailOutboxRepository.findClaimableIdsForUpdateSkipLocked(MAX_RETRIES, CLAIM_BATCH_SIZE);
        if (ids.isEmpty()) {
            return List.of();
        }
        String leaseMarker = PROCESSING_LEASE_PREFIX + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        List<EmailOutbox> rows = emailOutboxRepository.findAllById(ids);
        for (EmailOutbox row : rows) {
            row.setStatus("PROCESSING");
            row.setLastError(leaseMarker);
        }
        emailOutboxRepository.saveAllAndFlush(rows);
        return rows.stream().map(EmailOutbox::getId).toList();
    }

    private record DispatchPayload(
            Long id,
            String recipientEmail,
            String emailType,
            String subject,
            String html,
            byte[] inlineQrPng,
            Long bookingId
    ) {}

    private boolean processClaimedItem(Long outboxId) {
        try {
            DispatchPayload payload = runInTx(readOnlyTxTemplate, () -> prepareDispatchPayload(outboxId));
            if (payload == null) {
                return true;
            }

            if (mailSender == null) {
                log.warn("JavaMailSender is not configured. Simulating {} email dispatch to {}",
                        payload.emailType(), com.boxcinema.auth.service.AuthAuditService.maskEmail(payload.recipientEmail()));
                runInTx(txTemplate, () -> markSentTx(outboxId));
                return true;
            }

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(fromEmail.isBlank() ? "no-reply@boxcinema.vn" : fromEmail, fromName);
            helper.setTo(payload.recipientEmail());
            helper.setSubject(payload.subject());
            helper.setText(payload.html(), true);
            if (payload.inlineQrPng() != null && payload.inlineQrPng().length > 0) {
                helper.addInline("bookingQrCode", new ByteArrayResource(payload.inlineQrPng()), "image/png");
            }
            mailSender.send(message);

            runInTx(txTemplate, () -> markSentTx(outboxId));
            log.info("Outbox email [{}] dispatched successfully to {}",
                    payload.emailType(), com.boxcinema.auth.service.AuthAuditService.maskEmail(payload.recipientEmail()));
            return true;
        } catch (Exception ex) {
            log.error("Failed to send email for outbox id {}: {}", outboxId, ex.getMessage());
            runInTx(txTemplate, () -> markFailedAttemptTx(outboxId, ex.getMessage()));
            return false;
        }
    }

    private DispatchPayload prepareDispatchPayload(Long outboxId) {
        EmailOutbox outbox = emailOutboxRepository.findWithBookingById(outboxId).orElse(null);
        if (outbox == null) {
            return null;
        }
        if (outbox.getSubject() != null && outbox.getBodyHtml() != null) {
            return new DispatchPayload(
                    outbox.getId(),
                    outbox.getRecipientEmail(),
                    outbox.getEmailType(),
                    outbox.getSubject(),
                    outbox.getBodyHtml(),
                    null,
                    outbox.getBooking() != null ? outbox.getBooking().getBookingId() : null
            );
        }
        if (outbox.getBooking() == null) {
            throw new IllegalStateException("Booking reference missing for booking confirmation outbox " + outbox.getId());
        }
        Long bookingId = outbox.getBooking().getBookingId();
        BookingTicketViewDto ticketView = ticketService.getTicketViewForOutbox(bookingId);
        byte[] qrPng = bookingQrService.getBookingQrPngBytes(bookingId);
        String ticketUrl = bookingQrService.getBookingTicketUrl(bookingId);
        return new DispatchPayload(
                outbox.getId(),
                outbox.getRecipientEmail(),
                outbox.getEmailType(),
                "BOX Cinema - Booking Confirmation #" + ticketView.getBookingCode(),
                buildEmailHtml(ticketView, ticketUrl),
                qrPng,
                bookingId
        );
    }

    private Void markSentTx(Long outboxId) {
        EmailOutbox outbox = emailOutboxRepository.findById(outboxId).orElse(null);
        if (outbox != null) {
            outbox.setStatus("SENT");
            outbox.setSentAt(LocalDateTime.now());
            outbox.setLastError(null);
            emailOutboxRepository.save(outbox);
        }
        return null;
    }

    private Void markFailedAttemptTx(Long outboxId, String errorMsg) {
        EmailOutbox outbox = emailOutboxRepository.findById(outboxId).orElse(null);
        if (outbox != null) {
            int retries = outbox.getRetryCount() + 1;
            outbox.setRetryCount(retries);
            outbox.setStatus(retries >= MAX_RETRIES ? "FAILED" : "RETRYING");
            outbox.setLastError(errorMsg);
            emailOutboxRepository.save(outbox);
        }
        return null;
    }

    private String buildEmailHtml(BookingTicketViewDto ticket, String ticketUrl) {
        NumberFormat nf = NumberFormat.getInstance(Locale.US);

        StringBuilder seatsHtml = new StringBuilder();
        if (ticket.getTickets() != null) {
            for (var s : ticket.getTickets()) {
                seatsHtml.append("<span style='display:inline-block;padding:4px 8px;margin:2px;background:#e2e8f0;border-radius:4px;font-weight:600;'>")
                        .append(org.springframework.web.util.HtmlUtils.htmlEscape(s.getSeatLabel() != null ? s.getSeatLabel() : ""))
                        .append("</span> ");
            }
        }

        StringBuilder foodHtml = new StringBuilder();
        if (ticket.getFoodItems() != null && !ticket.getFoodItems().isEmpty()) {
            foodHtml.append("<div style='margin-top:16px;border-top:1px dashed #cbd5e1;padding-top:12px;'>");
            foodHtml.append("<h4 style='margin:0 0 8px;color:#1e293b;'>Concessions & Combos:</h4>");
            foodHtml.append("<ul style='padding-left:20px;margin:0;color:#475569;'>");
            for (var f : ticket.getFoodItems()) {
                foodHtml.append("<li>").append(f.getQuantity()).append("x ")
                        .append(org.springframework.web.util.HtmlUtils.htmlEscape(f.getProductName() != null ? f.getProductName() : ""));
                if (f.getVariantName() != null && !f.getVariantName().isBlank()) {
                    foodHtml.append(" (")
                            .append(org.springframework.web.util.HtmlUtils.htmlEscape(f.getVariantName()))
                            .append(")");
                }
                foodHtml.append(" - ").append(nf.format(f.getUnitPrice().multiply(java.math.BigDecimal.valueOf(f.getQuantity())))).append(" VND</li>");
            }
            foodHtml.append("</ul></div>");
        }

        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"/></head>
            <body style="font-family: Arial, sans-serif; background-color: #f8fafc; padding: 20px; margin: 0;">
                <div style="max-width: 600px; margin: 0 auto; background: #ffffff; border-radius: 12px; overflow: hidden; box-shadow: 0 4px 6px rgba(0,0,0,0.05); border: 1px solid #e2e8f0;">
                    <div style="background: #e11d48; color: #ffffff; padding: 20px; text-align: center;">
                        <h1 style="margin: 0; font-size: 24px; font-weight: bold; letter-spacing: 1px;">BOX CINEMA</h1>
                        <p style="margin: 4px 0 0; opacity: 0.9; font-size: 14px;">E-Ticket & Booking Confirmation</p>
                    </div>
                    
                    <div style="padding: 24px; text-align: center;">
                        <h2 style="color: #0f172a; margin: 0 0 4px;">%s</h2>
                        <p style="color: #64748b; margin: 0 0 16px; font-size: 14px;">Booking Code: <strong>%s</strong></p>
                        
                        <div style="background: #f1f5f9; padding: 16px; border-radius: 8px; margin-bottom: 20px; display: inline-block;">
                            <img src="cid:bookingQrCode" alt="Booking QR Code" width="220" height="220" style="display:block; margin: 0 auto; border-radius: 4px;"/>
                            <p style="margin: 8px 0 0; color: #0284c7; font-weight: 600; font-size: 13px;">QR code for checking in all seats in this booking</p>
                            <div style="margin-top: 12px;">
                                <a href="%s" style="display:inline-block; background-color:#e11d48; color:#ffffff; padding:8px 20px; border-radius:6px; font-weight:bold; font-size:13px; text-decoration:none;">View E-Ticket Online</a>
                            </div>
                            <p style="margin: 10px 0 0; color: #b45309; background: #fef3c7; border: 1px solid #fde68a; padding: 6px 10px; border-radius: 6px; font-size: 11px; line-height: 1.35; text-align: left;">
                                ⚠️ This QR code represents all tickets in this order. Do not share with people outside your booking group.
                            </p>
                        </div>
                        
                        <div style="text-align: left; background: #fafafa; border: 1px solid #f1f5f9; border-radius: 8px; padding: 16px;">
                            <p style="margin: 4px 0;"><strong>Cinema:</strong> %s</p>
                            <p style="margin: 4px 0;"><strong>Hall:</strong> %s</p>
                            <p style="margin: 4px 0;"><strong>Showtime:</strong> %s</p>
                            <p style="margin: 4px 0;"><strong>Seats:</strong> %s</p>
                            %s
                            <div style="margin-top: 16px; border-top: 2px solid #e2e8f0; padding-top: 12px; font-size: 16px;">
                                <strong>Total Amount:</strong> <span style="color: #e11d48; font-weight: bold; font-size: 18px;">%s VND</span>
                            </div>
                        </div>
                        
                        <p style="color: #94a3b8; font-size: 12px; margin-top: 24px; line-height: 1.5;">
                            Please present this QR code at the ticket counter or automated gate at least 15 minutes before showtime.<br/>
                            Enjoy your movie experience at BOX Cinema!
                        </p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(
                org.springframework.web.util.HtmlUtils.htmlEscape(ticket.getMovieTitle() != null ? ticket.getMovieTitle() : "Movie Ticket"),
                org.springframework.web.util.HtmlUtils.htmlEscape(ticket.getBookingCode() != null ? ticket.getBookingCode() : ""),
                org.springframework.web.util.HtmlUtils.htmlEscape(ticketUrl != null ? ticketUrl : "#"),
                org.springframework.web.util.HtmlUtils.htmlEscape(ticket.getCinemaName() != null ? ticket.getCinemaName() : "BOX Cinema"),
                org.springframework.web.util.HtmlUtils.htmlEscape(ticket.getRoomName() != null ? ticket.getRoomName() : "Auditorium"),
                org.springframework.web.util.HtmlUtils.htmlEscape(ticket.getShowtimeStart() != null ? ticket.getShowtimeStart() : ""),
                seatsHtml.toString(),
                foodHtml.toString(),
                nf.format(ticket.getTotalAmount() != null ? ticket.getTotalAmount() : java.math.BigDecimal.ZERO)
        );
    }
}
