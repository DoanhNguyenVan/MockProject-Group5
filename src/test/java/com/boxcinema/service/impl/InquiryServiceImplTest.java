package com.boxcinema.service.impl;

import com.boxcinema.entity.Inquiry;
import com.boxcinema.repository.InquiryRepository;
import com.boxcinema.service.EmailService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class InquiryServiceImplTest {

    @Test
    void testCreateInquiryPersistsAndTriggersAsyncConfirmationEmail() {
        InquiryRepository repo = mock(InquiryRepository.class);
        EmailService emailService = mock(EmailService.class);

        when(repo.save(any(Inquiry.class))).thenAnswer(inv -> {
            Inquiry inq = inv.getArgument(0);
            inq.setInquiryId(101L);
            return inq;
        });

        InquiryServiceImpl service = new InquiryServiceImpl(repo, emailService);
        Inquiry saved = service.createInquiry(
                "Alex Nguyen",
                "ALEX@GMAIL.COM",
                "0901234567",
                "Booking & E-Ticket Support",
                "Need assistance with ticket reference.",
                5L
        );

        assertEquals(101L, saved.getInquiryId());
        assertEquals("alex@gmail.com", saved.getEmail());
        verify(repo, times(1)).save(any(Inquiry.class));
        verify(emailService, times(1)).sendInquiryConfirmationEmailAsync(
                "alex@gmail.com",
                "Alex Nguyen",
                101L,
                "Booking & E-Ticket Support",
                "Need assistance with ticket reference."
        );
        verify(emailService, never()).sendInquiryConfirmationEmail(any(), any(), any(), any(), any());
    }
}
