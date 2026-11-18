package com.boxcinema.repository;

import com.boxcinema.entity.Promotion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    Optional<Promotion> findByVoucherCodeIgnoreCase(String voucherCode);

    /** All ACTIVE promotions whose window covers now and have remaining usage. */
    @Query("SELECT p FROM Promotion p WHERE p.status = 'ACTIVE' " +
           "AND (p.startAt IS NULL OR p.startAt <= :now) " +
           "AND (p.endAt IS NULL OR p.endAt >= :now) " +
           "AND (p.minOrderAmount IS NULL OR p.minOrderAmount <= :orderAmount) " +
           "ORDER BY p.discountValue DESC")
    List<Promotion> findAvailableForOrder(@Param("now") LocalDateTime now,
                                          @Param("orderAmount") BigDecimal orderAmount);

    /** Count how many times a user has used a specific promotion via bookings. */
    @Query(value = "SELECT COUNT(*) FROM bookings b WHERE b.promotion_id = :promotionId AND b.user_id = :userId " +
                   "AND b.booking_status NOT IN ('CANCELLED', 'DRAFT')", nativeQuery = true)
    long countUsageByUser(@Param("promotionId") Long promotionId, @Param("userId") Long userId);

    /** Count total usage of a promotion. */
    @Query(value = "SELECT COUNT(*) FROM bookings b WHERE b.promotion_id = :promotionId " +
                   "AND b.booking_status NOT IN ('CANCELLED', 'DRAFT')", nativeQuery = true)
    long countTotalUsage(@Param("promotionId") Long promotionId);
}
