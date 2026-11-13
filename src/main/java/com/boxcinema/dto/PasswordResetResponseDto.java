package com.boxcinema.dto;

/**
 * Response DTO for Password Reset API endpoints.
 */
public class PasswordResetResponseDto {

    private boolean success;
    private String message;
    private String errorCode;
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

    public static PasswordResetResponseDto success(String message, int cooldownSeconds) {
        return new PasswordResetResponseDto(true, message, null, cooldownSeconds);
    }

    public static PasswordResetResponseDto success(String message, String resetToken) {
        return new PasswordResetResponseDto(true, message, resetToken);
    }

    public static PasswordResetResponseDto error(String message) {
        return new PasswordResetResponseDto(false, message);
    }

    public static PasswordResetResponseDto errorWithCooldown(String message, int cooldownSeconds) {
        PasswordResetResponseDto dto = new PasswordResetResponseDto(false, message, null, cooldownSeconds);
        dto.setErrorCode("RATE_LIMITED");
        return dto;
    }

    public static PasswordResetResponseDto error(String errorCode, String message) {
        PasswordResetResponseDto dto = new PasswordResetResponseDto(false, message);
        dto.setErrorCode(errorCode);
        return dto;
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

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
}
