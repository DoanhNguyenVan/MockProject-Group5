package com.boxcinema.customer.service;

import com.boxcinema.customer.dto.response.PayOsCreatePaymentResponseDto;
import com.boxcinema.customer.dto.response.PayOsWebhookPayloadDto;
import com.boxcinema.customer.dto.response.PaymentStatusDto;

import java.util.List;
import java.util.Map;

public interface PayOsService {
    PayOsCreatePaymentResponseDto createPayment(Long bookingId, Long currentUserId);
    PaymentStatusDto getPaymentStatus(Long paymentId);
    PaymentStatusDto getPaymentStatus(Long paymentId, Long currentUserId);
    boolean verifyWebhookSignature(PayOsWebhookPayloadDto payload);
    void processWebhook(PayOsWebhookPayloadDto payload);
    void simulatePayment(Long orderCode);
    default List<Map<String, Object>> listCollectionReconciliationQueue() {
        return List.of();
    }

    default Map<String, Object> resolveCollectionReconciliation(Long paymentId, String decision, String note, Long adminUserId) {
        throw new UnsupportedOperationException("Collection reconciliation not implemented.");
    }
}
