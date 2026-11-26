-- =============================================================================
-- Migration v7: Refund Governance, One-Voucher-Per-Order Ledger & Allocations,
--               Refund Deadlines, Financial Limit Buckets/Reservations/Overrides,
--               Customer Refund Appeals, Evidence File Security & Fee Assessments
-- Target: MySQL 8.0+ (Idempotent; uses DATABASE() + information_schema; zero USE)
-- =============================================================================

SET NAMES utf8mb4;

-- =============================================================================
-- 1. Order Voucher Redemptions (Enforces ONE VOUCHER PER ORDER via uk_ovr_booking_id)
-- =============================================================================

CREATE TABLE IF NOT EXISTS order_voucher_redemptions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    promotion_id BIGINT NOT NULL,
    voucher_code VARCHAR(50) NOT NULL,
    discount_type VARCHAR(20) NOT NULL,
    discount_value DECIMAL(12,2) NOT NULL,
    max_discount DECIMAL(12,2) NULL,
    eligible_amount BIGINT NOT NULL DEFAULT 0,
    actual_discount BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'APPLIED',
    redeemed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    restored_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT uk_ovr_booking_id UNIQUE (booking_id),
    CONSTRAINT fk_ovr_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_ovr_promotion FOREIGN KEY (promotion_id) REFERENCES promotions(promotion_id) ON DELETE RESTRICT,
    INDEX idx_ovr_promo_status (promotion_id, status),
    INDEX idx_ovr_voucher_code (voucher_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Safe non-destructive backfill from existing bookings with promotion_id IS NOT NULL
INSERT IGNORE INTO order_voucher_redemptions (
    booking_id,
    promotion_id,
    voucher_code,
    discount_type,
    discount_value,
    max_discount,
    eligible_amount,
    actual_discount,
    status,
    redeemed_at,
    restored_at
)
SELECT
    b.booking_id,
    b.promotion_id,
    COALESCE(NULLIF(TRIM(b.voucher_code_snapshot), ''), p.voucher_code, CONCAT('PROMO-', b.promotion_id)),
    COALESCE(NULLIF(TRIM(b.promotion_discount_type_snapshot), ''), p.discount_type, 'FIXED'),
    COALESCE(b.promotion_discount_value_snapshot, p.discount_value, b.discount_amount, 0.00),
    COALESCE(b.promotion_max_discount_snapshot, p.max_discount),
    CAST(ROUND(COALESCE(b.subtotal, 0), 0) AS SIGNED),
    CAST(ROUND(COALESCE(b.discount_amount, 0), 0) AS SIGNED),
    CASE
        WHEN b.booking_status IN ('CANCELLED', 'EXPIRED') THEN 'CANCELLED'
        WHEN b.booking_status = 'REFUNDED' THEN 'RESTORED'
        WHEN b.booking_status = 'PARTIALLY_REFUNDED' THEN 'PARTIALLY_RESTORED'
        ELSE 'APPLIED'
    END,
    COALESCE(b.confirmed_at, b.booked_at, CURRENT_TIMESTAMP),
    CASE
        WHEN b.booking_status IN ('REFUNDED', 'PARTIALLY_REFUNDED') THEN b.updated_at
        ELSE NULL
    END
FROM bookings b
JOIN promotions p ON p.promotion_id = b.promotion_id
WHERE b.promotion_id IS NOT NULL;

-- =============================================================================
-- 2. Order Voucher Allocations (Per-Item / Per-Ticket Largest Remainder Ledger)
-- =============================================================================

CREATE TABLE IF NOT EXISTS order_voucher_allocations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    redemption_id BIGINT NOT NULL,
    booking_id BIGINT NOT NULL,
    item_kind VARCHAR(30) NOT NULL,
    item_ref_key VARCHAR(80) NOT NULL,
    ticket_id BIGINT NULL,
    booking_seat_id BIGINT NULL,
    booking_food_item_id BIGINT NULL,
    booking_food_item_component_id BIGINT NULL,
    gross_amount BIGINT NOT NULL DEFAULT 0,
    eligible_amount BIGINT NOT NULL DEFAULT 0,
    allocated_discount BIGINT NOT NULL DEFAULT 0,
    net_paid_amount BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ova_redemption_item UNIQUE (redemption_id, item_ref_key),
    CONSTRAINT fk_ova_redemption FOREIGN KEY (redemption_id) REFERENCES order_voucher_redemptions(id) ON DELETE CASCADE,
    CONSTRAINT fk_ova_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_ova_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(ticket_id) ON DELETE SET NULL,
    CONSTRAINT fk_ova_seat FOREIGN KEY (booking_seat_id) REFERENCES booking_seats(booking_seat_id) ON DELETE SET NULL,
    CONSTRAINT fk_ova_food FOREIGN KEY (booking_food_item_id) REFERENCES booking_food_item(id) ON DELETE SET NULL,
    CONSTRAINT fk_ova_comp FOREIGN KEY (booking_food_item_component_id) REFERENCES booking_food_item_component(id) ON DELETE SET NULL,
    INDEX idx_ova_booking (booking_id),
    INDEX idx_ova_ticket (ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Backfill ticket/seat allocations for existing redemptions
INSERT IGNORE INTO order_voucher_allocations (
    redemption_id,
    booking_id,
    item_kind,
    item_ref_key,
    ticket_id,
    booking_seat_id,
    booking_food_item_id,
    booking_food_item_component_id,
    gross_amount,
    eligible_amount,
    allocated_discount,
    net_paid_amount
)
SELECT
    ovr.id,
    ovr.booking_id,
    CASE WHEN t.ticket_id IS NOT NULL THEN 'TICKET' ELSE 'SEAT' END,
    CASE WHEN t.ticket_id IS NOT NULL THEN CONCAT('TICKET:', t.ticket_id) ELSE CONCAT('SEAT:', bs.booking_seat_id) END,
    t.ticket_id,
    bs.booking_seat_id,
    NULL,
    NULL,
    CAST(ROUND(COALESCE(NULLIF(bs.gross_price, 0), bs.unit_price, 0), 0) AS SIGNED),
    CAST(ROUND(COALESCE(NULLIF(bs.gross_price, 0), bs.unit_price, 0), 0) AS SIGNED),
    CAST(ROUND(COALESCE(bs.discount_allocated, 0), 0) AS SIGNED),
    CAST(ROUND(COALESCE(NULLIF(bs.net_paid_amount, 0), COALESCE(NULLIF(bs.gross_price, 0), bs.unit_price, 0) - COALESCE(bs.discount_allocated, 0)), 0) AS SIGNED)
FROM order_voucher_redemptions ovr
JOIN booking_seats bs ON bs.booking_id = ovr.booking_id
LEFT JOIN tickets t ON t.booking_seat_id = bs.booking_seat_id;

-- Backfill standalone food item allocations for existing redemptions
INSERT IGNORE INTO order_voucher_allocations (
    redemption_id,
    booking_id,
    item_kind,
    item_ref_key,
    ticket_id,
    booking_seat_id,
    booking_food_item_id,
    booking_food_item_component_id,
    gross_amount,
    eligible_amount,
    allocated_discount,
    net_paid_amount
)
SELECT
    ovr.id,
    ovr.booking_id,
    'FOOD_ITEM',
    CONCAT('FOOD:', bfi.id),
    NULL,
    NULL,
    bfi.id,
    NULL,
    CAST(ROUND(COALESCE(bfi.subtotal, 0), 0) AS SIGNED),
    CAST(ROUND(COALESCE(bfi.subtotal, 0), 0) AS SIGNED),
    CAST(ROUND(COALESCE(bfi.discount_allocated, 0), 0) AS SIGNED),
    CAST(ROUND(COALESCE(NULLIF(bfi.net_paid_amount, 0), COALESCE(bfi.subtotal, 0) - COALESCE(bfi.discount_allocated, 0)), 0) AS SIGNED)
FROM order_voucher_redemptions ovr
JOIN booking_food_item bfi ON bfi.booking_id = ovr.booking_id
WHERE bfi.item_type <> 'COMBO'
   OR NOT EXISTS (SELECT 1 FROM booking_food_item_component c WHERE c.booking_food_item_id = bfi.id);

-- Backfill combo component allocations for existing redemptions
INSERT IGNORE INTO order_voucher_allocations (
    redemption_id,
    booking_id,
    item_kind,
    item_ref_key,
    ticket_id,
    booking_seat_id,
    booking_food_item_id,
    booking_food_item_component_id,
    gross_amount,
    eligible_amount,
    allocated_discount,
    net_paid_amount
)
SELECT
    ovr.id,
    ovr.booking_id,
    'COMBO_COMPONENT',
    CONCAT('COMP:', comp.id),
    NULL,
    NULL,
    comp.booking_food_item_id,
    comp.id,
    CAST(ROUND(COALESCE(comp.allocated_subtotal, 0), 0) AS SIGNED),
    CAST(ROUND(COALESCE(comp.allocated_subtotal, 0), 0) AS SIGNED),
    CAST(ROUND(COALESCE(comp.discount_allocated, 0), 0) AS SIGNED),
    CAST(ROUND(COALESCE(NULLIF(comp.net_paid_amount, 0), COALESCE(comp.allocated_subtotal, 0) - COALESCE(comp.discount_allocated, 0)), 0) AS SIGNED)
FROM order_voucher_redemptions ovr
JOIN booking_food_item_component comp ON comp.booking_id = ovr.booking_id;

-- =============================================================================
-- 3. Incident Reports Governance Columns & Linked Refund Delete Restriction
-- =============================================================================

-- 3a. incident_reports.customer_notified_at
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'incident_reports' AND COLUMN_NAME = 'customer_notified_at'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE incident_reports ADD COLUMN customer_notified_at DATETIME NULL AFTER occurred_at',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3b. incident_reports.updated_by
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'incident_reports' AND COLUMN_NAME = 'updated_by'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE incident_reports ADD COLUMN updated_by BIGINT NULL AFTER final_conclusion',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3c. FK fk_ir_updated_by on incident_reports(updated_by) -> users(user_id)
SET @exists_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'incident_reports' AND CONSTRAINT_NAME = 'fk_ir_updated_by'
);
SET @sql_stmt := IF(@exists_fk = 0,
    'ALTER TABLE incident_reports ADD CONSTRAINT fk_ir_updated_by FOREIGN KEY (updated_by) REFERENCES users(user_id) ON DELETE SET NULL',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3d. Enforce ON DELETE RESTRICT on refund_requests.fk_rr_incident (if currently SET NULL or missing)
SET @fk_rr_inc_non_restrict := (
    SELECT COUNT(*) FROM information_schema.REFERENTIAL_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'refund_requests'
      AND CONSTRAINT_NAME = 'fk_rr_incident'
      AND DELETE_RULE <> 'RESTRICT'
);
SET @sql_stmt := IF(@fk_rr_inc_non_restrict > 0,
    'ALTER TABLE refund_requests DROP FOREIGN KEY fk_rr_incident',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND CONSTRAINT_NAME = 'fk_rr_incident'
);
SET @sql_stmt := IF(@exists_fk = 0,
    'ALTER TABLE refund_requests ADD CONSTRAINT fk_rr_incident FOREIGN KEY (incident_report_id) REFERENCES incident_reports(id) ON DELETE RESTRICT',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 4. Refund Requests Stage 1-3 Workflow, Deadline, Fee, Snapshot & Override Columns
-- =============================================================================

-- 4a. workflow_status
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'workflow_status'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN workflow_status VARCHAR(30) NOT NULL DEFAULT ''REQUESTED'' AFTER voucher_status',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4b. refund_method
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'refund_method'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN refund_method VARCHAR(30) NOT NULL DEFAULT ''BANK_PAYOUT'' AFTER workflow_status',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4c. original_payment_id
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'original_payment_id'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN original_payment_id BIGINT NULL AFTER refund_method',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4d. original_payment_ref
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'original_payment_ref'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN original_payment_ref VARCHAR(100) NULL AFTER original_payment_id',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4e. refund_deadline_at
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'refund_deadline_at'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN refund_deadline_at DATETIME NULL AFTER original_payment_ref',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4f. deadline_basis
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'deadline_basis'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN deadline_basis VARCHAR(40) NULL AFTER refund_deadline_at',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4g. deadline_exceeded
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'deadline_exceeded'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN deadline_exceeded TINYINT(1) NOT NULL DEFAULT 0 AFTER deadline_basis',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4h. deadline_exception_by
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'deadline_exception_by'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN deadline_exception_by BIGINT NULL AFTER deadline_exceeded',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4i. deadline_exception_reason
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'deadline_exception_reason'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN deadline_exception_reason TEXT NULL AFTER deadline_exception_by',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4j. deadline_exception_at
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'deadline_exception_at'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN deadline_exception_at DATETIME NULL AFTER deadline_exception_reason',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4k. fee_assessment_status
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'fee_assessment_status'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN fee_assessment_status VARCHAR(30) NULL AFTER payout_decision_note',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4l. fee_amount_vnd
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'fee_amount_vnd'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN fee_amount_vnd BIGINT NOT NULL DEFAULT 0 AFTER fee_assessment_status',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4m. payout_blocked_reason
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'payout_blocked_reason'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN payout_blocked_reason TEXT NULL AFTER fee_amount_vnd',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4n. recipient_snapshot_json
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'recipient_snapshot_json'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN recipient_snapshot_json TEXT NULL AFTER payout_blocked_reason',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4o. recipient_snapshot_hash
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'recipient_snapshot_hash'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN recipient_snapshot_hash VARCHAR(64) NULL AFTER recipient_snapshot_json',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4p. limit_override_applied
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'limit_override_applied'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN limit_override_applied TINYINT(1) NOT NULL DEFAULT 0 AFTER recipient_snapshot_hash',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4q. limit_override_by
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'limit_override_by'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN limit_override_by BIGINT NULL AFTER limit_override_applied',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4r. limit_override_reason
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'limit_override_reason'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN limit_override_reason TEXT NULL AFTER limit_override_by',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4s. limit_override_at
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND COLUMN_NAME = 'limit_override_at'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE refund_requests ADD COLUMN limit_override_at DATETIME NULL AFTER limit_override_reason',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4t. Index idx_rr_workflow_status
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND INDEX_NAME = 'idx_rr_workflow_status'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE refund_requests ADD INDEX idx_rr_workflow_status (workflow_status, cinema_id, created_at)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4u. Backfill workflow_status safely from existing request_status & payout_status
UPDATE refund_requests rr
SET rr.workflow_status = CASE
    WHEN rr.request_status = 'PAYOUT_BLOCKED' OR rr.payout_status = 'PAYOUT_BLOCKED' THEN 'PAYOUT_BLOCKED'
    WHEN rr.request_status = 'CANCELLED' OR rr.payout_status = 'CANCELLED' THEN 'CANCELLED'
    WHEN rr.request_status IN ('REJECTED', 'PAYOUT_REJECTED', 'INELIGIBLE') OR rr.payout_status = 'REJECTED' THEN 'REJECTED'
    WHEN rr.request_status IN ('COMPLETED', 'PAYOUT_SUCCEEDED') OR rr.payout_status = 'SUCCEEDED' THEN 'SUCCESS'
    WHEN rr.request_status = 'PAYOUT_FAILED' OR rr.payout_status = 'FAILED' THEN 'FAILED'
    WHEN rr.request_status = 'PAYOUT_PROCESSING' OR rr.payout_status = 'PROCESSING' THEN
        CASE
            WHEN EXISTS (
                SELECT 1 FROM payos_payout_transactions ppt
                WHERE ppt.refund_request_id = rr.id
                  AND ppt.transaction_state IN ('UNKNOWN_OUTCOME', 'MANUAL_RECONCILE_REQUIRED')
            ) THEN 'UNKNOWN'
            ELSE 'PROCESSING'
        END
    WHEN rr.request_status IN ('AWAITING_PAYOUT_APPROVAL', 'PAYOUT_APPROVED') OR rr.payout_status IN ('AWAITING_APPROVAL', 'PENDING') THEN 'PAYOUT_PENDING'
    WHEN rr.request_status IN ('APPROVED', 'PARTIALLY_APPROVED') THEN
        CASE WHEN rr.payout_status = 'NOT_REQUIRED' THEN 'APPROVED' ELSE 'PAYOUT_PENDING' END
    WHEN rr.request_status IN ('UNDER_VERIFICATION', 'MORE_INFO_REQUIRED', 'VERIFIED', 'ESCALATED_TO_ADMIN', 'UNDER_REVIEW') THEN 'UNDER_REVIEW'
    ELSE 'REQUESTED'
END;

-- =============================================================================
-- 5. Stage 2/3 Governance Tables (Financial Limits, Buckets, Reservations,
--    Overrides, Appeals, Evidence Files, Fee Assessments & Reconciliation Logs)
-- =============================================================================

-- 5a. refund_limit_configs
CREATE TABLE IF NOT EXISTS refund_limit_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(60) NOT NULL UNIQUE,
    limit_amount_vnd BIGINT NOT NULL,
    description VARCHAR(255) NULL,
    is_active TINYINT(1) NOT NULL DEFAULT 1,
    updated_by BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_rlc_updated_by FOREIGN KEY (updated_by) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO refund_limit_configs (config_key, limit_amount_vnd, description, is_active) VALUES
    ('MANAGER_PER_REQUEST', 500000, 'Max VND a Manager can approve for a single refund request without Admin override', 1),
    ('MANAGER_DAILY', 2000000, 'Max cumulative VND a single Manager can approve per Asia/Ho_Chi_Minh calendar day', 1),
    ('CINEMA_DAILY', 5000000, 'Max cumulative VND a Cinema branch can approve per Asia/Ho_Chi_Minh calendar day', 1),
    ('INCIDENT_CUMULATIVE', 10000000, 'Max cumulative VND approved across all refund requests linked to a single incident', 1);

-- 5b. refund_limit_config_history
CREATE TABLE IF NOT EXISTS refund_limit_config_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(60) NOT NULL,
    previous_amount_vnd BIGINT NULL,
    new_amount_vnd BIGINT NOT NULL,
    changed_by BIGINT NULL,
    reason TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rlch_changed_by FOREIGN KEY (changed_by) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_rlch_key_created (config_key, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5c. refund_limit_buckets (bucket_key PRIMARY KEY for deadlock-free sorted InnoDB row locking)
CREATE TABLE IF NOT EXISTS refund_limit_buckets (
    bucket_key VARCHAR(120) PRIMARY KEY,
    bucket_type VARCHAR(40) NOT NULL,
    scope_id BIGINT NOT NULL,
    quota_date DATE NULL,
    reserved_amount_vnd BIGINT NOT NULL DEFAULT 0,
    committed_amount_vnd BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_rlb_type_scope_date (bucket_type, scope_id, quota_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5d. refund_limit_reservations
CREATE TABLE IF NOT EXISTS refund_limit_reservations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_request_id BIGINT NOT NULL,
    manager_user_id BIGINT NULL,
    cinema_id BIGINT NOT NULL,
    incident_report_id BIGINT NULL,
    quota_date DATE NOT NULL,
    amount_vnd BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'RESERVED',
    active_lock_key VARCHAR(120) NULL UNIQUE,
    override_applied TINYINT(1) NOT NULL DEFAULT 0,
    reserved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    committed_at DATETIME NULL,
    released_at DATETIME NULL,
    release_reason VARCHAR(255) NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_rlr_rr FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_rlr_mgr FOREIGN KEY (manager_user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_rlr_cinema FOREIGN KEY (cinema_id) REFERENCES cinemas(cinema_id) ON DELETE RESTRICT,
    CONSTRAINT fk_rlr_incident FOREIGN KEY (incident_report_id) REFERENCES incident_reports(id) ON DELETE SET NULL,
    INDEX idx_rlr_rr_status (refund_request_id, status),
    INDEX idx_rlr_mgr_date (manager_user_id, quota_date, status),
    INDEX idx_rlr_cinema_date (cinema_id, quota_date, status),
    INDEX idx_rlr_incident (incident_report_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5e. refund_limit_overrides
CREATE TABLE IF NOT EXISTS refund_limit_overrides (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_request_id BIGINT NOT NULL,
    admin_user_id BIGINT NOT NULL,
    breached_limits_json TEXT NOT NULL,
    approved_amount_vnd BIGINT NOT NULL,
    override_reason TEXT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rlo_rr FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_rlo_admin FOREIGN KEY (admin_user_id) REFERENCES users(user_id) ON DELETE RESTRICT,
    INDEX idx_rlo_rr (refund_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5f. refund_appeals
CREATE TABLE IF NOT EXISTS refund_appeals (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    appeal_code VARCHAR(40) NOT NULL UNIQUE,
    refund_request_id BIGINT NOT NULL,
    customer_user_id BIGINT NULL,
    purchase_email VARCHAR(255) NOT NULL,
    appeal_reason TEXT NOT NULL,
    evidence_url VARCHAR(500) NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'APPEAL_SUBMITTED',
    active_appeal_lock BIGINT NULL UNIQUE,
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME NULL,
    decision_note TEXT NULL,
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ra_rr FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_ra_customer FOREIGN KEY (customer_user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_ra_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_ra_rr_status (refund_request_id, status),
    INDEX idx_ra_status_submitted (status, submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5g. refund_appeal_history
CREATE TABLE IF NOT EXISTS refund_appeal_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    appeal_id BIGINT NOT NULL,
    refund_request_id BIGINT NOT NULL,
    actor_user_id BIGINT NULL,
    actor_role VARCHAR(30) NOT NULL,
    action VARCHAR(50) NOT NULL,
    previous_status VARCHAR(30) NULL,
    new_status VARCHAR(30) NOT NULL,
    note TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rah2_appeal FOREIGN KEY (appeal_id) REFERENCES refund_appeals(id) ON DELETE CASCADE,
    CONSTRAINT fk_rah2_rr FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_rah2_actor FOREIGN KEY (actor_user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_rah2_appeal_created (appeal_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5h. refund_evidence_files
CREATE TABLE IF NOT EXISTS refund_evidence_files (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    storage_name VARCHAR(120) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(80) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    sha256_hash CHAR(64) NOT NULL,
    scan_status VARCHAR(30) NOT NULL DEFAULT 'CLEAN',
    scan_details VARCHAR(255) NULL,
    refund_request_id BIGINT NULL,
    incident_report_id BIGINT NULL,
    uploaded_by_user_id BIGINT NULL,
    uploaded_by_email VARCHAR(255) NULL,
    uploaded_by_ip VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ref_rr FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE SET NULL,
    CONSTRAINT fk_ref_ir FOREIGN KEY (incident_report_id) REFERENCES incident_reports(id) ON DELETE SET NULL,
    CONSTRAINT fk_ref_user FOREIGN KEY (uploaded_by_user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_ref_rr (refund_request_id),
    INDEX idx_ref_ir (incident_report_id),
    INDEX idx_ref_sha256 (sha256_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5i. payout_fee_assessments
CREATE TABLE IF NOT EXISTS payout_fee_assessments (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_request_id BIGINT NOT NULL,
    payout_transaction_id BIGINT NULL,
    provider_name VARCHAR(40) NOT NULL DEFAULT 'PAYOS',
    fee_mode VARCHAR(30) NOT NULL,
    approved_refund_vnd BIGINT NOT NULL,
    estimated_fee_vnd BIGINT NOT NULL DEFAULT 0,
    net_recipient_vnd BIGINT NOT NULL,
    total_debit_vnd BIGINT NOT NULL,
    guaranteed_full_receipt TINYINT(1) NOT NULL DEFAULT 0,
    blocked TINYINT(1) NOT NULL DEFAULT 0,
    block_reason TEXT NULL,
    assessed_by BIGINT NULL,
    assessed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_pfa_rr FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_pfa_ppt FOREIGN KEY (payout_transaction_id) REFERENCES payos_payout_transactions(id) ON DELETE SET NULL,
    CONSTRAINT fk_pfa_assessor FOREIGN KEY (assessed_by) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_pfa_rr (refund_request_id, assessed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5j. payout_reconciliation_logs
CREATE TABLE IF NOT EXISTS payout_reconciliation_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payout_transaction_id BIGINT NOT NULL,
    refund_request_id BIGINT NOT NULL,
    trigger_source VARCHAR(30) NOT NULL,
    previous_state VARCHAR(30) NULL,
    new_state VARCHAR(30) NOT NULL,
    provider_code VARCHAR(30) NULL,
    provider_desc VARCHAR(255) NULL,
    actor_user_id BIGINT NULL,
    details_json TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_prl_ppt FOREIGN KEY (payout_transaction_id) REFERENCES payos_payout_transactions(id) ON DELETE CASCADE,
    CONSTRAINT fk_prl_rr FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_prl_actor FOREIGN KEY (actor_user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_prl_ppt_created (payout_transaction_id, created_at),
    INDEX idx_prl_rr_created (refund_request_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
