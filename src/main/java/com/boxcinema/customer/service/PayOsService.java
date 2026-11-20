package com.boxcinema.customer.service;

import com.boxcinema.customer.dto.response.PayOsCreatePaymentResponseDto;
import com.boxcinema.customer.dto.response.PayOsWebhookPayloadDto;
import com.boxcinema.customer.dto.response.PaymentStatusDto;

public interface PayOsService {
    PayOsCreatePaymentResponseDto createPayment(Long bookingId, Long currentUserId);
    PaymentStatusDto getPaymentStatus(Long paymentId);
    boolean verifyWebhookSignature(PayOsWebhookPayloadDto payload);
    void processWebhook(PayOsWebhookPayloadDto payload);
    void simulatePayment(Long orderCode);
}
