package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "payout_fee_assessments")
public class PayoutFeeAssessmentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_request_id", nullable = false)
    private Long refundRequestId;

    @Column(name = "payout_transaction_id")
    private Long payoutTransactionId;

    @Column(name = "provider_name", nullable = false, length = 40)
    private String providerName = "PAYOS";

    @Column(name = "fee_mode", nullable = false, length = 30)
    private String feeMode;

    @Column(name = "approved_refund_vnd", nullable = false)
    private Long approvedRefundVnd;

    @Column(name = "estimated_fee_vnd", nullable = false)
    private Long estimatedFeeVnd = 0L;

    @Column(name = "net_recipient_vnd", nullable = false)
    private Long netRecipientVnd;

    @Column(name = "total_debit_vnd", nullable = false)
    private Long totalDebitVnd;

    @Column(name = "guaranteed_full_receipt", nullable = false)
    private boolean guaranteedFullReceipt = false;

    @Column(name = "blocked", nullable = false)
    private boolean blocked = false;

    @Column(name = "block_reason", columnDefinition = "TEXT")
    private String blockReason;

    @Column(name = "assessed_by")
    private Long assessedBy;

    @Column(name = "assessed_at", nullable = false, updatable = false)
    private LocalDateTime assessedAt = LocalDateTime.now();

    @PrePersist
    public void onCreate() {
        if (this.assessedAt == null) {
            this.assessedAt = LocalDateTime.now();
        }
        if (this.providerName == null || this.providerName.isBlank()) {
            this.providerName = "PAYOS";
        }
        if (this.estimatedFeeVnd == null) {
            this.estimatedFeeVnd = 0L;
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getRefundRequestId() { return refundRequestId; }
    public void setRefundRequestId(Long refundRequestId) { this.refundRequestId = refundRequestId; }

    public Long getPayoutTransactionId() { return payoutTransactionId; }
    public void setPayoutTransactionId(Long payoutTransactionId) { this.payoutTransactionId = payoutTransactionId; }

    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }

    public String getFeeMode() { return feeMode; }
    public void setFeeMode(String feeMode) { this.feeMode = feeMode; }

    public Long getApprovedRefundVnd() { return approvedRefundVnd; }
    public void setApprovedRefundVnd(Long approvedRefundVnd) { this.approvedRefundVnd = approvedRefundVnd; }

    public Long getEstimatedFeeVnd() { return estimatedFeeVnd; }
    public void setEstimatedFeeVnd(Long estimatedFeeVnd) { this.estimatedFeeVnd = estimatedFeeVnd; }

    public Long getNetRecipientVnd() { return netRecipientVnd; }
    public void setNetRecipientVnd(Long netRecipientVnd) { this.netRecipientVnd = netRecipientVnd; }

    public Long getTotalDebitVnd() { return totalDebitVnd; }
    public void setTotalDebitVnd(Long totalDebitVnd) { this.totalDebitVnd = totalDebitVnd; }

    public boolean isGuaranteedFullReceipt() { return guaranteedFullReceipt; }
    public void setGuaranteedFullReceipt(boolean guaranteedFullReceipt) { this.guaranteedFullReceipt = guaranteedFullReceipt; }

    public boolean isBlocked() { return blocked; }
    public void setBlocked(boolean blocked) { this.blocked = blocked; }

    public String getBlockReason() { return blockReason; }
    public void setBlockReason(String blockReason) { this.blockReason = blockReason; }

    public Long getAssessedBy() { return assessedBy; }
    public void setAssessedBy(Long assessedBy) { this.assessedBy = assessedBy; }

    public LocalDateTime getAssessedAt() { return assessedAt; }
    public void setAssessedAt(LocalDateTime assessedAt) { this.assessedAt = assessedAt; }
}
