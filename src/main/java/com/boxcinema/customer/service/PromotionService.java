package com.boxcinema.customer.service;

import com.boxcinema.common.entity.Booking;
import com.boxcinema.common.entity.OrderVoucherRedemption;
import com.boxcinema.common.entity.Promotion;

import java.math.BigDecimal;
import java.util.List;

public interface PromotionService {

    /**
     * Returns all currently active promotions available for the given order amount.
     * Used to populate the "Available vouchers" list in the payment step.
     */
    List<Promotion> getAvailablePromotions(BigDecimal orderAmount);

    List<Promotion> getMyAvailablePromotions(Long userId, String userEmail);

    /**
     * Validates a voucher code and calculates the discount amount.
     * Throws IllegalArgumentException with a user-friendly message on failure.
     */
    BigDecimal validateAndCalculateDiscount(String voucherCode, BigDecimal orderAmount, Long userId);

    BigDecimal validateAndCalculateDiscount(String voucherCode, BigDecimal orderAmount, Long userId, String userEmail);

    /**
     * Concurrency-safe validation with PESSIMISTIC_WRITE lock on Promotion,
     * also checking private refund-recovery voucher ownership.
     */
    BigDecimal validateAndLockForRedemption(String voucherCode, BigDecimal orderAmount, Long userId, String userEmail);

    BigDecimal validateAndLockForRedemption(String voucherCode, BigDecimal orderAmount, Long userId, String userEmail, Long excludeBookingId);

    /**
     * Enforces one-voucher-per-order in backend + DB (uk_ovr_booking_id).
     * Rejects attempting to apply a second voucher to an order that already has an active voucher
     * with BusinessException(HttpStatus.CONFLICT, "ORDER_ALREADY_HAS_VOUCHER", ...).
     */
    OrderVoucherRedemption redeemVoucherForOrder(
            Booking booking,
            String voucherCode,
            BigDecimal eligibleOrderAmount,
            Long userId,
            String userEmail);

    /**
     * Looks up a promotion by voucher code (case-insensitive).
     * Returns null if not found.
     */
    Promotion findByVoucherCode(String voucherCode);
}
