-- =============================================================================
-- BOX CINEMA - COMPLETE ENGLISH SEED DATA (30 BLOCKBUSTER MOVIES)
-- Full dataset: 12 Genres, 5 Cinemas, 14 Screening Rooms, 30 Movies 
-- (18 Now Showing + 12 Coming Soon), Movie-Genre Relations, and 120+ Showtimes.
-- =============================================================================

USE box_cinema;

SET FOREIGN_KEY_CHECKS = 0;

-- 1. Clean all existing movie-related tables
TRUNCATE TABLE showtime_seats;
TRUNCATE TABLE showtimes;
TRUNCATE TABLE movie_genres;
TRUNCATE TABLE movies;
TRUNCATE TABLE rooms;
TRUNCATE TABLE cinemas;
TRUNCATE TABLE genres;

SET FOREIGN_KEY_CHECKS = 1;

-- -----------------------------------------------------------------------------
-- 2. GENRES (12 ENGLISH GENRES)
-- -----------------------------------------------------------------------------
INSERT INTO GENRES (genre_id, genre_name, description) VALUES
(1, 'Action', 'High-energy stunts, martial arts, adrenaline chases, and intense combat.'),
(2, 'Sci-Fi', 'Futuristic science, space travel, parallel dimensions, and groundbreaking concepts.'),
(3, 'Drama', 'Emotional narratives, deep character journeys, and powerful life stories.'),
(4, 'Horror', 'Supernatural mysteries, bone-chilling atmospheres, and psychological dread.'),
(5, 'Animation', 'Artistic animated storytelling and heart-warming experiences for all ages.'),
(6, 'Adventure', 'Epic quests, uncharted landscapes, and daring heroic journeys.'),
(7, 'Comedy', 'Lighthearted humor, clever wit, and hilarious everyday encounters.'),
(8, 'Thriller', 'Nail-biting suspense, rapid plot twists, and edge-of-your-seat excitement.'),
(9, 'Mystery', 'Perplexing crimes, master detectives, and intricate whodunits.'),
(10, 'Fantasy', 'Mythical realms, magical wonders, and legendary creatures.'),
(11, 'Crime', 'Underworld syndicates, heist masterminds, and moral dilemmas.'),
(12, 'Romance', 'Heartfelt romance, heartfelt connections, and modern relationship journeys.');

-- -----------------------------------------------------------------------------
-- 3. CINEMAS (5 MODERN THEATERS)
-- -----------------------------------------------------------------------------
INSERT INTO CINEMAS (cinema_id, cinema_name, address, city, phone, email, status) VALUES
(1, 'BOX Cinema Landmark 81', 'B1 Floor, Vincom Landmark 81, 720A Dien Bien Phu, Binh Thanh', 'Ho Chi Minh City', '028 3822 8181', 'landmark81@boxcinema.vn', 'ACTIVE'),
(2, 'BOX Cinema Vincom Center D1', '5th Floor, 72 Le Thanh Ton, Ben Nghe, District 1', 'Ho Chi Minh City', '028 3939 6868', 'vincom.d1@boxcinema.vn', 'ACTIVE'),
(3, 'BOX Cinema Lotte Mall West Lake', '4th Floor, Lotte Mall West Lake, 272 Vo Chi Cong, Tay Ho', 'Hanoi', '024 3788 8899', 'westlake@boxcinema.vn', 'ACTIVE'),
(4, 'BOX Cinema Vincom Ba Trieu', '6th Floor, Vincom Center, 191 Ba Trieu, Hai Ba Trung', 'Hanoi', '024 3974 9999', 'batrieu@boxcinema.vn', 'ACTIVE'),
(5, 'BOX Cinema Vincom Plaza Da Nang', '4th Floor, Vincom Plaza, 910A Ngo Quyen, Son Tra', 'Da Nang', '0236 399 6688', 'danang@boxcinema.vn', 'ACTIVE');

