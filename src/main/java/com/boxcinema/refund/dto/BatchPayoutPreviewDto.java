package com.boxcinema.refund.dto;

import java.util.ArrayList;
import java.util.List;

public class BatchPayoutPreviewDto {
    private int requestedCount;
    private int eligibleCount;
    private int ineligibleCount;
    private int blockedCount;
    private long totalAmountVnd;
    private boolean livePayoutEnabled;
    private String payoutMode;
    private String providerName;
    private String configuredFeePolicy;
    private List<BatchPreviewItemDto> eligibleItems = new ArrayList<>();
    private List<BatchPreviewItemDto> ineligibleItems = new ArrayList<>();

    public static class BatchPreviewItemDto {
        private Long refundRequestId;
        private String requestCode;
        private String bookingCode;
        private String customerName;
        private String purchaseEmail;
        private Long cinemaId;
        private String cinemaName;
        private String reasonCategory;
        private String verifierName;
        private String toBankName;
        private String toBin;
        private String toAccountNumberMasked;
        private String accountHolderName;
        private String transferDescription;
        private String refundMethod;
        private long amountVnd;
        private String requestStatus;
        private String payoutStatus;
        private String workflowStatus;
        private String feeClassification;
        private long estimatedFeeVnd;
        private long netRecipientVnd;
        private long totalDebitVnd;
        private boolean guaranteedFullReceipt;
        private boolean feeBlocked;
        private String feeBlockReason;
        private boolean eligible;
        private List<String> ineligibleReasons = new ArrayList<>();

        public Long getRefundRequestId() { return refundRequestId; }
        public void setRefundRequestId(Long refundRequestId) { this.refundRequestId = refundRequestId; }
        public String getRequestCode() { return requestCode; }
        public void setRequestCode(String requestCode) { this.requestCode = requestCode; }
        public String getBookingCode() { return bookingCode; }
        public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }
        public String getCustomerName() { return customerName; }
        public void setCustomerName(String customerName) { this.customerName = customerName; }
        public String getPurchaseEmail() { return purchaseEmail; }
        public void setPurchaseEmail(String purchaseEmail) { this.purchaseEmail = purchaseEmail; }
        public Long getCinemaId() { return cinemaId; }
        public void setCinemaId(Long cinemaId) { this.cinemaId = cinemaId; }
        public String getCinemaName() { return cinemaName; }
        public void setCinemaName(String cinemaName) { this.cinemaName = cinemaName; }
        public String getReasonCategory() { return reasonCategory; }
        public void setReasonCategory(String reasonCategory) { this.reasonCategory = reasonCategory; }
        public String getVerifierName() { return verifierName; }
        public void setVerifierName(String verifierName) { this.verifierName = verifierName; }
        public String getToBankName() { return toBankName; }
        public void setToBankName(String toBankName) { this.toBankName = toBankName; }
        public String getToBin() { return toBin; }
        public void setToBin(String toBin) { this.toBin = toBin; }
        public String getToAccountNumberMasked() { return toAccountNumberMasked; }
        public void setToAccountNumberMasked(String toAccountNumberMasked) { this.toAccountNumberMasked = toAccountNumberMasked; }
        public String getAccountHolderName() { return accountHolderName; }
        public void setAccountHolderName(String accountHolderName) { this.accountHolderName = accountHolderName; }
        public String getTransferDescription() { return transferDescription; }
        public void setTransferDescription(String transferDescription) { this.transferDescription = transferDescription; }
        public String getRefundMethod() { return refundMethod; }
        public void setRefundMethod(String refundMethod) { this.refundMethod = refundMethod; }
        public long getAmountVnd() { return amountVnd; }
        public void setAmountVnd(long amountVnd) { this.amountVnd = amountVnd; }
        public String getRequestStatus() { return requestStatus; }
        public void setRequestStatus(String requestStatus) { this.requestStatus = requestStatus; }
        public String getPayoutStatus() { return payoutStatus; }
        public void setPayoutStatus(String payoutStatus) { this.payoutStatus = payoutStatus; }
        public String getWorkflowStatus() { return workflowStatus; }
        public void setWorkflowStatus(String workflowStatus) { this.workflowStatus = workflowStatus; }
        public String getFeeClassification() { return feeClassification; }
        public void setFeeClassification(String feeClassification) { this.feeClassification = feeClassification; }
        public long getEstimatedFeeVnd() { return estimatedFeeVnd; }
        public void setEstimatedFeeVnd(long estimatedFeeVnd) { this.estimatedFeeVnd = estimatedFeeVnd; }
        public long getNetRecipientVnd() { return netRecipientVnd; }
        public void setNetRecipientVnd(long netRecipientVnd) { this.netRecipientVnd = netRecipientVnd; }
        public long getTotalDebitVnd() { return totalDebitVnd; }
        public void setTotalDebitVnd(long totalDebitVnd) { this.totalDebitVnd = totalDebitVnd; }
        public boolean isGuaranteedFullReceipt() { return guaranteedFullReceipt; }
        public void setGuaranteedFullReceipt(boolean guaranteedFullReceipt) { this.guaranteedFullReceipt = guaranteedFullReceipt; }
        public boolean isFeeBlocked() { return feeBlocked; }
        public void setFeeBlocked(boolean feeBlocked) { this.feeBlocked = feeBlocked; }
        public String getFeeBlockReason() { return feeBlockReason; }
        public void setFeeBlockReason(String feeBlockReason) { this.feeBlockReason = feeBlockReason; }
        public boolean isEligible() { return eligible; }
        public void setEligible(boolean eligible) { this.eligible = eligible; }
        public List<String> getIneligibleReasons() { return ineligibleReasons; }
        public void setIneligibleReasons(List<String> ineligibleReasons) { this.ineligibleReasons = ineligibleReasons; }
    }

    public int getRequestedCount() { return requestedCount; }
    public void setRequestedCount(int requestedCount) { this.requestedCount = requestedCount; }
    public int getEligibleCount() { return eligibleCount; }
    public void setEligibleCount(int eligibleCount) { this.eligibleCount = eligibleCount; }
    public int getIneligibleCount() { return ineligibleCount; }
    public void setIneligibleCount(int ineligibleCount) { this.ineligibleCount = ineligibleCount; }
    public int getBlockedCount() { return blockedCount; }
    public void setBlockedCount(int blockedCount) { this.blockedCount = blockedCount; }
    public long getTotalAmountVnd() { return totalAmountVnd; }
    public void setTotalAmountVnd(long totalAmountVnd) { this.totalAmountVnd = totalAmountVnd; }
    public boolean isLivePayoutEnabled() { return livePayoutEnabled; }
    public void setLivePayoutEnabled(boolean livePayoutEnabled) { this.livePayoutEnabled = livePayoutEnabled; }
    public String getPayoutMode() { return payoutMode; }
    public void setPayoutMode(String payoutMode) { this.payoutMode = payoutMode; }
    public String getProviderName() { return providerName; }
    public void setProviderName(String providerName) { this.providerName = providerName; }
    public String getConfiguredFeePolicy() { return configuredFeePolicy; }
    public void setConfiguredFeePolicy(String configuredFeePolicy) { this.configuredFeePolicy = configuredFeePolicy; }
    public List<BatchPreviewItemDto> getEligibleItems() { return eligibleItems; }
    public void setEligibleItems(List<BatchPreviewItemDto> eligibleItems) { this.eligibleItems = eligibleItems; }
    public List<BatchPreviewItemDto> getIneligibleItems() { return ineligibleItems; }
    public void setIneligibleItems(List<BatchPreviewItemDto> ineligibleItems) { this.ineligibleItems = ineligibleItems; }
}
