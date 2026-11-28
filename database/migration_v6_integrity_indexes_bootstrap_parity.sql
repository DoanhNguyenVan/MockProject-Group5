-- =============================================================================
-- Migration v6: Schema Integrity, Performance Indexes, Single-Default Bank Lock,
-- Financial/Quantity CHECK Constraints & Bootstrap Parity (Idempotent)
-- Target: MySQL 8.0+ (Uses DATABASE() + information_schema; zero USE box_cinema)
-- Resolution 3: Preserves existing ON DELETE CASCADE on child tables used by
--               automated test teardowns; enforces safe soft-deactivation in app.
-- =============================================================================

SET NAMES utf8mb4;

-- =============================================================================
-- 1. Bootstrap Parity Columns (no-op on live DBs where already present)
-- =============================================================================

-- 1a. booking_qr.used_at
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_qr' AND COLUMN_NAME = 'used_at'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE booking_qr ADD COLUMN used_at DATETIME NULL AFTER expires_at',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1b. booking_qr.revoked_at
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_qr' AND COLUMN_NAME = 'revoked_at'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE booking_qr ADD COLUMN revoked_at DATETIME NULL AFTER used_at',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1c. booking_qr.ticket_url
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_qr' AND COLUMN_NAME = 'ticket_url'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE booking_qr ADD COLUMN ticket_url VARCHAR(500) NULL AFTER revoked_at',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1d. booking_qr.qr_image_path
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_qr' AND COLUMN_NAME = 'qr_image_path'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE booking_qr ADD COLUMN qr_image_path VARCHAR(500) NULL AFTER ticket_url',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1e. seat_types.shape_type
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'seat_types' AND COLUMN_NAME = 'shape_type'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE seat_types ADD COLUMN shape_type VARCHAR(30) NULL DEFAULT ''standard'' AFTER price_surcharge',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1f. seats.pair_id
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'seats' AND COLUMN_NAME = 'pair_id'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE seats ADD COLUMN pair_id VARCHAR(50) NULL AFTER seat_number',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 2. Safe Non-Destructive Data Normalization Before Adding FKs / UNIQUE Keys
-- =============================================================================

-- Backfill any NULL tickets.booking_id from booking_seats
UPDATE tickets t
JOIN booking_seats bs ON t.booking_seat_id = bs.booking_seat_id
SET t.booking_id = bs.booking_id
WHERE t.booking_id IS NULL;

-- Clear any dangling variant_id references in booking_food_item before adding fk_bfi_variant
UPDATE booking_food_item bfi
LEFT JOIN food_variant fv ON bfi.variant_id = fv.id
SET bfi.variant_id = NULL
WHERE bfi.variant_id IS NOT NULL AND fv.id IS NULL;

-- Ensure at most one is_default = 1 row per user in customer_bank_accounts (keep latest updated_at, highest id)
UPDATE customer_bank_accounts c
JOIN (
    SELECT user_id,
           SUBSTRING_INDEX(GROUP_CONCAT(id ORDER BY updated_at DESC, id DESC), ',', 1) + 0 AS keep_id
    FROM customer_bank_accounts
    WHERE is_default = 1
    GROUP BY user_id
    HAVING COUNT(*) > 1
) dup ON dup.user_id = c.user_id
SET c.is_default = 0
WHERE c.is_default = 1 AND c.id <> dup.keep_id;

-- =============================================================================
-- 3. Critical Missing Indexes & Foreign Keys
-- =============================================================================

