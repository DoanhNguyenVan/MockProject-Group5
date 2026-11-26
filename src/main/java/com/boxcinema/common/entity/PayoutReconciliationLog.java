package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "payout_reconciliation_logs")
public class PayoutReconciliationLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payout_transaction_id", nullable = false)
    private Long payoutTransactionId;

    @Column(name = "refund_request_id", nullable = false)
    private Long refundRequestId;

    @Column(name = "trigger_source", nullable = false, length = 30)
    private String triggerSource;

    @Column(name = "previous_state", length = 30)
    private String previousState;

    @Column(name = "new_state", nullable = false, length = 30)
    private String newState;

    @Column(name = "provider_code", length = 30)
    private String providerCode;

    @Column(name = "provider_desc", length = 255)
    private String providerDesc;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "details_json", columnDefinition = "TEXT")
    private String detailsJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPayoutTransactionId() { return payoutTransactionId; }
    public void setPayoutTransactionId(Long payoutTransactionId) { this.payoutTransactionId = payoutTransactionId; }

    public Long getRefundRequestId() { return refundRequestId; }
    public void setRefundRequestId(Long refundRequestId) { this.refundRequestId = refundRequestId; }

    public String getTriggerSource() { return triggerSource; }
    public void setTriggerSource(String triggerSource) { this.triggerSource = triggerSource; }

    public String getPreviousState() { return previousState; }
    public void setPreviousState(String previousState) { this.previousState = previousState; }

    public String getNewState() { return newState; }
    public void setNewState(String newState) { this.newState = newState; }

    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String providerCode) { this.providerCode = providerCode; }

    public String getProviderDesc() { return providerDesc; }
    public void setProviderDesc(String providerDesc) { this.providerDesc = providerDesc; }

    public Long getActorUserId() { return actorUserId; }
    public void setActorUserId(Long actorUserId) { this.actorUserId = actorUserId; }

    public String getDetailsJson() { return detailsJson; }
    public void setDetailsJson(String detailsJson) { this.detailsJson = detailsJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
