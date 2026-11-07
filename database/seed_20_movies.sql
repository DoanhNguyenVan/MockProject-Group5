-- =============================================================================
-- SCRIPT INSERT 20 PHIM VÀ DỮ LIỆU LIÊN QUAN CHO BOX CINEMA
-- Bao gồm: Thể loại, Cụm rạp, Phòng chiếu, 20 Phim (15 Đang chiếu + 5 Sắp chiếu)
-- và các Suất chiếu (Showtimes) theo ngày thực tế để test toàn bộ Trang chủ.
-- =============================================================================

USE box_cinema;

SET FOREIGN_KEY_CHECKS = 0;

-- Làm sạch dữ liệu các bảng liên quan để nạp mới tinh
TRUNCATE TABLE showtime_seats;
TRUNCATE TABLE showtimes;
TRUNCATE TABLE movie_genres;
TRUNCATE TABLE movies;
TRUNCATE TABLE rooms;
TRUNCATE TABLE cinemas;
TRUNCATE TABLE genres;

SET FOREIGN_KEY_CHECKS = 1;

-- -----------------------------------------------------------------------------
-- 1. THỂ LOẠI PHIM (GENRES)
-- -----------------------------------------------------------------------------
INSERT INTO GENRES (genre_id, genre_name, description) VALUES
(1, 'Hành động', 'Phim có nhiều pha hành động gay cấn, rượt đuổi, chiến đấu.'),
(2, 'Khoa học viễn tưởng', 'Khám phá không gian, công nghệ tương lai, thế giới song song.'),
(3, 'Tâm lý', 'Khai thác chiều sâu tâm trạng, số phận con người và tình cảm.'),
(4, 'Kinh dị', 'Mang lại cảm giác hồi hộp, sợ hãi, bí ẩn rùng rợn.'),
(5, 'Hoạt hình', 'Phim hoạt hình đồ họa đặc sắc cho gia đình và giới trẻ.'),
(6, 'Phiêu lưu', 'Những chuyến hành trình khám phá vùng đất mới đầy mạo hiểm.'),
(7, 'Hài', 'Những tình huống hài hước mang lại tiếng cười sảng khoái.'),
(8, 'Giật gân', 'Tiết tấu nhanh, nghẹt thở, những cú twist khó đoán.'),
(9, 'Bí ẩn', 'Giải mã bí ẩn, điều tra vụ án, trinh thám ly kỳ.'),
(10, 'Nhạc kịch', 'Kết hợp âm nhạc, vũ đạo hoành tráng và câu chuyện cảm động.');

-- -----------------------------------------------------------------------------
-- 2. CỤM RẠP (CINEMAS)
-- -----------------------------------------------------------------------------
INSERT INTO CINEMAS (cinema_id, cinema_name, address, city, phone, email, status) VALUES
(1, 'BOX Cinema Landmark 81', 'Tầng B1, Vincom Landmark 81, 720A Điện Biên Phủ, Bình Thạnh', 'TP. Hồ Chí Minh', '028 3822 8181', 'landmark81@boxcinema.vn', 'ACTIVE'),
(2, 'BOX Cinema Vincom Center', 'Tầng 5, 72 Lê Thánh Tôn, Bến Nghé, Quận 1', 'TP. Hồ Chí Minh', '028 3939 6868', 'vincom.d1@boxcinema.vn', 'ACTIVE'),
(3, 'BOX Cinema Lotte Mall Tây Hồ', 'Tầng 4, Lotte Mall Tây Hồ, 272 Võ Chí Công, Tây Hồ', 'Hà Nội', '024 3788 8899', 'tayho@boxcinema.vn', 'ACTIVE'),
(4, 'BOX Cinema Vincom Bà Triệu', 'Tầng 6, Vincom Center, 191 Bà Triệu, Hai Bà Trưng', 'Hà Nội', '024 3974 9999', 'batrieu@boxcinema.vn', 'ACTIVE'),
(5, 'BOX Cinema Vincom Plaza Ngô Quyền', 'Tầng 4, Vincom Plaza, 910A Ngô Quyền, Sơn Trà', 'Đà Nẵng', '0236 399 6688', 'danang@boxcinema.vn', 'ACTIVE');

