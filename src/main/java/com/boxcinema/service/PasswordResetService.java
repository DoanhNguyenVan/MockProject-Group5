package com.boxcinema.service;

import com.boxcinema.dto.PasswordResetResponseDto;
import com.boxcinema.entity.User;
import com.boxcinema.service.PasswordResetTxHelper.OtpPreparationResult;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class PasswordResetService {

    private static final String GENERIC_OTP_SENT_MESSAGE =
            "If an account exists with this email, a verification code has been sent.";
    private static final Pattern OTP_REGEX = Pattern.compile("^\\d{6}$");

    private final PasswordResetTxHelper txHelper;
    private final EmailService emailService;
    private final PasswordPolicyService passwordPolicyService;
    private final AuthAuditService authAuditService;

    @Autowired
    public PasswordResetService(
            PasswordResetTxHelper txHelper,
            EmailService emailService,
            PasswordPolicyService passwordPolicyService,
            ObjectProvider<AuthAuditService> authAuditServiceProvider) {
        this.txHelper = txHelper;
        this.emailService = emailService;
        this.passwordPolicyService = passwordPolicyService;
        this.authAuditService = authAuditServiceProvider != null
                ? authAuditServiceProvider.getIfAvailable(AuthAuditService::new)
                : new AuthAuditService();
    }

    public PasswordResetService(PasswordResetTxHelper txHelper, EmailService emailService) {
        this.txHelper = txHelper;
        this.emailService = emailService;
        this.passwordPolicyService = new PasswordPolicyService();
        this.authAuditService = new AuthAuditService();
    }

    /**
     * Step 1: Request Forgot Password OTP via Gmail SMTP.
     */
    public PasswordResetResponseDto requestPasswordReset(String rawEmail) {
        String cleanEmail = normalizeAndValidateEmail(rawEmail);

        OtpPreparationResult prep = txHelper.prepareOtpRequest(cleanEmail, false);
        int cooldown = txHelper.getResendCooldownSeconds();
        if (!prep.userEligible) {
            authAuditService.logEvent("PASSWORD_RESET_REQUESTED", null, cleanEmail, "IGNORED", "Ineligible or unknown account");
            return PasswordResetResponseDto.success(GENERIC_OTP_SENT_MESSAGE, cooldown);
        }

        boolean emailSent = false;
        try {
            emailSent = emailService.sendPasswordResetOtpEmail(prep.email, prep.fullName, prep.plainOtp);
        } finally {
            txHelper.finalizeOtpSent(prep.userId, prep.challengeId, emailSent);
        }

        if (!emailSent) {
            authAuditService.logEvent("PASSWORD_RESET_REQUESTED", prep.userId, cleanEmail, "FAILED", "SMTP delivery unavailable");
        } else {
            authAuditService.logEvent("PASSWORD_RESET_REQUESTED", prep.userId, cleanEmail, "SENT", "OTP email dispatched");
        }

        return PasswordResetResponseDto.success(GENERIC_OTP_SENT_MESSAGE, cooldown);
    }

    /**
     * Step 2: Verify 6-digit OTP and issue temporary 256-bit reset token.
     */
    public PasswordResetResponseDto verifyOtp(String rawEmail, String otp) {
        String cleanEmail = normalizeAndValidateEmail(rawEmail);
        if (otp == null || !OTP_REGEX.matcher(otp.trim()).matches()) {
            throw new IllegalArgumentException("Verification code must be exactly 6 numeric digits.");
        }

        try {
            String resetToken = txHelper.verifyOtp(cleanEmail, otp.trim());
            authAuditService.logEvent("PASSWORD_RESET_REQUESTED", null, cleanEmail, "OTP_VERIFIED", "OTP verified");
            return PasswordResetResponseDto.success(
                    "Code verified successfully. Please set your new password.",
                    resetToken
            );
        } catch (IllegalArgumentException | IllegalStateException ex) {
            authAuditService.logEvent("PASSWORD_RESET_REQUESTED", null, cleanEmail, "OTP_FAILED", ex.getMessage());
            throw ex;
        }
    }

    /**
     * Resend 6-digit OTP subject to cooldown, max resends, and hourly rate limit.
     */
    public PasswordResetResponseDto resendOtp(String rawEmail) {
        String cleanEmail = normalizeAndValidateEmail(rawEmail);

        OtpPreparationResult prep = txHelper.prepareOtpRequest(cleanEmail, true);
        int cooldown = txHelper.getResendCooldownSeconds();
        if (!prep.userEligible) {
            authAuditService.logEvent("PASSWORD_RESET_REQUESTED", null, cleanEmail, "RESEND_IGNORED", "Ineligible or unknown account");
            return PasswordResetResponseDto.success(GENERIC_OTP_SENT_MESSAGE, cooldown);
        }

        boolean emailSent = false;
        try {
            emailSent = emailService.sendPasswordResetOtpEmail(prep.email, prep.fullName, prep.plainOtp);
        } finally {
            txHelper.finalizeOtpSent(prep.userId, prep.challengeId, emailSent);
        }

        if (!emailSent) {
            authAuditService.logEvent("PASSWORD_RESET_REQUESTED", prep.userId, cleanEmail, "RESEND_FAILED", "SMTP delivery unavailable");
            throw new IllegalStateException("Failed to resend verification code. Please try again later.");
        }

        authAuditService.logEvent("PASSWORD_RESET_REQUESTED", prep.userId, cleanEmail, "RESENT", "OTP resent");
        return PasswordResetResponseDto.success(
                "A new verification code has been sent to your email.",
                cooldown
        );
    }

    /**
     * Step 3: Validate strong password, update hash atomically, invalidate active sessions, and notify via Gmail.
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
        passwordPolicyService.validatePasswordStrength(newPassword);

        User completedUser = txHelper.completePasswordReset(resetToken.trim(), newPassword);
        authAuditService.logEvent("PASSWORD_RESET_SUCCESS", completedUser.getUserId(), completedUser.getEmail(), "SUCCESS", "Password updated and sessions revoked");

        try {
            emailService.sendPasswordResetSuccessEmail(completedUser.getEmail(), completedUser.getFullName());
        } catch (Exception ignored) {
            // Best-effort confirmation email; password update is already committed
        }

        return PasswordResetResponseDto.success("Your password has been successfully updated! You can now log in with your new password.");
    }

    private String normalizeAndValidateEmail(String rawEmail) {
        if (rawEmail == null || rawEmail.isBlank()) {
            throw new IllegalArgumentException("Email address is required.");
        }
        return passwordPolicyService.validateAndNormalizeEmail(rawEmail);
    }
}
