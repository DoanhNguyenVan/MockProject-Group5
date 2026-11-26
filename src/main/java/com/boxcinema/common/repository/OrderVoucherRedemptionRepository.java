package com.boxcinema.common.repository;

import com.boxcinema.common.entity.OrderVoucherRedemption;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OrderVoucherRedemptionRepository extends JpaRepository<OrderVoucherRedemption, Long> {

    Optional<OrderVoucherRedemption> findByBooking_BookingId(Long bookingId);

    boolean existsByBooking_BookingId(Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM OrderVoucherRedemption r WHERE r.booking.bookingId = :bookingId")
    Optional<OrderVoucherRedemption> findByBookingIdForUpdate(@Param("bookingId") Long bookingId);

    List<OrderVoucherRedemption> findByPromotion_PromotionId(Long promotionId);
}
