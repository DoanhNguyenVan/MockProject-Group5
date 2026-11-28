-- =============================================================================
-- BOX CINEMA - SUẤT CHIẾU TỐI HÔM NAY 2026-11-27
-- Khung giờ: 18:00 | 20:15 | 22:30  (6 CH → 12 ĐÊM)
-- DYNAMIC: chỉ insert phim/phòng thực sự tồn tại trong DB
-- Không hard-code movie_id  →  tránh lỗi FK 1452
-- =============================================================================

USE box_cinema;

-- Bật IGNORE để bỏ qua suất đã tồn tại (tránh duplicate)
-- =============================================================================
-- BƯỚC 1: Mỗi (phim × slot) → 1 phòng theo hash
--   hash = (movie_id × 7 + giờ_slot) MOD tổng_phòng
--   NOT EXISTS  → bỏ qua nếu phòng đó giờ đó đã có suất chiếu
-- =============================================================================

INSERT INTO showtimes (movie_id, room_id, start_at, end_at, base_price, status)
SELECT
    m.movie_id,
    r.room_id,
    TIMESTAMP('2026-11-27', t.slot_time)                                       AS start_at,
    TIMESTAMPADD(MINUTE, m.duration_minutes + 15,
                 TIMESTAMP('2026-11-27', t.slot_time))                         AS end_at,
    CASE
        WHEN r.room_type LIKE '%IMAX%'  THEN 140000.00
        WHEN r.room_type LIKE '%4DX%'   THEN 130000.00
        WHEN r.room_type LIKE '%Dolby%' THEN 105000.00
        ELSE 85000.00
    END                                                                        AS base_price,
    'ACTIVE'
FROM movies m
CROSS JOIN (
    SELECT '18:00:00' AS slot_time UNION ALL
    SELECT '20:15:00'              UNION ALL
    SELECT '22:30:00'
) t
-- Phân phòng theo hash: đảm bảo chỉ join đúng 1 phòng mỗi (phim × slot)
JOIN (
    SELECT room_id, room_type,
           ROW_NUMBER() OVER (ORDER BY room_id) AS rn,
           COUNT(*)     OVER ()                 AS total_rooms
    FROM rooms
    WHERE status = 'ACTIVE'
) r ON r.rn = 1 + ((m.movie_id * 7 + FLOOR(TIME_TO_SEC(t.slot_time) / 3600)) MOD r.total_rooms)
WHERE m.status = 'NOW_SHOWING'
  -- Bỏ qua nếu phòng đó trong slot đó đã có suất bất kỳ
  AND NOT EXISTS (
      SELECT 1 FROM showtimes s2
      WHERE s2.room_id  = r.room_id
        AND s2.start_at = TIMESTAMP('2026-11-27', t.slot_time)
  );

-- =============================================================================
-- BƯỚC 2: Với những phòng vẫn còn slot tối trống (chưa được fill ở bước 1),
--         chọn phim ngẫu nhiên từ NOW_SHOWING để lấp đầy
-- =============================================================================

INSERT INTO showtimes (movie_id, room_id, start_at, end_at, base_price, status)
SELECT
    m.movie_id,
    r.room_id,
    TIMESTAMP('2026-11-27', t.slot_time)                                       AS start_at,
    TIMESTAMPADD(MINUTE, m.duration_minutes + 15,
                 TIMESTAMP('2026-11-27', t.slot_time))                         AS end_at,
    CASE
        WHEN r.room_type LIKE '%IMAX%'  THEN 140000.00
        WHEN r.room_type LIKE '%4DX%'   THEN 130000.00
        WHEN r.room_type LIKE '%Dolby%' THEN 105000.00
        ELSE 85000.00
    END                                                                        AS base_price,
    'ACTIVE'
FROM rooms r
CROSS JOIN (
    SELECT '18:00:00' AS slot_time UNION ALL
    SELECT '20:15:00'              UNION ALL
    SELECT '22:30:00'
) t
-- Với mỗi (phòng × slot) trống, chọn 1 phim theo hash khác
JOIN (
    SELECT movie_id, duration_minutes,
           ROW_NUMBER() OVER (ORDER BY movie_id) AS rn,
           COUNT(*)     OVER ()                  AS total
    FROM movies
    WHERE status = 'NOW_SHOWING'
) m ON m.rn = 1 + ((r.room_id * 3 + FLOOR(TIME_TO_SEC(t.slot_time) / 3600)) MOD m.total)
WHERE r.status = 'ACTIVE'
  -- Chỉ insert nếu slot đó của phòng vẫn còn trống
  AND NOT EXISTS (
      SELECT 1 FROM showtimes s2
      WHERE s2.room_id  = r.room_id
        AND s2.start_at = TIMESTAMP('2026-11-27', t.slot_time)
  );

-- =============================================================================
-- KIỂM TRA KẾT QUẢ
-- =============================================================================
SELECT
    c.cinema_name                      AS rap,
    r.room_name                        AS phong,
    m.title                            AS phim,
    DATE_FORMAT(s.start_at, '%H:%i')   AS bat_dau,
    DATE_FORMAT(s.end_at,   '%H:%i')   AS ket_thuc,
    FORMAT(s.base_price, 0)            AS gia_ve_vnd
FROM showtimes s
JOIN rooms   r ON r.room_id   = s.room_id
JOIN cinemas c ON c.cinema_id = r.cinema_id
JOIN movies  m ON m.movie_id  = s.movie_id
WHERE DATE(s.start_at) = '2026-11-27'
  AND HOUR(s.start_at) >= 18
ORDER BY c.cinema_id, r.room_id, s.start_at;
