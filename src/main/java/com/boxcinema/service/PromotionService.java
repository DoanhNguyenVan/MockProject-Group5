package com.boxcinema.service;

import com.boxcinema.entity.Promotion;

import java.math.BigDecimal;
import java.util.List;

public interface PromotionService {

    /**
     * Returns all currently active promotions available for the given order amount.
     * Used to populate the "Available vouchers" list in the payment step.
     */
    List<Promotion> getAvailablePromotions(BigDecimal orderAmount);

    /**
     * Validates a voucher code and calculates the discount amount.
     * Throws IllegalArgumentException with a user-friendly message on failure.
     *
     * @param voucherCode  the code entered by the customer
     * @param orderAmount  the current order total (ticket + food) BEFORE discount
     * @param userId       the logged-in user's ID (null for guest — no per-user check)
     * @return the computed discount amount (never negative, never exceeds orderAmount)
     */
    BigDecimal validateAndCalculateDiscount(String voucherCode, BigDecimal orderAmount, Long userId);

    /**
     * Looks up a promotion by voucher code (case-insensitive).
     * Returns null if not found.
     */
    Promotion findByVoucherCode(String voucherCode);
}