-- -----------------------------------------------------------------------------
-- 3. PHÒNG CHIẾU (ROOMS)
-- -----------------------------------------------------------------------------
INSERT INTO ROOMS (room_id, cinema_id, room_name, room_type, status) VALUES
-- Rạp 1: Landmark 81
(1, 1, 'Screen 01 - Laser', 'IMAX Laser', 'ACTIVE'),
(2, 1, 'Screen 02 - Atmos', 'Dolby Atmos', 'ACTIVE'),
(3, 1, 'Screen 03 - 4DX', '4DX Motion', 'ACTIVE'),
(4, 1, 'Screen 04 - Standard', 'Standard', 'ACTIVE'),

-- Rạp 2: Vincom D1
(5, 2, 'Screen 01 - Laser', 'IMAX Laser', 'ACTIVE'),
(6, 2, 'Screen 02 - Atmos', 'Dolby Atmos', 'ACTIVE'),
(7, 2, 'Screen 03 - Standard', 'Standard', 'ACTIVE'),

-- Rạp 3: Lotte Mall Tây Hồ
(8, 3, 'Screen 01 - Laser', 'IMAX Laser', 'ACTIVE'),
(9, 3, 'Screen 02 - Atmos', 'Dolby Atmos', 'ACTIVE'),
(10, 3, 'Screen 03 - 4DX', '4DX Motion', 'ACTIVE'),

-- Rạp 4: Vincom Bà Triệu
(11, 4, 'Screen 01 - Atmos', 'Dolby Atmos', 'ACTIVE'),
(12, 4, 'Screen 02 - Standard', 'Standard', 'ACTIVE'),

-- Rạp 5: Đà Nẵng
(13, 5, 'Screen 01 - Laser', 'IMAX Laser', 'ACTIVE'),
(14, 5, 'Screen 02 - Atmos', 'Dolby Atmos', 'ACTIVE');

-- -----------------------------------------------------------------------------
-- 4. 20 PHIM CHI TIẾT (MOVIES)
-- 15 Phim Đang Chiếu (NOW_SHOWING) + 5 Phim Sắp Chiếu (COMING_SOON)
-- -----------------------------------------------------------------------------
INSERT INTO MOVIES (
    movie_id, title, synopsis, duration_minutes, age_rating, language,
    release_date, end_date, status, poster_url, banner_url, trailer_url
) VALUES
-- 1. Dune: Part Two
(1, 'Dune: Hành Tinh Cát - Phần 2',
 'Paul Atreides hợp lực cùng Chani và tộc người Fremen để trả thù những kẻ đã hủy diệt gia tộc mình, đối mặt với ngã rẽ giữa tình yêu và định mệnh vũ trụ.',
 166, 'T16', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 14 DAY), DATE_ADD(CURDATE(), INTERVAL 45 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg',
 'https://image.tmdb.org/t/p/w1280/uhUO7vQQKvCTfQWubOt5MAKokbL.jpg',
 'https://www.youtube.com/watch?v=Way9Dexny3w'),

