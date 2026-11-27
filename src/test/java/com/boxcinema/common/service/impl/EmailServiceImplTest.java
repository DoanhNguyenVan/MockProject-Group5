package com.boxcinema.common.service.impl;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Properties;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

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
        assertEquals("BOX Cinema - Verify Your Account", mimeMessage.getSubject());
        assertFalse(mimeMessage.getSubject().contains("123456"));
    }

    @Test
    void testSendPasswordResetOtpEmailExcludesOtpFromSubject() throws Exception {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        MimeMessage mimeMessage = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);

        EmailServiceImpl service = new EmailServiceImpl(mailSender);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "smtpUsername", "box@gmail.com");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "smtpPassword", "app-pass");

        boolean result = service.sendPasswordResetOtpEmail("alex@gmail.com", "Alex Nguyen", "654321");

        assertTrue(result);
        verify(mailSender, times(1)).send(mimeMessage);
        assertEquals("BOX Cinema - Password Reset Verification Code", mimeMessage.getSubject());
        assertFalse(mimeMessage.getSubject().contains("654321"));
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

    @Test
    void testSendTicketConfirmationEmailDispatchesViaMailjetWithInlineQrAttachment() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        byte[] qrBytes = new byte[]{10, 20, 30, 40};
        String expectedB64 = Base64.getEncoder().encodeToString(qrBytes);

        server.expect(requestTo("https://api.mailjet.com/v3.1/send"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("bookingQrCode")))
                .andExpect(content().string(containsString(expectedB64)))
                .andExpect(content().string(containsString("https://box-cinema.onrender.com/t/BOX1.xyz")))
                .andRespond(withSuccess("{\"Messages\":[{\"Status\":\"success\"}]}", MediaType.APPLICATION_JSON));

        EmailServiceImpl service = new EmailServiceImpl(null, builder);
        ReflectionTestUtils.setField(service, "mailjetApiKey", "mj-key");
        ReflectionTestUtils.setField(service, "mailjetSecretKey", "mj-secret");
        ReflectionTestUtils.setField(service, "mailjetSenderEmail", "sender@boxcinema.vn");

        assertTrue(service.isMailTransportConfigured());
        boolean sent = service.sendTicketConfirmationEmail(
                "buyer@gmail.com",
                "BOX Cinema - Booking Confirmation #BOX-1",
                "<img src='cid:bookingQrCode'/><a href='https://box-cinema.onrender.com/t/BOX1.xyz'>Ticket</a>",
                qrBytes
        );

        assertTrue(sent);
        server.verify();
    }
}

