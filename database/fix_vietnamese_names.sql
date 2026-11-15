SET NAMES utf8mb4;
SET CHARACTER SET utf8mb4;

-- Fix users full_name
UPDATE users SET full_name = 'Doanh Nguyễn' WHERE user_id IN (4, 5, 8);

-- Fix bookings 31 to 35
UPDATE bookings SET guest_name = 'Trần Minh Hoàng' WHERE booking_id = 31;
UPDATE bookings SET guest_name = 'Lê Thị Thu Thảo' WHERE booking_id = 32;
UPDATE bookings SET guest_name = 'Phạm Quốc Bảo' WHERE booking_id = 33;
UPDATE bookings SET guest_name = 'Đặng Thùy Dung' WHERE booking_id = 34;
UPDATE bookings SET guest_name = 'Nguyễn Hải Nam' WHERE booking_id = 35;

-- Fix bookings 1 to 30
UPDATE bookings SET guest_name = 'Nguyễn Văn An' WHERE booking_id = 1;
UPDATE bookings SET guest_name = 'Trần Thị Mai' WHERE booking_id = 2;
UPDATE bookings SET guest_name = 'Lê Hoàng Long' WHERE booking_id = 3;
UPDATE bookings SET guest_name = 'Phạm Minh Đức' WHERE booking_id = 4;
UPDATE bookings SET guest_name = 'Vũ Hải Đăng' WHERE booking_id = 5;
UPDATE bookings SET guest_name = 'Hoàng Thu Trang' WHERE booking_id = 6;
UPDATE bookings SET guest_name = 'Đặng Quốc Huy' WHERE booking_id = 7;
UPDATE bookings SET guest_name = 'Bùi Phương Linh' WHERE booking_id = 8;
UPDATE bookings SET guest_name = 'Đỗ Tuấn Kiệt' WHERE booking_id = 9;
UPDATE bookings SET guest_name = 'Ngô Thảo Nguyên' WHERE booking_id = 10;
UPDATE bookings SET guest_name = 'Dương Đình Trọng' WHERE booking_id = 11;
UPDATE bookings SET guest_name = 'Đinh Ngọc Hân' WHERE booking_id = 12;
UPDATE bookings SET guest_name = 'Võ Thành Đạt' WHERE booking_id = 13;
UPDATE bookings SET guest_name = 'Trịnh Kim Ngân' WHERE booking_id = 14;
UPDATE bookings SET guest_name = 'Phan Bảo Châu' WHERE booking_id = 15;
UPDATE bookings SET guest_name = 'Nguyễn Minh Quân' WHERE booking_id = 16;
UPDATE bookings SET guest_name = 'Trần Đức Thắng' WHERE booking_id = 17;
UPDATE bookings SET guest_name = 'Lê Khánh Vy' WHERE booking_id = 18;
UPDATE bookings SET guest_name = 'Hồ Gia Bảo' WHERE booking_id = 19;
UPDATE bookings SET guest_name = 'Huỳnh Như Quỳnh' WHERE booking_id = 20;
UPDATE bookings SET guest_name = 'Đoàn Quốc Tuấn' WHERE booking_id = 21;
UPDATE bookings SET guest_name = 'Lâm Hoàng Yến' WHERE booking_id = 22;
UPDATE bookings SET guest_name = 'Mai Trọng Hiếu' WHERE booking_id = 23;
UPDATE bookings SET guest_name = 'Tạ Quỳnh Nga' WHERE booking_id = 24;
UPDATE bookings SET guest_name = 'Lý Gia Huy' WHERE booking_id = 25;
UPDATE bookings SET guest_name = 'Vương Mỹ Duyên' WHERE booking_id = 26;
UPDATE bookings SET guest_name = 'Lương Bách Khoa' WHERE booking_id = 27;
UPDATE bookings SET guest_name = 'Trương Tuấn Anh' WHERE booking_id = 28;
UPDATE bookings SET guest_name = 'Nguyễn Văn An' WHERE booking_id = 29;
UPDATE bookings SET guest_name = 'Trần Minh Hoàng' WHERE booking_id = 30;