-- -----------------------------------------------------------------------------
-- 4. SCREENING ROOMS (14 ROOMS ACROSS CINEMAS)
-- -----------------------------------------------------------------------------
INSERT INTO ROOMS (room_id, cinema_id, room_name, room_type, status) VALUES
-- Cinema 1: Landmark 81
(1, 1, 'Screen 01 - IMAX Laser', 'IMAX Laser', 'ACTIVE'),
(2, 1, 'Screen 02 - Dolby Atmos', 'Dolby Atmos', 'ACTIVE'),
(3, 1, 'Screen 03 - 4DX Motion', '4DX Motion', 'ACTIVE'),
(4, 1, 'Screen 04 - Standard Luxe', 'Standard', 'ACTIVE'),

-- Cinema 2: Vincom D1
(5, 2, 'Screen 01 - IMAX Laser', 'IMAX Laser', 'ACTIVE'),
(6, 2, 'Screen 02 - Dolby Atmos', 'Dolby Atmos', 'ACTIVE'),
(7, 2, 'Screen 03 - Standard Luxe', 'Standard', 'ACTIVE'),

-- Cinema 3: Lotte Mall West Lake
(8, 3, 'Screen 01 - IMAX Laser', 'IMAX Laser', 'ACTIVE'),
(9, 3, 'Screen 02 - Dolby Atmos', 'Dolby Atmos', 'ACTIVE'),
(10, 3, 'Screen 03 - 4DX Motion', '4DX Motion', 'ACTIVE'),

-- Cinema 4: Vincom Ba Trieu
(11, 4, 'Screen 01 - Dolby Atmos', 'Dolby Atmos', 'ACTIVE'),
(12, 4, 'Screen 02 - Standard Luxe', 'Standard', 'ACTIVE'),

-- Cinema 5: Da Nang
(13, 5, 'Screen 01 - IMAX Laser', 'IMAX Laser', 'ACTIVE'),
(14, 5, 'Screen 02 - Dolby Atmos', 'Dolby Atmos', 'ACTIVE');

-- -----------------------------------------------------------------------------
-- 5. MOVIES (30 BLOCKBUSTERS IN ENGLISH)
-- 18 NOW SHOWING + 12 COMING SOON
-- -----------------------------------------------------------------------------
INSERT INTO MOVIES (
    movie_id, title, synopsis, duration_minutes, age_rating, language,
    release_date, end_date, status, poster_url, banner_url, trailer_url
) VALUES
-- 1. Dune: Part Two
(1, 'Dune: Part Two',
 'Paul Atreides unites with Chani and the Fremen while seeking revenge against the conspirators who destroyed his family, facing a fateful choice between love and the destiny of the universe.',
 166, 'T16', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 20 DAY), DATE_ADD(CURDATE(), INTERVAL 40 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/1pdfLvkbY9ohJlCjQH2CZjjYVvJ.jpg',
 'https://image.tmdb.org/t/p/w1280/uhUO7vQQKvCTfQWubOt5MAKokbL.jpg',
 'https://www.youtube.com/watch?v=Way9Dexny3w'),

-- 2. Deadpool & Wolverine
(2, 'Deadpool & Wolverine',
 'Wade Wilson teams up with a reluctant Wolverine on a high-stakes mission across the multiverse filled with explosive action, outrageous humor, and redemption.',
 128, 'T18', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 14 DAY), DATE_ADD(CURDATE(), INTERVAL 35 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/8cdWjvZQUExUUTzyp4t6EDMubfO.jpg',
 'https://image.tmdb.org/t/p/w1280/dvBCdCohwWbsP5qAaglOXagDMtk.jpg',
 'https://www.youtube.com/watch?v=73_1biulkYk'),

