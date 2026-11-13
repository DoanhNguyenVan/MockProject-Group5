package com.boxcinema.service;

import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.entity.OtpPurpose;
import com.boxcinema.entity.PasswordReset;
import com.boxcinema.entity.PasswordResetStatus;
import com.boxcinema.entity.User;
import com.boxcinema.repository.PasswordResetRepository;
import com.boxcinema.repository.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Coordinates email + password registration (AUTH-01) and Gmail OTP account activation.
 */
@Service
public class EmailVerificationService {

    private static final Pattern OTP_REGEX = Pattern.compile("^\\d{6}$");

    public record RegistrationResult(
            UserSummaryDto user,
            String challengeId,
            boolean emailSent,
            int cooldownSeconds
    ) {
    }

    public record ResendResult(
            String email,
            String challengeId,
            boolean emailSent,
            int cooldownSeconds
    ) {
    }

    private final UserRepository userRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final OtpChallengeTxHelper otpChallengeTxHelper;
    private final PendingAccountCleanupService pendingAccountCleanupService;
    private final PasswordPolicyService passwordPolicyService;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final AuthAuditService authAuditService;

    @Autowired
    public EmailVerificationService(
            UserRepository userRepository,
            PasswordResetRepository passwordResetRepository,
            OtpChallengeTxHelper otpChallengeTxHelper,
            PendingAccountCleanupService pendingAccountCleanupService,
            PasswordPolicyService passwordPolicyService,
            PasswordEncoder passwordEncoder,
            EmailService emailService,
            ObjectProvider<AuthAuditService> authAuditServiceProvider) {
        this.userRepository = userRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.otpChallengeTxHelper = otpChallengeTxHelper;
        this.pendingAccountCleanupService = pendingAccountCleanupService;
        this.passwordPolicyService = passwordPolicyService;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.authAuditService = authAuditServiceProvider != null
                ? authAuditServiceProvider.getIfAvailable(AuthAuditService::new)
                : new AuthAuditService();
    }

    public RegistrationResult registerWithEmailOtp(RegisterRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("Registration details are required.");
        }
        String cleanName = passwordPolicyService.validateFullName(request.getFullName());
        String cleanEmail = passwordPolicyService.validateAndNormalizeEmail(request.getEmail());
        String cleanPhone = passwordPolicyService.validateAndNormalizePhone(request.getPhone());
        passwordPolicyService.validatePasswordPair(request.getPassword(), request.getConfirmPassword());

        String encodedPassword = passwordEncoder.encode(request.getPassword());

        OtpChallengeTxHelper.PreparationResult prep;
        try {
            boolean allowAbandonedReplace = userRepository.findByEmail(cleanEmail)
                    .map(existing -> !pendingAccountCleanupService.hasBusinessRecords(existing.getUserId()))
                    .orElse(false);

            prep = otpChallengeTxHelper.registerPendingCustomerTx(
                    cleanName,
                    cleanEmail,
                    cleanPhone,
                    encodedPassword,
                    allowAbandonedReplace
            );
        } catch (DataIntegrityViolationException ex) {
            authAuditService.logEvent("EMAIL_REGISTRATION_STARTED", null, cleanEmail, "FAILED", "Concurrent duplicate email or phone");
            throw new IllegalArgumentException("This email address or phone number is already registered.");
        } catch (OtpChallengeTxHelper.PendingVerificationException ex) {
            authAuditService.logEvent("EMAIL_REGISTRATION_STARTED", null, cleanEmail, "PENDING", "Account awaiting OTP verification");
            throw ex;
        } catch (IllegalArgumentException ex) {
            authAuditService.logEvent("EMAIL_REGISTRATION_STARTED", null, cleanEmail, "FAILED", ex.getMessage());
            throw ex;
        }

        boolean emailSent = false;
        try {
            emailSent = emailService.sendVerificationOtpEmail(prep.email(), prep.fullName(), prep.plainOtp());
        } finally {
            otpChallengeTxHelper.finalizeOtpSent(prep.userId(), OtpPurpose.EMAIL_VERIFICATION, prep.challengeId(), emailSent);
        }

        authAuditService.logEvent(
                "EMAIL_REGISTRATION_STARTED",
                prep.userId(),
                cleanEmail,
                emailSent ? "SENT" : "DELIVERY_FAILED",
                emailSent ? "Registration pending OTP verification" : "SMTP delivery failed; resend available"
        );

        UserSummaryDto userDto = new UserSummaryDto(prep.userId(), prep.fullName(), prep.email(), cleanPhone, "CUSTOMER");
        userDto.setAccountStatus("PENDING");
        userDto.setHasPassword(true);
        userDto.setGoogleLinked(false);