-- 3a. tickets.idx_tickets_booking_id (eliminates full-table scan & lock in findByBookingIdForUpdate)
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND INDEX_NAME = 'idx_tickets_booking_id'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE tickets ADD INDEX idx_tickets_booking_id (booking_id)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3b. tickets.idx_tickets_showtime_seat
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND INDEX_NAME = 'idx_tickets_showtime_seat'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE tickets ADD INDEX idx_tickets_showtime_seat (showtime_id, seat_id)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3c. tickets.fk_tickets_booking (ON DELETE CASCADE per Resolution 3)
SET @exists_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND CONSTRAINT_NAME = 'fk_tickets_booking'
);
SET @sql_stmt := IF(@exists_fk = 0,
    'ALTER TABLE tickets ADD CONSTRAINT fk_tickets_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3d. email_outbox.idx_email_outbox_poll (optimizes 5s FOR UPDATE SKIP LOCKED worker polling)
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'email_outbox' AND INDEX_NAME = 'idx_email_outbox_poll'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE email_outbox ADD INDEX idx_email_outbox_poll (status, retry_count, created_at, id)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3e. payment_webhook_event.idx_pwe_order_code
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_webhook_event' AND INDEX_NAME = 'idx_pwe_order_code'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE payment_webhook_event ADD INDEX idx_pwe_order_code (order_code)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3f. payment_webhook_event.idx_pwe_payment_id
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_webhook_event' AND INDEX_NAME = 'idx_pwe_payment_id'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE payment_webhook_event ADD INDEX idx_pwe_payment_id (payment_id)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3g. bookings.idx_bookings_status_deadline (optimizes 5s SeatHoldCleanupService polling)
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND INDEX_NAME = 'idx_bookings_status_deadline'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE bookings ADD INDEX idx_bookings_status_deadline (booking_status, payment_deadline)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3h. bookings.idx_bookings_promo_user_status (optimizes PromotionRepository usage-count queries)
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND INDEX_NAME = 'idx_bookings_promo_user_status'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE bookings ADD INDEX idx_bookings_promo_user_status (promotion_id, user_id, booking_status)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3i. refund_requests.idx_rr_customer_created
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND INDEX_NAME = 'idx_rr_customer_created'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE refund_requests ADD INDEX idx_rr_customer_created (customer_user_id, created_at)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3j. refund_requests.idx_rr_saved_bank
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND INDEX_NAME = 'idx_rr_saved_bank'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE refund_requests ADD INDEX idx_rr_saved_bank (saved_bank_account_id)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3k. booking_food_item.fk_bfi_variant
SET @exists_fk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_food_item' AND CONSTRAINT_NAME = 'fk_bfi_variant'
);
SET @sql_stmt := IF(@exists_fk = 0,
    'ALTER TABLE booking_food_item ADD CONSTRAINT fk_bfi_variant FOREIGN KEY (variant_id) REFERENCES food_variant(id) ON DELETE SET NULL',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 4. Single-Default Customer Bank Account Concurrency Lock
-- =============================================================================

-- 4a. customer_bank_accounts.default_user_lock (virtual generated column: user_id when is_default=1 else NULL;
--     VIRTUAL is required by MySQL 8 InnoDB when referencing FK column user_id with ON DELETE CASCADE)
SET @exists_col := (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_bank_accounts' AND COLUMN_NAME = 'default_user_lock'
);
SET @sql_stmt := IF(@exists_col = 0,
    'ALTER TABLE customer_bank_accounts ADD COLUMN default_user_lock BIGINT GENERATED ALWAYS AS (IF(is_default = 1, user_id, NULL)) VIRTUAL AFTER is_default',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4b. customer_bank_accounts.uk_cba_single_default
SET @exists_idx := (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'customer_bank_accounts' AND INDEX_NAME = 'uk_cba_single_default'
);
SET @sql_stmt := IF(@exists_idx = 0,
    'ALTER TABLE customer_bank_accounts ADD UNIQUE KEY uk_cba_single_default (default_user_lock)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- =============================================================================
-- 5. Non-Destructive Financial & Quantity CHECK Constraints
-- =============================================================================

-- 5a. payments.chk_payments_amount_pos
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND CONSTRAINT_NAME = 'chk_payments_amount_pos'
);
SET @sql_stmt := IF(@exists_chk = 0,
    'ALTER TABLE payments ADD CONSTRAINT chk_payments_amount_pos CHECK (amount > 0)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5b. payos_payout_transactions.chk_ppt_amount_pos
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'payos_payout_transactions' AND CONSTRAINT_NAME = 'chk_ppt_amount_pos'
);
SET @sql_stmt := IF(@exists_chk = 0,
    'ALTER TABLE payos_payout_transactions ADD CONSTRAINT chk_ppt_amount_pos CHECK (amount > 0)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5c. bookings.chk_bookings_amounts_nonneg
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND CONSTRAINT_NAME = 'chk_bookings_amounts_nonneg'
);
SET @sql_stmt := IF(@exists_chk = 0,
    'ALTER TABLE bookings ADD CONSTRAINT chk_bookings_amounts_nonneg CHECK (ticket_subtotal >= 0 AND food_subtotal >= 0 AND subtotal >= 0 AND discount_amount >= 0 AND service_fee >= 0 AND total_amount >= 0)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5d. food_inventory.chk_food_inv_nonneg
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'food_inventory' AND CONSTRAINT_NAME = 'chk_food_inv_nonneg'
);
SET @sql_stmt := IF(@exists_chk = 0,
    'ALTER TABLE food_inventory ADD CONSTRAINT chk_food_inv_nonneg CHECK (on_hand_quantity >= 0 AND reserved_quantity >= 0)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5e. refund_requests.chk_rr_totals_nonneg
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_requests' AND CONSTRAINT_NAME = 'chk_rr_totals_nonneg'
);
SET @sql_stmt := IF(@exists_chk = 0,
    'ALTER TABLE refund_requests ADD CONSTRAINT chk_rr_totals_nonneg CHECK (requested_cash_total >= 0 AND approved_cash_total >= 0 AND approved_voucher_total >= 0)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5f. refund_request_items.chk_rri_qty_bounds
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'refund_request_items' AND CONSTRAINT_NAME = 'chk_rri_qty_bounds'
);
SET @sql_stmt := IF(@exists_chk = 0,
    'ALTER TABLE refund_request_items ADD CONSTRAINT chk_rri_qty_bounds CHECK (purchased_quantity > 0 AND requested_quantity > 0 AND approved_quantity >= 0 AND approved_quantity <= requested_quantity)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5g. showtimes.chk_showtimes_window
SET @exists_chk := (
    SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'showtimes' AND CONSTRAINT_NAME = 'chk_showtimes_window'
);
SET @sql_stmt := IF(@exists_chk = 0,
    'ALTER TABLE showtimes ADD CONSTRAINT chk_showtimes_window CHECK (end_at > start_at AND base_price >= 0)',
    'SELECT 1');
PREPARE stmt FROM @sql_stmt; EXECUTE stmt; DEALLOCATE PREPARE stmt;
