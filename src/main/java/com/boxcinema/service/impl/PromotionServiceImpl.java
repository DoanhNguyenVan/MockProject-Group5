package com.boxcinema.service.impl;

import com.boxcinema.entity.Promotion;
import com.boxcinema.repository.PromotionRepository;
import com.boxcinema.service.PromotionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class PromotionServiceImpl implements PromotionService {

    private final PromotionRepository promotionRepository;

    public PromotionServiceImpl(PromotionRepository promotionRepository) {
        this.promotionRepository = promotionRepository;
    }

    @Override
    public List<Promotion> getAvailablePromotions(BigDecimal orderAmount) {
        BigDecimal amount = orderAmount != null ? orderAmount : BigDecimal.ZERO;
        return promotionRepository.findAvailableForOrder(LocalDateTime.now(), amount);
    }

    @Override
    public BigDecimal validateAndCalculateDiscount(String voucherCode, BigDecimal orderAmount, Long userId) {
        if (voucherCode == null || voucherCode.isBlank()) {
            throw new IllegalArgumentException("Please enter a voucher code.");
        }

        Optional<Promotion> opt = promotionRepository.findByVoucherCodeIgnoreCase(voucherCode.trim());
        if (opt.isEmpty()) {
            throw new IllegalArgumentException("Voucher code \"" + voucherCode + "\" does not exist.");
        }

        Promotion p = opt.get();
        LocalDateTime now = LocalDateTime.now();

        // 1. Status check
        if (!"ACTIVE".equalsIgnoreCase(p.getStatus())) {
            throw new IllegalArgumentException("Voucher code \"" + voucherCode + "\" is no longer active.");
        }

        // 2. Date range check
        if (p.getStartAt() != null && now.isBefore(p.getStartAt())) {
            throw new IllegalArgumentException("Voucher code \"" + voucherCode + "\" is not yet valid.");
        }
        if (p.getEndAt() != null && now.isAfter(p.getEndAt())) {
            throw new IllegalArgumentException("Voucher code \"" + voucherCode + "\" has expired.");
        }

        // 3. Minimum order check
        if (p.getMinOrderAmount() != null && orderAmount.compareTo(p.getMinOrderAmount()) < 0) {
            String minFormatted = String.format("%,.0f", p.getMinOrderAmount()) + " ₫";
            throw new IllegalArgumentException(
                    "Minimum order amount of " + minFormatted + " required to use this voucher.");
        }

        // 4. Total usage limit check
        if (p.getUsageLimit() != null) {
            long totalUsed = promotionRepository.countTotalUsage(p.getPromotionId());
            if (totalUsed >= p.getUsageLimit()) {
                throw new IllegalArgumentException("Voucher code \"" + voucherCode + "\" has reached its usage limit.");
            }
        }

        // 5. Per-user usage limit check (only for logged-in users)
        if (userId != null && p.getPerUserLimit() != null) {
            long userUsed = promotionRepository.countUsageByUser(p.getPromotionId(), userId);
            if (userUsed >= p.getPerUserLimit()) {
                throw new IllegalArgumentException("You have reached the usage limit for this voucher.");
            }
        }

        // 6. Calculate discount amount
        BigDecimal discount;
        if ("PERCENT".equalsIgnoreCase(p.getDiscountType())) {
            // discount = orderAmount * discountValue / 100
            discount = orderAmount
                    .multiply(p.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            // Apply max_discount cap
            if (p.getMaxDiscount() != null && discount.compareTo(p.getMaxDiscount()) > 0) {
                discount = p.getMaxDiscount();
            }
        } else {
            // FIXED discount
            discount = p.getDiscountValue();
        }

        // Ensure discount does not exceed order amount
        if (discount.compareTo(orderAmount) > 0) {
            discount = orderAmount;
        }

        return discount.setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public Promotion findByVoucherCode(String voucherCode) {
        if (voucherCode == null || voucherCode.isBlank()) return null;
        return promotionRepository.findByVoucherCodeIgnoreCase(voucherCode.trim()).orElse(null);
    }
}
