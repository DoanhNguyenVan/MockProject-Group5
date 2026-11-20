package com.boxcinema.customer.service;

import com.boxcinema.common.entity.Inquiry;

public interface InquiryService {
    Inquiry createInquiry(String fullName, String email, String phone, String topic, String message, Long userId);
}
