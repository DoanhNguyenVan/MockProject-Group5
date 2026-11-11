package com.boxcinema.dto;

public class ResendOtpRequestDto {
    private String email;

    public ResendOtpRequestDto() {
    }

    public ResendOtpRequestDto(String email) {
        this.email = email;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}

