USE box_cinema;

INSERT INTO user_activity_logs (user_id, email, role, action_type, page_url, page_title, ip_address, location, device_info, created_at)
VALUES 
(30193, 'boxadmin@gmail.com', 'ADMIN', 'LOGIN', '/login', 'Sign In', '118.69.182.45', 'Ho Chi Minh City, VN', 'Chrome 122 on Windows 11', NOW() - INTERVAL 12 MINUTE),
(30193, 'boxadmin@gmail.com', 'ADMIN', 'PAGE_VIEW', '/admin/workspace', 'Admin Workspace', '118.69.182.45', 'Ho Chi Minh City, VN', 'Chrome 122 on Windows 11', NOW() - INTERVAL 11 MINUTE),
(30193, 'boxadmin@gmail.com', 'ADMIN', 'PAGE_VIEW', '/admin/showtimes', 'Manage Showtimes', '118.69.182.45', 'Ho Chi Minh City, VN', 'Chrome 122 on Windows 11', NOW() - INTERVAL 8 MINUTE),
(2, 'manager@boxcinema.vn', 'MANAGER', 'LOGIN', '/login', 'Sign In', '14.161.20.12', 'Hanoi, VN', 'Edge 121 on Windows 10', NOW() - INTERVAL 45 MINUTE),
(2, 'manager@boxcinema.vn', 'MANAGER', 'PAGE_VIEW', '/manager/showtimes', 'Regional Showtimes', '14.161.20.12', 'Hanoi, VN', 'Edge 121 on Windows 10', NOW() - INTERVAL 42 MINUTE),
(3, 'staff@boxcinema.vn', 'STAFF', 'LOGIN', '/login', 'Sign In', '113.160.224.89', 'Da Nang, VN', 'Firefox 123 on macOS', NOW() - INTERVAL 2 HOUR),
(4, 'customer@gmail.com', 'CUSTOMER', 'LOGIN', '/login', 'Sign In', '171.244.38.10', 'Ho Chi Minh City, VN', 'Safari on iOS 17 (iPhone)', NOW() - INTERVAL 3 HOUR),
(4, 'customer@gmail.com', 'CUSTOMER', 'PAGE_VIEW', '/movies', 'Now Showing Movies', '171.244.38.10', 'Ho Chi Minh City, VN', 'Safari on iOS 17 (iPhone)', NOW() - INTERVAL 2 HOUR - INTERVAL 58 MINUTE),
(4, 'customer@gmail.com', 'CUSTOMER', 'PAGE_VIEW', '/booking/buy-tickets', 'Seat Booking', '171.244.38.10', 'Ho Chi Minh City, VN', 'Safari on iOS 17 (iPhone)', NOW() - INTERVAL 2 HOUR - INTERVAL 50 MINUTE);
