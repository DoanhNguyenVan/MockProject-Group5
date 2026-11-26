package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "refund_limit_overrides")
public class RefundLimitOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_request_id", nullable = false)
    private Long refundRequestId;

    @Column(name = "admin_user_id", nullable = false)
    private Long adminUserId;

    @Column(name = "breached_limits_json", nullable = false, columnDefinition = "TEXT")
    private String breachedLimitsJson;

    @Column(name = "approved_amount_vnd", nullable = false)
    private Long approvedAmountVnd;

    @Column(name = "override_reason", nullable = false, columnDefinition = "TEXT")
    private String overrideReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRefundRequestId() {
        return refundRequestId;
    }

    public void setRefundRequestId(Long refundRequestId) {
        this.refundRequestId = refundRequestId;
    }

    public Long getAdminUserId() {
        return adminUserId;
    }

    public void setAdminUserId(Long adminUserId) {
        this.adminUserId = adminUserId;
    }

    public String getBreachedLimitsJson() {
        return breachedLimitsJson;
    }

    public void setBreachedLimitsJson(String breachedLimitsJson) {
        this.breachedLimitsJson = breachedLimitsJson;
    }

    public Long getApprovedAmountVnd() {
        return approvedAmountVnd;
    }

    public void setApprovedAmountVnd(Long approvedAmountVnd) {
        this.approvedAmountVnd = approvedAmountVnd;
    }

    public String getOverrideReason() {
        return overrideReason;
    }

    public void setOverrideReason(String overrideReason) {
        this.overrideReason = overrideReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
