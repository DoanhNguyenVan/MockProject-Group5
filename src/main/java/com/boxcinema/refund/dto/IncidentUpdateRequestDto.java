package com.boxcinema.refund.dto;

import java.time.LocalDateTime;

public class IncidentUpdateRequestDto {
    private String incidentType;
    private String severity;
    private String title;
    private String description;
    private String evidenceUrl;
    private Integer affectedQuantity;
    private String status;
    private LocalDateTime customerNotifiedAt;

    public String getIncidentType() {
        return incidentType;
    }

    public void setIncidentType(String incidentType) {
        this.incidentType = incidentType;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEvidenceUrl() {
        return evidenceUrl;
    }

    public void setEvidenceUrl(String evidenceUrl) {
        this.evidenceUrl = evidenceUrl;
    }

    public Integer getAffectedQuantity() {
        return affectedQuantity;
    }

    public void setAffectedQuantity(Integer affectedQuantity) {
        this.affectedQuantity = affectedQuantity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCustomerNotifiedAt() {
        return customerNotifiedAt;
    }

    public void setCustomerNotifiedAt(LocalDateTime customerNotifiedAt) {
        this.customerNotifiedAt = customerNotifiedAt;
    }
}
