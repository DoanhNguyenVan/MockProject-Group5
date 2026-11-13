package com.boxcinema.entity;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary key for PASSWORD_RESET table: (user_id, purpose).
 */
public class PasswordResetId implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private OtpPurpose purpose;

    public PasswordResetId() {
    }

    public PasswordResetId(Long userId, OtpPurpose purpose) {
        this.userId = userId;
        this.purpose = purpose;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public OtpPurpose getPurpose() {
        return purpose;
    }

    public void setPurpose(OtpPurpose purpose) {
        this.purpose = purpose;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PasswordResetId that)) return false;
        return Objects.equals(userId, that.userId) && purpose == that.purpose;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, purpose);
    }
}

