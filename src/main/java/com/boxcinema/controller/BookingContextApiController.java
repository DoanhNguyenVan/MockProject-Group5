package com.boxcinema.controller;

import com.boxcinema.entity.Showtime;
import com.boxcinema.repository.ShowtimeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Live screening metadata and seat availability API for the customer booking flow.
 * Directly synchronizes room layout and seat configurations from Admin Suite in MySQL.
 */
@RestController
public class BookingContextApiController {
    private final ShowtimeRepository showtimes;
    private final JdbcTemplate jdbcTemplate;

    private static final List<String> PALETTE_COLORS = List.of(
            "#10b981", // 1. Emerald Green
            "#06b6d4", // 2. Cyan Blue
            "#f43f5e", // 3. Rose Pink
            "#6366f1", // 4. Indigo
            "#f97316", // 5. Warm Orange
            "#14b8a6", // 6. Teal
            "#d946ef", // 7. Fuchsia
            "#84cc16", // 8. Lime Green
            "#0ea5e9", // 9. Sky Blue
            "#eab308", // 10. Golden Yellow
            "#ec4899", // 11. Deep Pink
            "#8b5cf6", // 12. Violet
            "#22c55e", // 13. Bright Green
            "#38bdf8", // 14. Light Cyan
            "#fb7185", // 15. Coral Red
            "#e11d48"  // 16. Crimson
    );

