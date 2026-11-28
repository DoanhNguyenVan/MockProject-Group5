-- =====================================================================
-- Rollback v5: Reverts migration_v5_customer_bank_accounts.sql cleanly
-- =====================================================================

-- 1. Drop FK fk_rr_saved_bank
SET @exists_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND CONSTRAINT_NAME = 'fk_rr_saved_bank'
);
SET @sql_stmt := IF(@exists_fk > 0,
    'ALTER TABLE refund_requests DROP FOREIGN KEY fk_rr_saved_bank',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. Drop refund_requests.saved_bank_account_id
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'saved_bank_account_id'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN saved_bank_account_id',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. Drop refund_requests.bank_note
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'bank_note'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN bank_note',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. Drop customer_bank_accounts table
DROP TABLE IF EXISTS customer_bank_accounts;
