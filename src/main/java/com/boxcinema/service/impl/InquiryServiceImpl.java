package com.boxcinema.service.impl;

import com.boxcinema.entity.Inquiry;
import com.boxcinema.repository.InquiryRepository;
import com.boxcinema.service.InquiryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class InquiryServiceImpl implements InquiryService {

    private final InquiryRepository inquiryRepository;

    public InquiryServiceImpl(InquiryRepository inquiryRepository) {
        this.inquiryRepository = inquiryRepository;
    }

    @Override
    public Inquiry createInquiry(String fullName, String email, String phone, String topic, String message, Long userId) {
        Inquiry inquiry = new Inquiry(fullName, email, phone, topic, message);
        inquiry.setUserId(userId);
        return inquiryRepository.save(inquiry);
    }
}