-- 2. Deadpool & Wolverine
(2, 'Deadpool & Wolverine',
 'Wade Wilson tái xuất giang hồ và bất đắc dĩ phải lôi kéo Wolverine đang chán đời cùng hợp tác trong nhiệm vụ giải cứu đa vũ trụ đầy tiếng cười và bạo lực.',
 128, 'T18', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_ADD(CURDATE(), INTERVAL 40 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg',
 'https://image.tmdb.org/t/p/w1280/dvBCdCohwWbsP5qAaglOXagDMtk.jpg',
 'https://www.youtube.com/watch?v=73_1biulkYk'),

-- 3. Oppenheimer
(3, 'Oppenheimer',
 'Câu chuyện kịch tính về cuộc đời nhà vật lý J. Robert Oppenheimer - người đứng đầu Dự án Manhattan khai sinh ra bom nguyên tử làm thay đổi lịch sử nhân loại.',
 180, 'T18', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 20 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg',
 'https://image.tmdb.org/t/p/w1280/neeNHeXjMF5fXoCJRsOmkNGC7q.jpg',
 'https://www.youtube.com/watch?v=uYPbbksJxIg'),

-- 4. Inside Out 2
(4, 'Những Mảnh Ghép Cảm Xúc 2',
 'Riley bước vào tuổi dậy thì với sự xáo trộn bất ngờ khi bộ tứ cảm xúc mới toanh - dẫn đầu là Lo Âu (Anxiety) - đổ bộ vào trung tâm điều khiển tâm trí.',
 96, 'P', 'Lồng tiếng & Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 7 DAY), DATE_ADD(CURDATE(), INTERVAL 35 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/vpnVM9B6NMmQpWeZvzLvDESb2QY.jpg',
 'https://image.tmdb.org/t/p/w1280/xg27NrXi7VXCGUr7MG75UqLl6Vg.jpg',
 'https://www.youtube.com/watch?v=LEjhY15eCx0'),

-- 5. Godzilla x Kong: Đế Chế Mới
(5, 'Godzilla x Kong: Đế Chế Mới',
 'Hai quái thú cổ đại hùng mạnh nhất hành tinh buộc phải bắt tay nhau đối đầu với một mối đe dọa khổng lồ ẩn sâu dưới lòng Trái Đất đe dọa sự tồn vong nhân loại.',
 115, 'T13', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 12 DAY), DATE_ADD(CURDATE(), INTERVAL 25 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/bQ2ywkchIiaKLSEaMrcT6e29f91.jpg',
 'https://image.tmdb.org/t/p/w1280/veIyxxi5Gs8gvztLEW1Ysb8rrzs.jpg',
 'https://www.youtube.com/watch?v=lV1OOlGwExg'),

-- 6. Avatar: Dòng Chảy Của Nước
(6, 'Avatar: Dòng Chảy Của Nước',
 'Jake Sully và Neytiri rời bỏ quê hương rừng xanh để nương náu tại các quần đảo của tộc người biển Metkayina khi quân đoàn loài người tái xâm chiếm Pandora.',
 192, 'T13', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 25 DAY), DATE_ADD(CURDATE(), INTERVAL 20 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/t6HIqrRAclMCA60NsSmeqe9RmNV.jpg',
 'https://image.tmdb.org/t/p/w1280/s16H6tpK2utvwDtzZ8Qy4qm5Emw.jpg',
 'https://www.youtube.com/watch?v=d9MyW72ELq0'),

-- 7. Người Nhện: Du Hành Vũ Trụ Nhện
(7, 'Người Nhện: Du Hành Vũ Trụ Nhện',
 'Miles Morales bị cuốn vào mạng lưới đa vũ trụ nơi cậu phải đối đầu với toàn bộ Quân đoàn Nhện để bảo vệ định mệnh và người cậu yêu thương nhất.',
 140, 'T13', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 15 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg',
 'https://image.tmdb.org/t/p/w1280/4HodYYKEIsGOdinkGi2Ucz6X9i0.jpg',
 'https://www.youtube.com/watch?v=cqGjhVJWtEg'),

-- 8. Quật Mộ Trùng Ma (Exhuma)
(8, 'Quật Mộ Trùng Ma: Exhuma',
 'Một thầy phong thủy, một chuyên gia tang lễ và hai pháp sư nhận hợp đồng khai quật ngôi mộ cổ bí ẩn của gia tộc giàu có, vô tình giải thoát một lời nguyền tà ác.',
 134, 'T18', 'Tiếng Hàn - Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 18 DAY), DATE_ADD(CURDATE(), INTERVAL 20 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/6dasJ58GGFcC62H9KuukAryltUp.jpg',
 'https://image.tmdb.org/t/p/w1280/kYWk8TW5TkpCWkB0mkUm72N0lb8.jpg',
 'https://www.youtube.com/watch?v=ZfJ3H5kR9S8'),

-- 9. Kung Fu Panda 4
(9, 'Kung Fu Panda 4',
 'Gấu Po nhận trọng trách trở thành Thủ lĩnh Tinh thần Thung lũng Bình Yên và phải huấn luyện một hậu duệ mới trong lúc đối mặt với nữ phù thủy Tắc Kè Bông xảo quyệt.',
 94, 'P', 'Lồng tiếng & Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 8 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/kDp1vUBnMpe8ak4rjgl3cLELqjU.jpg',
 'https://image.tmdb.org/t/p/w1280/kDp1vUBnMpe8ak4rjgl3cLELqjU.jpg',
 'https://www.youtube.com/watch?v=_inKs4eeHiI'),

-- 10. Interstellar: Hố Đen Tử Thần
(10, 'Interstellar: Hố Đen Tử Thần',
 'Khi Trái Đất đứng trước bờ vực suy tàn, một nhóm nhà thám hiểm dấn thân qua hố giun không gian kỳ bí để tìm kiếm hành tinh mới cho nhân loại.',
 169, 'T13', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 5 DAY), DATE_ADD(CURDATE(), INTERVAL 60 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg',
 'https://image.tmdb.org/t/p/w1280/rAiYTfKGqDCRIIqo664sY9XZIvQ.jpg',
 'https://www.youtube.com/watch?v=zSWdZVtXT7E'),

-- 11. The Batman
(11, 'The Batman: Vạch Trần Tội Ác',
 'Năm thứ hai hiệp sĩ bóng đêm bảo vệ thành phố Gotham, Batman đối đầu với kẻ giết người hàng loạt Riddler nhắm vào giới tinh hoa tham nhũng của thành phố.',
 176, 'T16', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 16 DAY), DATE_ADD(CURDATE(), INTERVAL 20 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/74xTEgt7R36Fpooo50r9T25onhq.jpg',
 'https://image.tmdb.org/t/p/w1280/74xTEgt7R36Fpooo50r9T25onhq.jpg',
 'https://www.youtube.com/watch?v=mqqft2x_Aa4'),

-- 12. Phi Công Siêu Đẳng: Maverick
(12, 'Phi Công Siêu Đẳng: Maverick',
 'Hơn 30 năm phụng sự, phi công thử nghiệm huyền thoại Pete Mitchell dẫn đầu khóa huấn luyện nguy hiểm tột bậc dành cho nhóm phi công trẻ tinh nhuệ Top Gun.',
 130, 'T13', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 9 DAY), DATE_ADD(CURDATE(), INTERVAL 28 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/62HCnUTziyWcpDaBO2i1DX17ljH.jpg',
 'https://image.tmdb.org/t/p/w1280/odJ4hx6g6vBt4lBWKFD1tI8WS4x.jpg',
 'https://www.youtube.com/watch?v=giXco2nxYKg'),

-- 13. Sát Thủ John Wick 4
(13, 'Sát Thủ John Wick: Phần 4',
 'John Wick phát hiện con đường đánh bại Hội Tối Thượng, nhưng trước khi tự do, anh phải đối mặt với một kẻ thù mới có liên minh hùng mạnh khắp toàn cầu.',
 169, 'T18', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 22 DAY), DATE_ADD(CURDATE(), INTERVAL 15 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg',
 'https://image.tmdb.org/t/p/w1280/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg',
 'https://www.youtube.com/watch?v=qEVUtrk8_B4'),

-- 14. Inception: Kẻ Đánh Cắp Giấc Mơ
(14, 'Inception: Kẻ Đánh Cắp Giấc Mơ',
 'Dom Cobb - một bậc thầy trộm cắp thông tin bằng cách xâm nhập vào tiềm thức khi con người đang ngủ - nhận phi vụ cấy ghép ý tưởng dường như bất khả thi.',
 148, 'T16', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 4 DAY), DATE_ADD(CURDATE(), INTERVAL 50 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/oYuLEt3zVCKq57qu2F8dT7NIa6f.jpg',
 'https://image.tmdb.org/t/p/w1280/s3TBrRGB1iav7gFOCNx3H31MoES.jpg',
 'https://www.youtube.com/watch?v=YoHD9XEInc0'),

-- 15. Thám Tử Lừng Danh Conan: Ngôi Sao 5 Cánh 1 Triệu Đô
(15, 'Thám Tử Lừng Danh Conan: Ngôi Sao 5 Cánh 1 Triệu Đô',
 'Một bức thư bí ẩn từ Kaito Kid gửi tới dinh thự tài phiệt ở Hakodate nhắm vào thanh bảo kiếm thời Mạc phủ, dẫn tới cuộc so tài kịch tính cùng Hattori Heiji và Conan.',
 111, 'P', 'Lồng tiếng & Phụ đề Tiếng Việt',
 DATE_SUB(CURDATE(), INTERVAL 6 DAY), DATE_ADD(CURDATE(), INTERVAL 35 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/4TMkOnI6rCYxqD059aeeQSBv5mc.jpg',
 'https://image.tmdb.org/t/p/w1280/4TMkOnI6rCYxqD059aeeQSBv5mc.jpg',
 'https://www.youtube.com/watch?v=cZpW3eZ0m3k'),

-- 16. Joker: Điên Có Đôi (COMING_SOON)
(16, 'Joker: Điên Có Đôi',
 'Arthur Fleck bị giam giữ tại Arkham chờ xét xử, nơi anh tìm thấy tình yêu đích thực và bản hòa ca điên loạn cùng Harley Quinn.',
 138, 'T18', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_ADD(CURDATE(), INTERVAL 10 DAY), DATE_ADD(CURDATE(), INTERVAL 60 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/if8QiqCI7WAGImKcJCfzp6VTyKA.jpg',
 'https://image.tmdb.org/t/p/w1280/if8QiqCI7WAGImKcJCfzp6VTyKA.jpg',
 'https://www.youtube.com/watch?v=_OKAwz2NiOI'),

-- 17. Võ Sĩ Giác Đấu II (Gladiator II) (COMING_SOON)
(17, 'Võ Sĩ Giác Đấu II',
 'Nhiều năm sau cái chết của Maximus, Lucius bị bắt làm nô lệ và buộc phải bước vào đấu trường La Mã để đấu tranh cho tự do và danh dự của Đế chế.',
 148, 'T18', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_ADD(CURDATE(), INTERVAL 18 DAY), DATE_ADD(CURDATE(), INTERVAL 70 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/2cxhvwyEwRlysAmRH4iodkvo0z5.jpg',
 'https://image.tmdb.org/t/p/w1280/2cxhvwyEwRlysAmRH4iodkvo0z5.jpg',
 'https://www.youtube.com/watch?v=4rgYUipGJNo'),

-- 18. Hành Trình Của Moana 2 (COMING_SOON)
(18, 'Hành Trình Của Moana 2',
 'Sau khi nhận được lời hiệu triệu bất ngờ từ tổ tiên, Moana cùng Maui lên đường thực hiện chuyến hải trình vượt qua những vùng biển xa xôi nguy hiểm của châu Đại Dương.',
 100, 'P', 'Lồng tiếng & Phụ đề Tiếng Việt',
 DATE_ADD(CURDATE(), INTERVAL 25 DAY), DATE_ADD(CURDATE(), INTERVAL 75 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/aLVkiINlIeCkcZIzb7XHzPYgO6L.jpg',
 'https://image.tmdb.org/t/p/w1280/aLVkiINlIeCkcZIzb7XHzPYgO6L.jpg',
 'https://www.youtube.com/watch?v=hDZ7y8RP5HE'),

-- 19. Wicked: Phù Thủy Xứ Oz (COMING_SOON)
(19, 'Wicked: Câu Chuyện Phù Thủy Xứ Oz',
 'Câu chuyện chưa từng kể về tình bạn và định mệnh trái ngược giữa Elphaba - Phù thủy Ác phương Tây và Glinda - Phù thủy Tốt phương Bắc tại vùng đất Oz.',
 160, 'P', 'Tiếng Anh - Phụ đề Tiếng Việt',
 DATE_ADD(CURDATE(), INTERVAL 32 DAY), DATE_ADD(CURDATE(), INTERVAL 80 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/xDGbZ0JJ3mYaGKy4Nzd9Kph6M9L.jpg',
 'https://image.tmdb.org/t/p/w1280/xDGbZ0JJ3mYaGKy4Nzd9Kph6M9L.jpg',
 'https://www.youtube.com/watch?v=6COmYeLsz4c'),

-- 20. Mufasa: Vua Sư Tử (COMING_SOON)
(20, 'Mufasa: Vua Sư Tử',
 'Hành trình từ một chú sư tử mồ côi lạc lõng trở thành vị vua vĩ đại nhất của Vùng Đất Kiêu Hãnh, cùng mối quan hệ định mệnh với Taka (Scar).',
 118, 'P', 'Lồng tiếng & Phụ đề Tiếng Việt',
 DATE_ADD(CURDATE(), INTERVAL 40 DAY), DATE_ADD(CURDATE(), INTERVAL 90 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/iBqXjFkAozQ1z2sfAiWwBimbiJX.jpg',
 'https://image.tmdb.org/t/p/w1280/iBqXjFkAozQ1z2sfAiWwBimbiJX.jpg',
 'https://image.tmdb.org/t/p/original/b85bJCeL5w0b42d765d75v7b8f.jpg',
 'https://www.youtube.com/watch?v=o17MF9vnabg');

-- -----------------------------------------------------------------------------
-- 5. LIÊN KẾT PHIM - THỂ LOẠI (MOVIE_GENRES)
-- -----------------------------------------------------------------------------
INSERT INTO MOVIE_GENRES (movie_id, genre_id) VALUES
-- Dune 2: Hành động, Viễn tưởng, Phiêu lưu
(1, 1), (1, 2), (1, 6),
-- Deadpool & Wolverine: Hành động, Hài, Viễn tưởng
(2, 1), (2, 7), (2, 2),
-- Oppenheimer: Tâm lý, Giật gân
(3, 3), (3, 8),
-- Inside Out 2: Hoạt hình, Hài, Phiêu lưu
(4, 5), (4, 7), (4, 6),
-- Godzilla x Kong: Hành động, Viễn tưởng, Phiêu lưu
(5, 1), (5, 2), (5, 6),
-- Avatar 2: Hành động, Viễn tưởng, Phiêu lưu
(6, 1), (6, 2), (6, 6),
-- Spider-Verse: Hoạt hình, Hành động, Viễn tưởng
(7, 5), (7, 1), (7, 2),
-- Exhuma: Kinh dị, Bí ẩn, Giật gân
(8, 4), (8, 9), (8, 8),
-- Kung Fu Panda 4: Hoạt hình, Hành động, Hài
(9, 5), (9, 1), (9, 7),
-- Interstellar: Viễn tưởng, Tâm lý, Phiêu lưu
(10, 2), (10, 3), (10, 6),
-- The Batman: Hành động, Bí ẩn, Giật gân
(11, 1), (11, 9), (11, 8),
-- Top Gun: Maverick: Hành động, Tâm lý
(12, 1), (12, 3),
-- John Wick 4: Hành động, Giật gân
(13, 1), (13, 8),
-- Inception: Hành động, Viễn tưởng, Giật gân
(14, 1), (14, 2), (14, 8),
-- Conan: Hoạt hình, Bí ẩn, Phiêu lưu
(15, 5), (15, 9), (15, 6),
-- Joker 2: Tâm lý, Giật gân, Nhạc kịch
(16, 3), (16, 8), (16, 10),
-- Gladiator 2: Hành động, Tâm lý, Phiêu lưu
(17, 1), (17, 3), (17, 6),
-- Moana 2: Hoạt hình, Phiêu lưu, Nhạc kịch
(18, 5), (18, 6), (18, 10),
-- Wicked: Nhạc kịch, Phiêu lưu, Tâm lý
(19, 10), (19, 6), (19, 3),
-- Mufasa: Hoạt hình, Phiêu lưu, Tâm lý
(20, 5), (20, 6), (20, 3);

-- -----------------------------------------------------------------------------
-- 6. SUẤT CHIẾU (SHOWTIMES)
-- Tạo suất chiếu cho 15 phim đang chiếu trong 7 ngày tới tại các rạp
-- -----------------------------------------------------------------------------
INSERT INTO SHOWTIMES (movie_id, room_id, start_at, end_at, base_price, status)
SELECT
    m.movie_id,
    r.room_id,
    TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d.day_offset DAY), t.slot_time) AS start_at,
    TIMESTAMPADD(MINUTE, m.duration_minutes + 15, TIMESTAMP(DATE_ADD(CURDATE(), INTERVAL d.day_offset DAY), t.slot_time)) AS end_at,
    CASE 
        WHEN r.room_type = 'IMAX Laser' THEN 140000.00
        WHEN r.room_type = '4DX Motion' THEN 130000.00
        WHEN r.room_type = 'Dolby Atmos' THEN 105000.00
        ELSE 85000.00
    END AS base_price,
    'ACTIVE' AS status
FROM MOVIES m
CROSS JOIN (
    SELECT 0 AS day_offset UNION ALL
    SELECT 1 UNION ALL
    SELECT 2 UNION ALL
    SELECT 3 UNION ALL
    SELECT 4 UNION ALL
    SELECT 5 UNION ALL
    SELECT 6
) d
CROSS JOIN (
    SELECT '09:30:00' AS slot_time UNION ALL
    SELECT '13:00:00' UNION ALL
    SELECT '16:15:00' UNION ALL
    SELECT '19:30:00' UNION ALL
    SELECT '22:15:00'
) t
JOIN ROOMS r ON r.room_id = (
    -- Phân bổ phòng chiếu xoay vòng đều đặn
    1 + ((m.movie_id * 3 + d.day_offset * 2 + TIME_TO_SEC(t.slot_time) / 3600) MOD 14)
)
WHERE m.status = 'NOW_SHOWING'
  AND (m.movie_id + d.day_offset) % 2 = 0; -- Chọn lọc để lịch chiếu đa dạng, tự nhiên

