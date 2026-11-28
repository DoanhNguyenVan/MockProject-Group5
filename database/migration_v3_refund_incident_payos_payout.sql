SET NAMES utf8mb4;

-- ============================================================================
-- Migration V3: Purchase Snapshot, Immutable Delivery History, Incidents,
-- Staff Error Logs, Refund Management, Automated PayOS Payout & Voucher Recovery
-- Idempotent & non-destructive (no DROP / no TRUNCATE)
-- ============================================================================

-- 1. Update bookings (allow food-only orders, track cinema_id & promotion snapshot)
ALTER TABLE bookings MODIFY COLUMN showtime_id BIGINT NULL;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND COLUMN_NAME = 'cinema_id');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE bookings ADD COLUMN cinema_id BIGINT NULL AFTER showtime_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND CONSTRAINT_NAME = 'fk_bookings_cinema');
SET @sql := IF(@fk_exists = 0, 'ALTER TABLE bookings ADD CONSTRAINT fk_bookings_cinema FOREIGN KEY (cinema_id) REFERENCES cinemas(cinema_id) ON DELETE SET NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND COLUMN_NAME = 'order_type');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE bookings ADD COLUMN order_type VARCHAR(20) NOT NULL DEFAULT ''TICKET_ONLY'' AFTER booking_channel', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND COLUMN_NAME = 'promotion_discount_type_snapshot');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE bookings ADD COLUMN promotion_discount_type_snapshot VARCHAR(20) NULL AFTER voucher_code_snapshot', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND COLUMN_NAME = 'promotion_discount_value_snapshot');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE bookings ADD COLUMN promotion_discount_value_snapshot DECIMAL(12,2) NULL AFTER promotion_discount_type_snapshot', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND COLUMN_NAME = 'promotion_max_discount_snapshot');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE bookings ADD COLUMN promotion_max_discount_snapshot DECIMAL(12,2) NULL AFTER promotion_discount_value_snapshot', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND COLUMN_NAME = 'promotion_end_at_snapshot');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE bookings ADD COLUMN promotion_end_at_snapshot DATETIME NULL AFTER promotion_max_discount_snapshot', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND COLUMN_NAME = 'snapshot_review_required');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE bookings ADD COLUMN snapshot_review_required BOOLEAN NOT NULL DEFAULT FALSE AFTER promotion_end_at_snapshot', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. Update booking_seats snapshot columns
SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_seats' AND COLUMN_NAME = 'gross_price');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE booking_seats ADD COLUMN gross_price DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER unit_price', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_seats' AND COLUMN_NAME = 'discount_allocated');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE booking_seats ADD COLUMN discount_allocated DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER gross_price', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_seats' AND COLUMN_NAME = 'net_paid_amount');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE booking_seats ADD COLUMN net_paid_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER discount_allocated', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. Update tickets snapshot & refund status columns
SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND COLUMN_NAME = 'gross_price');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE tickets ADD COLUMN gross_price DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER price', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND COLUMN_NAME = 'discount_allocated');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE tickets ADD COLUMN discount_allocated DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER gross_price', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND COLUMN_NAME = 'net_paid_amount');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE tickets ADD COLUMN net_paid_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER discount_allocated', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND COLUMN_NAME = 'refund_status');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE tickets ADD COLUMN refund_status VARCHAR(30) NOT NULL DEFAULT ''NONE'' AFTER ticket_status', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4. Update booking_food_item snapshot & fulfillment tracking columns
SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_food_item' AND COLUMN_NAME = 'discount_allocated');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE booking_food_item ADD COLUMN discount_allocated DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER subtotal', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_food_item' AND COLUMN_NAME = 'net_paid_amount');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE booking_food_item ADD COLUMN net_paid_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER discount_allocated', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_food_item' AND COLUMN_NAME = 'delivered_quantity');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE booking_food_item ADD COLUMN delivered_quantity INT NOT NULL DEFAULT 0 AFTER net_paid_amount', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_food_item' AND COLUMN_NAME = 'refunded_quantity');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE booking_food_item ADD COLUMN refunded_quantity INT NOT NULL DEFAULT 0 AFTER delivered_quantity', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_food_item' AND COLUMN_NAME = 'replacement_accepted_quantity');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE booking_food_item ADD COLUMN replacement_accepted_quantity INT NOT NULL DEFAULT 0 AFTER refunded_quantity', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'booking_food_item' AND COLUMN_NAME = 'delivery_status');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE booking_food_item ADD COLUMN delivery_status VARCHAR(30) NOT NULL DEFAULT ''PENDING'' AFTER replacement_accepted_quantity', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5. New table: booking_food_item_component (Combo component breakdown snapshot)
CREATE TABLE IF NOT EXISTS booking_food_item_component (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_food_item_id BIGINT NOT NULL,
    booking_id BIGINT NOT NULL,
    food_item_id BIGINT NULL,
    variant_id BIGINT NULL,
    food_combo_item_id BIGINT NULL,
    component_name_snapshot VARCHAR(150) NOT NULL,
    variant_name_snapshot VARCHAR(100) NULL,
    quantity_per_combo INT NOT NULL DEFAULT 1,
    total_quantity INT NOT NULL,
    catalog_unit_price_snapshot DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    allocated_unit_price DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    allocated_subtotal DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    discount_allocated DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    net_paid_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    delivered_quantity INT NOT NULL DEFAULT 0,
    refunded_quantity INT NOT NULL DEFAULT 0,
    replacement_accepted_quantity INT NOT NULL DEFAULT 0,
    delivery_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_bfic_bfi FOREIGN KEY (booking_food_item_id) REFERENCES booking_food_item(id) ON DELETE CASCADE,
    CONSTRAINT fk_bfic_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_bfic_food_item FOREIGN KEY (food_item_id) REFERENCES food_item(id) ON DELETE SET NULL,
    CONSTRAINT fk_bfic_variant FOREIGN KEY (variant_id) REFERENCES food_variant(id) ON DELETE SET NULL,
    CONSTRAINT fk_bfic_combo_item FOREIGN KEY (food_combo_item_id) REFERENCES food_combo_item(id) ON DELETE SET NULL,
    INDEX idx_bfic_bfi (booking_food_item_id),
    INDEX idx_bfic_booking (booking_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. Update promotions (private refund-recovery vouchers)
SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'promotions' AND COLUMN_NAME = 'is_public');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE promotions ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT TRUE AFTER status', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'promotions' AND COLUMN_NAME = 'promotion_source');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE promotions ADD COLUMN promotion_source VARCHAR(30) NOT NULL DEFAULT ''CAMPAIGN'' AFTER is_public', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'promotions' AND COLUMN_NAME = 'target_user_id');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE promotions ADD COLUMN target_user_id BIGINT NULL AFTER promotion_source', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'promotions' AND CONSTRAINT_NAME = 'fk_promotions_target_user');
SET @sql := IF(@fk_exists = 0, 'ALTER TABLE promotions ADD CONSTRAINT fk_promotions_target_user FOREIGN KEY (target_user_id) REFERENCES users(user_id) ON DELETE SET NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'promotions' AND COLUMN_NAME = 'target_email');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE promotions ADD COLUMN target_email VARCHAR(255) NULL AFTER target_user_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 7. Update food_fulfillment & create immutable food_fulfillment_history
ALTER TABLE food_fulfillment MODIFY COLUMN status VARCHAR(30) NOT NULL DEFAULT 'QUEUED';

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'food_fulfillment' AND COLUMN_NAME = 'cinema_id');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE food_fulfillment ADD COLUMN cinema_id BIGINT NULL AFTER booking_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'food_fulfillment' AND CONSTRAINT_NAME = 'fk_ff_cinema');
SET @sql := IF(@fk_exists = 0, 'ALTER TABLE food_fulfillment ADD CONSTRAINT fk_ff_cinema FOREIGN KEY (cinema_id) REFERENCES cinemas(cinema_id) ON DELETE SET NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'food_fulfillment' AND COLUMN_NAME = 'updated_at');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE food_fulfillment ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER picked_up_at', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS food_fulfillment_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    food_fulfillment_id BIGINT NOT NULL,
    booking_id BIGINT NOT NULL,
    booking_food_item_id BIGINT NULL,
    booking_food_item_component_id BIGINT NULL,
    action_type VARCHAR(40) NOT NULL,
    previous_status VARCHAR(30) NULL,
    new_status VARCHAR(30) NOT NULL,
    quantity_affected INT NOT NULL DEFAULT 0,
    delivered_item_name_snapshot VARCHAR(150) NULL,
    wrong_item_description VARCHAR(255) NULL,
    replacement_description VARCHAR(255) NULL,
    customer_accepted_replacement BOOLEAN NULL,
    staff_id BIGINT NULL,
    note TEXT NULL,
    evidence_url VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ffh_ff FOREIGN KEY (food_fulfillment_id) REFERENCES food_fulfillment(id) ON DELETE CASCADE,
    CONSTRAINT fk_ffh_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_ffh_bfi FOREIGN KEY (booking_food_item_id) REFERENCES booking_food_item(id) ON DELETE SET NULL,
    CONSTRAINT fk_ffh_bfic FOREIGN KEY (booking_food_item_component_id) REFERENCES booking_food_item_component(id) ON DELETE SET NULL,
    CONSTRAINT fk_ffh_staff FOREIGN KEY (staff_id) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_ffh_ff_created (food_fulfillment_id, created_at),
    INDEX idx_ffh_booking_created (booking_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. Incident reporting, verification trail & isolated staff error logs
CREATE TABLE IF NOT EXISTS incident_reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_code VARCHAR(40) NOT NULL UNIQUE,
    cinema_id BIGINT NOT NULL,
    showtime_id BIGINT NULL,
    room_id BIGINT NULL,
    booking_id BIGINT NULL,
    booking_food_item_id BIGINT NULL,
    booking_food_item_component_id BIGINT NULL,
    incident_group VARCHAR(30) NOT NULL,
    incident_type VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'MEDIUM',
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL,
    evidence_url VARCHAR(500) NULL,
    affected_quantity INT NULL,
    occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reported_by_staff_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING_VERIFICATION',
    manager_verified_by BIGINT NULL,
    manager_decision VARCHAR(30) NULL,
    manager_note TEXT NULL,
    manager_decided_at DATETIME NULL,
    appealed_at DATETIME NULL,
    appealed_by BIGINT NULL,
    appeal_reason TEXT NULL,
    admin_resolved_by BIGINT NULL,
    admin_decision VARCHAR(30) NULL,
    admin_note TEXT NULL,
    admin_resolved_at DATETIME NULL,
    final_conclusion VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ir_cinema FOREIGN KEY (cinema_id) REFERENCES cinemas(cinema_id) ON DELETE RESTRICT,
    CONSTRAINT fk_ir_showtime FOREIGN KEY (showtime_id) REFERENCES showtimes(showtime_id) ON DELETE SET NULL,
    CONSTRAINT fk_ir_room FOREIGN KEY (room_id) REFERENCES rooms(room_id) ON DELETE SET NULL,
    CONSTRAINT fk_ir_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE SET NULL,
    CONSTRAINT fk_ir_bfi FOREIGN KEY (booking_food_item_id) REFERENCES booking_food_item(id) ON DELETE SET NULL,
    CONSTRAINT fk_ir_bfic FOREIGN KEY (booking_food_item_component_id) REFERENCES booking_food_item_component(id) ON DELETE SET NULL,
    CONSTRAINT fk_ir_reporter FOREIGN KEY (reported_by_staff_id) REFERENCES users(user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_ir_mgr FOREIGN KEY (manager_verified_by) REFERENCES users(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_ir_appealer FOREIGN KEY (appealed_by) REFERENCES users(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_ir_admin FOREIGN KEY (admin_resolved_by) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_ir_cinema_status (cinema_id, status, created_at),
    INDEX idx_ir_showtime_status (showtime_id, status),
    INDEX idx_ir_booking (booking_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS incident_verifications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    incident_report_id BIGINT NOT NULL,
    actor_user_id BIGINT NOT NULL,
    actor_role VARCHAR(20) NOT NULL,
    action VARCHAR(40) NOT NULL,
    decision VARCHAR(30) NULL,
    comment TEXT NULL,
    evidence_url VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_iv_incident FOREIGN KEY (incident_report_id) REFERENCES incident_reports(id) ON DELETE CASCADE,
    CONSTRAINT fk_iv_actor FOREIGN KEY (actor_user_id) REFERENCES users(user_id) ON DELETE RESTRICT,
    INDEX idx_iv_incident_created (incident_report_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS staff_error_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cinema_id BIGINT NOT NULL,
    staff_user_id BIGINT NOT NULL,
    reported_by_user_id BIGINT NOT NULL,
    incident_report_id BIGINT NULL,
    booking_id BIGINT NULL,
    error_category VARCHAR(50) NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'LOW',
    description TEXT NOT NULL,
    corrective_action TEXT NULL,
    verified_by_manager_id BIGINT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'LOGGED',
    occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_sel_cinema FOREIGN KEY (cinema_id) REFERENCES cinemas(cinema_id) ON DELETE RESTRICT,
    CONSTRAINT fk_sel_staff FOREIGN KEY (staff_user_id) REFERENCES users(user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_sel_reporter FOREIGN KEY (reported_by_user_id) REFERENCES users(user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_sel_incident FOREIGN KEY (incident_report_id) REFERENCES incident_reports(id) ON DELETE SET NULL,
    CONSTRAINT fk_sel_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE SET NULL,
    CONSTRAINT fk_sel_mgr FOREIGN KEY (verified_by_manager_id) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_sel_cinema_staff (cinema_id, staff_user_id, created_at),
    INDEX idx_sel_incident (incident_report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. Refund management tables
CREATE TABLE IF NOT EXISTS refund_requests (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_code VARCHAR(40) NOT NULL UNIQUE,
    booking_id BIGINT NOT NULL,
    cinema_id BIGINT NOT NULL,
    customer_user_id BIGINT NULL,
    customer_name VARCHAR(120) NULL,
    customer_phone VARCHAR(30) NULL,
    purchase_email VARCHAR(255) NOT NULL,
    submitted_order_code VARCHAR(50) NOT NULL,
    incident_report_id BIGINT NULL,
    reason_category VARCHAR(50) NOT NULL,
    description TEXT NOT NULL,
    evidence_url VARCHAR(500) NULL,
    to_bin VARCHAR(20) NULL,
    to_bank_name VARCHAR(100) NULL,
    to_account_number_encrypted TEXT NULL,
    to_account_number_masked VARCHAR(50) NULL,
    to_account_number_hash CHAR(64) NULL,
    account_holder_name VARCHAR(150) NULL,
    requested_cash_total DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    approved_cash_total DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    approved_voucher_total DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    request_status VARCHAR(30) NOT NULL DEFAULT 'PENDING_REVIEW',
    payout_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED',
    voucher_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED',
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME NULL,
    decision_note TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_rr_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE RESTRICT,
    CONSTRAINT fk_rr_cinema FOREIGN KEY (cinema_id) REFERENCES cinemas(cinema_id) ON DELETE RESTRICT,
    CONSTRAINT fk_rr_customer FOREIGN KEY (customer_user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_rr_incident FOREIGN KEY (incident_report_id) REFERENCES incident_reports(id) ON DELETE SET NULL,
    CONSTRAINT fk_rr_reviewer FOREIGN KEY (reviewed_by) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_rr_cinema_status (cinema_id, request_status, created_at),
    INDEX idx_rr_booking (booking_id),
    INDEX idx_rr_email_code (purchase_email, request_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS refund_request_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_request_id BIGINT NOT NULL,
    item_kind VARCHAR(30) NOT NULL,
    ticket_id BIGINT NULL,
    booking_seat_id BIGINT NULL,
    booking_food_item_id BIGINT NULL,
    booking_food_item_component_id BIGINT NULL,
    item_label_snapshot VARCHAR(200) NOT NULL,
    purchased_quantity INT NOT NULL DEFAULT 1,
    requested_quantity INT NOT NULL DEFAULT 1,
    approved_quantity INT NOT NULL DEFAULT 0,
    gross_unit_price DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    discount_allocated_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    net_paid_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    approved_cash_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    approved_voucher_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    item_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    decision_reason TEXT NULL,
    decided_by BIGINT NULL,
    decided_at DATETIME NULL,
    active_lock_key VARCHAR(100) NULL UNIQUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_rri_rr FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_rri_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(ticket_id) ON DELETE RESTRICT,
    CONSTRAINT fk_rri_bs FOREIGN KEY (booking_seat_id) REFERENCES booking_seats(booking_seat_id) ON DELETE RESTRICT,
    CONSTRAINT fk_rri_bfi FOREIGN KEY (booking_food_item_id) REFERENCES booking_food_item(id) ON DELETE RESTRICT,
    CONSTRAINT fk_rri_bfic FOREIGN KEY (booking_food_item_component_id) REFERENCES booking_food_item_component(id) ON DELETE RESTRICT,
    CONSTRAINT fk_rri_decider FOREIGN KEY (decided_by) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_rri_rr_status (refund_request_id, item_status),
    INDEX idx_rri_ticket (ticket_id),
    INDEX idx_rri_bfi (booking_food_item_id),
    INDEX idx_rri_bfic (booking_food_item_component_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS refund_approval_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_request_id BIGINT NOT NULL,
    refund_request_item_id BIGINT NULL,
    actor_user_id BIGINT NULL,
    actor_role VARCHAR(30) NOT NULL,
    action VARCHAR(50) NOT NULL,
    previous_status VARCHAR(30) NULL,
    new_status VARCHAR(30) NOT NULL,
    note TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rah_rr FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_rah_rri FOREIGN KEY (refund_request_item_id) REFERENCES refund_request_items(id) ON DELETE SET NULL,
    CONSTRAINT fk_rah_actor FOREIGN KEY (actor_user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_rah_rr_created (refund_request_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS refund_communications (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_request_id BIGINT NOT NULL,
    sender_type VARCHAR(20) NOT NULL,
    sender_user_id BIGINT NULL,
    sender_name VARCHAR(100) NULL,
    message TEXT NOT NULL,
    attachment_url VARCHAR(500) NULL,
    email_dispatched BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_rc_rr FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE CASCADE,
    CONSTRAINT fk_rc_sender FOREIGN KEY (sender_user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_rc_rr_created (refund_request_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS refund_audit_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_request_id BIGINT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id BIGINT NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    actor_user_id BIGINT NULL,
    actor_role VARCHAR(30) NULL,
    cinema_id BIGINT NULL,
    ip_address VARCHAR(64) NULL,
    details_json TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ral_rr FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE SET NULL,
    INDEX idx_ral_rr_created (refund_request_id, created_at),
    INDEX idx_ral_entity (entity_type, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. PayOS Payout Transactions & Voucher Recovery
CREATE TABLE IF NOT EXISTS payos_payout_transactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_request_id BIGINT NOT NULL,
    reference_id VARCHAR(80) NOT NULL UNIQUE,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    payos_payout_id VARCHAR(100) NULL,
    payos_transaction_id VARCHAR(100) NULL,
    provider_reference VARCHAR(100) NULL,
    payout_mode VARCHAR(10) NOT NULL DEFAULT 'MOCK',
    amount BIGINT NOT NULL,
    description VARCHAR(255) NOT NULL,
    to_bin VARCHAR(20) NOT NULL,
    to_account_number_masked VARCHAR(50) NOT NULL,
    to_account_holder_name VARCHAR(150) NULL,
    category VARCHAR(50) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    approval_state VARCHAR(30) NULL,
    transaction_state VARCHAR(30) NULL,
    payos_response_code VARCHAR(20) NULL,
    payos_response_desc VARCHAR(255) NULL,
    error_message TEXT NULL,
    raw_response_sanitized TEXT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    active_request_lock BIGINT NULL UNIQUE,
    initiated_by BIGINT NULL,
    initiated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_reconciled_at DATETIME NULL,
    succeeded_at DATETIME NULL,
    failed_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_ppt_rr FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE RESTRICT,
    CONSTRAINT fk_ppt_init FOREIGN KEY (initiated_by) REFERENCES users(user_id) ON DELETE SET NULL,
    INDEX idx_ppt_rr_status (refund_request_id, status),
    INDEX idx_ppt_status_reconcile (status, last_reconciled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS voucher_recoveries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    refund_request_id BIGINT NOT NULL,
    refund_request_item_id BIGINT NULL,
    booking_id BIGINT NOT NULL,
    old_promotion_id BIGINT NULL,
    old_voucher_code_snapshot VARCHAR(50) NULL,
    new_promotion_id BIGINT NULL UNIQUE,
    new_voucher_code VARCHAR(50) NULL UNIQUE,
    recovery_amount DECIMAL(12,2) NOT NULL,
    min_order_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    expires_at DATETIME NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT NULL,
    active_item_lock BIGINT NULL UNIQUE,
    generated_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_vr_rr FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE RESTRICT,
    CONSTRAINT fk_vr_rri FOREIGN KEY (refund_request_item_id) REFERENCES refund_request_items(id) ON DELETE RESTRICT,
    CONSTRAINT fk_vr_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE RESTRICT,
    CONSTRAINT fk_vr_old_promo FOREIGN KEY (old_promotion_id) REFERENCES promotions(promotion_id) ON DELETE SET NULL,
    CONSTRAINT fk_vr_new_promo FOREIGN KEY (new_promotion_id) REFERENCES promotions(promotion_id) ON DELETE SET NULL,
    UNIQUE KEY uk_vr_request_item (refund_request_id, refund_request_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. Extend email_outbox for 6-stage refund emails
ALTER TABLE email_outbox MODIFY COLUMN booking_id BIGINT NULL;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'email_outbox' AND COLUMN_NAME = 'refund_request_id');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE email_outbox ADD COLUMN refund_request_id BIGINT NULL AFTER booking_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @fk_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 'email_outbox' AND CONSTRAINT_NAME = 'fk_eo_refund_request');
SET @sql := IF(@fk_exists = 0, 'ALTER TABLE email_outbox ADD CONSTRAINT fk_eo_refund_request FOREIGN KEY (refund_request_id) REFERENCES refund_requests(id) ON DELETE CASCADE', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'email_outbox' AND COLUMN_NAME = 'subject');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE email_outbox ADD COLUMN subject VARCHAR(255) NULL AFTER email_type', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'email_outbox' AND COLUMN_NAME = 'body_html');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE email_outbox ADD COLUMN body_html MEDIUMTEXT NULL AFTER subject', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'email_outbox' AND COLUMN_NAME = 'dedup_key');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE email_outbox ADD COLUMN dedup_key VARCHAR(120) NULL AFTER body_html', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'email_outbox' AND INDEX_NAME = 'uk_email_outbox_dedup');
SET @sql := IF(@idx_exists = 0, 'CREATE UNIQUE INDEX uk_email_outbox_dedup ON email_outbox(dedup_key)', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 12. Idempotent Backfills for Existing Data
UPDATE bookings b
JOIN showtimes s ON b.showtime_id = s.showtime_id
JOIN rooms r ON s.room_id = r.room_id
SET b.cinema_id = r.cinema_id
WHERE b.cinema_id IS NULL;

UPDATE bookings b
SET b.order_type = CASE
    WHEN EXISTS (SELECT 1 FROM booking_seats bs WHERE bs.booking_id = b.booking_id)
     AND EXISTS (SELECT 1 FROM booking_food_item bfi WHERE bfi.booking_id = b.booking_id) THEN 'TICKET_AND_FOOD'
    WHEN EXISTS (SELECT 1 FROM booking_food_item bfi WHERE bfi.booking_id = b.booking_id) THEN 'FOOD_ONLY'
    ELSE 'TICKET_ONLY'
END;

UPDATE food_fulfillment ff
JOIN bookings b ON ff.booking_id = b.booking_id
SET ff.cinema_id = b.cinema_id
WHERE ff.cinema_id IS NULL AND b.cinema_id IS NOT NULL;

UPDATE booking_seats bs
JOIN bookings b ON bs.booking_id = b.booking_id
SET bs.gross_price = bs.unit_price,
    bs.discount_allocated = CASE WHEN b.discount_amount = 0 THEN 0.00 ELSE bs.discount_allocated END,
    bs.net_paid_amount = CASE WHEN b.discount_amount = 0 THEN bs.unit_price ELSE bs.net_paid_amount END
WHERE bs.gross_price = 0.00 AND bs.unit_price > 0.00;

UPDATE tickets t
JOIN booking_seats bs ON t.booking_seat_id = bs.booking_seat_id
SET t.gross_price = bs.gross_price,
    t.discount_allocated = bs.discount_allocated,
    t.net_paid_amount = bs.net_paid_amount
WHERE t.gross_price = 0.00 AND bs.gross_price > 0.00;

UPDATE booking_food_item bfi
JOIN bookings b ON bfi.booking_id = b.booking_id
SET bfi.net_paid_amount = CASE WHEN b.discount_amount = 0 THEN bfi.subtotal ELSE bfi.net_paid_amount END
WHERE bfi.net_paid_amount = 0.00 AND bfi.subtotal > 0.00 AND b.discount_amount = 0.00;

UPDATE booking_food_item bfi
JOIN food_fulfillment ff ON ff.booking_id = bfi.booking_id
SET bfi.delivered_quantity = bfi.quantity,
    bfi.delivery_status = 'DELIVERED'
WHERE ff.status = 'PICKED_UP' AND bfi.delivered_quantity = 0;

INSERT INTO booking_food_item_component (
    booking_food_item_id, booking_id, food_item_id, variant_id, food_combo_item_id,
    component_name_snapshot, variant_name_snapshot, quantity_per_combo, total_quantity,
    catalog_unit_price_snapshot, allocated_unit_price, allocated_subtotal,
    discount_allocated, net_paid_amount, delivered_quantity, refunded_quantity, delivery_status
)
SELECT
    bfi.id,
    bfi.booking_id,
    fci.food_item_id,
    fci.variant_id,
    fci.id,
    fi.name,
    fv.name,
    fci.quantity,
    bfi.quantity * fci.quantity,
    COALESCE(fv.price, fi.price),
    CASE WHEN ref_totals.total_ref > 0
         THEN ROUND((bfi.subtotal * (COALESCE(fv.price, fi.price) * fci.quantity) / ref_totals.total_ref) / (bfi.quantity * fci.quantity), 2)
         ELSE COALESCE(fv.price, fi.price) END,
    CASE WHEN ref_totals.total_ref > 0
         THEN ROUND(bfi.subtotal * (COALESCE(fv.price, fi.price) * fci.quantity) / ref_totals.total_ref, 0)
         ELSE COALESCE(fv.price, fi.price) * (bfi.quantity * fci.quantity) END,
    0.00,
    CASE WHEN ref_totals.total_ref > 0
         THEN ROUND(bfi.subtotal * (COALESCE(fv.price, fi.price) * fci.quantity) / ref_totals.total_ref, 0)
         ELSE COALESCE(fv.price, fi.price) * (bfi.quantity * fci.quantity) END,
    CASE WHEN bfi.delivered_quantity >= bfi.quantity THEN bfi.quantity * fci.quantity ELSE 0 END,
    0,
    bfi.delivery_status
FROM booking_food_item bfi
JOIN food_combo_item fci ON fci.combo_id = bfi.combo_id
JOIN food_item fi ON fi.id = fci.food_item_id
LEFT JOIN food_variant fv ON fv.id = fci.variant_id
JOIN (
    SELECT fci2.combo_id, SUM(COALESCE(fv2.price, fi2.price) * fci2.quantity) AS total_ref
    FROM food_combo_item fci2
    JOIN food_item fi2 ON fi2.id = fci2.food_item_id
    LEFT JOIN food_variant fv2 ON fv2.id = fci2.variant_id
    GROUP BY fci2.combo_id
) ref_totals ON ref_totals.combo_id = bfi.combo_id
WHERE bfi.item_type = 'COMBO'
  AND NOT EXISTS (
      SELECT 1 FROM booking_food_item_component c WHERE c.booking_food_item_id = bfi.id
  );

-- Ensure branch staff/managers without cinema_id are assigned to default Cinema 1 for local operations
UPDATE users
SET cinema_id = (SELECT MIN(cinema_id) FROM cinemas WHERE status = 'ACTIVE')
WHERE role IN ('STAFF', 'MANAGER')
  AND cinema_id IS NULL
  AND EXISTS (SELECT 1 FROM cinemas WHERE status = 'ACTIVE');
