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

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
@Transactional
public class UserServiceImpl implements UserService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int LOCK_DURATION_MINUTES = 30;
    private static final int OTP_EXPIRY_MINUTES = 10;

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    private final Map<String, LoginAttemptState> loginAttempts = new ConcurrentHashMap<>();
    private final Map<String, EmailOtpState> pendingEmailOtps = new ConcurrentHashMap<>();

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

        Optional<User> existingEmailUser = userRepository.findByEmail(cleanEmail);
        if (existingEmailUser.isPresent()) {
            User existing = existingEmailUser.get();
            if (!"PENDING_VERIFICATION".equalsIgnoreCase(existing.getAccountStatus())) {
                throw new IllegalArgumentException("This email address is already registered.");
            }
            Optional<User> phoneOwner = userRepository.findByPhone(cleanPhone);
            if (phoneOwner.isPresent() && !phoneOwner.get().getUserId().equals(existing.getUserId())) {
                throw new IllegalArgumentException("This phone number is already registered.");
            }
            existing.setFullName(request.getFullName().trim());
            existing.setPhone(cleanPhone);
            existing.setPasswordHash(passwordEncoder.encode(request.getPassword()));
            existing = userRepository.save(existing);
            generateAndSendOtp(existing);
            return toSummaryDto(existing);
        }

        if (userRepository.existsByPhone(cleanPhone)) {
            throw new IllegalArgumentException("This phone number is already registered.");
        }

        String hashedPassword = passwordEncoder.encode(request.getPassword());
        User user = new User(request.getFullName().trim(), cleanEmail, cleanPhone, hashedPassword, "CUSTOMER");
        user.setAccountStatus("PENDING_VERIFICATION");
        user = userRepository.save(user);

        generateAndSendOtp(user);
        return toSummaryDto(user);
    }

    @Override
    public UserSummaryDto verifyEmailOtp(String email, String otpCode) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email address is required.");
        }
        if (otpCode == null || otpCode.trim().length() != 6) {
            throw new IllegalArgumentException("Please enter the 6-digit verification code sent to your Gmail.");
        }

        String cleanEmail = email.trim().toLowerCase();
        String cleanOtp = otpCode.trim();

        User user = userRepository.findByEmail(cleanEmail)
                .orElseThrow(() -> new IllegalArgumentException("No pending registration found for " + cleanEmail + "."));

        if ("ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
            pendingEmailOtps.remove(cleanEmail);
            return toSummaryDto(user);
        }

        EmailOtpState otpState = pendingEmailOtps.get(cleanEmail);
        if (otpState == null) {
            throw new IllegalArgumentException("No active verification code found. Please click 'Resend Code' to get a new one.");
        }

        if (LocalDateTime.now().isAfter(otpState.expiresAt)) {
            pendingEmailOtps.remove(cleanEmail);
            throw new IllegalArgumentException("Your verification code has expired (valid for 10 minutes). Please request a new code.");
        }

        if (!otpState.otpCode.equals(cleanOtp)) {
            throw new IllegalArgumentException("Invalid verification code. Please check your Gmail and try again.");
        }

        user.setAccountStatus("ACTIVE");
        user = userRepository.save(user);
        pendingEmailOtps.remove(cleanEmail);

        return toSummaryDto(user);
    }

    @Override
    public boolean resendVerificationOtp(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email address is required.");
        }
        String cleanEmail = email.trim().toLowerCase();
        User user = userRepository.findByEmail(cleanEmail)
                .orElseThrow(() -> new IllegalArgumentException("No account found for " + cleanEmail + "."));

        if ("ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
            throw new IllegalArgumentException("This account is already verified. Please sign in.");
        }

        return generateAndSendOtp(user);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> getPendingOtpPreview(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        EmailOtpState state = pendingEmailOtps.get(email.trim().toLowerCase());
        if (state == null || LocalDateTime.now().isAfter(state.expiresAt) || state.sentViaSmtp) {
            return Optional.empty();
        }
        return Optional.of(state.otpCode);
    }

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
        if (user.getPasswordHash() == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            recordFailedAttempt(cleanEmail);
            throw new IllegalArgumentException("Invalid email or password.");
        }

        if ("PENDING_VERIFICATION".equalsIgnoreCase(user.getAccountStatus())) {
            generateAndSendOtp(user);
            throw new IllegalStateException("UNVERIFIED_EMAIL:" + cleanEmail);
        }

        if (!"ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
            throw new IllegalArgumentException("Your account is currently locked or inactive.");
        }

        loginAttempts.remove(cleanEmail);
        return toSummaryDto(user);
    }

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

        String rawId = (providerId == null || providerId.isBlank()) ? cleanEmail : providerId.trim();
        String socialKey = normalizedProvider.toLowerCase() + ":" + rawId;

        Optional<User> existingUserOpt = userRepository.findByGoogleId(socialKey);
        if (existingUserOpt.isEmpty()) {
            existingUserOpt = userRepository.findByEmail(cleanEmail);
        }

        if (existingUserOpt.isPresent()) {
            User existingUser = existingUserOpt.get();
            if ("PENDING_VERIFICATION".equalsIgnoreCase(existingUser.getAccountStatus())) {
                existingUser.setAccountStatus("ACTIVE");
                pendingEmailOtps.remove(cleanEmail);
            } else if (!"ACTIVE".equalsIgnoreCase(existingUser.getAccountStatus())) {
                throw new IllegalArgumentException("Your account is currently locked or inactive.");
            }
            if (existingUser.getGoogleId() == null || existingUser.getGoogleId().isBlank()) {
                existingUser.setGoogleId(socialKey);
            }
            if (existingUser.getFullName() == null || existingUser.getFullName().isBlank()) {
                existingUser.setFullName(cleanName);
            }
            existingUser = userRepository.save(existingUser);
            loginAttempts.remove(cleanEmail);
            return toSummaryDto(existingUser);
        }

        String uniqueSocialPhone = generateUniqueSocialPhone(normalizedProvider);
        User newUser = new User(cleanName, cleanEmail, uniqueSocialPhone, null, "CUSTOMER");
        newUser.setGoogleId(socialKey);
        newUser.setAccountStatus("ACTIVE");
        newUser = userRepository.save(newUser);

        return toSummaryDto(newUser);
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
            emailService.sendPasswordResetConfirmationEmail(user.getEmail(), user.getFullName());
        }
    }

    private boolean generateAndSendOtp(User user) {
        String otpCode = String.format("%06d", ThreadLocalRandom.current().nextInt(100_000, 1_000_000));
        boolean sentViaSmtp = emailService.sendVerificationOtpEmail(user.getEmail(), user.getFullName(), otpCode);
        pendingEmailOtps.put(
                user.getEmail().trim().toLowerCase(),
                new EmailOtpState(otpCode, LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES), sentViaSmtp)
        );
        return sentViaSmtp;
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

    private String generateUniqueSocialPhone(String provider) {
        String prefix = "FACEBOOK".equals(provider) ? "FB" : "GG";
        for (int i = 0; i < 20; i++) {
            long randomDigits = ThreadLocalRandom.current().nextLong(100_000_000L, 999_999_999L);
            String candidate = prefix + randomDigits;
            if (!userRepository.existsByPhone(candidate)) {
                return candidate;
            }
        }
        return prefix + (System.currentTimeMillis() % 1_000_000_000L);
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

    private static class EmailOtpState {
        final String otpCode;
        final LocalDateTime expiresAt;
        final boolean sentViaSmtp;

        EmailOtpState(String otpCode, LocalDateTime expiresAt, boolean sentViaSmtp) {
            this.otpCode = otpCode;
            this.expiresAt = expiresAt;
            this.sentViaSmtp = sentViaSmtp;
        }
    }
}
