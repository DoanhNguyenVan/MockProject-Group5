package com.boxcinema.service;

import com.boxcinema.entity.PasswordReset;
import com.boxcinema.entity.PasswordResetStatus;
import com.boxcinema.entity.User;
import com.boxcinema.repository.PasswordResetRepository;
import com.boxcinema.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Component
public class PasswordResetTxHelper {

    private final UserRepository userRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final OtpService otpService;
    private final ResetTokenService resetTokenService;
    private final PasswordEncoder passwordEncoder;

    public PasswordResetTxHelper(
            UserRepository userRepository,
            PasswordResetRepository passwordResetRepository,
            OtpService otpService,
            ResetTokenService resetTokenService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.otpService = otpService;
        this.resetTokenService = resetTokenService;
        this.passwordEncoder = passwordEncoder;
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
    }

    /**
     * Phase 1 of requesting OTP: Lock user and password_reset, validate limits, create PENDING state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public OtpPreparationResult prepareOtpRequest(String cleanEmail, boolean isResend) {
        LocalDateTime now = LocalDateTime.now();

        // 1. Lock user row
        Optional<User> userOpt = userRepository.findByEmailForUpdate(cleanEmail);
        if (userOpt.isEmpty()) {
            return OtpPreparationResult.ineligible();
        }

        User user = userOpt.get();
        // Check account eligibility (Only ACTIVE accounts)
        if (!"ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
            return OtpPreparationResult.ineligible();
        }

        // 2. Lock PasswordReset row
        Optional<PasswordReset> prOpt = passwordResetRepository.findByIdForUpdate(user.getUserId());
        boolean isNew = prOpt.isEmpty();
        PasswordReset pr = prOpt.orElseGet(() -> new PasswordReset(user.getUserId(), UUID.randomUUID().toString()));

        // Check if locked
        if (pr.getLockedUntil() != null && pr.getLockedUntil().isAfter(now)) {
            long remainingMinutes = Duration.between(now, pr.getLockedUntil()).toMinutes() + 1;
            throw new IllegalArgumentException("Account password reset is temporarily locked due to too many failed attempts. Please try again in " + remainingMinutes + " minute(s).");
        }

        // Check 1-hour rate limit window (max 5 OTP emails per hour per account)
        if (pr.getSendWindowStartedAt() == null || pr.getSendWindowStartedAt().plusHours(1).isBefore(now)) {
            pr.setSendWindowStartedAt(now);
            pr.setSendCountInWindow(1);
        } else {
            if (pr.getSendCountInWindow() >= 5) {
                throw new IllegalArgumentException("Too many password reset requests in the last hour. Please try again later.");
            }
            pr.setSendCountInWindow(pr.getSendCountInWindow() + 1);
        }

        // If resend, check 60s cooldown and max 3 resends
        if (isResend) {
            if (pr.getLastSentAt() != null && pr.getLastSentAt().plusSeconds(60).isAfter(now)) {
                long waitSec = Duration.between(now, pr.getLastSentAt().plusSeconds(60)).getSeconds();
                throw new IllegalArgumentException("Please wait " + waitSec + " second(s) before requesting a new code.");
            }
            if (pr.getResendCount() >= 3) {
                throw new IllegalArgumentException("Maximum resend attempts reached for this session. Please start a new request after your lockout window expires.");
            }
            pr.setResendCount(pr.getResendCount() + 1);
        } else {
            // New request: check cooldown if previous code was sent recently
            if (pr.getLastSentAt() != null && pr.getLastSentAt().plusSeconds(60).isAfter(now)) {
                long waitSec = Duration.between(now, pr.getLastSentAt().plusSeconds(60)).getSeconds();
                throw new IllegalArgumentException("A verification code was recently sent. Please wait " + waitSec + " second(s) before requesting again.");
            }
            pr.setResendCount(0);
        }

        // Avoid overlapping when a pending email is already being dispatched
        if (!isNew && PasswordResetStatus.PENDING == pr.getStatus() && pr.getUpdatedAt() != null && pr.getUpdatedAt().plusMinutes(2).isAfter(now)) {
            throw new IllegalArgumentException("A code delivery request is already in progress. Please check your inbox or wait a moment.");
        }

        // Generate new challenge ID & OTP
        String newChallengeId = UUID.randomUUID().toString();
        String plainOtp = otpService.generate6DigitOtp();
        String otpHmac = otpService.computeOtpHmac(user.getUserId(), newChallengeId, plainOtp);

        pr.setChallengeId(newChallengeId);
        pr.setOtpHash(otpHmac);
        pr.setStatus(PasswordResetStatus.PENDING);
        pr.setFailedAttempts(0);
        pr.setResetTokenHash(null);
        pr.setResetTokenExpiresAt(null);
        pr.setVerifiedAt(null);
        pr.setCompletedAt(null);

        passwordResetRepository.save(pr);

        return new OtpPreparationResult(true, user.getUserId(), user.getEmail(), user.getFullName(), newChallengeId, plainOtp);
    }

    /**
     * Phase 2: After SMTP attempt, finalize state to SENT or DELIVERY_FAILED.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void finalizeOtpSent(Long userId, String challengeId, boolean emailDispatched) {
        LocalDateTime now = LocalDateTime.now();
        Optional<PasswordReset> prOpt = passwordResetRepository.findByIdForUpdate(userId);
        if (prOpt.isEmpty()) {
            return;
        }

        PasswordReset pr = prOpt.get();
        // Only update if challengeId is still the active one
        if (!challengeId.equals(pr.getChallengeId())) {
            return;
        }

        if (emailDispatched) {
            pr.setStatus(PasswordResetStatus.SENT);
            pr.setLastSentAt(now);
            pr.setOtpExpiresAt(now.plusMinutes(5)); // BR-02: 5 minutes expiration
        } else {
            pr.setStatus(PasswordResetStatus.DELIVERY_FAILED);
            pr.setOtpHash(null);
            pr.setOtpExpiresAt(null);
        }
        passwordResetRepository.save(pr);
    }

    /**
     * Verify OTP atomically. Returns raw reset token if correct.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, noRollbackFor = IllegalArgumentException.class)
    public String verifyOtp(String cleanEmail, String enteredOtp) {
        LocalDateTime now = LocalDateTime.now();

        // 1. Lock User
        User user = userRepository.findByEmailForUpdate(cleanEmail)
                .orElseThrow(() -> new IllegalArgumentException("Invalid verification code or request expired."));

        if (!"ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
            throw new IllegalArgumentException("Account is not active.");
        }

        // 2. Lock PasswordReset
        PasswordReset pr = passwordResetRepository.findByIdForUpdate(user.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("No active password reset request found."));

        if (pr.getStatus() != PasswordResetStatus.SENT) {
            if (pr.getStatus() == PasswordResetStatus.LOCKED) {
                throw new IllegalArgumentException("Account password reset is locked due to too many failed attempts.");
            }
            if (pr.getStatus() == PasswordResetStatus.VERIFIED) {
                throw new IllegalArgumentException("This code has already been verified.");
            }
            throw new IllegalArgumentException("Invalid or expired verification request. Please request a new code.");
        }

        // Check expiration
        if (pr.getOtpExpiresAt() == null || pr.getOtpExpiresAt().isBefore(now)) {
            pr.setStatus(PasswordResetStatus.EXPIRED);
            pr.setOtpHash(null);
            passwordResetRepository.save(pr);
            throw new IllegalArgumentException("Verification code has expired. Please request a new one.");
        }

        // Check failed attempts
        if (pr.getFailedAttempts() >= 5) {
            pr.setStatus(PasswordResetStatus.LOCKED);
            pr.setLockedUntil(now.plusMinutes(15));
            pr.setOtpHash(null);
            passwordResetRepository.save(pr);
            throw new IllegalArgumentException("Too many failed attempts. Password reset is locked for 15 minutes.");
        }

        // Compare OTP
        boolean matches = otpService.verifyOtp(user.getUserId(), pr.getChallengeId(), enteredOtp, pr.getOtpHash());
        if (!matches) {
            int newAttempts = pr.getFailedAttempts() + 1;
            pr.setFailedAttempts(newAttempts);
            if (newAttempts >= 5) {
                pr.setStatus(PasswordResetStatus.LOCKED);
                pr.setLockedUntil(now.plusMinutes(15));
                pr.setOtpHash(null);
                passwordResetRepository.save(pr);
                throw new IllegalArgumentException("Too many failed attempts (5/5). Password reset is locked for 15 minutes.");
            } else {
                passwordResetRepository.save(pr);
                throw new IllegalArgumentException("Invalid verification code. Remaining attempts: " + (5 - newAttempts));
            }
        }

        // Success: consume OTP, issue 256-bit reset token
        pr.setStatus(PasswordResetStatus.VERIFIED);
        pr.setVerifiedAt(now);
        pr.setOtpHash(null);
        pr.setFailedAttempts(0);

        String rawResetToken = resetTokenService.generateResetToken();
        pr.setResetTokenHash(resetTokenService.hashToken(rawResetToken));
        pr.setResetTokenExpiresAt(now.plusMinutes(10)); // BR-08: 10 minutes duration

        passwordResetRepository.save(pr);
        return rawResetToken;
    }

    /**
     * Complete password reset atomically.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public User completePasswordReset(String resetToken, String newPassword) {
        LocalDateTime now = LocalDateTime.now();

        String tokenHash = resetTokenService.hashToken(resetToken);
        Long userId = passwordResetRepository.findUserIdByResetTokenHash(tokenHash)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired password reset authorization."));

        // Lock in consistent order: USERS first, then PASSWORD_RESET
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("User account not found."));

        PasswordReset lockedPr = passwordResetRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("Reset request not found."));

        if (lockedPr.getStatus() != PasswordResetStatus.VERIFIED) {
            throw new IllegalArgumentException("Password reset authorization is not in a valid state.");
        }

        if (lockedPr.getResetTokenHash() == null || !tokenHash.equals(lockedPr.getResetTokenHash())) {
            throw new IllegalArgumentException("Password reset authorization has already been used or revoked.");
        }

        if (lockedPr.getResetTokenExpiresAt() == null || lockedPr.getResetTokenExpiresAt().isBefore(now)) {
            lockedPr.setStatus(PasswordResetStatus.EXPIRED);
            lockedPr.setResetTokenHash(null);
            passwordResetRepository.save(lockedPr);
            throw new IllegalArgumentException("Password reset authorization has expired. Please request a new code.");
        }

        if (!"ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
            throw new IllegalArgumentException("Account is not active.");
        }

        // Update user's password
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // Revoke reset authorization and mark completed
        lockedPr.setStatus(PasswordResetStatus.COMPLETED);
        lockedPr.setCompletedAt(now);
        lockedPr.setResetTokenHash(null);
        lockedPr.setResetTokenExpiresAt(null);
        lockedPr.setOtpHash(null);
        passwordResetRepository.save(lockedPr);

        return user;
    }
}
