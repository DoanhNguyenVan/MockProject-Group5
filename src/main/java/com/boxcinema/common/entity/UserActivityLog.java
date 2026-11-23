package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "user_activity_logs", indexes = {
        @Index(name = "idx_user_activity", columnList = "user_id, created_at"),
        @Index(name = "idx_created_at", columnList = "created_at"),
        @Index(name = "idx_role_action", columnList = "role, action_type")
})
public class UserActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "email", length = 255)
    private String email;

    @Column(name = "role", length = 20, nullable = false)
    private String role = "GUEST";

    @Column(name = "action_type", length = 50, nullable = false)
    private String actionType; // LOGIN, LOGOUT, PAGE_VIEW

    @Column(name = "page_url", length = 255, nullable = false)
    private String pageUrl;

    @Column(name = "page_title", length = 150)
    private String pageTitle;

    @Column(name = "ip_address", length = 45, nullable = false)
    private String ipAddress;

    @Column(name = "location", length = 100)
    private String location;

    @Column(name = "device_info", length = 255)
    private String deviceInfo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public UserActivityLog() {
    }

    public UserActivityLog(Long userId, String email, String role, String actionType,
                           String pageUrl, String pageTitle, String ipAddress,
                           String location, String deviceInfo) {
        this.userId = userId;
        this.email = email;
        this.role = (role != null && !role.isBlank()) ? role : "GUEST";
        this.actionType = actionType;
        this.pageUrl = pageUrl;
        this.pageTitle = pageTitle;
        this.ipAddress = ipAddress;
        this.location = location;
        this.deviceInfo = deviceInfo;
        this.createdAt = LocalDateTime.now();
    }

    public Long getLogId() {
        return logId;
    }

    public void setLogId(Long logId) {
        this.logId = logId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public String getPageTitle() {
        return pageTitle;
    }

    public void setPageTitle(String pageTitle) {
        this.pageTitle = pageTitle;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getDeviceInfo() {
        return deviceInfo;
    }

    public void setDeviceInfo(String deviceInfo) {
        this.deviceInfo = deviceInfo;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
