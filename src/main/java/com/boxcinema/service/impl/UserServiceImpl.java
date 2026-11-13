package com.boxcinema.service.impl;

import com.boxcinema.dto.AuthMethodsDto;
import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.entity.OtpPurpose;
import com.boxcinema.entity.PasswordReset;
import com.boxcinema.entity.PasswordResetStatus;
import com.boxcinema.entity.User;
import com.boxcinema.exception.AuthException;
import com.boxcinema.repository.PasswordResetRepository;
import com.boxcinema.repository.UserRepository;
import com.boxcinema.service.AuthAuditService;
import com.boxcinema.service.EmailService;
import com.boxcinema.service.EmailVerificationService;
import com.boxcinema.service.GoogleAccountLinkService;
import com.boxcinema.service.GoogleOidcService;
import com.boxcinema.service.LoginAttemptService;
import com.boxcinema.service.PasswordHashService;
import com.boxcinema.service.PasswordPolicyService;
import com.boxcinema.service.PasswordUpgradeService;
import com.boxcinema.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicyService passwordPolicyService;
    private final LoginAttemptService loginAttemptService;
    private final AuthAuditService authAuditService;
    private final ObjectProvider<EmailVerificationService> emailVerificationServiceProvider;
    private final String dummyHash;

    private PasswordUpgradeService passwordUpgradeService;
    private GoogleAccountLinkService googleAccountLinkService;
    private PasswordResetRepository passwordResetRepository;

    @Autowired
    public UserServiceImpl(
            UserRepository userRepository,
            EmailService emailService,
            PasswordEncoder passwordEncoder,
            PasswordPolicyService passwordPolicyService,
            LoginAttemptService loginAttemptService,
            AuthAuditService authAuditService,
            ObjectProvider<EmailVerificationService> emailVerificationServiceProvider) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder != null ? passwordEncoder : new PasswordHashService();
        this.passwordPolicyService = passwordPolicyService != null ? passwordPolicyService : new PasswordPolicyService();
        this.loginAttemptService = loginAttemptService != null ? loginAttemptService : new LoginAttemptService();
        this.authAuditService = authAuditService != null ? authAuditService : new AuthAuditService();
        this.emailVerificationServiceProvider = emailVerificationServiceProvider;
        this.dummyHash = this.passwordEncoder.encode("BOX_CINEMA_DUMMY_TIMING_GUARD_2026!");
    }

    public UserServiceImpl(UserRepository userRepository, EmailService emailService, PasswordEncoder passwordEncoder) {
        this(userRepository, emailService, passwordEncoder, new PasswordPolicyService(), new LoginAttemptService(), new AuthAuditService(), null);
    }

    public UserServiceImpl(UserRepository userRepository, EmailService emailService) {
        this(userRepository, emailService, new PasswordHashService());
    }

    public UserServiceImpl(UserRepository userRepository) {
        this(userRepository, new EmailService() {
            @Override
            public boolean sendVerificationOtpEmail(String toEmail, String fullName, String otpCode) {
                return false;
            }
        });
    }

    @Autowired(required = false)
    public void setPasswordUpgradeService(PasswordUpgradeService passwordUpgradeService) {
        this.passwordUpgradeService = passwordUpgradeService;
    }

    @Autowired(required = false)
    public void setGoogleAccountLinkService(GoogleAccountLinkService googleAccountLinkService) {
        this.googleAccountLinkService = googleAccountLinkService;
    }

    @Autowired(required = false)
    public void setPasswordResetRepository(PasswordResetRepository passwordResetRepository) {
        this.passwordResetRepository = passwordResetRepository;
    }

    @Override
    public UserSummaryDto registerCustomer(RegisterRequestDto request) {
        EmailVerificationService verificationService = emailVerificationServiceProvider != null
                ? emailVerificationServiceProvider.getIfAvailable()
                : null;
        if (verificationService == null) {
            throw new IllegalStateException("Email verification registration service is unavailable.");
        }
        return verificationService.registerWithEmailOtp(request).user();
    }

    @Override
    @Transactional(readOnly = true)
    public UserSummaryDto authenticate(String email, String rawPassword) {
        return authenticateWithIp(email, rawPassword, resolveCurrentRequestIp());
    }

    @Transactional(readOnly = true)
    public UserSummaryDto authenticateWithIp(String email, String rawPassword, String clientIp) {
        String cleanEmail = passwordPolicyService.validateAndNormalizeEmail(email);
        if (rawPassword == null || rawPassword.isBlank()) {
            throw AuthException.unauthorized("AUTH_INVALID_CREDENTIALS", "Invalid email or password.");
        }
        String cleanIp = (clientIp == null || clientIp.isBlank()) ? "local" : clientIp.trim();

        loginAttemptService.checkNotLocked(cleanEmail, cleanIp);

        Optional<User> userOpt = userRepository.findByEmail(cleanEmail);
        if (userOpt.isEmpty()) {
            passwordEncoder.matches(rawPassword, dummyHash);
            handleFailedLogin(cleanEmail, cleanIp, null, "Unknown account");
        }

        User user = userOpt.get();
        String status = user.getAccountStatus() != null ? user.getAccountStatus().trim().toUpperCase(Locale.ROOT) : "";

        if ("DELETED".equals(status)) {
            passwordEncoder.matches(rawPassword, dummyHash);
            handleFailedLogin(cleanEmail, cleanIp, user.getUserId(), "Deleted account");
        }

        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            if (!"ACTIVE".equals(status) && !EmailVerificationService.isPendingStatus(status)) {
                authAuditService.logEvent("ACCOUNT_STATUS_REJECTED", user.getUserId(), cleanEmail, "DENIED",
                        "Inactive Google-only account: " + status, cleanIp);
                throw AuthException.forbidden("ACCOUNT_NOT_ACTIVE", "Account is not active.");
            }
            // V1.2 Section E2: never disclose Google-only status on unauthenticated password login
            passwordEncoder.matches(rawPassword, dummyHash);
            handleFailedLogin(cleanEmail, cleanIp, user.getUserId(), "Passwordless account attempted password login");
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            handleFailedLogin(cleanEmail, cleanIp, user.getUserId(), "Invalid password");
        }

        if (EmailVerificationService.isPendingStatus(status)) {
            authAuditService.logEvent("ACCOUNT_STATUS_REJECTED", user.getUserId(), cleanEmail, "DENIED",
                    "Email OTP verification pending", cleanIp);
            throw new IllegalStateException("PENDING_VERIFICATION:" + user.getEmail());
        }

        if (!"ACTIVE".equals(status)) {
            authAuditService.logEvent("ACCOUNT_STATUS_REJECTED", user.getUserId(), cleanEmail, "DENIED",
                    "Account status=" + status, cleanIp);
            throw AuthException.forbidden("ACCOUNT_NOT_ACTIVE", "Account is not active.");
        }

        loginAttemptService.clearAttempts(cleanEmail, cleanIp);

        if (passwordUpgradeService != null) {
            passwordUpgradeService.upgradeLegacyHashIfNeeded(user.getUserId(), rawPassword, user.getPasswordHash());
        }

        authAuditService.logEvent("AUTH_LOGIN_SUCCESS", user.getUserId(), cleanEmail, "SUCCESS", "Authenticated", cleanIp);
        return toSummaryDto(user);
    }

    private void handleFailedLogin(String cleanEmail, String cleanIp, Long userId, String reason) {
        boolean lockedNow = loginAttemptService.recordFailure(cleanEmail, cleanIp);
        authAuditService.logEvent("AUTH_LOGIN_FAILURE", userId, cleanEmail, "FAILED",
                lockedNow ? "Brute-force cooldown triggered" : reason, cleanIp);
        if (lockedNow) {
            long retryAfter = loginAttemptService.getRemainingLockSeconds(cleanEmail, cleanIp);
            throw new LoginAttemptService.LoginLockoutException(
                    "Too many failed login attempts for this email and IP. Please wait 15 minutes before trying again.",
                    retryAfter
            );
        }
        loginAttemptService.applyProgressiveDelayIfEnabled(cleanEmail, cleanIp);
        throw AuthException.unauthorized("AUTH_INVALID_CREDENTIALS", "Invalid email or password.");
    }

    /**
     * Google OAuth2 Login flow with controlled Automatic Account Linking:
     * - Case 1: Lookup strictly by google_id (`sub`) first; never creates duplicate user or rejects email alias drift.
     * - Case 2: Same verified email + google_id IS NULL -> auto-link ONLY when email is Google-managed (or matching Workspace `hd`),
     *           role == CUSTOMER, account_status == ACTIVE, and EMAIL_VERIFICATION OTP proof (verified_at != NULL) exists.
     * - Privileged roles (STAFF/MANAGER/ADMIN) or unverified/untrusted emails require explicit BOX verification before linking.
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserSummaryDto authenticateSocial(String provider, String providerId, String email, String fullName) {
        return authenticateSocial(
                provider,
                providerId,
                email,
                fullName,
                GoogleOidcService.isTrustedGoogleEmailIdentity(email, null)
        );
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserSummaryDto authenticateSocial(
            String provider,
            String providerId,
            String email,
            String fullName,
            boolean trustedGoogleEmail) {
        if (!"google".equalsIgnoreCase(provider)) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Unsupported social provider.");
        }
        if (providerId == null || providerId.isBlank()) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Google user identifier (sub) is missing.");
        }
        String cleanSub = providerId.trim();
        String cleanEmail = passwordPolicyService.validateAndNormalizeEmail(email);

        Optional<User> linkedUserOpt = userRepository.findByGoogleId(cleanSub);
        if (linkedUserOpt.isPresent()) {
            User user = linkedUserOpt.get();
            ensureAccountActiveForSocial(user, cleanEmail);
            authAuditService.logGoogleEvent("GOOGLE_LOGIN_SUCCESS", user.getUserId(), user.getEmail(), "SUCCESS",
                    "Authenticated via Google sub");
            return toSummaryDto(user);
        }

        Optional<User> existingEmailUserOpt = userRepository.findByEmailForUpdate(cleanEmail);
        if (existingEmailUserOpt.isPresent()) {
            User existing = existingEmailUserOpt.get();
            ensureAccountActiveForSocial(existing, cleanEmail);

            if (existing.getGoogleId() != null && !existing.getGoogleId().isBlank()) {
                if (cleanSub.equals(existing.getGoogleId())) {
                    authAuditService.logGoogleEvent("GOOGLE_LOGIN_SUCCESS", existing.getUserId(), existing.getEmail(),
                            "SUCCESS", "Authenticated via Google sub");
                    return toSummaryDto(existing);
                }
                authAuditService.logGoogleEvent("GOOGLE_LOGIN_FAILURE", existing.getUserId(), cleanEmail, "REJECTED",
                        "Account already linked to a different Google sub");
                throw AuthException.conflict("USER_ALREADY_HAS_DIFFERENT_GOOGLE",
                        "Your BOX Cinema account is already linked to a different Google account.");
            }

            String role = existing.getRole() != null ? existing.getRole().trim().toUpperCase(Locale.ROOT) : "CUSTOMER";
            if (!"CUSTOMER".equals(role)) {
                authAuditService.logGoogleEvent("GOOGLE_LOGIN_FAILURE", existing.getUserId(), cleanEmail, "AUTH_REQUIRED",
                        "Privileged role requires password verification before linking Google");
                throw new IllegalStateException("BOX_PASSWORD_AUTH_REQUIRED:" + existing.getEmail());
            }

            if (!trustedGoogleEmail) {
                authAuditService.logGoogleEvent("GOOGLE_LOGIN_FAILURE", existing.getUserId(), cleanEmail, "AUTH_REQUIRED",
                        "Untrusted third-party Google email domain requires BOX verification");
                throw new IllegalStateException("ACCOUNT_LINK_AUTH_REQUIRED:" + existing.getEmail());
            }

            if (!hasVerifiedEmailOtpEvidence(existing.getUserId())) {
                authAuditService.logGoogleEvent("GOOGLE_LOGIN_FAILURE", existing.getUserId(), cleanEmail, "OTP_REQUIRED",
                        "Missing verified EMAIL_VERIFICATION OTP proof; one-time OTP verification required");
                throw new IllegalStateException("GOOGLE_EMAIL_OTP_REQUIRED:" + existing.getEmail());
            }

            return performVerifiedGoogleLink(existing.getUserId(), cleanSub, existing.getEmail(), true);
        }

        authAuditService.logGoogleEvent("GOOGLE_LOGIN_SUCCESS", null, cleanEmail, "ONBOARDING_REQUIRED",
                "New Google user routed to profile completion");
        throw new IllegalStateException("GOOGLE_ONBOARDING_REQUIRED:" + cleanEmail);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserSummaryDto verifyPasswordAndLinkPendingGoogle(
            String pendingGoogleSub,
            String boxEmail,
            String rawPassword) {
        if (pendingGoogleSub == null || pendingGoogleSub.isBlank()) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Verified Google identity is required.");
        }
        String cleanSub = pendingGoogleSub.trim();

        UserSummaryDto authenticatedBox = authenticateWithIp(boxEmail, rawPassword, resolveCurrentRequestIp());
        return linkVerifiedBoxUserToPendingGoogle(authenticatedBox.getUserId(), cleanSub);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserSummaryDto verifyOtpAndLinkPendingGoogle(
            String pendingGoogleSub,
            String boxEmail,
            String challengeId,
            String otp) {
        if (pendingGoogleSub == null || pendingGoogleSub.isBlank()) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Verified Google identity is required.");
        }
        EmailVerificationService verificationService = emailVerificationServiceProvider != null
                ? emailVerificationServiceProvider.getIfAvailable()
                : null;
        if (verificationService == null) {
            throw new IllegalStateException("Email verification service is unavailable.");
        }
        String cleanSub = pendingGoogleSub.trim();
        UserSummaryDto verifiedBox = verificationService.verifyGoogleLinkEmailOtp(boxEmail, challengeId, otp);
        return linkVerifiedBoxUserToPendingGoogle(verifiedBox.getUserId(), cleanSub);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UserSummaryDto registerGoogleCustomer(String googleSub, String email, String fullName, String phone) {
        if (googleSub == null || googleSub.isBlank()) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Verified Google identity is required.");
        }
        String cleanSub = googleSub.trim();
        String cleanEmail = passwordPolicyService.validateAndNormalizeEmail(email);
        String cleanName = passwordPolicyService.validateFullName(fullName);
        String cleanPhone = passwordPolicyService.validateAndNormalizePhone(phone);
        List<String> phoneVariants = passwordPolicyService.getEquivalentPhoneVariants(cleanPhone);

        if (userRepository.findByGoogleId(cleanSub).isPresent()) {
            throw AuthException.conflict("GOOGLE_IDENTITY_ALREADY_LINKED",
                    "This Google identity is already registered. Please sign in with Google.");
        }
        if (userRepository.findByEmail(cleanEmail).isPresent()) {
            throw AuthException.conflict("ACCOUNT_LINK_REQUIRED",
                    "An existing BOX Cinema account uses this email. Please sign in and link Google from Account Settings.");
        }
        boolean phoneTaken = !phoneVariants.isEmpty()
                ? userRepository.existsByPhoneIn(phoneVariants)
                : userRepository.existsByPhone(cleanPhone);
        if (phoneTaken) {
            throw AuthException.conflict("VALIDATION_ERROR", "This phone number is already registered.");
        }

        User newUser = new User(cleanName, cleanEmail, cleanPhone, null, "CUSTOMER");
        newUser.setGoogleId(cleanSub);
        newUser.setPasswordHash(null);
        newUser.setCinemaId(null);
        newUser.setAccountStatus("ACTIVE");

        try {
            newUser = userRepository.saveAndFlush(newUser);
        } catch (DataIntegrityViolationException ex) {
            throw AuthException.conflict("GOOGLE_REGISTRATION_CONFLICT",
                    "This Google identity, email, or phone number is already registered.");
        }

        authAuditService.logGoogleEvent("GOOGLE_REGISTRATION_SUCCESS", newUser.getUserId(), cleanEmail, "SUCCESS",
                "Google customer registered");
        return toSummaryDto(newUser);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void linkGoogleAccount(Long userId, String googleSub, String googleEmail) {
        resolveLinkService().linkGoogleAccount(userId, googleSub, googleEmail);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void unlinkGoogleAccount(Long userId) {
        resolveLinkService().unlinkGoogleAccount(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public void reauthenticatePassword(Long userId, String rawPassword) {
        if (userId == null) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Authentication is required.");
        }
        if (rawPassword == null || rawPassword.isBlank()) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Current password is required to continue.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AuthException.badRequest("VALIDATION_ERROR", "User account not found."));
        if (!"ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
            throw AuthException.forbidden("ACCOUNT_NOT_ACTIVE", "Account is not active.");
        }
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw AuthException.badRequest("VALIDATION_ERROR", "This account does not have a BOX Cinema password yet.");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            authAuditService.logEvent("AUTH_LOGIN_FAILURE", userId, user.getEmail(), "REAUTH_FAILED", "Wrong password");
            throw AuthException.unauthorized("AUTH_INVALID_CREDENTIALS", "Current password is incorrect.");
        }
        if (passwordUpgradeService != null) {
            passwordUpgradeService.upgradeLegacyHashIfNeeded(user.getUserId(), rawPassword, user.getPasswordHash());
        }
        authAuditService.logEvent("GOOGLE_LINK_STARTED", userId, user.getEmail(), "REAUTH_VERIFIED", "Password re-verified");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = IllegalArgumentException.class)
    public void setPasswordForGoogleUser(Long userId, String newPassword, String confirmPassword) {
        if (userId == null) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Authentication is required.");
        }
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> AuthException.badRequest("VALIDATION_ERROR", "User account not found."));
        if (!"ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
            authAuditService.logEvent("ACCOUNT_STATUS_REJECTED", userId, user.getEmail(), "DENIED",
                    "Set password rejected: status=" + user.getAccountStatus());
            throw AuthException.forbidden("ACCOUNT_NOT_ACTIVE", "Only active accounts can set a password.");
        }
        if (user.getPasswordHash() != null && !user.getPasswordHash().isBlank()) {
            authAuditService.logEvent("PASSWORD_CHANGED", userId, user.getEmail(), "REJECTED",
                    "Account already has password");
            throw AuthException.conflict("VALIDATION_ERROR", "Your account already has a password configured.");
        }

        passwordPolicyService.validatePasswordPair(newPassword, confirmPassword);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.saveAndFlush(user);

        authAuditService.logEvent("PASSWORD_CHANGED", userId, user.getEmail(), "SUCCESS",
                "Password set for Google account");
    }

    @Override
    @Transactional(readOnly = true)
    public void verifyGoogleSubOwnership(Long userId, String googleSub) {
        if (userId == null) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Authentication is required.");
        }
        if (googleSub == null || googleSub.isBlank()) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Google user identifier (sub) is missing.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AuthException.badRequest("VALIDATION_ERROR", "User account not found."));
        if (!"ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
            throw AuthException.forbidden("ACCOUNT_NOT_ACTIVE", "Account is not active.");
        }
        if (user.getGoogleId() == null || user.getGoogleId().isBlank()) {
            throw AuthException.conflict("VALIDATION_ERROR", "No Google account is linked to this profile.");
        }
        byte[] expected = user.getGoogleId().trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] actual = googleSub.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (!java.security.MessageDigest.isEqual(expected, actual)) {
            authAuditService.logGoogleEvent("GOOGLE_LOGIN_FAILURE", userId, user.getEmail(), "REJECTED",
                    "Set-password Google re-auth sub mismatch");
            throw AuthException.conflict("GOOGLE_SUB_MISMATCH",
                    "Selected Google account does not match the Google account linked to your BOX Cinema profile.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public AuthMethodsDto getAuthMethods(Long userId) {
        if (userId == null) {
            throw AuthException.badRequest("VALIDATION_ERROR", "User ID is required.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> AuthException.badRequest("VALIDATION_ERROR", "User account not found."));
        boolean hasPassword = user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
        boolean googleLinked = user.getGoogleId() != null && !user.getGoogleId().isBlank();
        return new AuthMethodsDto(
                user.getUserId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                user.getAccountStatus(),
                hasPassword,
                googleLinked,
                hasPassword && googleLinked
        );
    }

    private UserSummaryDto linkVerifiedBoxUserToPendingGoogle(Long userId, String cleanSub) {
        Optional<User> subOwner = userRepository.findByGoogleId(cleanSub);
        if (subOwner.isPresent() && !subOwner.get().getUserId().equals(userId)) {
            authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", userId, null, "REJECTED",
                    "Google sub already owned by another user");
            throw AuthException.conflict(
                    "GOOGLE_IDENTITY_ALREADY_LINKED",
                    "This Google account is already linked to another BOX Cinema user."
            );
        }

        User locked = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> AuthException.badRequest("VALIDATION_ERROR", "User account not found."));
        ensureAccountActiveForSocial(locked, locked.getEmail());

        if (locked.getGoogleId() != null && !locked.getGoogleId().isBlank()) {
            if (cleanSub.equals(locked.getGoogleId())) {
                authAuditService.logGoogleEvent("GOOGLE_LOGIN_SUCCESS", locked.getUserId(), locked.getEmail(),
                        "IDEMPOTENT", "Verified Google link already established");
                return toSummaryDto(locked);
            }
            authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", locked.getUserId(), locked.getEmail(),
                    "REJECTED", "Target BOX account already has a different Google sub");
            throw AuthException.conflict(
                    "USER_ALREADY_HAS_DIFFERENT_GOOGLE",
                    "Your BOX Cinema account is already linked to a different Google account."
            );
        }

        return performVerifiedGoogleLink(locked.getUserId(), cleanSub, locked.getEmail(), false);
    }

    private UserSummaryDto performVerifiedGoogleLink(
            Long userId,
            String cleanSub,
            String verifiedEmail,
            boolean autoLinkMode) {
        int updated;
        try {
            updated = autoLinkMode
                    ? userRepository.atomicAutoLinkGoogle(userId, cleanSub, verifiedEmail)
                    : userRepository.atomicVerifiedLinkGoogle(userId, cleanSub);
        } catch (DataIntegrityViolationException ex) {
            authAuditService.logGoogleEvent(
                    autoLinkMode ? "GOOGLE_LOGIN_FAILURE" : "GOOGLE_LINK_FAILURE",
                    userId,
                    verifiedEmail,
                    "REJECTED",
                    "Unique google_id constraint conflict"
            );
            throw AuthException.conflict(
                    "GOOGLE_IDENTITY_ALREADY_LINKED",
                    "This Google account is already linked to another BOX Cinema user."
            );
        }

        User reloaded = userRepository.findById(userId)
                .orElseThrow(() -> AuthException.badRequest("VALIDATION_ERROR", "User account not found."));

        if (updated == 0) {
            if (cleanSub.equals(reloaded.getGoogleId())) {
                authAuditService.logGoogleEvent("GOOGLE_LOGIN_SUCCESS", userId, reloaded.getEmail(),
                        "IDEMPOTENT", "Google sub linked concurrently");
                return toSummaryDto(reloaded);
            }
            if (reloaded.getGoogleId() != null && !reloaded.getGoogleId().isBlank()) {
                authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", userId, reloaded.getEmail(),
                        "REJECTED", "Account already linked to a different Google sub concurrently");
                throw AuthException.conflict(
                        "USER_ALREADY_HAS_DIFFERENT_GOOGLE",
                        "Your BOX Cinema account is already linked to a different Google account."
                );
            }
            throw AuthException.conflict(
                    "GOOGLE_LINK_CONFLICT",
                    "Unable to link Google account because account state changed concurrently."
            );
        }

        authAuditService.logGoogleEvent(
                "GOOGLE_AUTO_LINK_SUCCESS",
                userId,
                reloaded.getEmail(),
                "SUCCESS",
                autoLinkMode ? "Auto-linked verified Google identity on login" : "Linked Google identity after BOX verification"
        );
        authAuditService.logGoogleEvent(
                "GOOGLE_LOGIN_SUCCESS",
                userId,
                reloaded.getEmail(),
                "SUCCESS",
                "Authenticated and linked via Google"
        );
        runAfterCommit(() -> emailService.sendGoogleLinkedNotificationEmailAsync(reloaded.getEmail(), reloaded.getFullName()));
        return toSummaryDto(reloaded);
    }

    private boolean hasVerifiedEmailOtpEvidence(Long userId) {
        if (userId == null) {
            return false;
        }
        EmailVerificationService verificationService = emailVerificationServiceProvider != null
                ? emailVerificationServiceProvider.getIfAvailable()
                : null;
        if (verificationService != null) {
            return verificationService.hasVerifiedEmailOtpEvidence(userId);
        }
        if (passwordResetRepository == null) {
            return false;
        }
        Optional<PasswordReset> prOpt = passwordResetRepository.findByUserIdAndPurpose(userId, OtpPurpose.EMAIL_VERIFICATION);
        return prOpt.isPresent()
                && prOpt.get().getStatus() == PasswordResetStatus.COMPLETED
                && prOpt.get().getVerifiedAt() != null;
    }

    private void ensureAccountActiveForSocial(User user, String emailForAudit) {
        String status = user.getAccountStatus() != null
                ? user.getAccountStatus().trim().toUpperCase(Locale.ROOT)
                : "";
        if ("ACTIVE".equals(status)) {
            return;
        }
        authAuditService.logGoogleEvent("GOOGLE_LOGIN_FAILURE", user.getUserId(), emailForAudit, "DENIED",
                "Account status=" + status);
        if (EmailVerificationService.isPendingStatus(status)) {
            throw AuthException.forbidden(
                    "ACCOUNT_PENDING_VERIFICATION",
                    "Your BOX Cinema account email is not verified yet. Please verify your email OTP before signing in with Google."
            );
        }
        if ("LOCKED".equals(status)) {
            throw AuthException.forbidden(
                    "ACCOUNT_LOCKED",
                    "Your BOX Cinema account is currently locked. Please contact support."
            );
        }
        if ("SUSPENDED".equals(status)) {
            throw AuthException.forbidden(
                    "ACCOUNT_SUSPENDED",
                    "Your BOX Cinema account is suspended. Please contact support."
            );
        }
        throw AuthException.forbidden("ACCOUNT_NOT_ACTIVE", "Account is not active.");
    }

    private void runAfterCommit(Runnable task) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    private GoogleAccountLinkService resolveLinkService() {
        if (googleAccountLinkService != null) {
            return googleAccountLinkService;
        }
        return new GoogleAccountLinkService(userRepository, emailService, passwordPolicyService, authAuditService);
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

    private String resolveCurrentRequestIp() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                HttpServletRequest req = attrs.getRequest();
                if (req != null) {
                    String xff = req.getHeader("X-Forwarded-For");
                    if (xff != null && !xff.isBlank()) {
                        return xff.split(",")[0].trim();
                    }
                    if (req.getRemoteAddr() != null && !req.getRemoteAddr().isBlank()) {
                        return req.getRemoteAddr();
                    }
                }
            }
        } catch (Exception ignored) {
            // Non-web test invocation
        }
        return "local";
    }
}
