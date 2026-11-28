package com.boxcinema.common.entity;

import com.boxcinema.common.enums.RefundMethod;
import com.boxcinema.common.enums.RefundWorkflowState;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "refund_requests")
public class RefundRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_code", nullable = false, unique = true, length = 40)
    private String requestCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cinema_id", nullable = false)
    private Cinema cinema;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_user_id")
    private User customerUser;

    @Column(name = "customer_name", length = 120)
    private String customerName;

    @Column(name = "customer_phone", length = 30)
    private String customerPhone;

    @Column(name = "purchase_email", nullable = false, length = 255)
    private String purchaseEmail;

    @Column(name = "submitted_order_code", nullable = false, length = 50)
    private String submittedOrderCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_report_id")
    private IncidentReport incidentReport;

    @Column(name = "reason_category", nullable = false, length = 50)
    private String reasonCategory;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "evidence_url", length = 500)
    private String evidenceUrl;

    @Column(name = "to_bin", length = 20)
    private String toBin;

    @Column(name = "to_bank_name", length = 100)
    private String toBankName;

    @Column(name = "to_account_number_encrypted", columnDefinition = "TEXT")
    private String toAccountNumberEncrypted;

    @Column(name = "to_account_number_masked", length = 50)
    private String toAccountNumberMasked;

    @Column(name = "to_account_number_hash", length = 64)
    private String toAccountNumberHash;

    @Column(name = "account_holder_name", length = 150)
    private String accountHolderName;

    @Column(name = "bank_note", length = 255)
    private String bankNote;

    @Column(name = "saved_bank_account_id")
    private Long savedBankAccountId;

    @Column(name = "requested_cash_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal requestedCashTotal = BigDecimal.ZERO;

    @Column(name = "approved_cash_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal approvedCashTotal = BigDecimal.ZERO;

    @Column(name = "approved_voucher_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal approvedVoucherTotal = BigDecimal.ZERO;

    @Column(name = "request_status", nullable = false, length = 30)
    private String requestStatus = "PENDING_REVIEW";

    @Column(name = "payout_status", nullable = false, length = 30)
    private String payoutStatus = "NOT_REQUIRED";

    @Column(name = "voucher_status", nullable = false, length = 30)
    private String voucherStatus = "NOT_REQUIRED";

    @Column(name = "workflow_status", nullable = false, length = 30)
    private String workflowStatus = "REQUESTED";

    @Column(name = "refund_method", nullable = false, length = 30)
    private String refundMethod = RefundMethod.BANK_PAYOUT.name();

    @Column(name = "original_payment_id")
    private Long originalPaymentId;

    @Column(name = "original_payment_ref", length = 100)
    private String originalPaymentRef;

    @Column(name = "refund_deadline_at")
    private LocalDateTime refundDeadlineAt;

    @Column(name = "deadline_basis", length = 40)
    private String deadlineBasis;

    @Column(name = "deadline_exceeded", nullable = false)
    private boolean deadlineExceeded = false;

    @Column(name = "deadline_exception_by")
    private Long deadlineExceptionBy;

    @Column(name = "deadline_exception_reason", columnDefinition = "TEXT")
    private String deadlineExceptionReason;

    @Column(name = "deadline_exception_at")
    private LocalDateTime deadlineExceptionAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payout_decided_by")
    private User payoutDecidedBy;

    @Column(name = "payout_decided_at")
    private LocalDateTime payoutDecidedAt;

    @Column(name = "payout_decision_note", columnDefinition = "TEXT")
    private String payoutDecisionNote;

    @Column(name = "fee_assessment_status", length = 30)
    private String feeAssessmentStatus;

    @Column(name = "fee_amount_vnd", nullable = false)
    private Long feeAmountVnd = 0L;

    @Column(name = "payout_blocked_reason", columnDefinition = "TEXT")
    private String payoutBlockedReason;

    @Column(name = "recipient_snapshot_json", columnDefinition = "TEXT")
    private String recipientSnapshotJson;

    @Column(name = "recipient_snapshot_hash", length = 64)
    private String recipientSnapshotHash;

    @Column(name = "limit_override_applied", nullable = false)
    private boolean limitOverrideApplied = false;

    @Column(name = "limit_override_by")
    private Long limitOverrideBy;

    @Column(name = "limit_override_reason", columnDefinition = "TEXT")
    private String limitOverrideReason;

    @Column(name = "limit_override_at")
    private LocalDateTime limitOverrideAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        if (this.updatedAt == null) this.updatedAt = now;
        if (this.refundMethod == null || this.refundMethod.isBlank()) {
            this.refundMethod = RefundMethod.BANK_PAYOUT.name();
        }
        if (this.feeAmountVnd == null) {
            this.feeAmountVnd = 0L;
        }
        syncWorkflowStatusFromLegacy();
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        if (this.refundMethod == null || this.refundMethod.isBlank()) {
            this.refundMethod = RefundMethod.BANK_PAYOUT.name();
        }
        if (this.feeAmountVnd == null) {
            this.feeAmountVnd = 0L;
        }
        syncWorkflowStatusFromLegacy();
    }

    public void syncWorkflowStatusFromLegacy() {
        if ("PAYOUT_BLOCKED".equalsIgnoreCase(this.workflowStatus)
                && !"COMPLETED".equalsIgnoreCase(this.requestStatus)
                && !"PAYOUT_SUCCEEDED".equalsIgnoreCase(this.requestStatus)
                && !"CANCELLED".equalsIgnoreCase(this.requestStatus)
                && !"REJECTED".equalsIgnoreCase(this.requestStatus)
                && !"PAYOUT_REJECTED".equalsIgnoreCase(this.requestStatus)) {
            return;
        }
        boolean unknown = "UNKNOWN".equalsIgnoreCase(this.workflowStatus);
        this.workflowStatus = RefundWorkflowState.fromLegacyStatuses(this.requestStatus, this.payoutStatus, unknown).name();
    }

    @Override
    public String toString() {
        return "RefundRequest{id=" + id + ", requestCode='" + requestCode + "', status='" + requestStatus + "'}";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRequestCode() { return requestCode; }
    public void setRequestCode(String requestCode) { this.requestCode = requestCode; }

    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }

    public Cinema getCinema() { return cinema; }
    public void setCinema(Cinema cinema) { this.cinema = cinema; }

    public User getCustomerUser() { return customerUser; }
    public void setCustomerUser(User customerUser) { this.customerUser = customerUser; }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }

    public String getPurchaseEmail() { return purchaseEmail; }
    public void setPurchaseEmail(String purchaseEmail) { this.purchaseEmail = purchaseEmail; }

    public String getSubmittedOrderCode() { return submittedOrderCode; }
    public void setSubmittedOrderCode(String submittedOrderCode) { this.submittedOrderCode = submittedOrderCode; }

    public IncidentReport getIncidentReport() { return incidentReport; }
    public void setIncidentReport(IncidentReport incidentReport) { this.incidentReport = incidentReport; }

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

    public String getToAccountNumberEncrypted() { return toAccountNumberEncrypted; }
    public void setToAccountNumberEncrypted(String toAccountNumberEncrypted) { this.toAccountNumberEncrypted = toAccountNumberEncrypted; }

    public String getToAccountNumberMasked() { return toAccountNumberMasked; }
    public void setToAccountNumberMasked(String toAccountNumberMasked) { this.toAccountNumberMasked = toAccountNumberMasked; }

    public String getToAccountNumberHash() { return toAccountNumberHash; }
    public void setToAccountNumberHash(String toAccountNumberHash) { this.toAccountNumberHash = toAccountNumberHash; }

    public String getAccountHolderName() { return accountHolderName; }
    public void setAccountHolderName(String accountHolderName) { this.accountHolderName = accountHolderName; }

    public String getBankNote() { return bankNote; }
    public void setBankNote(String bankNote) { this.bankNote = bankNote; }

    public Long getSavedBankAccountId() { return savedBankAccountId; }
    public void setSavedBankAccountId(Long savedBankAccountId) { this.savedBankAccountId = savedBankAccountId; }

    public BigDecimal getRequestedCashTotal() { return requestedCashTotal; }
    public void setRequestedCashTotal(BigDecimal requestedCashTotal) { this.requestedCashTotal = requestedCashTotal; }

    public BigDecimal getApprovedCashTotal() { return approvedCashTotal; }
    public void setApprovedCashTotal(BigDecimal approvedCashTotal) { this.approvedCashTotal = approvedCashTotal; }

    public BigDecimal getApprovedVoucherTotal() { return approvedVoucherTotal; }
    public void setApprovedVoucherTotal(BigDecimal approvedVoucherTotal) { this.approvedVoucherTotal = approvedVoucherTotal; }

    public String getRequestStatus() { return requestStatus; }
    public void setRequestStatus(String requestStatus) {
        this.requestStatus = requestStatus;
        syncWorkflowStatusFromLegacy();
    }

    public String getPayoutStatus() { return payoutStatus; }
    public void setPayoutStatus(String payoutStatus) {
        this.payoutStatus = payoutStatus;
        syncWorkflowStatusFromLegacy();
    }

    public String getVoucherStatus() { return voucherStatus; }
    public void setVoucherStatus(String voucherStatus) { this.voucherStatus = voucherStatus; }

    public String getWorkflowStatus() {
        if (workflowStatus == null || workflowStatus.isBlank()) {
            syncWorkflowStatusFromLegacy();
        }
        return workflowStatus;
    }
    public void setWorkflowStatus(String workflowStatus) {
        this.workflowStatus = workflowStatus;
    }
    public RefundWorkflowState getWorkflowState() {
        try {
            return RefundWorkflowState.valueOf(getWorkflowStatus());
        } catch (Exception ex) {
            return RefundWorkflowState.fromLegacyStatuses(requestStatus, payoutStatus, false);
        }
    }
    public void setWorkflowState(RefundWorkflowState state) {
        if (state != null) {
            this.workflowStatus = state.name();
        }
    }

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

    public Long getFeeAmountVnd() { return feeAmountVnd != null ? feeAmountVnd : 0L; }
    public void setFeeAmountVnd(Long feeAmountVnd) { this.feeAmountVnd = feeAmountVnd != null ? feeAmountVnd : 0L; }

    public String getPayoutBlockedReason() { return payoutBlockedReason; }
    public void setPayoutBlockedReason(String payoutBlockedReason) { this.payoutBlockedReason = payoutBlockedReason; }

    public String getRecipientSnapshotJson() { return recipientSnapshotJson; }
    public void setRecipientSnapshotJson(String recipientSnapshotJson) { this.recipientSnapshotJson = recipientSnapshotJson; }

    public String getRecipientSnapshotHash() { return recipientSnapshotHash; }
    public void setRecipientSnapshotHash(String recipientSnapshotHash) { this.recipientSnapshotHash = recipientSnapshotHash; }

    public boolean isLimitOverrideApplied() { return limitOverrideApplied; }
    public void setLimitOverrideApplied(boolean limitOverrideApplied) { this.limitOverrideApplied = limitOverrideApplied; }

    public Long getLimitOverrideBy() { return limitOverrideBy; }
    public void setLimitOverrideBy(Long limitOverrideBy) { this.limitOverrideBy = limitOverrideBy; }

    public String getLimitOverrideReason() { return limitOverrideReason; }
    public void setLimitOverrideReason(String limitOverrideReason) { this.limitOverrideReason = limitOverrideReason; }

    public LocalDateTime getLimitOverrideAt() { return limitOverrideAt; }
    public void setLimitOverrideAt(LocalDateTime limitOverrideAt) { this.limitOverrideAt = limitOverrideAt; }

    public User getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(User reviewedBy) { this.reviewedBy = reviewedBy; }

    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getDecisionNote() { return decisionNote; }
    public void setDecisionNote(String decisionNote) { this.decisionNote = decisionNote; }

    public User getPayoutDecidedBy() { return payoutDecidedBy; }
    public void setPayoutDecidedBy(User payoutDecidedBy) { this.payoutDecidedBy = payoutDecidedBy; }

    public LocalDateTime getPayoutDecidedAt() { return payoutDecidedAt; }
    public void setPayoutDecidedAt(LocalDateTime payoutDecidedAt) { this.payoutDecidedAt = payoutDecidedAt; }

    public String getPayoutDecisionNote() { return payoutDecisionNote; }
    public void setPayoutDecisionNote(String payoutDecisionNote) { this.payoutDecisionNote = payoutDecisionNote; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
