-- =============================================================================
-- BOX Cinema Auth v2 Migration: Add OTP purpose separation to password_reset
-- Target: MySQL 8.0+ (Idempotent; uses DATABASE() + information_schema; no USE box_cinema)
-- =============================================================================

SET NAMES utf8mb4;

-- 1. Add purpose column if absent
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'password_reset' AND COLUMN_NAME = 'purpose'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE password_reset ADD COLUMN purpose VARCHAR(30) NOT NULL DEFAULT ''PASSWORD_RESET'' AFTER user_id',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. Ensure composite PRIMARY KEY (user_id, purpose)
SET @pk_has_purpose := (
    SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'password_reset'
      AND CONSTRAINT_NAME = 'PRIMARY' AND COLUMN_NAME = 'purpose'
);
SET @exists_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'password_reset' AND CONSTRAINT_NAME = 'fk_password_reset_user'
);

SET @sql_stmt := IF(@pk_has_purpose = 0 AND @exists_fk > 0,
    'ALTER TABLE password_reset DROP FOREIGN KEY fk_password_reset_user',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql_stmt := IF(@pk_has_purpose = 0,
    'ALTER TABLE password_reset DROP PRIMARY KEY, ADD PRIMARY KEY (user_id, purpose)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. Ensure CHECK constraint chk_password_reset_purpose
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'password_reset' AND CONSTRAINT_NAME = 'chk_password_reset_purpose'
);
SET @sql_stmt := IF(@exists_chk = 0,
    'ALTER TABLE password_reset ADD CONSTRAINT chk_password_reset_purpose CHECK (purpose IN (''PASSWORD_RESET'', ''EMAIL_VERIFICATION''))',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. Ensure FK fk_password_reset_user
SET @exists_fk_after := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'password_reset' AND CONSTRAINT_NAME = 'fk_password_reset_user'
);
SET @sql_stmt := IF(@exists_fk_after = 0,
    'ALTER TABLE password_reset ADD CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES users (user_id) ON DELETE CASCADE',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
