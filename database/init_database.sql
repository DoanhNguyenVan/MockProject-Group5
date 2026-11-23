-- =============================================================================
-- BOX CINEMA - COMPLETE ALL-IN-ONE DATABASE SETUP (SCHEMA + SEED DATA)
-- Chạy 1 file duy nhất để thiết lập toàn bộ Database cho hệ thống BOX Cinema
-- Hỗ trợ: 5 Rạp (1 rạp/thành phố), 14 Phòng, 30 Phim, Ghế, F&B, Vé, Voucher, Tài khoản
-- =============================================================================

CREATE DATABASE IF NOT EXISTS box_cinema
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE box_cinema;
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

SET FOREIGN_KEY_CHECKS = 0;

-- -----------------------------------------------------------------------------
-- BƯỚC 1: DROP TẤT CẢ BẢNG CŨ (NẾU CÓ) ĐỂ ĐẢM BẢO DATABASE LUÔN SẠCH VÀ CHUẨN
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS email_outbox;
DROP TABLE IF EXISTS food_fulfillment;
DROP TABLE IF EXISTS booking_checkin;
DROP TABLE IF EXISTS booking_qr;
DROP TABLE IF EXISTS payment_webhook_event;
DROP TABLE IF EXISTS booking_food_item;
DROP TABLE IF EXISTS food_inventory;
DROP TABLE IF EXISTS food_combo_item;
DROP TABLE IF EXISTS food_combo;
DROP TABLE IF EXISTS food_variant;
DROP TABLE IF EXISTS food_item;
DROP TABLE IF EXISTS food_category;
DROP TABLE IF EXISTS password_reset;
DROP TABLE IF EXISTS inquiries;
DROP TABLE IF EXISTS booking_items;
DROP TABLE IF EXISTS tickets;
DROP TABLE IF EXISTS booking_seats;
DROP TABLE IF EXISTS payments;
DROP TABLE IF EXISTS bookings;
DROP TABLE IF EXISTS products;
DROP TABLE IF EXISTS promotions;
DROP TABLE IF EXISTS showtime_seats;
DROP TABLE IF EXISTS showtimes;
DROP TABLE IF EXISTS movie_genres;
DROP TABLE IF EXISTS movies;
DROP TABLE IF EXISTS genres;
DROP TABLE IF EXISTS seats;
DROP TABLE IF EXISTS seat_types;
DROP TABLE IF EXISTS rooms;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS cinemas;

-- -----------------------------------------------------------------------------
-- BƯỚC 2: TẠO CẤU TRÚC BẢNG (DDL SCHEMA)
-- -----------------------------------------------------------------------------

