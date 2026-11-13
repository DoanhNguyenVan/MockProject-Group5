package com.boxcinema.dto;

public class ReauthRequestDto {

    private String password;

    public ReauthRequestDto() {
    }

    public ReauthRequestDto(String password) {
        this.password = password;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}

