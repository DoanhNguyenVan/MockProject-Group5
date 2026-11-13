package com.boxcinema.service;

import com.boxcinema.config.OtpProperties;
import com.boxcinema.entity.OtpPurpose;
import com.boxcinema.entity.PasswordReset;
import com.boxcinema.entity.PasswordResetStatus;
import com.boxcinema.entity.User;
import com.boxcinema.repository.PasswordResetRepository;
import com.boxcinema.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Shared transactional OTP challenge engine partitioned by (user_id, purpose).
 * Reads policy limits from {@link OtpProperties}, locks user OTP rows consistently,
 * preserves failed-attempt counts across resends, and enforces E.164 phone uniqueness.
 */
@Component
public class OtpChallengeTxHelper {

    public static final int OTP_TTL_MINUTES = 5;
    public static final int RESEND_COOLDOWN_SECONDS = 60;
    public static final int MAX_RESENDS = 3;
    public static final int MAX_HOURLY_REQUESTS = 10;
    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final int LOCK_DURATION_MINUTES = 15;
    public static final int RESET_TOKEN_TTL_MINUTES = 10;

    public static class OtpCooldownException extends IllegalArgumentException {
        private final int cooldownSeconds;

        public OtpCooldownException(String message, int cooldownSeconds) {
            super(message);
            this.cooldownSeconds = Math.max(1, cooldownSeconds);
        }

        public int getCooldownSeconds() {
            return cooldownSeconds;
        }
    }

    public static class PendingVerificationException extends IllegalStateException {
        private final String email;
        private final String challengeId;

        public PendingVerificationException(String email, String challengeId, String message) {
            super(message);
            this.email = email;
            this.challengeId = challengeId;
        }

        public String getEmail() {
            return email;
        }

        public String getChallengeId() {
            return challengeId;
        }
    }

    public record PreparationResult(
            boolean userEligible,
            Long userId,
            String email,
            String fullName,
            String challengeId,
            String plainOtp
    ) {
        public static PreparationResult ineligible() {
            return new PreparationResult(false, null, null, null, null, null);
        }
    }

    public record VerificationResult(
            Long userId,
            String email,
            String fullName,
            String resetToken
    ) {
    }

    private final UserRepository userRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final OtpService otpService;
    private final ResetTokenService resetTokenService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AuthRateLimitService authRateLimitService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OtpProperties otpProperties = new OtpProperties();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PasswordPolicyService passwordPolicyService = new PasswordPolicyService();

    public OtpChallengeTxHelper(
            UserRepository userRepository,
            PasswordResetRepository passwordResetRepository,
            OtpService otpService,
            ResetTokenService resetTokenService) {
        this.userRepository = userRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.otpService = otpService;
        this.resetTokenService = resetTokenService;
    }

    public void setAuthRateLimitService(AuthRateLimitService authRateLimitService) {
        this.authRateLimitService = authRateLimitService;
    }

    public void setOtpProperties(OtpProperties otpProperties) {
        this.otpProperties = (otpProperties != null) ? otpProperties : new OtpProperties();
    }

    public void setPasswordPolicyService(PasswordPolicyService passwordPolicyService) {
        this.passwordPolicyService = (passwordPolicyService != null) ? passwordPolicyService : new PasswordPolicyService();
    }

    public int getOtpExpirationSeconds() {
        return (otpProperties != null && otpProperties.getExpirationSeconds() > 0)
                ? otpProperties.getExpirationSeconds()
                : OTP_TTL_MINUTES * 60;
    }

    public int getResendCooldownSeconds() {
        return (otpProperties != null && otpProperties.getResendCooldownSeconds() > 0)
                ? otpProperties.getResendCooldownSeconds()
                : RESEND_COOLDOWN_SECONDS;
    }

    public int getMaxResends() {
        return (otpProperties != null && otpProperties.getMaxResendsPerChallenge() > 0)
                ? otpProperties.getMaxResendsPerChallenge()
                : MAX_RESENDS;
    }

    public int getMaxHourlyRequests() {
        return (otpProperties != null && otpProperties.getMaxSendsPerEmailPerHour() > 0)
                ? otpProperties.getMaxSendsPerEmailPerHour()
                : MAX_HOURLY_REQUESTS;
    }

    public int getMaxFailedAttempts() {
        return (otpProperties != null && otpProperties.getMaxFailedAttempts() > 0)
                ? otpProperties.getMaxFailedAttempts()
                : MAX_FAILED_ATTEMPTS;
    }

