package com.boxcinema.refund.dto;

import java.util.ArrayList;
import java.util.List;

public class BatchPayoutResponseDto {
    private String batchCode;
    private String overallStatus; // ALL_SUCCEEDED, PARTIAL_SUCCESS, IN_PROGRESS, ALL_FAILED, ALL_BLOCKED, NO_ELIGIBLE_ITEMS
    private boolean livePayoutEnabled;
    private String payoutMode;
    private int requestedCount;
    private int eligibleCount;
    private int succeededCount;
    private int processingCount;
    private int unknownOutcomeCount;
    private int blockedCount;
    private int failedCount;
    private int skippedCount;
    private long requestedTotalAmount;
    private long dispatchedTotalAmount;
    private long succeededTotalAmount;
    private List<BatchPayoutItemResultDto> results = new ArrayList<>();

    public static class BatchPayoutItemResultDto {
        private Long refundRequestId;
        private String requestCode;
        private String bookingCode;
        private String customerName;
        private String recipientBank;
        private String maskedAccount;
        private long amount;
        private String requestStatus;
        private String payoutStatus;
        private String workflowStatus;
        private String refundMethod;
        private String feeClassification;
        private long estimatedFeeVnd;
        private long netRecipientVnd;
        private boolean blocked;
        private String resultStatus; // SUCCEEDED, PROCESSING, UNKNOWN_OUTCOME, PAYOUT_BLOCKED, FAILED, SKIPPED_INELIGIBLE, ALREADY_PROCESSED
        private String referenceId;
        private String payosPayoutId;
        private String idempotencyKey;
        private boolean unknownOutcome;
        private String message;

        public Long getRefundRequestId() { return refundRequestId; }
        public void setRefundRequestId(Long refundRequestId) { this.refundRequestId = refundRequestId; }
        public String getRequestCode() { return requestCode; }
        public void setRequestCode(String requestCode) { this.requestCode = requestCode; }
        public String getBookingCode() { return bookingCode; }
        public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }
        public String getCustomerName() { return customerName; }
        public void setCustomerName(String customerName) { this.customerName = customerName; }
        public String getRecipientBank() { return recipientBank; }
        public void setRecipientBank(String recipientBank) { this.recipientBank = recipientBank; }
        public String getMaskedAccount() { return maskedAccount; }
        public void setMaskedAccount(String maskedAccount) { this.maskedAccount = maskedAccount; }
        public long getAmount() { return amount; }
        public void setAmount(long amount) { this.amount = amount; }
        public String getRequestStatus() { return requestStatus; }
        public void setRequestStatus(String requestStatus) { this.requestStatus = requestStatus; }
        public String getPayoutStatus() { return payoutStatus; }
        public void setPayoutStatus(String payoutStatus) { this.payoutStatus = payoutStatus; }
        public String getWorkflowStatus() { return workflowStatus; }
        public void setWorkflowStatus(String workflowStatus) { this.workflowStatus = workflowStatus; }
        public String getRefundMethod() { return refundMethod; }
        public void setRefundMethod(String refundMethod) { this.refundMethod = refundMethod; }
        public String getFeeClassification() { return feeClassification; }
        public void setFeeClassification(String feeClassification) { this.feeClassification = feeClassification; }
        public long getEstimatedFeeVnd() { return estimatedFeeVnd; }
        public void setEstimatedFeeVnd(long estimatedFeeVnd) { this.estimatedFeeVnd = estimatedFeeVnd; }
        public long getNetRecipientVnd() { return netRecipientVnd; }
        public void setNetRecipientVnd(long netRecipientVnd) { this.netRecipientVnd = netRecipientVnd; }
        public boolean isBlocked() { return blocked; }
        public void setBlocked(boolean blocked) { this.blocked = blocked; }
        public String getResultStatus() { return resultStatus; }
        public void setResultStatus(String resultStatus) { this.resultStatus = resultStatus; }
        public String getReferenceId() { return referenceId; }
        public void setReferenceId(String referenceId) { this.referenceId = referenceId; }
        public String getPayosPayoutId() { return payosPayoutId; }
        public void setPayosPayoutId(String payosPayoutId) { this.payosPayoutId = payosPayoutId; }
        public String getIdempotencyKey() { return idempotencyKey; }
        public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
        public boolean isUnknownOutcome() { return unknownOutcome; }
        public void setUnknownOutcome(boolean unknownOutcome) { this.unknownOutcome = unknownOutcome; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public String getBatchCode() { return batchCode; }
    public void setBatchCode(String batchCode) { this.batchCode = batchCode; }
    public String getOverallStatus() { return overallStatus; }
    public void setOverallStatus(String overallStatus) { this.overallStatus = overallStatus; }
    public boolean isLivePayoutEnabled() { return livePayoutEnabled; }
    public void setLivePayoutEnabled(boolean livePayoutEnabled) { this.livePayoutEnabled = livePayoutEnabled; }
    public String getPayoutMode() { return payoutMode; }
    public void setPayoutMode(String payoutMode) { this.payoutMode = payoutMode; }
    public int getRequestedCount() { return requestedCount; }
    public void setRequestedCount(int requestedCount) { this.requestedCount = requestedCount; }
    public int getEligibleCount() { return eligibleCount; }
    public void setEligibleCount(int eligibleCount) { this.eligibleCount = eligibleCount; }
    public int getSucceededCount() { return succeededCount; }
    public void setSucceededCount(int succeededCount) { this.succeededCount = succeededCount; }
    public int getProcessingCount() { return processingCount; }
    public void setProcessingCount(int processingCount) { this.processingCount = processingCount; }
    public int getUnknownOutcomeCount() { return unknownOutcomeCount; }
    public void setUnknownOutcomeCount(int unknownOutcomeCount) { this.unknownOutcomeCount = unknownOutcomeCount; }
    public int getBlockedCount() { return blockedCount; }
    public void setBlockedCount(int blockedCount) { this.blockedCount = blockedCount; }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }
    public int getSkippedCount() { return skippedCount; }
    public void setSkippedCount(int skippedCount) { this.skippedCount = skippedCount; }
    public long getRequestedTotalAmount() { return requestedTotalAmount; }
    public void setRequestedTotalAmount(long requestedTotalAmount) { this.requestedTotalAmount = requestedTotalAmount; }
    public long getDispatchedTotalAmount() { return dispatchedTotalAmount; }
    public void setDispatchedTotalAmount(long dispatchedTotalAmount) { this.dispatchedTotalAmount = dispatchedTotalAmount; }
    public long getSucceededTotalAmount() { return succeededTotalAmount; }
    public void setSucceededTotalAmount(long succeededTotalAmount) { this.succeededTotalAmount = succeededTotalAmount; }
    public List<BatchPayoutItemResultDto> getResults() { return results; }
    public void setResults(List<BatchPayoutItemResultDto> results) { this.results = results; }
}
