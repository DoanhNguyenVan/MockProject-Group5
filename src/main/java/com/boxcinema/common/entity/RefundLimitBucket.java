package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "refund_limit_buckets")
public class RefundLimitBucket {

    @Id
    @Column(name = "bucket_key", nullable = false, length = 120)
    private String bucketKey;

    @Column(name = "bucket_type", nullable = false, length = 40)
    private String bucketType;

    @Column(name = "scope_id", nullable = false)
    private Long scopeId;

    @Column(name = "quota_date")
    private LocalDate quotaDate;

    @Column(name = "reserved_amount_vnd", nullable = false)
    private Long reservedAmountVnd = 0L;

    @Column(name = "committed_amount_vnd", nullable = false)
    private Long committedAmountVnd = 0L;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (this.updatedAt == null) {
            this.updatedAt = LocalDateTime.now();
        }
        if (this.reservedAmountVnd == null) {
            this.reservedAmountVnd = 0L;
        }
        if (this.committedAmountVnd == null) {
            this.committedAmountVnd = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public String getBucketKey() {
        return bucketKey;
    }

    public void setBucketKey(String bucketKey) {
        this.bucketKey = bucketKey;
    }

    public String getBucketType() {
        return bucketType;
    }

    public void setBucketType(String bucketType) {
        this.bucketType = bucketType;
    }

    public Long getScopeId() {
        return scopeId;
    }

    public void setScopeId(Long scopeId) {
        this.scopeId = scopeId;
    }

    public LocalDate getQuotaDate() {
        return quotaDate;
    }

    public void setQuotaDate(LocalDate quotaDate) {
        this.quotaDate = quotaDate;
    }

    public Long getReservedAmountVnd() {
        return reservedAmountVnd;
    }

    public void setReservedAmountVnd(Long reservedAmountVnd) {
        this.reservedAmountVnd = reservedAmountVnd;
    }

    public Long getCommittedAmountVnd() {
        return committedAmountVnd;
    }

    public void setCommittedAmountVnd(Long committedAmountVnd) {
        this.committedAmountVnd = committedAmountVnd;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
