package com.boxcinema.service;

import com.boxcinema.entity.OtpPurpose;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

@Service
public class OtpService {

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.security.hmac-secret:BOX_CINEMA_DEFAULT_SECURE_HMAC_SECRET_KEY_2026_@!#}")
    private String hmacSecret;

    /**
     * Generates a cryptographically secure 6-digit numeric OTP (000000 - 999999).
     */
    public String generate6DigitOtp() {
        int code = secureRandom.nextInt(1_000_000);
        return String.format("%06d", code);
    }

    /**
     * Computes HMAC-SHA256 hash bound to (userId + challengeId + otp) for PASSWORD_RESET.
     */
    public String computeOtpHmac(Long userId, String challengeId, String otp) {
        return computeOtpHmac(userId, OtpPurpose.PASSWORD_RESET, challengeId, otp);
    }

    /**
     * Computes HMAC-SHA256 hash strictly scoped to (purpose + userId + challengeId + otp).
     */
    public String computeOtpHmac(Long userId, OtpPurpose purpose, String challengeId, String otp) {
        if (userId == null || purpose == null || challengeId == null || otp == null) {
            throw new IllegalArgumentException("Parameters for HMAC calculation cannot be null.");
        }
        try {
            // Preserve legacy PASSWORD_RESET payload while isolating non-reset purposes by prefix
            String payload = (purpose == OtpPurpose.PASSWORD_RESET)
                    ? userId + ":" + challengeId + ":" + otp.trim()
                    : purpose.name() + ":" + userId + ":" + challengeId + ":" + otp.trim();
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC-SHA256 for OTP", e);
        }
    }

    /**
     * Timing-safe verification of the entered OTP against the stored HMAC hash (PASSWORD_RESET).
     */
    public boolean verifyOtp(Long userId, String challengeId, String enteredOtp, String storedHash) {
        return verifyOtp(userId, OtpPurpose.PASSWORD_RESET, challengeId, enteredOtp, storedHash);
    }

    /**
     * Timing-safe verification of the entered OTP strictly scoped by OTP purpose.
     */
    public boolean verifyOtp(Long userId, OtpPurpose purpose, String challengeId, String enteredOtp, String storedHash) {
        if (enteredOtp == null || storedHash == null || enteredOtp.isBlank()) {
            return false;
        }
        String calculated = computeOtpHmac(userId, purpose, challengeId, enteredOtp);
        return MessageDigest.isEqual(
                calculated.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8)
        );
    }
}

