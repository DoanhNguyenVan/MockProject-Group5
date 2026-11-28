package com.boxcinema.refund.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class IncidentResponseDto {
    private Long id;
    private String incidentCode;
    private Long cinemaId;
    private String cinemaName;
    private Long showtimeId;
    private Long bookingId;
    private String bookingCode;
    private Long bookingFoodItemId;
    private Long bookingFoodItemComponentId;
    private String incidentGroup;
    private String incidentType;
    private String severity;
    private String title;
    private String description;
    private String evidenceUrl;
    private Integer affectedQuantity;
    private LocalDateTime occurredAt;
    private LocalDateTime customerNotifiedAt;
    private String reportedByName;
    private String status;
    private String managerVerifiedByName;
    private String managerDecision;
    private String managerNote;
    private LocalDateTime managerDecidedAt;
    private LocalDateTime appealedAt;
    private String appealedByName;
    private String appealReason;
    private String adminResolvedByName;
    private String adminDecision;
    private String adminNote;
    private LocalDateTime adminResolvedAt;
    private String finalConclusion;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private List<VerificationEntryDto> verifications = new ArrayList<>();

    public static class VerificationEntryDto {
        private String actorName;
        private String actorRole;
        private String action;
        private String decision;
        private String comment;
        private String evidenceUrl;
        private LocalDateTime createdAt;

        public String getActorName() { return actorName; }
        public void setActorName(String actorName) { this.actorName = actorName; }
        public String getActorRole() { return actorRole; }
        public void setActorRole(String actorRole) { this.actorRole = actorRole; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getDecision() { return decision; }
        public void setDecision(String decision) { this.decision = decision; }
        public String getComment() { return comment; }
        public void setComment(String comment) { this.comment = comment; }
        public String getEvidenceUrl() { return evidenceUrl; }
        public void setEvidenceUrl(String evidenceUrl) { this.evidenceUrl = evidenceUrl; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getIncidentCode() { return incidentCode; }
    public void setIncidentCode(String incidentCode) { this.incidentCode = incidentCode; }
    public Long getCinemaId() { return cinemaId; }
    public void setCinemaId(Long cinemaId) { this.cinemaId = cinemaId; }
    public String getCinemaName() { return cinemaName; }
    public void setCinemaName(String cinemaName) { this.cinemaName = cinemaName; }
    public Long getShowtimeId() { return showtimeId; }
    public void setShowtimeId(Long showtimeId) { this.showtimeId = showtimeId; }
    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }
    public String getBookingCode() { return bookingCode; }
    public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }
    public Long getBookingFoodItemId() { return bookingFoodItemId; }
    public void setBookingFoodItemId(Long bookingFoodItemId) { this.bookingFoodItemId = bookingFoodItemId; }
    public Long getBookingFoodItemComponentId() { return bookingFoodItemComponentId; }
    public void setBookingFoodItemComponentId(Long bookingFoodItemComponentId) { this.bookingFoodItemComponentId = bookingFoodItemComponentId; }
    public String getIncidentGroup() { return incidentGroup; }
    public void setIncidentGroup(String incidentGroup) { this.incidentGroup = incidentGroup; }
    public String getIncidentType() { return incidentType; }
    public void setIncidentType(String incidentType) { this.incidentType = incidentType; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getEvidenceUrl() { return evidenceUrl; }
    public void setEvidenceUrl(String evidenceUrl) { this.evidenceUrl = evidenceUrl; }
    public Integer getAffectedQuantity() { return affectedQuantity; }
    public void setAffectedQuantity(Integer affectedQuantity) { this.affectedQuantity = affectedQuantity; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public LocalDateTime getCustomerNotifiedAt() { return customerNotifiedAt; }
    public void setCustomerNotifiedAt(LocalDateTime customerNotifiedAt) { this.customerNotifiedAt = customerNotifiedAt; }
    public String getReportedByName() { return reportedByName; }
    public void setReportedByName(String reportedByName) { this.reportedByName = reportedByName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getManagerVerifiedByName() { return managerVerifiedByName; }
    public void setManagerVerifiedByName(String managerVerifiedByName) { this.managerVerifiedByName = managerVerifiedByName; }
    public String getManagerDecision() { return managerDecision; }
    public void setManagerDecision(String managerDecision) { this.managerDecision = managerDecision; }
    public String getManagerNote() { return managerNote; }
    public void setManagerNote(String managerNote) { this.managerNote = managerNote; }
    public LocalDateTime getManagerDecidedAt() { return managerDecidedAt; }
    public void setManagerDecidedAt(LocalDateTime managerDecidedAt) { this.managerDecidedAt = managerDecidedAt; }
    public LocalDateTime getAppealedAt() { return appealedAt; }
    public void setAppealedAt(LocalDateTime appealedAt) { this.appealedAt = appealedAt; }
    public String getAppealedByName() { return appealedByName; }
    public void setAppealedByName(String appealedByName) { this.appealedByName = appealedByName; }
    public String getAppealReason() { return appealReason; }
    public void setAppealReason(String appealReason) { this.appealReason = appealReason; }
    public String getAdminResolvedByName() { return adminResolvedByName; }
    public void setAdminResolvedByName(String adminResolvedByName) { this.adminResolvedByName = adminResolvedByName; }
    public String getAdminDecision() { return adminDecision; }
    public void setAdminDecision(String adminDecision) { this.adminDecision = adminDecision; }
    public String getAdminNote() { return adminNote; }
    public void setAdminNote(String adminNote) { this.adminNote = adminNote; }
    public LocalDateTime getAdminResolvedAt() { return adminResolvedAt; }
    public void setAdminResolvedAt(LocalDateTime adminResolvedAt) { this.adminResolvedAt = adminResolvedAt; }
    public String getFinalConclusion() { return finalConclusion; }
    public void setFinalConclusion(String finalConclusion) { this.finalConclusion = finalConclusion; }
    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public List<VerificationEntryDto> getVerifications() { return verifications; }
    public void setVerifications(List<VerificationEntryDto> verifications) { this.verifications = verifications; }
}
