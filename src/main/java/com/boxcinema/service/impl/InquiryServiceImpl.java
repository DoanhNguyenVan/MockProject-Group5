package com.boxcinema.service.impl;

import com.boxcinema.entity.Inquiry;
import com.boxcinema.repository.InquiryRepository;
import com.boxcinema.service.EmailService;
import com.boxcinema.service.InquiryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Transactional
public class InquiryServiceImpl implements InquiryService {

    private static final Logger log = LoggerFactory.getLogger(InquiryServiceImpl.class);

    private final InquiryRepository inquiryRepository;
    private final EmailService emailService;

    public InquiryServiceImpl(InquiryRepository inquiryRepository,
                              @Autowired(required = false) EmailService emailService) {
        this.inquiryRepository = inquiryRepository;
        this.emailService = emailService;
    }

    @Override
    public Inquiry createInquiry(String fullName, String email, String phone, String topic, String message, Long userId) {
        if (fullName == null || fullName.trim().length() < 2) {
            throw new IllegalArgumentException("Please enter your full name (at least 2 characters).");
        }
        if (email == null || email.trim().isBlank()) {
            throw new IllegalArgumentException("Please enter a valid email address.");
        }
        if (topic == null || topic.trim().isBlank()) {
            throw new IllegalArgumentException("Please select an inquiry topic.");
        }
        if (message == null || message.trim().length() < 6) {
            throw new IllegalArgumentException("Please enter a message with at least 6 characters.");
        }

        Inquiry inquiry = new Inquiry(
                fullName.trim(),
                email.trim().toLowerCase(),
                (phone != null && !phone.isBlank()) ? phone.trim() : null,
                topic.trim(),
                message.trim()
        );
        inquiry.setUserId(userId);
        Inquiry saved = inquiryRepository.save(inquiry);

        // Dispatch confirmation email asynchronously after transaction commits
        // so the web UI shows instant success while SMTP runs on the background pool.
        if (emailService != null) {
            Runnable sendEmailTask = () -> {
                try {
                    emailService.sendInquiryConfirmationEmailAsync(
                            saved.getEmail(),
                            saved.getFullName(),
                            saved.getInquiryId(),
                            saved.getInquiryTopic(),
                            saved.getMessage()
                    );
                } catch (Exception ex) {
                    log.warn("Could not schedule confirmation email for inquiry #{}: {}", saved.getInquiryId(), ex.getMessage());
                }
            };

            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        sendEmailTask.run();
                    }
                });
            } else {
                sendEmailTask.run();
            }
        }

        return saved;
    }
}