-- 3. Oppenheimer
(3, 'Oppenheimer',
 'The story of American scientist J. Robert Oppenheimer and his role in the development of the atomic bomb, exploring the profound moral consequences of humanity''s ultimate weapon.',
 180, 'T18', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 25 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/8Gxv8gSFCU0XGDykEGv7zR1n2ua.jpg',
 'https://image.tmdb.org/t/p/w1280/fm6KqXpk3M2HVveHwCrBSSBaO0V.jpg',
 'https://www.youtube.com/watch?v=uYPbbksJxIg'),

-- 4. Inside Out 2
(4, 'Inside Out 2',
 'Joy, Sadness, Anger, Fear, and Disgust navigate new emotional terrain inside teenage Riley as Anxiety, Envy, Ennui, and Embarrassment take over headquarters.',
 96, 'P', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 12 DAY), DATE_ADD(CURDATE(), INTERVAL 45 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/vpnVM9B6NMmQpWeZvzLvDESb2QY.jpg',
 'https://image.tmdb.org/t/p/w1280/xg27NrXi7VXCGUr7MG75UqLl6Vg.jpg',
 'https://www.youtube.com/watch?v=LEjhY15eCx0'),

-- 5. Godzilla x Kong: The New Empire
(5, 'Godzilla x Kong: The New Empire',
 'An almighty titan confrontation erupts as Godzilla and Kong must unite against a colossal undiscovered threat hidden within our world that challenges their very existence.',
 115, 'T13', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 18 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/tMefBSflR6PGQLv7WvFPpKLZkyk.jpg',
 'https://image.tmdb.org/t/p/w1280/qrGtVFxaD8c7et0jUtaYhyTzzPg.jpg',
 'https://www.youtube.com/watch?v=lV1OOlGwExg'),

-- 6. Avatar: The Way of Water
(6, 'Avatar: The Way of Water',
 'Jake Sully and Neytiri protect their growing family on Pandora by journeying to the oceanic reefs of the Metkayina clan when an ancient enemy returns.',
 192, 'T13', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 25 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/t6HIqrRAclMCA60NsSmeqe9RmNV.jpg',
 'https://image.tmdb.org/t/p/w1280/ovM06PdF3M8wvKb06i4sjW3xoww.jpg',
 'https://www.youtube.com/watch?v=d9MyW72ELq0'),

-- 7. Spider-Man: Across the Spider-Verse
(7, 'Spider-Man: Across the Spider-Verse',
 'Miles Morales catapults across the Multiverse, encountering a society of Spider-Heroes charged with protecting its existence, but finds himself clashing with their uncompromising methods.',
 140, 'P', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 16 DAY), DATE_ADD(CURDATE(), INTERVAL 35 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/8Vt6mWEReuy4Of61Lnj5Xj704m8.jpg',
 'https://image.tmdb.org/t/p/w1280/4HodYYKEIsGOdinkGi2Ucz6X9i0.jpg',
 'https://www.youtube.com/watch?v=cqGjhVJWtEg'),

-- 8. Exhuma
(8, 'Exhuma',
 'A top shaman and geomancer uncover dark occult secrets when exhuming an ominous ancestral grave in a remote village, unleashing a horrifying malevolent force.',
 134, 'T16', 'Korean - English Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 22 DAY), DATE_ADD(CURDATE(), INTERVAL 28 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/dqmMWNWfLnExDRpMtIMqI97GQFR.jpg',
 'https://image.tmdb.org/t/p/w1280/dqmMWNWfLnExDRpMtIMqI97GQFR.jpg',
 'https://www.youtube.com/watch?v=Kz6m2_b_4cE'),

-- 9. Kung Fu Panda 4
(9, 'Kung Fu Panda 4',
 'Po is tapped to become the Spiritual Leader of the Valley of Peace, needing to train a quick-witted corsac fox named Zhen to defeat the shape-shifting sorceress Chameleon.',
 94, 'P', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 15 DAY), DATE_ADD(CURDATE(), INTERVAL 32 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/kDp1vUBnMpe8ak4rjgl3cLELqjU.jpg',
 'https://image.tmdb.org/t/p/w1280/1XDDXPXGiI8id7MrUxK36ke7gkX.jpg',
 'https://www.youtube.com/watch?v=_inKs4eeHiI'),

