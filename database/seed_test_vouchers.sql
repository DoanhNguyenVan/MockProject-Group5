USE box_cinema;

-- Lower min_order_amount for existing vouchers so they are easy to test with 1-2 tickets
UPDATE promotions SET min_order_amount = 0.00, per_user_limit = 100, usage_limit = 10000 WHERE voucher_code IN ('BOXWELCOME', 'BOXDUO', 'BOXSTUDENT', 'BOXFRIDAY');

-- Insert dedicated test vouchers
INSERT INTO promotions (promotion_name, description, voucher_code, discount_type, discount_value, max_discount, min_order_amount, applicable_scope, start_at, end_at, usage_limit, usage_count, per_user_limit, status)
VALUES
('Special Test Voucher - 10K Off', 'Instant 10,000 VND discount for testing. Min order: 0 VND.', 'BOX10K', 'FIXED', 10000.00, 10000.00, 0.00, 'ALL', '2026-01-01 00:00:00', '2027-12-31 23:59:59', 10000, 0, 100, 'ACTIVE'),
('Mega 50% Discount Test', '50% off total booking value, up to 50,000 VND. Min order: 0 VND.', 'BOX50', 'PERCENT', 50.00, 50000.00, 0.00, 'ALL', '2026-01-01 00:00:00', '2027-12-31 23:59:59', 10000, 0, 100, 'ACTIVE'),
('Quick Test 5K Off', '5,000 VND discount for small orders. Min order: 0 VND.', 'BOX5K', 'FIXED', 5000.00, 5000.00, 0.00, 'ALL', '2026-01-01 00:00:00', '2027-12-31 23:59:59', 10000, 0, 100, 'ACTIVE'),
('100% Free Ticket Voucher', '100% discount for testing full payment waiver. Min order: 0 VND.', 'BOXFREE', 'PERCENT', 100.00, 500000.00, 0.00, 'ALL', '2026-01-01 00:00:00', '2027-12-31 23:59:59', 10000, 0, 100, 'ACTIVE')
ON DUPLICATE KEY UPDATE 
    promotion_name = VALUES(promotion_name),
    discount_type = VALUES(discount_type),
    discount_value = VALUES(discount_value),
    max_discount = VALUES(max_discount),
    min_order_amount = VALUES(min_order_amount),
    per_user_limit = VALUES(per_user_limit),
    status = 'ACTIVE';
