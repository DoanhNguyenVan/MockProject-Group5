-- =====================================================================
-- Migration v4: Two-Stage Refund Verification, Admin Payout Approval,
-- Batch PayOS Payout Grouping & Composite Queue Indexes (Idempotent)
-- Target: MySQL 8.0+ (Zero new redundant tables; reuses existing tables)
-- =====================================================================

-- 1. refund_requests.payout_decided_by
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'payout_decided_by'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN payout_decided_by BIGINT NULL AFTER decision_note',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. refund_requests.payout_decided_at
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'payout_decided_at'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN payout_decided_at DATETIME NULL AFTER payout_decided_by',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. refund_requests.payout_decision_note
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'payout_decision_note'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN payout_decision_note TEXT NULL AFTER payout_decided_at',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. FK fk_rr_payout_decider on refund_requests(payout_decided_by) -> users(user_id)
SET @exists_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND CONSTRAINT_NAME = 'fk_rr_payout_decider'
);
SET @sql_stmt := IF(@exists_fk = 0,
    'ALTER TABLE refund_requests ADD CONSTRAINT fk_rr_payout_decider FOREIGN KEY (payout_decided_by) REFERENCES users(user_id) ON DELETE SET NULL',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5. Composite index idx_rr_payout_queue on refund_requests(request_status, payout_status, created_at)
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND INDEX_NAME = 'idx_rr_payout_queue'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE refund_requests ADD INDEX idx_rr_payout_queue (request_status, payout_status, created_at)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 6. payos_payout_transactions.batch_code
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payos_payout_transactions' AND COLUMN_NAME = 'batch_code'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE payos_payout_transactions ADD COLUMN batch_code VARCHAR(40) NULL AFTER reference_id',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 7. Index idx_ppt_batch_code on payos_payout_transactions(batch_code)
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payos_payout_transactions' AND INDEX_NAME = 'idx_ppt_batch_code'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE payos_payout_transactions ADD INDEX idx_ppt_batch_code (batch_code)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 8. Index idx_ppt_payos_payout_id on payos_payout_transactions(payos_payout_id)
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payos_payout_transactions' AND INDEX_NAME = 'idx_ppt_payos_payout_id'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE payos_payout_transactions ADD INDEX idx_ppt_payos_payout_id (payos_payout_id)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 9. Safe non-destructive backfill for historical SUCCEEDED payout rows
UPDATE refund_requests rr
JOIN (
    SELECT refund_request_id, MAX(initiated_by) AS initiated_by, MAX(initiated_at) AS initiated_at
    FROM payos_payout_transactions
    WHERE status = 'SUCCEEDED'
    GROUP BY refund_request_id
) ppt ON ppt.refund_request_id = rr.id
SET rr.payout_decided_by = COALESCE(rr.payout_decided_by, ppt.initiated_by, rr.reviewed_by),
    rr.payout_decided_at = COALESCE(rr.payout_decided_at, ppt.initiated_at, rr.reviewed_at)
WHERE rr.payout_status = 'SUCCEEDED'
  AND rr.payout_decided_by IS NULL;
