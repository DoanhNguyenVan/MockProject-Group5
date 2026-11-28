-- =====================================================================
-- Rollback v4: Reverts migration_v4_refund_payout_workflow.sql cleanly
-- =====================================================================

-- 1. Drop idx_ppt_payos_payout_id
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payos_payout_transactions' AND INDEX_NAME = 'idx_ppt_payos_payout_id'
);
SET @sql_stmt := IF(@exists_idx > 0,
    'ALTER TABLE payos_payout_transactions DROP INDEX idx_ppt_payos_payout_id',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. Drop idx_ppt_batch_code
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payos_payout_transactions' AND INDEX_NAME = 'idx_ppt_batch_code'
);
SET @sql_stmt := IF(@exists_idx > 0,
    'ALTER TABLE payos_payout_transactions DROP INDEX idx_ppt_batch_code',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. Drop payos_payout_transactions.batch_code
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payos_payout_transactions' AND COLUMN_NAME = 'batch_code'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE payos_payout_transactions DROP COLUMN batch_code',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. Drop idx_rr_payout_queue
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND INDEX_NAME = 'idx_rr_payout_queue'
);
SET @sql_stmt := IF(@exists_idx > 0,
    'ALTER TABLE refund_requests DROP INDEX idx_rr_payout_queue',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5. Drop FK fk_rr_payout_decider
SET @exists_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND CONSTRAINT_NAME = 'fk_rr_payout_decider'
);
SET @sql_stmt := IF(@exists_fk > 0,
    'ALTER TABLE refund_requests DROP FOREIGN KEY fk_rr_payout_decider',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 6. Drop payout_decision_note
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'payout_decision_note'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN payout_decision_note',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 7. Drop payout_decided_at
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'payout_decided_at'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN payout_decided_at',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 8. Drop payout_decided_by
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'payout_decided_by'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN payout_decided_by',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
