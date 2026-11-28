package com.boxcinema.refund.dto;

import java.time.LocalDateTime;

public class PayoutTransactionDetailDto {
    private Long id;
    private Long refundRequestId;
    private String requestCode;
    private String bookingCode;
    private String cinemaName;
    private String customerName;
    private String purchaseEmail;
    private String referenceId;
    private String batchCode;
    private String idempotencyKey;
    private String payosPayoutId;
    private String payosTransactionId;
    private String providerReference;
    private String payoutMode;
    private Long amount;
    private String description;
    private String toBin;
    private String toAccountNumberMasked;
    private String toAccountHolderName;
    private String category;
    private String status;
    private String approvalState;
    private String transactionState;
    private String payosResponseCode;
    private String payosResponseDesc;
    private String errorMessage;
    private String rawResponseSanitized;
    private int attemptCount;
    private Long initiatedBy;
    private String initiatedByName;
    private LocalDateTime initiatedAt;
    private LocalDateTime lastReconciledAt;
    private LocalDateTime succeededAt;
    private LocalDateTime failedAt;
    private boolean unknownOutcome;
    private String displayStage;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRefundRequestId() { return refundRequestId; }
    public void setRefundRequestId(Long refundRequestId) { this.refundRequestId = refundRequestId; }
    public String getRequestCode() { return requestCode; }
    public void setRequestCode(String requestCode) { this.requestCode = requestCode; }
    public String getBookingCode() { return bookingCode; }
    public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }
    public String getCinemaName() { return cinemaName; }
    public void setCinemaName(String cinemaName) { this.cinemaName = cinemaName; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getPurchaseEmail() { return purchaseEmail; }
    public void setPurchaseEmail(String purchaseEmail) { this.purchaseEmail = purchaseEmail; }
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
    public Long getInitiatedBy() { return initiatedBy; }
    public void setInitiatedBy(Long initiatedBy) { this.initiatedBy = initiatedBy; }
    public String getInitiatedByName() { return initiatedByName; }
    public void setInitiatedByName(String initiatedByName) { this.initiatedByName = initiatedByName; }
    public LocalDateTime getInitiatedAt() { return initiatedAt; }
    public void setInitiatedAt(LocalDateTime initiatedAt) { this.initiatedAt = initiatedAt; }
    public LocalDateTime getLastReconciledAt() { return lastReconciledAt; }
    public void setLastReconciledAt(LocalDateTime lastReconciledAt) { this.lastReconciledAt = lastReconciledAt; }
    public LocalDateTime getSucceededAt() { return succeededAt; }
    public void setSucceededAt(LocalDateTime succeededAt) { this.succeededAt = succeededAt; }
    public LocalDateTime getFailedAt() { return failedAt; }
    public void setFailedAt(LocalDateTime failedAt) { this.failedAt = failedAt; }
    public boolean isUnknownOutcome() { return unknownOutcome; }
    public void setUnknownOutcome(boolean unknownOutcome) { this.unknownOutcome = unknownOutcome; }
    public String getDisplayStage() { return displayStage; }
    public void setDisplayStage(String displayStage) { this.displayStage = displayStage; }
}
