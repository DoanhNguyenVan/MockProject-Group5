package com.boxcinema.refund.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class RefundRequestDetailDto {
    private Long id;
    private String requestCode;
    private Long bookingId;
    private String bookingCode;
    private String orderType;
    private Long cinemaId;
    private String cinemaName;
    private String customerName;
    private String customerPhone;
    private String purchaseEmail;
    private String submittedOrderCode;
    private String reasonCategory;
    private String description;
    private String evidenceUrl;
    private String toBin;
    private String toBankName;
    private String toAccountNumberMasked;
    private String accountHolderName;
    private String bankNote;
    private Long savedBankAccountId;
    private boolean bankAccountEditable;
    private BigDecimal requestedCashTotal;
    private BigDecimal approvedCashTotal;
    private BigDecimal approvedVoucherTotal;
    private String requestStatus;
    private String payoutStatus;
    private String voucherStatus;
    private String workflowStatus;
    private String refundMethod;
    private Long originalPaymentId;
    private String originalPaymentRef;
    private LocalDateTime refundDeadlineAt;
    private String deadlineBasis;
    private boolean deadlineExceeded;
    private Long deadlineExceptionBy;
    private String deadlineExceptionReason;
    private LocalDateTime deadlineExceptionAt;
    private String feeAssessmentStatus;
    private Long feeAmountVnd;
    private Long netRecipientVnd;
    private Long totalDebitVnd;
    private Boolean guaranteedFullReceipt;
    private String payoutBlockedReason;
    private String recipientSnapshotHash;
    private String transferDescription;
    private boolean livePayoutEnabled;
    private String payoutMode;
    private boolean limitOverrideApplied;
    private Long limitOverrideBy;
    private String limitOverrideReason;
    private LocalDateTime limitOverrideAt;
    private String decisionNote;
    private Long reviewedById;
    private String reviewedByName;
    private String reviewedByRole;
    private LocalDateTime reviewedAt;
    private Long payoutDecidedById;
    private String payoutDecidedByName;
    private LocalDateTime payoutDecidedAt;
    private String payoutDecisionNote;
    private boolean payoutEligible;
    private List<String> payoutEligibilityErrors = new ArrayList<>();
    private String payoutDisplayStage;
    private LocalDateTime createdAt;
    private OriginalPaymentDto originalPayment;
    private List<RefundItemDto> items = new ArrayList<>();
    private List<CommunicationDto> communications = new ArrayList<>();
    private List<ApprovalHistoryDto> history = new ArrayList<>();
    private PayoutSummaryDto latestPayout;
    private List<PayoutSummaryDto> payoutTransactions = new ArrayList<>();
    private VoucherSummaryDto recoveryVoucher;
    private List<IncidentResponseDto> relatedIncidents = new ArrayList<>();

    public static class OriginalPaymentDto {
        private Long paymentId;
        private Long orderCode;
        private BigDecimal amount;
        private String paymentStatus;
        private String paymentMethod;
        private LocalDateTime paidAt;
        private String transactionRef;

        public Long getPaymentId() { return paymentId; }
        public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }
        public Long getOrderCode() { return orderCode; }
        public void setOrderCode(Long orderCode) { this.orderCode = orderCode; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public String getPaymentStatus() { return paymentStatus; }
        public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }
        public String getPaymentMethod() { return paymentMethod; }
        public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
        public LocalDateTime getPaidAt() { return paidAt; }
        public void setPaidAt(LocalDateTime paidAt) { this.paidAt = paidAt; }
        public String getTransactionRef() { return transactionRef; }
        public void setTransactionRef(String transactionRef) { this.transactionRef = transactionRef; }
    }

    public static class RefundItemDto {
        private Long id;
        private String itemKind;
        private Long ticketId;
        private Long bookingFoodItemId;
        private Long bookingFoodItemComponentId;
        private String itemLabel;
        private int purchasedQuantity;
        private int requestedQuantity;
        private int approvedQuantity;
        private BigDecimal grossUnitPrice;
        private BigDecimal discountAllocatedAmount;
        private BigDecimal netPaidAmount;
        private BigDecimal approvedCashAmount;
        private BigDecimal approvedVoucherAmount;
        private String itemStatus;
        private String decisionReason;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getItemKind() { return itemKind; }
        public void setItemKind(String itemKind) { this.itemKind = itemKind; }
        public Long getTicketId() { return ticketId; }
        public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
        public Long getBookingFoodItemId() { return bookingFoodItemId; }
        public void setBookingFoodItemId(Long bookingFoodItemId) { this.bookingFoodItemId = bookingFoodItemId; }
        public Long getBookingFoodItemComponentId() { return bookingFoodItemComponentId; }
        public void setBookingFoodItemComponentId(Long bookingFoodItemComponentId) { this.bookingFoodItemComponentId = bookingFoodItemComponentId; }
        public String getItemLabel() { return itemLabel; }
        public void setItemLabel(String itemLabel) { this.itemLabel = itemLabel; }
        public int getPurchasedQuantity() { return purchasedQuantity; }
        public void setPurchasedQuantity(int purchasedQuantity) { this.purchasedQuantity = purchasedQuantity; }
        public int getRequestedQuantity() { return requestedQuantity; }
        public void setRequestedQuantity(int requestedQuantity) { this.requestedQuantity = requestedQuantity; }
        public int getApprovedQuantity() { return approvedQuantity; }
        public void setApprovedQuantity(int approvedQuantity) { this.approvedQuantity = approvedQuantity; }
        public BigDecimal getGrossUnitPrice() { return grossUnitPrice; }
        public void setGrossUnitPrice(BigDecimal grossUnitPrice) { this.grossUnitPrice = grossUnitPrice; }
        public BigDecimal getDiscountAllocatedAmount() { return discountAllocatedAmount; }
        public void setDiscountAllocatedAmount(BigDecimal discountAllocatedAmount) { this.discountAllocatedAmount = discountAllocatedAmount; }
        public BigDecimal getNetPaidAmount() { return netPaidAmount; }
        public void setNetPaidAmount(BigDecimal netPaidAmount) { this.netPaidAmount = netPaidAmount; }
        public BigDecimal getApprovedCashAmount() { return approvedCashAmount; }
        public void setApprovedCashAmount(BigDecimal approvedCashAmount) { this.approvedCashAmount = approvedCashAmount; }
        public BigDecimal getApprovedVoucherAmount() { return approvedVoucherAmount; }
        public void setApprovedVoucherAmount(BigDecimal approvedVoucherAmount) { this.approvedVoucherAmount = approvedVoucherAmount; }
        public String getItemStatus() { return itemStatus; }
        public void setItemStatus(String itemStatus) { this.itemStatus = itemStatus; }
        public String getDecisionReason() { return decisionReason; }
        public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }
    }

    public static class CommunicationDto {
        private Long id;
        private String senderType;
        private String senderName;
        private String message;
        private String attachmentUrl;
        private LocalDateTime createdAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getSenderType() { return senderType; }
        public void setSenderType(String senderType) { this.senderType = senderType; }
        public String getSenderName() { return senderName; }
        public void setSenderName(String senderName) { this.senderName = senderName; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getAttachmentUrl() { return attachmentUrl; }
        public void setAttachmentUrl(String attachmentUrl) { this.attachmentUrl = attachmentUrl; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    public static class ApprovalHistoryDto {
        private String actorName;
        private String actorRole;
        private String action;
        private String previousStatus;
        private String newStatus;
        private String note;
        private LocalDateTime createdAt;

        public String getActorName() { return actorName; }
        public void setActorName(String actorName) { this.actorName = actorName; }
        public String getActorRole() { return actorRole; }
        public void setActorRole(String actorRole) { this.actorRole = actorRole; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getPreviousStatus() { return previousStatus; }
        public void setPreviousStatus(String previousStatus) { this.previousStatus = previousStatus; }
        public String getNewStatus() { return newStatus; }
        public void setNewStatus(String newStatus) { this.newStatus = newStatus; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    public static class PayoutSummaryDto {
        private Long id;
        private String referenceId;
        private String batchCode;
        private String idempotencyKey;
        private String payosPayoutId;
        private String payosTransactionId;
        private String providerReference;
        private String payoutMode;
        private Long amount;
        private String status;
        private String approvalState;
        private String transactionState;
        private String payosResponseCode;
        private String payosResponseDesc;
        private int attemptCount;
        private Long initiatedBy;
        private String toBin;
        private String toAccountNumberMasked;
        private String errorMessage;
        private LocalDateTime initiatedAt;
        private LocalDateTime lastReconciledAt;
        private LocalDateTime succeededAt;
        private LocalDateTime failedAt;
        private boolean unknownOutcome;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
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
        public int getAttemptCount() { return attemptCount; }
        public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
        public Long getInitiatedBy() { return initiatedBy; }
        public void setInitiatedBy(Long initiatedBy) { this.initiatedBy = initiatedBy; }
        public String getToBin() { return toBin; }
        public void setToBin(String toBin) { this.toBin = toBin; }
        public String getToAccountNumberMasked() { return toAccountNumberMasked; }
        public void setToAccountNumberMasked(String toAccountNumberMasked) { this.toAccountNumberMasked = toAccountNumberMasked; }
        public String getErrorMessage() { return errorMessage; }
        public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
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
    }

    public static class VoucherSummaryDto {
        private String newVoucherCode;
        private BigDecimal recoveryAmount;
        private LocalDateTime expiresAt;
        private String status;
        private String lastError;

        public String getNewVoucherCode() { return newVoucherCode; }
        public void setNewVoucherCode(String newVoucherCode) { this.newVoucherCode = newVoucherCode; }
        public BigDecimal getRecoveryAmount() { return recoveryAmount; }
        public void setRecoveryAmount(BigDecimal recoveryAmount) { this.recoveryAmount = recoveryAmount; }
        public LocalDateTime getExpiresAt() { return expiresAt; }
        public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getLastError() { return lastError; }
        public void setLastError(String lastError) { this.lastError = lastError; }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRequestCode() { return requestCode; }
    public void setRequestCode(String requestCode) { this.requestCode = requestCode; }
    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }
    public String getBookingCode() { return bookingCode; }
    public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public Long getCinemaId() { return cinemaId; }
    public void setCinemaId(Long cinemaId) { this.cinemaId = cinemaId; }
    public String getCinemaName() { return cinemaName; }
    public void setCinemaName(String cinemaName) { this.cinemaName = cinemaName; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }
    public String getPurchaseEmail() { return purchaseEmail; }
    public void setPurchaseEmail(String purchaseEmail) { this.purchaseEmail = purchaseEmail; }
    public String getSubmittedOrderCode() { return submittedOrderCode; }
    public void setSubmittedOrderCode(String submittedOrderCode) { this.submittedOrderCode = submittedOrderCode; }
    public String getReasonCategory() { return reasonCategory; }
    public void setReasonCategory(String reasonCategory) { this.reasonCategory = reasonCategory; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getEvidenceUrl() { return evidenceUrl; }
    public void setEvidenceUrl(String evidenceUrl) { this.evidenceUrl = evidenceUrl; }
    public String getToBin() { return toBin; }
    public void setToBin(String toBin) { this.toBin = toBin; }
    public String getToBankName() { return toBankName; }
    public void setToBankName(String toBankName) { this.toBankName = toBankName; }
    public String getToAccountNumberMasked() { return toAccountNumberMasked; }
    public void setToAccountNumberMasked(String toAccountNumberMasked) { this.toAccountNumberMasked = toAccountNumberMasked; }
    public String getAccountHolderName() { return accountHolderName; }
    public void setAccountHolderName(String accountHolderName) { this.accountHolderName = accountHolderName; }
    public String getBankNote() { return bankNote; }
    public void setBankNote(String bankNote) { this.bankNote = bankNote; }
    public Long getSavedBankAccountId() { return savedBankAccountId; }
    public void setSavedBankAccountId(Long savedBankAccountId) { this.savedBankAccountId = savedBankAccountId; }
    public boolean isBankAccountEditable() { return bankAccountEditable; }
    public void setBankAccountEditable(boolean bankAccountEditable) { this.bankAccountEditable = bankAccountEditable; }
    public BigDecimal getRequestedCashTotal() { return requestedCashTotal; }
    public void setRequestedCashTotal(BigDecimal requestedCashTotal) { this.requestedCashTotal = requestedCashTotal; }
    public BigDecimal getApprovedCashTotal() { return approvedCashTotal; }
    public void setApprovedCashTotal(BigDecimal approvedCashTotal) { this.approvedCashTotal = approvedCashTotal; }
    public BigDecimal getApprovedVoucherTotal() { return approvedVoucherTotal; }
    public void setApprovedVoucherTotal(BigDecimal approvedVoucherTotal) { this.approvedVoucherTotal = approvedVoucherTotal; }
    public String getRequestStatus() { return requestStatus; }
    public void setRequestStatus(String requestStatus) { this.requestStatus = requestStatus; }
    public String getPayoutStatus() { return payoutStatus; }
    public void setPayoutStatus(String payoutStatus) { this.payoutStatus = payoutStatus; }
    public String getVoucherStatus() { return voucherStatus; }
    public void setVoucherStatus(String voucherStatus) { this.voucherStatus = voucherStatus; }
    public String getWorkflowStatus() { return workflowStatus; }
    public void setWorkflowStatus(String workflowStatus) { this.workflowStatus = workflowStatus; }
    public String getRefundMethod() { return refundMethod; }
    public void setRefundMethod(String refundMethod) { this.refundMethod = refundMethod; }
    public Long getOriginalPaymentId() { return originalPaymentId; }
    public void setOriginalPaymentId(Long originalPaymentId) { this.originalPaymentId = originalPaymentId; }
    public String getOriginalPaymentRef() { return originalPaymentRef; }
    public void setOriginalPaymentRef(String originalPaymentRef) { this.originalPaymentRef = originalPaymentRef; }
    public LocalDateTime getRefundDeadlineAt() { return refundDeadlineAt; }
    public void setRefundDeadlineAt(LocalDateTime refundDeadlineAt) { this.refundDeadlineAt = refundDeadlineAt; }
    public String getDeadlineBasis() { return deadlineBasis; }
    public void setDeadlineBasis(String deadlineBasis) { this.deadlineBasis = deadlineBasis; }
    public boolean isDeadlineExceeded() { return deadlineExceeded; }
    public void setDeadlineExceeded(boolean deadlineExceeded) { this.deadlineExceeded = deadlineExceeded; }
    public Long getDeadlineExceptionBy() { return deadlineExceptionBy; }
    public void setDeadlineExceptionBy(Long deadlineExceptionBy) { this.deadlineExceptionBy = deadlineExceptionBy; }
    public String getDeadlineExceptionReason() { return deadlineExceptionReason; }
    public void setDeadlineExceptionReason(String deadlineExceptionReason) { this.deadlineExceptionReason = deadlineExceptionReason; }
    public LocalDateTime getDeadlineExceptionAt() { return deadlineExceptionAt; }
    public void setDeadlineExceptionAt(LocalDateTime deadlineExceptionAt) { this.deadlineExceptionAt = deadlineExceptionAt; }
    public String getFeeAssessmentStatus() { return feeAssessmentStatus; }
    public void setFeeAssessmentStatus(String feeAssessmentStatus) { this.feeAssessmentStatus = feeAssessmentStatus; }
    public Long getFeeAmountVnd() { return feeAmountVnd; }
    public void setFeeAmountVnd(Long feeAmountVnd) { this.feeAmountVnd = feeAmountVnd; }
    public Long getNetRecipientVnd() { return netRecipientVnd; }
    public void setNetRecipientVnd(Long netRecipientVnd) { this.netRecipientVnd = netRecipientVnd; }
    public Long getTotalDebitVnd() { return totalDebitVnd; }
    public void setTotalDebitVnd(Long totalDebitVnd) { this.totalDebitVnd = totalDebitVnd; }
    public Boolean getGuaranteedFullReceipt() { return guaranteedFullReceipt; }
    public void setGuaranteedFullReceipt(Boolean guaranteedFullReceipt) { this.guaranteedFullReceipt = guaranteedFullReceipt; }
    public String getPayoutBlockedReason() { return payoutBlockedReason; }
    public void setPayoutBlockedReason(String payoutBlockedReason) { this.payoutBlockedReason = payoutBlockedReason; }
    public String getRecipientSnapshotHash() { return recipientSnapshotHash; }
    public void setRecipientSnapshotHash(String recipientSnapshotHash) { this.recipientSnapshotHash = recipientSnapshotHash; }
    public String getTransferDescription() { return transferDescription; }
    public void setTransferDescription(String transferDescription) { this.transferDescription = transferDescription; }
    public boolean isLivePayoutEnabled() { return livePayoutEnabled; }
    public void setLivePayoutEnabled(boolean livePayoutEnabled) { this.livePayoutEnabled = livePayoutEnabled; }
    public String getPayoutMode() { return payoutMode; }
    public void setPayoutMode(String payoutMode) { this.payoutMode = payoutMode; }
    public boolean isLimitOverrideApplied() { return limitOverrideApplied; }
    public void setLimitOverrideApplied(boolean limitOverrideApplied) { this.limitOverrideApplied = limitOverrideApplied; }
    public Long getLimitOverrideBy() { return limitOverrideBy; }
    public void setLimitOverrideBy(Long limitOverrideBy) { this.limitOverrideBy = limitOverrideBy; }
    public String getLimitOverrideReason() { return limitOverrideReason; }
    public void setLimitOverrideReason(String limitOverrideReason) { this.limitOverrideReason = limitOverrideReason; }
    public LocalDateTime getLimitOverrideAt() { return limitOverrideAt; }
    public void setLimitOverrideAt(LocalDateTime limitOverrideAt) { this.limitOverrideAt = limitOverrideAt; }
    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }
    public Long getReviewedById() { return reviewedById; }
    public void setReviewedById(Long reviewedById) { this.reviewedById = reviewedById; }
    public String getReviewedByName() { return reviewedByName; }
    public void setReviewedByName(String reviewedByName) { this.reviewedByName = reviewedByName; }
    public String getReviewedByRole() { return reviewedByRole; }
    public void setReviewedByRole(String reviewedByRole) { this.reviewedByRole = reviewedByRole; }
    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }
    public Long getPayoutDecidedById() { return payoutDecidedById; }
    public void setPayoutDecidedById(Long payoutDecidedById) { this.payoutDecidedById = payoutDecidedById; }
    public String getPayoutDecidedByName() { return payoutDecidedByName; }
    public void setPayoutDecidedByName(String payoutDecidedByName) { this.payoutDecidedByName = payoutDecidedByName; }
    public LocalDateTime getPayoutDecidedAt() { return payoutDecidedAt; }
    public void setPayoutDecidedAt(LocalDateTime payoutDecidedAt) { this.payoutDecidedAt = payoutDecidedAt; }
    public String getPayoutDecisionNote() { return payoutDecisionNote; }
    public void setPayoutDecisionNote(String payoutDecisionNote) { this.payoutDecisionNote = payoutDecisionNote; }
    public boolean isPayoutEligible() { return payoutEligible; }
    public void setPayoutEligible(boolean payoutEligible) { this.payoutEligible = payoutEligible; }
    public List<String> getPayoutEligibilityErrors() { return payoutEligibilityErrors; }
    public void setPayoutEligibilityErrors(List<String> payoutEligibilityErrors) { this.payoutEligibilityErrors = payoutEligibilityErrors; }
    public String getPayoutDisplayStage() { return payoutDisplayStage; }
    public void setPayoutDisplayStage(String payoutDisplayStage) { this.payoutDisplayStage = payoutDisplayStage; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public OriginalPaymentDto getOriginalPayment() { return originalPayment; }
    public void setOriginalPayment(OriginalPaymentDto originalPayment) { this.originalPayment = originalPayment; }
    public List<RefundItemDto> getItems() { return items; }
    public void setItems(List<RefundItemDto> items) { this.items = items; }
    public List<CommunicationDto> getCommunications() { return communications; }
    public void setCommunications(List<CommunicationDto> communications) { this.communications = communications; }
    public List<ApprovalHistoryDto> getHistory() { return history; }
    public void setHistory(List<ApprovalHistoryDto> history) { this.history = history; }
    public PayoutSummaryDto getLatestPayout() { return latestPayout; }
    public void setLatestPayout(PayoutSummaryDto latestPayout) { this.latestPayout = latestPayout; }
    public List<PayoutSummaryDto> getPayoutTransactions() { return payoutTransactions; }
    public void setPayoutTransactions(List<PayoutSummaryDto> payoutTransactions) { this.payoutTransactions = payoutTransactions; }
    public VoucherSummaryDto getRecoveryVoucher() { return recoveryVoucher; }
    public void setRecoveryVoucher(VoucherSummaryDto recoveryVoucher) { this.recoveryVoucher = recoveryVoucher; }
    public List<IncidentResponseDto> getRelatedIncidents() { return relatedIncidents; }
    public void setRelatedIncidents(List<IncidentResponseDto> relatedIncidents) { this.relatedIncidents = relatedIncidents; }
}
