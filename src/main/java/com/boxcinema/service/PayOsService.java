package com.boxcinema.service;

import com.boxcinema.dto.PayOsCreatePaymentResponseDto;
import com.boxcinema.dto.PayOsWebhookPayloadDto;
import com.boxcinema.dto.PaymentStatusDto;

public interface PayOsService {
    PayOsCreatePaymentResponseDto createPayment(Long bookingId, Long currentUserId);
    PaymentStatusDto getPaymentStatus(Long paymentId);
    boolean verifyWebhookSignature(PayOsWebhookPayloadDto payload);
    void processWebhook(PayOsWebhookPayloadDto payload);
    void simulatePayment(Long orderCode);
}
