-- =============================================================================
-- MOCK DATA FOR SELECT SEATS ON SEAT MAP & HOLD SEATS WITH EXPIRY
-- Populates:
--   1. Schema column SEATS.pair_id (if missing) & clean UTF-8 SEAT_TYPES
--   2. Physical SEATS for all 28 ROOMS (Standard, VIP, Sweetbox pairs, IMAX, 4DX, Wheelchair)
--   3. Active F&B PRODUCTS (Combos, Popcorn, Drinks)
--   4. Dynamic SHOWTIMES for Today (future slots) + Next 7 Days across all Cinemas & Movies
--   5. Mock BOOKINGS, BOOKING_SEATS (BOOKED seats) & SHOWTIME_SEATS (HELD seats with expiry)
-- =============================================================================

USE box_cinema;
SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

-- 1. Ensure pair_id column exists on SEATS table
SET @col_exists := (
    SELECT COUNT(*)
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'seats'
      AND COLUMN_NAME = 'pair_id'
);
SET @alter_sql := IF(
    @col_exists = 0,
    'ALTER TABLE seats ADD COLUMN pair_id VARCHAR(30) NULL AFTER status',
    'SELECT 1'
);
PREPARE stmt FROM @alter_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. Fix UTF-8 descriptions in SEAT_TYPES
UPDATE seat_types SET description = 'Ghế tiêu chuẩn với tầm nhìn tối ưu và đệm êm ái.', shape_type = 'standard', price_surcharge = 0.00 WHERE seat_type_id = 1;
UPDATE seat_types SET description = 'Ghế VIP hàng giữa trung tâm, góc nhìn và âm thanh chuẩn nhất rạp.', shape_type = 'vip', price_surcharge = 30000.00 WHERE seat_type_id = 2;
UPDATE seat_types SET description = 'Ghế sofa đôi Sweetbox rộng rãi ở hàng cuối dành cho 2 người.', shape_type = 'sweetbox', price_surcharge = 50000.00 WHERE seat_type_id = 3;
UPDATE seat_types SET description = 'Ghế công thái học tối ưu tầm nhìn màn hình cực đại IMAX Laser.', shape_type = 'imax', price_surcharge = 60000.00 WHERE seat_type_id = 4;
UPDATE seat_types SET description = 'Ghế chuyển động đa chiều kèm hiệu ứng gió, nước, ánh sáng 4DX.', shape_type = '4dx', price_surcharge = 70000.00 WHERE seat_type_id = 5;
UPDATE seat_types SET description = 'Ghế ngả lưng bọc da cao cấp 180 độ tại phòng chiếu Gold Class.', shape_type = 'recliner', price_surcharge = 100000.00 WHERE seat_type_id = 6;
UPDATE seat_types SET description = 'Vị trí ưu tiên không gian phẳng dành cho khách hàng sử dụng xe lăn.', shape_type = 'wheelchair', price_surcharge = 0.00 WHERE seat_type_id = 7;

-- 3. Clean old seats/showtimes/bookings to seed fresh, consistent mock data
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE tickets;
TRUNCATE TABLE booking_items;
TRUNCATE TABLE booking_seats;
TRUNCATE TABLE payments;
TRUNCATE TABLE bookings;
TRUNCATE TABLE showtime_seats;
TRUNCATE TABLE showtimes;
TRUNCATE TABLE seats;
TRUNCATE TABLE products;
SET FOREIGN_KEY_CHECKS = 1;

-- 4. Seed F&B Products (Combos, Popcorn, Beverages)
INSERT INTO products (product_id, product_name, product_type, description, current_price, status) VALUES
(1, 'Solo Popcorn Combo', 'POPCORN', '1 Bắp ngọt lớn + 1 Nước ngọt có ga (Pepsi / 7Up / Mirinda) cỡ lớn', 89000.00, 'ACTIVE'),
(2, 'Couple Movie Night Combo', 'COMBO', '1 Bắp phô mai & caramel cỡ đại + 2 Nước ngọt cỡ lớn + 1 Snack khoai tây', 139000.00, 'ACTIVE'),
(3, 'VIP Blockbuster Party Combo', 'COMBO', '2 Bắp lớn (tự chọn vị) + 4 Nước ngọt lớn + 2 Xúc xích nướng', 249000.00, 'ACTIVE'),
(4, 'Signature Caramel Popcorn', 'POPCORN', 'Bắp rang bơ phủ sốt Caramel thượng hạng giòn tan cỡ lớn', 65000.00, 'ACTIVE'),
(5, 'Refreshing Soft Drink (Large)', 'DRINK', 'Nước ngọt giải khát ướp lạnh cỡ lớn (32oz)', 39000.00, 'ACTIVE');

