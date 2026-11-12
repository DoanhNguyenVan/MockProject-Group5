package com.boxcinema.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalized OTP challenge and rate-limit configuration (Specification V1.2 Section C3).
 */
@Component
@ConfigurationProperties(prefix = "app.otp")
public class OtpProperties {

    private int length = 6;
    private int expirationSeconds = 300;
    private int resendCooldownSeconds = 60;
    private int maxFailedAttempts = 5;
    private int maxResendsPerChallenge = 3;
    private int maxSendsPerEmailPerHour = 10;
    private int maxSendsPerIpPerHour = 30;
    private int lockDurationMinutes = 15;
    private int resetTokenExpirationMinutes = 10;

    public int getLength() {
        return length;
    }

    public void setLength(int length) {
        this.length = length;
    }

    public int getExpirationSeconds() {
        return expirationSeconds;
    }

    public void setExpirationSeconds(int expirationSeconds) {
        this.expirationSeconds = expirationSeconds;
    }

    public int getResendCooldownSeconds() {
        return resendCooldownSeconds;
    }

    public void setResendCooldownSeconds(int resendCooldownSeconds) {
        this.resendCooldownSeconds = resendCooldownSeconds;
    }

    public int getMaxFailedAttempts() {
        return maxFailedAttempts;
    }

    public void setMaxFailedAttempts(int maxFailedAttempts) {
        this.maxFailedAttempts = maxFailedAttempts;
    }

    public int getMaxResendsPerChallenge() {
        return maxResendsPerChallenge;
    }

    public void setMaxResendsPerChallenge(int maxResendsPerChallenge) {
        this.maxResendsPerChallenge = maxResendsPerChallenge;
    }

    public int getMaxSendsPerEmailPerHour() {
        return maxSendsPerEmailPerHour;
    }

    public void setMaxSendsPerEmailPerHour(int maxSendsPerEmailPerHour) {
        this.maxSendsPerEmailPerHour = maxSendsPerEmailPerHour;
    }

    public int getMaxSendsPerIpPerHour() {
        return maxSendsPerIpPerHour;
    }

    public void setMaxSendsPerIpPerHour(int maxSendsPerIpPerHour) {
        this.maxSendsPerIpPerHour = maxSendsPerIpPerHour;
    }

    public int getLockDurationMinutes() {
        return lockDurationMinutes;
    }

    public void setLockDurationMinutes(int lockDurationMinutes) {
        this.lockDurationMinutes = lockDurationMinutes;
    }

    public int getResetTokenExpirationMinutes() {
        return resetTokenExpirationMinutes;
    }

    public void setResetTokenExpirationMinutes(int resetTokenExpirationMinutes) {
        this.resetTokenExpirationMinutes = resetTokenExpirationMinutes;
    }
}

