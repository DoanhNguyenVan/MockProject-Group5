package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "incident_reports")
public class IncidentReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_code", nullable = false, unique = true, length = 40)
    private String incidentCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cinema_id", nullable = false)
    private Cinema cinema;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "showtime_id")
    private Showtime showtime;

    @Column(name = "room_id")
    private Long roomId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @Column(name = "booking_food_item_id")
    private Long bookingFoodItemId;

    @Column(name = "booking_food_item_component_id")
    private Long bookingFoodItemComponentId;

    @Column(name = "incident_group", nullable = false, length = 30)
    private String incidentGroup;

    @Column(name = "incident_type", nullable = false, length = 50)
    private String incidentType;

    @Column(nullable = false, length = 20)
    private String severity = "MEDIUM";

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "evidence_url", length = 500)
    private String evidenceUrl;

    @Column(name = "affected_quantity")
    private Integer affectedQuantity;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt = LocalDateTime.now();

    @Column(name = "customer_notified_at")
    private LocalDateTime customerNotifiedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reported_by_staff_id", nullable = false)
    private User reportedByStaff;

    @Column(nullable = false, length = 30)
    private String status = "PENDING_VERIFICATION";

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_verified_by")
    private User managerVerifiedBy;

    @Column(name = "manager_decision", length = 30)
    private String managerDecision;

    @Column(name = "manager_note", columnDefinition = "TEXT")
    private String managerNote;

    @Column(name = "manager_decided_at")
    private LocalDateTime managerDecidedAt;

    @Column(name = "appealed_at")
    private LocalDateTime appealedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appealed_by")
    private User appealedBy;

    @Column(name = "appeal_reason", columnDefinition = "TEXT")
    private String appealReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_resolved_by")
    private User adminResolvedBy;

    @Column(name = "admin_decision", length = 30)
    private String adminDecision;

    @Column(name = "admin_note", columnDefinition = "TEXT")
    private String adminNote;

    @Column(name = "admin_resolved_at")
    private LocalDateTime adminResolvedAt;

    @Column(name = "final_conclusion", nullable = false, length = 30)
    private String finalConclusion = "PENDING";

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        if (this.updatedAt == null) this.updatedAt = now;
        if (this.occurredAt == null) this.occurredAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getIncidentCode() { return incidentCode; }
    public void setIncidentCode(String incidentCode) { this.incidentCode = incidentCode; }

    public Cinema getCinema() { return cinema; }
    public void setCinema(Cinema cinema) { this.cinema = cinema; }

    public Showtime getShowtime() { return showtime; }
    public void setShowtime(Showtime showtime) { this.showtime = showtime; }

    public Long getRoomId() { return roomId; }
    public void setRoomId(Long roomId) { this.roomId = roomId; }

    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }

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

    public User getReportedByStaff() { return reportedByStaff; }
    public void setReportedByStaff(User reportedByStaff) { this.reportedByStaff = reportedByStaff; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public User getManagerVerifiedBy() { return managerVerifiedBy; }
    public void setManagerVerifiedBy(User managerVerifiedBy) { this.managerVerifiedBy = managerVerifiedBy; }

    public String getManagerDecision() { return managerDecision; }
    public void setManagerDecision(String managerDecision) { this.managerDecision = managerDecision; }

    public String getManagerNote() { return managerNote; }
    public void setManagerNote(String managerNote) { this.managerNote = managerNote; }

    public LocalDateTime getManagerDecidedAt() { return managerDecidedAt; }
    public void setManagerDecidedAt(LocalDateTime managerDecidedAt) { this.managerDecidedAt = managerDecidedAt; }

    public LocalDateTime getAppealedAt() { return appealedAt; }
    public void setAppealedAt(LocalDateTime appealedAt) { this.appealedAt = appealedAt; }

    public User getAppealedBy() { return appealedBy; }
    public void setAppealedBy(User appealedBy) { this.appealedBy = appealedBy; }

    public String getAppealReason() { return appealReason; }
    public void setAppealReason(String appealReason) { this.appealReason = appealReason; }

    public User getAdminResolvedBy() { return adminResolvedBy; }
    public void setAdminResolvedBy(User adminResolvedBy) { this.adminResolvedBy = adminResolvedBy; }

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

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
