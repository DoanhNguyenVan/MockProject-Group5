package com.boxcinema.dto;

public class EmailVerificationVerifyDto {

    private String challengeId;
    private String email;
    private String otp;

    public EmailVerificationVerifyDto() {
    }

    public EmailVerificationVerifyDto(String challengeId, String email, String otp) {
        this.challengeId = challengeId;
        this.email = email;
        this.otp = otp;
    }

    public String getChallengeId() {
        return challengeId;
    }

    public void setChallengeId(String challengeId) {
        this.challengeId = challengeId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getOtp() {
        return otp;
    }

    public void setOtp(String otp) {
        this.otp = otp;
    }
}

