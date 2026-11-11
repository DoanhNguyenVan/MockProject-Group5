package com.boxcinema.service;

import com.boxcinema.dto.PasswordResetResponseDto;
import com.boxcinema.entity.PasswordReset;
import com.boxcinema.entity.PasswordResetStatus;
import com.boxcinema.entity.User;
import com.boxcinema.repository.PasswordResetRepository;
import com.boxcinema.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
public class PasswordResetServiceIntegrationTest {

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private PasswordResetTxHelper txHelper;

    @Autowired
    private OtpService otpService;

    @Autowired
    private ResetTokenService resetTokenService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetRepository passwordResetRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private EmailService emailService;

    private static final String TEST_EMAIL = "qa_audit_testuser@boxcinema.com";
    private User testUser;

    @BeforeEach
    void setUp() {
        when(emailService.sendPasswordResetOtpEmail(anyString(), anyString(), anyString())).thenReturn(true);
        when(emailService.sendPasswordResetSuccessEmail(anyString(), anyString())).thenReturn(true);

        // Clean up any existing test user
        userRepository.findByEmail(TEST_EMAIL).ifPresent(u -> {
            passwordResetRepository.deleteById(u.getUserId());
            userRepository.delete(u);
        });

        // Create standard active test user
        testUser = new User("QA Audit User", TEST_EMAIL, "0999888111", passwordEncoder.encode("InitialP@ssword123"), "CUSTOMER");
        testUser.setAccountStatus("ACTIVE");
        testUser = userRepository.save(testUser);
    }

    @AfterEach
    void tearDown() {
        if (testUser != null && testUser.getUserId() != null) {
            passwordResetRepository.deleteById(testUser.getUserId());
            userRepository.deleteById(testUser.getUserId());
        }
    }

    @Test
    void testFP001_RequestOtpSuccessCreatesPendingAndSentInDb() {
        PasswordResetResponseDto response = passwordResetService.requestPasswordReset(TEST_EMAIL);

        assertTrue(response.isSuccess());
        assertNotNull(response.getMessage());

        Optional<PasswordReset> prOpt = passwordResetRepository.findById(testUser.getUserId());
        assertTrue(prOpt.isPresent());

        PasswordReset pr = prOpt.get();
        assertEquals(PasswordResetStatus.SENT, pr.getStatus());
        assertEquals(0, pr.getFailedAttempts());
        assertEquals(0, pr.getResendCount());
        assertEquals(1, pr.getSendCountInWindow());
        assertNotNull(pr.getChallengeId());
        assertEquals(36, pr.getChallengeId().length()); // UUID
        assertNotNull(pr.getOtpHash());
        assertEquals(64, pr.getOtpHash().length()); // HMAC-SHA256
        assertNotNull(pr.getOtpExpiresAt());
        assertTrue(pr.getOtpExpiresAt().isAfter(LocalDateTime.now()));
    }

    @Test
    void testFP002_RequestOtpNonExistentEmailReturnsGenericSuccessAndNoDbRecord() {
        String nonExistent = "doesnotexist_random_9999@boxcinema.com";
        PasswordResetResponseDto response = passwordResetService.requestPasswordReset(nonExistent);

        assertTrue(response.isSuccess());
        assertEquals("If an account exists with this email, a verification code has been sent.", response.getMessage());

        // Ensure no user and no password_reset was created
        assertTrue(userRepository.findByEmail(nonExistent).isEmpty());
    }

    @Test
    void testFP003_RequestOtpInactiveAccountReturnsGenericSuccessAndNoDbRecord() {
        testUser.setAccountStatus("LOCKED");
        userRepository.save(testUser);

        PasswordResetResponseDto response = passwordResetService.requestPasswordReset(TEST_EMAIL);

        assertTrue(response.isSuccess());
        assertEquals("If an account exists with this email, a verification code has been sent.", response.getMessage());
        assertTrue(passwordResetRepository.findById(testUser.getUserId()).isEmpty());
    }

