package com.boxcinema.service.impl;

import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.entity.User;
import com.boxcinema.repository.UserRepository;
import com.boxcinema.service.EmailService;
import com.boxcinema.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Transactional
public class UserServiceImpl implements UserService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_DURATION_MINUTES = 30;

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final Map<String, LoginAttemptState> loginAttempts = new ConcurrentHashMap<>();

    @Autowired
    public UserServiceImpl(UserRepository userRepository,
                           EmailService emailService,
                           @Autowired(required = false) PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.emailService = emailService;
        this.passwordEncoder = (passwordEncoder != null) ? passwordEncoder : new BCryptPasswordEncoder();
    }

    public UserServiceImpl(UserRepository userRepository, EmailService emailService) {
        this(userRepository, emailService, new BCryptPasswordEncoder());
    }

    public UserServiceImpl(UserRepository userRepository) {
        this(userRepository, new EmailService() {
            @Override
            public boolean sendVerificationOtpEmail(String toEmail, String fullName, String otpCode) {
                return false;
            }
        }, new BCryptPasswordEncoder());
    }

    /**
     * Phase 1: Register customer directly with status ACTIVE (No OTP).
     */
    @Override
    public UserSummaryDto registerCustomer(RegisterRequestDto request) {
        if (request.getFullName() == null || request.getFullName().trim().length() < 2) {
            throw new IllegalArgumentException("Full name must be at least 2 characters.");
        }
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }
        String cleanEmail = request.getEmail().trim().toLowerCase();

        if (request.getPhone() == null || request.getPhone().isBlank()) {
            throw new IllegalArgumentException("Phone number is required.");
        }
        String cleanPhone = request.getPhone().trim();

        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters long.");
        }

        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Passwords do not match.");
        }

        // Check duplicate email
        if (userRepository.existsByEmail(cleanEmail)) {
            throw new IllegalArgumentException("This email address is already registered.");
        }

        // Check duplicate phone
        if (userRepository.existsByPhone(cleanPhone)) {
            throw new IllegalArgumentException("This phone number is already registered.");
        }

        String hashedPassword = passwordEncoder.encode(request.getPassword());
        User user = new User(request.getFullName().trim(), cleanEmail, cleanPhone, hashedPassword, "CUSTOMER");
        user.setAccountStatus("ACTIVE");
        user.setGoogleId(null);
        user.setCinemaId(null);
        user = userRepository.save(user);

        return toSummaryDto(user);
    }

    /**
     * Phase 2: Authenticate email/password.
     * Validates account status, blocks password login for Google-only accounts, BCrypt matches.
     */
    @Override
    @Transactional(readOnly = true)
    public UserSummaryDto authenticate(String email, String rawPassword) {
        if (email == null || email.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Email and password are required.");
        }

        String cleanEmail = email.trim().toLowerCase();
        checkLockStatus(cleanEmail);

        Optional<User> userOpt = userRepository.findByEmail(cleanEmail);
        if (userOpt.isEmpty()) {
            recordFailedAttempt(cleanEmail);
            throw new IllegalArgumentException("Invalid email or password.");
        }

        User user = userOpt.get();

        // Check account status
        if (!"ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
            throw new IllegalArgumentException("Your account is currently locked or inactive.");
        }

        // Check if account is Google-only (password_hash is NULL)
        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new IllegalArgumentException("This account was registered via Google. Please sign in using 'Continue with Google'.");
        }

        // Compare password via BCrypt
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            recordFailedAttempt(cleanEmail);
            throw new IllegalArgumentException("Invalid email or password.");
        }

        loginAttempts.remove(cleanEmail);
        return toSummaryDto(user);
    }

    /**
     * Phase 3: Authenticate Social (Google OAuth2).
     * Follows OWASP recommendations:
     * - Case A: Google sub already linked -> login directly.
     * - Case B: Google sub not linked, but email already exists in USERS -> DO NOT auto-link, instruct user to sign in and link in Account Settings.
     * - Case C: Brand new Google user -> signal that user needs to complete profile (enter phone number).
     */
    @Override
    public UserSummaryDto authenticateSocial(String provider, String providerId, String email, String fullName) {
        String normalizedProvider = (provider == null || provider.isBlank())
                ? "GOOGLE"
                : provider.trim().toUpperCase();

        if (!"GOOGLE".equals(normalizedProvider) && !"FACEBOOK".equals(normalizedProvider)) {
            throw new IllegalArgumentException("Unsupported social login provider: " + provider);
        }

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required from " + normalizedProvider + " account.");
        }

        String cleanEmail = email.trim().toLowerCase();
        String cleanName = (fullName == null || fullName.isBlank())
                ? cleanEmail.substring(0, cleanEmail.indexOf('@'))
                : fullName.trim();
        String sub = (providerId == null || providerId.isBlank()) ? cleanEmail : providerId.trim();

        // 1. Look up by google_id
        Optional<User> byGoogleId = userRepository.findByGoogleId(sub);
        if (byGoogleId.isPresent()) {
            User user = byGoogleId.get();
            if (!"ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
                throw new IllegalArgumentException("Your account is currently locked or inactive.");
            }
            return toSummaryDto(user);
        }

        // 2. Look up by email (User previously registered via Email/Password)
        Optional<User> byEmail = userRepository.findByEmail(cleanEmail);
        if (byEmail.isPresent()) {
            // OWASP rule: Do NOT automatically merge or link!
            throw new IllegalStateException("ACCOUNT_EXISTS_NEEDS_LINK:" + cleanEmail);
        }

        // 3. Brand new Google user: needs to complete profile with phone number
        throw new IllegalStateException("GOOGLE_NEEDS_PROFILE:" + sub + ":::" + cleanEmail + ":::" + cleanName);
    }

    /**
     * Phase 3: Complete profile for brand new Google user.
     */
    @Override
    public UserSummaryDto registerGoogleCustomer(String googleSub, String email, String fullName, String phone) {
        if (googleSub == null || googleSub.isBlank()) {
            throw new IllegalArgumentException("Google identity information is missing.");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }
        if (phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("Phone number is required to complete registration.");
        }

        String cleanEmail = email.trim().toLowerCase();
        String cleanPhone = phone.trim();
        String cleanName = (fullName != null && !fullName.isBlank()) ? fullName.trim() : "BOX Cinema Member";
        String cleanSub = googleSub.trim();

        if (userRepository.existsByPhone(cleanPhone)) {
            throw new IllegalArgumentException("This phone number is already registered. Please enter a different number.");
        }
        if (userRepository.existsByEmail(cleanEmail)) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }
        if (userRepository.findByGoogleId(cleanSub).isPresent()) {
            throw new IllegalArgumentException("This Google account is already registered.");
        }

        User newUser = new User(cleanName, cleanEmail, cleanPhone, null, "CUSTOMER");
        newUser.setGoogleId(cleanSub);
        newUser.setAccountStatus("ACTIVE");
        newUser = userRepository.save(newUser);

        return toSummaryDto(newUser);
    }

    /**
     * Phase 4: Link Google account to an existing user in Account Settings.
     */
    @Override
    public void linkGoogleAccount(Long userId, String googleSub, String googleEmail) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID is required.");
        }
        if (googleSub == null || googleSub.isBlank()) {
            throw new IllegalArgumentException("Google identity is required.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User account not found."));

        String cleanSub = googleSub.trim();

        // Check if this Google sub is already linked to another account
        Optional<User> otherUser = userRepository.findByGoogleId(cleanSub);
        if (otherUser.isPresent() && !otherUser.get().getUserId().equals(userId)) {
            throw new IllegalArgumentException("This Google account is already linked to another BOX Cinema user.");
        }

        user.setGoogleId(cleanSub);
        userRepository.save(user);
    }

    /**
     * Phase 4: Unlink Google account from an existing user in Account Settings.
     */
    @Override
    public void unlinkGoogleAccount(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID is required.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User account not found."));

        if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
            throw new IllegalArgumentException("Cannot unlink Google because it is your only login method. Please set a password first.");
        }

        user.setGoogleId(null);
        userRepository.save(user);
    }

    @Override
    public void resetPassword(String email, String newPassword, String confirmPassword) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email address is required.");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("New password must be at least 6 characters long.");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new IllegalArgumentException("Passwords do not match.");
        }

        String cleanEmail = email.trim().toLowerCase();
        User user = userRepository.findByEmail(cleanEmail)
                .orElseThrow(() -> new IllegalArgumentException("No account found with that email address."));

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        loginAttempts.remove(cleanEmail);
        if (emailService != null) {
            final String targetEmail = user.getEmail();
            final String targetName = user.getFullName();
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        emailService.sendPasswordResetConfirmationEmailAsync(targetEmail, targetName);
                    }
                });
            } else {
                emailService.sendPasswordResetConfirmationEmailAsync(targetEmail, targetName);
            }
        }
    }

    private void checkLockStatus(String cleanEmail) {
        LoginAttemptState state = loginAttempts.get(cleanEmail);
        if (state != null && state.lockedUntil != null) {
            LocalDateTime now = LocalDateTime.now();
            if (now.isBefore(state.lockedUntil)) {
                long minutesLeft = Math.max(1, Duration.between(now, state.lockedUntil).toMinutes() + 1);
                throw new IllegalArgumentException(
                        "Account temporarily locked due to 5 failed login attempts. Please try again in "
                                + minutesLeft + " minutes.");
            } else {
                loginAttempts.remove(cleanEmail);
            }
        }
    }

    private void recordFailedAttempt(String cleanEmail) {
        LoginAttemptState state = loginAttempts.compute(cleanEmail, (key, existing) -> {
            if (existing == null || (existing.lockedUntil != null && LocalDateTime.now().isAfter(existing.lockedUntil))) {
                return new LoginAttemptState(1, null);
            }
            int nextCount = existing.failedCount + 1;
            LocalDateTime lockUntil = (nextCount >= MAX_FAILED_ATTEMPTS)
                    ? LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES)
                    : null;
            return new LoginAttemptState(nextCount, lockUntil);
        });

        if (state.lockedUntil != null) {
            throw new IllegalArgumentException(
                    "Account locked for 30 minutes after 5 consecutive failed login attempts.");
        }
    }

    private UserSummaryDto toSummaryDto(User user) {
        return new UserSummaryDto(
                user.getUserId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole()
        );
    }

    private static class LoginAttemptState {
        final int failedCount;
        final LocalDateTime lockedUntil;

        LoginAttemptState(int failedCount, LocalDateTime lockedUntil) {
            this.failedCount = failedCount;
            this.lockedUntil = lockedUntil;
        }
    }
}
