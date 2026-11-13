package com.boxcinema.dto;

public class GoogleUnlinkRequestDto {

    private String password;

    public GoogleUnlinkRequestDto() {
    }

    public GoogleUnlinkRequestDto(String password) {
        this.password = password;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}

