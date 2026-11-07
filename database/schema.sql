-- =============================================================================
-- DATABASE SCHEMA: BOX CINEMA (SRS V2.2 - OFFICIAL 17-ENTITY BASELINE)
-- MySQL 8.0 / InnoDB / UTF8MB4
-- =============================================================================

CREATE DATABASE IF NOT EXISTS box_cinema
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE box_cinema;

-- Tắt kiểm tra khóa ngoại tạm thời để drop bảng an toàn nếu cần tái tạo
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS INQUIRIES;
DROP TABLE IF EXISTS BOOKING_ITEMS;
DROP TABLE IF EXISTS TICKETS;
DROP TABLE IF EXISTS BOOKING_SEATS;
DROP TABLE IF EXISTS PAYMENTS;
DROP TABLE IF EXISTS BOOKINGS;
DROP TABLE IF EXISTS PRODUCTS;
DROP TABLE IF EXISTS PROMOTIONS;
DROP TABLE IF EXISTS SHOWTIME_SEATS;
DROP TABLE IF EXISTS SHOWTIMES;
DROP TABLE IF EXISTS MOVIE_GENRES;
DROP TABLE IF EXISTS MOVIES;
DROP TABLE IF EXISTS GENRES;
DROP TABLE IF EXISTS SEATS;
DROP TABLE IF EXISTS SEAT_TYPES;
DROP TABLE IF EXISTS ROOMS;
DROP TABLE IF EXISTS USERS;
DROP TABLE IF EXISTS CINEMAS;

SET FOREIGN_KEY_CHECKS = 1;

