package com.boxcinema.dto;

public class EmailVerificationResendDto {

    private String challengeId;
    private String email;

    public EmailVerificationResendDto() {
    }

    public EmailVerificationResendDto(String challengeId, String email) {
        this.challengeId = challengeId;
        this.email = email;
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
}

