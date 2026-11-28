-- =============================================================================
-- Rollback v6: Cleanly reverts migration_v6_integrity_indexes_bootstrap_parity.sql
-- in strict reverse dependency order (Idempotent; uses DATABASE() + information_schema)
-- Note: Bootstrap parity columns (booking_qr.*, seat_types.shape_type, seats.pair_id)
--       are intentionally preserved on rollback so live application data is never lost.
-- =============================================================================

SET NAMES utf8mb4;

-- =============================================================================
-- 1. Drop CHECK Constraints
-- =============================================================================

-- 1a. showtimes.chk_showtimes_window
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'showtimes' AND CONSTRAINT_NAME = 'chk_showtimes_window'
);
SET @sql_stmt := IF(@exists_chk > 0,
    'ALTER TABLE showtimes DROP CHECK chk_showtimes_window',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1b. refund_request_items.chk_rri_qty_bounds
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_request_items' AND CONSTRAINT_NAME = 'chk_rri_qty_bounds'
);
SET @sql_stmt := IF(@exists_chk > 0,
    'ALTER TABLE refund_request_items DROP CHECK chk_rri_qty_bounds',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1c. refund_requests.chk_rr_totals_nonneg
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND CONSTRAINT_NAME = 'chk_rr_totals_nonneg'
);
SET @sql_stmt := IF(@exists_chk > 0,
    'ALTER TABLE refund_requests DROP CHECK chk_rr_totals_nonneg',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1d. food_inventory.chk_food_inv_nonneg
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'food_inventory' AND CONSTRAINT_NAME = 'chk_food_inv_nonneg'
);
SET @sql_stmt := IF(@exists_chk > 0,
    'ALTER TABLE food_inventory DROP CHECK chk_food_inv_nonneg',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1e. bookings.chk_bookings_amounts_nonneg
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND CONSTRAINT_NAME = 'chk_bookings_amounts_nonneg'
);
SET @sql_stmt := IF(@exists_chk > 0,
    'ALTER TABLE bookings DROP CHECK chk_bookings_amounts_nonneg',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1f. payos_payout_transactions.chk_ppt_amount_pos
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'payos_payout_transactions' AND CONSTRAINT_NAME = 'chk_ppt_amount_pos'
);
SET @sql_stmt := IF(@exists_chk > 0,
    'ALTER TABLE payos_payout_transactions DROP CHECK chk_ppt_amount_pos',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1g. payments.chk_payments_amount_pos
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND CONSTRAINT_NAME = 'chk_payments_amount_pos'
);
SET @sql_stmt := IF(@exists_chk > 0,
    'ALTER TABLE payments DROP CHECK chk_payments_amount_pos',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 2. Drop Single-Default Bank Account Lock
-- =============================================================================

-- 2a. Drop UNIQUE KEY uk_cba_single_default
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_bank_accounts' AND INDEX_NAME = 'uk_cba_single_default'
);
SET @sql_stmt := IF(@exists_idx > 0,
    'ALTER TABLE customer_bank_accounts DROP INDEX uk_cba_single_default',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2b. Drop generated column default_user_lock
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_bank_accounts' AND COLUMN_NAME = 'default_user_lock'
);
SET @sql_stmt := IF(@exists_col > 0,
    'ALTER TABLE customer_bank_accounts DROP COLUMN default_user_lock',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 3. Drop Foreign Keys & Secondary Indexes (reverse dependency order)
-- =============================================================================

-- 3a. booking_food_item.fk_bfi_variant (+ backing index if present)
SET @exists_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_food_item' AND CONSTRAINT_NAME = 'fk_bfi_variant'
);
SET @sql_stmt := IF(@exists_fk > 0,
    'ALTER TABLE booking_food_item DROP FOREIGN KEY fk_bfi_variant',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_food_item' AND INDEX_NAME = 'fk_bfi_variant'
);
SET @sql_stmt := IF(@exists_idx > 0,
    'ALTER TABLE booking_food_item DROP INDEX fk_bfi_variant',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3b. refund_requests.idx_rr_saved_bank (preserve v5 fk_rr_saved_bank backing index if MySQL rebound it)
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND INDEX_NAME = 'idx_rr_saved_bank'
);
SET @exists_fk_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND INDEX_NAME = 'fk_rr_saved_bank'
);
SET @sql_stmt := IF(@exists_idx > 0 AND @exists_fk_idx = 0,
    'ALTER TABLE refund_requests ADD INDEX fk_rr_saved_bank (saved_bank_account_id), DROP INDEX idx_rr_saved_bank',
    IF(@exists_idx > 0,
        'ALTER TABLE refund_requests DROP INDEX idx_rr_saved_bank',
        'SELECT 1'));
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3c. refund_requests.idx_rr_customer_created (preserve fk_rr_customer backing index if needed)
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND INDEX_NAME = 'idx_rr_customer_created'
);
SET @exists_fk_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND INDEX_NAME = 'fk_rr_customer'
);
SET @sql_stmt := IF(@exists_idx > 0 AND @exists_fk_idx = 0,
    'ALTER TABLE refund_requests ADD INDEX fk_rr_customer (customer_user_id), DROP INDEX idx_rr_customer_created',
    IF(@exists_idx > 0,
        'ALTER TABLE refund_requests DROP INDEX idx_rr_customer_created',
        'SELECT 1'));
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3d. bookings.idx_bookings_promo_user_status (preserve fk_bookings_promotion backing index if needed)
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND INDEX_NAME = 'idx_bookings_promo_user_status'
);
SET @exists_fk_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND INDEX_NAME = 'fk_bookings_promotion'
);
SET @sql_stmt := IF(@exists_idx > 0 AND @exists_fk_idx = 0,
    'ALTER TABLE bookings ADD INDEX fk_bookings_promotion (promotion_id), DROP INDEX idx_bookings_promo_user_status',
    IF(@exists_idx > 0,
        'ALTER TABLE bookings DROP INDEX idx_bookings_promo_user_status',
        'SELECT 1'));
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3e. bookings.idx_bookings_status_deadline
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND INDEX_NAME = 'idx_bookings_status_deadline'
);
SET @sql_stmt := IF(@exists_idx > 0,
    'ALTER TABLE bookings DROP INDEX idx_bookings_status_deadline',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3f. payment_webhook_event.idx_pwe_payment_id
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_webhook_event' AND INDEX_NAME = 'idx_pwe_payment_id'
);
SET @sql_stmt := IF(@exists_idx > 0,
    'ALTER TABLE payment_webhook_event DROP INDEX idx_pwe_payment_id',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3g. payment_webhook_event.idx_pwe_order_code
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_webhook_event' AND INDEX_NAME = 'idx_pwe_order_code'
);
SET @sql_stmt := IF(@exists_idx > 0,
    'ALTER TABLE payment_webhook_event DROP INDEX idx_pwe_order_code',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3h. email_outbox.idx_email_outbox_poll
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'email_outbox' AND INDEX_NAME = 'idx_email_outbox_poll'
);
SET @sql_stmt := IF(@exists_idx > 0,
    'ALTER TABLE email_outbox DROP INDEX idx_email_outbox_poll',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3i. tickets.fk_tickets_booking (MUST drop before idx_tickets_booking_id)
SET @exists_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND CONSTRAINT_NAME = 'fk_tickets_booking'
);
SET @sql_stmt := IF(@exists_fk > 0,
    'ALTER TABLE tickets DROP FOREIGN KEY fk_tickets_booking',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3j. tickets.idx_tickets_showtime_seat
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND INDEX_NAME = 'idx_tickets_showtime_seat'
);
SET @sql_stmt := IF(@exists_idx > 0,
    'ALTER TABLE tickets DROP INDEX idx_tickets_showtime_seat',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3k. tickets.idx_tickets_booking_id
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND INDEX_NAME = 'idx_tickets_booking_id'
);
SET @sql_stmt := IF(@exists_idx > 0,
    'ALTER TABLE tickets DROP INDEX idx_tickets_booking_id',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
