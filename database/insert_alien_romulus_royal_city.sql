-- =============================================================================
-- INSERT SUẤT CHIẾU HÔM NAY 2026-11-27 CHO ALIEN: ROMULUS TẠI ROYAL CITY
-- Đã thực thi trực tiếp vào database box_cinema thành công!
-- =============================================================================

USE box_cinema;

INSERT INTO showtimes (movie_id, room_id, start_at, end_at, base_price, status) VALUES
-- Screen 01 - Standard Luxe (2D Standard) | 85.000 đ
(1, 1, '2026-11-27 22:15:00', '2026-11-28 00:29:00', 85000.00, 'ACTIVE'),

-- Screen 04 - Gold Class (Gold Class) | 95.000 đ
(1, 4, '2026-11-27 22:30:00', '2026-11-28 00:44:00', 95000.00, 'ACTIVE'),

-- Screen 03 - 4DX Motion (4DX Motion) | 130.000 đ
(1, 3, '2026-11-27 22:45:00', '2026-11-28 00:59:00', 130000.00, 'ACTIVE'),

-- Screen 02 - IMAX Laser (IMAX Laser) | 140.000 đ
(1, 2, '2026-11-27 23:00:00', '2026-11-28 01:14:00', 140000.00, 'ACTIVE');

-- Kiểm tra lại:
SELECT s.showtime_id, m.title, c.cinema_name, r.room_name, r.room_type, s.start_at, s.end_at, s.base_price, s.status
FROM showtimes s
JOIN movies m ON s.movie_id = m.movie_id
JOIN rooms r ON s.room_id = r.room_id
JOIN cinemas c ON r.cinema_id = c.cinema_id
WHERE s.movie_id = 1 AND r.cinema_id = 1 AND DATE(s.start_at) = '2026-11-27'
ORDER BY s.start_at;