-- -----------------------------------------------------------------------------
-- 1. BẢNG CINEMAS (Cụm rạp chiếu)
-- -----------------------------------------------------------------------------
CREATE TABLE CINEMAS (
    cinema_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cinema_name VARCHAR(150) NOT NULL,
    address VARCHAR(255) NOT NULL,
    city VARCHAR(100) NOT NULL,
    phone VARCHAR(20) NOT NULL,
    email VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 2. BẢNG USERS (Tài khoản người dùng & nhân viên)
-- Không có bảng ROLES; role lưu trực tiếp: CUSTOMER, STAFF, MANAGER, ADMIN
-- -----------------------------------------------------------------------------
CREATE TABLE USERS (
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
    CONSTRAINT fk_users_cinema FOREIGN KEY (cinema_id) REFERENCES CINEMAS(cinema_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 3. BẢNG ROOMS (Phòng chiếu phim)
-- -----------------------------------------------------------------------------
CREATE TABLE ROOMS (
    room_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cinema_id BIGINT NOT NULL,
    room_name VARCHAR(50) NOT NULL,
    room_type VARCHAR(30) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_rooms_cinema FOREIGN KEY (cinema_id) REFERENCES CINEMAS(cinema_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 4. BẢNG SEAT_TYPES (Loại ghế: STANDARD, VIP, COUPLE)
-- -----------------------------------------------------------------------------
CREATE TABLE SEAT_TYPES (
    seat_type_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    type_name VARCHAR(50) NOT NULL UNIQUE,
    description TEXT NULL,
    price_surcharge DECIMAL(12,2) NOT NULL DEFAULT 0.00
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 5. BẢNG SEATS (Ghế vật lý trong phòng chiếu)
-- -----------------------------------------------------------------------------
CREATE TABLE SEATS (
    seat_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    room_id BIGINT NOT NULL,
    seat_type_id BIGINT NOT NULL,
    seat_row VARCHAR(5) NOT NULL,
    seat_number SMALLINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT fk_seats_room FOREIGN KEY (room_id) REFERENCES ROOMS(room_id) ON DELETE CASCADE,
    CONSTRAINT fk_seats_type FOREIGN KEY (seat_type_id) REFERENCES SEAT_TYPES(seat_type_id) ON DELETE RESTRICT,
    CONSTRAINT uk_seats_room_row_number UNIQUE (room_id, seat_row, seat_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 6. BẢNG GENRES (Danh mục thể loại phim)
-- -----------------------------------------------------------------------------
CREATE TABLE GENRES (
    genre_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    genre_name VARCHAR(50) NOT NULL UNIQUE,
    description TEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 7. BẢNG MOVIES (Danh mục phim - Không chứa trường đạo diễn/diễn viên theo ERD)
-- -----------------------------------------------------------------------------
CREATE TABLE MOVIES (
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

-- -----------------------------------------------------------------------------
-- 8. BẢNG MOVIE_GENRES (Liên kết Phim - Thể loại)
-- -----------------------------------------------------------------------------
CREATE TABLE MOVIE_GENRES (
    movie_id BIGINT NOT NULL,
    genre_id BIGINT NOT NULL,
    PRIMARY KEY (movie_id, genre_id),
    CONSTRAINT fk_movie_genres_movie FOREIGN KEY (movie_id) REFERENCES MOVIES(movie_id) ON DELETE CASCADE,
    CONSTRAINT fk_movie_genres_genre FOREIGN KEY (genre_id) REFERENCES GENRES(genre_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 9. BẢNG SHOWTIMES (Suất chiếu)
-- -----------------------------------------------------------------------------
CREATE TABLE SHOWTIMES (
    showtime_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    movie_id BIGINT NOT NULL,
    room_id BIGINT NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    base_price DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_showtimes_movie FOREIGN KEY (movie_id) REFERENCES MOVIES(movie_id) ON DELETE RESTRICT,
    CONSTRAINT fk_showtimes_room FOREIGN KEY (room_id) REFERENCES ROOMS(room_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 10. BẢNG SHOWTIME_SEATS (Kho trạng thái ghế theo từng suất chiếu)
-- -----------------------------------------------------------------------------
CREATE TABLE SHOWTIME_SEATS (
    showtime_seat_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    showtime_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    seat_status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE',
    hold_token_hash VARCHAR(255) NULL,
    held_by_user_id BIGINT NULL,
    hold_session_id VARCHAR(100) NULL,
    hold_expires_at DATETIME NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_showtime_seats_showtime FOREIGN KEY (showtime_id) REFERENCES SHOWTIMES(showtime_id) ON DELETE CASCADE,
    CONSTRAINT fk_showtime_seats_seat FOREIGN KEY (seat_id) REFERENCES SEATS(seat_id) ON DELETE RESTRICT,
    CONSTRAINT fk_showtime_seats_user FOREIGN KEY (held_by_user_id) REFERENCES USERS(user_id) ON DELETE SET NULL,
    CONSTRAINT uk_showtime_seat UNIQUE (showtime_id, seat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 11. BẢNG PROMOTIONS (Mã giảm giá & Khuyến mãi)
-- -----------------------------------------------------------------------------
CREATE TABLE PROMOTIONS (
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

-- -----------------------------------------------------------------------------
-- 12. BẢNG PRODUCTS (Danh mục Bắp nước & Combo F&B)
-- -----------------------------------------------------------------------------
CREATE TABLE PRODUCTS (
    product_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_name VARCHAR(150) NOT NULL,
    product_type VARCHAR(20) NOT NULL,
    description TEXT NULL,
    current_price DECIMAL(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 13. BẢNG BOOKINGS (Đơn đặt vé / Đơn bán hàng)
-- -----------------------------------------------------------------------------
CREATE TABLE BOOKINGS (
    booking_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_code VARCHAR(40) NOT NULL UNIQUE,
    user_id BIGINT NULL,
    showtime_id BIGINT NOT NULL,
    sold_by_staff_id BIGINT NULL,
    promotion_id BIGINT NULL,
    booking_channel VARCHAR(20) NOT NULL,
    guest_name VARCHAR(100) NULL,
    guest_email VARCHAR(255) NULL,
    guest_phone VARCHAR(20) NULL,
    subtotal DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    total_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    booking_status VARCHAR(20) NOT NULL DEFAULT 'HOLDING',
    booked_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at DATETIME NULL,
    cancelled_at DATETIME NULL,
    CONSTRAINT fk_bookings_user FOREIGN KEY (user_id) REFERENCES USERS(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_bookings_showtime FOREIGN KEY (showtime_id) REFERENCES SHOWTIMES(showtime_id) ON DELETE RESTRICT,
    CONSTRAINT fk_bookings_staff FOREIGN KEY (sold_by_staff_id) REFERENCES USERS(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_bookings_promotion FOREIGN KEY (promotion_id) REFERENCES PROMOTIONS(promotion_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 14. BẢNG PAYMENTS (Các lượt thanh toán)
-- -----------------------------------------------------------------------------
CREATE TABLE PAYMENTS (
    payment_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    payment_provider VARCHAR(30) NOT NULL,
    payment_method VARCHAR(30) NOT NULL,
    provider_transaction_id VARCHAR(150) NULL,
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    amount DECIMAL(12,2) NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'VND',
    payment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    paid_at DATETIME NULL,
    CONSTRAINT fk_payments_booking FOREIGN KEY (booking_id) REFERENCES BOOKINGS(booking_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 15. BẢNG BOOKING_SEATS (Chi tiết ghế đã chọn trong đơn hàng)
-- active_seat_key: Khóa chống đặt trùng ghế đồng thời
-- -----------------------------------------------------------------------------
CREATE TABLE BOOKING_SEATS (
    booking_seat_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    showtime_seat_id BIGINT NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    seat_label_snapshot VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'HOLDING',
    active_seat_key BIGINT NULL UNIQUE,
    CONSTRAINT fk_booking_seats_booking FOREIGN KEY (booking_id) REFERENCES BOOKINGS(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_booking_seats_showtime_seat FOREIGN KEY (showtime_seat_id) REFERENCES SHOWTIME_SEATS(showtime_seat_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 16. BẢNG TICKETS (Vé điện tử phát hành cho từng ghế)
-- -----------------------------------------------------------------------------
CREATE TABLE TICKETS (
    ticket_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_seat_id BIGINT NOT NULL UNIQUE,
    ticket_code VARCHAR(50) NOT NULL UNIQUE,
    qr_token_hash VARCHAR(255) NOT NULL UNIQUE,
    ticket_status VARCHAR(20) NOT NULL DEFAULT 'VALID',
    issued_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    checked_in_at DATETIME NULL,
    checked_in_by BIGINT NULL,
    CONSTRAINT fk_tickets_booking_seat FOREIGN KEY (booking_seat_id) REFERENCES BOOKING_SEATS(booking_seat_id) ON DELETE CASCADE,
    CONSTRAINT fk_tickets_staff FOREIGN KEY (checked_in_by) REFERENCES USERS(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 17. BẢNG BOOKING_ITEMS (Chi tiết bắp nước / combo kèm theo đơn hàng)
-- -----------------------------------------------------------------------------
CREATE TABLE BOOKING_ITEMS (
    booking_item_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    subtotal DECIMAL(12,2) NOT NULL,
    CONSTRAINT fk_booking_items_booking FOREIGN KEY (booking_id) REFERENCES BOOKINGS(booking_id) ON DELETE CASCADE,
    CONSTRAINT fk_booking_items_product FOREIGN KEY (product_id) REFERENCES PRODUCTS(product_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 18. BẢNG INQUIRIES (Yêu cầu liên hệ & Phản hồi khách hàng)
-- -----------------------------------------------------------------------------
CREATE TABLE INQUIRIES (
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
    CONSTRAINT fk_inquiries_user FOREIGN KEY (user_id) REFERENCES USERS(user_id) ON DELETE SET NULL,
    CONSTRAINT fk_inquiries_assigned FOREIGN KEY (assigned_to) REFERENCES USERS(user_id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- CÁC CHỈ MỤC (INDEXES) BỔ SUNG ĐỂ TỐI ƯU HIỆU NĂNG THEO MỤC 1.9.2 SRS
-- -----------------------------------------------------------------------------
CREATE INDEX idx_showtimes_schedule ON SHOWTIMES (room_id, start_at, end_at, status);
CREATE INDEX idx_showtime_seats_lookup ON SHOWTIME_SEATS (showtime_id, seat_status, hold_expires_at);
CREATE INDEX idx_bookings_user_showtime ON BOOKINGS (user_id, showtime_id, booking_status, booked_at);
CREATE INDEX idx_bookings_staff_pos ON BOOKINGS (sold_by_staff_id, booked_at);
CREATE INDEX idx_payments_lookup ON PAYMENTS (booking_id, payment_status, created_at);
CREATE INDEX idx_booking_seats_seat ON BOOKING_SEATS (showtime_seat_id, status);
CREATE INDEX idx_movie_genres_genre ON MOVIE_GENRES (genre_id, movie_id);
CREATE INDEX idx_inquiries_status ON INQUIRIES (inquiry_status, created_at);
CREATE INDEX idx_inquiries_user ON INQUIRIES (user_id);
CREATE INDEX idx_inquiries_assigned ON INQUIRIES (assigned_to);

