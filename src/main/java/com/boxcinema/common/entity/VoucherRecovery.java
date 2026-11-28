package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "voucher_recoveries")
public class VoucherRecovery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_request_id", nullable = false)
    private RefundRequest refundRequest;

    @Column(name = "refund_request_item_id")
    private Long refundRequestItemId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "old_promotion_id")
    private Long oldPromotionId;

    @Column(name = "old_voucher_code_snapshot", length = 50)
    private String oldVoucherCodeSnapshot;

    @Column(name = "new_promotion_id", unique = true)
    private Long newPromotionId;

    @Column(name = "new_voucher_code", unique = true, length = 50)
    private String newVoucherCode;

    @Column(name = "recovery_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal recoveryAmount;

    @Column(name = "min_order_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal minOrderAmount = BigDecimal.ZERO;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(nullable = false, length = 20)
    private String status = "PENDING"; // PENDING, GENERATED, FAILED

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "last_error", columnDefinition = "TEXT")
    private String lastError;

    @Column(name = "active_item_lock", unique = true)
    private Long activeItemLock;

    @Column(name = "generated_at")
    private LocalDateTime generatedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        if (this.updatedAt == null) this.updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public RefundRequest getRefundRequest() { return refundRequest; }
    public void setRefundRequest(RefundRequest refundRequest) { this.refundRequest = refundRequest; }

    public Long getRefundRequestItemId() { return refundRequestItemId; }
    public void setRefundRequestItemId(Long refundRequestItemId) { this.refundRequestItemId = refundRequestItemId; }

    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }

    public Long getOldPromotionId() { return oldPromotionId; }
    public void setOldPromotionId(Long oldPromotionId) { this.oldPromotionId = oldPromotionId; }

    public String getOldVoucherCodeSnapshot() { return oldVoucherCodeSnapshot; }
    public void setOldVoucherCodeSnapshot(String oldVoucherCodeSnapshot) { this.oldVoucherCodeSnapshot = oldVoucherCodeSnapshot; }

    public Long getNewPromotionId() { return newPromotionId; }
    public void setNewPromotionId(Long newPromotionId) { this.newPromotionId = newPromotionId; }

    public String getNewVoucherCode() { return newVoucherCode; }
    public void setNewVoucherCode(String newVoucherCode) { this.newVoucherCode = newVoucherCode; }

    public BigDecimal getRecoveryAmount() { return recoveryAmount; }
    public void setRecoveryAmount(BigDecimal recoveryAmount) { this.recoveryAmount = recoveryAmount; }

    public BigDecimal getMinOrderAmount() { return minOrderAmount; }
    public void setMinOrderAmount(BigDecimal minOrderAmount) { this.minOrderAmount = minOrderAmount; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }

    public Long getActiveItemLock() { return activeItemLock; }
    public void setActiveItemLock(Long activeItemLock) { this.activeItemLock = activeItemLock; }

    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
