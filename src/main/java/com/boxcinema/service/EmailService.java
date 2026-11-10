package com.boxcinema.service;

public interface EmailService {
    /**
     * Sends a 6-digit verification OTP to the user's email (Gmail).
     *
     * @param toEmail   recipient email address
     * @param fullName  recipient full name
     * @param otpCode   6-digit OTP verification code
     * @return true if sent via real SMTP server, false if running in local fallback mode
     */
    boolean sendVerificationOtpEmail(String toEmail, String fullName, String otpCode);
}