-- 10. Interstellar
(10, 'Interstellar',
 'With humanity facing catastrophic global food shortages, a team of daring astronauts travels through a newly discovered wormhole near Saturn in search of a viable new home planet.',
 169, 'T13', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 10 DAY), DATE_ADD(CURDATE(), INTERVAL 50 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg',
 'https://image.tmdb.org/t/p/w1280/xJHokMbljvjADYdit5fK5VQsXEG.jpg',
 'https://www.youtube.com/watch?v=zSWdZVtXT7E'),

-- 11. The Batman
(11, 'The Batman',
 'In his second year fighting crime, Bruce Wayne investigates the corrupt underworld of Gotham City while tracking a sadistic serial killer known as the Riddler.',
 176, 'T16', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 28 DAY), DATE_ADD(CURDATE(), INTERVAL 20 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/74xTEgt7R36Fpooo50r9T25onhq.jpg',
 'https://image.tmdb.org/t/p/w1280/b0PlSFdDwbyK0cf5RxwDpaOJQvQ.jpg',
 'https://www.youtube.com/watch?v=mqqft2x_Aa4'),

-- 12. Top Gun: Maverick
(12, 'Top Gun: Maverick',
 'After thirty years of service, aviator Pete "Maverick" Mitchell leads TOPGUN''s elite graduates on a hazardous specialized mission demanding the ultimate sacrifice.',
 130, 'T13', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 35 DAY), DATE_ADD(CURDATE(), INTERVAL 25 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/62HCnUTziyWcpDaBO2i1DX17ljH.jpg',
 'https://image.tmdb.org/t/p/w1280/AaV1YIdWKnjAIAOe8UUKBFm327v.jpg',
 'https://www.youtube.com/watch?v=giXco2jaZ_4'),

-- 13. John Wick: Chapter 4
(13, 'John Wick: Chapter 4',
 'Legendary hitman John Wick uncovers a path to defeating the High Table, but must face a ruthless new adversary with powerful global alliances before earning his freedom.',
 169, 'T18', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 8 DAY), DATE_ADD(CURDATE(), INTERVAL 38 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/vZloFAK7NmvMGKE7VkF5UHaz0I.jpg',
 'https://image.tmdb.org/t/p/w1280/h8gHn0OzBoaefsYseUByqsmEDMY.jpg',
 'https://www.youtube.com/watch?v=qEVUtrk8_B4'),

-- 14. Inception
(14, 'Inception',
 'Dom Cobb is a skilled thief who steals valuable secrets from deep within the subconscious during dream states, offered a clean slate if he can accomplish inception.',
 148, 'T16', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 14 DAY), DATE_ADD(CURDATE(), INTERVAL 45 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/edv5CZvWj09upOsy2Y6IwDhK8bt.jpg',
 'https://image.tmdb.org/t/p/w1280/8ZTVqvKDQ8emSGUEMjsS4yHAwrp.jpg',
 'https://www.youtube.com/watch?v=YoHD9XEInc0'),

-- 15. Furiosa: A Mad Max Saga
(15, 'Furiosa: A Mad Max Saga',
 'As the world fell, young Furiosa is snatched from the Green Place of Many Mothers and must survive tyrannical warlords while plotting her long journey home.',
 148, 'T18', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 19 DAY), DATE_ADD(CURDATE(), INTERVAL 30 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/raph7qjAGTMXaIjVxt6ZDSXRzUr.jpg',
 'https://image.tmdb.org/t/p/w1280/wNAhuOZ3Zf84jCIlrcI6JhgmY5q.jpg',
 'https://www.youtube.com/watch?v=XJMuhwVlca4'),

