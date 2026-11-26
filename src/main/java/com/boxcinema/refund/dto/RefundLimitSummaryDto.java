package com.boxcinema.refund.dto;

import java.time.LocalDate;

public class RefundLimitSummaryDto {
    private LocalDate quotaDate;
    private Long managerPerRequestLimitVnd;

    private Long managerUserId;
    private Long managerDailyLimitVnd;
    private Long managerCommittedVnd;
    private Long managerReservedVnd;
    private Long managerReleasedVnd;
    private Long managerAvailableVnd;

    private Long cinemaId;
    private Long cinemaDailyLimitVnd;
    private Long cinemaCommittedVnd;
    private Long cinemaReservedVnd;
    private Long cinemaReleasedVnd;
    private Long cinemaAvailableVnd;

    private Long incidentId;
    private Long incidentCumulativeLimitVnd;
    private Long incidentCommittedVnd;
    private Long incidentReservedVnd;
    private Long incidentReleasedVnd;
    private Long incidentAvailableVnd;

    public LocalDate getQuotaDate() {
        return quotaDate;
    }

    public void setQuotaDate(LocalDate quotaDate) {
        this.quotaDate = quotaDate;
    }

    public Long getManagerPerRequestLimitVnd() {
        return managerPerRequestLimitVnd;
    }

    public void setManagerPerRequestLimitVnd(Long managerPerRequestLimitVnd) {
        this.managerPerRequestLimitVnd = managerPerRequestLimitVnd;
    }

    public Long getManagerUserId() {
        return managerUserId;
    }

    public void setManagerUserId(Long managerUserId) {
        this.managerUserId = managerUserId;
    }

    public Long getManagerDailyLimitVnd() {
        return managerDailyLimitVnd;
    }

    public void setManagerDailyLimitVnd(Long managerDailyLimitVnd) {
        this.managerDailyLimitVnd = managerDailyLimitVnd;
    }

    public Long getManagerCommittedVnd() {
        return managerCommittedVnd;
    }

    public void setManagerCommittedVnd(Long managerCommittedVnd) {
        this.managerCommittedVnd = managerCommittedVnd;
    }

    public Long getManagerReservedVnd() {
        return managerReservedVnd;
    }

    public void setManagerReservedVnd(Long managerReservedVnd) {
        this.managerReservedVnd = managerReservedVnd;
    }

    public Long getManagerReleasedVnd() {
        return managerReleasedVnd;
    }

    public void setManagerReleasedVnd(Long managerReleasedVnd) {
        this.managerReleasedVnd = managerReleasedVnd;
    }

    public Long getManagerAvailableVnd() {
        return managerAvailableVnd;
    }

    public void setManagerAvailableVnd(Long managerAvailableVnd) {
        this.managerAvailableVnd = managerAvailableVnd;
    }

    public Long getCinemaId() {
        return cinemaId;
    }

    public void setCinemaId(Long cinemaId) {
        this.cinemaId = cinemaId;
    }

    public Long getCinemaDailyLimitVnd() {
        return cinemaDailyLimitVnd;
    }

    public void setCinemaDailyLimitVnd(Long cinemaDailyLimitVnd) {
        this.cinemaDailyLimitVnd = cinemaDailyLimitVnd;
    }

    public Long getCinemaCommittedVnd() {
        return cinemaCommittedVnd;
    }

    public void setCinemaCommittedVnd(Long cinemaCommittedVnd) {
        this.cinemaCommittedVnd = cinemaCommittedVnd;
    }

    public Long getCinemaReservedVnd() {
        return cinemaReservedVnd;
    }

    public void setCinemaReservedVnd(Long cinemaReservedVnd) {
        this.cinemaReservedVnd = cinemaReservedVnd;
    }

    public Long getCinemaReleasedVnd() {
        return cinemaReleasedVnd;
    }

    public void setCinemaReleasedVnd(Long cinemaReleasedVnd) {
        this.cinemaReleasedVnd = cinemaReleasedVnd;
    }

    public Long getCinemaAvailableVnd() {
        return cinemaAvailableVnd;
    }

    public void setCinemaAvailableVnd(Long cinemaAvailableVnd) {
        this.cinemaAvailableVnd = cinemaAvailableVnd;
    }

    public Long getIncidentId() {
        return incidentId;
    }

    public void setIncidentId(Long incidentId) {
        this.incidentId = incidentId;
    }

    public Long getIncidentCumulativeLimitVnd() {
        return incidentCumulativeLimitVnd;
    }

    public void setIncidentCumulativeLimitVnd(Long incidentCumulativeLimitVnd) {
        this.incidentCumulativeLimitVnd = incidentCumulativeLimitVnd;
    }

    public Long getIncidentCommittedVnd() {
        return incidentCommittedVnd;
    }

    public void setIncidentCommittedVnd(Long incidentCommittedVnd) {
        this.incidentCommittedVnd = incidentCommittedVnd;
    }

    public Long getIncidentReservedVnd() {
        return incidentReservedVnd;
    }

    public void setIncidentReservedVnd(Long incidentReservedVnd) {
        this.incidentReservedVnd = incidentReservedVnd;
    }

    public Long getIncidentReleasedVnd() {
        return incidentReleasedVnd;
    }

    public void setIncidentReleasedVnd(Long incidentReleasedVnd) {
        this.incidentReleasedVnd = incidentReleasedVnd;
    }

    public Long getIncidentAvailableVnd() {
        return incidentAvailableVnd;
    }

    public void setIncidentAvailableVnd(Long incidentAvailableVnd) {
        this.incidentAvailableVnd = incidentAvailableVnd;
    }
}
