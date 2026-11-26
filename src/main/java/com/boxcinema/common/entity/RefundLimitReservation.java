package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "refund_limit_reservations")
public class RefundLimitReservation {

    public static final String STATUS_RESERVED = "RESERVED";
    public static final String STATUS_COMMITTED = "COMMITTED";
    public static final String STATUS_RELEASED = "RELEASED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "refund_request_id", nullable = false)
    private Long refundRequestId;

    @Column(name = "manager_user_id")
    private Long managerUserId;

    @Column(name = "cinema_id", nullable = false)
    private Long cinemaId;

    @Column(name = "incident_report_id")
    private Long incidentReportId;

    @Column(name = "quota_date", nullable = false)
    private LocalDate quotaDate;

    @Column(name = "amount_vnd", nullable = false)
    private Long amountVnd;

    @Column(name = "status", nullable = false, length = 20)
    private String status = STATUS_RESERVED;

    @Column(name = "active_lock_key", unique = true, length = 120)
    private String activeLockKey;

    @Column(name = "override_applied", nullable = false)
    private Boolean overrideApplied = false;

    @Column(name = "reserved_at", nullable = false)
    private LocalDateTime reservedAt;

    @Column(name = "committed_at")
    private LocalDateTime committedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "release_reason", length = 255)
    private String releaseReason;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.reservedAt == null) {
            this.reservedAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.status == null) {
            this.status = STATUS_RESERVED;
        }
        if (this.overrideApplied == null) {
            this.overrideApplied = false;
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

    public Long getRefundRequestId() {
        return refundRequestId;
    }

    public void setRefundRequestId(Long refundRequestId) {
        this.refundRequestId = refundRequestId;
    }

    public Long getManagerUserId() {
        return managerUserId;
    }

    public void setManagerUserId(Long managerUserId) {
        this.managerUserId = managerUserId;
    }

    public Long getCinemaId() {
        return cinemaId;
    }

    public void setCinemaId(Long cinemaId) {
        this.cinemaId = cinemaId;
    }

    public Long getIncidentReportId() {
        return incidentReportId;
    }

    public void setIncidentReportId(Long incidentReportId) {
        this.incidentReportId = incidentReportId;
    }

    public LocalDate getQuotaDate() {
        return quotaDate;
    }

    public void setQuotaDate(LocalDate quotaDate) {
        this.quotaDate = quotaDate;
    }

    public Long getAmountVnd() {
        return amountVnd;
    }

    public void setAmountVnd(Long amountVnd) {
        this.amountVnd = amountVnd;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getActiveLockKey() {
        return activeLockKey;
    }

    public void setActiveLockKey(String activeLockKey) {
        this.activeLockKey = activeLockKey;
    }

    public Boolean getOverrideApplied() {
        return overrideApplied;
    }

    public void setOverrideApplied(Boolean overrideApplied) {
        this.overrideApplied = overrideApplied;
    }

    public LocalDateTime getReservedAt() {
        return reservedAt;
    }

    public void setReservedAt(LocalDateTime reservedAt) {
        this.reservedAt = reservedAt;
    }

    public LocalDateTime getCommittedAt() {
        return committedAt;
    }

    public void setCommittedAt(LocalDateTime committedAt) {
        this.committedAt = committedAt;
    }

    public LocalDateTime getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(LocalDateTime releasedAt) {
        this.releasedAt = releasedAt;
    }

    public String getReleaseReason() {
        return releaseReason;
    }

    public void setReleaseReason(String releaseReason) {
        this.releaseReason = releaseReason;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