    @Test
    void testFP004_RequestOtpCooldownRejectsWithin60Seconds() {
        passwordResetService.requestPasswordReset(TEST_EMAIL);

        // Immediate second request within 60s
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                passwordResetService.requestPasswordReset(TEST_EMAIL)
        );
        assertTrue(ex.getMessage().contains("Please wait") || ex.getMessage().contains("recently sent"));
    }

    @Test
    void testFP005_ResendOtpRejectsWithinCooldown() {
        passwordResetService.requestPasswordReset(TEST_EMAIL);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                passwordResetService.resendOtp(TEST_EMAIL)
        );
        assertTrue(ex.getMessage().contains("Please wait"));
    }

    @Test
    void testFP006_ResendOtpSuccessAfterCooldownGeneratesNewChallenge() {
        passwordResetService.requestPasswordReset(TEST_EMAIL);

        PasswordReset prInitial = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        String initialChallenge = prInitial.getChallengeId();
        String initialHash = prInitial.getOtpHash();

        // Simulate 65 seconds elapsed
        prInitial.setLastSentAt(LocalDateTime.now().minusSeconds(65));
        passwordResetRepository.save(prInitial);

        PasswordResetResponseDto resendResponse = passwordResetService.resendOtp(TEST_EMAIL);
        assertTrue(resendResponse.isSuccess());

        PasswordReset prUpdated = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        assertNotEquals(initialChallenge, prUpdated.getChallengeId());
        assertNotEquals(initialHash, prUpdated.getOtpHash());
        assertEquals(1, prUpdated.getResendCount());
        assertEquals(PasswordResetStatus.SENT, prUpdated.getStatus());
    }

    @Test
    void testFP007_ResendOtpExceedsMax3Times() {
        passwordResetService.requestPasswordReset(TEST_EMAIL);

        PasswordReset pr = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        pr.setResendCount(3);
        pr.setLastSentAt(LocalDateTime.now().minusSeconds(65));
        passwordResetRepository.save(pr);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                passwordResetService.resendOtp(TEST_EMAIL)
        );
        assertTrue(ex.getMessage().contains("Maximum resend attempts reached"));
    }

    @Test
    void testFP008_HourlyRateLimitExceeds5Otps() {
        passwordResetService.requestPasswordReset(TEST_EMAIL);

        PasswordReset pr = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        pr.setSendCountInWindow(5);
        pr.setSendWindowStartedAt(LocalDateTime.now().minusMinutes(10));
        pr.setLastSentAt(LocalDateTime.now().minusSeconds(65));
        passwordResetRepository.save(pr);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                passwordResetService.requestPasswordReset(TEST_EMAIL)
        );
        assertTrue(ex.getMessage().contains("Too many password reset requests in the last hour"));
    }

    @Test
    void testFP009_VerifyOtpWrongCodeIncrementsFailedAttempts() {
        passwordResetService.requestPasswordReset(TEST_EMAIL);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                passwordResetService.verifyOtp(TEST_EMAIL, "000000")
        );
        assertTrue(ex.getMessage().contains("Invalid verification code. Remaining attempts: 4"));

        PasswordReset pr = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        assertEquals(1, pr.getFailedAttempts());
    }

    @Test
    void testFP010_VerifyOtp5FailedAttemptsLocksAccountFor15Minutes() {
        passwordResetService.requestPasswordReset(TEST_EMAIL);

        PasswordReset pr = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        pr.setFailedAttempts(4);
        passwordResetRepository.save(pr);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                passwordResetService.verifyOtp(TEST_EMAIL, "000000")
        );
        assertTrue(ex.getMessage().contains("Too many failed attempts (5/5). Password reset is locked for 15 minutes."));

        PasswordReset lockedPr = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        assertEquals(PasswordResetStatus.LOCKED, lockedPr.getStatus());
        assertNotNull(lockedPr.getLockedUntil());
        assertTrue(lockedPr.getLockedUntil().isAfter(LocalDateTime.now().plusMinutes(14)));
        assertNull(lockedPr.getOtpHash());
    }

    @Test
    void testFP011_VerifyOtpExpiredCodeRejects() {
        passwordResetService.requestPasswordReset(TEST_EMAIL);

        PasswordReset pr = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        pr.setOtpExpiresAt(LocalDateTime.now().minusSeconds(1)); // Expired
        passwordResetRepository.save(pr);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                passwordResetService.verifyOtp(TEST_EMAIL, "123456")
        );
        assertTrue(ex.getMessage().contains("Verification code has expired"));

        PasswordReset expiredPr = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        assertEquals(PasswordResetStatus.EXPIRED, expiredPr.getStatus());
        assertNull(expiredPr.getOtpHash());
    }

    @Test
    void testFP012_VerifyOtpSuccessAndAtomicConsumption() {
        passwordResetService.requestPasswordReset(TEST_EMAIL);

        PasswordReset pr = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        String knownOtp = "789123";
        pr.setOtpHash(otpService.computeOtpHmac(testUser.getUserId(), pr.getChallengeId(), knownOtp));
        passwordResetRepository.save(pr);

        PasswordResetResponseDto verifyResponse = passwordResetService.verifyOtp(TEST_EMAIL, knownOtp);
        assertTrue(verifyResponse.isSuccess());
        assertNotNull(verifyResponse.getResetToken());
        assertEquals(64, verifyResponse.getResetToken().length()); // 256-bit token hex

        PasswordReset verifiedPr = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        assertEquals(PasswordResetStatus.VERIFIED, verifiedPr.getStatus());
        assertNull(verifiedPr.getOtpHash()); // Atomic consumption
        assertNotNull(verifiedPr.getVerifiedAt());
        assertNotNull(verifiedPr.getResetTokenHash());
        assertNotNull(verifiedPr.getResetTokenExpiresAt());

        // Attempting to reuse the OTP immediately fails
        IllegalArgumentException reuseEx = assertThrows(IllegalArgumentException.class, () ->
                passwordResetService.verifyOtp(TEST_EMAIL, knownOtp)
        );
        assertTrue(reuseEx.getMessage().contains("already been verified"));
    }

    @Test
    void testFP013_CompletePasswordResetSuccessUpdatesPasswordAndRevokesToken() {
        passwordResetService.requestPasswordReset(TEST_EMAIL);

        PasswordReset pr = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        String knownOtp = "654321";
        pr.setOtpHash(otpService.computeOtpHmac(testUser.getUserId(), pr.getChallengeId(), knownOtp));
        passwordResetRepository.save(pr);

        PasswordResetResponseDto verifyResponse = passwordResetService.verifyOtp(TEST_EMAIL, knownOtp);
        String resetToken = verifyResponse.getResetToken();

        // 1. Weak password rejected
        assertThrows(IllegalArgumentException.class, () ->
                passwordResetService.completePasswordReset(resetToken, "short", "short")
        );

        // 2. Mismatched confirmation rejected
        assertThrows(IllegalArgumentException.class, () ->
                passwordResetService.completePasswordReset(resetToken, "NewP@ssword2026!", "DifferentP@ssword2026!")
        );

        // 3. Valid strong password accepted
        String newPassword = "NewStrongP@ss2026!";
        PasswordResetResponseDto completeResponse = passwordResetService.completePasswordReset(resetToken, newPassword, newPassword);
        assertTrue(completeResponse.isSuccess());

        // Verify user password hash was updated and matches
        User updatedUser = userRepository.findById(testUser.getUserId()).orElseThrow();
        assertTrue(passwordEncoder.matches(newPassword, updatedUser.getPasswordHash()));
        assertFalse(passwordEncoder.matches("InitialP@ssword123", updatedUser.getPasswordHash()));

        // Verify status COMPLETED and token revoked
        PasswordReset completedPr = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        assertEquals(PasswordResetStatus.COMPLETED, completedPr.getStatus());
        assertNotNull(completedPr.getCompletedAt());
        assertNull(completedPr.getResetTokenHash());
        assertNull(completedPr.getResetTokenExpiresAt());

        // 4. Token cannot be reused
        assertThrows(IllegalArgumentException.class, () ->
                passwordResetService.completePasswordReset(resetToken, "AnotherP@ss123!", "AnotherP@ss123!")
        );
    }

    @Test
    void testFP014_ConcurrencyTwoThreadsVerifySameOtpSimultaneously() throws Exception {
        passwordResetService.requestPasswordReset(TEST_EMAIL);

        PasswordReset pr = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        String knownOtp = "998877";
        pr.setOtpHash(otpService.computeOtpHmac(testUser.getUserId(), pr.getChallengeId(), knownOtp));
        passwordResetRepository.save(pr);

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Callable<Void> task = () -> {
            try {
                barrier.await(); // Synchronize thread start
                String token = txHelper.verifyOtp(TEST_EMAIL, knownOtp);
                if (token != null && !token.isBlank()) {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                failureCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task);
        Future<Void> f2 = executor.submit(task);

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one thread succeeds, the other fails due to row lock and atomic state change
        assertEquals(1, successCount.get(), "Exactly one thread must succeed in verifying OTP");
        assertEquals(1, failureCount.get(), "Concurrent second verification must be rejected");
    }

    @Test
    void testFP015_ConcurrencyTwoThreadsCompleteResetSimultaneously() throws Exception {
        passwordResetService.requestPasswordReset(TEST_EMAIL);

        PasswordReset pr = passwordResetRepository.findById(testUser.getUserId()).orElseThrow();
        String knownOtp = "445566";
        pr.setOtpHash(otpService.computeOtpHmac(testUser.getUserId(), pr.getChallengeId(), knownOtp));
        passwordResetRepository.save(pr);

        PasswordResetResponseDto verifyRes = passwordResetService.verifyOtp(TEST_EMAIL, knownOtp);
        String resetToken = verifyRes.getResetToken();

        int numThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numThreads);
        CyclicBarrier barrier = new CyclicBarrier(numThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Callable<Void> task = () -> {
            try {
                barrier.await();
                User u = txHelper.completePasswordReset(resetToken, "ConcurrentP@ss123!");
                if (u != null) {
                    successCount.incrementAndGet();
                }
            } catch (Exception e) {
                failureCount.incrementAndGet();
            }
            return null;
        };

        Future<Void> f1 = executor.submit(task);
        Future<Void> f2 = executor.submit(task);

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, successCount.get(), "Exactly one thread must succeed in completing password reset");
        assertEquals(1, failureCount.get(), "Second thread must be rejected because token was already consumed");
    }
}
