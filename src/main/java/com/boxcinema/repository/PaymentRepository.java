package com.boxcinema.repository;

import com.boxcinema.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {
    Optional<Payment> findByOrderCode(Long orderCode);
    Optional<Payment> findByPaymentLinkId(String paymentLinkId);
    Optional<Payment> findByBooking_BookingId(Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.orderCode = :orderCode")
    Optional<Payment> findByOrderCodeForUpdate(@Param("orderCode") Long orderCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.paymentId = :paymentId")
    Optional<Payment> findByIdForUpdate(@Param("paymentId") Long paymentId);
}
