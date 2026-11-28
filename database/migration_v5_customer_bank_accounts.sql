-- =====================================================================
-- Migration v5: Customer Saved Bank Accounts & Refund Bank Note / Link
-- Target: MySQL 8.0+ (Idempotent, uses DATABASE() + information_schema)
-- =====================================================================

-- 1. Create customer_bank_accounts table
CREATE TABLE IF NOT EXISTS customer_bank_accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    bank_bin VARCHAR(20) NOT NULL,
    bank_name VARCHAR(100) NOT NULL,
    account_number_encrypted TEXT NOT NULL,
    account_number_masked VARCHAR(50) NOT NULL,
    account_number_hash CHAR(64) NOT NULL,
    account_holder_name VARCHAR(150) NOT NULL,
    note VARCHAR(255) NULL,
    is_default TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_cba_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    UNIQUE KEY uk_cba_user_bin_hash (user_id, bank_bin, account_number_hash),
    INDEX idx_cba_user_default (user_id, is_default, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. refund_requests.bank_note
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'bank_note'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN bank_note VARCHAR(255) NULL AFTER account_holder_name',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. refund_requests.saved_bank_account_id
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'saved_bank_account_id'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN saved_bank_account_id BIGINT NULL AFTER bank_note',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. FK fk_rr_saved_bank on refund_requests(saved_bank_account_id) -> customer_bank_accounts(id)
SET @exists_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND CONSTRAINT_NAME = 'fk_rr_saved_bank'
);
SET @sql_stmt := IF(@exists_fk = 0,
    'ALTER TABLE refund_requests ADD CONSTRAINT fk_rr_saved_bank FOREIGN KEY (saved_bank_account_id) REFERENCES customer_bank_accounts(id) ON DELETE SET NULL',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5. Bootstrap parity guards for payments(bin, account_number, account_name)
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'bin'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE payments ADD COLUMN bin VARCHAR(20) NULL AFTER expired_at',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'account_number'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE payments ADD COLUMN account_number VARCHAR(50) NULL AFTER bin',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'account_name'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE payments ADD COLUMN account_name VARCHAR(100) NULL AFTER account_number',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