-- 16. Alien: Romulus
(16, 'Alien: Romulus',
 'While scavenging the deep ends of a derelict space station, a group of young space colonizers comes face-to-face with the most terrifying and lethal lifeform in the universe.',
 119, 'T18', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 7 DAY), DATE_ADD(CURDATE(), INTERVAL 42 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/b33nnKl1GSFbao4l3fZDDqsMx0F.jpg',
 'https://image.tmdb.org/t/p/w1280/9SSEUrSqhljBMzRe4aBTh17rUaC.jpg',
 'https://www.youtube.com/watch?v=x0XDEhP4MQs'),

-- 17. Twisters
(17, 'Twisters',
 'Former storm chaser Kate Cooper is lured back to open plains to test a groundbreaking new tracking system, colliding with daredevil weather influencer Tyler Owens.',
 122, 'T13', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 9 DAY), DATE_ADD(CURDATE(), INTERVAL 35 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/pjnD08FlMAIXsfOLKQbvmO0f0MD.jpg',
 'https://image.tmdb.org/t/p/w1280/pjnD08FlMAIXsfOLKQbvmO0f0MD.jpg',
 'https://www.youtube.com/watch?v=Jb8mE6G_t10'),

-- 18. Kingdom of the Planet of the Apes
(18, 'Kingdom of the Planet of the Apes',
 'Many generations after Caesar''s reign, a young ape embarks on a perilous journey that leads him to question everything he has been taught about apes and humans.',
 145, 'T13', 'English - Vietnamese Subtitles',
 DATE_SUB(CURDATE(), INTERVAL 11 DAY), DATE_ADD(CURDATE(), INTERVAL 38 DAY), 'NOW_SHOWING',
 'https://image.tmdb.org/t/p/w780/gKkl37BQuKTanygYQG1pyYgLVgf.jpg',
 'https://image.tmdb.org/t/p/w1280/fqv8v6AycXKsivp1T5yKtLbGXce.jpg',
 'https://www.youtube.com/watch?v=XtFI7SNtVpY'),

-- -----------------------------------------------------------------------------
-- COMING SOON MOVIES (12 UPCOMING RELEASES)
-- -----------------------------------------------------------------------------
-- 19. Joker: Folie a Deux
(19, 'Joker: Folie a Deux',
 'Institutionalized at Arkham awaiting trial for his crimes as Joker, Arthur Fleck struggles with his dual identity and stumbles upon true love and musical mania with Lee Quinzel.',
 138, 'T18', 'English - Vietnamese Subtitles',
 DATE_ADD(CURDATE(), INTERVAL 7 DAY), DATE_ADD(CURDATE(), INTERVAL 60 DAY), 'COMING_SOON',
 '/assets/joker2.jpg',
 '/assets/joker2.jpg',
 'https://www.youtube.com/watch?v=_OKAwz2NiOI'),

