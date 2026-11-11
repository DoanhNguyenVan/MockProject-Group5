package com.boxcinema.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

public class OtpServiceTest {

    private final OtpService otpService = new OtpService();

    @Test
    void testOtpGenerationIs6Digits() {
        for (int i = 0; i < 50; i++) {
            String otp = otpService.generate6DigitOtp();
            assertNotNull(otp);
            assertEquals(6, otp.length());
            assertTrue(otp.matches("^\\d{6}$"));
        }
    }

    @Test
    void testHmacOtpVerificationMatchesAndRejectsTampered() {
        ReflectionTestUtils.setField(otpService, "hmacSecret", "TEST_SECRET_KEY_12345");
        Long userId = 1001L;
        String challengeId = "challenge-uuid-abc";
        String otp = "583921";

        String hash = otpService.computeOtpHmac(userId, challengeId, otp);
        assertNotNull(hash);
        assertEquals(64, hash.length()); // 256 bits = 64 hex characters

        // Correct OTP matches
        assertTrue(otpService.verifyOtp(userId, challengeId, "583921", hash));

        // Wrong OTP rejects
        assertFalse(otpService.verifyOtp(userId, challengeId, "123456", hash));

        // Wrong challenge rejects
        assertFalse(otpService.verifyOtp(userId, "different-challenge", otp, hash));

        // Wrong user rejects
        assertFalse(otpService.verifyOtp(1002L, challengeId, otp, hash));
    }
}

