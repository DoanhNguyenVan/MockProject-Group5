USE box_cinema;

-- 1. Standardize 4DX Motion rooms: 100% SEAT_4DX (type 5), no wheelchair, no sweetbox, no VIP
UPDATE seats s
JOIN rooms r ON s.room_id = r.room_id
SET s.seat_type_id = 5,
    s.status = 'ACTIVE',
    s.pair_id = NULL
WHERE r.room_type LIKE '%4DX%' OR r.room_type LIKE '%4D%';

-- 2. Standardize Gold Class rooms: 100% RECLINER (type 6), WHEELCHAIR (type 7) at A1, A2
UPDATE seats s
JOIN rooms r ON s.room_id = r.room_id
SET s.seat_type_id = CASE 
        WHEN s.seat_row = 'A' AND s.seat_number IN (1, 2) THEN 7 
        ELSE 6 
    END,
    s.status = 'ACTIVE',
    s.pair_id = NULL
WHERE r.room_type LIKE '%Gold%';

-- 3. Standardize IMAX Laser rooms:
-- 100% ghế chính là IMAX_SEAT (type 4)
-- Hàng đầu góc: A1, A10 là WHEELCHAIR (type 7)
-- Cấm tuyệt đối STANDARD, VIP, SWEETBOX, RECLINER, 4DX
UPDATE seats s
JOIN rooms r ON s.room_id = r.room_id
SET s.seat_type_id = CASE
        WHEN s.seat_row = 'A' AND s.seat_number IN (1, 10) THEN 7
        ELSE 4
    END,
    s.status = 'ACTIVE',
    s.pair_id = NULL
WHERE r.room_type LIKE '%IMAX%';

-- 4. Standardize 2D Standard / 3D Digital / Dolby Atmos rooms:
-- Hàng A góc: A1, A2 là WHEELCHAIR (type 7)
-- Hàng A-C: STANDARD (type 1)
-- Hàng D-E: VIP (type 2)
-- Hàng F: SWEETBOX (type 3) có pair_id
UPDATE seats s
JOIN rooms r ON s.room_id = r.room_id
SET s.seat_type_id = CASE
        WHEN s.seat_row = 'A' AND s.seat_number IN (1, 2) THEN 7
        WHEN s.seat_row IN ('A', 'B', 'C') THEN 1
        WHEN s.seat_row IN ('D', 'E') THEN 2
        WHEN s.seat_row = 'F' THEN 3
        ELSE 1
    END,
    s.status = 'ACTIVE',
    s.pair_id = CASE
        WHEN s.seat_row = 'F' AND s.seat_number IN (1, 2) THEN 'F1-F2'
        WHEN s.seat_row = 'F' AND s.seat_number IN (3, 4) THEN 'F3-F4'
        WHEN s.seat_row = 'F' AND s.seat_number IN (5, 6) THEN 'F5-F6'
        WHEN s.seat_row = 'F' AND s.seat_number IN (7, 8) THEN 'F7-F8'
        WHEN s.seat_row = 'F' AND s.seat_number IN (9, 10) THEN 'F9-F10'
        ELSE NULL
    END
WHERE r.room_type NOT LIKE '%4DX%' 
  AND r.room_type NOT LIKE '%4D%' 
  AND r.room_type NOT LIKE '%Gold%' 
  AND r.room_type NOT LIKE '%IMAX%';
