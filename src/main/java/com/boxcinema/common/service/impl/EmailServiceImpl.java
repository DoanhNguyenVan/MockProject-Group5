package com.boxcinema.common.service.impl;

import com.boxcinema.common.service.EmailService;
import jakarta.annotation.PostConstruct;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class EmailServiceImpl implements EmailService {

  private static final Logger log = LoggerFactory.getLogger(EmailServiceImpl.class);

  private final JavaMailSender mailSender;
  private final RestClient restClient;

  @Value("${spring.mail.host:smtp.gmail.com}")
  private String smtpHost;

  @Value("${spring.mail.username:}")
  private String smtpUsername;

  @Value("${spring.mail.password:}")
  private String smtpPassword;

  @Value("${app.mail.from-name:BOX Cinema}")
  private String fromName;

  @Value("${mailjet.api.key:${MAILJET_API_KEY:}}")
  private String mailjetApiKey;

  @Value("${mailjet.secret.key:${MAILJET_SECRET_KEY:}}")
  private String mailjetSecretKey;

  @Value("${mailjet.sender.email:${MAILJET_SENDER_EMAIL:}}")
  private String mailjetSenderEmail;

  @Value("${mailjet.sender.name:BOX Cinema}")
  private String mailjetSenderName;

  @Value("${resend.api.key:${RESEND_API_KEY:}}")
  private String resendApiKey;

  @Value("${resend.from.email:${RESEND_FROM_EMAIL:BOX Cinema <onboarding@resend.dev>}}")
  private String resendFromEmail;

  public EmailServiceImpl(JavaMailSender mailSender) {
    this(mailSender, null);
  }

  @Autowired
  public EmailServiceImpl(@Autowired(required = false) JavaMailSender mailSender,
                          @Autowired(required = false) RestClient.Builder restClientBuilder) {
    this.mailSender = mailSender;
    this.restClient = (restClientBuilder != null) ? restClientBuilder.build() : RestClient.create();
  }

  @PostConstruct
  void normalizeGmailAppPassword() {
    if (mailSender instanceof JavaMailSenderImpl impl
        && impl.getHost() != null
        && impl.getHost().toLowerCase().contains("gmail.com")
        && impl.getPassword() != null) {
      impl.setPassword(impl.getPassword().replace(" ", "").trim());
    }
  }

  @Override
  public boolean sendVerificationOtpEmail(String toEmail, String fullName, String otpCode) {
    log.info("[GMAIL VERIFICATION OTP] Dispatching verification email to {}",
        com.boxcinema.auth.service.AuthAuditService.maskEmail(toEmail));

    return sendHtmlEmail(
        toEmail,
        fullName,
        "BOX Cinema - Verify Your Account",
        buildVerificationEmailHtml(fullName, otpCode));
  }

  @Override
  public boolean sendGoogleAutoLinkOtpEmail(String toEmail, String fullName, String otpCode) {
    log.info("[GOOGLE AUTO-LINK OTP] Dispatching one-time verification code to {}",
        com.boxcinema.auth.service.AuthAuditService.maskEmail(toEmail));

    return sendHtmlEmail(
        toEmail,
        fullName,
        "BOX Cinema - Verify Email to Link Google Sign-In",
        buildGoogleAutoLinkOtpEmailHtml(fullName, otpCode));
  }

  @Async("emailTaskExecutor")
  @Override
  public void sendVerificationOtpEmailAsync(String toEmail, String fullName, String otpCode) {
    sendVerificationOtpEmail(toEmail, fullName, otpCode);
  }

  @Override
  public boolean sendInquiryConfirmationEmail(String toEmail, String fullName, Long inquiryId, String topic,
      String userMessage) {
    log.info("=================================================================");
    log.info("[CONTACT INQUIRY CONFIRMATION] Recipient: {} ({}) | Ticket #BOX-REQ-{} | Topic: {}",
        fullName, toEmail, inquiryId, topic);
    log.info("=================================================================");

    String subject = "BOX Cinema - Support Inquiry Received [#BOX-REQ-" + inquiryId + "]";
    return sendHtmlEmail(
        toEmail,
        fullName,
        subject,
        buildInquiryConfirmationEmailHtml(fullName, inquiryId, topic, userMessage));
  }

  @Async("emailTaskExecutor")
  @Override
  public void sendInquiryConfirmationEmailAsync(String toEmail, String fullName, Long inquiryId, String topic,
      String userMessage) {
    sendInquiryConfirmationEmail(toEmail, fullName, inquiryId, topic, userMessage);
  }

  @Override
  public boolean sendInquiryResponseEmail(String toEmail, String fullName, Long inquiryId, String topic,
      String adminResponse) {
    log.info("=================================================================");
    log.info("[INQUIRY RESOLUTION RESPONSE] Recipient: {} ({}) | Ticket #BOX-REQ-{} | Topic: {}",
        fullName, toEmail, inquiryId, topic);
    log.info("=================================================================");

    String subject = "BOX Cinema - Support Inquiry Response [#BOX-REQ-" + inquiryId + "]";
    return sendHtmlEmail(
        toEmail,
        fullName,
        subject,
        buildInquiryResponseEmailHtml(fullName, inquiryId, topic, adminResponse));
  }

  @Async("emailTaskExecutor")
  @Override
  public void sendInquiryResponseEmailAsync(String toEmail, String fullName, Long inquiryId, String topic,
      String adminResponse) {
    sendInquiryResponseEmail(toEmail, fullName, inquiryId, topic, adminResponse);
  }

  @Override
  public boolean sendPasswordResetOtpEmail(String toEmail, String fullName, String otpCode) {
    log.info("[PASSWORD RESET OTP] Dispatching reset code email to {}",
        com.boxcinema.auth.service.AuthAuditService.maskEmail(toEmail));

    return sendHtmlEmail(
        toEmail,
        fullName,
        "BOX Cinema - Password Reset Verification Code",
        buildPasswordResetOtpEmailHtml(fullName, otpCode));
  }

  @Override
  public boolean sendPasswordResetSuccessEmail(String toEmail, String fullName) {
    return sendPasswordResetConfirmationEmail(toEmail, fullName);
  }

  @Override
  public boolean sendPasswordResetConfirmationEmail(String toEmail, String fullName) {
    log.info("[PASSWORD RESET ALERT] Recipient: {} ({})", fullName, toEmail);

    return sendHtmlEmail(
        toEmail,
        fullName,
        "BOX Cinema - Your Password Was Successfully Reset",
        buildPasswordResetEmailHtml(fullName));
  }

  @Async("emailTaskExecutor")
  @Override
  public void sendPasswordResetConfirmationEmailAsync(String toEmail, String fullName) {
    sendPasswordResetConfirmationEmail(toEmail, fullName);
  }

  @Async("emailTaskExecutor")
  @Override
  public void sendGoogleLinkedNotificationEmailAsync(String toEmail, String fullName) {
    log.info("[SECURITY ALERT] Google account linked notification for {}",
        com.boxcinema.auth.service.AuthAuditService.maskEmail(toEmail));
    sendHtmlEmail(
        toEmail,
        fullName,
        "BOX Cinema - Google Account Linked",
        buildSecurityNoticeHtml(
            fullName,
            "Google Sign-In Linked",
            "A Google account was just linked to your BOX Cinema profile. You can now sign in with either your email & password or Google."));
  }

  @Async("emailTaskExecutor")
  @Override
  public void sendGoogleUnlinkedNotificationEmailAsync(String toEmail, String fullName) {
    log.info("[SECURITY ALERT] Google account unlinked notification for {}",
        com.boxcinema.auth.service.AuthAuditService.maskEmail(toEmail));
    sendHtmlEmail(
        toEmail,
        fullName,
        "BOX Cinema - Google Account Unlinked",
        buildSecurityNoticeHtml(
            fullName,
            "Google Sign-In Unlinked",
            "Google sign-in has been removed from your BOX Cinema account. You can continue signing in using your email and password."));
  }

  @Override
  public boolean sendTicketConfirmationEmail(
      String toEmail,
      String subject,
      String htmlBody,
      byte[] qrPngBytes) {
    return sendHtmlEmail(toEmail, null, subject, htmlBody, "bookingQrCode", qrPngBytes, "ticket-qr.png");
  }

  @Override
  public boolean isMailTransportConfigured() {
    boolean hasMailjet = mailjetApiKey != null && !mailjetApiKey.trim().isBlank()
        && mailjetSecretKey != null && !mailjetSecretKey.trim().isBlank();
    boolean hasResend = resendApiKey != null && !resendApiKey.trim().isBlank();
    return hasMailjet || hasResend || isSmtpConfigured();
  }

  private boolean sendHtmlEmail(String toEmail, String toName, String subject, String htmlBody) {
    return sendHtmlEmail(toEmail, toName, subject, htmlBody, null, null, null);
  }

  private boolean sendHtmlEmail(
      String toEmail,
      String toName,
      String subject,
      String htmlBody,
      String inlineContentId,
      byte[] inlinePngBytes,
      String inlineFilename) {
    if (toEmail == null || toEmail.isBlank()) {
      log.warn("Cannot send email: recipient address is blank.");
      return false;
    }

    boolean hasInlineImage = inlineContentId != null && !inlineContentId.isBlank()
        && inlinePngBytes != null && inlinePngBytes.length > 0;
    String safeInlineFilename = (inlineFilename != null && !inlineFilename.isBlank()) ? inlineFilename : "inline.png";

    // 1. Send via Mailjet Send API v3.1 (Port 443 - Bypasses Render cloud SMTP blocking)
    if (mailjetApiKey != null && !mailjetApiKey.trim().isBlank()
        && mailjetSecretKey != null && !mailjetSecretKey.trim().isBlank()) {
      try {
        String senderEmail = (mailjetSenderEmail != null && !mailjetSenderEmail.isBlank())
            ? mailjetSenderEmail.trim()
            : ((smtpUsername != null && !smtpUsername.isBlank()) ? smtpUsername.trim() : "candydark05@gmail.com");
        String senderName = (mailjetSenderName != null && !mailjetSenderName.isBlank())
            ? mailjetSenderName.trim()
            : ((fromName != null && !fromName.isBlank()) ? fromName.trim() : "BOX Cinema");

        Map<String, Object> fromMap = new LinkedHashMap<>();
        fromMap.put("Email", senderEmail);
        fromMap.put("Name", senderName);

        Map<String, Object> toMap = new LinkedHashMap<>();
        toMap.put("Email", toEmail.trim());

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("From", fromMap);
        message.put("To", List.of(toMap));
        message.put("Subject", subject);
        message.put("HTMLPart", htmlBody);
        if (hasInlineImage) {
          message.put("InlinedAttachments", List.of(Map.of(
              "ContentType", "image/png",
              "Filename", safeInlineFilename,
              "ContentID", inlineContentId,
              "Base64Content", Base64.getEncoder().encodeToString(inlinePngBytes)
          )));
        }

        Map<String, Object> body = Map.of("Messages", List.of(message));

        ResponseEntity<String> response = restClient.post()
            .uri("https://api.mailjet.com/v3.1/send")
            .headers(headers -> headers.setBasicAuth(mailjetApiKey.trim(), mailjetSecretKey.trim()))
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
          log.info("Successfully dispatched email '{}' to {} via Mailjet HTTP API.", subject, toEmail);
          return true;
        } else {
          log.error("Failed to dispatch email '{}' to {} via Mailjet. HTTP Status: {}, Response: {}",
              subject, toEmail, response.getStatusCode(), response.getBody());
          return false;
        }
      } catch (RestClientResponseException ex) {
        log.error("Failed to dispatch email '{}' to {} via Mailjet. HTTP Status: {}, Response: {}",
            subject, toEmail, ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
        return false;
      } catch (Exception ex) {
        log.error("Failed to dispatch email '{}' to {} via Mailjet: {}", subject, toEmail, ex.getMessage(), ex);
        return false;
      }
    }

    // 2. Send via Resend HTTP REST API (Port 443 - Bypasses Render cloud SMTP blocking)
    if (resendApiKey != null && !resendApiKey.trim().isBlank()) {
      try {
        String fromAddress = (resendFromEmail != null && !resendFromEmail.isBlank())
            ? resendFromEmail.trim()
            : "BOX Cinema <onboarding@resend.dev>";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", fromAddress);
        body.put("to", List.of(toEmail.trim()));
        body.put("subject", subject);
        body.put("html", htmlBody);
        if (hasInlineImage) {
          body.put("attachments", List.of(Map.of(
              "filename", safeInlineFilename,
              "content", Base64.getEncoder().encodeToString(inlinePngBytes),
              "content_id", inlineContentId
          )));
        }

        ResponseEntity<String> response = restClient.post()
            .uri("https://api.resend.com/emails")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + resendApiKey.trim())
            .header(HttpHeaders.USER_AGENT, "BoxCinemaApp/1.0")
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .toEntity(String.class);

        if (response.getStatusCode().is2xxSuccessful()) {
          log.info("Successfully dispatched email '{}' to {} via Resend HTTP API.", subject, toEmail);
          return true;
        } else {
          log.error("Failed to dispatch email '{}' to {} via Resend. HTTP Status: {}, Response: {}",
              subject, toEmail, response.getStatusCode(), response.getBody());
          return false;
        }
      } catch (RestClientResponseException ex) {
        log.error("Failed to dispatch email '{}' to {} via Resend. HTTP Status: {}, Response: {}",
            subject, toEmail, ex.getStatusCode(), ex.getResponseBodyAsString(), ex);
        return false;
      } catch (Exception ex) {
        log.error("Failed to dispatch email '{}' to {} via Resend: {}", subject, toEmail, ex.getMessage(), ex);
        return false;
      }
    }

    // 3. Fallback to JavaMailSender (SMTP on Port 465 / 587)
    if (!isSmtpConfigured()) {
      log.info("SMTP credentials and Resend API key are not configured. Skipping live email dispatch to {}.", toEmail);
      return false;
    }

    try {
      MimeMessage mimeMessage = mailSender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, StandardCharsets.UTF_8.name());

      String senderDisplayName = (fromName != null && !fromName.isBlank()) ? fromName.trim() : "BOX Cinema";
      helper.setFrom(new InternetAddress(smtpUsername.trim(), senderDisplayName, StandardCharsets.UTF_8.name()));

      if (toName != null && !toName.isBlank()) {
        helper.setTo(new InternetAddress(toEmail.trim(), toName.trim(), StandardCharsets.UTF_8.name()));
      } else {
        helper.setTo(toEmail.trim());
      }

      helper.setSubject(subject);
      helper.setSentDate(new java.util.Date());

      // Multipart plain-text + HTML: Clean deliverability score for Gmail outbound queue
      String plainText = htmlBody.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
      helper.setText(plainText, htmlBody);
      if (hasInlineImage) {
        helper.addInline(inlineContentId, new ByteArrayResource(inlinePngBytes), "image/png");
      }

      // Standard RFC headers (avoid spam-flagged X-Priority / Urgent headers)
      mimeMessage.setHeader("X-Auto-Response-Suppress", "All");
      mimeMessage.setHeader("Auto-Submitted", "auto-generated");

      // Explicit Message-ID prevents Jakarta Mail from executing slow DNS reverse-lookup for getLocalHost()
      mimeMessage.setHeader("Message-ID", "<" + java.util.UUID.randomUUID() + "@boxcinema.vn>");

      mailSender.send(mimeMessage);
      log.info("Successfully dispatched email '{}' to {} via {} (JavaMailSender).", subject, toEmail, smtpHost);
      return true;
    } catch (MailException | MessagingException | UnsupportedEncodingException ex) {
      log.error("Failed to dispatch email '{}' to {} via {} (JavaMailSender): {}",
          subject, toEmail, smtpHost, ex.getMessage(), ex);
      return false;
    }
  }

  private boolean isSmtpConfigured() {
    return mailSender != null
        && smtpUsername != null && !smtpUsername.isBlank()
        && !smtpUsername.startsWith("YOUR_")
        && smtpPassword != null && !smtpPassword.isBlank()
        && !smtpPassword.startsWith("YOUR_");
  }

  private String escapeHtml(String raw) {
    if (raw == null) {
      return "";
    }
    return raw.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private String buildVerificationEmailHtml(String fullName, String otpCode) {
    String safeName = escapeHtml(fullName != null && !fullName.isBlank() ? fullName : "Member");
    String safeOtp = escapeHtml(otpCode);
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
              This code will expire in <strong>5 minutes</strong>. If you did not create a BOX Cinema account, you can safely ignore this email.
            </p>
          </div>
        </div>
        """
        .formatted(safeName, safeOtp);
  }

  private String buildInquiryConfirmationEmailHtml(String fullName, Long inquiryId, String topic, String userMessage) {
    String safeName = escapeHtml(fullName);
    String safeTopic = escapeHtml(topic);
    String safeMessage = escapeHtml(userMessage).replace("\r\n", "<br>").replace("\n", "<br>");
    return """
        <div style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 580px; margin: 0 auto; background: #121318; color: #f8fafc; border-radius: 16px; overflow: hidden; border: 1px solid #2a2b36;">
          <div style="background: linear-gradient(135deg, #C91934 0%%, #7a0d1e 100%%); padding: 26px 30px; text-align: center;">
            <h1 style="margin: 0; font-size: 24px; font-weight: 800; color: #ffffff; letter-spacing: 0.04em;">BOX CINEMA</h1>
            <p style="margin: 6px 0 0; font-size: 13px; color: #ffd7de;">Support Inquiry Confirmation</p>
          </div>
          <div style="padding: 30px;">
            <p style="font-size: 15px; margin-top: 0; color: #e2e8f0;">Dear <strong>%s</strong>,</p>
            <p style="font-size: 14px; line-height: 1.6; color: #cbd5e1;">
              Thank you for contacting <strong>BOX Cinema</strong>. We have received your support request with the following details:
            </p>
            <div style="margin: 20px 0; padding: 18px; background: #1b1c24; border-left: 4px solid #C91934; border-radius: 8px;">
              <p style="margin: 0 0 8px; font-size: 13px; color: #94a3b8;">Ticket Reference: <strong style="color: #ffffff; font-size: 14px;">#BOX-REQ-%d</strong></p>
              <p style="margin: 0 0 8px; font-size: 13px; color: #94a3b8;">Topic: <strong style="color: #ffffff;">%s</strong></p>
              <p style="margin: 0; font-size: 13px; color: #94a3b8;">Status: <strong style="color: #22c55e;">RECEIVED (PENDING)</strong></p>
            </div>
            <div style="margin: 18px 0; padding: 16px; background: #161720; border: 1px solid #282938; border-radius: 8px;">
              <p style="margin: 0 0 6px; font-size: 12px; font-weight: 700; color: #94a3b8; text-transform: uppercase;">Your Message:</p>
              <p style="margin: 0; font-size: 13px; line-height: 1.6; color: #e2e8f0; font-style: italic;">"%s"</p>
            </div>
            <p style="font-size: 14px; line-height: 1.6; color: #cbd5e1;">
              Our Guest Relations team will review your inquiry and respond within <strong>2 business hours</strong>.
            </p>
            <p style="font-size: 13px; color: #94a3b8; line-height: 1.5; margin-bottom: 0;">
              For urgent ticket assistance before a showtime, please call our National Hotline at <strong style="color: #ff4d6a;">1900 6868</strong> (08:00 - 01:00 daily).
            </p>
          </div>
          <div style="background: #0d0e14; padding: 16px 30px; text-align: center; border-top: 1px solid #22232e;">
            <p style="margin: 0; font-size: 12px; color: #64748b;">BOX Cinema Vietnam · IMAX Laser &amp; Dolby Atmos</p>
          </div>
        </div>
        """
        .formatted(safeName, inquiryId, safeTopic, safeMessage);
  }

  private String buildInquiryResponseEmailHtml(String fullName, Long inquiryId, String topic, String adminResponse) {
    String safeName = escapeHtml(fullName);
    String safeTopic = escapeHtml(topic);
    String safeResponse = escapeHtml(adminResponse).replace("\r\n", "<br>").replace("\n", "<br>");
    return """
        <div style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 580px; margin: 0 auto; background: #121318; color: #f8fafc; border-radius: 16px; overflow: hidden; border: 1px solid #2a2b36;">
          <div style="background: linear-gradient(135deg, #C91934 0%%, #7a0d1e 100%%); padding: 26px 30px; text-align: center;">
            <h1 style="margin: 0; font-size: 24px; font-weight: 800; color: #ffffff; letter-spacing: 0.04em;">BOX CINEMA</h1>
            <p style="margin: 6px 0 0; font-size: 13px; color: #ffd7de;">Support Inquiry Resolution</p>
          </div>
          <div style="padding: 30px;">
            <p style="font-size: 15px; margin-top: 0; color: #e2e8f0;">Dear <strong>%s</strong>,</p>
            <p style="font-size: 14px; line-height: 1.6; color: #cbd5e1;">
              Our Guest Relations team has reviewed and responded to your inquiry <strong>#BOX-REQ-%d</strong> (Topic: <em>%s</em>):
            </p>
            <div style="margin: 20px 0; padding: 18px; background: #161a24; border-left: 4px solid #10b981; border-radius: 8px;">
              <p style="margin: 0 0 8px; font-size: 12px; font-weight: 700; color: #34d399; letter-spacing: 0.05em; text-transform: uppercase;">Response from Management</p>
              <div style="font-size: 14px; line-height: 1.6; color: #f1f5f9;">
                %s
              </div>
            </div>
            <p style="font-size: 13px; color: #94a3b8; line-height: 1.5; margin-bottom: 0;">
              If you have any further questions or require additional assistance, please feel free to reply to this email or call our hotline at <strong style="color: #ff4d6a;">1900 6868</strong>.
            </p>
          </div>
          <div style="background: #0d0e14; padding: 16px 30px; text-align: center; border-top: 1px solid #22232e;">
            <p style="margin: 0; font-size: 12px; color: #64748b;">BOX Cinema Vietnam · Customer Relations Department</p>
          </div>
        </div>
        """
        .formatted(safeName, inquiryId, safeTopic, safeResponse);
  }

  private String buildPasswordResetOtpEmailHtml(String fullName, String otpCode) {
    String safeName = escapeHtml(fullName != null && !fullName.isBlank() ? fullName : "Member");
    String safeOtp = escapeHtml(otpCode);
    return """
        <div style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 560px; margin: 0 auto; background: #141418; color: #f8fafc; border-radius: 16px; overflow: hidden; border: 1px solid #2e2e38;">
          <div style="background: linear-gradient(135deg, #C91934 0%%, #7a0d1e 100%%); padding: 28px 32px; text-align: center;">
            <h1 style="margin: 0; font-size: 24px; font-weight: 800; color: #ffffff; letter-spacing: 0.04em;">BOX CINEMA</h1>
            <p style="margin: 6px 0 0; font-size: 13px; color: #ffd7de;">Password Reset Code</p>
          </div>
          <div style="padding: 32px;">
            <p style="font-size: 16px; margin-top: 0; color: #e2e8f0;">Hello <strong>%s</strong>,</p>
            <p style="font-size: 14px; line-height: 1.6; color: #cbd5e1;">
              We received a request to reset your BOX Cinema account password. Please enter the following 6-digit verification code:
            </p>
            <div style="margin: 28px 0; padding: 20px; background: #1f2029; border: 2px dashed #C91934; border-radius: 12px; text-align: center;">
              <span style="font-size: 34px; font-weight: 900; letter-spacing: 10px; color: #ffffff;">%s</span>
            </div>
            <p style="font-size: 13px; color: #94a3b8; margin-bottom: 8px;">
              This verification code expires in <strong>5 minutes</strong>.
            </p>
            <p style="font-size: 13px; color: #94a3b8; margin-bottom: 0;">
              If you did not request a password reset, please ignore this email. Your current password remains secure.
            </p>
          </div>
          <div style="background: #0d0e14; padding: 16px 30px; text-align: center; border-top: 1px solid #22232e;">
            <p style="margin: 0; font-size: 12px; color: #64748b;">BOX Cinema Vietnam · Secure Account Recovery</p>
          </div>
        </div>
        """
        .formatted(safeName, safeOtp);
  }

  private String buildGoogleAutoLinkOtpEmailHtml(String fullName, String otpCode) {
    String safeName = escapeHtml(fullName != null && !fullName.isBlank() ? fullName : "Member");
    String safeOtp = escapeHtml(otpCode);
    return """
        <div style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 560px; margin: 0 auto; background: #141418; color: #f8fafc; border-radius: 16px; overflow: hidden; border: 1px solid #2e2e38;">
          <div style="background: linear-gradient(135deg, #C91934 0%%, #7a0d1e 100%%); padding: 28px 32px; text-align: center;">
            <h1 style="margin: 0; font-size: 24px; font-weight: 800; color: #ffffff; letter-spacing: 0.04em;">BOX CINEMA</h1>
            <p style="margin: 6px 0 0; font-size: 13px; color: #ffd7de;">Google Sign-In Verification</p>
          </div>
          <div style="padding: 32px;">
            <p style="font-size: 16px; margin-top: 0; color: #e2e8f0;">Hi <strong>%s</strong>,</p>
            <p style="font-size: 14px; line-height: 1.6; color: #cbd5e1;">
              We received a request to link <strong>Google Sign-In</strong> to your existing <strong>BOX Cinema</strong> account. Please enter this one-time 6-digit verification code to confirm ownership of your email:
            </p>
            <div style="margin: 28px 0; padding: 20px; background: #1f2029; border: 2px dashed #C91934; border-radius: 12px; text-align: center;">
              <span style="font-size: 34px; font-weight: 900; letter-spacing: 10px; color: #ffffff;">%s</span>
            </div>
            <p style="font-size: 13px; color: #94a3b8; margin-bottom: 0;">
              This code expires in <strong>5 minutes</strong>. If you did not try to sign in with Google, please ignore this email or sign in using your BOX Cinema password.
            </p>
          </div>
        </div>
        """
        .formatted(safeName, safeOtp);
  }

  private String buildSecurityNoticeHtml(String fullName, String title, String message) {
    String safeName = escapeHtml(fullName != null && !fullName.isBlank() ? fullName : "Member");
    return """
        <div style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 560px; margin: 0 auto; background: #141418; color: #f8fafc; border-radius: 16px; overflow: hidden; border: 1px solid #2e2e38;">
          <div style="background: linear-gradient(135deg, #C91934 0%%, #7a0d1e 100%%); padding: 26px 30px; text-align: center;">
            <h1 style="margin: 0; font-size: 24px; font-weight: 800; color: #ffffff; letter-spacing: 0.04em;">BOX CINEMA</h1>
            <p style="margin: 6px 0 0; font-size: 13px; color: #ffd7de;">%s</p>
          </div>
          <div style="padding: 30px;">
            <p style="font-size: 15px; margin-top: 0; color: #e2e8f0;">Hi <strong>%s</strong>,</p>
            <p style="font-size: 14px; line-height: 1.6; color: #cbd5e1;">%s</p>
            <p style="font-size: 13px; color: #94a3b8; margin-bottom: 0;">
              If this was not you, please reset your password immediately or contact <strong>1900 6868</strong>.
            </p>
          </div>
        </div>
        """
        .formatted(escapeHtml(title), safeName, escapeHtml(message));
  }

  private String buildPasswordResetEmailHtml(String fullName) {
    String safeName = escapeHtml(fullName != null && !fullName.isBlank() ? fullName : "Member");
    return """
        <div style="font-family: 'Segoe UI', Arial, sans-serif; max-width: 560px; margin: 0 auto; background: #141418; color: #f8fafc; border-radius: 16px; overflow: hidden; border: 1px solid #2e2e38;">
          <div style="background: linear-gradient(135deg, #C91934 0%%, #7a0d1e 100%%); padding: 26px 30px; text-align: center;">
            <h1 style="margin: 0; font-size: 24px; font-weight: 800; color: #ffffff; letter-spacing: 0.04em;">BOX CINEMA</h1>
            <p style="margin: 6px 0 0; font-size: 13px; color: #ffd7de;">Security Notification</p>
          </div>
          <div style="padding: 30px;">
            <p style="font-size: 15px; margin-top: 0; color: #e2e8f0;">Hi <strong>%s</strong>,</p>
            <p style="font-size: 14px; line-height: 1.6; color: #cbd5e1;">
              Your <strong>BOX Cinema</strong> account password has just been updated. If you initiated this change, no further action is needed.
            </p>
            <p style="font-size: 13px; color: #94a3b8; margin-bottom: 0;">
              If you did not request a password reset, please contact our support team immediately at <strong>1900 6868</strong> or <strong>support@boxcinema.vn</strong>.
            </p>
          </div>
        </div>
        """
        .formatted(safeName);
  }
}
