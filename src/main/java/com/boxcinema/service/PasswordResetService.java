package com.boxcinema.service;

import com.boxcinema.dto.PasswordResetResponseDto;
import com.boxcinema.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    // Password must be at least 8 characters long, contain uppercase, lowercase, number, and special character
    private static final Pattern STRONG_PASSWORD_REGEX = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&#^()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?]).{8,}$"
    );

    private final PasswordResetTxHelper txHelper;
    private final EmailService emailService;

    public PasswordResetService(PasswordResetTxHelper txHelper, EmailService emailService) {
        this.txHelper = txHelper;
        this.emailService = emailService;
    }

    /**
     * Flow 1: Request OTP code for password reset.
     */
    public PasswordResetResponseDto requestPasswordReset(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new IllegalArgumentException("Email address is required.");
        }
        String cleanEmail = rawEmail.trim().toLowerCase();

        // 1. Transactional preparation
        PasswordResetTxHelper.OtpPreparationResult prep = txHelper.prepareOtpRequest(cleanEmail, false);

        if (!prep.userEligible) {
            // BR-11: Generic success response so email existence is not leaked
            return PasswordResetResponseDto.success("If an account exists with this email, a verification code has been sent.");
        }

        // 2. Dispatch email outside of DB transaction
        boolean sentOk = false;
        try {
            sentOk = emailService.sendPasswordResetOtpEmail(prep.email, prep.fullName, prep.plainOtp);
        } catch (Exception e) {
            log.warn("Failed to dispatch password reset OTP email to {}: {}", prep.email, e.getMessage());
        }

        // 3. Finalize DB status
        txHelper.finalizeOtpSent(prep.userId, prep.challengeId, sentOk);

        if (!sentOk) {
            throw new IllegalStateException("Failed to send verification code. Please check your email configuration or try again.");
        }

        return PasswordResetResponseDto.success("A 6-digit verification code has been sent to your email. Please enter it to continue.");
    }

    /**
     * Flow 2: Verify OTP code. Returns reset authorization token if valid.
     */
    public PasswordResetResponseDto verifyOtp(String rawEmail, String otp) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new IllegalArgumentException("Email address is required.");
        }
        if (otp == null || otp.isBlank() || !otp.trim().matches("^\\d{6}$")) {
            throw new IllegalArgumentException("Verification code must be exactly 6 numeric digits.");
        }

        String cleanEmail = rawEmail.trim().toLowerCase();
        String resetToken = txHelper.verifyOtp(cleanEmail, otp.trim());

        return PasswordResetResponseDto.success("Code verified successfully.", resetToken);
    }

    /**
     * Flow 3: Resend OTP code.
     */
    public PasswordResetResponseDto resendOtp(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new IllegalArgumentException("Email address is required.");
        }
        String cleanEmail = rawEmail.trim().toLowerCase();

        // 1. Transactional preparation with isResend = true
        PasswordResetTxHelper.OtpPreparationResult prep = txHelper.prepareOtpRequest(cleanEmail, true);

        if (!prep.userEligible) {
            return PasswordResetResponseDto.success("If an account exists with this email, a verification code has been sent.");
        }

        // 2. Dispatch email outside of DB transaction
        boolean sentOk = false;
        try {
            sentOk = emailService.sendPasswordResetOtpEmail(prep.email, prep.fullName, prep.plainOtp);
        } catch (Exception e) {
            log.warn("Failed to resend password reset OTP email to {}: {}", prep.email, e.getMessage());
        }

        // 3. Finalize DB status
        txHelper.finalizeOtpSent(prep.userId, prep.challengeId, sentOk);

        if (!sentOk) {
            throw new IllegalStateException("Failed to resend verification code. Please try again later.");
        }

        return PasswordResetResponseDto.success("A fresh 6-digit verification code has been dispatched to your email.");
    }

    /**
     * Flow 4: Complete password reset with new password.
     */
    public PasswordResetResponseDto completePasswordReset(String resetToken, String newPassword, String confirmPassword) {
        if (resetToken == null || resetToken.isBlank()) {
            throw new IllegalArgumentException("Reset authorization token is missing or invalid.");
        }
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("New password cannot be empty.");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("New password and confirmation password do not match.");
        }
        if (!STRONG_PASSWORD_REGEX.matcher(newPassword).matches()) {
            throw new IllegalArgumentException(
                    "Password must be at least 8 characters long and contain at least one uppercase letter, one lowercase letter, one number, and one special character."
            );
        }

        User updatedUser = txHelper.completePasswordReset(resetToken.trim(), newPassword);

        // Send confirmation email asynchronously / outside transaction
        try {
            emailService.sendPasswordResetSuccessEmail(updatedUser.getEmail(), updatedUser.getFullName());
        } catch (Exception e) {
            log.warn("Could not dispatch password reset success notification: {}", e.getMessage());
        }

        return PasswordResetResponseDto.success("Your password has been reset successfully. Please sign in with your new password.");
    }
}

