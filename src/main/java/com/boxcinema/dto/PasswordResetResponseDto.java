package com.boxcinema.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PasswordResetResponseDto {
    private boolean success;
    private String message;
    private String resetToken;
    private Integer cooldownSeconds;

    public PasswordResetResponseDto() {
    }

    public PasswordResetResponseDto(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public PasswordResetResponseDto(boolean success, String message, String resetToken) {
        this.success = success;
        this.message = message;
        this.resetToken = resetToken;
    }

    public PasswordResetResponseDto(boolean success, String message, String resetToken, Integer cooldownSeconds) {
        this.success = success;
        this.message = message;
        this.resetToken = resetToken;
        this.cooldownSeconds = cooldownSeconds;
    }

    public static PasswordResetResponseDto success(String message) {
        return new PasswordResetResponseDto(true, message);
    }

    public static PasswordResetResponseDto success(String message, String resetToken) {
        return new PasswordResetResponseDto(true, message, resetToken);
    }

    public static PasswordResetResponseDto error(String message) {
        return new PasswordResetResponseDto(false, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getResetToken() {
        return resetToken;
    }

    public void setResetToken(String resetToken) {
        this.resetToken = resetToken;
    }

    public Integer getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(Integer cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }
}

