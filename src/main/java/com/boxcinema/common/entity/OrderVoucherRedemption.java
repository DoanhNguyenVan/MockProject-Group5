package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Ledger row recording the single voucher redeemed on an order.
 * Enforces ONE VOUCHER PER ORDER at the database level via uk_ovr_booking_id (UNIQUE(booking_id)).
 */
@Entity
@Table(
        name = "order_voucher_redemptions",
        uniqueConstraints = @UniqueConstraint(name = "uk_ovr_booking_id", columnNames = "booking_id")
)
public class OrderVoucherRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false, unique = true)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "promotion_id", nullable = false)
    private Promotion promotion;

    @Column(name = "voucher_code", nullable = false, length = 50)
    private String voucherCode;

    @Column(name = "discount_type", nullable = false, length = 20)
    private String discountType;

    @Column(name = "discount_value", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountValue = BigDecimal.ZERO;

    @Column(name = "max_discount", precision = 12, scale = 2)
    private BigDecimal maxDiscount;

    @Column(name = "eligible_amount", nullable = false)
    private Long eligibleAmount = 0L;

    @Column(name = "actual_discount", nullable = false)
    private Long actualDiscount = 0L;

    /** APPLIED, RESTORED, PARTIALLY_RESTORED, CANCELLED */
    @Column(name = "status", nullable = false, length = 30)
    private String status = "APPLIED";

    @Column(name = "redeemed_at", nullable = false)
    private LocalDateTime redeemedAt = LocalDateTime.now();

    @Column(name = "restored_at")
    private LocalDateTime restoredAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public OrderVoucherRedemption() {}

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.redeemedAt == null) this.redeemedAt = now;
        if (this.createdAt == null) this.createdAt = now;
        if (this.updatedAt == null) this.updatedAt = now;
        if (this.eligibleAmount == null) this.eligibleAmount = 0L;
        if (this.actualDiscount == null) this.actualDiscount = 0L;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }

    public Promotion getPromotion() { return promotion; }
    public void setPromotion(Promotion promotion) { this.promotion = promotion; }

    public String getVoucherCode() { return voucherCode; }
    public void setVoucherCode(String voucherCode) { this.voucherCode = voucherCode; }

    public String getDiscountType() { return discountType; }
    public void setDiscountType(String discountType) { this.discountType = discountType; }

    public BigDecimal getDiscountValue() { return discountValue; }
    public void setDiscountValue(BigDecimal discountValue) { this.discountValue = discountValue; }

    public BigDecimal getMaxDiscount() { return maxDiscount; }
    public void setMaxDiscount(BigDecimal maxDiscount) { this.maxDiscount = maxDiscount; }

    public Long getEligibleAmount() { return eligibleAmount; }
    public void setEligibleAmount(Long eligibleAmount) { this.eligibleAmount = eligibleAmount != null ? eligibleAmount : 0L; }

    public Long getActualDiscount() { return actualDiscount; }
    public void setActualDiscount(Long actualDiscount) { this.actualDiscount = actualDiscount != null ? actualDiscount : 0L; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getRedeemedAt() { return redeemedAt; }
    public void setRedeemedAt(LocalDateTime redeemedAt) { this.redeemedAt = redeemedAt; }

    public LocalDateTime getRestoredAt() { return restoredAt; }
    public void setRestoredAt(LocalDateTime restoredAt) { this.restoredAt = restoredAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
