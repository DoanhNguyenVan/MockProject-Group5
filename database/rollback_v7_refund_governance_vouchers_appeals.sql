-- =============================================================================
-- Rollback v7: Cleanly reverts migration_v7_refund_governance_vouchers_appeals.sql
-- in strict reverse dependency order (Idempotent; uses DATABASE() + information_schema)
-- =============================================================================

SET NAMES utf8mb4;

-- =============================================================================
-- 1. Drop Stage 2/3 Governance & Stage 1 Voucher Tables (reverse dependency order)
-- =============================================================================

DROP TABLE IF EXISTS payout_reconciliation_logs;
DROP TABLE IF EXISTS payout_fee_assessments;
DROP TABLE IF EXISTS refund_evidence_files;
DROP TABLE IF EXISTS refund_appeal_history;
DROP TABLE IF EXISTS refund_appeals;
DROP TABLE IF EXISTS refund_limit_overrides;
DROP TABLE IF EXISTS refund_limit_reservations;
DROP TABLE IF EXISTS refund_limit_buckets;
DROP TABLE IF EXISTS refund_limit_config_history;
DROP TABLE IF EXISTS refund_limit_configs;
DROP TABLE IF EXISTS order_voucher_allocations;
DROP TABLE IF EXISTS order_voucher_redemptions;

-- =============================================================================
-- 2. Drop Added Index & Columns from refund_requests
-- =============================================================================

SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND INDEX_NAME = 'idx_rr_workflow_status'
);
SET @sql_stmt := IF(@exists_idx > 0,
    'ALTER TABLE refund_requests DROP INDEX idx_rr_workflow_status',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'limit_override_at'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN limit_override_at',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'limit_override_reason'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN limit_override_reason',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'limit_override_by'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN limit_override_by',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'limit_override_applied'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN limit_override_applied',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'recipient_snapshot_hash'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN recipient_snapshot_hash',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'recipient_snapshot_json'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN recipient_snapshot_json',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'payout_blocked_reason'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN payout_blocked_reason',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'fee_amount_vnd'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN fee_amount_vnd',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'fee_assessment_status'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN fee_assessment_status',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'deadline_exception_at'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN deadline_exception_at',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'deadline_exception_reason'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN deadline_exception_reason',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'deadline_exception_by'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN deadline_exception_by',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'deadline_exceeded'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN deadline_exceeded',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'deadline_basis'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN deadline_basis',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'refund_deadline_at'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN refund_deadline_at',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'original_payment_ref'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN original_payment_ref',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'original_payment_id'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN original_payment_id',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'refund_method'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN refund_method',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'workflow_status'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE refund_requests DROP COLUMN workflow_status',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 3. Revert fk_rr_incident on refund_requests & Drop Added Columns on incident_reports
-- =============================================================================

SET @fk_rr_inc_restrict := (
    SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'refund_requests'
      AND CONSTRAINT_NAME = 'fk_rr_incident'
      AND DELETE_RULE = 'RESTRICT'
);
SET @sql_stmt := IF(@fk_rr_inc_restrict > 0,
    'ALTER TABLE refund_requests DROP FOREIGN KEY fk_rr_incident',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND CONSTRAINT_NAME = 'fk_rr_incident'
);
SET @sql_stmt := IF(@exists_fk = 0,
    'ALTER TABLE refund_requests ADD CONSTRAINT fk_rr_incident FOREIGN KEY (incident_report_id) REFERENCES incident_reports(id) ON DELETE SET NULL',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'incident_reports' AND CONSTRAINT_NAME = 'fk_ir_updated_by'
);
SET @sql_stmt := IF(@exists_fk > 0,
    'ALTER TABLE incident_reports DROP FOREIGN KEY fk_ir_updated_by',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'incident_reports' AND INDEX_NAME = 'fk_ir_updated_by'
);
SET @sql_stmt := IF(@exists_idx > 0,
    'ALTER TABLE incident_reports DROP INDEX fk_ir_updated_by',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'incident_reports' AND COLUMN_NAME = 'updated_by'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE incident_reports DROP COLUMN updated_by',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'incident_reports' AND COLUMN_NAME = 'customer_notified_at'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE incident_reports DROP COLUMN customer_notified_at',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
