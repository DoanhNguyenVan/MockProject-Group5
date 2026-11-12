package com.boxcinema.service;

import com.boxcinema.entity.OtpPurpose;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit verification of V1.2 security primitives:
 * Argon2id hashing + upgrade check, progressive delay backoff (1s/2s/4s/8s),
 * Section L rate-limit thresholds, and cross-purpose OTP HMAC separation.
 */
public class V12AuthSecurityUnitTest {

    @Test
    void testPasswordHashServiceUsesArgon2idAndDetectsLegacyBcryptUpgrades() {
        PasswordHashService hashService = new PasswordHashService();
        String hash = hashService.encode("Cinema_Unique_Passphrase_2026");

        assertNotNull(hash);
        assertTrue(hash.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"), "Expected Argon2id m=19456,t=2,p=1 prefix: " + hash);
        assertTrue(hashService.matches("Cinema_Unique_Passphrase_2026", hash));
        assertFalse(hashService.matches("Wrong_Passphrase_2026", hash));
        assertFalse(hashService.upgradeEncoding(hash));

        String legacy10 = new BCryptPasswordEncoder(10).encode("LegacySecret1");
        String legacy12 = new BCryptPasswordEncoder(12).encode("LegacySecret2");
        assertTrue(hashService.matches("LegacySecret1", legacy10));
        assertTrue(hashService.upgradeEncoding(legacy10));
        assertTrue(hashService.upgradeEncoding(legacy12));
    }

    @Test
    void testLoginAttemptServiceProgressiveDelayAndLockout() {
        LoginAttemptService attemptService = new LoginAttemptService();
        String email = "delay@boxcinema.com";
        String ipA = "198.51.100.10";
        String ipB = "198.51.100.20";

        assertEquals(0, attemptService.getProgressiveDelaySeconds(email, ipA));

        assertFalse(attemptService.recordFailure(email, ipA));
        assertEquals(1, attemptService.getProgressiveDelaySeconds(email, ipA));

        assertFalse(attemptService.recordFailure(email, ipA));
        assertEquals(2, attemptService.getProgressiveDelaySeconds(email, ipA));

        assertFalse(attemptService.recordFailure(email, ipA));
        assertEquals(4, attemptService.getProgressiveDelaySeconds(email, ipA));

        assertFalse(attemptService.recordFailure(email, ipA));
        assertEquals(8, attemptService.getProgressiveDelaySeconds(email, ipA));

        assertTrue(attemptService.recordFailure(email, ipA));
        LoginAttemptService.LoginLockoutException lockEx = assertThrows(
                LoginAttemptService.LoginLockoutException.class,
                () -> attemptService.checkNotLocked(email, ipA)
        );
        assertTrue(lockEx.getRetryAfterSeconds() > 0);
        assertDoesNotThrow(() -> attemptService.checkNotLocked(email, ipB));

        attemptService.clearAttempts(email, ipA);
        assertEquals(0, attemptService.getProgressiveDelaySeconds(email, ipA));
    }

    @Test
    void testRateLimitThresholdsMatchSectionL() {
        AuthRateLimitService rateLimiter = new AuthRateLimitService();

        for (int i = 0; i < 60; i++) {
            rateLimiter.checkLoginRateLimit("10.0.0.5");
        }
        AuthRateLimitService.RateLimitExceededException loginEx = assertThrows(
                AuthRateLimitService.RateLimitExceededException.class,
                () -> rateLimiter.checkLoginRateLimit("10.0.0.5")
        );
        assertTrue(loginEx.getRetryAfterSeconds() > 0);

        for (int i = 0; i < 5; i++) {
            rateLimiter.checkRegisterRateLimit("10.0.0.6");
        }
        assertThrows(AuthRateLimitService.RateLimitExceededException.class,
                () -> rateLimiter.checkRegisterRateLimit("10.0.0.6"));

        for (int i = 0; i < 10; i++) {
            rateLimiter.checkRegisterEmailRateLimit("regcap@boxcinema.com");
        }
        assertThrows(AuthRateLimitService.RateLimitExceededException.class,
                () -> rateLimiter.checkRegisterEmailRateLimit("REGCAP@boxcinema.com"));

        for (int i = 0; i < 5; i++) {
            rateLimiter.checkGoogleLinkUserRateLimit(555L);
        }
        assertThrows(AuthRateLimitService.RateLimitExceededException.class,
                () -> rateLimiter.checkGoogleLinkUserRateLimit(555L));

        for (int i = 0; i < 20; i++) {
            rateLimiter.checkGoogleLinkIpRateLimit("10.0.0.7");
        }
        assertThrows(AuthRateLimitService.RateLimitExceededException.class,
                () -> rateLimiter.checkGoogleLinkIpRateLimit("10.0.0.7"));
    }

    @Test
    void testOtpHmacStrictCrossPurposeIsolation() {
        OtpService otpService = new OtpService();
        ReflectionTestUtils.setField(otpService, "hmacSecret", "TEST_HMAC_SECRET_V12");
        String regHash = otpService.computeOtpHmac(77L, OtpPurpose.EMAIL_VERIFICATION, "chal-1", "123456");
        assertTrue(otpService.verifyOtp(77L, OtpPurpose.EMAIL_VERIFICATION, "chal-1", "123456", regHash));
        assertFalse(otpService.verifyOtp(77L, OtpPurpose.PASSWORD_RESET, "chal-1", "123456", regHash));
    }
}

