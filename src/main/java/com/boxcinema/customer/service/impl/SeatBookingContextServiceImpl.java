package com.boxcinema.customer.service.impl;

import com.boxcinema.common.constant.AppConstants;
import com.boxcinema.customer.dto.response.ScreeningContextResponse;
import com.boxcinema.common.entity.Showtime;
import com.boxcinema.common.repository.ShowtimeRepository;
import com.boxcinema.customer.service.SeatBookingContextService;
import com.boxcinema.customer.service.SeatRealtimePushService;
import com.boxcinema.common.util.CryptoUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class SeatBookingContextServiceImpl implements SeatBookingContextService {

    public static final int DEFAULT_HOLD_DURATION_SECONDS = (int) AppConstants.DEFAULT_HOLD_DURATION_SECONDS;
    public static final int MAX_SEATS_PER_BOOKING = AppConstants.MAX_SEATS_PER_BOOKING;

    private final ShowtimeRepository showtimes;
    private final JdbcTemplate jdbcTemplate;
    private final SeatRealtimePushService seatRealtimePushService;

    @Value("${app.booking.hold-duration-seconds:600}")
    private int defaultHoldDurationSeconds = DEFAULT_HOLD_DURATION_SECONDS;

    @Autowired
    public SeatBookingContextServiceImpl(
            ShowtimeRepository showtimes,
            @Autowired(required = false) JdbcTemplate jdbcTemplate,
            @Autowired(required = false) SeatRealtimePushService seatRealtimePushService) {
        this.showtimes = showtimes;
        this.jdbcTemplate = jdbcTemplate;
        this.seatRealtimePushService = seatRealtimePushService != null ? seatRealtimePushService : new SeatRealtimePushService();
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<?> getScreeningContext(Long showtimeId) {
        Showtime showtime = showtimes.findById(showtimeId).orElse(null);
        if (showtime == null) {
            return error(HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND", "This screening could not be found. Please choose another showtime.");
        }
        if (!"ACTIVE".equalsIgnoreCase(showtime.getStatus())
                || (showtime.getRoom() != null && !"ACTIVE".equalsIgnoreCase(showtime.getRoom().getStatus()))
                || (showtime.getRoom() != null && showtime.getRoom().getCinema() != null && !"ACTIVE".equalsIgnoreCase(showtime.getRoom().getCinema().getStatus()))
                || (showtime.getStartAt() != null && !showtime.getStartAt().isAfter(LocalDateTime.now()))) {
            return error(HttpStatus.CONFLICT, "SHOWTIME_UNAVAILABLE", "This screening has started or is no longer available. Please choose another showtime.");
        }
        var movie = showtime.getMovie();
        var room = showtime.getRoom();
        var cinema = room != null ? room.getCinema() : null;
        if (room == null || cinema == null) {
            return error(HttpStatus.NOT_FOUND, "SHOWTIME_NOT_AVAILABLE", "This screening is no longer available because the cinema or room has suspended operations.");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new ScreeningContextResponse(
                        showtime.getShowtimeId().toString(),
                        movie.getMovieId().toString(),
                        movie.getTitle(),
                        movie.getAgeRating(),
                        movie.getPosterUrl(),
                        room.getRoomType() + " / " + (movie.getLanguage() != null ? movie.getLanguage() : "Subtitles"),
                        cinema.getCinemaId().toString(),
                        cinema.getCinemaName(),
                        room.getRoomName(),
                        showtime.getStartAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy / HH:mm", Locale.ENGLISH)),
                        showtime.getBasePrice()
                ));
    }

    @Override
    @Transactional
    public ResponseEntity<?> getBookingData(Long showtimeId, Long callerUserId, String callerSessionId) {
        if (jdbcTemplate == null) {
            return error(HttpStatus.NOT_FOUND, "DB_UNAVAILABLE", "Live seat inventory is not connected.");
        }

        cleanupExpiredHolds();

        Showtime showtime = showtimes.findById(showtimeId).orElse(null);
        if (showtime == null || !"ACTIVE".equalsIgnoreCase(showtime.getStatus())) {
            return error(HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND", "This screening could not be found. Please choose another showtime.");
        }
        if (showtime.getStartAt() != null && !showtime.getStartAt().isAfter(LocalDateTime.now())) {
            return error(HttpStatus.CONFLICT, "SHOWTIME_STARTED", "This screening has already started or ticket sales have closed. Please choose another showtime.");
        }

        var movie = showtime.getMovie();
        var room = showtime.getRoom();
        var cinema = room != null ? room.getCinema() : null;
        if (room == null || !"ACTIVE".equalsIgnoreCase(room.getStatus()) || cinema == null || !"ACTIVE".equalsIgnoreCase(cinema.getStatus())) {
            return error(HttpStatus.NOT_FOUND, "SHOWTIME_NOT_AVAILABLE", "This screening is no longer available because the cinema or room has suspended operations.");
        }
        Long roomId = room.getRoomId();

        List<Map<String, Object>> dbSeatTypes = new ArrayList<>();
        try {
            dbSeatTypes = jdbcTemplate.query(
                    "SELECT seat_type_id, type_name, price_surcharge, description, COALESCE(shape_type, 'standard') AS shape_type FROM seat_types ORDER BY seat_type_id ASC",
                    (rs, rowNum) -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("id", String.valueOf(rs.getLong("seat_type_id")));
                        map.put("typeName", rs.getString("type_name"));
                        map.put("surcharge", rs.getDouble("price_surcharge"));
                        map.put("description", rs.getString("description"));
                        map.put("shapeType", rs.getString("shape_type"));
                        return map;
                    }
            );
        } catch (Exception ignored) {
        }

        Map<String, Map<String, Object>> seatTypeMetaMap = new LinkedHashMap<>();
        List<Map<String, Object>> seatTypesList = new ArrayList<>();
        for (Map<String, Object> st : dbSeatTypes) {
            String typeId = (String) st.get("id");
            String rawName = (String) st.get("typeName");
            String shapeType = (String) st.get("shapeType");
            String upper = rawName != null ? rawName.trim().toUpperCase() : "STANDARD";
            String slug = slugify(rawName);

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("id", typeId);
            meta.put("name", rawName);
            meta.put("slug", slug);
            meta.put("shapeType", shapeType != null ? shapeType : "standard");
            meta.put("surcharge", st.get("surcharge"));

            seatTypeMetaMap.put(typeId, meta);
            seatTypeMetaMap.put(upper, meta);
            seatTypesList.add(meta);
        }

        List<Map<String, Object>> seatRows = queryRoomSeats(roomId);

        Set<String> bookedSeatLabels = new HashSet<>();
        Set<Long> bookedPhysicalSeatIds = new HashSet<>();
        try {
            jdbcTemplate.query(
                    "SELECT bs.seat_label_snapshot, ss.seat_id " +
                    "FROM booking_seats bs " +
                    "JOIN bookings b ON bs.booking_id = b.booking_id " +
                    "LEFT JOIN showtime_seats ss ON bs.showtime_seat_id = ss.showtime_seat_id " +
                    "WHERE b.showtime_id = ? AND b.booking_status IN ('CONFIRMED', 'PENDING', 'PAID')",
                    rs -> {
                        String label = rs.getString("seat_label_snapshot");
                        if (label != null && !label.isBlank()) {
                            bookedSeatLabels.add(label.trim().toUpperCase());
                        }
                        long sId = rs.getLong("seat_id");
                        if (!rs.wasNull() && sId > 0) {
                            bookedPhysicalSeatIds.add(sId);
                        }
                    },
                    showtimeId
            );
        } catch (Exception ignored) {
        }

        Map<Long, Map<String, Object>> showtimeSeatStatesBySeatId = new HashMap<>();
        Map<String, Map<String, Object>> showtimeSeatStatesByLabel = new HashMap<>();
        try {
            jdbcTemplate.query(
                    "SELECT ss.seat_id, CONCAT(s.seat_row, s.seat_number) AS raw_label, " +
                    "       ss.seat_status, ss.held_by_user_id, ss.hold_session_id, ss.hold_expires_at, " +
                    "       TIMESTAMPDIFF(SECOND, NOW(), ss.hold_expires_at) AS remaining_sec " +
                    "FROM showtime_seats ss " +
                    "JOIN seats s ON ss.seat_id = s.seat_id " +
                    "WHERE ss.showtime_id = ?",
                    rs -> {
                        long seatId = rs.getLong("seat_id");
                        String rawLabel = rs.getString("raw_label");
                        String status = rs.getString("seat_status");
                        Long heldByUserId = rs.getObject("held_by_user_id") != null ? rs.getLong("held_by_user_id") : null;
                        String holdSessionId = rs.getString("hold_session_id");
                        Timestamp holdExpiresTs = rs.getTimestamp("hold_expires_at");
                        long remainingSec = rs.getLong("remaining_sec");

                        Map<String, Object> state = new HashMap<>();
                        state.put("seatId", seatId);
                        state.put("status", status);
                        state.put("heldByUserId", heldByUserId);
                        state.put("holdSessionId", holdSessionId);
                        state.put("holdExpiresAt", holdExpiresTs != null ? holdExpiresTs.toLocalDateTime().toString() : null);
                        state.put("remainingSeconds", Math.max(0L, remainingSec));

                        showtimeSeatStatesBySeatId.put(seatId, state);
                        if (rawLabel != null) {
                            showtimeSeatStatesByLabel.put(rawLabel.toUpperCase(), state);
                        }
                    },
                    showtimeId
            );
        } catch (Exception ignored) {
        }

        int basePriceInt = showtime.getBasePrice() != null ? showtime.getBasePrice().intValue() : 9000;
        List<Map<String, Object>> seats = new ArrayList<>();

        if (seatRows.isEmpty()) {
            for (int r = 0; r < 7; r++) {
                String rowLetter = String.valueOf((char) ('A' + r));
                for (int c = 1; c <= 10; c++) {
                    Map<String, Object> seat = new LinkedHashMap<>();
                    String seatId = rowLetter + c;
                    String typeUpper = (r >= 2 && r <= 5) ? "VIP" : (r == 6 ? "SWEETBOX" : "STANDARD");
                    String shape = (r >= 2 && r <= 5) ? "vip" : (r == 6 ? "sweetbox" : "standard");
                    Map<String, Object> meta = seatTypeMetaMap.getOrDefault(typeUpper, Map.of(
                            "name", typeUpper, "slug", typeUpper.toLowerCase(), "shapeType", shape
                    ));
                    seat.put("id", seatId);
                    seat.put("row", rowLetter);
                    seat.put("number", c);
                    seat.put("type", meta.get("name"));
                    seat.put("typeSlug", meta.get("slug"));
                    seat.put("shapeType", meta.getOrDefault("shapeType", shape));
                    seat.put("price", basePriceInt + ((r >= 2 && r <= 5) ? 3000 : (r == 6 ? 5000 : 0)));
                    seat.put("status", bookedSeatLabels.contains(seatId) ? "BOOKED" : "AVAILABLE");
                    seat.put("aisleAfter", c == 5);
                    if (r == 6) {
                        int firstCol = (c % 2 == 1) ? c : (c - 1);
                        seat.put("pairId", "G" + firstCol + "-G" + (firstCol + 1));
                    }
                    seats.add(seat);
                }
            }
        } else {
            for (Map<String, Object> rowMap : seatRows) {
                Long physicalSeatId = (Long) rowMap.get("seatId");
                String rowLetter = (String) rowMap.get("row");
                int number = (int) rowMap.get("number");
                String dbStatus = (String) rowMap.get("seatStatus");
                String rawTypeName = (String) rowMap.get("typeName");
                String seatTypeId = (String) rowMap.get("seatTypeId");
                String upper = rawTypeName != null ? rawTypeName.trim().toUpperCase() : "STANDARD";
                double surcharge = (double) rowMap.get("surcharge");

                Map<String, Object> meta = seatTypeMetaMap.get(seatTypeId);
                if (meta == null) meta = seatTypeMetaMap.get(upper);
                if (meta == null) {
                    String shape = "standard";
                    if (upper.contains("SWEETBOX") || upper.contains("COUPLE") || upper.contains("\u0110\u00D4I") || upper.contains("DOI")) shape = "sweetbox";
                    else if (upper.contains("VIP")) shape = "vip";
                    else if (upper.contains("4DX") || upper.contains("4D")) shape = "4dx";
                    else if (upper.contains("IMAX")) shape = "imax";
                    else if (upper.contains("RECLINER") || upper.contains("\u004C\u01AF\u1EDCI") || upper.contains("LUOI")) shape = "recliner";
                    else if (upper.contains("WHEELCHAIR") || upper.contains("\u004B\u0048\u0055\u0059\u1EBE\u0054") || upper.contains("KHUYT")) shape = "wheelchair";

                    meta = Map.of(
                            "name", rawTypeName != null ? rawTypeName : "Standard",
                            "slug", slugify(rawTypeName != null ? rawTypeName : "standard"),
                            "shapeType", shape
                    );
                }

                String typeName = (String) meta.get("name");
                String typeSlug = (String) meta.get("slug");
                String rawLabel = (rowLetter + number).toUpperCase();

                Map<String, Object> seat = new LinkedHashMap<>();
                seat.put("id", rawLabel);
                seat.put("dbSeatId", physicalSeatId);
                seat.put("row", rowLetter);
                seat.put("number", number);
                seat.put("type", typeName);
                seat.put("typeSlug", typeSlug);
                seat.put("price", basePriceInt + (int) Math.round(surcharge));
                seat.put("rawStatus", dbStatus);
                seat.put("shapeType", rowMap.get("shapeType") != null ? rowMap.get("shapeType") : meta.getOrDefault("shapeType", "standard"));
                seat.put("pairId", rowMap.get("pairId"));
                seat.put("aisleAfter", false);
                seats.add(seat);
            }
        }

        List<String> myHeldSeats = new ArrayList<>();
        String myHoldExpiresAt = null;
        long myHoldRemainingSeconds = 0L;

        Map<String, Integer> rowCounter = new LinkedHashMap<>();
        for (Map<String, Object> seat : seats) {
            String r = (String) seat.get("row");
            String rawStatus = (String) seat.remove("rawStatus");
            String fallbackStatus = (String) seat.get("status");
            String effectiveDbStatus = rawStatus != null ? rawStatus : fallbackStatus;
            int colNum = (int) seat.getOrDefault("number", 0);
            Long physicalSeatId = (Long) seat.get("dbSeatId");

            if ("UNAVAILABLE".equalsIgnoreCase(effectiveDbStatus) || "INACTIVE".equalsIgnoreCase(effectiveDbStatus)) {
                seat.put("activeNumber", null);
                seat.put("seatLabel", r + "×");
                seat.put("id", "UNAVAIL_" + r + "_" + colNum);
                seat.put("status", "UNAVAILABLE");
                seat.put("heldByMe", false);
            } else {
                int actNum = rowCounter.getOrDefault(r, 0) + 1;
                rowCounter.put(r, actNum);
                String activeLabel = r + actNum;
                String rawLabel = r + colNum;
                seat.put("activeNumber", actNum);
                seat.put("seatLabel", activeLabel);
                seat.put("id", activeLabel);

                Map<String, Object> ssState = physicalSeatId != null ? showtimeSeatStatesBySeatId.get(physicalSeatId) : null;
                if (ssState == null) {
                    ssState = showtimeSeatStatesByLabel.get(activeLabel.toUpperCase());
                }
                if (ssState == null) {
                    ssState = showtimeSeatStatesByLabel.get(rawLabel.toUpperCase());
                }

                boolean isBooked = (physicalSeatId != null && bookedPhysicalSeatIds.contains(physicalSeatId))
                        || bookedSeatLabels.contains(activeLabel.toUpperCase())
                        || bookedSeatLabels.contains(rawLabel.toUpperCase());

                if (ssState != null) {
                    String ssStatus = (String) ssState.get("status");
                    if ("BOOKED".equalsIgnoreCase(ssStatus) || "SOLD".equalsIgnoreCase(ssStatus)) {
                        isBooked = true;
                    }
                }

                if (isBooked) {
                    seat.put("status", "BOOKED");
                    seat.put("heldByMe", false);
                } else if (ssState != null && "HELD".equalsIgnoreCase((String) ssState.get("status"))
                        && ((Long) ssState.getOrDefault("remainingSeconds", 0L)) > 0) {
                    Long heldByUser = (Long) ssState.get("heldByUserId");
                    String heldBySession = (String) ssState.get("holdSessionId");
                    long remSec = (Long) ssState.get("remainingSeconds");
                    String expAt = (String) ssState.get("holdExpiresAt");

                    if (isHeldByCaller(heldByUser, heldBySession, callerUserId, callerSessionId)) {
                        seat.put("status", "AVAILABLE");
                        seat.put("heldByMe", true);
                        seat.put("holdExpiresAt", expAt);
                        seat.put("holdRemainingSeconds", remSec);
                        myHeldSeats.add(activeLabel);
                        if (remSec > myHoldRemainingSeconds) {
                            myHoldRemainingSeconds = remSec;
                            myHoldExpiresAt = expAt;
                        }
                    } else {
                        seat.put("status", "HELD");
                        seat.put("heldByMe", false);
                        seat.put("holdExpiresAt", expAt);
                        seat.put("holdRemainingSeconds", remSec);
                    }
                } else {
                    seat.put("status", "AVAILABLE");
                    seat.put("heldByMe", false);
                }
            }
        }

        List<Map<String, Object>> products = new ArrayList<>();
        try {
            List<Map<String, Object>> comboProducts = jdbcTemplate.query(
                    "SELECT id, name, description, price, image_url FROM food_combo WHERE status = 'ACTIVE' AND is_available = 1 ORDER BY id ASC",
                    (rs, rowNum) -> {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("id", "combo_" + rs.getLong("id"));
                        p.put("comboId", rs.getLong("id"));
                        p.put("name", rs.getString("name"));
                        p.put("description", rs.getString("description") != null ? rs.getString("description") : "");
                        p.put("price", (int) Math.round(rs.getDouble("price")));
                        p.put("imageUrl", rs.getString("image_url") != null && !rs.getString("image_url").isBlank() ? rs.getString("image_url") : "/assets/img/fnb/box-combos.jpg");
                        p.put("type", "COMBO");
                        return p;
                    }
            );
            products.addAll(comboProducts);

            List<Map<String, Object>> itemProducts = jdbcTemplate.query(
                    "SELECT fi.id, fi.name, fi.description, fi.price, fi.image_url, fc.name as cat_name " +
                    "FROM food_item fi LEFT JOIN food_category fc ON fi.category_id = fc.id " +
                    "WHERE fi.status = 'ACTIVE' AND fi.is_available = 1 ORDER BY fi.category_id ASC, fi.id ASC",
                    (rs, rowNum) -> {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("id", "item_" + rs.getLong("id"));
                        p.put("foodItemId", rs.getLong("id"));
                        p.put("name", rs.getString("name"));
                        p.put("description", rs.getString("description") != null ? rs.getString("description") : "");
                        p.put("price", (int) Math.round(rs.getDouble("price")));
                        String img = rs.getString("image_url");
                        if (img == null || img.isBlank()) {
                            String cat = rs.getString("cat_name");
                            img = (cat != null && (cat.toLowerCase().contains("beverage") || cat.toLowerCase().contains("drink") || cat.toLowerCase().contains("n\u01B0\u1EDBc") || cat.toLowerCase().contains("nuoc"))) ? "/assets/img/fnb/box-beverages.jpg" : "/assets/img/fnb/box-popcorn.jpg";
                        }
                        p.put("imageUrl", img);
                        p.put("type", "FOOD_ITEM");
                        return p;
                    }
            );
            products.addAll(itemProducts);
        } catch (Exception ignored) {
        }

        if (products.isEmpty()) {
            products = List.of(
                    Map.of("id", "solo", "name", "Solo combo", "description", "1 large popcorn + 1 soft drink", "price", 8900, "imageUrl", "/assets/img/fnb/box-popcorn.jpg"),
                    Map.of("id", "duo", "name", "Movie night for two", "description", "1 large popcorn + 2 soft drinks", "price", 12900, "imageUrl", "/assets/img/fnb/box-combos.jpg"),
                    Map.of("id", "drink", "name", "Refreshing soft drink", "description", "1 large soft drink", "price", 3500, "imageUrl", "/assets/img/fnb/box-beverages.jpg")
            );
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("showtimeId", String.valueOf(showtime.getShowtimeId()));
        response.put("movieId", String.valueOf(movie.getMovieId()));
        response.put("movieTitle", movie.getTitle());
        response.put("ageRating", movie.getAgeRating() != null ? movie.getAgeRating() : "P");
        response.put("posterUrl", movie.getPosterUrl() != null ? movie.getPosterUrl() : "/assets/img/crimson-horizon.webp");
        response.put("format", (room.getRoomType() != null ? room.getRoomType() : "2D") + " / " + (movie.getLanguage() != null ? movie.getLanguage() : "Subtitles"));
        response.put("roomType", room.getRoomType() != null ? room.getRoomType() : "2D Standard");
        response.put("cinemaId", String.valueOf(cinema.getCinemaId()));
        response.put("cinemaName", cinema.getCinemaName());
        response.put("roomName", room.getRoomName());
        response.put("screeningLabel", showtime.getStartAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy / HH:mm", Locale.ENGLISH)));
        response.put("basePrice", basePriceInt);
        response.put("seats", seats);
        response.put("seatTypes", seatTypesList);
        response.put("products", products);
        response.put("heldSeats", myHeldSeats);
        response.put("holdExpiresAt", myHoldExpiresAt);
        response.put("holdRemainingSeconds", myHoldRemainingSeconds);
        response.put("holdDurationSeconds", defaultHoldDurationSeconds > 0 ? defaultHoldDurationSeconds : DEFAULT_HOLD_DURATION_SECONDS);
        response.put("preview", false);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    @Override
    @Transactional
    public ResponseEntity<?> holdSeats(Long showtimeId, Map<String, Object> body, Long callerUserId, String callerSessionId) {
        if (jdbcTemplate == null) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "DB_UNAVAILABLE", "Database connection is required to hold seats.");
        }

        cleanupExpiredHolds();

        Showtime showtime = showtimes.findById(showtimeId).orElse(null);
        if (showtime == null || !"ACTIVE".equalsIgnoreCase(showtime.getStatus())) {
            return error(HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND", "Showtime does not exist or has been discontinued.");
        }
        if (showtime.getStartAt() != null && !showtime.getStartAt().isAfter(LocalDateTime.now())) {
            return error(HttpStatus.CONFLICT, "SHOWTIME_STARTED", "This screening has already started or booking time has elapsed.");
        }
        var room = showtime.getRoom();
        if (room == null || !"ACTIVE".equalsIgnoreCase(room.getStatus())) {
            return error(HttpStatus.CONFLICT, "ROOM_UNAVAILABLE", "The auditorium is currently unavailable.");
        }

        if (callerUserId == null) {
            return error(HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_AUTHENTICATED", "Please sign in to select and reserve seats.");
        }

        List<String> requestedSeatIds = new ArrayList<>();
        if (body != null && body.get("seatIds") instanceof List<?> rawList) {
            for (Object item : rawList) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    String clean = String.valueOf(item).trim().toUpperCase();
                    if (!requestedSeatIds.contains(clean)) {
                        requestedSeatIds.add(clean);
                    }
                }
            }
        }

        if (requestedSeatIds.size() > MAX_SEATS_PER_BOOKING) {
            return error(HttpStatus.BAD_REQUEST, "MAX_SEATS_EXCEEDED",
                    "You can only select up to " + MAX_SEATS_PER_BOOKING + " seats per booking.");
        }

        if (requestedSeatIds.isEmpty()) {
            releaseCallerHolds(showtimeId, callerUserId, callerSessionId);
            if (seatRealtimePushService != null) {
                seatRealtimePushService.broadcastSeatUpdate(showtimeId, "SEAT_RELEASED");
            }
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "showtimeId", String.valueOf(showtimeId),
                    "heldSeatIds", Collections.emptyList(),
                    "holdRemainingSeconds", 0,
                    "message", "Seat reservation released."
            ));
        }

        List<Map<String, Object>> roomSeats = queryRoomSeats(room.getRoomId());
        if (roomSeats.isEmpty()) {
            ensureDefaultRoomSeats(room.getRoomId());
            roomSeats = queryRoomSeats(room.getRoomId());
        }
        Map<String, Map<String, Object>> labelToSeatMap = new LinkedHashMap<>();
        Map<String, List<String>> pairGroupMap = new LinkedHashMap<>();
        Map<String, Integer> rowCounter = new LinkedHashMap<>();

        for (Map<String, Object> rowMap : roomSeats) {
            String r = (String) rowMap.get("row");
            int colNum = (int) rowMap.get("number");
            String seatStatus = (String) rowMap.get("seatStatus");
            if ("UNAVAILABLE".equalsIgnoreCase(seatStatus) || "INACTIVE".equalsIgnoreCase(seatStatus)) {
                continue;
            }
            int actNum = rowCounter.getOrDefault(r, 0) + 1;
            rowCounter.put(r, actNum);
            String activeLabel = (r + actNum).toUpperCase();
            String rawLabel = (r + colNum).toUpperCase();
            rowMap.put("activeLabel", activeLabel);
            labelToSeatMap.put(activeLabel, rowMap);
            labelToSeatMap.putIfAbsent(rawLabel, rowMap);

            String pairId = (String) rowMap.get("pairId");
            if (pairId != null && !pairId.isBlank()) {
                pairGroupMap.computeIfAbsent(pairId, k -> new ArrayList<>()).add(activeLabel);
            }
        }

        List<Long> targetPhysicalSeatIds = new ArrayList<>();
        List<String> normalizedSeatLabels = new ArrayList<>();
        for (String reqLabel : requestedSeatIds) {
            Map<String, Object> seatRow = labelToSeatMap.get(reqLabel);
            if (seatRow == null) {
                return error(HttpStatus.BAD_REQUEST, "INVALID_SEAT", "Seat " + reqLabel + " is invalid or unavailable.");
            }
            String pairId = (String) seatRow.get("pairId");
            if (pairId != null && pairGroupMap.containsKey(pairId)) {
                for (String partnerLabel : pairGroupMap.get(pairId)) {
                    if (!requestedSeatIds.contains(partnerLabel)) {
                        return error(HttpStatus.BAD_REQUEST, "SWEETBOX_PAIR_REQUIRED",
                                "Couple seats (" + String.join(" - ", pairGroupMap.get(pairId)) + ") must be booked together.");
                    }
                }
            }
            Long dbSeatId = (Long) seatRow.get("seatId");
            String activeLabel = (String) seatRow.get("activeLabel");
            if (!targetPhysicalSeatIds.contains(dbSeatId)) {
                targetPhysicalSeatIds.add(dbSeatId);
                normalizedSeatLabels.add(activeLabel);
            }
        }

        Set<String> bookedLabels = new HashSet<>();
        try {
            jdbcTemplate.query(
                    "SELECT bs.seat_label_snapshot " +
                    "FROM booking_seats bs " +
                    "JOIN bookings b ON bs.booking_id = b.booking_id " +
                    "WHERE b.showtime_id = ? AND b.booking_status IN ('CONFIRMED', 'PENDING', 'PAID')",
                    rs -> {
                        String lbl = rs.getString("seat_label_snapshot");
                        if (lbl != null) bookedLabels.add(lbl.trim().toUpperCase());
                    },
                    showtimeId
            );
        } catch (Exception ignored) {
        }

        List<String> conflictingSeats = new ArrayList<>();
        for (int i = 0; i < targetPhysicalSeatIds.size(); i++) {
            Long dbSeatId = targetPhysicalSeatIds.get(i);
            String label = normalizedSeatLabels.get(i);
            if (bookedLabels.contains(label)) {
                conflictingSeats.add(label);
                continue;
            }
            List<Map<String, Object>> existingRows = jdbcTemplate.query(
                    "SELECT seat_status, held_by_user_id, hold_session_id, " +
                    "       TIMESTAMPDIFF(SECOND, NOW(), hold_expires_at) AS rem_sec " +
                    "FROM showtime_seats WHERE showtime_id = ? AND seat_id = ?",
                    (rs, rowNum) -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("status", rs.getString("seat_status"));
                        m.put("userId", rs.getObject("held_by_user_id") != null ? rs.getLong("held_by_user_id") : null);
                        m.put("sessionId", rs.getString("hold_session_id"));
                        m.put("remSec", rs.getLong("rem_sec"));
                        return m;
                    },
                    showtimeId, dbSeatId
            );
            if (!existingRows.isEmpty()) {
                Map<String, Object> existing = existingRows.get(0);
                String st = (String) existing.get("status");
                long remSec = (Long) existing.get("remSec");
                Long heldUser = (Long) existing.get("userId");
                String heldSession = (String) existing.get("sessionId");
                if ("BOOKED".equalsIgnoreCase(st) || "SOLD".equalsIgnoreCase(st)) {
                    conflictingSeats.add(label);
                } else if ("HELD".equalsIgnoreCase(st) && remSec > 0
                        && !isHeldByCaller(heldUser, heldSession, callerUserId, callerSessionId)) {
                    conflictingSeats.add(label);
                }
            }
        }

        if (!conflictingSeats.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "errorCode", "SEAT_ALREADY_HELD_OR_BOOKED",
                    "conflictingSeats", conflictingSeats,
                    "message", "Seat(s) " + String.join(", ", conflictingSeats) + " were just held or booked by another customer. Please choose other seats."
            ));
        }

        boolean resetTimer = body != null && Boolean.TRUE.equals(body.get("resetTimer"));
        Integer requestedDuration = null;
        if (body != null && body.get("durationSeconds") instanceof Number num) {
            requestedDuration = Math.max(5, Math.min(1800, num.intValue()));
        }

        int holdSeconds = requestedDuration != null
                ? requestedDuration
                : (defaultHoldDurationSeconds > 0 ? defaultHoldDurationSeconds : DEFAULT_HOLD_DURATION_SECONDS);

        LocalDateTime expiresAt = null;
        if (!resetTimer && requestedDuration == null) {
            try {
                List<Timestamp> existingTimers = jdbcTemplate.query(
                        "SELECT hold_expires_at FROM showtime_seats " +
                        "WHERE showtime_id = ? AND seat_status = 'HELD' AND hold_expires_at > NOW() " +
                        "  AND ((? IS NOT NULL AND held_by_user_id = ?) OR (? IS NULL AND held_by_user_id IS NULL AND hold_session_id = ?)) " +
                        "ORDER BY hold_expires_at DESC LIMIT 1",
                        (rs, rowNum) -> rs.getTimestamp("hold_expires_at"),
                        showtimeId, callerUserId, callerUserId, callerUserId, callerSessionId
                );
                if (!existingTimers.isEmpty() && existingTimers.get(0) != null) {
                    LocalDateTime existingExp = existingTimers.get(0).toLocalDateTime();
                    if (Duration.between(LocalDateTime.now(), existingExp).getSeconds() > 15) {
                        expiresAt = existingExp;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (expiresAt == null) {
            expiresAt = LocalDateTime.now().plusSeconds(holdSeconds);
        }

        releaseCallerHoldsExcept(showtimeId, callerUserId, callerSessionId, targetPhysicalSeatIds);

        Timestamp expiresTimestamp = Timestamp.valueOf(expiresAt);
        for (Long dbSeatId : targetPhysicalSeatIds) {
            String tokenHash = CryptoUtils.sha256(showtimeId + ":" + dbSeatId + ":" + callerSessionId + ":" + callerUserId);
            jdbcTemplate.update(
                    "INSERT INTO showtime_seats (showtime_id, seat_id, seat_status, hold_token_hash, held_by_user_id, hold_session_id, hold_expires_at) " +
                    "VALUES (?, ?, 'HELD', ?, ?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE " +
                    "    seat_status = 'HELD', " +
                    "    hold_token_hash = VALUES(hold_token_hash), " +
                    "    held_by_user_id = VALUES(held_by_user_id), " +
                    "    hold_session_id = VALUES(hold_session_id), " +
                    "    hold_expires_at = VALUES(hold_expires_at)",
                    showtimeId, dbSeatId, tokenHash, callerUserId, callerSessionId, expiresTimestamp
            );
        }

        long remainingSeconds = Math.max(1L, Duration.between(LocalDateTime.now(), expiresAt).getSeconds());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("showtimeId", String.valueOf(showtimeId));
        result.put("heldSeatIds", normalizedSeatLabels);
        result.put("holdExpiresAt", expiresAt.toString());
        result.put("holdRemainingSeconds", remainingSeconds);
        result.put("holdDurationSeconds", holdSeconds);
        result.put("message", "Successfully held seat(s) " + String.join(", ", normalizedSeatLabels) + ".");

        if (seatRealtimePushService != null) {
            seatRealtimePushService.broadcastSeatUpdate(showtimeId, "SEAT_HELD");
        }

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(result);
    }

    @Override
    @Transactional
    public ResponseEntity<?> releaseSeats(Long showtimeId, Long callerUserId, String callerSessionId) {
        if (jdbcTemplate == null) {
            return ResponseEntity.ok(Map.of("success", true, "releasedCount", 0));
        }
        if (callerUserId == null) {
            return error(HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_AUTHENTICATED", "Please sign in to select and reserve seats.");
        }
        int released = releaseCallerHolds(showtimeId, callerUserId, callerSessionId);
        if (seatRealtimePushService != null) {
            seatRealtimePushService.broadcastSeatUpdate(showtimeId, "SEAT_RELEASED");
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "showtimeId", String.valueOf(showtimeId),
                "releasedCount", released,
                "message", "Held seats have been released."
        ));
    }

    private static String slugify(String text) {
        if (text == null) return "standard";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return normalized.isEmpty() ? "custom" : normalized;
    }

    private void cleanupExpiredHolds() {
        if (jdbcTemplate == null) return;
        try {
            jdbcTemplate.update(
                    "UPDATE showtime_seats " +
                    "SET seat_status = 'AVAILABLE', " +
                    "    hold_token_hash = NULL, " +
                    "    held_by_user_id = NULL, " +
                    "    hold_session_id = NULL, " +
                    "    hold_expires_at = NULL " +
                    "WHERE seat_status = 'HELD' " +
                    "  AND (hold_expires_at IS NULL OR hold_expires_at <= NOW())"
            );
        } catch (Exception ignored) {
        }
    }

    private boolean isHeldByCaller(Long seatHeldByUserId, String seatHoldSessionId, Long callerUserId, String callerSessionId) {
        if (seatHeldByUserId != null) {
            return callerUserId != null && callerUserId.equals(seatHeldByUserId);
        }
        if (callerUserId != null) {
            return false;
        }
        return callerSessionId != null && seatHoldSessionId != null && callerSessionId.equals(seatHoldSessionId);
    }

    private void ensureDefaultRoomSeats(Long roomId) {
        if (jdbcTemplate == null || roomId == null) return;
        try {
            for (char r = 'A'; r <= 'G'; r++) {
                long typeId = (r <= 'B') ? 1L : (r <= 'F') ? 2L : 3L;
                for (int col = 1; col <= 10; col++) {
                    String pairId = null;
                    if (r == 'G') {
                        int firstCol = (col % 2 == 1) ? col : (col - 1);
                        pairId = "G" + firstCol + "-G" + (firstCol + 1);
                    }
                    try {
                        jdbcTemplate.update(
                                "INSERT IGNORE INTO seats (room_id, seat_type_id, seat_row, seat_number, status, pair_id) VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                                roomId, typeId, String.valueOf(r), col, pairId
                        );
                    } catch (Exception e) {
                        jdbcTemplate.update(
                                "INSERT IGNORE INTO seats (room_id, seat_type_id, seat_row, seat_number, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                                roomId, typeId, String.valueOf(r), col
                        );
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private List<Map<String, Object>> queryRoomSeats(Long roomId) {
        if (jdbcTemplate == null) return Collections.emptyList();
        try {
            return jdbcTemplate.query(
                    "SELECT s.seat_id, s.seat_row, s.seat_number, s.status AS seat_status, s.seat_type_id, s.pair_id, " +
                    "       COALESCE(st.type_name, 'STANDARD') AS type_name, " +
                    "       COALESCE(st.price_surcharge, 0) AS price_surcharge, " +
                    "       COALESCE(st.shape_type, 'standard') AS shape_type " +
                    "FROM seats s " +
                    "LEFT JOIN seat_types st ON s.seat_type_id = st.seat_type_id " +
                    "WHERE s.room_id = ? " +
                    "ORDER BY s.seat_row ASC, s.seat_number ASC",
                    (rs, rowNum) -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        map.put("seatId", rs.getLong("seat_id"));
                        map.put("seatTypeId", String.valueOf(rs.getLong("seat_type_id")));
                        map.put("row", rs.getString("seat_row"));
                        map.put("number", rs.getInt("seat_number"));
                        map.put("seatStatus", rs.getString("seat_status"));
                        map.put("pairId", rs.getString("pair_id"));
                        map.put("typeName", rs.getString("type_name"));
                        map.put("surcharge", rs.getDouble("price_surcharge"));
                        map.put("shapeType", rs.getString("shape_type"));
                        return map;
                    },
                    roomId
            );
        } catch (Exception ex) {
            return jdbcTemplate.query(
                    "SELECT s.seat_id, s.seat_row, s.seat_number, s.status AS seat_status, s.seat_type_id, " +
                    "       COALESCE(st.type_name, 'STANDARD') AS type_name, " +
                    "       COALESCE(st.price_surcharge, 0) AS price_surcharge " +
                    "FROM seats s " +
                    "LEFT JOIN seat_types st ON s.seat_type_id = st.seat_type_id " +
                    "WHERE s.room_id = ? " +
                    "ORDER BY s.seat_row ASC, s.seat_number ASC",
                    (rs, rowNum) -> {
                        Map<String, Object> map = new LinkedHashMap<>();
                        String row = rs.getString("seat_row");
                        int num = rs.getInt("seat_number");
                        String tName = rs.getString("type_name");
                        String pairId = null;
                        if (tName != null && (tName.toUpperCase().contains("SWEETBOX") || tName.toUpperCase().contains("COUPLE"))) {
                            int firstCol = (num % 2 == 1) ? num : (num - 1);
                            pairId = row + firstCol + "-" + row + (firstCol + 1);
                        }
                        map.put("seatId", rs.getLong("seat_id"));
                        map.put("seatTypeId", String.valueOf(rs.getLong("seat_type_id")));
                        map.put("row", row);
                        map.put("number", num);
                        map.put("seatStatus", rs.getString("seat_status"));
                        map.put("pairId", pairId);
                        map.put("typeName", tName);
                        map.put("surcharge", rs.getDouble("price_surcharge"));
                        map.put("shapeType", null);
                        return map;
                    },
                    roomId
            );
        }
    }

    private int releaseCallerHolds(Long showtimeId, Long callerUserId, String callerSessionId) {
        if (jdbcTemplate == null) return 0;
        return jdbcTemplate.update(
                "UPDATE showtime_seats " +
                "SET seat_status = 'AVAILABLE', " +
                "    hold_token_hash = NULL, " +
                "    held_by_user_id = NULL, " +
                "    hold_session_id = NULL, " +
                "    hold_expires_at = NULL " +
                "WHERE showtime_id = ? AND seat_status = 'HELD' " +
                "  AND ((? IS NOT NULL AND held_by_user_id = ?) OR (? IS NULL AND held_by_user_id IS NULL AND hold_session_id = ?))",
                showtimeId, callerUserId, callerUserId, callerUserId, callerSessionId
        );
    }

    private void releaseCallerHoldsExcept(Long showtimeId, Long callerUserId, String callerSessionId, List<Long> keepSeatIds) {
        if (jdbcTemplate == null) return;
        if (keepSeatIds == null || keepSeatIds.isEmpty()) {
            releaseCallerHolds(showtimeId, callerUserId, callerSessionId);
            return;
        }
        String inPlaceholders = String.join(",", Collections.nCopies(keepSeatIds.size(), "?"));
        List<Object> params = new ArrayList<>();
        params.add(showtimeId);
        params.add(callerUserId);
        params.add(callerUserId);
        params.add(callerUserId);
        params.add(callerSessionId);
        params.addAll(keepSeatIds);
        jdbcTemplate.update(
                "UPDATE showtime_seats " +
                "SET seat_status = 'AVAILABLE', " +
                "    hold_token_hash = NULL, " +
                "    held_by_user_id = NULL, " +
                "    hold_session_id = NULL, " +
                "    hold_expires_at = NULL " +
                "WHERE showtime_id = ? AND seat_status = 'HELD' " +
                "  AND ((? IS NOT NULL AND held_by_user_id = ?) OR (? IS NULL AND held_by_user_id IS NULL AND hold_session_id = ?)) " +
                "  AND seat_id NOT IN (" + inPlaceholders + ")",
                params.toArray()
        );
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "errorCode", code, "message", message));
    }
}
