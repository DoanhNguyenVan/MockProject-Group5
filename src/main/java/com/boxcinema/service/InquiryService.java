package com.boxcinema.service;

import com.boxcinema.entity.Inquiry;

public interface InquiryService {
    Inquiry createInquiry(String fullName, String email, String phone, String topic, String message, Long userId);
}
