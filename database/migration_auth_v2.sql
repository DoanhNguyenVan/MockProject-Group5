-- BOX Cinema Auth v2 Migration: Add OTP purpose separation to PASSWORD_RESET
USE box_cinema;

ALTER TABLE password_reset
    ADD COLUMN purpose VARCHAR(30) NOT NULL DEFAULT 'PASSWORD_RESET' AFTER user_id;

ALTER TABLE password_reset
    DROP FOREIGN KEY fk_password_reset_user;

ALTER TABLE password_reset
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (user_id, purpose),
    ADD CONSTRAINT chk_password_reset_purpose CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION')),
    ADD CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE;

