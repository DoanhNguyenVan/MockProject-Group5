package com.boxcinema.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ResetTokenServiceTest {

    private final ResetTokenService resetTokenService = new ResetTokenService();

    @Test
    void testTokenGenerationIs256BitHex() {
        String token = resetTokenService.generateResetToken();
        assertNotNull(token);
        assertEquals(64, token.length()); // 32 bytes = 64 hex characters
    }

    @Test
    void testTokenHashingAndVerification() {
        String token = resetTokenService.generateResetToken();
        String hash = resetTokenService.hashToken(token);

        assertNotNull(hash);
        assertEquals(64, hash.length());

        assertTrue(resetTokenService.verifyToken(token, hash));
        assertFalse(resetTokenService.verifyToken("invalid-token-123456", hash));
    }
}

