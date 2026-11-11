package com.boxcinema.service.impl;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class EmailServiceImplTest {

    @Test
    void testSendVerificationOtpEmailUsesJavaMailSender() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        EmailServiceImpl service = new EmailServiceImpl(mailSender);
        ReflectionTestUtils.setField(service, "smtpUsername", "support@boxcinema.vn");
        ReflectionTestUtils.setField(service, "smtpPassword", "app-secret-pass");
        ReflectionTestUtils.setField(service, "smtpHost", "smtp.gmail.com");
        ReflectionTestUtils.setField(service, "fromName", "BOX Cinema");

        boolean result = service.sendVerificationOtpEmail("alex@gmail.com", "Alex Nguyen", "123456");

        assertTrue(result);
        verify(mailSender, times(1)).send(mimeMessage);
        assertEquals("BOX Cinema - Verify Your Account (OTP: 123456)", mimeMessage.getSubject());
    }

    @Test
    void testSendInquiryConfirmationEmailUsesJavaMailSender() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        EmailServiceImpl service = new EmailServiceImpl(mailSender);
        ReflectionTestUtils.setField(service, "smtpUsername", "support@boxcinema.vn");
        ReflectionTestUtils.setField(service, "smtpPassword", "app-secret-pass");
        ReflectionTestUtils.setField(service, "smtpHost", "smtp.gmail.com");
        ReflectionTestUtils.setField(service, "fromName", "BOX Cinema");

        boolean result = service.sendInquiryConfirmationEmail(
                "guest@gmail.com",
                "Minh <script>",
                42L,
                "Booking & E-Ticket Support",
                "Need help with <ticket>"
        );

        assertTrue(result);
        verify(mailSender, times(1)).send(mimeMessage);
        assertEquals("BOX Cinema - Support Inquiry Received [#BOX-REQ-42]", mimeMessage.getSubject());
    }

    @Test
    void testSendPasswordResetConfirmationEmailUsesJavaMailSender() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        EmailServiceImpl service = new EmailServiceImpl(mailSender);
        ReflectionTestUtils.setField(service, "smtpUsername", "support@boxcinema.vn");
        ReflectionTestUtils.setField(service, "smtpPassword", "app-secret-pass");
        ReflectionTestUtils.setField(service, "smtpHost", "smtp.gmail.com");
        ReflectionTestUtils.setField(service, "fromName", "BOX Cinema");

        boolean result = service.sendPasswordResetConfirmationEmail("alex@gmail.com", "Alex");

        assertTrue(result);
        verify(mailSender, times(1)).send(mimeMessage);
        assertEquals("BOX Cinema - Your Password Was Successfully Reset", mimeMessage.getSubject());
    }

    @Test
    void testFallsBackGracefullyWhenSmtpNotConfigured() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        EmailServiceImpl service = new EmailServiceImpl(mailSender);
        ReflectionTestUtils.setField(service, "smtpUsername", "");
        ReflectionTestUtils.setField(service, "smtpPassword", "");

        boolean result = service.sendVerificationOtpEmail("alex@gmail.com", "Alex", "654321");

        assertFalse(result);
        verify(mailSender, never()).send(any(MimeMessage.class));
    }
}

