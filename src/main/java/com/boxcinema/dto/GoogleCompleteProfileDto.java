package com.boxcinema.dto;

public class GoogleCompleteProfileDto {
    private String phone;

    public GoogleCompleteProfileDto() {
    }

    public GoogleCompleteProfileDto(String phone) {
        this.phone = phone;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}

