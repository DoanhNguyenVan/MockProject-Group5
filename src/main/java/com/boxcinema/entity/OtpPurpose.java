package com.boxcinema.entity;

/**
 * Distinct OTP challenge purposes sharing the PASSWORD_RESET storage table.
 * Each purpose is strictly isolated by the composite primary key (user_id, purpose).
 */
public enum OtpPurpose {
    PASSWORD_RESET,
    EMAIL_VERIFICATION
}

