package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "payos_payout_transactions")
public class PayOsPayoutTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_request_id", nullable = false)
    private RefundRequest refundRequest;

    @Column(name = "reference_id", nullable = false, unique = true, length = 80)
    private String referenceId;

    @Column(name = "batch_code", length = 40)
    private String batchCode;

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "payos_payout_id", length = 100)
    private String payosPayoutId;

    @Column(name = "payos_transaction_id", length = 100)
    private String payosTransactionId;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "payout_mode", nullable = false, length = 10)
    private String payoutMode = "MOCK";

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false, length = 255)
    private String description;

    @Column(name = "to_bin", nullable = false, length = 20)
    private String toBin;

    @Column(name = "to_account_number_masked", nullable = false, length = 50)
    private String toAccountNumberMasked;

    @Column(name = "to_account_holder_name", length = 150)
    private String toAccountHolderName;

    @Column(length = 50)
    private String category;

    @Column(nullable = false, length = 20)
    private String status = "PENDING"; // PENDING, PROCESSING, SUCCEEDED, FAILED

    @Column(name = "approval_state", length = 30)
    private String approvalState;

    @Column(name = "transaction_state", length = 30)
    private String transactionState;

    @Column(name = "payos_response_code", length = 20)
    private String payosResponseCode;

    @Column(name = "payos_response_desc", length = 255)
    private String payosResponseDesc;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "raw_response_sanitized", columnDefinition = "TEXT")
    private String rawResponseSanitized;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount = 1;

    @Column(name = "active_request_lock", unique = true)
    private Long activeRequestLock;

    @Column(name = "initiated_by")
    private Long initiatedBy;

    @Column(name = "initiated_at", nullable = false, updatable = false)
    private LocalDateTime initiatedAt = LocalDateTime.now();

    @Column(name = "last_reconciled_at")
    private LocalDateTime lastReconciledAt;

    @Column(name = "succeeded_at")
    private LocalDateTime succeededAt;

    @Column(name = "failed_at")
    private LocalDateTime failedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.initiatedAt == null) this.initiatedAt = now;
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

    public String getReferenceId() { return referenceId; }
    public void setReferenceId(String referenceId) { this.referenceId = referenceId; }

    public String getBatchCode() { return batchCode; }
    public void setBatchCode(String batchCode) { this.batchCode = batchCode; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getPayosPayoutId() { return payosPayoutId; }
    public void setPayosPayoutId(String payosPayoutId) { this.payosPayoutId = payosPayoutId; }

    public String getPayosTransactionId() { return payosTransactionId; }
    public void setPayosTransactionId(String payosTransactionId) { this.payosTransactionId = payosTransactionId; }

    public String getProviderReference() { return providerReference; }
    public void setProviderReference(String providerReference) { this.providerReference = providerReference; }

    public String getPayoutMode() { return payoutMode; }
    public void setPayoutMode(String payoutMode) { this.payoutMode = payoutMode; }

    public Long getAmount() { return amount; }
    public void setAmount(Long amount) { this.amount = amount; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getToBin() { return toBin; }
    public void setToBin(String toBin) { this.toBin = toBin; }

    public String getToAccountNumberMasked() { return toAccountNumberMasked; }
    public void setToAccountNumberMasked(String toAccountNumberMasked) { this.toAccountNumberMasked = toAccountNumberMasked; }

    public String getToAccountHolderName() { return toAccountHolderName; }
    public void setToAccountHolderName(String toAccountHolderName) { this.toAccountHolderName = toAccountHolderName; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getApprovalState() { return approvalState; }
    public void setApprovalState(String approvalState) { this.approvalState = approvalState; }

    public String getTransactionState() { return transactionState; }
    public void setTransactionState(String transactionState) { this.transactionState = transactionState; }

    public String getPayosResponseCode() { return payosResponseCode; }
    public void setPayosResponseCode(String payosResponseCode) { this.payosResponseCode = payosResponseCode; }

    public String getPayosResponseDesc() { return payosResponseDesc; }
    public void setPayosResponseDesc(String payosResponseDesc) { this.payosResponseDesc = payosResponseDesc; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getRawResponseSanitized() { return rawResponseSanitized; }
    public void setRawResponseSanitized(String rawResponseSanitized) { this.rawResponseSanitized = rawResponseSanitized; }

    public int getAttemptCount() { return attemptCount; }
    public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }

    public Long getActiveRequestLock() { return activeRequestLock; }
    public void setActiveRequestLock(Long activeRequestLock) { this.activeRequestLock = activeRequestLock; }

    public Long getInitiatedBy() { return initiatedBy; }
    public void setInitiatedBy(Long initiatedBy) { this.initiatedBy = initiatedBy; }

    public LocalDateTime getInitiatedAt() { return initiatedAt; }
    public void setInitiatedAt(LocalDateTime initiatedAt) { this.initiatedAt = initiatedAt; }

    public LocalDateTime getLastReconciledAt() { return lastReconciledAt; }
    public void setLastReconciledAt(LocalDateTime lastReconciledAt) { this.lastReconciledAt = lastReconciledAt; }

    public LocalDateTime getSucceededAt() { return succeededAt; }
    public void setSucceededAt(LocalDateTime succeededAt) { this.succeededAt = succeededAt; }

    public LocalDateTime getFailedAt() { return failedAt; }
    public void setFailedAt(LocalDateTime failedAt) { this.failedAt = failedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
