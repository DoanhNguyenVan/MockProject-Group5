package com.boxcinema.customer.service.impl;

import com.boxcinema.common.entity.Booking;
import com.boxcinema.common.entity.OrderVoucherRedemption;
import com.boxcinema.common.entity.Promotion;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.OrderVoucherRedemptionRepository;
import com.boxcinema.common.repository.PromotionRepository;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import com.boxcinema.customer.service.PromotionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class PromotionServiceImpl implements PromotionService {

    private final PromotionRepository promotionRepository;
    private final OrderVoucherRedemptionRepository redemptionRepository;

    public PromotionServiceImpl(PromotionRepository promotionRepository) {
        this(promotionRepository, null);
    }

    @Autowired
    public PromotionServiceImpl(
            PromotionRepository promotionRepository,
            @Autowired(required = false) OrderVoucherRedemptionRepository redemptionRepository) {
        this.promotionRepository = promotionRepository;
        this.redemptionRepository = redemptionRepository;
    }

    @Override
    public List<Promotion> getAvailablePromotions(BigDecimal orderAmount) {
        BigDecimal amount = orderAmount != null ? orderAmount : BigDecimal.ZERO;
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Order amount cannot be negative.");
        }
        return promotionRepository.findAvailableForOrder(LocalDateTime.now(), amount);
    }

    @Override
    public List<Promotion> getMyAvailablePromotions(Long userId, String userEmail) {
        if (userId == null) {
            return List.of();
        }
        return promotionRepository.findMyAvailablePromotions(userId, userEmail, LocalDateTime.now());
    }

    @Override
    public BigDecimal validateAndCalculateDiscount(String voucherCode, BigDecimal orderAmount, Long userId) {
        return validateAndCalculateDiscount(voucherCode, orderAmount, userId, null);
    }

    @Override
    public BigDecimal validateAndCalculateDiscount(String voucherCode, BigDecimal orderAmount, Long userId, String userEmail) {
        if (voucherCode == null || voucherCode.isBlank()) {
            throw new IllegalArgumentException("Please enter a voucher code.");
        }
        rejectMultipleVoucherCodesInInput(voucherCode);

        Optional<Promotion> opt = promotionRepository.findByVoucherCodeIgnoreCase(voucherCode.trim());
        if (opt.isEmpty()) {
            throw new IllegalArgumentException("Voucher code \"" + voucherCode + "\" does not exist.");
        }
        return validatePromotionAndCompute(opt.get(), voucherCode, orderAmount, userId, userEmail, null);
    }

    @Override
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public BigDecimal validateAndLockForRedemption(String voucherCode, BigDecimal orderAmount, Long userId, String userEmail) {
        return validateAndLockForRedemption(voucherCode, orderAmount, userId, userEmail, null);
    }

    @Override
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public BigDecimal validateAndLockForRedemption(String voucherCode, BigDecimal orderAmount, Long userId, String userEmail, Long excludeBookingId) {
        if (voucherCode == null || voucherCode.isBlank()) {
            throw new IllegalArgumentException("Please enter a voucher code.");
        }
        rejectMultipleVoucherCodesInInput(voucherCode);

        Optional<Promotion> opt = promotionRepository.findByVoucherCodeIgnoreCaseForUpdate(voucherCode.trim());
        if (opt.isEmpty()) {
            throw new IllegalArgumentException("Voucher code \"" + voucherCode + "\" does not exist.");
        }
        return validatePromotionAndCompute(opt.get(), voucherCode, orderAmount, userId, userEmail, excludeBookingId);
    }

    @Override
    @Transactional
    public OrderVoucherRedemption redeemVoucherForOrder(
            Booking booking,
            String voucherCode,
            BigDecimal eligibleOrderAmount,
            Long userId,
            String userEmail) {
        if (booking == null || booking.getBookingId() == null) {
            throw new IllegalArgumentException("A persisted booking order is required to redeem a voucher.");
        }
        if (voucherCode == null || voucherCode.isBlank()) {
            throw new IllegalArgumentException("Please enter a voucher code.");
        }
        rejectMultipleVoucherCodesInInput(voucherCode);
        String cleanCode = voucherCode.trim().toUpperCase(Locale.ROOT);

        // Enforce One-Voucher-Per-Order before DB insert
        if (booking.getPromotion() != null
                || (booking.getVoucherCodeSnapshot() != null && !booking.getVoucherCodeSnapshot().isBlank())) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "ORDER_ALREADY_HAS_VOUCHER",
                    "Order already has an applied voucher. Only one voucher can be applied per order.");
        }
        if (redemptionRepository != null) {
            Optional<OrderVoucherRedemption> existingOpt = redemptionRepository.findByBooking_BookingId(booking.getBookingId());
            if (existingOpt.isPresent()) {
                OrderVoucherRedemption existing = existingOpt.get();
                if (!"CANCELLED".equalsIgnoreCase(existing.getStatus())) {
                    throw new BusinessException(
                            HttpStatus.CONFLICT,
                            "ORDER_ALREADY_HAS_VOUCHER",
                            "Order already has an applied voucher (" + existing.getVoucherCode() + "). Only one voucher is allowed per order.");
                }
            }
        }

        BigDecimal discount = validateAndLockForRedemption(cleanCode, eligibleOrderAmount, userId, userEmail, booking.getBookingId());
        Promotion promo = findByVoucherCode(cleanCode);
        if (promo == null) {
            throw new IllegalArgumentException("Voucher code \"" + voucherCode + "\" does not exist.");
        }

        long eligibleVnd = PurchaseSnapshotService.toVndLong(eligibleOrderAmount);
        long actualDiscountVnd = PurchaseSnapshotService.toVndLong(discount);

        booking.setPromotion(promo);
        booking.setVoucherCodeSnapshot(cleanCode);
        booking.setPromotionDiscountTypeSnapshot(promo.getDiscountType());
        booking.setPromotionDiscountValueSnapshot(promo.getDiscountValue());
        booking.setPromotionMaxDiscountSnapshot(promo.getMaxDiscount());
        booking.setPromotionEndAtSnapshot(promo.getEndAt());
        booking.setDiscountAmount(BigDecimal.valueOf(actualDiscountVnd).setScale(2, RoundingMode.HALF_UP));
        booking.recalculateTotals();

        OrderVoucherRedemption redemption = new OrderVoucherRedemption();
        if (redemptionRepository != null) {
            redemption = redemptionRepository.findByBooking_BookingId(booking.getBookingId()).orElse(redemption);
        }
        redemption.setBooking(booking);
        redemption.setPromotion(promo);
        redemption.setVoucherCode(cleanCode);
        redemption.setDiscountType(promo.getDiscountType());
        redemption.setDiscountValue(promo.getDiscountValue());
        redemption.setMaxDiscount(promo.getMaxDiscount());
        redemption.setEligibleAmount(eligibleVnd);
        redemption.setActualDiscount(actualDiscountVnd);
        redemption.setStatus("APPLIED");
        redemption.setRedeemedAt(LocalDateTime.now());
        redemption.setRestoredAt(null);

        if (redemptionRepository != null) {
            try {
                redemption = redemptionRepository.saveAndFlush(redemption);
            } catch (DataIntegrityViolationException ex) {
                throw new BusinessException(
                        HttpStatus.CONFLICT,
                        "ORDER_ALREADY_HAS_VOUCHER",
                        "Order already has an applied voucher (one-voucher-per-order constraint uk_ovr_booking_id).");
            }
        }
        return redemption;
    }

    private void rejectMultipleVoucherCodesInInput(String voucherCode) {
        if (voucherCode != null && (voucherCode.contains(",") || voucherCode.contains(";"))) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "ORDER_ALREADY_HAS_VOUCHER",
                    "Only one voucher code can be applied per order.");
        }
    }

    private BigDecimal validatePromotionAndCompute(Promotion p, String voucherCode, BigDecimal orderAmount, Long userId, String userEmail, Long excludeBookingId) {
        if (orderAmount == null || orderAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Order amount must be zero or positive.");
        }
        LocalDateTime now = LocalDateTime.now();

        // 1. Status check
        if (!"ACTIVE".equalsIgnoreCase(p.getStatus())) {
            throw new IllegalArgumentException("Voucher code \"" + voucherCode + "\" is no longer active.");
        }

        // 1b. Private refund-recovery voucher ownership check
        if (!p.isPublic()) {
            if (p.getTargetUserId() != null) {
                if (userId == null || !p.getTargetUserId().equals(userId)) {
                    throw new IllegalArgumentException("This recovery voucher belongs to another customer account.");
                }
            } else {
                boolean matchesEmail = userEmail != null && !userEmail.isBlank()
                        && p.getTargetEmail() != null && p.getTargetEmail().equalsIgnoreCase(userEmail.trim());
                if (!matchesEmail) {
                    throw new IllegalArgumentException("This recovery voucher belongs to another customer account or email.");
                }
            }
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
            long totalUsed = excludeBookingId != null
                    ? promotionRepository.countTotalUsageExcludingBooking(p.getPromotionId(), excludeBookingId)
                    : promotionRepository.countTotalUsage(p.getPromotionId());
            if (totalUsed >= p.getUsageLimit()) {
                throw new IllegalArgumentException("Voucher code \"" + voucherCode + "\" has reached its usage limit.");
            }
        }

        // 5. Per-user usage limit check (enforced for both registered users and guest email)
        if (p.getPerUserLimit() != null) {
            if (userId != null) {
                long userUsed = excludeBookingId != null
                        ? promotionRepository.countUsageByUserExcludingBooking(p.getPromotionId(), userId, excludeBookingId)
                        : promotionRepository.countUsageByUser(p.getPromotionId(), userId);
                if (userUsed >= p.getPerUserLimit()) {
                    throw new IllegalArgumentException("You have reached the usage limit for this voucher.");
                }
            } else if (userEmail != null && !userEmail.isBlank()) {
                long guestUsed = excludeBookingId != null
                        ? promotionRepository.countUsageByGuestEmailExcludingBooking(p.getPromotionId(), userEmail.trim(), excludeBookingId)
                        : promotionRepository.countUsageByGuestEmail(p.getPromotionId(), userEmail.trim());
                if (guestUsed >= p.getPerUserLimit()) {
                    throw new IllegalArgumentException("You have reached the usage limit for this voucher.");
                }
            }
        }

        // 6. Calculate exact integer VND discount amount
        long eligibleVnd = PurchaseSnapshotService.toVndLong(orderAmount);
        long actualDiscountVnd = PurchaseSnapshotService.computeConfiguredVoucherDiscountVnd(p, eligibleVnd);
        return BigDecimal.valueOf(actualDiscountVnd).setScale(2, RoundingMode.HALF_UP);
    }

    @Override
    public Promotion findByVoucherCode(String voucherCode) {
        if (voucherCode == null || voucherCode.isBlank()) return null;
        return promotionRepository.findByVoucherCodeIgnoreCase(voucherCode.trim()).orElse(null);
    }
}
