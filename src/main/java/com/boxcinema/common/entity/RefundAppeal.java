package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "refund_appeals")
public class RefundAppeal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "appeal_code", nullable = false, unique = true, length = 40)
    private String appealCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_request_id", nullable = false)
    private RefundRequest refundRequest;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_user_id")
    private User customerUser;

    @Column(name = "purchase_email", nullable = false, length = 255)
    private String purchaseEmail;

    @Column(name = "appeal_reason", nullable = false, columnDefinition = "TEXT")
    private String appealReason;

    @Column(name = "evidence_url", length = 500)
    private String evidenceUrl;

    @Column(name = "status", nullable = false, length = 30)
    private String status = "APPEAL_SUBMITTED";

    @Column(name = "active_appeal_lock", unique = true)
    private Long activeAppealLock;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewed_by")
    private User reviewedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "decision_note", columnDefinition = "TEXT")
    private String decisionNote;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private LocalDateTime submittedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.submittedAt == null) {
            this.submittedAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.status == null) {
            this.status = "APPEAL_SUBMITTED";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAppealCode() {
        return appealCode;
    }

    public void setAppealCode(String appealCode) {
        this.appealCode = appealCode;
    }

    public RefundRequest getRefundRequest() {
        return refundRequest;
    }

    public void setRefundRequest(RefundRequest refundRequest) {
        this.refundRequest = refundRequest;
    }

    public User getCustomerUser() {
        return customerUser;
    }

    public void setCustomerUser(User customerUser) {
        this.customerUser = customerUser;
    }

    public String getPurchaseEmail() {
        return purchaseEmail;
    }

    public void setPurchaseEmail(String purchaseEmail) {
        this.purchaseEmail = purchaseEmail;
    }

    public String getAppealReason() {
        return appealReason;
    }

    public void setAppealReason(String appealReason) {
        this.appealReason = appealReason;
    }

    public String getEvidenceUrl() {
        return evidenceUrl;
    }

    public void setEvidenceUrl(String evidenceUrl) {
        this.evidenceUrl = evidenceUrl;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getActiveAppealLock() {
        return activeAppealLock;
    }

    public void setActiveAppealLock(Long activeAppealLock) {
        this.activeAppealLock = activeAppealLock;
    }

    public User getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(User reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public void setDecisionNote(String decisionNote) {
        this.decisionNote = decisionNote;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(LocalDateTime submittedAt) {
        this.submittedAt = submittedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
