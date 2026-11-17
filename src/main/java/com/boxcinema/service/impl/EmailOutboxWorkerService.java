package com.boxcinema.service.impl;

import com.boxcinema.dto.BookingTicketViewDto;
import com.boxcinema.entity.EmailOutbox;
import com.boxcinema.repository.EmailOutboxRepository;
import com.boxcinema.service.BookingQrService;
import com.boxcinema.service.TicketService;
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
import java.util.List;
import java.util.Locale;

@Service
public class EmailOutboxWorkerService {

    private static final Logger log = LoggerFactory.getLogger(EmailOutboxWorkerService.class);

    private final EmailOutboxRepository emailOutboxRepository;
    private final TicketService ticketService;
    private final BookingQrService bookingQrService;
    private final JavaMailSender mailSender;

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
        this.emailOutboxRepository = emailOutboxRepository;
        this.ticketService = ticketService;
        this.bookingQrService = bookingQrService;
        this.mailSender = mailSender;
    }

    @Scheduled(fixedDelay = 5000)
    public void processOutbox() {
        List<EmailOutbox> pendingList = emailOutboxRepository.findByStatusOrderByCreatedAtAsc("PENDING");
        for (EmailOutbox outbox : pendingList) {
            processItem(outbox);
        }

        // Also retry failed ones under max retries
        List<EmailOutbox> retryingList = emailOutboxRepository.findByStatusAndRetryCountLessThan("RETRYING", 3);
        for (EmailOutbox outbox : retryingList) {
            processItem(outbox);
        }
    }

    private void processItem(EmailOutbox outbox) {
        try {
            Long bookingId = outbox.getBooking().getBookingId();
            BookingTicketViewDto ticketView = ticketService.getTicketView(bookingId, null);
            byte[] qrPng = bookingQrService.getBookingQrPngBytes(bookingId);
            String ticketUrl = bookingQrService.getBookingTicketUrl(bookingId);

            if (mailSender == null) {
                log.warn("JavaMailSender is not configured. Simulating email dispatch to {}", outbox.getRecipientEmail());
                outbox.setStatus("SENT");
                outbox.setSentAt(LocalDateTime.now());
                emailOutboxRepository.save(outbox);
                return;
            }

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());

            helper.setFrom(fromEmail.isBlank() ? "no-reply@boxcinema.vn" : fromEmail, fromName);
            helper.setTo(outbox.getRecipientEmail());
            helper.setSubject("BOX Cinema - Xác nhận đặt vé thành công #" + ticketView.getBookingCode());

            String html = buildEmailHtml(ticketView, ticketUrl);
            helper.setText(html, true);

            // Attach ZXing QR Code inline as CID (never leak token to 3rd-party services)
            if (qrPng != null && qrPng.length > 0) {
                helper.addInline("bookingQrCode", new ByteArrayResource(qrPng), "image/png");
            }

            mailSender.send(message);

            outbox.setStatus("SENT");
            outbox.setSentAt(LocalDateTime.now());
            outbox.setLastError(null);
            emailOutboxRepository.save(outbox);
            log.info("Ticket email dispatched successfully for booking {}", bookingId);

        } catch (Exception ex) {
            log.error("Failed to send ticket email for outbox id {}: {}", outbox.getId(), ex.getMessage());
            int retries = outbox.getRetryCount() + 1;
            outbox.setRetryCount(retries);
            if (retries >= 3) {
                outbox.setStatus("FAILED");
            } else {
                outbox.setStatus("RETRYING");
            }
            outbox.setLastError(ex.getMessage());
            emailOutboxRepository.save(outbox);
        }
    }

    private String buildEmailHtml(BookingTicketViewDto ticket, String ticketUrl) {
        NumberFormat nf = NumberFormat.getInstance(new Locale("vi", "VN"));

        StringBuilder seatsHtml = new StringBuilder();
        if (ticket.getTickets() != null) {
            for (var s : ticket.getTickets()) {
                seatsHtml.append("<span style='display:inline-block;padding:4px 8px;margin:2px;background:#e2e8f0;border-radius:4px;font-weight:600;'>")
                        .append(s.getSeatLabel())
                        .append("</span> ");
            }
        }

        StringBuilder foodHtml = new StringBuilder();
        if (ticket.getFoodItems() != null && !ticket.getFoodItems().isEmpty()) {
            foodHtml.append("<div style='margin-top:16px;border-top:1px dashed #cbd5e1;padding-top:12px;'>");
            foodHtml.append("<h4 style='margin:0 0 8px;color:#1e293b;'>Bắp & Nước đã đặt:</h4>");
            foodHtml.append("<ul style='padding-left:20px;margin:0;color:#475569;'>");
            for (var f : ticket.getFoodItems()) {
                foodHtml.append("<li>").append(f.getQuantity()).append("x ").append(f.getProductName());
                if (f.getVariantName() != null && !f.getVariantName().isBlank()) {
                    foodHtml.append(" (").append(f.getVariantName()).append(")");
                }
                foodHtml.append(" - ").append(nf.format(f.getUnitPrice().multiply(java.math.BigDecimal.valueOf(f.getQuantity())))).append(" đ</li>");
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
                        <p style="margin: 4px 0 0; opacity: 0.9; font-size: 14px;">Vé Điện Tử & Xác Nhận Đơn Hàng</p>
                    </div>
                    
                    <div style="padding: 24px; text-align: center;">
                        <h2 style="color: #0f172a; margin: 0 0 4px;">%s</h2>
                        <p style="color: #64748b; margin: 0 0 16px; font-size: 14px;">Mã đặt vé: <strong>%s</strong></p>
                        
                        <div style="background: #f1f5f9; padding: 16px; border-radius: 8px; margin-bottom: 20px; display: inline-block;">
                            <img src="cid:bookingQrCode" alt="Booking QR Code" width="220" height="220" style="display:block; margin: 0 auto; border-radius: 4px;"/>
                            <p style="margin: 8px 0 0; color: #0284c7; font-weight: 600; font-size: 13px;">Mã QR dùng check-in cho toàn bộ ghế trong đơn</p>
                            <div style="margin-top: 12px;">
                                <a href="%s" style="display:inline-block; background-color:#e11d48; color:#ffffff; padding:8px 20px; border-radius:6px; font-weight:bold; font-size:13px; text-decoration:none;">Xem Vé Trực Tuyến</a>
                            </div>
                            <p style="margin: 10px 0 0; color: #b45309; background: #fef3c7; border: 1px solid #fde68a; padding: 6px 10px; border-radius: 6px; font-size: 11px; line-height: 1.35; text-align: left;">
                                ⚠️ Mã QR này đại diện cho toàn bộ số vé trong đơn đặt hàng. Không chia sẻ với người không thuộc nhóm đặt vé.
                            </p>
                        </div>
                        
                        <div style="text-align: left; background: #fafafa; border: 1px solid #f1f5f9; border-radius: 8px; padding: 16px;">
                            <p style="margin: 4px 0;"><strong>Rạp:</strong> %s</p>
                            <p style="margin: 4px 0;"><strong>Phòng chiếu:</strong> %s</p>
                            <p style="margin: 4px 0;"><strong>Suất chiếu:</strong> %s</p>
                            <p style="margin: 4px 0;"><strong>Ghế ngồi:</strong> %s</p>
                            %s
                            <div style="margin-top: 16px; border-top: 2px solid #e2e8f0; padding-top: 12px; font-size: 16px;">
                                <strong>Tổng cộng:</strong> <span style="color: #e11d48; font-weight: bold; font-size: 18px;">%s đ</span>
                            </div>
                        </div>
                        
                        <p style="color: #94a3b8; font-size: 12px; margin-top: 24px; line-height: 1.5;">
                            Vui lòng xuất trình mã QR này tại quầy soát vé hoặc cổng tự động trước giờ chiếu ít nhất 15 phút.<br/>
                            Chúc bạn có những giây phút thưởng thức điện ảnh tuyệt vời tại BOX Cinema!
                        </p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(
                ticket.getMovieTitle() != null ? ticket.getMovieTitle() : "Vé xem phim",
                ticket.getBookingCode(),
                ticketUrl != null ? ticketUrl : "#",
                ticket.getCinemaName() != null ? ticket.getCinemaName() : "BOX Cinema",
                ticket.getRoomName() != null ? ticket.getRoomName() : "Phòng chiếu",
                ticket.getShowtimeStart() != null ? ticket.getShowtimeStart() : "",
                seatsHtml.toString(),
                foodHtml.toString(),
                nf.format(ticket.getTotalAmount())
        );
    }
}
