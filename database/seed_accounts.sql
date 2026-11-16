

USE box_cinema;

-- -----------------------------------------------
-- 1. Tài khoản ADMIN (Quản trị viên hệ thống)
-- -----------------------------------------------
INSERT INTO users (full_name, email, phone, password_hash, role, account_status, created_at, updated_at)
VALUES (
    'BOX Cinema Administrator',
    'admin@gmail.com',
    '0909999991',
    '$2a$10$1lo24jWmA8SV65ErRmY/fuKzdUBhepzaK5JyoUFt.ceYQ9FtCg7Xy',
    'ADMIN',
    'ACTIVE',
    NOW(),
    NOW()
) ON DUPLICATE KEY UPDATE updated_at = NOW();

-- -----------------------------------------------
-- 2. Tài khoản MANAGER (Quản lý rạp)
-- -----------------------------------------------
INSERT INTO users (full_name, email, phone, password_hash, role, cinema_id, account_status, created_at, updated_at)
VALUES (
    'Manager1',
    'manager1@gmail.com',
    '0909999992',
    '$2a$10$yF8r/z1ZqfB6GjY6M9yIfe4K.q43r6K29oDk9tBkWfD9NqC7cT0eW',
    'MANAGER',
    NULL,
    'ACTIVE',
    NOW(),
    NOW()
) ON DUPLICATE KEY UPDATE updated_at = NOW();

-- -----------------------------------------------
-- 3. Tài khoản STAFF (Nhân viên bán vé / soát vé)
-- -----------------------------------------------
INSERT INTO users (full_name, email, phone, password_hash, role, cinema_id, account_status, created_at, updated_at)
VALUES (
    'Staff1',
    'staff1@gmail.com',
    '0909999993',
    '$2a$10$yF8r/z1ZqfB6GjY6M9yIfe4K.q43r6K29oDk9tBkWfD9NqC7cT0eW',
    'STAFF',
    NULL,
    'ACTIVE',
    NOW(),
    NOW()
) ON DUPLICATE KEY UPDATE updated_at = NOW();

-- ============================================================
-- Kiểm tra kết quả
-- ============================================================
SELECT user_id, full_name, email, phone, role, account_status
FROM users
WHERE role IN ('ADMIN', 'MANAGER', 'STAFF')
ORDER BY
    FIELD(role, 'ADMIN', 'MANAGER', 'STAFF'),
    user_id;

