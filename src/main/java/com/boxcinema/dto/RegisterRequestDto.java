package com.boxcinema.dto;

public class RegisterRequestDto {
    private String fullName;
    private String email;
    private String phone;
    private String password;
    private String confirmPassword;
    private Boolean termsAccepted;

    public RegisterRequestDto() {
    }

    public RegisterRequestDto(String fullName, String email, String phone, String password, String confirmPassword, Boolean termsAccepted) {
        this.fullName = fullName;
        this.email = email;
        this.phone = phone;
        this.password = password;
        this.confirmPassword = confirmPassword;
        this.termsAccepted = termsAccepted;
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

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getConfirmPassword() {
        return confirmPassword;
    }

    public void setConfirmPassword(String confirmPassword) {
        this.confirmPassword = confirmPassword;
    }

    public Boolean getTermsAccepted() {
        return termsAccepted;
    }

    public void setTermsAccepted(Boolean termsAccepted) {
        this.termsAccepted = termsAccepted;
    }
}

