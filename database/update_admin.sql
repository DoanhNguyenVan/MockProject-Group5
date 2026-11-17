USE box_cinema;

UPDATE users 
SET password_hash = '$2a$10$ibmjNoYWS33i1NuO4QO00O5LHGvJxb0m9S9.LghpSitf2FUrBUct2',
    account_status = 'ACTIVE',
    role = 'ADMIN',
    updated_at = NOW()
WHERE email IN ('admin@gmail.com', 'admin@boxcinema.vn', 'admin@boxcinema.com');

INSERT INTO users (full_name, email, phone, password_hash, role, account_status, created_at, updated_at)
VALUES (
    'BOX Cinema Administrator',
    'admin@gmail.com',
    '0909999991',
    '$2a$10$ibmjNoYWS33i1NuO4QO00O5LHGvJxb0m9S9.LghpSitf2FUrBUct2',
    'ADMIN',
    'ACTIVE',
    NOW(),
    NOW()
) ON DUPLICATE KEY UPDATE 
    password_hash = '$2a$10$ibmjNoYWS33i1NuO4QO00O5LHGvJxb0m9S9.LghpSitf2FUrBUct2',
    account_status = 'ACTIVE',
    role = 'ADMIN',
    updated_at = NOW();

INSERT INTO users (full_name, email, phone, password_hash, role, account_status, created_at, updated_at)
VALUES (
    'System Administrator',
    'admin@boxcinema.vn',
    '0909999990',
    '$2a$10$ibmjNoYWS33i1NuO4QO00O5LHGvJxb0m9S9.LghpSitf2FUrBUct2',
    'ADMIN',
    'ACTIVE',
    NOW(),
    NOW()
) ON DUPLICATE KEY UPDATE 
    password_hash = '$2a$10$ibmjNoYWS33i1NuO4QO00O5LHGvJxb0m9S9.LghpSitf2FUrBUct2',
    account_status = 'ACTIVE',
    role = 'ADMIN',
    updated_at = NOW();
