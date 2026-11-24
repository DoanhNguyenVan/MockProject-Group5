package com.boxcinema.admin.dto;

import java.time.LocalDateTime;

public class AdminFeedbackDto {
    private Long inquiryId;
    private Long userId;
    private String fullName;
    private String email;
    private String phone;
    private String inquiryTopic;
    private String message;
    private String inquiryStatus;
    private Long assignedTo;
    private String assignedToName;
    private String adminResponse;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime respondedAt;

    public AdminFeedbackDto() {}

    public Long getInquiryId() {
        return inquiryId;
    }

    public void setInquiryId(Long inquiryId) {
        this.inquiryId = inquiryId;
    }

    public String getTicketCode() {
        return "BOX-REQ-" + inquiryId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public boolean isRegisteredMember() {
        return userId != null && userId > 0;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getInquiryTopic() {
        return inquiryTopic;
    }

    public void setInquiryTopic(String inquiryTopic) {
        this.inquiryTopic = inquiryTopic;
    }

    public String getTopicDisplay() {
        if (inquiryTopic == null) return "General Inquiry";
        return switch (inquiryTopic.toUpperCase()) {
            case "TICKETING" -> "Ticket Booking & Showtimes";
            case "MEMBERSHIP" -> "Membership & Loyalty";
            case "CINEMA_EXPERIENCE" -> "Cinema & Theater Experience";
            case "REFUND" -> "Refund & Cancellation";
            case "FOOD_BEVERAGE" -> "Popcorn & Drinks Combo";
            case "PARTNERSHIP" -> "Business Partnership";
            default -> inquiryTopic;
        };
    }

    public String getTopicBadgeClass() {
        if (inquiryTopic == null) return "topic-badge-general";
        return switch (inquiryTopic.toUpperCase()) {
            case "TICKETING" -> "topic-badge-ticket";
            case "MEMBERSHIP" -> "topic-badge-member";
            case "CINEMA_EXPERIENCE" -> "topic-badge-experience";
            case "REFUND" -> "topic-badge-refund";
            default -> "topic-badge-general";
        };
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getInquiryStatus() {
        return inquiryStatus;
    }

    public void setInquiryStatus(String inquiryStatus) {
        this.inquiryStatus = inquiryStatus;
    }

    public String getStatusBadgeClass() {
        if (inquiryStatus == null) return "admin-status-pending";
        return switch (inquiryStatus.toUpperCase()) {
            case "RESOLVED" -> "admin-status-active";
            case "IN_PROGRESS" -> "admin-status-info";
            case "CLOSED" -> "admin-status-inactive";
            default -> "admin-status-warning";
        };
    }

    public Long getAssignedTo() {
        return assignedTo;
    }

    public void setAssignedTo(Long assignedTo) {
        this.assignedTo = assignedTo;
    }

    public String getAssignedToName() {
        return assignedToName;
    }

    public void setAssignedToName(String assignedToName) {
        this.assignedToName = assignedToName;
    }

    public String getAdminResponse() {
        return adminResponse;
    }

    public void setAdminResponse(String adminResponse) {
        this.adminResponse = adminResponse;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getRespondedAt() {
        return respondedAt;
    }

    public void setRespondedAt(LocalDateTime respondedAt) {
        this.respondedAt = respondedAt;
    }
}
