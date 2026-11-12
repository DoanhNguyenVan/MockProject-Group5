package com.boxcinema.service;

import com.boxcinema.entity.OtpPurpose;
import com.boxcinema.entity.PasswordReset;
import com.boxcinema.entity.PasswordResetStatus;
import com.boxcinema.entity.User;
import com.boxcinema.repository.PasswordResetRepository;
import com.boxcinema.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class PasswordResetTxHelper {

    private final UserRepository userRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final OtpChallengeTxHelper otpChallengeTxHelper;
    private final ResetTokenService resetTokenService;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final SessionManagementService sessionManagementService;

    @Autowired
    public PasswordResetTxHelper(
            UserRepository userRepository,
            PasswordResetRepository passwordResetRepository,
            OtpChallengeTxHelper otpChallengeTxHelper,
            ResetTokenService resetTokenService,
            PasswordEncoder passwordEncoder,
            LoginAttemptService loginAttemptService,
            SessionManagementService sessionManagementService) {
        this.userRepository = userRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.otpChallengeTxHelper = otpChallengeTxHelper;
        this.resetTokenService = resetTokenService;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
        this.sessionManagementService = sessionManagementService;
    }

    public static class OtpPreparationResult {
        public final boolean userEligible;
        public final Long userId;
        public final String email;
        public final String fullName;
        public final String challengeId;
        public final String plainOtp;

        public OtpPreparationResult(boolean userEligible, Long userId, String email, String fullName, String challengeId, String plainOtp) {
            this.userEligible = userEligible;
            this.userId = userId;
            this.email = email;
            this.fullName = fullName;
            this.challengeId = challengeId;
            this.plainOtp = plainOtp;
        }

        public static OtpPreparationResult ineligible() {
            return new OtpPreparationResult(false, null, null, null, null, null);
        }

        public static OtpPreparationResult from(OtpChallengeTxHelper.PreparationResult r) {
            return new OtpPreparationResult(r.userEligible(), r.userId(), r.email(), r.fullName(), r.challengeId(), r.plainOtp());
        }
    }

    /**
     * Phase 1 of requesting OTP: Lock user and password_reset (purpose=PASSWORD_RESET).
     * Excludes inactive accounts and Google-only accounts without a BOX Cinema password.
     */
    public OtpPreparationResult prepareOtpRequest(String cleanEmail, boolean isResend) {
        OtpChallengeTxHelper.PreparationResult res = otpChallengeTxHelper.prepareChallenge(
                cleanEmail,
                OtpPurpose.PASSWORD_RESET,
                isResend,
                this::isEligibleForPasswordReset
        );
        return OtpPreparationResult.from(res);
    }

    public int getResendCooldownSeconds() {
        return otpChallengeTxHelper.getResendCooldownSeconds();
    }

    /**
     * Phase 2 after Gmail SMTP attempt: Atomically finalize SENT or DELIVERY_FAILED.
     */
    public void finalizeOtpSent(Long userId, String challengeId, boolean emailDispatched) {
        otpChallengeTxHelper.finalizeOtpSent(userId, OtpPurpose.PASSWORD_RESET, challengeId, emailDispatched);
    }

    /**
     * Phase 2 (Verify OTP): Row-locked validation of HMAC OTP + 256-bit reset token minting.
     */
    public String verifyOtp(String cleanEmail, String enteredOtp) {
        OtpChallengeTxHelper.VerificationResult result = otpChallengeTxHelper.verifyOtpChallenge(
                cleanEmail,
                OtpPurpose.PASSWORD_RESET,
                null,
                enteredOtp,
                this::isEligibleForPasswordReset,
                true,
                false
        );
        return result.resetToken();
    }

    /**
     * Phase 3 (Complete Password Reset):
     * Atomically verifies SHA-256 token, updates BCrypt password, consumes challenge,
     * clears login lockout counters, and revokes active user sessions.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, noRollbackFor = IllegalArgumentException.class)
    public User completePasswordReset(String resetToken, String newPassword) {
        String tokenHash = resetTokenService.hashToken(resetToken);
        Long userId = passwordResetRepository.findUserIdByResetTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired password reset authorization."));

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("User account no longer exists."));

        PasswordReset pr = passwordResetRepository.findByUserIdAndPurposeForUpdate(userId, OtpPurpose.PASSWORD_RESET)
                .orElseThrow(() -> new IllegalArgumentException("Password reset session not found."));

        if (pr.getStatus() != PasswordResetStatus.VERIFIED) {
            throw new IllegalArgumentException("Password reset authorization is not in a valid state.");
        }

        if (pr.getResetTokenHash() == null || !resetTokenService.verifyToken(resetToken, pr.getResetTokenHash())) {
            throw new IllegalArgumentException("Password reset authorization has already been used or revoked.");
        }

        LocalDateTime now = LocalDateTime.now();
        if (pr.getResetTokenExpiresAt() == null || !pr.getResetTokenExpiresAt().isAfter(now)) {
            pr.setStatus(PasswordResetStatus.EXPIRED);
            pr.setResetTokenHash(null);
            pr.setResetTokenExpiresAt(null);
            passwordResetRepository.save(pr);
            throw new IllegalArgumentException("Password reset authorization has expired. Please request a new code.");
        }

        if (!"ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
            throw new IllegalArgumentException("Account is not active.");
        }

        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new IllegalArgumentException("Password reset is not available for Google-only accounts.");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        pr.setStatus(PasswordResetStatus.COMPLETED);
        pr.setCompletedAt(now);
        pr.setResetTokenHash(null);
        pr.setResetTokenExpiresAt(null);
        passwordResetRepository.save(pr);

        loginAttemptService.clearAllAttemptsForEmail(user.getEmail());
        sessionManagementService.invalidateUserSessions(user.getUserId());

        return user;
    }

    private boolean isEligibleForPasswordReset(User user) {
        return user != null
                && "ACTIVE".equalsIgnoreCase(user.getAccountStatus())
                && user.getPasswordHash() != null
                && !user.getPasswordHash().isBlank();
    }
}
