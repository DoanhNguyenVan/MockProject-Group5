package com.boxcinema.common.repository;

import com.boxcinema.common.entity.Promotion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Promotion p WHERE p.voucherCode = :voucherCode")
    Optional<Promotion> findByVoucherCodeIgnoreCaseForUpdate(@Param("voucherCode") String voucherCode);

    /** All ACTIVE public promotions whose window covers now and have remaining usage. */
    @Query("SELECT p FROM Promotion p WHERE p.status = 'ACTIVE' " +
           "AND p.isPublic = true " +
           "AND (p.startAt IS NULL OR p.startAt <= :now) " +
           "AND (p.endAt IS NULL OR p.endAt >= :now) " +
           "AND (p.minOrderAmount IS NULL OR p.minOrderAmount <= :orderAmount) " +
           "ORDER BY p.discountValue DESC")
    List<Promotion> findAvailableForOrder(@Param("now") LocalDateTime now,
                                          @Param("orderAmount") BigDecimal orderAmount);

    /** All ACTIVE public promotions plus private recovery vouchers owned by the caller. */
    @Query("SELECT p FROM Promotion p WHERE p.status = 'ACTIVE' " +
           "AND (p.startAt IS NULL OR p.startAt <= :now) " +
           "AND (p.endAt IS NULL OR p.endAt >= :now) " +
           "AND (p.isPublic = true " +
           "     OR (p.targetUserId IS NOT NULL AND p.targetUserId = :userId) " +
           "     OR (p.targetUserId IS NULL AND :email IS NOT NULL AND LOWER(p.targetEmail) = LOWER(:email))) " +
           "ORDER BY p.isPublic ASC, p.discountValue DESC")
    List<Promotion> findMyAvailablePromotions(@Param("userId") Long userId,
                                              @Param("email") String email,
                                              @Param("now") LocalDateTime now);

    /** Count how many times a user has used a specific promotion via bookings (including active DRAFT holds). */
    @Query(value = "SELECT COUNT(*) FROM bookings b WHERE b.promotion_id = :promotionId AND b.user_id = :userId " +
                   "AND b.booking_status NOT IN ('CANCELLED', 'EXPIRED')", nativeQuery = true)
    long countUsageByUser(@Param("promotionId") Long promotionId, @Param("userId") Long userId);

    /** Count how many times a guest email has used a specific promotion via bookings. */
    @Query(value = "SELECT COUNT(*) FROM bookings b WHERE b.promotion_id = :promotionId AND LOWER(b.guest_email) = LOWER(:guestEmail) " +
                   "AND b.booking_status NOT IN ('CANCELLED', 'EXPIRED')", nativeQuery = true)
    long countUsageByGuestEmail(@Param("promotionId") Long promotionId, @Param("guestEmail") String guestEmail);

    /** Count total usage of a promotion (including active DRAFT holds). */
    @Query(value = "SELECT COUNT(*) FROM bookings b WHERE b.promotion_id = :promotionId " +
                   "AND b.booking_status NOT IN ('CANCELLED', 'EXPIRED')", nativeQuery = true)
    long countTotalUsage(@Param("promotionId") Long promotionId);

    /** Count usage by a specific user excluding a booking currently being updated (e.g. PENDING_PAYMENT). */
    @Query(value = "SELECT COUNT(*) FROM bookings b WHERE b.promotion_id = :promotionId AND b.user_id = :userId " +
                   "AND b.booking_status NOT IN ('CANCELLED', 'EXPIRED') AND b.booking_id <> :excludeBookingId", nativeQuery = true)
    long countUsageByUserExcludingBooking(@Param("promotionId") Long promotionId,
                                          @Param("userId") Long userId,
                                          @Param("excludeBookingId") Long excludeBookingId);

    /** Count usage by a guest email excluding a booking currently being updated. */
    @Query(value = "SELECT COUNT(*) FROM bookings b WHERE b.promotion_id = :promotionId AND LOWER(b.guest_email) = LOWER(:guestEmail) " +
                   "AND b.booking_status NOT IN ('CANCELLED', 'EXPIRED') AND b.booking_id <> :excludeBookingId", nativeQuery = true)
    long countUsageByGuestEmailExcludingBooking(@Param("promotionId") Long promotionId,
                                                @Param("guestEmail") String guestEmail,
                                                @Param("excludeBookingId") Long excludeBookingId);

    /** Count total usage excluding a booking currently being updated (e.g. PENDING_PAYMENT). */
    @Query(value = "SELECT COUNT(*) FROM bookings b WHERE b.promotion_id = :promotionId " +
                   "AND b.booking_status NOT IN ('CANCELLED', 'EXPIRED') AND b.booking_id <> :excludeBookingId", nativeQuery = true)
    long countTotalUsageExcludingBooking(@Param("promotionId") Long promotionId,
                                         @Param("excludeBookingId") Long excludeBookingId);
}