-- 5. Generate standardized seats for all 28 rooms (Rows A..F, Columns 1..10 = 60 seats per room)
INSERT INTO seats (room_id, seat_type_id, seat_row, seat_number, status, pair_id)
SELECT
    r.room_id,
    CASE
        WHEN r.room_type LIKE '%4DX%' OR r.room_type LIKE '%4D%' THEN 5
        WHEN r.room_type LIKE '%IMAX%' THEN
            CASE WHEN rw.seat_row = 'A' AND cl.seat_number IN (1, 10) THEN 7 ELSE 4 END
        WHEN r.room_type LIKE '%Gold%' THEN
            CASE WHEN rw.seat_row = 'A' AND cl.seat_number IN (1, 2) THEN 7 ELSE 6 END
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
             AND r.room_type NOT LIKE '%IMAX%' AND r.room_type NOT LIKE '%Gold%'
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

-- Add Row G (Sweetbox / VIP) for IMAX & Standard rooms so users see variety
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

-- 6. Ensure all NOW_SHOWING movies have valid release_date / end_date around CURDATE()
UPDATE movies
SET release_date = DATE_SUB(CURDATE(), INTERVAL 10 DAY),
    end_date = DATE_ADD(CURDATE(), INTERVAL 45 DAY)
WHERE status = 'NOW_SHOWING';

-- 7. Seed rich SHOWTIMES for Today (future hours) and Next 7 Days across all 12 cinemas
INSERT INTO showtimes (movie_id, room_id, start_at, end_at, base_price, status, created_at)
SELECT
    m.movie_id,
    r.room_id,
    TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d.day_offset DAY), t.slot_time) AS start_at,
    TIMESTAMPADD(MINUTE, m.duration_minutes + 15, TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d.day_offset DAY), t.slot_time)) AS end_at,
    CASE
        WHEN r.room_type LIKE '%IMAX%' THEN 135000.00
        WHEN r.room_type LIKE '%4DX%' THEN 125000.00
        WHEN r.room_type LIKE '%Dolby%' THEN 105000.00
        ELSE 90000.00
    END AS base_price,
    'ACTIVE' AS status,
    NOW()
FROM movies m
CROSS JOIN (
    SELECT 0 AS day_offset UNION ALL
    SELECT 1 UNION ALL
    SELECT 2 UNION ALL
    SELECT 3 UNION ALL
    SELECT 4 UNION ALL
    SELECT 5 UNION ALL
    SELECT 6 UNION ALL
    SELECT 7
) d
CROSS JOIN (
    SELECT '10:15:00' AS slot_time, 1 AS slot_idx UNION ALL
    SELECT '13:30:00', 2 UNION ALL
    SELECT '16:15:00', 3 UNION ALL
    SELECT '18:30:00', 4 UNION ALL
    SELECT '20:15:00', 5 UNION ALL
    SELECT '22:00:00', 6 UNION ALL
    SELECT '23:30:00', 7
) t
JOIN rooms r ON r.room_id = (1 + ((m.movie_id * 5 + d.day_offset * 3 + t.slot_idx) MOD 28))
WHERE m.status = 'NOW_SHOWING'
  AND TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d.day_offset DAY), t.slot_time) > DATE_ADD(NOW(), INTERVAL 15 MINUTE);

-- Also ensure Flagship Cinemas (Cinema 1 in Hanoi & Cinema 5 Landmark 81 in HCMC) have screenings every day for top movies (1..6)
INSERT INTO showtimes (movie_id, room_id, start_at, end_at, base_price, status, created_at)
SELECT
    m.movie_id,
    r.room_id,
    TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d.day_offset DAY), t.slot_time) AS start_at,
    TIMESTAMPADD(MINUTE, m.duration_minutes + 15, TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d.day_offset DAY), t.slot_time)) AS end_at,
    CASE
        WHEN r.room_type LIKE '%IMAX%' THEN 140000.00
        WHEN r.room_type LIKE '%4DX%' THEN 130000.00
        WHEN r.room_type LIKE '%Dolby%' THEN 110000.00
        ELSE 90000.00
    END AS base_price,
    'ACTIVE' AS status,
    NOW()
