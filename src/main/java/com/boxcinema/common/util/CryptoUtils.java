package com.boxcinema.common.util;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public final class CryptoUtils {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String GCM_PREFIX = "GCM1:";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private CryptoUtils() {
    }

    public static String generate256BitToken() {
        return generateBookingQrToken();
    }

    public static String generateBookingQrToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return "BOX1." + token;
    }

    public static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Error calculating HMAC-SHA256", e);
        }
    }

    public static String sha256(String data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Error calculating SHA-256", e);
        }
    }

    public static String encryptAes(String data, String secret) {
        if (data == null) {
            throw new IllegalArgumentException("Data to encrypt must not be null.");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Encryption secret must not be blank.");
        }
        try {
            byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(data.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return GCM_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt token with AES-GCM.", e);
        }
    }

    public static String decryptAes(String encryptedData, String secret) {
        if (encryptedData == null || encryptedData.isBlank()) {
            throw new IllegalArgumentException("Encrypted data must not be blank.");
        }
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("Encryption secret must not be blank.");
        }
        if (encryptedData.startsWith(GCM_PREFIX)) {
            try {
                byte[] combined = Base64.getDecoder().decode(encryptedData.substring(GCM_PREFIX.length()));
                if (combined.length <= GCM_IV_BYTES) {
                    throw new IllegalStateException("Malformed AES-GCM payload.");
                }
                byte[] iv = Arrays.copyOfRange(combined, 0, GCM_IV_BYTES);
                byte[] ciphertext = Arrays.copyOfRange(combined, GCM_IV_BYTES, combined.length);
                byte[] keyBytes = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
                SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
                return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to decrypt AES-GCM token.", e);
            }
        }
        // Legacy AES/ECB/PKCS5Padding backward-compatible fallback for historical rows
        try {
            byte[] keyBytes = Arrays.copyOf(MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)), 16);
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            return new String(cipher.doFinal(Base64.getDecoder().decode(encryptedData)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt legacy token.", e);
        }
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }
}
