USE box_cinema;
SET NAMES utf8mb4;

-- 1. Update bookings table columns
SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND COLUMN_NAME = 'ticket_subtotal');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE bookings ADD COLUMN ticket_subtotal DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER guest_phone', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND COLUMN_NAME = 'food_subtotal');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE bookings ADD COLUMN food_subtotal DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER ticket_subtotal', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND COLUMN_NAME = 'service_fee');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE bookings ADD COLUMN service_fee DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER discount_amount', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND COLUMN_NAME = 'payment_deadline');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE bookings ADD COLUMN payment_deadline DATETIME NULL AFTER booking_status', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND COLUMN_NAME = 'confirmed_at');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE bookings ADD COLUMN confirmed_at DATETIME NULL AFTER payment_deadline', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'bookings' AND COLUMN_NAME = 'updated_at');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE bookings ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER booked_at', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. Food Management Tables
CREATE TABLE IF NOT EXISTS food_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS food_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    category_id BIGINT NOT NULL,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    price DECIMAL(12,2) NOT NULL,
    image_url VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    is_available BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_food_item_category FOREIGN KEY (category_id) REFERENCES food_category(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS food_variant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    food_item_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_food_variant_item FOREIGN KEY (food_item_id) REFERENCES food_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS food_combo (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    price DECIMAL(12,2) NOT NULL,
    image_url VARCHAR(500) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    is_available BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS food_combo_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    combo_id BIGINT NOT NULL,
    food_item_id BIGINT NOT NULL,
    variant_id BIGINT NULL,
    quantity INT NOT NULL DEFAULT 1,
    CONSTRAINT fk_combo_item_combo FOREIGN KEY (combo_id) REFERENCES food_combo(id) ON DELETE CASCADE,
    CONSTRAINT fk_combo_item_food FOREIGN KEY (food_item_id) REFERENCES food_item(id) ON DELETE CASCADE,
    CONSTRAINT fk_combo_item_variant FOREIGN KEY (variant_id) REFERENCES food_variant(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS food_inventory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    food_item_id BIGINT NOT NULL UNIQUE,
    on_hand_quantity INT NOT NULL DEFAULT 100,
    reserved_quantity INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_inventory_food_item FOREIGN KEY (food_item_id) REFERENCES food_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Unified Booking Food Items Table
CREATE TABLE IF NOT EXISTS booking_food_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    item_type VARCHAR(20) NOT NULL, -- 'FOOD' or 'COMBO'
    food_item_id BIGINT NULL,
    variant_id BIGINT NULL,
    combo_id BIGINT NULL,
    product_name_snapshot VARCHAR(150) NOT NULL,
    variant_name_snapshot VARCHAR(100) NULL,
    quantity INT NOT NULL,
    unit_price_snapshot DECIMAL(12,2) NOT NULL,
    subtotal DECIMAL(12,2) NOT NULL,
    CONSTRAINT fk_bfi_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_bfi_food_item FOREIGN KEY (food_item_id) REFERENCES food_item(id) ON DELETE SET NULL,
    CONSTRAINT fk_bfi_combo FOREIGN KEY (combo_id) REFERENCES food_combo(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. Payment Additions
SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'order_code');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE payments ADD COLUMN order_code BIGINT NULL UNIQUE AFTER booking_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'payment_link_id');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE payments ADD COLUMN payment_link_id VARCHAR(100) NULL UNIQUE AFTER order_code', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'description');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE payments ADD COLUMN description VARCHAR(255) NULL AFTER payment_link_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'qr_code_payload');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE payments ADD COLUMN qr_code_payload TEXT NULL AFTER description', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'checkout_url');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE payments ADD COLUMN checkout_url VARCHAR(500) NULL AFTER qr_code_payload', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'expired_at');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE payments ADD COLUMN expired_at DATETIME NULL AFTER paid_at', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 5. Payment Webhook Event (Idempotency & Auditing)
CREATE TABLE IF NOT EXISTS payment_webhook_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    payment_id BIGINT NULL,
    order_code BIGINT NOT NULL,
    transaction_reference VARCHAR(150) NULL,
    event_fingerprint VARCHAR(255) NOT NULL UNIQUE,
    signature_verified BOOLEAN NOT NULL DEFAULT FALSE,
    received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at DATETIME NULL,
    processing_status VARCHAR(30) NOT NULL DEFAULT 'PENDING'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. Ticket Enhancements
SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND COLUMN_NAME = 'booking_id');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE tickets ADD COLUMN booking_id BIGINT NULL AFTER ticket_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND COLUMN_NAME = 'showtime_id');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE tickets ADD COLUMN showtime_id BIGINT NULL AFTER booking_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND COLUMN_NAME = 'seat_id');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE tickets ADD COLUMN seat_id BIGINT NULL AFTER showtime_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND COLUMN_NAME = 'price');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE tickets ADD COLUMN price DECIMAL(12,2) NOT NULL DEFAULT 0.00 AFTER seat_id', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tickets' AND COLUMN_NAME = 'used_at');
SET @sql := IF(@col_exists = 0, 'ALTER TABLE tickets ADD COLUMN used_at DATETIME NULL AFTER checked_in_at', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 7. Booking QR (One single QR for the entire booking)
CREATE TABLE IF NOT EXISTS booking_qr (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL UNIQUE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    token_encrypted TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NULL,
    CONSTRAINT fk_booking_qr_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. Booking Check-in History
CREATE TABLE IF NOT EXISTS booking_checkin (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL UNIQUE,
    staff_id BIGINT NULL,
    checked_in_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    result VARCHAR(30) NOT NULL DEFAULT 'SUCCESS',
    CONSTRAINT fk_checkin_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_checkin_staff FOREIGN KEY (staff_id) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. Food Fulfillment
CREATE TABLE IF NOT EXISTS food_fulfillment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    fulfillment_type VARCHAR(30) NOT NULL DEFAULT 'COUNTER_PICKUP',
    pickup_location VARCHAR(100) NULL,
    assigned_staff_id BIGINT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    preparing_at DATETIME NULL,
    ready_at DATETIME NULL,
    picked_up_at DATETIME NULL,
    note TEXT NULL,
    CONSTRAINT fk_ff_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_ff_staff FOREIGN KEY (assigned_staff_id) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. Email Outbox
CREATE TABLE IF NOT EXISTS email_outbox (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    recipient_email VARCHAR(255) NOT NULL,
    email_type VARCHAR(50) NOT NULL DEFAULT 'BOOKING_CONFIRMATION',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    sent_at DATETIME NULL,
    last_error TEXT NULL,
    CONSTRAINT fk_outbox_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. Seed Food Categories
INSERT IGNORE INTO food_category (id, name, description, status) VALUES
(1, 'Bắp rang', 'Bắp rang bơ truyền thống, phô mai và sốt caramel hảo hạng', 'ACTIVE'),
(2, 'Nước uống', 'Nước ngọt có ga giải khát và nước suối tinh khiết', 'ACTIVE'),
(3, 'Đồ ăn', 'Xúc xích nướng, khoai tây chiên giòn rụm và snack', 'ACTIVE'),
(4, 'Combo', 'Các gói combo bắp nước siêu tiết kiệm dành cho cá nhân và nhóm', 'ACTIVE');

-- 12. Seed Food Items
INSERT IGNORE INTO food_item (id, category_id, name, description, price, image_url, status, is_available) VALUES
(1, 1, 'Bắp Rang Bơ Truyền Thống', 'Hạt bắp nổ thơm lừng ngào ngạt vị bơ béo ngậy', 55000.00, '/assets/img/food/popcorn-butter.webp', 'ACTIVE', TRUE),
(2, 1, 'Bắp Phô Mai Thượng Hạng', 'Bắp ngô vàng giòn phủ lớp bột phô mai mặn ngọt đậm đà', 65000.00, '/assets/img/food/popcorn-cheese.webp', 'ACTIVE', TRUE),
(3, 1, 'Bắp Sốt Caramel Giòn Cay', 'Vị ngọt thanh đượm sốt caramel giòn tan từng miếng', 65000.00, '/assets/img/food/popcorn-caramel.webp', 'ACTIVE', TRUE),
(4, 2, 'Coca-Cola Tươi', 'Nước ngọt Coca-Cola mát lạnh sảng khoái', 35000.00, '/assets/img/food/coca-cola.webp', 'ACTIVE', TRUE),
(5, 2, 'Sprite Chanh Mát', 'Nước giải khát vị chanh sảng khoái bất tận', 35000.00, '/assets/img/food/sprite.webp', 'ACTIVE', TRUE),
(6, 2, 'Nước Suối Dasani', 'Nước khoáng tinh khiết đóng chai 500ml', 20000.00, '/assets/img/food/water.webp', 'ACTIVE', TRUE),
(7, 3, 'Xúc Xích Nướng Phô Mai', 'Xúc xích Đức xông khói nướng nóng kèm sốt mù tạt mật ong', 45000.00, '/assets/img/food/sausage.webp', 'ACTIVE', TRUE),
(8, 3, 'Khoai Tây Lắc Phô Mai', 'Khoai tây chiên giòn rụm lắc phô mai thơm phức', 45000.00, '/assets/img/food/fries.webp', 'ACTIVE', TRUE);

-- 13. Seed Food Variants (Size M / Size L for drinks and popcorn)
INSERT IGNORE INTO food_variant (id, food_item_id, name, price, status) VALUES
(1, 1, 'Size Tiêu Chuẩn (M)', 55000.00, 'ACTIVE'),
(2, 1, 'Size Lớn (L)', 69000.00, 'ACTIVE'),
(3, 2, 'Size Tiêu Chuẩn (M)', 65000.00, 'ACTIVE'),
(4, 2, 'Size Lớn (L)', 79000.00, 'ACTIVE'),
(5, 3, 'Size Tiêu Chuẩn (M)', 65000.00, 'ACTIVE'),
(6, 3, 'Size Lớn (L)', 79000.00, 'ACTIVE'),
(7, 4, 'Size Vừa (22oz)', 35000.00, 'ACTIVE'),
(8, 4, 'Size Lớn (32oz)', 45000.00, 'ACTIVE'),
(9, 5, 'Size Vừa (22oz)', 35000.00, 'ACTIVE'),
(10, 5, 'Size Lớn (32oz)', 45000.00, 'ACTIVE');

-- 14. Seed Food Combos
INSERT IGNORE INTO food_combo (id, name, description, price, image_url, status, is_available) VALUES
(1, 'Solo Combo', '1 Bắp rang bơ thơm lớn + 1 Nước ngọt có ga 32oz', 89000.00, '/assets/img/food/combo-solo.webp', 'ACTIVE', TRUE),
(2, 'Couple Movie Night Combo', '1 Bắp lớn (tự chọn vị) + 2 Nước ngọt 32oz + 1 Khoai tây lắc', 139000.00, '/assets/img/food/combo-couple.webp', 'ACTIVE', TRUE),
(3, 'VIP Blockbuster Party Combo', '2 Bắp khổng lồ + 4 Nước ngọt lớn + 2 Xúc xích nướng phô mai', 249000.00, '/assets/img/food/combo-vip.webp', 'ACTIVE', TRUE);

-- 15. Seed Combo Composition Items
INSERT IGNORE INTO food_combo_item (id, combo_id, food_item_id, variant_id, quantity) VALUES
(1, 1, 1, 2, 1),
(2, 1, 4, 8, 1),
(3, 2, 2, 4, 1),
(4, 2, 4, 8, 2),
(5, 2, 8, NULL, 1),
(6, 3, 1, 2, 2),
(7, 3, 4, 8, 4),
(8, 3, 7, NULL, 2);

-- 16. Seed Initial Inventory
INSERT IGNORE INTO food_inventory (food_item_id, on_hand_quantity, reserved_quantity)
SELECT id, 200, 0 FROM food_item;
