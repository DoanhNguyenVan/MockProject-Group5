package com.boxcinema.repository;

import com.boxcinema.entity.PaymentWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, Long> {
    boolean existsByEventFingerprint(String eventFingerprint);
    Optional<PaymentWebhookEvent> findByEventFingerprint(String eventFingerprint);
}
