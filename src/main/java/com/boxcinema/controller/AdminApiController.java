package com.boxcinema.controller;

import com.boxcinema.service.PasswordHashService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/admin/api")
@PreAuthorize("hasRole('ADMIN')")
public class AdminApiController {

    private final JdbcTemplate jdbcTemplate;
    private final PasswordHashService passwordHashService;

    public AdminApiController(JdbcTemplate jdbcTemplate, PasswordHashService passwordHashService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordHashService = passwordHashService;
    }

    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> getAdminData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);

        // 1. Movies with genreIds
        List<Map<String, Object>> movies = jdbcTemplate.query(
                "SELECT m.movie_id, m.title, m.synopsis, m.duration_minutes, m.age_rating, m.language, " +
                        "m.release_date, m.end_date, m.status, m.poster_url, m.trailer_url, " +
                        "GROUP_CONCAT(mg.genre_id) as genre_ids " +
                        "FROM movies m " +
                        "LEFT JOIN movie_genres mg ON m.movie_id = mg.movie_id " +
                        "GROUP BY m.movie_id ORDER BY m.movie_id DESC",
                (rs, rowNum) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", String.valueOf(rs.getLong("movie_id")));
                    m.put("title", rs.getString("title"));
                    m.put("durationMinutes", rs.getInt("duration_minutes"));
                    m.put("ageRating", rs.getString("age_rating"));
                    m.put("language", rs.getString("language"));
                    m.put("releaseDate", rs.getString("release_date"));
                    m.put("endDate", rs.getString("end_date") != null ? rs.getString("end_date") : "");
                    m.put("status", rs.getString("status"));
                    String gIds = rs.getString("genre_ids");
                    m.put("genreIds",
                            gIds != null && !gIds.isBlank() ? Arrays.asList(gIds.split(",")) : Collections.emptyList());
                    m.put("synopsis", rs.getString("synopsis") != null ? rs.getString("synopsis") : "");
                    m.put("posterUrl", rs.getString("poster_url") != null ? rs.getString("poster_url") : "");
                    m.put("trailerUrl", rs.getString("trailer_url") != null ? rs.getString("trailer_url") : "");
                    return m;
                });
        data.put("movies", movies);

        // 2. Genres
        List<Map<String, Object>> genres = jdbcTemplate.query(
                "SELECT genre_id, genre_name, description FROM genres ORDER BY genre_id ASC",
                (rs, rowNum) -> {
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("id", String.valueOf(rs.getLong("genre_id")));
                    g.put("genreName", rs.getString("genre_name"));
                    g.put("description", rs.getString("description") != null ? rs.getString("description") : "");
                    return g;
                });
        data.put("genres", genres);

        // 3. Cinemas
        List<Map<String, Object>> cinemas = jdbcTemplate.query(
                "SELECT cinema_id, cinema_name, city, address, phone, email, status FROM cinemas ORDER BY cinema_id ASC",
                (rs, rowNum) -> {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("id", String.valueOf(rs.getLong("cinema_id")));
                    c.put("cinemaName", rs.getString("cinema_name"));
                    c.put("city", rs.getString("city"));
                    c.put("address", rs.getString("address"));
                    c.put("phone", rs.getString("phone"));
                    c.put("email", rs.getString("email"));
                    c.put("status", rs.getString("status"));
                    return c;
                });
        data.put("cinemas", cinemas);

        // 4. Rooms
        List<Map<String, Object>> rooms = jdbcTemplate.query(
                "SELECT room_id, cinema_id, room_name, room_type, status FROM rooms ORDER BY room_id ASC",
                (rs, rowNum) -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("id", String.valueOf(rs.getLong("room_id")));
                    r.put("cinemaId", String.valueOf(rs.getLong("cinema_id")));
                    r.put("roomName", rs.getString("room_name"));
                    r.put("roomType", rs.getString("room_type"));
                    r.put("status", rs.getString("status"));
                    return r;
                });
        data.put("rooms", rooms);

        // 5. Showtimes
        List<Map<String, Object>> showtimes = jdbcTemplate.query(
                "SELECT showtime_id, movie_id, room_id, start_at, end_at, base_price, status " +
                        "FROM showtimes ORDER BY start_at DESC LIMIT 500",
                (rs, rowNum) -> {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("id", String.valueOf(rs.getLong("showtime_id")));
                    s.put("movieId", String.valueOf(rs.getLong("movie_id")));
                    s.put("roomId", String.valueOf(rs.getLong("room_id")));
                    Timestamp start = rs.getTimestamp("start_at");
                    Timestamp end = rs.getTimestamp("end_at");
                    s.put("startAt",
                            start != null
                                    ? start.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                                    : "");
                    s.put("endAt",
                            end != null
                                    ? end.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                                    : "");
                    s.put("basePrice", rs.getDouble("base_price"));
                    s.put("status", rs.getString("status"));
                    return s;
                });
        data.put("showtimes", showtimes);

        // 6. Pricing (Seat Types)
        List<Map<String, Object>> pricing = jdbcTemplate.query(
                "SELECT seat_type_id, type_name, price_surcharge, description, shape_type FROM seat_types ORDER BY seat_type_id ASC",
                (rs, rowNum) -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("id", String.valueOf(rs.getLong("seat_type_id")));
                    p.put("typeName", rs.getString("type_name"));
                    p.put("priceSurcharge", rs.getDouble("price_surcharge"));
                    p.put("description", rs.getString("description") != null ? rs.getString("description") : "");
                    p.put("shapeType", rs.getString("shape_type") != null ? rs.getString("shape_type") : "standard");
                    return p;
                });
        data.put("pricing", pricing);

        // 7. Promotions
        List<Map<String, Object>> promotions = jdbcTemplate.query(
                "SELECT promotion_id, promotion_name, voucher_code, discount_type, discount_value, max_discount, min_order_amount, "
                        +
                        "start_at, end_at, usage_limit, per_user_limit, status FROM promotions ORDER BY promotion_id DESC",
                (rs, rowNum) -> {
                    Map<String, Object> pr = new LinkedHashMap<>();
                    pr.put("id", String.valueOf(rs.getLong("promotion_id")));
                    pr.put("promotionName", rs.getString("promotion_name"));
                    pr.put("voucherCode", rs.getString("voucher_code") != null ? rs.getString("voucher_code") : "");
                    pr.put("discountType", rs.getString("discount_type"));
                    pr.put("discountValue", rs.getDouble("discount_value"));
                    pr.put("maxDiscount", rs.getDouble("max_discount"));
                    pr.put("minOrderAmount", rs.getDouble("min_order_amount"));
                    Timestamp start = rs.getTimestamp("start_at");
                    Timestamp end = rs.getTimestamp("end_at");
                    pr.put("startAt",
                            start != null
                                    ? start.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                                    : "");
                    pr.put("endAt",
                            end != null
                                    ? end.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                                    : "");
                    pr.put("usageLimit", rs.getInt("usage_limit"));
                    pr.put("perUserLimit", rs.getInt("per_user_limit"));
                    pr.put("status", rs.getString("status"));
                    return pr;
                });
        data.put("promotions", promotions);

        // 8. Combos (Products)
        List<Map<String, Object>> combos = jdbcTemplate.query(
                "SELECT product_id, product_name, product_type, current_price, description, status FROM products ORDER BY product_id ASC",
                (rs, rowNum) -> {
                    Map<String, Object> cb = new LinkedHashMap<>();
                    cb.put("id", String.valueOf(rs.getLong("product_id")));
                    cb.put("productName", rs.getString("product_name"));
                    cb.put("productType", rs.getString("product_type"));
                    cb.put("price", rs.getDouble("current_price"));
                    cb.put("description", rs.getString("description") != null ? rs.getString("description") : "");
                    cb.put("status", rs.getString("status"));
                    return cb;
                });
        data.put("combos", combos);

        // 9. Staff (Users with role STAFF, MANAGER, ADMIN)
        List<Map<String, Object>> staff = jdbcTemplate.query(
                "SELECT user_id, full_name, email, phone, cinema_id, role, account_status FROM users WHERE role IN ('STAFF', 'MANAGER', 'ADMIN') ORDER BY user_id ASC",
                (rs, rowNum) -> {
                    Map<String, Object> st = new LinkedHashMap<>();
                    st.put("id", String.valueOf(rs.getLong("user_id")));
                    st.put("fullName", rs.getString("full_name"));
                    st.put("email", rs.getString("email"));
                    st.put("phone", rs.getString("phone") != null ? rs.getString("phone") : "");
                    Long cinemaId = rs.getLong("cinema_id");
                    st.put("cinemaId", !rs.wasNull() && cinemaId > 0 ? String.valueOf(cinemaId) : "");
                    st.put("role", rs.getString("role"));
                    st.put("status", rs.getString("account_status"));
                    return st;
                });
        data.put("staff", staff);

        // 10. Seat layouts per room
        Map<String, List<Map<String, Object>>> layouts = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT seat_id, room_id, seat_type_id, seat_row, seat_number, status, pair_id FROM seats ORDER BY room_id, seat_row, seat_number",
                rs -> {
                    String roomId = String.valueOf(rs.getLong("room_id"));
                    layouts.computeIfAbsent(roomId, k -> new ArrayList<>());
                    Map<String, Object> seat = new LinkedHashMap<>();
                    seat.put("row", rs.getString("seat_row"));
                    seat.put("number", rs.getInt("seat_number"));
                    seat.put("typeId", String.valueOf(rs.getLong("seat_type_id")));
                    seat.put("status", rs.getString("status"));
                    seat.put("pairId", rs.getString("pair_id"));
                    layouts.get(roomId).add(seat);
                });
        data.put("layouts", layouts);

        // 11. Sales records (from bookings)
        List<Map<String, Object>> sales = jdbcTemplate.query(
                "SELECT b.booking_id, b.booking_code, DATE_FORMAT(b.booked_at, '%Y-%m-%d') as sale_date, " +
                        "s.movie_id, r.cinema_id, " +
                        "COALESCE((SELECT COUNT(*) FROM booking_seats bs WHERE bs.booking_id = b.booking_id), 2) as seat_cnt, "
                        +
                        "b.total_amount, " +
                        "CASE WHEN b.booking_status = 'CANCELLED' THEN 'CANCELLED' ELSE 'PAID' END as sale_status " +
                        "FROM bookings b " +
                        "JOIN showtimes s ON b.showtime_id = s.showtime_id " +
                        "JOIN rooms r ON s.room_id = r.room_id " +
                        "ORDER BY b.booked_at DESC LIMIT 500",
                (rs, rowNum) -> {
                    Map<String, Object> sl = new LinkedHashMap<>();
                    sl.put("id", rs.getString("booking_code"));
                    sl.put("date", rs.getString("sale_date"));
                    sl.put("movieId", String.valueOf(rs.getLong("movie_id")));
                    sl.put("cinemaId", String.valueOf(rs.getLong("cinema_id")));
                    int tickets = rs.getInt("seat_cnt");
                    sl.put("tickets", tickets > 0 ? tickets : 2);
                    sl.put("revenue", rs.getDouble("total_amount"));
                    sl.put("status", rs.getString("sale_status"));
                    return sl;
                });
        data.put("sales", sales);

        // 12. Rich Bookings records (with full invoice & customer breakdown)
        List<Map<String, Object>> bookings = jdbcTemplate.query(
                "SELECT b.booking_id, b.booking_code, b.booking_status, " +
                        "DATE_FORMAT(b.booked_at, '%Y-%m-%d %H:%i') as booked_at_formatted, " +
                        "b.subtotal, b.discount_amount, b.total_amount, b.booking_channel, " +
                        "COALESCE(b.guest_name, u.full_name, 'Khách hàng') as customer_name, " +
                        "COALESCE(b.guest_email, u.email, '') as customer_email, " +
                        "COALESCE(b.guest_phone, u.phone, '') as customer_phone, " +
                        "s.showtime_id, DATE_FORMAT(s.start_at, '%Y-%m-%d %H:%i') as showtime_start, " +
                        "s.base_price, m.movie_id, m.title as movie_title, m.poster_url, m.age_rating, " +
                        "r.room_id, r.room_name, r.room_type, " +
                        "c.cinema_id, c.cinema_name, c.city, " +
                        "p.payment_provider, p.payment_method, p.payment_status, " +
                        "pr.promotion_name, pr.voucher_code, " +
                        "(SELECT GROUP_CONCAT(CONCAT(bs.seat_label_snapshot, ' (', st.type_name, ' - ', FORMAT(bs.unit_price, 0), 'đ)') SEPARATOR ', ') "
                        +
                        " FROM booking_seats bs " +
                        " JOIN showtime_seats ss ON bs.showtime_seat_id = ss.showtime_seat_id " +
                        " JOIN seats seat ON ss.seat_id = seat.seat_id " +
                        " JOIN seat_types st ON seat.seat_type_id = st.seat_type_id " +
                        " WHERE bs.booking_id = b.booking_id) as seats_detail, " +
                        "(SELECT GROUP_CONCAT(bs.seat_label_snapshot SEPARATOR ', ') " +
                        " FROM booking_seats bs WHERE bs.booking_id = b.booking_id) as seats_summary, " +
                        "(SELECT COUNT(*) FROM booking_seats bs WHERE bs.booking_id = b.booking_id) as seat_cnt, " +
                        "(SELECT GROUP_CONCAT(CONCAT(prod.product_name, ' x', bi.quantity, ' (', FORMAT(bi.subtotal, 0), 'đ)') SEPARATOR ', ') "
                        +
                        " FROM booking_items bi " +
                        " JOIN products prod ON bi.product_id = prod.product_id " +
                        " WHERE bi.booking_id = b.booking_id) as combos_detail " +
                        "FROM bookings b " +
                        "LEFT JOIN users u ON b.user_id = u.user_id " +
                        "JOIN showtimes s ON b.showtime_id = s.showtime_id " +
                        "JOIN movies m ON s.movie_id = m.movie_id " +
                        "JOIN rooms r ON s.room_id = r.room_id " +
                        "JOIN cinemas c ON r.cinema_id = c.cinema_id " +
                        "LEFT JOIN payments p ON b.booking_id = p.booking_id " +
                        "LEFT JOIN promotions pr ON b.promotion_id = pr.promotion_id " +
                        "ORDER BY b.booked_at DESC, b.booking_id DESC LIMIT 500",
                (rs, rowNum) -> {
                    Map<String, Object> bk = new LinkedHashMap<>();
                    bk.put("id", String.valueOf(rs.getLong("booking_id")));
                    bk.put("bookingCode", rs.getString("booking_code"));
                    bk.put("bookingStatus", rs.getString("booking_status"));
                    bk.put("bookedAt", rs.getString("booked_at_formatted"));
                    bk.put("subtotal", rs.getDouble("subtotal"));
                    bk.put("discountAmount", rs.getDouble("discount_amount"));
                    bk.put("totalAmount", rs.getDouble("total_amount"));
                    bk.put("bookingChannel",
                            rs.getString("booking_channel") != null ? rs.getString("booking_channel") : "ONLINE");
                    bk.put("customerName", rs.getString("customer_name"));
                    bk.put("customerEmail", rs.getString("customer_email"));
                    bk.put("customerPhone", rs.getString("customer_phone"));
                    bk.put("showtimeId", String.valueOf(rs.getLong("showtime_id")));
                    bk.put("showtimeStart", rs.getString("showtime_start"));
                    bk.put("basePrice", rs.getDouble("base_price"));
                    bk.put("movieId", String.valueOf(rs.getLong("movie_id")));
                    bk.put("movieTitle", rs.getString("movie_title"));
                    bk.put("posterUrl", rs.getString("poster_url") != null ? rs.getString("poster_url") : "");
                    bk.put("ageRating", rs.getString("age_rating") != null ? rs.getString("age_rating") : "P");
                    bk.put("roomId", String.valueOf(rs.getLong("room_id")));
                    bk.put("roomName", rs.getString("room_name"));
                    bk.put("roomType", rs.getString("room_type"));
                    bk.put("cinemaId", String.valueOf(rs.getLong("cinema_id")));
                    bk.put("cinemaName", rs.getString("cinema_name"));
                    bk.put("city", rs.getString("city"));
                    bk.put("paymentProvider",
                            rs.getString("payment_provider") != null ? rs.getString("payment_provider") : "VNPAY");
                    bk.put("paymentMethod",
                            rs.getString("payment_method") != null ? rs.getString("payment_method") : "QR_CODE");
                    bk.put("paymentStatus",
                            rs.getString("payment_status") != null ? rs.getString("payment_status") : "SUCCESS");
                    bk.put("promotionName",
                            rs.getString("promotion_name") != null ? rs.getString("promotion_name") : "");
                    bk.put("voucherCode", rs.getString("voucher_code") != null ? rs.getString("voucher_code") : "");

                    int seatCnt = rs.getInt("seat_cnt");
                    bk.put("ticketCount", seatCnt > 0 ? seatCnt : 2);
                    String seatsSummary = rs.getString("seats_summary");
                    bk.put("seatsSummary", seatsSummary != null && !seatsSummary.isBlank() ? seatsSummary : "A1, A2");
                    String seatsDetail = rs.getString("seats_detail");
                    bk.put("seatsDetail", seatsDetail != null && !seatsDetail.isBlank() ? seatsDetail
                            : "A1 (STANDARD), A2 (STANDARD)");
                    String combosDetail = rs.getString("combos_detail");
                    bk.put("combosDetail", combosDetail != null ? combosDetail : "");
                    return bk;
                });
        data.put("bookings", bookings);

        return ResponseEntity.ok(data);
    }

    @GetMapping("/impact-check")
    public ResponseEntity<Map<String, Object>> checkImpact(
            @RequestParam String section,
            @RequestParam Long id) {
        Map<String, Object> response = new LinkedHashMap<>();

        String sql;
        String countShowtimesSql;
        if ("cinemas".equalsIgnoreCase(section)) {
            sql = "SELECT b.booking_id, b.booking_code, b.total_amount, " +
                    "COALESCE(b.guest_name, u.full_name, 'Khách hàng') AS customer_name, " +
                    "COALESCE(b.guest_email, u.email, '') AS customer_email, " +
                    "COALESCE(b.guest_phone, u.phone, '') AS customer_phone, " +
                    "s.showtime_id, s.start_at, m.title AS movie_title, r.room_name, " +
                    "(SELECT COUNT(*) FROM booking_seats bs WHERE bs.booking_id = b.booking_id) AS ticket_count " +
                    "FROM bookings b " +
                    "LEFT JOIN users u ON b.user_id = u.user_id " +
                    "JOIN showtimes s ON b.showtime_id = s.showtime_id " +
                    "JOIN rooms r ON s.room_id = r.room_id " +
                    "JOIN movies m ON s.movie_id = m.movie_id " +
                    "WHERE r.cinema_id = ? " +
                    "AND s.start_at >= NOW() " +
                    "AND b.booking_status IN ('CONFIRMED', 'PAID', 'HOLDING') " +
                    "ORDER BY s.start_at ASC";
            countShowtimesSql = "SELECT COUNT(*) FROM showtimes s JOIN rooms r ON s.room_id = r.room_id WHERE r.cinema_id = ? AND s.start_at >= NOW() AND s.status = 'ACTIVE'";
        } else if ("rooms".equalsIgnoreCase(section)) {
            sql = "SELECT b.booking_id, b.booking_code, b.total_amount, " +
                    "COALESCE(b.guest_name, u.full_name, 'Khách hàng') AS customer_name, " +
                    "COALESCE(b.guest_email, u.email, '') AS customer_email, " +
                    "COALESCE(b.guest_phone, u.phone, '') AS customer_phone, " +
                    "s.showtime_id, s.start_at, m.title AS movie_title, r.room_name, " +
                    "(SELECT COUNT(*) FROM booking_seats bs WHERE bs.booking_id = b.booking_id) AS ticket_count " +
                    "FROM bookings b " +
                    "LEFT JOIN users u ON b.user_id = u.user_id " +
                    "JOIN showtimes s ON b.showtime_id = s.showtime_id " +
                    "JOIN rooms r ON s.room_id = r.room_id " +
                    "JOIN movies m ON s.movie_id = m.movie_id " +
                    "WHERE r.room_id = ? " +
                    "AND s.start_at >= NOW() " +
                    "AND b.booking_status IN ('CONFIRMED', 'PAID', 'HOLDING') " +
                    "ORDER BY s.start_at ASC";
            countShowtimesSql = "SELECT COUNT(*) FROM showtimes WHERE room_id = ? AND start_at >= NOW() AND status = 'ACTIVE'";
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid section for impact check"));
        }

        List<Map<String, Object>> bookings = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bookingId", rs.getLong("booking_id"));
            item.put("bookingCode", rs.getString("booking_code"));
            item.put("customerName", rs.getString("customer_name"));
            item.put("customerEmail", rs.getString("customer_email"));
            item.put("customerPhone", rs.getString("customer_phone"));
            item.put("showtimeId", rs.getLong("showtime_id"));
            Timestamp startAt = rs.getTimestamp("start_at");
            item.put("startAt",
                    startAt != null ? startAt.toLocalDateTime().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
                            : "");
            item.put("movieTitle", rs.getString("movie_title"));
            item.put("roomName", rs.getString("room_name"));
            int ticketCount = rs.getInt("ticket_count");
            item.put("ticketCount", ticketCount > 0 ? ticketCount : 1);
            item.put("totalAmount", rs.getDouble("total_amount"));
            return item;
        }, id);

        Integer upcomingShowtimes = jdbcTemplate.queryForObject(countShowtimesSql, Integer.class, id);
        int totalTickets = bookings.stream().mapToInt(b -> (int) b.get("ticketCount")).sum();
        double totalAmount = bookings.stream().mapToDouble(b -> (double) b.get("totalAmount")).sum();

        response.put("hasImpact", !bookings.isEmpty());
        response.put("totalBookings", bookings.size());
        response.put("totalTickets", totalTickets);
        response.put("totalAmount", totalAmount);
        response.put("upcomingShowtimes", upcomingShowtimes != null ? upcomingShowtimes : 0);
        response.put("bookings", bookings);

        return ResponseEntity.ok(response);
    }

    @PostMapping("/resolve-deactivation")
    @Transactional
    public ResponseEntity<Map<String, Object>> resolveDeactivation(@RequestBody Map<String, Object> payload) {
        String section = String.valueOf(payload.get("section"));
        Long id = Long.valueOf(String.valueOf(payload.get("id")));
        String strategy = String.valueOf(payload.get("strategy"));

        Map<String, Object> result = new LinkedHashMap<>();

        if ("REFUND_AND_CANCEL".equalsIgnoreCase(strategy)) {
            if ("cinemas".equalsIgnoreCase(section)) {
                List<Long> showtimeIds = jdbcTemplate.queryForList(
                        "SELECT s.showtime_id FROM showtimes s JOIN rooms r ON s.room_id = r.room_id WHERE r.cinema_id = ? AND s.start_at >= NOW()",
                        Long.class, id);

                if (!showtimeIds.isEmpty()) {
                    String inSql = String.join(",", Collections.nCopies(showtimeIds.size(), "?"));
                    jdbcTemplate.update(
                            "UPDATE payments p JOIN bookings b ON p.booking_id = b.booking_id " +
                                    "SET p.payment_status = 'REFUNDED' WHERE b.showtime_id IN (" + inSql + ")",
                            showtimeIds.toArray());
                    jdbcTemplate.update(
                            "UPDATE bookings SET booking_status = 'CANCELLED', cancelled_at = NOW() WHERE showtime_id IN ("
                                    + inSql + ") AND booking_status IN ('CONFIRMED', 'PAID', 'HOLDING')",
                            showtimeIds.toArray());
                    jdbcTemplate.update(
                            "UPDATE showtime_seats SET seat_status = 'AVAILABLE', hold_token_hash = NULL, held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL WHERE showtime_id IN ("
                                    + inSql + ")",
                            showtimeIds.toArray());
                    jdbcTemplate.update(
                            "UPDATE showtimes SET status = 'CANCELLED' WHERE showtime_id IN (" + inSql + ")",
                            showtimeIds.toArray());
                }

                jdbcTemplate.update("UPDATE rooms SET status = 'INACTIVE' WHERE cinema_id = ?", id);
                jdbcTemplate.update("UPDATE cinemas SET status = 'INACTIVE' WHERE cinema_id = ?", id);

                result.put("success", true);
                result.put("message",
                        "Đã dừng hoạt động cơ sở rạp. Toàn bộ phòng chiếu đã chuyển sang Inactive, hủy các suất chiếu tương lai và tự động hoàn tiền 100% cho khách hàng đã mua vé.");
            } else if ("rooms".equalsIgnoreCase(section)) {
                List<Long> showtimeIds = jdbcTemplate.queryForList(
                        "SELECT showtime_id FROM showtimes WHERE room_id = ? AND start_at >= NOW()",
                        Long.class, id);

                if (!showtimeIds.isEmpty()) {
                    String inSql = String.join(",", Collections.nCopies(showtimeIds.size(), "?"));
                    jdbcTemplate.update(
                            "UPDATE payments p JOIN bookings b ON p.booking_id = b.booking_id " +
                                    "SET p.payment_status = 'REFUNDED' WHERE b.showtime_id IN (" + inSql + ")",
                            showtimeIds.toArray());
                    jdbcTemplate.update(
                            "UPDATE bookings SET booking_status = 'CANCELLED', cancelled_at = NOW() WHERE showtime_id IN ("
                                    + inSql + ") AND booking_status IN ('CONFIRMED', 'PAID', 'HOLDING')",
                            showtimeIds.toArray());
                    jdbcTemplate.update(
                            "UPDATE showtime_seats SET seat_status = 'AVAILABLE', hold_token_hash = NULL, held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL WHERE showtime_id IN ("
                                    + inSql + ")",
                            showtimeIds.toArray());
                    jdbcTemplate.update(
                            "UPDATE showtimes SET status = 'CANCELLED' WHERE showtime_id IN (" + inSql + ")",
                            showtimeIds.toArray());
                }

                jdbcTemplate.update("UPDATE rooms SET status = 'INACTIVE' WHERE room_id = ?", id);
                result.put("success", true);
                result.put("message",
                        "Đã dừng hoạt động phòng chiếu. Hủy các suất chiếu tương lai và tự động hoàn tiền 100% cho tất cả khách hàng.");
            }
        } else if ("GRACEFUL_DECOMMISSION".equalsIgnoreCase(strategy)) {
            if ("cinemas".equalsIgnoreCase(section)) {
                jdbcTemplate.update(
                        "UPDATE showtimes SET status = 'CANCELLED' WHERE room_id IN (SELECT room_id FROM rooms WHERE cinema_id = ?) "
                                +
                                "AND start_at >= NOW() AND status = 'ACTIVE' " +
                                "AND showtime_id NOT IN (SELECT showtime_id FROM bookings WHERE booking_status IN ('CONFIRMED', 'PAID'))",
                        id);

                jdbcTemplate.update("UPDATE cinemas SET status = 'STOP_BOOKING' WHERE cinema_id = ?", id);

                jdbcTemplate.update(
                        "UPDATE rooms SET status = 'STOP_BOOKING' WHERE cinema_id = ? AND room_id IN (SELECT room_id FROM showtimes WHERE start_at >= NOW() AND status = 'ACTIVE')",
                        id);
                jdbcTemplate.update(
                        "UPDATE rooms SET status = 'INACTIVE' WHERE cinema_id = ? AND room_id NOT IN (SELECT room_id FROM showtimes WHERE start_at >= NOW() AND status = 'ACTIVE')",
                        id);

                result.put("success", true);
                result.put("message",
                        "Đã chuyển cơ sở rạp sang chế độ Đóng theo lộ trình (STOP_BOOKING). Rạp ngừng nhận khách mới; các suất chiếu đã có người mua vé vẫn tiếp tục diễn ra bình thường.");
            } else if ("rooms".equalsIgnoreCase(section)) {
                jdbcTemplate.update(
                        "UPDATE showtimes SET status = 'CANCELLED' WHERE room_id = ? AND start_at >= NOW() AND status = 'ACTIVE' "
                                +
                                "AND showtime_id NOT IN (SELECT showtime_id FROM bookings WHERE booking_status IN ('CONFIRMED', 'PAID'))",
                        id);
                jdbcTemplate.update("UPDATE rooms SET status = 'STOP_BOOKING' WHERE room_id = ?", id);
                result.put("success", true);
                result.put("message",
                        "Đã chuyển phòng chiếu sang chế độ Ngưng nhận khách mới (STOP_BOOKING). Các suất chiếu đã có vé sẽ diễn ra bình thường.");
            }
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown deactivation strategy"));
        }

        return ResponseEntity.ok(result);
    }

    @PostMapping("/save/{section}")
    @Transactional
    public ResponseEntity<Map<String, Object>> saveRecord(@PathVariable String section,
            @RequestBody Map<String, Object> body) {
        String rawId = body.get("id") != null ? String.valueOf(body.get("id")) : null;
        boolean isNumericId = rawId != null && rawId.matches("^\\d+$");
        Long id = isNumericId ? Long.valueOf(rawId) : null;

        Map<String, Object> result = new LinkedHashMap<>();

        try {
            switch (section) {
                case "movies" -> {
                    String title = String.valueOf(body.get("title"));
                    int durationMinutes = Integer.parseInt(String.valueOf(body.get("durationMinutes")));
                    String ageRating = String.valueOf(body.get("ageRating"));
                    String language = String.valueOf(body.get("language"));
                    String releaseDate = String.valueOf(body.get("releaseDate"));
                    String endDate = body.get("endDate") != null && !String.valueOf(body.get("endDate")).isBlank()
                            ? String.valueOf(body.get("endDate"))
                            : null;
                    String status = String.valueOf(body.get("status"));
                    String synopsis = body.get("synopsis") != null ? String.valueOf(body.get("synopsis")) : null;
                    String posterUrl = body.get("posterUrl") != null ? String.valueOf(body.get("posterUrl")) : null;
                    String trailerUrl = body.get("trailerUrl") != null ? String.valueOf(body.get("trailerUrl")) : null;

                    if (id != null) {
                        jdbcTemplate.update(
                                "UPDATE movies SET title=?, duration_minutes=?, age_rating=?, language=?, release_date=?, end_date=?, status=?, synopsis=?, poster_url=?, trailer_url=? WHERE movie_id=?",
                                title, durationMinutes, ageRating, language, releaseDate, endDate, status, synopsis,
                                posterUrl, trailerUrl, id);
                        result.put("id", String.valueOf(id));
                    } else {
                        KeyHolder keyHolder = new GeneratedKeyHolder();
                        jdbcTemplate.update(connection -> {
                            PreparedStatement ps = connection.prepareStatement(
                                    "INSERT INTO movies (title, duration_minutes, age_rating, language, release_date, end_date, status, synopsis, poster_url, trailer_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                            ps.setString(1, title);
                            ps.setInt(2, durationMinutes);
                            ps.setString(3, ageRating);
                            ps.setString(4, language);
                            ps.setString(5, releaseDate);
                            ps.setString(6, endDate);
                            ps.setString(7, status);
                            ps.setString(8, synopsis);
                            ps.setString(9, posterUrl);
                            ps.setString(10, trailerUrl);
                            return ps;
                        }, keyHolder);
                        id = Objects.requireNonNull(keyHolder.getKey()).longValue();
                        result.put("id", String.valueOf(id));
                    }

                    // Update movie_genres
                    if (body.get("genreIds") instanceof List<?> list) {
                        jdbcTemplate.update("DELETE FROM movie_genres WHERE movie_id=?", id);
                        for (Object gIdObj : list) {
                            String gIdStr = String.valueOf(gIdObj);
                            if (gIdStr.matches("^\\d+$")) {
                                jdbcTemplate.update(
                                        "INSERT IGNORE INTO movie_genres (movie_id, genre_id) VALUES (?, ?)", id,
                                        Long.valueOf(gIdStr));
                            }
                        }
                    }
                }
                case "genres" -> {
                    String genreName = String.valueOf(body.get("genreName"));
                    String description = body.get("description") != null ? String.valueOf(body.get("description")) : "";
                    if (id != null) {
                        jdbcTemplate.update("UPDATE genres SET genre_name=?, description=? WHERE genre_id=?", genreName,
                                description, id);
                        result.put("id", String.valueOf(id));
                    } else {
                        KeyHolder keyHolder = new GeneratedKeyHolder();
                        jdbcTemplate.update(conn -> {
                            PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO genres (genre_name, description) VALUES (?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                            ps.setString(1, genreName);
                            ps.setString(2, description);
                            return ps;
                        }, keyHolder);
                        result.put("id", String.valueOf(Objects.requireNonNull(keyHolder.getKey()).longValue()));
                    }
                }
                case "cinemas" -> {
                    String cinemaName = String.valueOf(body.get("cinemaName"));
                    String city = String.valueOf(body.get("city"));
                    String address = String.valueOf(body.get("address"));
                    String phone = String.valueOf(body.get("phone"));
                    String email = String.valueOf(body.get("email"));
                    String status = String.valueOf(body.get("status"));
                    if (id != null) {
                        jdbcTemplate.update(
                                "UPDATE cinemas SET cinema_name=?, city=?, address=?, phone=?, email=?, status=? WHERE cinema_id=?",
                                cinemaName, city, address, phone, email, status, id);
                        result.put("id", String.valueOf(id));
                        if ("INACTIVE".equalsIgnoreCase(status)) {
                            jdbcTemplate.update("UPDATE rooms SET status='INACTIVE' WHERE cinema_id=?", id);
                            jdbcTemplate.update(
                                    "UPDATE showtimes SET status='CANCELLED' WHERE room_id IN (SELECT room_id FROM rooms WHERE cinema_id=?) "
                                            +
                                            "AND start_at >= NOW() AND status='ACTIVE' " +
                                            "AND showtime_id NOT IN (SELECT showtime_id FROM bookings WHERE booking_status IN ('CONFIRMED', 'PAID'))",
                                    id);
                        }
                    } else {
                        KeyHolder keyHolder = new GeneratedKeyHolder();
                        jdbcTemplate.update(conn -> {
                            PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO cinemas (cinema_name, city, address, phone, email, status) VALUES (?, ?, ?, ?, ?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                            ps.setString(1, cinemaName);
                            ps.setString(2, city);
                            ps.setString(3, address);
                            ps.setString(4, phone);
                            ps.setString(5, email);
                            ps.setString(6, status);
                            return ps;
                        }, keyHolder);
                        result.put("id", String.valueOf(Objects.requireNonNull(keyHolder.getKey()).longValue()));
                    }
                }
                case "rooms" -> {
                    String roomName = String.valueOf(body.get("roomName"));
                    Long cinemaId = Long.valueOf(String.valueOf(body.get("cinemaId")));
                    String roomType = String.valueOf(body.get("roomType"));
                    String status = String.valueOf(body.get("status"));
                    if (id != null) {
                        jdbcTemplate.update(
                                "UPDATE rooms SET room_name=?, cinema_id=?, room_type=?, status=? WHERE room_id=?",
                                roomName, cinemaId, roomType, status, id);
                        result.put("id", String.valueOf(id));
                        if ("INACTIVE".equalsIgnoreCase(status)) {
                            jdbcTemplate.update("UPDATE showtimes SET status='CANCELLED' WHERE room_id=? " +
                                    "AND start_at >= NOW() AND status='ACTIVE' " +
                                    "AND showtime_id NOT IN (SELECT showtime_id FROM bookings WHERE booking_status IN ('CONFIRMED', 'PAID'))",
                                    id);
                        }
                    } else {
                        KeyHolder keyHolder = new GeneratedKeyHolder();
                        jdbcTemplate.update(conn -> {
                            PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO rooms (room_name, cinema_id, room_type, status) VALUES (?, ?, ?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                            ps.setString(1, roomName);
                            ps.setLong(2, cinemaId);
                            ps.setString(3, roomType);
                            ps.setString(4, status);
                            return ps;
                        }, keyHolder);
                        long newRoomId = Objects.requireNonNull(keyHolder.getKey()).longValue();
                        result.put("id", String.valueOf(newRoomId));
                        // Automatically generate initial standard seats for the newly created room
                        // (Rows A-G, 1-10)
                        for (char r = 'A'; r <= 'G'; r++) {
                            long typeId = (r <= 'B') ? 1L : (r <= 'F') ? 2L : 3L;
                            for (int col = 1; col <= 10; col++) {
                                String pairId = null;
                                if (r == 'G') {
                                    int firstCol = (col % 2 == 1) ? col : (col - 1);
                                    pairId = "G" + firstCol + "-G" + (firstCol + 1);
                                }
                                jdbcTemplate.update(
                                        "INSERT INTO seats (room_id, seat_type_id, seat_row, seat_number, status, pair_id) VALUES (?, ?, ?, ?, 'ACTIVE', ?)",
                                        newRoomId, typeId, String.valueOf(r), col, pairId);
                            }
                        }
                    }
                }
                case "showtimes" -> {
                    Long movieId = Long.valueOf(String.valueOf(body.get("movieId")));
                    Long roomId = Long.valueOf(String.valueOf(body.get("roomId")));
                    String startAt = String.valueOf(body.get("startAt")).replace('T', ' ');
                    String endAt = String.valueOf(body.get("endAt")).replace('T', ' ');
                    if (startAt.length() == 16)
                        startAt += ":00";
                    if (endAt.length() == 16)
                        endAt += ":00";
                    double basePrice = Double.parseDouble(String.valueOf(body.get("basePrice")));
                    String status = String.valueOf(body.get("status"));
                    if (id != null) {
                        jdbcTemplate.update(
                                "UPDATE showtimes SET movie_id=?, room_id=?, start_at=?, end_at=?, base_price=?, status=? WHERE showtime_id=?",
                                movieId, roomId, startAt, endAt, basePrice, status, id);
                        result.put("id", String.valueOf(id));
                    } else {
                        KeyHolder keyHolder = new GeneratedKeyHolder();
                        String finalStartAt = startAt;
                        String finalEndAt = endAt;
                        jdbcTemplate.update(conn -> {
                            PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO showtimes (movie_id, room_id, start_at, end_at, base_price, status) VALUES (?, ?, ?, ?, ?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                            ps.setLong(1, movieId);
                            ps.setLong(2, roomId);
                            ps.setString(3, finalStartAt);
                            ps.setString(4, finalEndAt);
                            ps.setBigDecimal(5, BigDecimal.valueOf(basePrice));
                            ps.setString(6, status);
                            return ps;
                        }, keyHolder);
                        result.put("id", String.valueOf(Objects.requireNonNull(keyHolder.getKey()).longValue()));
                    }
                }
                case "pricing" -> {
                    String typeName = String.valueOf(body.get("typeName"));
                    double priceSurcharge = Double.parseDouble(String.valueOf(body.get("priceSurcharge")));
                    String description = body.get("description") != null ? String.valueOf(body.get("description")) : "";
                    String shapeType = body.get("shapeType") != null && !String.valueOf(body.get("shapeType")).isBlank()
                            ? String.valueOf(body.get("shapeType")).trim()
                            : "standard";
                    if (id != null) {
                        jdbcTemplate.update(
                                "UPDATE seat_types SET type_name=?, price_surcharge=?, description=?, shape_type=? WHERE seat_type_id=?",
                                typeName, priceSurcharge, description, shapeType, id);
                        result.put("id", String.valueOf(id));
                    } else {
                        KeyHolder keyHolder = new GeneratedKeyHolder();
                        jdbcTemplate.update(conn -> {
                            PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO seat_types (type_name, price_surcharge, description, shape_type) VALUES (?, ?, ?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                            ps.setString(1, typeName);
                            ps.setBigDecimal(2, BigDecimal.valueOf(priceSurcharge));
                            ps.setString(3, description);
                            ps.setString(4, shapeType);
                            return ps;
                        }, keyHolder);
                        result.put("id", String.valueOf(Objects.requireNonNull(keyHolder.getKey()).longValue()));
                    }
                }
                case "promotions" -> {
                    String promotionName = String.valueOf(body.get("promotionName"));
                    String voucherCode = String.valueOf(body.get("voucherCode"));
                    String discountType = String.valueOf(body.get("discountType"));
                    double discountValue = Double.parseDouble(String.valueOf(body.get("discountValue")));
                    double maxDiscount = Double.parseDouble(String.valueOf(body.get("maxDiscount")));
                    double minOrderAmount = Double.parseDouble(String.valueOf(body.get("minOrderAmount")));
                    String startAt = String.valueOf(body.get("startAt")).replace('T', ' ');
                    String endAt = String.valueOf(body.get("endAt")).replace('T', ' ');
                    if (startAt.length() == 16)
                        startAt += ":00";
                    if (endAt.length() == 16)
                        endAt += ":00";
                    int usageLimit = Integer.parseInt(String.valueOf(body.get("usageLimit")));
                    int perUserLimit = Integer.parseInt(String.valueOf(body.get("perUserLimit")));
                    String status = String.valueOf(body.get("status"));
                    if (id != null) {
                        jdbcTemplate.update(
                                "UPDATE promotions SET promotion_name=?, voucher_code=?, discount_type=?, discount_value=?, max_discount=?, min_order_amount=?, start_at=?, end_at=?, usage_limit=?, per_user_limit=?, status=? WHERE promotion_id=?",
                                promotionName, voucherCode, discountType, discountValue, maxDiscount, minOrderAmount,
                                startAt, endAt, usageLimit, perUserLimit, status, id);
                        result.put("id", String.valueOf(id));
                    } else {
                        KeyHolder keyHolder = new GeneratedKeyHolder();
                        String finalStartAt = startAt;
                        String finalEndAt = endAt;
                        jdbcTemplate.update(conn -> {
                            PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO promotions (promotion_name, voucher_code, discount_type, discount_value, max_discount, min_order_amount, start_at, end_at, usage_limit, per_user_limit, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                            ps.setString(1, promotionName);
                            ps.setString(2, voucherCode);
                            ps.setString(3, discountType);
                            ps.setBigDecimal(4, BigDecimal.valueOf(discountValue));
                            ps.setBigDecimal(5, BigDecimal.valueOf(maxDiscount));
                            ps.setBigDecimal(6, BigDecimal.valueOf(minOrderAmount));
                            ps.setString(7, finalStartAt);
                            ps.setString(8, finalEndAt);
                            ps.setInt(9, usageLimit);
                            ps.setInt(10, perUserLimit);
                            ps.setString(11, status);
                            return ps;
                        }, keyHolder);
                        result.put("id", String.valueOf(Objects.requireNonNull(keyHolder.getKey()).longValue()));
                    }
                }
                case "combos" -> {
                    String productName = String.valueOf(body.get("productName"));
                    String productType = String.valueOf(body.get("productType"));
                    double price = Double.parseDouble(String.valueOf(body.get("price")));
                    String description = body.get("description") != null ? String.valueOf(body.get("description")) : "";
                    String status = String.valueOf(body.get("status"));
                    if (id != null) {
                        jdbcTemplate.update(
                                "UPDATE products SET product_name=?, product_type=?, current_price=?, description=?, status=? WHERE product_id=?",
                                productName, productType, price, description, status, id);
                        result.put("id", String.valueOf(id));
                    } else {
                        KeyHolder keyHolder = new GeneratedKeyHolder();
                        jdbcTemplate.update(conn -> {
                            PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO products (product_name, product_type, current_price, description, status) VALUES (?, ?, ?, ?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                            ps.setString(1, productName);
                            ps.setString(2, productType);
                            ps.setBigDecimal(3, BigDecimal.valueOf(price));
                            ps.setString(4, description);
                            ps.setString(5, status);
                            return ps;
                        }, keyHolder);
                        result.put("id", String.valueOf(Objects.requireNonNull(keyHolder.getKey()).longValue()));
                    }
                }
                case "staff" -> {
                    String fullName = String.valueOf(body.get("fullName"));
                    String email = String.valueOf(body.get("email"));
                    String phone = body.get("phone") != null ? String.valueOf(body.get("phone")) : "";
                    Long cinemaId = body.get("cinemaId") != null
                            && String.valueOf(body.get("cinemaId")).matches("^\\d+$")
                                    ? Long.valueOf(String.valueOf(body.get("cinemaId")))
                                    : null;
                    String role = String.valueOf(body.get("role"));
                    String status = String.valueOf(body.get("status"));
                    if (id != null) {
                        jdbcTemplate.update(
                                "UPDATE users SET full_name=?, email=?, phone=?, cinema_id=?, role=?, account_status=? WHERE user_id=?",
                                fullName, email, phone, cinemaId, role, status, id);
                        result.put("id", String.valueOf(id));
                    } else {
                        String defaultPasswordHash = passwordHashService.encode("Staff@123");
                        KeyHolder keyHolder = new GeneratedKeyHolder();
                        jdbcTemplate.update(conn -> {
                            PreparedStatement ps = conn.prepareStatement(
                                    "INSERT INTO users (full_name, email, phone, cinema_id, role, account_status, password_hash) VALUES (?, ?, ?, ?, ?, ?, ?)",
                                    Statement.RETURN_GENERATED_KEYS);
                            ps.setString(1, fullName);
                            ps.setString(2, email);
                            ps.setString(3, phone);
                            if (cinemaId != null)
                                ps.setLong(4, cinemaId);
                            else
                                ps.setNull(4, java.sql.Types.BIGINT);
                            ps.setString(5, role);
                            ps.setString(6, status);
                            ps.setString(7, defaultPasswordHash);
                            return ps;
                        }, keyHolder);
                        result.put("id", String.valueOf(Objects.requireNonNull(keyHolder.getKey()).longValue()));
                    }
                }
                default -> throw new IllegalArgumentException("Unsupported section: " + section);
            }
            result.put("success", true);
            result.put("message", "Record saved successfully.");
            return ResponseEntity.ok(result);
        } catch (Exception ex) {
            result.put("success", false);
            result.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        }
    }

    @DeleteMapping("/delete/{section}/{id}")
    @Transactional
    public ResponseEntity<Map<String, Object>> deleteRecord(@PathVariable String section, @PathVariable Long id) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            switch (section) {
                case "movies" -> {
                    jdbcTemplate.update("DELETE FROM movie_genres WHERE movie_id=?", id);
                    jdbcTemplate.update("DELETE FROM movies WHERE movie_id=?", id);
                }
                case "genres" -> {
                    jdbcTemplate.update("DELETE FROM movie_genres WHERE genre_id=?", id);
                    jdbcTemplate.update("DELETE FROM genres WHERE genre_id=?", id);
                }
                case "cinemas" -> jdbcTemplate.update("DELETE FROM cinemas WHERE cinema_id=?", id);
                case "rooms" -> {
                    jdbcTemplate.update("DELETE FROM seats WHERE room_id=?", id);
                    jdbcTemplate.update("DELETE FROM rooms WHERE room_id=?", id);
                }
                case "showtimes" -> jdbcTemplate.update("DELETE FROM showtimes WHERE showtime_id=?", id);
                case "pricing" -> jdbcTemplate.update("DELETE FROM seat_types WHERE seat_type_id=?", id);
                case "promotions" -> jdbcTemplate.update("DELETE FROM promotions WHERE promotion_id=?", id);
                case "combos" -> jdbcTemplate.update("DELETE FROM products WHERE product_id=?", id);
                case "staff" -> jdbcTemplate.update("DELETE FROM users WHERE user_id=?", id);
                default -> throw new IllegalArgumentException("Unsupported section: " + section);
            }
            response.put("success", true);
            response.put("message", "Record deleted successfully.");
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            response.put("success", false);
            response.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping("/seats/{roomId}")
    @Transactional
    public ResponseEntity<Map<String, Object>> saveSeats(@PathVariable Long roomId,
            @RequestBody List<Map<String, Object>> seats) {
        Map<String, Object> response = new LinkedHashMap<>();
        try {
            Set<String> activeKeys = new HashSet<>();
            for (Map<String, Object> seat : seats) {
                String row = String.valueOf(seat.get("row")).trim().toUpperCase();
                int number = Integer.parseInt(String.valueOf(seat.get("number")));
                Long typeId = Long.valueOf(String.valueOf(seat.get("typeId")));
                String status = String.valueOf(seat.get("status"));
                String pairId = seat.get("pairId") != null && !String.valueOf(seat.get("pairId")).isBlank()
                        ? String.valueOf(seat.get("pairId")).trim()
                        : null;
                activeKeys.add(row + ":" + number);
                jdbcTemplate.update(
                        "INSERT INTO seats (room_id, seat_type_id, seat_row, seat_number, status, pair_id) VALUES (?, ?, ?, ?, ?, ?) "
                                +
                                "ON DUPLICATE KEY UPDATE seat_type_id = VALUES(seat_type_id), status = VALUES(status), pair_id = VALUES(pair_id)",
                        roomId, typeId, row, number, status, pairId);
            }

            List<Map<String, Object>> existingSeats = jdbcTemplate.queryForList(
                    "SELECT seat_id, seat_row, seat_number FROM seats WHERE room_id = ?", roomId);
            for (Map<String, Object> ex : existingSeats) {
                String key = String.valueOf(ex.get("seat_row")).trim().toUpperCase() + ":" + ex.get("seat_number");
                if (!activeKeys.contains(key)) {
                    Long seatId = ((Number) ex.get("seat_id")).longValue();
                    jdbcTemplate.update(
                            "DELETE FROM booking_seats WHERE showtime_seat_id IN (SELECT showtime_seat_id FROM showtime_seats WHERE seat_id = ?)",
                            seatId);
                    jdbcTemplate.update("DELETE FROM showtime_seats WHERE seat_id = ?", seatId);
                    jdbcTemplate.update("DELETE FROM seats WHERE seat_id = ?", seatId);
                }
            }

            response.put("success", true);
            response.put("message", "Seat layout updated successfully.");
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            response.put("success", false);
            response.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
