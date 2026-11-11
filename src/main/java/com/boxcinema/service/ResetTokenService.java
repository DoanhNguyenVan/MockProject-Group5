package com.boxcinema.service;

import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

@Service
public class ResetTokenService {

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a secure random 256-bit (32 bytes) token encoded as a 64-character hex string.
     */
    public String generateResetToken() {
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        return HexFormat.of().formatHex(tokenBytes);
    }

    /**
     * Computes the SHA-256 hash of the plain reset token to store in the database.
     */
    public String hashToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Raw reset token cannot be null or empty.");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Constant-time verification between raw token and stored hash.
     */
    public boolean verifyToken(String rawToken, String storedHash) {
        if (rawToken == null || storedHash == null || rawToken.isBlank()) {
            return false;
        }
        String calculated = hashToken(rawToken);
        return MessageDigest.isEqual(
                calculated.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8)
        );
    }
}

