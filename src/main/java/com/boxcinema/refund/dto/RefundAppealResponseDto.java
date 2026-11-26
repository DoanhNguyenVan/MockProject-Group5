package com.boxcinema.refund.dto;

import java.time.LocalDateTime;
import java.util.List;

public class RefundAppealResponseDto {
    private Long id;
    private String appealCode;
    private Long refundRequestId;
    private String refundRequestCode;
    private String refundWorkflowStatus;
    private String refundRequestStatus;
    private Long cinemaId;
    private String cinemaName;
    private Long customerUserId;
    private String purchaseEmail;
    private String appealReason;
    private String evidenceUrl;
    private String status;
    private Boolean activeLockOpen;
    private Long reviewedById;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private String decisionNote;
    private LocalDateTime submittedAt;
    private LocalDateTime updatedAt;
    private List<AppealHistoryItemDto> history;

    public static class AppealHistoryItemDto {
        private Long id;
        private Long actorUserId;
        private String actorRole;
        private String action;
        private String previousStatus;
        private String newStatus;
        private String note;
        private LocalDateTime createdAt;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public Long getActorUserId() {
            return actorUserId;
        }

        public void setActorUserId(Long actorUserId) {
            this.actorUserId = actorUserId;
        }

        public String getActorRole() {
            return actorRole;
        }

        public void setActorRole(String actorRole) {
            this.actorRole = actorRole;
        }

        public String getAction() {
            return action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String getPreviousStatus() {
            return previousStatus;
        }

        public void setPreviousStatus(String previousStatus) {
            this.previousStatus = previousStatus;
        }

        public String getNewStatus() {
            return newStatus;
        }

        public void setNewStatus(String newStatus) {
            this.newStatus = newStatus;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }

        public LocalDateTime getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(LocalDateTime createdAt) {
            this.createdAt = createdAt;
        }
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

    public Long getRefundRequestId() {
        return refundRequestId;
    }

    public void setRefundRequestId(Long refundRequestId) {
        this.refundRequestId = refundRequestId;
    }

    public String getRefundRequestCode() {
        return refundRequestCode;
    }

    public void setRefundRequestCode(String refundRequestCode) {
        this.refundRequestCode = refundRequestCode;
    }

    public String getRefundWorkflowStatus() {
        return refundWorkflowStatus;
    }

    public void setRefundWorkflowStatus(String refundWorkflowStatus) {
        this.refundWorkflowStatus = refundWorkflowStatus;
    }

    public String getRefundRequestStatus() {
        return refundRequestStatus;
    }

    public void setRefundRequestStatus(String refundRequestStatus) {
        this.refundRequestStatus = refundRequestStatus;
    }

    public Long getCinemaId() {
        return cinemaId;
    }

    public void setCinemaId(Long cinemaId) {
        this.cinemaId = cinemaId;
    }

    public String getCinemaName() {
        return cinemaName;
    }

    public void setCinemaName(String cinemaName) {
        this.cinemaName = cinemaName;
    }

    public Long getCustomerUserId() {
        return customerUserId;
    }

    public void setCustomerUserId(Long customerUserId) {
        this.customerUserId = customerUserId;
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

    public Boolean getActiveLockOpen() {
        return activeLockOpen;
    }

    public void setActiveLockOpen(Boolean activeLockOpen) {
        this.activeLockOpen = activeLockOpen;
    }

    public Long getReviewedById() {
        return reviewedById;
    }

    public void setReviewedById(Long reviewedById) {
        this.reviewedById = reviewedById;
    }

    public String getReviewedByName() {
        return reviewedByName;
    }

    public void setReviewedByName(String reviewedByName) {
        this.reviewedByName = reviewedByName;
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

    public List<AppealHistoryItemDto> getHistory() {
        return history;
    }

    public void setHistory(List<AppealHistoryItemDto> history) {
        this.history = history;
    }
}
