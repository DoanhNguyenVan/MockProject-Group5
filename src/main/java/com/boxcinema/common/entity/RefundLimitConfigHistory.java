package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "refund_limit_config_history")
public class RefundLimitConfigHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, length = 60)
    private String configKey;

    @Column(name = "previous_amount_vnd", nullable = false)
    private Long previousAmountVnd;

    @Column(name = "new_amount_vnd", nullable = false)
    private Long newAmountVnd;

    @Column(name = "changed_by", nullable = false)
    private Long changedBy;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

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

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public Long getPreviousAmountVnd() {
        return previousAmountVnd;
    }

    public void setPreviousAmountVnd(Long previousAmountVnd) {
        this.previousAmountVnd = previousAmountVnd;
    }

    public Long getNewAmountVnd() {
        return newAmountVnd;
    }

    public void setNewAmountVnd(Long newAmountVnd) {
        this.newAmountVnd = newAmountVnd;
    }

    public Long getChangedBy() {
        return changedBy;
    }

    public void setChangedBy(Long changedBy) {
        this.changedBy = changedBy;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