    public BookingContextApiController(ShowtimeRepository showtimes) {
        this(showtimes, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public BookingContextApiController(ShowtimeRepository showtimes, @org.springframework.beans.factory.annotation.Autowired(required = false) JdbcTemplate jdbcTemplate) {
        this.showtimes = showtimes;
        this.jdbcTemplate = jdbcTemplate;
    }

    private static String hexToRgba(String hex, double opacity) {
        if (hex == null || !hex.startsWith("#") || (hex.length() != 7 && hex.length() != 4)) {
            return "rgba(255, 255, 255, 0.1)";
        }
        try {
            int r, g, b;
            if (hex.length() == 7) {
                r = Integer.parseInt(hex.substring(1, 3), 16);
                g = Integer.parseInt(hex.substring(3, 5), 16);
                b = Integer.parseInt(hex.substring(5, 7), 16);
            } else {
                r = Integer.parseInt(hex.substring(1, 2) + hex.substring(1, 2), 16);
                g = Integer.parseInt(hex.substring(2, 3) + hex.substring(2, 3), 16);
                b = Integer.parseInt(hex.substring(3, 4) + hex.substring(3, 4), 16);
            }
            return String.format(Locale.ROOT, "rgba(%d, %d, %d, %.2f)", r, g, b, opacity);
        } catch (Exception e) {
            return "rgba(255, 255, 255, 0.1)";
        }
    }

    private static Map<String, String> getColorForType(String typeName, String colorCode, int customIndex) {
        if (colorCode != null && colorCode.trim().startsWith("#")) {
            String hex = colorCode.trim();
            return Map.of("color", hex, "border", hex, "bg", hexToRgba(hex, 0.16));
        }
        String upper = (typeName != null) ? typeName.trim().toUpperCase() : "STANDARD";
        if ("STANDARD".equals(upper) || upper.contains("THƯỜNG") || upper.contains("THUONG")) {
            return Map.of("color", "#93c5fd", "border", "#3b82f6", "bg", "#0f172a");
        } else if ("VIP".equals(upper)) {
            return Map.of("color", "#fcd34d", "border", "#f59e0b", "bg", "#1c1507");
        } else if ("SWEETBOX".equals(upper) || "COUPLE".equals(upper) || upper.contains("ĐÔI") || upper.contains("DOI")) {
            return Map.of("color", "#e9d5ff", "border", "#a855f7", "bg", "#1e112a");
        } else if (upper.contains("LƯỜI") || upper.contains("LUOI") || upper.contains("LOUNGE") || upper.contains("BED")) {
            return Map.of("color", "#6ee7b7", "border", "#10b981", "bg", "#062419");
        } else if (upper.contains("TÌNH YÊU") || upper.contains("TINH YEU") || upper.contains("TÌNH IÊU") || upper.contains("TINH IEU") || upper.contains("LOVE")) {
            return Map.of("color", "#f472b6", "border", "#ec4899", "bg", "#260a1c");
        }
        String color = PALETTE_COLORS.get(Math.abs(customIndex) % PALETTE_COLORS.size());
        return Map.of("color", color, "border", color, "bg", hexToRgba(color, 0.16));
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

    @GetMapping("/api/quick-booking/showtimes/{showtimeId}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> context(@PathVariable Long showtimeId) {
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
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(new ScreeningContext(
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

    /**
     * Primary live booking endpoint: loads exact seat matrix configured by Admin for this room,
     * including custom rows, columns, seat types (STANDARD, VIP, SWEETBOX, GHẾ LƯỜI...), and booked/unavailable status.
     */
    @GetMapping({"/api/booking/showtimes/{showtimeId}", "/api/quick-booking/showtimes/{showtimeId}/data"})
    @Transactional(readOnly = true)
    public ResponseEntity<?> getBookingData(@PathVariable Long showtimeId) {
        Showtime showtime = showtimes.findById(showtimeId).orElse(null);
        if (showtime == null || !"ACTIVE".equalsIgnoreCase(showtime.getStatus())) {
            return error(HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND", "This screening could not be found. Please choose another showtime.");
        }

        var movie = showtime.getMovie();
        var room = showtime.getRoom();
        var cinema = room != null ? room.getCinema() : null;
        if (room == null || !"ACTIVE".equalsIgnoreCase(room.getStatus()) || cinema == null || !"ACTIVE".equalsIgnoreCase(cinema.getStatus())) {
            return error(HttpStatus.NOT_FOUND, "SHOWTIME_NOT_AVAILABLE", "This screening is no longer available because the cinema or room has suspended operations.");
        }
        Long roomId = room.getRoomId();

        // 1. Fetch all configured seat types from MySQL to build metadata
        List<Map<String, Object>> dbSeatTypes = jdbcTemplate.query(
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

        // 2. Fetch exact seats configured for this room in MySQL
        List<Map<String, Object>> seatRows = jdbcTemplate.query(
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

        // 3. Query booked seats for this specific screening
        Set<String> bookedSeatLabels = new HashSet<>();
        try {
            List<String> booked = jdbcTemplate.query(
                    "SELECT bs.seat_label_snapshot " +
                    "FROM booking_seats bs " +
                    "JOIN bookings b ON bs.booking_id = b.booking_id " +
                    "WHERE b.showtime_id = ? AND b.booking_status IN ('CONFIRMED', 'PENDING', 'PAID')",
                    (rs, rowNum) -> rs.getString("seat_label_snapshot"),
                    showtimeId
            );
            bookedSeatLabels.addAll(booked);
        } catch (Exception ignored) {}

        try {
            List<String> showtimeBooked = jdbcTemplate.query(
                    "SELECT CONCAT(s.seat_row, s.seat_number) as label " +
                    "FROM showtime_seats ss " +
                    "JOIN seats s ON ss.seat_id = s.seat_id " +
                    "WHERE ss.showtime_id = ? AND ss.seat_status IN ('HELD', 'BOOKED', 'SOLD')",
                    (rs, rowNum) -> rs.getString("label"),
                    showtimeId
            );
            bookedSeatLabels.addAll(showtimeBooked);
        } catch (Exception ignored) {}

        // 4. Build seats list according to frontend contract
        int basePriceInt = showtime.getBasePrice() != null ? showtime.getBasePrice().intValue() : 90000;
        List<Map<String, Object>> seats = new ArrayList<>();

        if (seatRows.isEmpty()) {
            for (int r = 0; r < 6; r++) {
                String rowLetter = String.valueOf((char) ('A' + r));
                for (int c = 1; c <= 10; c++) {
                    Map<String, Object> seat = new LinkedHashMap<>();
                    String seatId = rowLetter + c;
                    String typeUpper = (r >= 2 && r <= 4) ? "VIP" : (r == 5 ? "SWEETBOX" : "STANDARD");
                    String shape = (r >= 2 && r <= 4) ? "vip" : (r == 5 ? "sweetbox" : "standard");
                    Map<String, Object> meta = seatTypeMetaMap.getOrDefault(typeUpper, Map.of(
                            "name", typeUpper, "slug", typeUpper.toLowerCase(), "shapeType", shape
                    ));
                    seat.put("id", seatId);
                    seat.put("row", rowLetter);
                    seat.put("number", c);
                    seat.put("type", meta.get("name"));
                    seat.put("typeSlug", meta.get("slug"));
                    seat.put("shapeType", meta.getOrDefault("shapeType", shape));
                    seat.put("price", basePriceInt + ((r >= 2 && r <= 4) ? 30000 : (r == 5 ? 50000 : 0)));
                    seat.put("status", bookedSeatLabels.contains(seatId) ? "BOOKED" : "AVAILABLE");
                    seat.put("aisleAfter", c == 5);
                    seats.add(seat);
                }
            }
        } else {
            for (Map<String, Object> rowMap : seatRows) {
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
                    if (upper.contains("SWEETBOX") || upper.contains("COUPLE") || upper.contains("ĐÔI") || upper.contains("DOI")) shape = "sweetbox";
                    else if (upper.contains("VIP")) shape = "vip";
                    else if (upper.contains("4DX") || upper.contains("4D")) shape = "4dx";
                    else if (upper.contains("IMAX")) shape = "imax";
                    else if (upper.contains("RECLINER") || upper.contains("LƯỜI") || upper.contains("LUOI")) shape = "recliner";
                    else if (upper.contains("WHEELCHAIR") || upper.contains("KHUYẾT") || upper.contains("KHUYT")) shape = "wheelchair";

                    meta = Map.of(
                            "name", rawTypeName != null ? rawTypeName : "Standard",
                            "slug", slugify(rawTypeName != null ? rawTypeName : "standard"),
                            "shapeType", shape
                    );
                }

                String typeName = (String) meta.get("name");
                String typeSlug = (String) meta.get("slug");
                String seatId = rowLetter + number;
                Map<String, Object> seat = new LinkedHashMap<>();
                seat.put("id", seatId);
                seat.put("row", rowLetter);
                seat.put("number", number);
                seat.put("type", typeName);
                seat.put("typeSlug", typeSlug);
                seat.put("price", basePriceInt + (int) Math.round(surcharge));

                if ("INACTIVE".equalsIgnoreCase(dbStatus) || "UNAVAILABLE".equalsIgnoreCase(dbStatus)) {
                    seat.put("status", "UNAVAILABLE");
                } else if (bookedSeatLabels.contains(seatId)) {
                    seat.put("status", "BOOKED");
                } else {
                    seat.put("status", "AVAILABLE");
                }
                seat.put("shapeType", rowMap.get("shapeType") != null ? rowMap.get("shapeType") : meta.getOrDefault("shapeType", "standard"));
                seat.put("pairId", rowMap.get("pairId"));
                seat.put("aisleAfter", false);
                seats.add(seat);
            }
        }

        // 5. Products / Combos
        List<Map<String, Object>> products = new ArrayList<>();
        try {
            products = jdbcTemplate.query(
                    "SELECT product_id, product_name, description, price, image_url " +
                    "FROM products WHERE status = 'ACTIVE' ORDER BY product_id ASC",
                    (rs, rowNum) -> {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("id", String.valueOf(rs.getLong("product_id")));
                        p.put("name", rs.getString("product_name"));
                        p.put("description", rs.getString("description") != null ? rs.getString("description") : "");
                        p.put("price", rs.getInt("price"));
                        p.put("imageUrl", rs.getString("image_url") != null ? rs.getString("image_url") : "/assets/img/fnb/box-combos.jpg");
                        return p;
                    }
            );
        } catch (Exception ignored) {}

        if (products.isEmpty()) {
            products = List.of(
                    Map.of("id", "solo", "name", "Solo combo", "description", "1 large popcorn + 1 soft drink", "price", 89000, "imageUrl", "/assets/img/fnb/box-popcorn.jpg"),
                    Map.of("id", "duo", "name", "Movie night for two", "description", "1 large popcorn + 2 soft drinks", "price", 129000, "imageUrl", "/assets/img/fnb/box-combos.jpg"),
                    Map.of("id", "drink", "name", "Refreshing soft drink", "description", "1 large soft drink", "price", 35000, "imageUrl", "/assets/img/fnb/box-beverages.jpg")
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
        // Re-index active seat numbers per row (excluding UNAVAILABLE / INACTIVE)
        // Ensure every seat in response has a 100% unique id so validateBooking never fails!
        Map<String, Integer> rowCounter = new LinkedHashMap<>();
        for (Map<String, Object> seat : seats) {
            String r = (String) seat.get("row");
            String st = (String) seat.get("status");
            int colNum = (int) seat.getOrDefault("number", 0);
            if (!"UNAVAILABLE".equalsIgnoreCase(st) && !"INACTIVE".equalsIgnoreCase(st)) {
                int actNum = rowCounter.getOrDefault(r, 0) + 1;
                rowCounter.put(r, actNum);
                seat.put("activeNumber", actNum);
                seat.put("seatLabel", r + actNum);
                seat.put("id", r + actNum);
            } else {
                seat.put("activeNumber", null);
                seat.put("seatLabel", r + "×");
                seat.put("id", "UNAVAIL_" + r + "_" + colNum);
            }
        }

        response.put("seats", seats);
        response.put("seatTypes", seatTypesList);
        response.put("products", products);
        response.put("preview", false);

        return ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore())
                .body(response);
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "errorCode", code, "message", message));
    }

    public record ScreeningContext(String showtimeId, String movieId, String movieTitle, String ageRating,
            String posterUrl, String format, String cinemaId, String cinemaName, String roomName,
            String screeningLabel, BigDecimal basePrice) {}
}
