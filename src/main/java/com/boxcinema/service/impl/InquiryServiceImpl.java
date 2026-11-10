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
            throw new IllegalArgumentException("Vui lòng nhập họ và tên (tối thiểu 2 ký tự).");
        }
        if (email == null || email.trim().isBlank()) {
            throw new IllegalArgumentException("Vui lòng nhập địa chỉ email hợp lệ.");
        }
        if (topic == null || topic.trim().isBlank()) {
            throw new IllegalArgumentException("Vui lòng chọn chủ đề liên hệ.");
        }
        if (message == null || message.trim().length() < 6) {
            throw new IllegalArgumentException("Nội dung tin nhắn cần tối thiểu 6 ký tự.");
        }

        Inquiry inquiry = new Inquiry(
                fullName.trim(),
                email.trim().toLowerCase(),
                (phone != null && !phone.isBlank()) ? phone.trim() : null,
                topic.trim(),
                message.trim()
        );
        inquiry.setUserId(userId);
        inquiry = inquiryRepository.save(inquiry);

        // Send confirmation email to customer
        if (emailService != null) {
            try {
                emailService.sendInquiryConfirmationEmail(
                        inquiry.getEmail(),
                        inquiry.getFullName(),
                        inquiry.getInquiryId(),
                        inquiry.getInquiryTopic(),
                        inquiry.getMessage()
                );
            } catch (Exception ex) {
                log.warn("Could not dispatch confirmation email for inquiry #{}: {}", inquiry.getInquiryId(), ex.getMessage());
            }
        }

        return inquiry;
    }
}