-- 1. Cụm rạp chiếu phim (Mỗi khu vực / thành phố 1 rạp đại diện)
CREATE TABLE cinemas (
    cinema_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cinema_name VARCHAR(150) NOT NULL,
    address VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    email VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Tài khoản người dùng (ADMIN, MANAGER, STAFF, CUSTOMER)
CREATE TABLE users (
    user_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    full_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    phone VARCHAR(20) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NULL,
    google_id VARCHAR(255) NULL UNIQUE,
    role VARCHAR(20) NOT NULL,
    cinema_id BIGINT NULL,
    account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_users_cinema FOREIGN KEY (cinema_id) REFERENCES cinemas(cinema_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Phòng chiếu phim
CREATE TABLE rooms (
    room_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cinema_id BIGINT NOT NULL,
    room_name VARCHAR(50) NOT NULL,
    room_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_rooms_cinema FOREIGN KEY (cinema_id) REFERENCES cinemas(cinema_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. Loại ghế (STANDARD, VIP, SWEETBOX, IMAX, 4DX, RECLINER, WHEELCHAIR)
CREATE TABLE seat_types (
    seat_type_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    type_name VARCHAR(50) NOT NULL UNIQUE,
    shape_type VARCHAR(30) NOT NULL DEFAULT 'standard',
    description TEXT NULL,
    price_surcharge DECIMAL(12,2) NOT NULL DEFAULT 0.00
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. Ghế vật lý trong phòng chiếu
CREATE TABLE seats (
    seat_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    seat_type_id BIGINT NOT NULL,
    seat_row VARCHAR(5) NOT NULL,
    seat_number SMALLINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    pair_id VARCHAR(30) NULL,
    CONSTRAINT fk_seats_room FOREIGN KEY (room_id) REFERENCES rooms(room_id) ON DELETE CASCADE,
    CONSTRAINT fk_seats_type FOREIGN KEY (seat_type_id) REFERENCES seat_types(seat_type_id) ON DELETE RESTRICT,
    CONSTRAINT uk_seats_room_row_number UNIQUE (room_id, seat_row, seat_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. Thể loại phim
CREATE TABLE genres (
    genre_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    genre_name VARCHAR(50) NOT NULL UNIQUE,
    description TEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. Danh mục phim
CREATE TABLE movies (
    movie_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    synopsis TEXT NULL,
    duration_minutes SMALLINT NOT NULL,
    age_rating VARCHAR(10) NOT NULL,
    language VARCHAR(50) NOT NULL,
    release_date DATE NOT NULL,
    end_date DATE NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'NOW_SHOWING',
    poster_url VARCHAR(500) NULL,
    banner_url VARCHAR(500) NULL,
    trailer_url VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. Liên kết Phim - Thể loại
CREATE TABLE movie_genres (
    movie_id BIGINT NOT NULL,
    genre_id BIGINT NOT NULL,
    PRIMARY KEY (movie_id, genre_id),
    CONSTRAINT fk_movie_genres_movie FOREIGN KEY (movie_id) REFERENCES movies(movie_id) ON DELETE CASCADE,
    CONSTRAINT fk_movie_genres_genre FOREIGN KEY (genre_id) REFERENCES genres(genre_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. Suất chiếu phim
CREATE TABLE showtimes (
    showtime_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    base_price DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_showtimes_movie FOREIGN KEY (movie_id) REFERENCES movies(movie_id) ON DELETE RESTRICT,
    CONSTRAINT fk_showtimes_room FOREIGN KEY (room_id) REFERENCES rooms(room_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. Kho trạng thái ghế theo từng suất chiếu
CREATE TABLE showtime_seats (
    showtime_seat_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    showtime_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    seat_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    hold_token_hash VARCHAR(255) NULL,
    held_by_user_id BIGINT NULL,
    hold_session_id VARCHAR(100) NULL,
    hold_expires_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_showtime_seats_showtime FOREIGN KEY (showtime_id) REFERENCES showtimes(showtime_id) ON DELETE CASCADE,
    CONSTRAINT fk_showtime_seats_seat FOREIGN KEY (seat_id) REFERENCES seats(seat_id) ON DELETE RESTRICT,
    CONSTRAINT fk_showtime_seats_user FOREIGN KEY (held_by_user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    CONSTRAINT uk_showtime_seat UNIQUE (showtime_id, seat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. Mã giảm giá & Khuyến mãi (Voucher)
CREATE TABLE promotions (
    promotion_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    promotion_name VARCHAR(150) NOT NULL,
    description TEXT NULL,
    voucher_code VARCHAR(50) NULL UNIQUE,
    discount_type VARCHAR(20) NOT NULL,
    discount_value DECIMAL(12,2) NOT NULL,
    max_discount DECIMAL(12,2) NULL,
    min_order_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    applicable_scope VARCHAR(30) NOT NULL DEFAULT 'ALL',
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    usage_limit INT NULL,
    usage_count INT NOT NULL DEFAULT 0,
    per_user_limit INT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 12. Danh mục sản phẩm F&B dùng chung
CREATE TABLE products (
    product_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_name VARCHAR(150) NOT NULL,
    product_type VARCHAR(20) NOT NULL,
    description TEXT NULL,
    current_price DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 13. Đơn đặt vé / Đơn bán hàng (Bookings)
CREATE TABLE bookings (
    booking_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_code VARCHAR(40) NOT NULL UNIQUE,
    user_id BIGINT NULL,
    showtime_id BIGINT NOT NULL,
    sold_by_staff_id BIGINT NULL,
    promotion_id BIGINT NULL,
    voucher_code_snapshot VARCHAR(50) NULL,
    booking_channel VARCHAR(20) NOT NULL,
    guest_name VARCHAR(100) NULL,
    guest_email VARCHAR(255) NULL,
    guest_phone VARCHAR(20) NULL,
    ticket_subtotal DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    food_subtotal DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    subtotal DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    service_fee DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    total_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    booking_status VARCHAR(20) NOT NULL DEFAULT 'HOLDING',
    booked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    payment_deadline DATETIME NULL,
    confirmed_at DATETIME NULL,
    expires_at DATETIME NULL,
    cancelled_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_bookings_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_bookings_showtime FOREIGN KEY (showtime_id) REFERENCES showtimes(showtime_id) ON DELETE RESTRICT,
    CONSTRAINT fk_bookings_staff FOREIGN KEY (sold_by_staff_id) REFERENCES users(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_bookings_promotion FOREIGN KEY (promotion_id) REFERENCES promotions(promotion_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 14. Bảng thanh toán (Payments)
CREATE TABLE payments (
    payment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    order_code BIGINT NULL UNIQUE,
    payment_link_id VARCHAR(100) NULL UNIQUE,
    description VARCHAR(255) NULL,
    payment_provider VARCHAR(30) NOT NULL,
    payment_method VARCHAR(30) NOT NULL,
    provider_transaction_id VARCHAR(150) NULL,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    amount DECIMAL(12,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    payment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    qr_code_payload TEXT NULL,
    checkout_url VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at DATETIME NULL,
    expired_at DATETIME NULL,
    CONSTRAINT fk_payments_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 15. Chi tiết ghế đã chọn trong đơn hàng
CREATE TABLE booking_seats (
    booking_seat_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    showtime_seat_id BIGINT NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    seat_label_snapshot VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'HOLDING',
    active_seat_key BIGINT NULL UNIQUE,
    CONSTRAINT fk_booking_seats_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_booking_seats_showtime_seat FOREIGN KEY (showtime_seat_id) REFERENCES showtime_seats(showtime_seat_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 16. Vé điện tử phát hành cho từng ghế
CREATE TABLE tickets (
    ticket_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NULL,
    showtime_id BIGINT NULL,
    seat_id BIGINT NULL,
    booking_seat_id BIGINT NOT NULL UNIQUE,
    ticket_code VARCHAR(50) NOT NULL UNIQUE,
    qr_token_hash VARCHAR(255) NOT NULL UNIQUE,
    price DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    ticket_status VARCHAR(20) NOT NULL DEFAULT 'VALID',
    issued_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    checked_in_at DATETIME NULL,
    checked_in_by BIGINT NULL,
    used_at DATETIME NULL,
    CONSTRAINT fk_tickets_booking_seat FOREIGN KEY (booking_seat_id) REFERENCES booking_seats(booking_seat_id) ON DELETE CASCADE,
    CONSTRAINT fk_tickets_staff FOREIGN KEY (checked_in_by) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 17. Chi tiết bắp nước / combo theo đơn hàng (Legacy)
CREATE TABLE booking_items (
    booking_item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    subtotal DECIMAL(12,2) NOT NULL,
    CONSTRAINT fk_booking_items_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_booking_items_product FOREIGN KEY (product_id) REFERENCES products(product_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 18. Yêu cầu liên hệ & Hỗ trợ khách hàng
CREATE TABLE inquiries (
    inquiry_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NULL,
    full_name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    phone VARCHAR(20) NULL,
    inquiry_topic VARCHAR(50) NOT NULL,
    message TEXT NOT NULL,
    inquiry_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    assigned_to BIGINT NULL,
    admin_response TEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    responded_at DATETIME NULL,
    CONSTRAINT fk_inquiries_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_inquiries_assigned FOREIGN KEY (assigned_to) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 19. Xác thực OTP & Đặt lại mật khẩu
CREATE TABLE password_reset (
    user_id BIGINT NOT NULL,
    purpose VARCHAR(30) NOT NULL DEFAULT 'PASSWORD_RESET',
    challenge_id CHAR(36) NOT NULL,
    otp_hash CHAR(64) NULL,
    status VARCHAR(20) NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    resend_count INT NOT NULL DEFAULT 0,
    send_count_in_window INT NOT NULL DEFAULT 0,
    send_window_started_at DATETIME(6) NULL,
    last_sent_at DATETIME(6) NULL,
    otp_expires_at DATETIME(6) NULL,
    locked_until DATETIME(6) NULL,
    verified_at DATETIME(6) NULL,
    reset_token_hash CHAR(64) NULL,
    reset_token_expires_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (user_id, purpose),
    UNIQUE KEY uq_password_reset_challenge (challenge_id),
    UNIQUE KEY uq_password_reset_token (reset_token_hash),
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT chk_password_reset_attempts CHECK (failed_attempts >= 0),
    CONSTRAINT chk_password_reset_resend CHECK (resend_count >= 0),
    CONSTRAINT chk_password_reset_purpose CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION')),
    CONSTRAINT chk_password_reset_status CHECK (status IN ('PENDING', 'SENT', 'VERIFIED', 'COMPLETED', 'EXPIRED', 'LOCKED', 'DELIVERY_FAILED', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 20. Bảng Danh mục F&B
CREATE TABLE food_category (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 21. Bảng Món ăn / Đồ uống F&B
CREATE TABLE food_item (
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

-- 22. Bảng Biến thể kích cỡ F&B (Size M, Size L)
CREATE TABLE food_variant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    food_item_id BIGINT NOT NULL,
    name VARCHAR(50) NOT NULL,
    price DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_food_variant_item FOREIGN KEY (food_item_id) REFERENCES food_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 23. Bảng Combo F&B
CREATE TABLE food_combo (
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

-- 24. Chi tiết thành phần của Combo F&B
CREATE TABLE food_combo_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    combo_id BIGINT NOT NULL,
    food_item_id BIGINT NOT NULL,
    variant_id BIGINT NULL,
    quantity INT NOT NULL DEFAULT 1,
    CONSTRAINT fk_combo_item_combo FOREIGN KEY (combo_id) REFERENCES food_combo(id) ON DELETE CASCADE,
    CONSTRAINT fk_combo_item_food FOREIGN KEY (food_item_id) REFERENCES food_item(id) ON DELETE CASCADE,
    CONSTRAINT fk_combo_item_variant FOREIGN KEY (variant_id) REFERENCES food_variant(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 25. Kho số lượng tồn F&B
CREATE TABLE food_inventory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    food_item_id BIGINT NOT NULL UNIQUE,
    on_hand_quantity INT NOT NULL DEFAULT 100,
    reserved_quantity INT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_inventory_food_item FOREIGN KEY (food_item_id) REFERENCES food_item(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 26. Chi tiết bắp nước trong đơn hàng (Unified Booking Food Item)
CREATE TABLE booking_food_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    item_type VARCHAR(20) NOT NULL,
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

-- 27. Webhook thanh toán (Idempotency chống trùng lặp)
CREATE TABLE payment_webhook_event (
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

-- 28. Mã QR đơn hàng
CREATE TABLE booking_qr (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL UNIQUE,
    token_hash VARCHAR(128) NOT NULL UNIQUE,
    token_encrypted TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NULL,
    CONSTRAINT fk_booking_qr_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 29. Lịch sử check-in vé
CREATE TABLE booking_checkin (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL UNIQUE,
    staff_id BIGINT NULL,
    checked_in_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    result VARCHAR(30) NOT NULL DEFAULT 'SUCCESS',
    CONSTRAINT fk_checkin_booking FOREIGN KEY (booking_id) REFERENCES bookings(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_checkin_staff FOREIGN KEY (staff_id) REFERENCES users(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 30. Trạng thái chuẩn bị F&B tại quầy
CREATE TABLE food_fulfillment (
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

-- 31. Hòm thư gửi email xác nhận vé
CREATE TABLE email_outbox (
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

-- -----------------------------------------------------------------------------
-- CHỈ MỤC TỐI ƯU HIỆU NĂNG TRUY VẤN
-- -----------------------------------------------------------------------------
CREATE INDEX idx_showtimes_schedule ON showtimes (room_id, start_at, end_at, status);
CREATE INDEX idx_showtime_seats_lookup ON showtime_seats (showtime_id, seat_status, hold_expires_at);
CREATE INDEX idx_bookings_user_showtime ON bookings (user_id, showtime_id, booking_status, booked_at);
CREATE INDEX idx_bookings_staff_pos ON bookings (sold_by_staff_id, booked_at);
CREATE INDEX idx_payments_lookup ON payments (booking_id, payment_status, created_at);
CREATE INDEX idx_booking_seats_seat ON booking_seats (showtime_seat_id, status);
CREATE INDEX idx_movie_genres_genre ON movie_genres (genre_id, movie_id);
CREATE INDEX idx_inquiries_status ON inquiries (inquiry_status, created_at);
CREATE INDEX idx_inquiries_user ON inquiries (user_id);
CREATE INDEX idx_inquiries_assigned ON inquiries (assigned_to);

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================================================
-- BƯỚC 3: NẠP DỮ LIỆU NỀN TẢNG (SEED DATA CHUẨN)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. THỂ LOẠI PHIM (12 THỂ LOẠI CHUẨN)
-- -----------------------------------------------------------------------------
INSERT INTO genres (genre_id, genre_name, description) VALUES
(1, 'Action', 'High-energy stunts, martial arts, adrenaline chases, and intense combat.'),
(2, 'Sci-Fi', 'Futuristic science, space travel, parallel dimensions, and groundbreaking concepts.'),
(3, 'Drama', 'Emotional narratives, deep character journeys, and powerful life stories.'),
(4, 'Horror', 'Supernatural mysteries, bone-chilling atmospheres, and psychological dread.'),
(5, 'Animation', 'Artistic animated storytelling and heart-warming experiences for all ages.'),
(6, 'Adventure', 'Epic quests, uncharted landscapes, and daring heroic journeys.'),
(7, 'Comedy', 'Lighthearted humor, clever wit, and hilarious everyday encounters.'),
(8, 'Thriller', 'Nail-biting suspense, rapid plot twists, and edge-of-your-seat excitement.'),
(9, 'Mystery', 'Perplexing crimes, master detectives, and intricate whodunits.'),
(10, 'Fantasy', 'Mythical realms, magical wonders, and legendary creatures.'),
(11, 'Crime', 'Underworld syndicates, heist masterminds, and moral dilemmas.'),
(12, 'Romance', 'Heartfelt romance, heartfelt connections, and modern relationship journeys.');

-- -----------------------------------------------------------------------------
-- 2. CỤM RẠP CHIẾU (5 RẠP ĐẠI DIỆN 5 KHU VỰC / THÀNH PHỐ LỚN)
-- -----------------------------------------------------------------------------
INSERT INTO cinemas (cinema_id, cinema_name, address, city, phone, email, status) VALUES
(1, 'BOX Cinema Hà Nội', 'Tầng 4, Lotte Mall West Lake, 272 Võ Chí Công, Tây Hồ', 'Hà Nội', '024 3788 8899', 'hanoi@boxcinema.vn', 'ACTIVE'),
(2, 'BOX Cinema TP. Hồ Chí Minh', 'Tầng B1, Landmark 81, 720A Điện Biên Phủ, Bình Thạnh', 'TP. Hồ Chí Minh', '028 3822 8181', 'hcm@boxcinema.vn', 'ACTIVE'),
(3, 'BOX Cinema Đà Nẵng', 'Tầng 4, Vincom Plaza, 910A Ngô Quyền, Sơn Trà', 'Đà Nẵng', '0236 399 6688', 'danang@boxcinema.vn', 'ACTIVE'),
(4, 'BOX Cinema Hải Phòng', 'Tầng 4, Vincom Plaza Imperia, Thượng Lý, Hồng Bàng', 'Hải Phòng', '0225 366 8899', 'haiphong@boxcinema.vn', 'ACTIVE'),
(5, 'BOX Cinema Cần Thơ', 'Số 209, Đường 30 Tháng 4, Xuân Khánh, Ninh Kiều', 'Cần Thơ', '0292 376 8899', 'cantho@boxcinema.vn', 'ACTIVE');

-- -----------------------------------------------------------------------------
-- 3. PHÒNG CHIẾU PHIM (14 PHÒNG CHUẨN PHÂN BỔ CHO 5 RẠP)
-- -----------------------------------------------------------------------------
INSERT INTO rooms (room_id, cinema_id, room_name, room_type, status) VALUES
-- Hà Nội: 4 phòng
(1, 1, 'Screen 01 - IMAX Laser', 'IMAX Laser', 'ACTIVE'),
(2, 1, 'Screen 02 - Dolby Atmos', 'Dolby Atmos', 'ACTIVE'),
(3, 1, 'Screen 03 - 4DX Motion', '4DX Motion', 'ACTIVE'),
(4, 1, 'Screen 04 - Standard Luxe', 'Standard', 'ACTIVE'),
-- TP. Hồ Chí Minh: 4 phòng
(5, 2, 'Screen 01 - IMAX Laser', 'IMAX Laser', 'ACTIVE'),
(6, 2, 'Screen 02 - Dolby Atmos', 'Dolby Atmos', 'ACTIVE'),
(7, 2, 'Screen 03 - 4DX Motion', '4DX Motion', 'ACTIVE'),
(8, 2, 'Screen 04 - Standard Luxe', 'Standard', 'ACTIVE'),
-- Đà Nẵng: 2 phòng
(9, 3, 'Screen 01 - IMAX Laser', 'IMAX Laser', 'ACTIVE'),
(10, 3, 'Screen 02 - Dolby Atmos', 'Dolby Atmos', 'ACTIVE'),
-- Hải Phòng: 2 phòng
(11, 4, 'Screen 01 - IMAX Laser', 'IMAX Laser', 'ACTIVE'),
(12, 4, 'Screen 02 - Standard Luxe', 'Standard', 'ACTIVE'),
-- Cần Thơ: 2 phòng
(13, 5, 'Screen 01 - Dolby Atmos', 'Dolby Atmos', 'ACTIVE'),
(14, 5, 'Screen 02 - Standard Luxe', 'Standard', 'ACTIVE');

-- -----------------------------------------------------------------------------
-- 4. BẢNG LOẠI GHẾ (SEAT TYPES)
-- -----------------------------------------------------------------------------
INSERT INTO seat_types (seat_type_id, type_name, shape_type, description, price_surcharge) VALUES
(1, 'STANDARD', 'standard', 'Ghế tiêu chuẩn với tầm nhìn tối ưu và đệm êm ái.', 0.00),
(2, 'VIP', 'vip', 'Ghế VIP hàng giữa trung tâm, góc nhìn và âm thanh chuẩn nhất rạp.', 30000.00),
(3, 'SWEETBOX', 'sweetbox', 'Ghế sofa đôi Sweetbox rộng rãi ở hàng cuối dành cho 2 người.', 50000.00),
(4, 'IMAX_SEAT', 'imax', 'Ghế công thái học tối ưu tầm nhìn màn hình cực đại IMAX Laser.', 60000.00),
(5, 'SEAT_4DX', '4dx', 'Ghế chuyển động đa chiều kèm hiệu ứng gió, nước, ánh sáng 4DX.', 70000.00),
(6, 'RECLINER', 'recliner', 'Ghế ngả lưng bọc da cao cấp 180 độ tại phòng chiếu Gold Class.', 100000.00),
(7, 'WHEELCHAIR', 'wheelchair', 'Vị trí ưu tiên không gian phẳng dành cho khách hàng sử dụng xe lăn.', 0.00);

-- -----------------------------------------------------------------------------
-- 5. SƠ ĐỒ GHẾ VẬT LÝ CHO 14 PHÒNG CHIẾU (60-70 GHẾ MỖI PHÒNG)
-- -----------------------------------------------------------------------------
INSERT INTO seats (room_id, seat_type_id, seat_row, seat_number, status, pair_id)
SELECT
    r.room_id,
    CASE
        WHEN r.room_type LIKE '%4DX%' OR r.room_type LIKE '%4D%' THEN 5
        WHEN r.room_type LIKE '%IMAX%' THEN
            CASE WHEN rw.seat_row = 'A' AND cl.seat_number IN (1, 10) THEN 7 ELSE 4 END
        ELSE
            CASE
                WHEN rw.seat_row = 'A' AND cl.seat_number IN (1, 2) THEN 7
                WHEN rw.seat_row IN ('A', 'B') THEN 1
                WHEN rw.seat_row IN ('C', 'D', 'E') THEN 2
                WHEN rw.seat_row = 'F' THEN 3
                ELSE 1
            END
    END AS seat_type_id,
    rw.seat_row,
    cl.seat_number,
    'ACTIVE' AS status,
    CASE
        WHEN r.room_type NOT LIKE '%4DX%' AND r.room_type NOT LIKE '%4D%'
             AND r.room_type NOT LIKE '%IMAX%'
             AND rw.seat_row = 'F' THEN
            CASE
                WHEN cl.seat_number IN (1, 2) THEN 'F1-F2'
                WHEN cl.seat_number IN (3, 4) THEN 'F3-F4'
                WHEN cl.seat_number IN (5, 6) THEN 'F5-F6'
                WHEN cl.seat_number IN (7, 8) THEN 'F7-F8'
                WHEN cl.seat_number IN (9, 10) THEN 'F9-F10'
                ELSE NULL
            END
        ELSE NULL
    END AS pair_id
FROM rooms r
CROSS JOIN (
    SELECT 'A' AS seat_row UNION ALL
    SELECT 'B' UNION ALL
    SELECT 'C' UNION ALL
    SELECT 'D' UNION ALL
    SELECT 'E' UNION ALL
    SELECT 'F'
) rw
CROSS JOIN (
    SELECT 1 AS seat_number UNION ALL
    SELECT 2 UNION ALL
    SELECT 3 UNION ALL
    SELECT 4 UNION ALL
    SELECT 5 UNION ALL
    SELECT 6 UNION ALL
    SELECT 7 UNION ALL
    SELECT 8 UNION ALL
    SELECT 9 UNION ALL
    SELECT 10
) cl
ORDER BY r.room_id, rw.seat_row, cl.seat_number;

-- Bổ sung hàng Sweetbox (G1-G10) cho các phòng IMAX
INSERT INTO seats (room_id, seat_type_id, seat_row, seat_number, status, pair_id)
SELECT
    r.room_id,
    3 AS seat_type_id,
    'G' AS seat_row,
    cl.seat_number,
    'ACTIVE' AS status,
    CASE
        WHEN cl.seat_number IN (1, 2) THEN 'G1-G2'
        WHEN cl.seat_number IN (3, 4) THEN 'G3-G4'
        WHEN cl.seat_number IN (5, 6) THEN 'G5-G6'
        WHEN cl.seat_number IN (7, 8) THEN 'G7-G8'
        WHEN cl.seat_number IN (9, 10) THEN 'G9-G10'
        ELSE NULL
    END AS pair_id
FROM rooms r
CROSS JOIN (
    SELECT 1 AS seat_number UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
    UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9 UNION ALL SELECT 10
) cl
WHERE r.room_type LIKE '%IMAX%';

-- -----------------------------------------------------------------------------
-- 6. DANH MỤC 30 PHIM BOM TẤN (18 ĐANG CHIẾU + 12 SẮP CHIẾU)
-- -----------------------------------------------------------------------------
INSERT INTO movies (
    movie_id, title, synopsis, duration_minutes, age_rating, language,
    release_date, end_date, status, poster_url, banner_url, trailer_url
) VALUES
(1, 'Dune: Part Two', 'Paul Atreides unites with Chani and the Fremen while seeking revenge against conspirators.', 166, 'T16', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 20 DAY), DATE_ADD(CURDATE(), INTERVAL 40 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg', 'https://image.tmdb.org/t/p/w1280/uhUO7vQQKvCTfQWubOt5MAKokbL.jpg', 'https://www.youtube.com/watch?v=Way9Dexny3w'),
(2, 'Deadpool & Wolverine', 'Wade Wilson teams up with Wolverine on a high-stakes multiverse mission.', 128, 'T18', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 14 DAY), DATE_ADD(CURDATE(), INTERVAL 35 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg', 'https://image.tmdb.org/t/p/w1280/dvBCdCohwWbsP5qAaglOXagDMtk.jpg', 'https://www.youtube.com/watch?v=73_1biulkYk'),
(3, 'Oppenheimer', 'The story of J. Robert Oppenheimer and his role in the atomic bomb creation.', 180, 'T18', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 25 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg', 'https://image.tmdb.org/t/p/w1280/fm6KqXpk3M2HVveHwCrBSSBaO0V.jpg', 'https://www.youtube.com/watch?v=uYPbbksJxIg'),
(4, 'Inside Out 2', 'Joy and Sadness navigate new emotions in teenage Riley as Anxiety arrives.', 96, 'P', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 15 DAY), DATE_ADD(CURDATE(), INTERVAL 45 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/vpnVM9B6NMmQpWeZvzLvDESb2QY.jpg', 'https://image.tmdb.org/t/p/w1280/p5ozvmdgsmbWe0H8umaqFdJwY2P.jpg', 'https://www.youtube.com/watch?v=LEjhY15eCx0'),
(5, 'Godzilla x Kong: The New Empire', 'Two ancient titans clash in a battle that shapes humanity.', 115, 'T13', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 18 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/tMefBSflR6PGQLv7WvFPpKLZkyk.jpg', 'https://image.tmdb.org/t/p/w1280/qrGtVF3YZvJneMmNkEiIZz2GdJd.jpg', 'https://www.youtube.com/watch?v=lV1OOlGwExg'),
(6, 'Avatar: The Way of Water', 'Jake Sully and Neytiri fight to protect their family on Pandora.', 192, 'T13', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 20 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/t6HIqrRAclMCA60NsSmeqe9RmNV.jpg', 'https://image.tmdb.org/t/p/w1280/s16H6tpK2utvwDtzZ8Qy4qm5Emw.jpg', 'https://www.youtube.com/watch?v=d9MyW72ELq0'),
(7, 'Spider-Man: Across the Spider-Verse', 'Miles Morales catapults across the Multiverse with Gwen Stacy.', 140, 'T13', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 12 DAY), DATE_ADD(CURDATE(), INTERVAL 40 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg', 'https://image.tmdb.org/t/p/w1280/4HodYYKEIsGOdinkGi2Ucz6X9i0.jpg', 'https://www.youtube.com/watch?v=cqGjhVJWtEg'),
(8, 'Exhuma', 'A shaman and a geomancer unearth a sinister ancestral grave.', 134, 'T18', 'Korean - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_ADD(CURDATE(), INTERVAL 35 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/muTc5q0q5N4yN4G8K1J4F2j9p4a.jpg', 'https://image.tmdb.org/t/p/w1280/f7J2m0m0p6X6k5q1j1p1p1p1p1p.jpg', 'https://www.youtube.com/watch?v=73_1biulkYk'),
(9, 'Kung Fu Panda 4', 'Po must train a new Dragon Warrior while facing the Chameleon.', 94, 'P', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 16 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/kDp1vUBnMpe8ak4rjgl3cLELqjU.jpg', 'https://image.tmdb.org/t/p/w1280/1XDDXPXGiI8id7MrUxK36ke7gkX.jpg', 'https://www.youtube.com/watch?v=_inKs4eeHiI'),
(10, 'Interstellar', 'Explorers travel through a wormhole in space to ensure humanity''s survival.', 169, 'T13', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 22 DAY), DATE_ADD(CURDATE(), INTERVAL 45 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg', 'https://image.tmdb.org/t/p/w1280/rAiYTsqJJR9asvkF2Id01Stiezq.jpg', 'https://www.youtube.com/watch?v=zSWdZVtXT7E'),
(11, 'The Batman', 'Batman ventures into Gotham''s underworld to uncover corruption.', 176, 'T16', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 14 DAY), DATE_ADD(CURDATE(), INTERVAL 35 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/74xTEgt7R36Fpooo50r9T25onhq.jpg', 'https://image.tmdb.org/t/p/w1280/5P8SmMzSNYikXpxil6BYzJ16611.jpg', 'https://www.youtube.com/watch?v=mqqft2x_Aa4'),
(12, 'Top Gun: Maverick', 'Maverick leads Top Gun graduates on a specialized mission.', 130, 'T13', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 15 DAY), DATE_ADD(CURDATE(), INTERVAL 35 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/62HCnUTziyWcpDaBO2i1DX17ljH.jpg', 'https://image.tmdb.org/t/p/w1280/odJ4hx6g6vBt4lBWKFD1tI8WS4x.jpg', 'https://www.youtube.com/watch?v=giXco2jaZ_4'),
(13, 'John Wick: Chapter 4', 'John Wick uncovers a path to defeating the High Table.', 169, 'T18', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 20 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg', 'https://image.tmdb.org/t/p/w1280/h8gHn0OzRogL0GayvWGmBQ4a0Z0.jpg', 'https://www.youtube.com/watch?v=qEVUtrk8_B4'),
(14, 'Inception', 'A thief steals corporate secrets through dream-sharing technology.', 148, 'T16', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 24 DAY), DATE_ADD(CURDATE(), INTERVAL 40 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg', 'https://image.tmdb.org/t/p/w1280/s3TBrRGB1iav7gFOCNx3H31MoES.jpg', 'https://www.youtube.com/watch?v=YoHD9XEInc0'),
(15, 'Furiosa: A Mad Max Saga', 'Young Furiosa falls into the hands of a Biker Horde.', 148, 'T18', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 8 DAY), DATE_ADD(CURDATE(), INTERVAL 40 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/iADOJ8Zymht2JPMoy3R7xUMZqaC.jpg', 'https://image.tmdb.org/t/p/w1280/wNAhuOZ3Zf86J7NkWUr2qY4OF4W.jpg', 'https://www.youtube.com/watch?v=XJMuhwVlca4'),
(16, 'Alien: Romulus', 'Young space colonizers come face to face with the most terrifying life form.', 119, 'T18', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 6 DAY), DATE_ADD(CURDATE(), INTERVAL 45 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/b33nnKl12vfwhW7SQq9Qo3m3Dke.jpg', 'https://image.tmdb.org/t/p/w1280/9SSEUrSqhljBMzRe4aBTh17rUaC.jpg', 'https://www.youtube.com/watch?v=x0XDEhP4MQs'),
(17, 'Twisters', 'An update to the 1996 film Twister about tornado chasing.', 122, 'T13', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 11 DAY), DATE_ADD(CURDATE(), INTERVAL 35 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/pjnD08FlMAIXsfOLKQbvmO0f0MD.jpg', 'https://image.tmdb.org/t/p/w1280/581JR99itkZ0XVRv2vNbhq76G2G.jpg', 'https://www.youtube.com/watch?v=jwaN0wce154'),
(18, 'Kingdom of the Planet of the Apes', 'Many years after Caesar, a young ape questions his history.', 145, 'T13', 'English - Vietnamese Subtitles', DATE_SUB(CURDATE(), INTERVAL 14 DAY), DATE_ADD(CURDATE(), INTERVAL 35 DAY), 'NOW_SHOWING', 'https://image.tmdb.org/t/p/w780/gKkl37BQuKTanygYQG1pyYgLVgf.jpg', 'https://image.tmdb.org/t/p/w1280/fqv8v6AycXKsivp1TddsHR3MinQ.jpg', 'https://www.youtube.com/watch?v=XtFI7SNtVpY'),

-- 12 Phim sắp chiếu (COMING_SOON)
(19, 'Joker: Folie a Deux', 'Arthur Fleck awaits trial and finds love at Arkham.', 138, 'T18', 'English - Vietnamese Subtitles', DATE_ADD(CURDATE(), INTERVAL 15 DAY), DATE_ADD(CURDATE(), INTERVAL 60 DAY), 'COMING_SOON', 'https://image.tmdb.org/t/p/w780/aciP8Km0waTLGEYfLHikGHoxHgk.jpg', 'https://image.tmdb.org/t/p/w1280/uLqNGocnRhmq51680Xj13008p7K.jpg', 'https://www.youtube.com/watch?v=_OKAwz2NiJs'),
(20, 'Gladiator II', 'Lucius enters the Colosseum after his home is conquered.', 148, 'T18', 'English - Vietnamese Subtitles', DATE_ADD(CURDATE(), INTERVAL 25 DAY), DATE_ADD(CURDATE(), INTERVAL 75 DAY), 'COMING_SOON', 'https://image.tmdb.org/t/p/w780/2cxhvwyEwRlysAmRHbtKN0osugq.jpg', 'https://image.tmdb.org/t/p/w1280/euYIwmwkmz95mnEx7vke1CmhuNG.jpg', 'https://www.youtube.com/watch?v=4rgYUipGJNo'),
(21, 'Moana 2', 'Moana journeys to far seas after receiving an unexpected call.', 100, 'P', 'English - Vietnamese Subtitles', DATE_ADD(CURDATE(), INTERVAL 20 DAY), DATE_ADD(CURDATE(), INTERVAL 70 DAY), 'COMING_SOON', 'https://image.tmdb.org/t/p/w780/m9EtH1j3Toq9iTviJuZ5viinPdt.jpg', 'https://image.tmdb.org/t/p/w1280/tElnmtQ6yszmi5TCkbPhvd74W0r.jpg', 'https://www.youtube.com/watch?v=hDZ7y8RP5HE'),
(22, 'Wicked', 'The untold story of the witches of Oz.', 160, 'P', 'English - Vietnamese Subtitles', DATE_ADD(CURDATE(), INTERVAL 18 DAY), DATE_ADD(CURDATE(), INTERVAL 65 DAY), 'COMING_SOON', 'https://image.tmdb.org/t/p/w780/xDGbZ0JJ3mYaGKy4Nzd9Kph6M9L.jpg', 'https://image.tmdb.org/t/p/w1280/uKb22E5ww9u494rh9qfQzS338Ww.jpg', 'https://www.youtube.com/watch?v=6COmYeLsz4c'),
(23, 'Mufasa: The Lion King', 'The unlikely rise of the beloved king of the Pride Lands.', 118, 'P', 'English - Vietnamese Subtitles', DATE_ADD(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 80 DAY), 'COMING_SOON', 'https://image.tmdb.org/t/p/w780/lurEK87kukWNaHd0z3WsC5bNxRH.jpg', 'https://image.tmdb.org/t/p/w1280/b85bJijvJk7vCg7B9b85bJijvJk.jpg', 'https://www.youtube.com/watch?v=o17MF9vnabg'),
(24, 'Sonic the Hedgehog 3', 'Sonic, Knuckles, and Tails reunite against Shadow.', 110, 'P', 'English - Vietnamese Subtitles', DATE_ADD(CURDATE(), INTERVAL 35 DAY), DATE_ADD(CURDATE(), INTERVAL 85 DAY), 'COMING_SOON', 'https://image.tmdb.org/t/p/w780/d8Ryb8AunYAuycVKDp5HpdWPKgC.jpg', 'https://image.tmdb.org/t/p/w1280/t5k8q9w9y4J1p1p1p1p1p1p1p1.jpg', 'https://www.youtube.com/watch?v=qSu6i2iFMO0'),
(25, 'Nosferatu', 'A gothic tale of obsession between a haunted young woman and a vampire.', 132, 'T18', 'English - Vietnamese Subtitles', DATE_ADD(CURDATE(), INTERVAL 40 DAY), DATE_ADD(CURDATE(), INTERVAL 90 DAY), 'COMING_SOON', 'https://image.tmdb.org/t/p/w780/5SsvwE3yR2m6r4y5x2J1p1p1p1.jpg', 'https://image.tmdb.org/t/p/w1280/q8gHn0OzRogL0GayvWGmBQ4a0Z0.jpg', 'https://www.youtube.com/watch?v=73_1biulkYk'),
(26, 'A Quiet Place: Day One', 'Experience the day the world went quiet in New York City.', 99, 'T16', 'English - Vietnamese Subtitles', DATE_ADD(CURDATE(), INTERVAL 10 DAY), DATE_ADD(CURDATE(), INTERVAL 50 DAY), 'COMING_SOON', 'https://image.tmdb.org/t/p/w780/yrpPYKijwd9Ja5urQkqaRzFUm7.jpg', 'https://image.tmdb.org/t/p/w1280/2RVcJbWFmICRDVjdvPv8GFWl58o.jpg', 'https://www.youtube.com/watch?v=YPY7J-flzE8'),
(27, 'Beetlejuice Beetlejuice', 'Three generations of the Deetz family return to Winter River.', 105, 'T13', 'English - Vietnamese Subtitles', DATE_ADD(CURDATE(), INTERVAL 12 DAY), DATE_ADD(CURDATE(), INTERVAL 55 DAY), 'COMING_SOON', 'https://image.tmdb.org/t/p/w780/kKgQzkUCUm0meqZiwVoqqGFJwPT.jpg', 'https://image.tmdb.org/t/p/w1280/1wP1phHo2CROOqzahSVQY8v5970.jpg', 'https://www.youtube.com/watch?v=CoZqL9N6Rx4'),
(28, 'Venom: The Last Dance', 'Eddie and Venom are on the run hunted by both of their worlds.', 109, 'T13', 'English - Vietnamese Subtitles', DATE_ADD(CURDATE(), INTERVAL 22 DAY), DATE_ADD(CURDATE(), INTERVAL 65 DAY), 'COMING_SOON', 'https://image.tmdb.org/t/p/w780/aosm8NMQ3UzyAcg786hgObtdDrA.jpg', 'https://image.tmdb.org/t/p/w1280/3V4kLQg0kSqksct0umGRVa9Zrr5.jpg', 'https://www.youtube.com/watch?v=__2bjWbetsA'),
(29, 'Captain America: Brave New World', 'Sam Wilson finds himself in the middle of an international incident.', 118, 'T13', 'English - Vietnamese Subtitles', DATE_ADD(CURDATE(), INTERVAL 45 DAY), DATE_ADD(CURDATE(), INTERVAL 95 DAY), 'COMING_SOON', 'https://image.tmdb.org/t/p/w780/pzIddQEMWhWzfvLI3TwxUG2wYIW.jpg', 'https://image.tmdb.org/t/p/w1280/7h65MWooyURaqSqudyxSt6Vg97h.jpg', 'https://www.youtube.com/watch?v=1pHDWnXmK7Y'),
(30, 'Mission: Impossible - The Final Reckoning', 'Ethan Hunt and his IMF team face their most dangerous mission yet.', 165, 'T16', 'English - Vietnamese Subtitles', DATE_ADD(CURDATE(), INTERVAL 60 DAY), DATE_ADD(CURDATE(), INTERVAL 110 DAY), 'COMING_SOON', 'https://image.tmdb.org/t/p/w780/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg', 'https://image.tmdb.org/t/p/w1280/dvBCdCohwWbsP5qAaglOXagDMtk.jpg', 'https://www.youtube.com/watch?v=73_1biulkYk');

-- -----------------------------------------------------------------------------
-- 7. LIÊN KẾT PHIM VỚI THỂ LOẠI (MOVIE_GENRES)
-- -----------------------------------------------------------------------------
INSERT INTO movie_genres (movie_id, genre_id) VALUES
(1, 1), (1, 2), (1, 6),
(2, 1), (2, 7), (2, 2),
(3, 3), (3, 8),
(4, 5), (4, 7), (4, 3),
(5, 1), (5, 2), (5, 6),
(6, 1), (6, 2), (6, 6),
(7, 5), (7, 1), (7, 2),
(8, 4), (8, 9), (8, 8),
(9, 5), (9, 1), (9, 7),
(10, 2), (10, 3), (10, 6),
(11, 1), (11, 9), (11, 11),
(12, 1), (12, 3),
(13, 1), (13, 11), (13, 8),
(14, 1), (14, 2), (14, 8),
(15, 1), (15, 2), (15, 6),
(16, 4), (16, 2), (16, 8),
(17, 1), (17, 8), (17, 6),
(18, 1), (18, 2), (18, 6),
(19, 3), (19, 11), (19, 8),
(20, 1), (20, 3), (20, 6),
(21, 5), (21, 6), (21, 7),
(22, 10), (22, 3), (22, 12),
(23, 5), (23, 6), (23, 3),
(24, 5), (24, 1), (24, 7),
(25, 4), (25, 10), (25, 9),
(26, 4), (26, 2), (26, 8),
(27, 7), (27, 10), (27, 4),
(28, 1), (28, 2), (28, 8),
(29, 1), (29, 2), (29, 8),
(30, 1), (30, 8), (30, 6);

-- -----------------------------------------------------------------------------
-- 8. SUẤT CHIẾU PHIM TỰ ĐỘNG (SHOWTIMES: HÔM NAY VÀ 7 NGÀY TỚI QUA 14 PHÒNG)
-- -----------------------------------------------------------------------------
INSERT INTO showtimes (movie_id, room_id, start_at, end_at, base_price, status)
SELECT
    m.movie_id,
    r.room_id,
    TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d.day_offset DAY), t.slot_time) AS start_at,
    TIMESTAMPADD(MINUTE, m.duration_minutes + 15, TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d.day_offset DAY), t.slot_time)) AS end_at,
    CASE 
        WHEN r.room_type = 'IMAX Laser' THEN 140000.00
        WHEN r.room_type = '4DX Motion' THEN 130000.00
        WHEN r.room_type = 'Dolby Atmos' THEN 105000.00
        ELSE 85000.00
    END AS base_price,
    'ACTIVE' AS status
FROM movies m
CROSS JOIN (
    SELECT 0 AS day_offset UNION ALL
    SELECT 1 UNION ALL
    SELECT 2 UNION ALL
    SELECT 3 UNION ALL
    SELECT 4 UNION ALL
    SELECT 5 UNION ALL
    SELECT 6
) d
CROSS JOIN (
    SELECT '09:30:00' AS slot_time UNION ALL
    SELECT '13:00:00' UNION ALL
    SELECT '16:15:00' UNION ALL
    SELECT '19:30:00' UNION ALL
    SELECT '22:15:00'
) t
JOIN rooms r ON r.room_id = (
    1 + ((m.movie_id * 3 + d.day_offset * 2 + TIME_TO_SEC(t.slot_time) / 3600) MOD 14)
)
WHERE m.status = 'NOW_SHOWING'
  AND (m.movie_id + d.day_offset) % 2 = 0;

-- -----------------------------------------------------------------------------
-- 9. TÀI KHOẢN HỆ THỐNG (ADMIN, MANAGER THEO TỪNG KHU VỰC, STAFF, CUSTOMER)
-- Mật khẩu mặc định: 123456
-- BCrypt Hash: $2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.
-- -----------------------------------------------------------------------------
INSERT INTO users (user_id, full_name, email, phone, password_hash, role, cinema_id, account_status, created_at, updated_at) VALUES
-- Admin hệ thống (toàn quyền)
(1, 'BOX Cinema Administrator', 'admin@gmail.com', '0123456789', '$2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.', 'ADMIN', NULL, 'ACTIVE', NOW(), NOW()),
(2, 'System Administrator', 'admin@boxcinema.vn', '0909999990', '$2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.', 'ADMIN', NULL, 'ACTIVE', NOW(), NOW()),

-- 5 Quản lý rạp (Manager đại diện 5 khu vực / thành phố)
(3, 'Manager Hà Nội', 'manager.hanoi@gmail.com', '0909000001', '$2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.', 'MANAGER', 1, 'ACTIVE', NOW(), NOW()),
(4, 'Manager TP.HCM', 'manager.hcm@gmail.com', '0909000002', '$2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.', 'MANAGER', 2, 'ACTIVE', NOW(), NOW()),
(5, 'Manager Đà Nẵng', 'manager.danang@gmail.com', '0909000003', '$2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.', 'MANAGER', 3, 'ACTIVE', NOW(), NOW()),
(6, 'Manager Hải Phòng', 'manager.haiphong@gmail.com', '0909000004', '$2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.', 'MANAGER', 4, 'ACTIVE', NOW(), NOW()),
(7, 'Manager Cần Thơ', 'manager.cantho@gmail.com', '0909000005', '$2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.', 'MANAGER', 5, 'ACTIVE', NOW(), NOW()),

-- Tài khoản Manager & Staff tương thích ngược
(8, 'Manager1 (TP.HCM)', 'manager1@gmail.com', '0909999992', '$2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.', 'MANAGER', 2, 'ACTIVE', NOW(), NOW()),
(9, 'Linh Pham (Manager)', 'manager@boxcinema.vn', '0901234569', '$2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.', 'MANAGER', 2, 'ACTIVE', NOW(), NOW()),
(10, 'Staff Hà Nội', 'staff.hanoi@gmail.com', '0908000001', '$2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.', 'STAFF', 1, 'ACTIVE', NOW(), NOW()),
(11, 'Staff TP.HCM', 'staff.hcm@gmail.com', '0908000002', '$2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.', 'STAFF', 2, 'ACTIVE', NOW(), NOW()),
(12, 'Staff1', 'staff1@gmail.com', '0909999993', '$2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.', 'STAFF', 2, 'ACTIVE', NOW(), NOW()),

-- Khách hàng mẫu
(13, 'Alex Nguyen', 'customer@gmail.com', '0901000004', '$2a$10$6s6R76Gelgn2VtHb.AbiuOOTCHicJesUsPsMvFBxS9pg02U2hoso.', 'CUSTOMER', NULL, 'ACTIVE', NOW(), NOW());

-- -----------------------------------------------------------------------------
-- 10. DANH MỤC F&B VÀ BẮP NƯỚC (FOOD & BEVERAGES)
-- -----------------------------------------------------------------------------
INSERT INTO food_category (id, name, description, status) VALUES
(1, 'Bắp rang', 'Bắp rang bơ truyền thống, phô mai và sốt caramel hảo hạng', 'ACTIVE'),
(2, 'Nước uống', 'Nước ngọt có ga giải khát và nước suối tinh khiết', 'ACTIVE'),
(3, 'Đồ ăn', 'Xúc xích nướng, khoai tây chiên giòn rụm và snack', 'ACTIVE'),
(4, 'Combo', 'Các gói combo bắp nước siêu tiết kiệm dành cho cá nhân và nhóm', 'ACTIVE');

INSERT INTO food_item (id, category_id, name, description, price, image_url, status, is_available) VALUES
(1, 1, 'Bắp Rang Bơ Truyền Thống', 'Hạt bắp nổ thơm lừng ngào ngạt vị bơ béo ngậy', 55000.00, '/assets/img/food/popcorn-butter.webp', 'ACTIVE', TRUE),
(2, 1, 'Bắp Phô Mai Thượng Hạng', 'Bắp ngô vàng giòn phủ lớp bột phô mai mặn ngọt đậm đà', 65000.00, '/assets/img/food/popcorn-cheese.webp', 'ACTIVE', TRUE),
(3, 1, 'Bắp Sốt Caramel Giòn Cay', 'Vị ngọt thanh đượm sốt caramel giòn tan từng miếng', 65000.00, '/assets/img/food/popcorn-caramel.webp', 'ACTIVE', TRUE),
(4, 2, 'Coca-Cola Tươi', 'Nước ngọt Coca-Cola mát lạnh sảng khoái', 35000.00, '/assets/img/food/coca-cola.webp', 'ACTIVE', TRUE),
(5, 2, 'Sprite Chanh Mát', 'Nước giải khát vị chanh sảng khoái bất tận', 35000.00, '/assets/img/food/sprite.webp', 'ACTIVE', TRUE),
(6, 2, 'Nước Suối Dasani', 'Nước khoáng tinh khiết đóng chai 500ml', 20000.00, '/assets/img/food/water.webp', 'ACTIVE', TRUE),
(7, 3, 'Xúc Xích Nướng Phô Mai', 'Xúc xích Đức xông khói nướng nóng kèm sốt mù tạt mật ong', 45000.00, '/assets/img/food/sausage.webp', 'ACTIVE', TRUE),
(8, 3, 'Khoai Tây Lắc Phô Mai', 'Khoai tây chiên giòn rụm lắc phô mai thơm phức', 45000.00, '/assets/img/food/fries.webp', 'ACTIVE', TRUE);

INSERT INTO food_variant (id, food_item_id, name, price, status) VALUES
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

INSERT INTO food_combo (id, name, description, price, image_url, status, is_available) VALUES
(1, 'Solo Combo', '1 Bắp rang bơ thơm lớn + 1 Nước ngọt có ga 32oz', 89000.00, '/assets/img/food/combo-solo.webp', 'ACTIVE', TRUE),
(2, 'Couple Movie Night Combo', '1 Bắp lớn (tự chọn vị) + 2 Nước ngọt 32oz + 1 Khoai tây lắc', 139000.00, '/assets/img/food/combo-couple.webp', 'ACTIVE', TRUE),
(3, 'VIP Blockbuster Party Combo', '2 Bắp khổng lồ + 4 Nước ngọt lớn + 2 Xúc xích nướng phô mai', 249000.00, '/assets/img/food/combo-vip.webp', 'ACTIVE', TRUE);

INSERT INTO food_combo_item (id, combo_id, food_item_id, variant_id, quantity) VALUES
(1, 1, 1, 2, 1),
(2, 1, 4, 8, 1),
(3, 2, 2, 4, 1),
(4, 2, 4, 8, 2),
(5, 2, 8, NULL, 1),
(6, 3, 1, 2, 2),
(7, 3, 4, 8, 4),
(8, 3, 7, NULL, 2);

INSERT INTO food_inventory (food_item_id, on_hand_quantity, reserved_quantity)
SELECT id, 500, 0 FROM food_item;

-- Bảng sản phẩm dùng chung (Admin products suite)
INSERT INTO products (product_id, product_name, product_type, description, current_price, status) VALUES
(1, 'Solo Combo', 'COMBO', '1 Bắp lớn + 1 Nước ngọt 32oz', 89000.00, 'ACTIVE'),
(2, 'Couple Movie Night Combo', 'COMBO', '1 Bắp lớn + 2 Nước ngọt + 1 Khoai tây lắc', 139000.00, 'ACTIVE'),
(3, 'VIP Blockbuster Party Combo', 'COMBO', '2 Bắp lớn + 4 Nước ngọt + 2 Xúc xích nướng', 249000.00, 'ACTIVE'),
(4, 'Bắp Rang Bơ Truyền Thống', 'FOOD', 'Bắp rang bơ giòn rụm ngào bơ thơm', 55000.00, 'ACTIVE'),
(5, 'Coca-Cola Tươi 32oz', 'DRINK', 'Nước ngọt Coca-Cola mát lạnh sảng khoái', 39000.00, 'ACTIVE');

-- -----------------------------------------------------------------------------
-- 11. MÃ KHUYẾN MÃI & VOUCHER (PROMOTIONS)
-- -----------------------------------------------------------------------------
INSERT INTO promotions (
    promotion_id, promotion_name, description, voucher_code,
    discount_type, discount_value, max_discount, min_order_amount,
    applicable_scope, start_at, end_at, usage_limit, usage_count, per_user_limit, status
) VALUES
(1, 'Giảm 10.000đ cho đơn từ 50.000đ', 'Voucher giảm giá cố định cho khách hàng', 'BOX10K', 'FIXED', 10000.00, 10000.00, 50000.00, 'ALL', '2026-01-01 00:00:00', '2027-12-31 23:59:59', 1000, 0, 5, 'ACTIVE'),
(2, 'Giảm 50% tối đa 30.000đ', 'Voucher giảm phần trăm có chặn mức tối đa', 'BOX50', 'PERCENT', 50.00, 30000.00, 0.00, 'ALL', '2026-01-01 00:00:00', '2027-12-31 23:59:59', 500, 0, 2, 'ACTIVE'),
(3, 'Giảm 5.000đ không giới hạn đơn hàng', 'Voucher giảm giá áp dụng mọi đơn', 'BOX5K', 'FIXED', 5000.00, 5000.00, 0.00, 'ALL', '2026-01-01 00:00:00', '2027-12-31 23:59:59', 2000, 0, 10, 'ACTIVE'),
(4, 'Giảm 100% tối đa 100.000đ', 'Voucher xem phim miễn phí có chặn trần', 'BOXFREE', 'PERCENT', 100.00, 100000.00, 0.00, 'ALL', '2026-01-01 00:00:00', '2027-12-31 23:59:59', 100, 0, 1, 'ACTIVE'),
(5, 'Giảm 20.000đ cho đơn từ 100.000đ', 'Ưu đãi chào bạn mới', 'BOX20K', 'FIXED', 20000.00, 20000.00, 100000.00, 'ALL', '2026-01-01 00:00:00', '2027-12-31 23:59:59', 1000, 0, 2, 'ACTIVE'),
(6, 'Ưu Đãi Thứ Tư Vui Vẻ - Giảm 15%', 'Giảm 15% tối đa 50K cho đơn từ 150K', 'BOXMIDWEEK', 'PERCENT', 15.00, 50000.00, 150000.00, 'ALL', '2026-01-01 00:00:00', '2027-12-31 23:59:59', 500, 0, 1, 'ACTIVE'),
(7, 'Đại Tiệc Phim Bom Tấn - Giảm 50K', 'Giảm 50K cho đơn hàng từ 250K', 'VIPPARTY', 'FIXED', 50000.00, 50000.00, 250000.00, 'ALL', '2026-01-01 00:00:00', '2027-12-31 23:59:59', 200, 0, 2, 'ACTIVE');

-- -----------------------------------------------------------------------------
-- KIỂM TRA TỔNG QUAN HỆ THỐNG SAU KHI KHỞI TẠO
-- -----------------------------------------------------------------------------
SELECT 
    (SELECT COUNT(*) FROM cinemas) AS total_cinemas,
    (SELECT COUNT(*) FROM rooms) AS total_rooms,
    (SELECT COUNT(*) FROM seats) AS total_seats,
    (SELECT COUNT(*) FROM movies) AS total_movies,
    (SELECT COUNT(*) FROM showtimes) AS total_showtimes,
    (SELECT COUNT(*) FROM users) AS total_users,
    (SELECT COUNT(*) FROM food_item) AS total_food_items,
    (SELECT COUNT(*) FROM promotions) AS total_vouchers;