        return new RegistrationResult(
                userDto,
                prep.challengeId(),
                emailSent,
                otpChallengeTxHelper.getResendCooldownSeconds()
        );
    }

    public UserSummaryDto verifyRegistrationOtp(String rawEmail, String challengeId, String otp) {
        if (otp == null || !OTP_REGEX.matcher(otp.trim()).matches()) {
            throw new IllegalArgumentException("Verification code must be exactly 6 numeric digits.");
        }
        String resolvedEmail = resolveEmail(rawEmail, challengeId);

        try {
            OtpChallengeTxHelper.VerificationResult result = otpChallengeTxHelper.verifyOtpChallenge(
                    resolvedEmail,
                    OtpPurpose.EMAIL_VERIFICATION,
                    challengeId,
                    otp.trim(),
                    u -> isPendingStatus(u.getAccountStatus()),
                    false,
                    true
            );
            User activated = userRepository.findById(result.userId())
                    .orElseThrow(() -> new IllegalStateException("Activated user not found."));
            authAuditService.logEvent("EMAIL_VERIFICATION_SUCCESS", activated.getUserId(), activated.getEmail(), "SUCCESS", "Account activated");
            return toSummaryDto(activated);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            authAuditService.logEvent("EMAIL_VERIFICATION_FAILURE", null, resolvedEmail, "FAILED", ex.getMessage());
            throw ex;
        }
    }

    public ResendResult resendVerificationOtp(String rawEmail, String challengeId) {
        String resolvedEmail = resolveEmail(rawEmail, challengeId);

        OtpChallengeTxHelper.PreparationResult prep = otpChallengeTxHelper.prepareChallenge(
                resolvedEmail,
                OtpPurpose.EMAIL_VERIFICATION,
                true,
                u -> isPendingStatus(u.getAccountStatus())
        );

        if (!prep.userEligible()) {
            authAuditService.logEvent("EMAIL_VERIFICATION_FAILURE", null, resolvedEmail, "IGNORED", "Account not awaiting verification");
            throw new IllegalArgumentException("No pending verification request was found for this email.");
        }

        boolean emailSent = false;
        try {
            emailSent = emailService.sendVerificationOtpEmail(prep.email(), prep.fullName(), prep.plainOtp());
        } finally {
            otpChallengeTxHelper.finalizeOtpSent(prep.userId(), OtpPurpose.EMAIL_VERIFICATION, prep.challengeId(), emailSent);
        }

        if (!emailSent) {
            authAuditService.logEvent("EMAIL_VERIFICATION_FAILURE", prep.userId(), resolvedEmail, "FAILED", "SMTP delivery unavailable on resend");
            throw new IllegalStateException("Failed to resend verification email. Please try again later.");
        }

        authAuditService.logEvent("EMAIL_REGISTRATION_STARTED", prep.userId(), resolvedEmail, "RESENT", "Verification OTP resent");
        return new ResendResult(
                prep.email(),
                prep.challengeId(),
                true,
                otpChallengeTxHelper.getResendCooldownSeconds()
        );
    }

    /**
     * Checks whether the account has a completed EMAIL_VERIFICATION OTP proof with a non-null verified_at timestamp.
     */
    public boolean hasVerifiedEmailOtpEvidence(Long userId) {
        if (userId == null) {
            return false;
        }
        return passwordResetRepository.findByUserIdAndPurpose(userId, OtpPurpose.EMAIL_VERIFICATION)
                .map(pr -> pr.getStatus() == PasswordResetStatus.COMPLETED && pr.getVerifiedAt() != null)
                .orElse(false);
    }

    /**
     * Issues a one-time 6-digit EMAIL_VERIFICATION OTP for an already-ACTIVE CUSTOMER whose email
     * lacks verified OTP evidence (e.g. GGL-005) or who chooses OTP verification in Case 3 cross-email linking.
     */
    public ResendResult requestGoogleLinkEmailOtp(String rawEmail, boolean isResend) {
        String cleanEmail = passwordPolicyService.validateAndNormalizeEmail(rawEmail);
        Optional<User> userOpt = userRepository.findByEmail(cleanEmail);
        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("No BOX Cinema account found for this email address.");
        }
        User target = userOpt.get();
        if (!"ACTIVE".equalsIgnoreCase(target.getAccountStatus())) {
            throw new IllegalArgumentException("Only active BOX Cinema accounts can link Google sign-in.");
        }
        if (!"CUSTOMER".equalsIgnoreCase(target.getRole())) {
            throw new IllegalArgumentException("Staff, Manager, and Admin accounts must verify with Email & Password to link Google sign-in.");
        }
        if (target.getGoogleId() != null && !target.getGoogleId().isBlank()) {
            throw new IllegalArgumentException("This BOX Cinema account is already linked to a Google account.");
        }

        OtpChallengeTxHelper.PreparationResult prep = otpChallengeTxHelper.prepareChallenge(
                cleanEmail,
                OtpPurpose.EMAIL_VERIFICATION,
                isResend,
                u -> "ACTIVE".equalsIgnoreCase(u.getAccountStatus())
                        && "CUSTOMER".equalsIgnoreCase(u.getRole())
                        && (u.getGoogleId() == null || u.getGoogleId().isBlank())
        );

        if (!prep.userEligible()) {
            throw new IllegalArgumentException("This account is not eligible for email OTP Google verification.");
        }

        boolean emailSent = false;
        try {
            emailSent = emailService.sendGoogleAutoLinkOtpEmail(prep.email(), prep.fullName(), prep.plainOtp());
        } finally {
            otpChallengeTxHelper.finalizeOtpSent(prep.userId(), OtpPurpose.EMAIL_VERIFICATION, prep.challengeId(), emailSent);
        }

        authAuditService.logGoogleEvent(
                "GOOGLE_LINK_OTP_SENT",
                prep.userId(),
                cleanEmail,
                emailSent ? "SENT" : "DELIVERY_FAILED",
                isResend ? "Google link verification OTP resent" : "Google link verification OTP sent"
        );

        return new ResendResult(
                prep.email(),
                prep.challengeId(),
                emailSent,
                otpChallengeTxHelper.getResendCooldownSeconds()
        );
    }

    /**
     * Verifies the 6-digit EMAIL_VERIFICATION OTP for an already-ACTIVE CUSTOMER prior to linking Google.
     * Persists COMPLETED status and verified_at without altering account_status.
     */
    public UserSummaryDto verifyGoogleLinkEmailOtp(String rawEmail, String challengeId, String otp) {
        if (otp == null || !OTP_REGEX.matcher(otp.trim()).matches()) {
            throw new IllegalArgumentException("Verification code must be exactly 6 numeric digits.");
        }
        String resolvedEmail = resolveEmail(rawEmail, challengeId);
        try {
            OtpChallengeTxHelper.VerificationResult result = otpChallengeTxHelper.verifyOtpChallenge(
                    resolvedEmail,
                    OtpPurpose.EMAIL_VERIFICATION,
                    challengeId,
                    otp.trim(),
                    u -> "ACTIVE".equalsIgnoreCase(u.getAccountStatus())
                            && "CUSTOMER".equalsIgnoreCase(u.getRole())
                            && (u.getGoogleId() == null || u.getGoogleId().isBlank()),
                    false,
                    false
            );
            User verifiedUser = userRepository.findById(result.userId())
                    .orElseThrow(() -> new IllegalStateException("Verified user account not found."));
            authAuditService.logGoogleEvent("EMAIL_VERIFICATION_SUCCESS", verifiedUser.getUserId(), verifiedUser.getEmail(),
                    "SUCCESS", "Google-link email ownership verified via OTP");
            return toSummaryDto(verifiedUser);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            authAuditService.logGoogleEvent("EMAIL_VERIFICATION_FAILURE", null, resolvedEmail, "FAILED", ex.getMessage());
            throw ex;
        }
    }

    private String resolveEmail(String rawEmail, String challengeId) {
        if (rawEmail != null && !rawEmail.isBlank()) {
            return passwordPolicyService.validateAndNormalizeEmail(rawEmail);
        }
        if (challengeId != null && !challengeId.isBlank()) {
            PasswordReset pr = passwordResetRepository
                    .findByChallengeIdAndPurpose(challengeId.trim(), OtpPurpose.EMAIL_VERIFICATION)
                    .orElseThrow(() -> new IllegalArgumentException("Invalid or expired verification request. Please request a new code."));
            User user = userRepository.findById(pr.getUserId())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid or expired verification request. Please request a new code."));
            return user.getEmail();
        }
        throw new IllegalArgumentException("Email address or verification challenge ID is required.");
    }

    public static boolean isPendingStatus(String status) {
        if (status == null) {
            return false;
        }
        String normalized = status.trim().toUpperCase();
        return "PENDING_VERIFICATION".equals(normalized) || "PENDING".equals(normalized);
    }

    private UserSummaryDto toSummaryDto(User user) {
        UserSummaryDto dto = new UserSummaryDto(
                user.getUserId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole()
        );
        dto.setAccountStatus(user.getAccountStatus());
        dto.setHasPassword(user.getPasswordHash() != null && !user.getPasswordHash().isBlank());
        dto.setGoogleLinked(user.getGoogleId() != null && !user.getGoogleId().isBlank());
        return dto;
    }
}