    public int getLockDurationMinutes() {
        return (otpProperties != null && otpProperties.getLockDurationMinutes() > 0)
                ? otpProperties.getLockDurationMinutes()
                : LOCK_DURATION_MINUTES;
    }

    public int getResetTokenTtlMinutes() {
        return (otpProperties != null && otpProperties.getResetTokenExpirationMinutes() > 0)
                ? otpProperties.getResetTokenExpirationMinutes()
                : RESET_TOKEN_TTL_MINUTES;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public PreparationResult prepareChallenge(
            String cleanEmail,
            OtpPurpose purpose,
            boolean isResend,
            Predicate<User> eligibilityPredicate) {
        Optional<User> userOpt = userRepository.findByEmailForUpdate(cleanEmail);
        if (userOpt.isEmpty()) {
            return PreparationResult.ineligible();
        }
        User user = userOpt.get();
        if (!eligibilityPredicate.test(user)) {
            return PreparationResult.ineligible();
        }
        return issueChallengeForLockedUser(user, purpose, isResend);
    }

    @Transactional(propagation = Propagation.MANDATORY, isolation = Isolation.READ_COMMITTED)
    public PreparationResult issueChallengeForLockedUser(User user, OtpPurpose purpose, boolean isResend) {
        LocalDateTime now = LocalDateTime.now();
        // Lock all challenge rows for this user first to guarantee deadlock-free cross-purpose revocation
        List<PasswordReset> lockedRows = passwordResetRepository.findAllByUserIdForUpdate(user.getUserId());
        Optional<PasswordReset> prOpt = lockedRows.stream()
                .filter(row -> row.getPurpose() == purpose)
                .findFirst();

        String flowLabel = (purpose == OtpPurpose.EMAIL_VERIFICATION) ? "email verification" : "password reset";
        String requestNoun = (purpose == OtpPurpose.EMAIL_VERIFICATION) ? "verification" : "password reset";

        if (isResend && prOpt.isEmpty()) {
            throw new IllegalArgumentException("No active " + flowLabel + " request found. Please start a new request.");
        }

        PasswordReset pr = prOpt.orElseGet(() -> {
            PasswordReset created = new PasswordReset(user.getUserId(), purpose, UUID.randomUUID().toString());
            created.setSendWindowStartedAt(now);
            created.setSendCountInWindow(0);
            created.setResendCount(0);
            created.setFailedAttempts(0);
            return created;
        });

        boolean preserveFailedAttempts = isResend;
        if (prOpt.isPresent()) {
            if (pr.getStatus() == PasswordResetStatus.LOCKED) {
                if (pr.getLockedUntil() != null && pr.getLockedUntil().isAfter(now)) {
                    long remainingMinutes = Math.max(1, Duration.between(now, pr.getLockedUntil()).toMinutes() + 1);
                    throw new IllegalArgumentException(
                            "Account " + flowLabel + " is temporarily locked due to too many failed attempts. Please try again in " + remainingMinutes + " minute(s)."
                    );
                }
                pr.setFailedAttempts(0);
                pr.setLockedUntil(null);
                pr.setStatus(PasswordResetStatus.EXPIRED);
                preserveFailedAttempts = false;
            }

            if (isResend) {
                if (pr.getFailedAttempts() >= getMaxFailedAttempts()) {
                    pr.setStatus(PasswordResetStatus.LOCKED);
                    pr.setLockedUntil(now.plusMinutes(getLockDurationMinutes()));
                    pr.setOtpHash(null);
                    passwordResetRepository.save(pr);
                    throw new IllegalArgumentException(
                            "Account " + flowLabel + " is temporarily locked due to too many failed attempts."
                    );
                }
                if (pr.getStatus() == PasswordResetStatus.COMPLETED) {
                    throw new IllegalArgumentException("This " + flowLabel + " code has already been used. Please start a new request.");
                }
                if (pr.getStatus() == PasswordResetStatus.VERIFIED) {
                    throw new IllegalArgumentException("This code has already been verified. Please continue to the next step.");
                }
            } else {
                boolean activeUnverified = (pr.getStatus() == PasswordResetStatus.PENDING || pr.getStatus() == PasswordResetStatus.SENT)
                        && pr.getOtpExpiresAt() != null
                        && pr.getOtpExpiresAt().isAfter(now);
                preserveFailedAttempts = activeUnverified && pr.getFailedAttempts() > 0;
            }

            if (pr.getStatus() == PasswordResetStatus.PENDING
                    && pr.getUpdatedAt() != null
                    && pr.getUpdatedAt().plusSeconds(15).isAfter(now)) {
                throw new IllegalArgumentException("A code delivery request is already in progress. Please check your inbox or wait a moment.");
            }

            boolean windowExpired = pr.getSendWindowStartedAt() == null || !pr.getSendWindowStartedAt().plusHours(1).isAfter(now);
            if (windowExpired) {
                pr.setSendWindowStartedAt(now);
                pr.setSendCountInWindow(0);
                if (!isResend) {
                    pr.setResendCount(0);
                }
            } else if (pr.getSendCountInWindow() >= getMaxHourlyRequests()) {
                throw new IllegalArgumentException("Too many " + requestNoun + " requests in the last hour. Please try again later.");
            }

            int cooldownSeconds = getResendCooldownSeconds();
            if (pr.getLastSentAt() != null && pr.getLastSentAt().plusSeconds(cooldownSeconds).isAfter(now)) {
                int waitSec = Math.max(1, (int) Duration.between(now, pr.getLastSentAt().plusSeconds(cooldownSeconds)).getSeconds() + 1);
                String msg = isResend
                        ? "Please wait " + waitSec + " second(s) before requesting a new code."
                        : "A verification code was recently sent. Please wait " + waitSec + " second(s) before requesting again.";
                throw new OtpCooldownException(msg, waitSec);
            }

            if (isResend) {
                if (pr.getResendCount() >= getMaxResends()) {
                    throw new IllegalArgumentException("Maximum resend attempts reached for this session. Please start a new request after your lockout window expires.");
                }
                pr.setResendCount(pr.getResendCount() + 1);
            } else if (pr.getStatus() == PasswordResetStatus.COMPLETED
                    || pr.getStatus() == PasswordResetStatus.REVOKED
                    || pr.getStatus() == PasswordResetStatus.EXPIRED) {
                pr.setResendCount(0);
            }
        }

        if (authRateLimitService != null) {
            authRateLimitService.checkOtpEmailHourlyLimit(user.getEmail());
        }

        revokeCompetingPurposeChallenges(lockedRows, purpose);

        String plainOtp = otpService.generate6DigitOtp();
        String challengeId = UUID.randomUUID().toString();
        String otpHmac = otpService.computeOtpHmac(user.getUserId(), purpose, challengeId, plainOtp);

        pr.setChallengeId(challengeId);
        pr.setOtpHash(otpHmac);
        pr.setOtpExpiresAt(now.plusSeconds(getOtpExpirationSeconds()));
        pr.setStatus(PasswordResetStatus.PENDING);
        if (!preserveFailedAttempts) {
            pr.setFailedAttempts(0);
        }
        pr.setSendCountInWindow(pr.getSendCountInWindow() + 1);
        if (pr.getSendWindowStartedAt() == null) {
            pr.setSendWindowStartedAt(now);
        }
        pr.setVerifiedAt(null);
        pr.setCompletedAt(null);
        pr.setResetTokenHash(null);
        pr.setResetTokenExpiresAt(null);

        passwordResetRepository.saveAndFlush(pr);

        return new PreparationResult(
                true,
                user.getUserId(),
                user.getEmail(),
                user.getFullName(),
                challengeId,
                plainOtp
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void finalizeOtpSent(Long userId, OtpPurpose purpose, String challengeId, boolean emailDispatched) {
        Optional<PasswordReset> prOpt = passwordResetRepository.findByUserIdAndPurposeForUpdate(userId, purpose);
        if (prOpt.isEmpty()) {
            return;
        }
        PasswordReset pr = prOpt.get();
        if (!challengeId.equals(pr.getChallengeId()) || pr.getStatus() != PasswordResetStatus.PENDING) {
            return;
        }

        if (emailDispatched) {
            pr.setStatus(PasswordResetStatus.SENT);
            pr.setLastSentAt(LocalDateTime.now());
        } else {
            pr.setStatus(PasswordResetStatus.DELIVERY_FAILED);
            pr.setLastSentAt(null);
        }
        passwordResetRepository.save(pr);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, noRollbackFor = IllegalArgumentException.class)
    public VerificationResult verifyOtpChallenge(
            String cleanEmail,
            OtpPurpose purpose,
            String expectedChallengeId,
            String enteredOtp,
            Predicate<User> accountEligibilityCheck,
            boolean issueResetToken,
            boolean activateUserOnSuccess) {
        User user = userRepository.findByEmailForUpdate(cleanEmail)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired verification request. Please request a new code."));

        if (!accountEligibilityCheck.test(user)) {
            if (purpose == OtpPurpose.EMAIL_VERIFICATION && "ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
                throw new IllegalArgumentException("This account is already verified. Please sign in.");
            }
            throw new IllegalArgumentException("Account is not active.");
        }

        PasswordReset pr = passwordResetRepository.findByUserIdAndPurposeForUpdate(user.getUserId(), purpose)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired verification request. Please request a new code."));

        if (expectedChallengeId != null && !expectedChallengeId.isBlank()
                && !expectedChallengeId.trim().equals(pr.getChallengeId())) {
            throw new IllegalArgumentException("Verification challenge is no longer active. Please use your latest code or request a new one.");
        }

        LocalDateTime now = LocalDateTime.now();
        String flowTitle = (purpose == OtpPurpose.EMAIL_VERIFICATION) ? "Email verification" : "Password reset";
        String flowLower = (purpose == OtpPurpose.EMAIL_VERIFICATION) ? "email verification" : "password reset";

        if (pr.getStatus() == PasswordResetStatus.LOCKED) {
            if (pr.getLockedUntil() != null && pr.getLockedUntil().isAfter(now)) {
                throw new IllegalArgumentException("Account " + flowLower + " is locked due to too many failed attempts.");
            }
            pr.setStatus(PasswordResetStatus.EXPIRED);
            pr.setOtpHash(null);
            pr.setFailedAttempts(0);
            pr.setLockedUntil(null);
            passwordResetRepository.save(pr);
            throw new IllegalArgumentException("Verification code has expired. Please request a new one.");
        }

        if (pr.getStatus() == PasswordResetStatus.VERIFIED) {
            throw new IllegalArgumentException("This code has already been verified.");
        }

        if (pr.getStatus() == PasswordResetStatus.COMPLETED || pr.getStatus() == PasswordResetStatus.EXPIRED
                || pr.getStatus() == PasswordResetStatus.PENDING || pr.getStatus() == PasswordResetStatus.DELIVERY_FAILED
                || pr.getStatus() == PasswordResetStatus.REVOKED) {
            throw new IllegalArgumentException("Invalid or expired verification request. Please request a new code.");
        }

        if (pr.getOtpExpiresAt() == null || !pr.getOtpExpiresAt().isAfter(now)) {
            pr.setStatus(PasswordResetStatus.EXPIRED);
            pr.setOtpHash(null);
            passwordResetRepository.save(pr);
            throw new IllegalArgumentException("Verification code has expired. Please request a new one.");
        }

        int maxFailedAttempts = getMaxFailedAttempts();
        int lockDurationMinutes = getLockDurationMinutes();
        boolean matches = otpService.verifyOtp(user.getUserId(), purpose, pr.getChallengeId(), enteredOtp, pr.getOtpHash());
        if (!matches) {
            int newAttempts = pr.getFailedAttempts() + 1;
            pr.setFailedAttempts(newAttempts);
            if (newAttempts >= maxFailedAttempts) {
                pr.setStatus(PasswordResetStatus.LOCKED);
                pr.setLockedUntil(now.plusMinutes(lockDurationMinutes));
                pr.setOtpHash(null);
                passwordResetRepository.save(pr);
                throw new IllegalArgumentException("Too many failed attempts (" + maxFailedAttempts + "/" + maxFailedAttempts + "). " + flowTitle + " is locked for " + lockDurationMinutes + " minutes.");
            }
            passwordResetRepository.save(pr);
            throw new IllegalArgumentException("Invalid verification code. Remaining attempts: " + (maxFailedAttempts - newAttempts));
        }

        String plainResetToken = null;
        if (issueResetToken) {
            plainResetToken = resetTokenService.generateResetToken();
            pr.setStatus(PasswordResetStatus.VERIFIED);
            pr.setVerifiedAt(now);
            pr.setResetTokenHash(resetTokenService.hashToken(plainResetToken));
            pr.setResetTokenExpiresAt(now.plusMinutes(getResetTokenTtlMinutes()));
        } else {
            pr.setStatus(PasswordResetStatus.COMPLETED);
            pr.setVerifiedAt(now);
            pr.setCompletedAt(now);
            pr.setResetTokenHash(null);
            pr.setResetTokenExpiresAt(null);
        }

        pr.setOtpHash(null);
        pr.setFailedAttempts(0);
        pr.setLockedUntil(null);
        passwordResetRepository.save(pr);

        if (activateUserOnSuccess) {
            user.setAccountStatus("ACTIVE");
            userRepository.save(user);
        }

        return new VerificationResult(user.getUserId(), user.getEmail(), user.getFullName(), plainResetToken);
    }

    /**
     * Atomically creates (or recycles an abandoned >24h) PENDING_VERIFICATION customer account
     * alongside its initial EMAIL_VERIFICATION OTP challenge.
     */
    @Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
    public PreparationResult registerPendingCustomerTx(
            String cleanFullName,
            String cleanEmail,
            String cleanPhone,
            String encodedPassword,
            boolean canReplaceAbandoned) {
        Optional<User> existingOpt = userRepository.findByEmailForUpdate(cleanEmail);
        LocalDateTime now = LocalDateTime.now();

        PasswordPolicyService policy = (passwordPolicyService != null) ? passwordPolicyService : new PasswordPolicyService();
        List<String> phoneVariants = policy.getEquivalentPhoneVariants(cleanPhone);

        if (existingOpt.isPresent()) {
            User existing = existingOpt.get();
            String status = existing.getAccountStatus() != null ? existing.getAccountStatus().trim().toUpperCase() : "";
            boolean isPending = "PENDING_VERIFICATION".equals(status) || "PENDING".equals(status);
            if (!isPending) {
                throw new IllegalArgumentException("This email address is already registered.");
            }
            boolean isAbandoned = existing.getCreatedAt() != null
                    && existing.getCreatedAt().isBefore(now.minusHours(24))
                    && canReplaceAbandoned;
            if (!isAbandoned) {
                String activeChallenge = passwordResetRepository
                        .findByUserIdAndPurpose(existing.getUserId(), OtpPurpose.EMAIL_VERIFICATION)
                        .map(PasswordReset::getChallengeId)
                        .orElse(null);
                throw new PendingVerificationException(
                        cleanEmail,
                        activeChallenge,
                        "This email is already awaiting verification. Please enter your verification code or resend a new one."
                );
            }
            boolean phoneTaken = !phoneVariants.isEmpty()
                    ? userRepository.existsByPhoneInAndUserIdNot(phoneVariants, existing.getUserId())
                    : userRepository.existsByPhoneAndUserIdNot(cleanPhone, existing.getUserId());
            if (phoneTaken) {
                throw new IllegalArgumentException("This phone number is already registered.");
            }
            existing.setFullName(cleanFullName);
            existing.setPhone(cleanPhone);
            existing.setPasswordHash(encodedPassword);
            existing.setGoogleId(null);
            existing.setCinemaId(null);
            existing.setRole("CUSTOMER");
            existing.setAccountStatus("PENDING");
            userRepository.saveAndFlush(existing);
            return issueChallengeForLockedUser(existing, OtpPurpose.EMAIL_VERIFICATION, false);
        }

        boolean newPhoneTaken = !phoneVariants.isEmpty()
                ? userRepository.existsByPhoneIn(phoneVariants)
                : userRepository.existsByPhone(cleanPhone);
        if (newPhoneTaken) {
            throw new IllegalArgumentException("This phone number is already registered.");
        }

        User user = new User(cleanFullName, cleanEmail, cleanPhone, encodedPassword, "CUSTOMER");
        user.setGoogleId(null);
        user.setCinemaId(null);
        user.setAccountStatus("PENDING");
        user = userRepository.saveAndFlush(user);

        return issueChallengeForLockedUser(user, OtpPurpose.EMAIL_VERIFICATION, false);
    }

    private void revokeCompetingPurposeChallenges(List<PasswordReset> lockedRows, OtpPurpose activePurpose) {
        if (lockedRows == null || lockedRows.isEmpty()) {
            return;
        }
        for (PasswordReset other : lockedRows) {
            if (other.getPurpose() == activePurpose) {
                continue;
            }
            if (other.getStatus() == PasswordResetStatus.PENDING
                    || other.getStatus() == PasswordResetStatus.SENT
                    || other.getStatus() == PasswordResetStatus.VERIFIED) {
                other.setStatus(PasswordResetStatus.REVOKED);
                other.setOtpHash(null);
                other.setResetTokenHash(null);
                other.setResetTokenExpiresAt(null);
                passwordResetRepository.save(other);
            }
        }
    }
}
