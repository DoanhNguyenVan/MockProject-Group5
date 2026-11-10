package com.boxcinema.service.impl;

import com.boxcinema.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
public class EmailServiceImpl implements EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);

    @Value("${spring.mail.host:smtp.gmail.com}")
    private String smtpHost;

    @Value("${spring.mail.port:465}")
    private int smtpPort;

    @Value("${spring.mail.username:}")
    private String smtpUsername;

    @Value("${spring.mail.password:}")
    private String smtpPassword;

    @Override
    public boolean sendVerificationOtpEmail(String toEmail, String fullName, String otpCode) {
        log.info("=================================================================");
        log.info("[GMAIL VERIFICATION OTP] Recipient: {} ({}) | OTP Code: {}", fullName, toEmail, otpCode);
        log.info("=================================================================");

        if (!isSmtpConfigured()) {
            log.info("Gmail SMTP credentials (spring.mail.username / spring.mail.password) are not configured. Using local OTP preview mode.");
            return false;
        }

        try {
            sendHtmlEmailViaSslSmtp(
                    toEmail,
                    fullName,
                    "BOX Cinema - Verify Your Account (OTP: " + otpCode + ")",
                    buildVerificationEmailHtml(fullName, otpCode)
            );
            log.info("Successfully sent verification OTP email to {} via {}", toEmail, smtpHost);
            return true;
        } catch (Exception ex) {
            log.warn("Failed to send email via Gmail SMTP ({}). Falling back to on-screen/console OTP. Reason: {}",
                    smtpHost, ex.getMessage());
            return false;
        }
    }

    @Override
    public boolean sendInquiryConfirmationEmail(String toEmail, String fullName, Long inquiryId, String topic, String userMessage) {
        log.info("=================================================================");
        log.info("[CONTACT INQUIRY CONFIRMATION] Recipient: {} ({}) | Ticket #BOX-REQ-{} | Topic: {}",
                fullName, toEmail, inquiryId, topic);
        log.info("=================================================================");

        if (!isSmtpConfigured()) {
            log.info("Gmail SMTP credentials are not configured. Skipping live confirmation email.");
            return false;
        }

        try {
            String subject = "BOX Cinema - Tiếp nhận yêu cầu hỗ trợ [#BOX-REQ-" + inquiryId + "]";
            String htmlBody = buildInquiryConfirmationEmailHtml(fullName, inquiryId, topic, userMessage);
            sendHtmlEmailViaSslSmtp(toEmail, fullName, subject, htmlBody);
            log.info("Successfully dispatched inquiry confirmation email to {} for ticket #BOX-REQ-{}", toEmail, inquiryId);
            return true;
        } catch (Exception ex) {
            log.warn("Could not dispatch inquiry email via Gmail SMTP: {}", ex.getMessage());
            return false;
        }
    }

    private boolean isSmtpConfigured() {
        return smtpUsername != null && !smtpUsername.isBlank()
                && !smtpUsername.startsWith("YOUR_")
                && smtpPassword != null && !smtpPassword.isBlank()
                && !smtpPassword.startsWith("YOUR_");
    }

    private void sendHtmlEmailViaSslSmtp(String toEmail, String fullName, String subject, String htmlBody) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        int port = (smtpPort == 587) ? 465 : smtpPort;

        try (SSLSocket socket = (SSLSocket) factory.createSocket(smtpHost, port);
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            socket.setSoTimeout(10_000);
            String cleanUser = smtpUsername.trim();
            String cleanPass = smtpPassword.replace(" ", "").trim();

            readSmtpResponse(reader, "220");
            sendSmtpCommand(writer, reader, "EHLO boxcinema.com", "250");
            sendSmtpCommand(writer, reader, "AUTH LOGIN", "334");
            sendSmtpCommand(writer, reader, Base64.getEncoder().encodeToString(cleanUser.getBytes(StandardCharsets.UTF_8)), "334");
            sendSmtpCommand(writer, reader, Base64.getEncoder().encodeToString(cleanPass.getBytes(StandardCharsets.UTF_8)), "235");
            sendSmtpCommand(writer, reader, "MAIL FROM:<" + cleanUser + ">", "250");
            sendSmtpCommand(writer, reader, "RCPT TO:<" + toEmail.trim() + ">", "250");
            sendSmtpCommand(writer, reader, "DATA", "354");

            String encodedSubject = "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=";
            StringBuilder message = new StringBuilder();
            message.append("From: \"BOX Cinema\" <").append(cleanUser).append(">\r\n");
            message.append("To: \"").append(fullName.replace("\"", "")).append("\" <").append(toEmail.trim()).append(">\r\n");
            message.append("Subject: ").append(encodedSubject).append("\r\n");
            message.append("MIME-Version: 1.0\r\n");
            message.append("Content-Type: text/html; charset=UTF-8\r\n");
            message.append("Content-Transfer-Encoding: 8bit\r\n\r\n");
            message.append(htmlBody).append("\r\n.\r\n");

            writer.write(message.toString());
            writer.flush();
            readSmtpResponse(reader, "250");
            sendSmtpCommand(writer, reader, "QUIT", "221");
        }
    }

    private void sendSmtpCommand(BufferedWriter writer, BufferedReader reader, String command, String expectedCode) throws IOException {
        writer.write(command + "\r\n");
        writer.flush();
        readSmtpResponse(reader, expectedCode);
    }

    private void readSmtpResponse(BufferedReader reader, String expectedCode) throws IOException {
        String line;
        String lastLine = "";
        while ((line = reader.readLine()) != null) {
            lastLine = line;
            if (line.length() < 4 || line.charAt(3) != '-') {
                break;
            }
        }
        if (!lastLine.startsWith(expectedCode)) {
            throw new IOException("SMTP error (expected " + expectedCode + "): " + lastLine);
        }
    }

    private String buildVerificationEmailHtml(String fullName, String otpCode) {
        return """
                <div style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 560px; margin: 0 auto; background: #141418; color: #f8fafc; border-radius: 16px; overflow: hidden; border: 1px solid #2e2e38;">
                  <div style="background: linear-gradient(135deg, #C91934 0%%, #7a0d1e 100%%); padding: 28px 32px; text-align: center;">
                    <h1 style="margin: 0; font-size: 24px; font-weight: 800; color: #ffffff; letter-spacing: 0.04em;">BOX CINEMA</h1>
                    <p style="margin: 6px 0 0; font-size: 13px; color: #ffd7de;">Gmail Account Verification</p>
                  </div>
                  <div style="padding: 32px;">
                    <p style="font-size: 16px; margin-top: 0; color: #e2e8f0;">Hi <strong>%s</strong>,</p>
                    <p style="font-size: 14px; line-height: 1.6; color: #cbd5e1;">
                      Thank you for registering at <strong>BOX Cinema</strong>. Please enter the 6-digit verification code below to activate your account:
                    </p>
                    <div style="margin: 28px 0; padding: 20px; background: #1f2029; border: 2px dashed #C91934; border-radius: 12px; text-align: center;">
                      <span style="font-size: 34px; font-weight: 900; letter-spacing: 10px; color: #ffffff;">%s</span>
                    </div>
                    <p style="font-size: 13px; color: #94a3b8; margin-bottom: 0;">
                      This code will expire in <strong>10 minutes</strong>. If you did not create a BOX Cinema account, you can safely ignore this email.
                    </p>
                  </div>
                </div>
                """.formatted(fullName, otpCode);
    }

    private String buildInquiryConfirmationEmailHtml(String fullName, Long inquiryId, String topic, String userMessage) {
        String cleanMessage = userMessage.replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
        return """
                <div style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 580px; margin: 0 auto; background: #121318; color: #f8fafc; border-radius: 16px; overflow: hidden; border: 1px solid #2a2b36;">
                  <div style="background: linear-gradient(135deg, #C91934 0%%, #7a0d1e 100%%); padding: 26px 30px; text-align: center;">
                    <h1 style="margin: 0; font-size: 24px; font-weight: 800; color: #ffffff; letter-spacing: 0.04em;">BOX CINEMA</h1>
                    <p style="margin: 6px 0 0; font-size: 13px; color: #ffd7de;">Xác Nhận Tiếp Nhận Yêu Cầu Hỗ Trợ</p>
                  </div>
                  <div style="padding: 30px;">
                    <p style="font-size: 15px; margin-top: 0; color: #e2e8f0;">Kính gửi Quý khách <strong>%s</strong>,</p>
                    <p style="font-size: 14px; line-height: 1.6; color: #cbd5e1;">
                      BOX Cinema xin chân thành cảm ơn Quý khách đã gửi tin nhắn liên hệ. Hệ thống đã tiếp nhận thành công yêu cầu của Quý khách với thông tin chi tiết dưới đây:
                    </p>
                    
                    <div style="margin: 20px 0; padding: 18px; background: #1b1c24; border-left: 4px solid #C91934; border-radius: 8px;">
                      <p style="margin: 0 0 8px; font-size: 13px; color: #94a3b8;">Mã phiếu hỗ trợ: <strong style="color: #ffffff; font-size: 14px;">#BOX-REQ-%d</strong></p>
                      <p style="margin: 0 0 8px; font-size: 13px; color: #94a3b8;">Chủ đề: <strong style="color: #ffffff;">%s</strong></p>
                      <p style="margin: 0; font-size: 13px; color: #94a3b8;">Trạng thái: <strong style="color: #22c55e;">ĐÃ TIẾP NHẬN (PENDING)</strong></p>
                    </div>

                    <div style="margin: 18px 0; padding: 16px; background: #161720; border: 1px solid #282938; border-radius: 8px;">
                      <p style="margin: 0 0 6px; font-size: 12px; font-weight: 700; color: #94a3b8; text-transform: uppercase;">Nội dung Quý khách gửi:</p>
                      <p style="margin: 0; font-size: 13px; line-height: 1.6; color: #e2e8f0; font-style: italic;">"%s"</p>
                    </div>

                    <p style="font-size: 14px; line-height: 1.6; color: #cbd5e1;">
                      Đội ngũ Chăm Sóc Khách Hàng của BOX Cinema sẽ tiến hành kiểm tra và phản hồi trực tiếp tới Quý khách trong vòng <strong>2 giờ làm việc</strong> (tối đa 24 giờ).
                    </p>
                    <p style="font-size: 13px; color: #94a3b8; line-height: 1.5; margin-bottom: 0;">
                      Trường hợp cần hỗ trợ khẩn cấp (suất chiếu sắp bắt đầu hoặc sự cố vé), Quý khách vui lòng gọi ngay Tổng đài CSKH: <strong style="color: #ff4d6a;">1900 6868</strong> (08:00 - 01:00 hàng ngày).
                    </p>
                  </div>
                  <div style="background: #0d0e14; padding: 16px 30px; text-align: center; border-top: 1px solid #22232e;">
                    <p style="margin: 0; font-size: 12px; color: #64748b;">BOX Cinema Vietnam · Rạp chiếu phim thế hệ mới IMAX Laser &amp; Dolby Atmos</p>
                  </div>
                </div>
                """.formatted(fullName, inquiryId, topic, cleanMessage);
    }
}