FROM movies m
JOIN rooms r ON r.room_id IN (1, 2, 11, 12, 14)
CROSS JOIN (
    SELECT 0 AS day_offset UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3
) d
CROSS JOIN (
    SELECT '17:45:00' AS slot_time UNION ALL
    SELECT '19:45:00' UNION ALL
    SELECT '21:45:00' UNION ALL
    SELECT '23:15:00'
) t
WHERE m.movie_id IN (1, 2, 3, 4, 5, 16)
  AND TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d.day_offset DAY), t.slot_time) > DATE_ADD(NOW(), INTERVAL 15 MINUTE);

-- 8. Seed mock SHOWTIME_SEATS for upcoming showtimes so every showtime has:
--    - BOOKED seats (C5, C6, D5, D6) -> Red on seat map (cannot be selected)
--    - HELD seats by another customer (D3, D4, F3, F4) -> Sky Blue on seat map with active 30-min hold expiry (TS-BOOK-002)
--    - Expired HELD seats (B3, B4) -> Expired 5 mins ago so backend auto-releases them to AVAILABLE (TS-BOOK-003)

-- 8a. Insert BOOKED seats (C5, C6, D5, D6) into showtime_seats for the first 150 upcoming showtimes
INSERT IGNORE INTO showtime_seats (showtime_id, seat_id, seat_status, hold_token_hash, held_by_user_id, hold_session_id, hold_expires_at)
SELECT
    st.showtime_id,
    s.seat_id,
    'BOOKED' AS seat_status,
    NULL,
    1,
    NULL,
    NULL
FROM (SELECT showtime_id, room_id FROM showtimes WHERE start_at > NOW() ORDER BY start_at ASC LIMIT 150) st
JOIN seats s ON s.room_id = st.room_id
WHERE (s.seat_row = 'C' AND s.seat_number IN (5, 6))
   OR (s.seat_row = 'D' AND s.seat_number IN (5, 6));

-- 8b. Insert HELD seats by another customer (D3, D4 and Sweetbox F3, F4) with active hold_expires_at (25 minutes from now)
INSERT IGNORE INTO showtime_seats (showtime_id, seat_id, seat_status, hold_token_hash, held_by_user_id, hold_session_id, hold_expires_at)
SELECT
    st.showtime_id,
    s.seat_id,
    'HELD' AS seat_status,
    SHA2(CONCAT('other_hold_', st.showtime_id, '_', s.seat_id), 256),
    NULL,
    'mock-other-customer-session-001',
    DATE_ADD(NOW(), INTERVAL 25 MINUTE)
FROM (SELECT showtime_id, room_id FROM showtimes WHERE start_at > NOW() ORDER BY start_at ASC LIMIT 150) st
JOIN seats s ON s.room_id = st.room_id
WHERE (s.seat_row = 'D' AND s.seat_number IN (3, 4))
   OR (s.seat_row = 'F' AND s.seat_number IN (3, 4));

-- 8c. Insert EXPIRED HELD seats (B3, B4) with hold_expires_at in the past (to verify automatic expiry cleanup)
INSERT IGNORE INTO showtime_seats (showtime_id, seat_id, seat_status, hold_token_hash, held_by_user_id, hold_session_id, hold_expires_at)
SELECT
    st.showtime_id,
    s.seat_id,
    'HELD' AS seat_status,
    SHA2(CONCAT('expired_hold_', st.showtime_id, '_', s.seat_id), 256),
    NULL,
    'mock-expired-session-999',
    DATE_SUB(NOW(), INTERVAL 5 MINUTE)
FROM (SELECT showtime_id, room_id FROM showtimes WHERE start_at > NOW() ORDER BY start_at ASC LIMIT 50) st
JOIN seats s ON s.room_id = st.room_id
WHERE s.seat_row = 'B' AND s.seat_number IN (3, 4);


SELECT
    (SELECT COUNT(*) FROM seats) AS total_seats,
    (SELECT COUNT(*) FROM showtimes WHERE start_at > NOW()) AS future_showtimes,
    (SELECT COUNT(*) FROM showtime_seats WHERE seat_status = 'BOOKED') AS booked_seats,
    (SELECT COUNT(*) FROM showtime_seats WHERE seat_status = 'HELD' AND hold_expires_at > NOW()) AS active_held_seats,
    (SELECT COUNT(*) FROM showtime_seats WHERE seat_status = 'HELD' AND hold_expires_at <= NOW()) AS expired_held_seats,
    (SELECT COUNT(*) FROM products) AS total_products;
