package com.boxcinema.refund.dto;

import java.time.LocalDateTime;

public class IncidentCreateRequestDto {
    private Long cinemaId;
    private Long showtimeId;
    private String bookingCode;
    private Long bookingFoodItemId;
    private Long bookingFoodItemComponentId;
    private String incidentGroup; // CINEMA_INCIDENT, PRODUCT_INCIDENT, STAFF_ERROR
    private String incidentType;
    private String severity;
    private String title;
    private String description;
    private String evidenceUrl;
    private Integer affectedQuantity;
    private Long staffErrorUserId;
    private LocalDateTime customerNotifiedAt;

    public Long getCinemaId() { return cinemaId; }
    public void setCinemaId(Long cinemaId) { this.cinemaId = cinemaId; }
    public Long getShowtimeId() { return showtimeId; }
    public void setShowtimeId(Long showtimeId) { this.showtimeId = showtimeId; }
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
    public Long getStaffErrorUserId() { return staffErrorUserId; }
    public void setStaffErrorUserId(Long staffErrorUserId) { this.staffErrorUserId = staffErrorUserId; }
    public LocalDateTime getCustomerNotifiedAt() { return customerNotifiedAt; }
    public void setCustomerNotifiedAt(LocalDateTime customerNotifiedAt) { this.customerNotifiedAt = customerNotifiedAt; }
}