-- 20. Gladiator II
(20, 'Gladiator II',
 'Years after witnessing the death of Maximus, Lucius must enter the Colosseum after his home is conquered by tyrannical emperors who rule Rome with an iron fist.',
 148, 'T18', 'English - Vietnamese Subtitles',
 DATE_ADD(CURDATE(), INTERVAL 14 DAY), DATE_ADD(CURDATE(), INTERVAL 70 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/b5UXjzW5cLZhprMnlAmsVAA3G4t.jpg',
 'https://image.tmdb.org/t/p/w1280/b5UXjzW5cLZhprMnlAmsVAA3G4t.jpg',
 'https://www.youtube.com/watch?v=4rgYUipGJNo'),

-- 21. Moana 2
(21, 'Moana 2',
 'After receiving an unexpected call from her wayfinding ancestors, Moana journeys to the far seas of Oceania and into dangerous, long-lost waters for an adventure unlike anything she has faced.',
 100, 'P', 'English - Vietnamese Subtitles',
 DATE_ADD(CURDATE(), INTERVAL 21 DAY), DATE_ADD(CURDATE(), INTERVAL 75 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/vYqt6kb4lcF8wwqsMMaULkP9OEn.jpg',
 'https://image.tmdb.org/t/p/w1280/vYqt6kb4lcF8wwqsMMaULkP9OEn.jpg',
 'https://www.youtube.com/watch?v=hDZ7y8RP5HE'),

-- 22. Wicked
(22, 'Wicked',
 'The untold story of the witches of Oz: Elphaba, a misunderstood young woman born with emerald-green skin, and Glinda, a privileged and ambitious young woman.',
 160, 'P', 'English - Vietnamese Subtitles',
 DATE_ADD(CURDATE(), INTERVAL 28 DAY), DATE_ADD(CURDATE(), INTERVAL 80 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/xDGbZ0JJ3mYaGKy4Nzd9Kph6M9L.jpg',
 'https://image.tmdb.org/t/p/w1280/uKb22E0nlzr914bA9KyA5CVCOlV.jpg',
 'https://www.youtube.com/watch?v=6COmYeLsz4c'),

-- 23. Mufasa: The Lion King
(23, 'Mufasa: The Lion King',
 'Told in flashbacks through Rafiki and Timon, an orphaned cub named Mufasa navigates the savanna and discovers his royal destiny alongside sympathetic prince Taka.',
 118, 'P', 'English - Vietnamese Subtitles',
 DATE_ADD(CURDATE(), INTERVAL 35 DAY), DATE_ADD(CURDATE(), INTERVAL 85 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/lurEK87kukWNaHd0zYnsi3yzJrs.jpg',
 'https://image.tmdb.org/t/p/w1280/9nhjGaFLKtddDPtPaX5EmKqsWdH.jpg',
 'https://www.youtube.com/watch?v=o17MF9vnabg'),

-- 24. Sonic the Hedgehog 3
(24, 'Sonic the Hedgehog 3',
 'Sonic, Knuckles, and Tails reunite against a powerful new adversary, Shadow the Hedgehog, an enigmatic enemy with powers unlike anything they have ever faced.',
 110, 'P', 'English - Vietnamese Subtitles',
 DATE_ADD(CURDATE(), INTERVAL 40 DAY), DATE_ADD(CURDATE(), INTERVAL 90 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/fwFWhYXj8wY6gFACtecJbg229FI.jpg',
 'https://image.tmdb.org/t/p/w1280/fwFWhYXj8wY6gFACtecJbg229FI.jpg',
 'https://www.youtube.com/watch?v=qSu6i2iFMO0'),

-- 25. Nosferatu
(25, 'Nosferatu',
 'Robert Eggers directs a gothic tale of obsession between a haunted young woman and the terrifying vampire infatuated with her, leaving an untold trail of nightmare in his wake.',
 132, 'T18', 'English - Vietnamese Subtitles',
 DATE_ADD(CURDATE(), INTERVAL 45 DAY), DATE_ADD(CURDATE(), INTERVAL 95 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/5qGIxdEO841C0tdY8vOdLoRVrr0.jpg',
 'https://image.tmdb.org/t/p/w1280/gprjiZWY43vxSKngMha1wfb5TGG.jpg',
 'https://www.youtube.com/watch?v=nulvWqYUM8k'),

-- 26. A Quiet Place: Day One
(26, 'A Quiet Place: Day One',
 'Experience the heart-stopping day the world went silent in New York City when mysterious sound-hunting extraterrestrial predators first arrived on Earth.',
 99, 'T16', 'English - Vietnamese Subtitles',
 DATE_ADD(CURDATE(), INTERVAL 18 DAY), DATE_ADD(CURDATE(), INTERVAL 65 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/hU42CRk14JuPEdqZG3AWmagiPAP.jpg',
 'https://image.tmdb.org/t/p/w1280/hU42CRk14JuPEdqZG3AWmagiPAP.jpg',
 'https://www.youtube.com/watch?v=YPY7J-flzE8'),

-- 27. Beetlejuice Beetlejuice
(27, 'Beetlejuice Beetlejuice',
 'After a family tragedy, three generations of the Deetz family return to Winter River. When the portal to the Afterlife is accidentally opened, Beetlejuice is back.',
 104, 'T13', 'English - Vietnamese Subtitles',
 DATE_ADD(CURDATE(), INTERVAL 25 DAY), DATE_ADD(CURDATE(), INTERVAL 70 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/kzCsCJBGPtsfj9CS5Z0Fkwmhwrp.jpg',
 'https://image.tmdb.org/t/p/w1280/A1dZ6faTjg0e6HYftBmEKujuXGQ.jpg',
 'https://www.youtube.com/watch?v=As-vKW4ZpbU'),

-- 28. Venom: The Last Dance
(28, 'Venom: The Last Dance',
 'Eddie Brock and Venom are on the run. Hunted by both of their worlds and with the net closing in, the duo are forced into a devastating decision.',
 110, 'T13', 'English - Vietnamese Subtitles',
 DATE_ADD(CURDATE(), INTERVAL 30 DAY), DATE_ADD(CURDATE(), INTERVAL 75 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/kV6ceSPLK1f8cIjvqOmvnmBEMbD.jpg',
 'https://image.tmdb.org/t/p/w1280/kV6ceSPLK1f8cIjvqOmvnmBEMbD.jpg',
 'https://www.youtube.com/watch?v=__2bjWbetsA'),

-- 29. Captain America: Brave New World
(29, 'Captain America: Brave New World',
 'Sam Wilson officially takes up the mantle of Captain America, finding himself in the middle of an international incident as he must discover the reason behind a nefarious global plot.',
 125, 'T13', 'English - Vietnamese Subtitles',
 DATE_ADD(CURDATE(), INTERVAL 50 DAY), DATE_ADD(CURDATE(), INTERVAL 100 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/1DTP1Ph4uzNO6ofRUm7eAimWoKD.jpg',
 'https://image.tmdb.org/t/p/w1280/8eifdha9GQeZAkexgtD45546XKx.jpg',
 'https://www.youtube.com/watch?v=1pHDWnXmK7Y'),

-- 30. Mission: Impossible - The Final Reckoning
(30, 'Mission: Impossible - The Final Reckoning',
 'Ethan Hunt and the IMF team embark on their definitive, most dangerous mission yet to track down a terrifying weapon that threatens all of humanity before it falls into the wrong hands.',
 165, 'T16', 'English - Vietnamese Subtitles',
 DATE_ADD(CURDATE(), INTERVAL 60 DAY), DATE_ADD(CURDATE(), INTERVAL 120 DAY), 'COMING_SOON',
 'https://image.tmdb.org/t/p/w780/NNxYkU70HPurnNCSiCjYAmacwm.jpg',
 'https://image.tmdb.org/t/p/w1280/628Dep6AxEtDxjZoGP78TsOxYbK.jpg',
 'https://www.youtube.com/watch?v=NOhDyHNwvbc');

-- -----------------------------------------------------------------------------
-- 6. MOVIE_GENRES (CROSS-RELATIONS)
-- -----------------------------------------------------------------------------
INSERT INTO MOVIE_GENRES (movie_id, genre_id) VALUES
-- 1. Dune 2: Action(1), Sci-Fi(2), Adventure(6)
(1, 1), (1, 2), (1, 6),
-- 2. Deadpool & Wolverine: Action(1), Comedy(7), Sci-Fi(2)
(2, 1), (2, 7), (2, 2),
-- 3. Oppenheimer: Drama(3), Thriller(8)
(3, 3), (3, 8),
-- 4. Inside Out 2: Animation(5), Comedy(7), Drama(3)
(4, 5), (4, 7), (4, 3),
-- 5. Godzilla x Kong: Action(1), Sci-Fi(2), Adventure(6)
(5, 1), (5, 2), (5, 6),
-- 6. Avatar 2: Action(1), Sci-Fi(2), Adventure(6)
(6, 1), (6, 2), (6, 6),
-- 7. Spider-Man: Animation(5), Action(1), Sci-Fi(2)
(7, 5), (7, 1), (7, 2),
-- 8. Exhuma: Horror(4), Mystery(9), Thriller(8)
(8, 4), (8, 9), (8, 8),
-- 9. Kung Fu Panda 4: Animation(5), Action(1), Comedy(7)
(9, 5), (9, 1), (9, 7),
-- 10. Interstellar: Sci-Fi(2), Drama(3), Adventure(6)
(10, 2), (10, 3), (10, 6),
-- 11. The Batman: Action(1), Mystery(9), Crime(11)
(11, 1), (11, 9), (11, 11),
-- 12. Top Gun Maverick: Action(1), Drama(3)
(12, 1), (12, 3),
-- 13. John Wick 4: Action(1), Crime(11), Thriller(8)
(13, 1), (13, 11), (13, 8),
-- 14. Inception: Action(1), Sci-Fi(2), Thriller(8)
(14, 1), (14, 2), (14, 8),
-- 15. Furiosa: Action(1), Sci-Fi(2), Adventure(6)
(15, 1), (15, 2), (15, 6),
-- 16. Alien: Romulus: Horror(4), Sci-Fi(2), Thriller(8)
(16, 4), (16, 2), (16, 8),
-- 17. Twisters: Action(1), Thriller(8), Adventure(6)
(17, 1), (17, 8), (17, 6),
-- 18. Planet of the Apes: Action(1), Sci-Fi(2), Adventure(6)
(18, 1), (18, 2), (18, 6),
-- 19. Joker 2: Drama(3), Crime(11), Thriller(8)
(19, 3), (19, 11), (19, 8),
-- 20. Gladiator 2: Action(1), Drama(3), Adventure(6)
(20, 1), (20, 3), (20, 6),
-- 21. Moana 2: Animation(5), Adventure(6), Comedy(7)
(21, 5), (21, 6), (21, 7),
-- 22. Wicked: Fantasy(10), Drama(3), Romance(12)
(22, 10), (22, 3), (22, 12),
-- 23. Mufasa: Animation(5), Adventure(6), Drama(3)
(23, 5), (23, 6), (23, 3),
-- 24. Sonic 3: Animation(5), Action(1), Comedy(7)
(24, 5), (24, 1), (24, 7),
-- 25. Nosferatu: Horror(4), Fantasy(10), Mystery(9)
(25, 4), (25, 10), (25, 9),
-- 26. A Quiet Place Day One: Horror(4), Sci-Fi(2), Thriller(8)
(26, 4), (26, 2), (26, 8),
-- 27. Beetlejuice 2: Comedy(7), Fantasy(10), Horror(4)
(27, 7), (27, 10), (27, 4),
-- 28. Venom 3: Action(1), Sci-Fi(2), Thriller(8)
(28, 1), (28, 2), (28, 8),
-- 29. Captain America 4: Action(1), Sci-Fi(2), Thriller(8)
(29, 1), (29, 2), (29, 8),
-- 30. Mission: Impossible 8: Action(1), Thriller(8), Adventure(6)
(30, 1), (30, 8), (30, 6);

-- -----------------------------------------------------------------------------
-- 7. SHOWTIMES (DYNAMIC SCREENINGS FOR THE NEXT 7 DAYS)
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
    1 + ((m.movie_id * 3 + d.day_offset * 2 + TIME_TO_SEC(t.slot_time) / 3600) MOD 14)
)
WHERE m.status = 'NOW_SHOWING'
  AND (m.movie_id + d.day_offset) % 2 = 0;

