package com.boxcinema.service;

public interface EmailService {
    /**
     * Sends a 6-digit verification OTP to the user's email (Gmail).
     *
     * @param toEmail  recipient email address
     * @param fullName recipient full name
     * @param otpCode  6-digit OTP verification code
     * @return true if sent via real SMTP server, false if running in local fallback
     *         mode
     */
    boolean sendVerificationOtpEmail(String toEmail, String fullName, String otpCode);

    /**
     * Sends a confirmation email to customer after submitting a contact/support
     * inquiry.
     *
     * @param toEmail     customer email address
     * @param fullName    customer full name
     * @param inquiryId   inquiry reference ticket ID
     * @param topic       inquiry topic
     * @param userMessage customer message content
     * @return true if sent via real SMTP server, false if running in local fallback
     *         mode
     */
    default boolean sendInquiryConfirmationEmail(String toEmail, String fullName, Long inquiryId, String topic,
            String userMessage) {
        return false;
    }

    /**
     * Dispatches the inquiry confirmation email asynchronously on the background
     * mail executor
     * so HTTP responses return immediately after DB persistence.
     */
    default void sendInquiryConfirmationEmailAsync(String toEmail, String fullName, Long inquiryId, String topic,
            String userMessage) {
        sendInquiryConfirmationEmail(toEmail, fullName, inquiryId, topic, userMessage);
    }

    /**
     * Sends a security notification email confirming that the user's password was
     * reset.
     *
     * @param toEmail  customer email address
     * @param fullName customer full name
     * @return true if sent via real SMTP server, false if running in local fallback
     *         mode
     */
    default boolean sendPasswordResetConfirmationEmail(String toEmail, String fullName) {
        return false;
    }

    /**
     * Dispatches the password reset confirmation email asynchronously on the
     * background mail executor.
     */
    default void sendPasswordResetConfirmationEmailAsync(String toEmail, String fullName) {
        sendPasswordResetConfirmationEmail(toEmail, fullName);
    }
}
