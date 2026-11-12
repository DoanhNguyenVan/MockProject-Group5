package com.boxcinema.dto;

public class AuthMethodsDto {

    private Long userId;
    private String fullName;
    private String email;
    private String phone;
    private String role;
    private String accountStatus;
    private boolean hasPassword;
    private boolean googleLinked;
    private boolean canUnlinkGoogle;
    private boolean setPasswordVerified;

    public AuthMethodsDto() {
    }

    public AuthMethodsDto(
            Long userId,
            String fullName,
            String email,
            String phone,
            String role,
            String accountStatus,
            boolean hasPassword,
            boolean googleLinked,
            boolean canUnlinkGoogle) {
        this.userId = userId;
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.role = role;
        this.accountStatus = accountStatus;
        this.hasPassword = hasPassword;
        this.googleLinked = googleLinked;
        this.canUnlinkGoogle = canUnlinkGoogle;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getAccountStatus() {
        return accountStatus;
    }

    public void setAccountStatus(String accountStatus) {
        this.accountStatus = accountStatus;
    }

    public boolean isHasPassword() {
        return hasPassword;
    }

    public void setHasPassword(boolean hasPassword) {
        this.hasPassword = hasPassword;
    }

    public boolean isGoogleLinked() {
        return googleLinked;
    }

    public void setGoogleLinked(boolean googleLinked) {
        this.googleLinked = googleLinked;
    }

    public boolean isCanUnlinkGoogle() {
        return canUnlinkGoogle;
    }

    public void setCanUnlinkGoogle(boolean canUnlinkGoogle) {
        this.canUnlinkGoogle = canUnlinkGoogle;
    }

    public boolean isSetPasswordVerified() {
        return setPasswordVerified;
    }

    public void setSetPasswordVerified(boolean setPasswordVerified) {
        this.setPasswordVerified = setPasswordVerified;
    }

    public boolean isEmailPasswordEnabled() {
        return hasPassword;
    }

    public boolean isGoogleConnected() {
        return googleLinked;
    }
}

