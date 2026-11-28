package com.boxcinema.common.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * JDBC repository dedicated to Admin Workspace queries and mutations.
 */
@Repository
public class AdminWorkspaceJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdminWorkspaceJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> findAllMoviesWithGenreIds() {
        return jdbcTemplate.query(
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
    }

    public List<Map<String, Object>> findAllGenres() {
        return jdbcTemplate.query(
                "SELECT genre_id, genre_name, description FROM genres ORDER BY genre_id ASC",
                (rs, rowNum) -> {
                    Map<String, Object> g = new LinkedHashMap<>();
                    g.put("id", String.valueOf(rs.getLong("genre_id")));
                    g.put("genreName", rs.getString("genre_name"));
                    g.put("description", rs.getString("description") != null ? rs.getString("description") : "");
                    return g;
                });
    }

    public List<Map<String, Object>> findAllCinemas() {
        return jdbcTemplate.query(
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
    }

    public List<Map<String, Object>> findAllRooms() {
        return jdbcTemplate.query(
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
    }

    public List<Map<String, Object>> findRecentShowtimes() {
        return jdbcTemplate.query(
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
                    s.put("basePrice", rs.getBigDecimal("base_price"));
                    s.put("status", rs.getString("status"));
                    return s;
                });
    }

    public List<Map<String, Object>> findAllPricing() {
        return jdbcTemplate.query(
                "SELECT seat_type_id, type_name, price_surcharge, description, shape_type FROM seat_types ORDER BY seat_type_id ASC",
                (rs, rowNum) -> {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("id", String.valueOf(rs.getLong("seat_type_id")));
                    p.put("typeName", rs.getString("type_name"));
                    p.put("priceSurcharge", rs.getBigDecimal("price_surcharge"));
                    p.put("description", rs.getString("description") != null ? rs.getString("description") : "");
                    p.put("shapeType", rs.getString("shape_type") != null ? rs.getString("shape_type") : "standard");
                    return p;
                });
    }

    public List<Map<String, Object>> findAllPromotions() {
        return jdbcTemplate.query(
                "SELECT promotion_id, promotion_name, voucher_code, discount_type, discount_value, max_discount, min_order_amount, "
                        + "start_at, end_at, usage_limit, per_user_limit, status FROM promotions ORDER BY promotion_id DESC",
                (rs, rowNum) -> {
                    Map<String, Object> pr = new LinkedHashMap<>();
                    pr.put("id", String.valueOf(rs.getLong("promotion_id")));
                    pr.put("promotionName", rs.getString("promotion_name"));
                    pr.put("voucherCode", rs.getString("voucher_code") != null ? rs.getString("voucher_code") : "");
                    pr.put("discountType", rs.getString("discount_type"));
                    pr.put("discountValue", rs.getBigDecimal("discount_value"));
                    pr.put("maxDiscount", rs.getBigDecimal("max_discount"));
                    pr.put("minOrderAmount", rs.getBigDecimal("min_order_amount"));
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
                    pr.put("usageLimit", rs.getObject("usage_limit") != null ? rs.getInt("usage_limit") : null);
                    pr.put("perUserLimit", rs.getObject("per_user_limit") != null ? rs.getInt("per_user_limit") : null);
                    pr.put("status", rs.getString("status"));
                    return pr;
                });
    }

    /**
     * Unified F&B catalog from food_combo and food_item (no legacy products split-brain).
     */
    public List<Map<String, Object>> findAllCombos() {
        List<Map<String, Object>> result = new ArrayList<>();
        result.addAll(jdbcTemplate.query(
                "SELECT id, name, price, description, status FROM food_combo ORDER BY id ASC",
                (rs, rowNum) -> {
                    Map<String, Object> cb = new LinkedHashMap<>();
                    cb.put("id", "combo-" + rs.getLong("id"));
                    cb.put("productName", rs.getString("name"));
                    cb.put("productType", "COMBO");
                    cb.put("price", rs.getBigDecimal("price"));
                    cb.put("description", rs.getString("description") != null ? rs.getString("description") : "");
                    cb.put("status", rs.getString("status"));
                    return cb;
                }));
        result.addAll(jdbcTemplate.query(
                "SELECT fi.id, fi.name, fi.price, fi.description, fi.status, COALESCE(fc.name, '') AS cat_name " +
                        "FROM food_item fi LEFT JOIN food_category fc ON fi.category_id = fc.id ORDER BY fi.id ASC",
                (rs, rowNum) -> {
                    String catName = rs.getString("cat_name");
                    String productType = (catName != null && (catName.toLowerCase().contains("nước")
                            || catName.toLowerCase().contains("drink")
                            || catName.toLowerCase().contains("beverage"))) ? "DRINK" : "FOOD";
                    Map<String, Object> cb = new LinkedHashMap<>();
                    cb.put("id", "item-" + rs.getLong("id"));
                    cb.put("productName", rs.getString("name"));
                    cb.put("productType", productType);
                    cb.put("price", rs.getBigDecimal("price"));
                    cb.put("description", rs.getString("description") != null ? rs.getString("description") : "");
                    cb.put("status", rs.getString("status"));
                    return cb;
                }));
        return result;
    }

    public List<Map<String, Object>> findAllStaff() {
        return jdbcTemplate.query(
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
    }

    public Map<String, List<Map<String, Object>>> findSeatLayoutsByRoom() {
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
        return layouts;
    }

    /**
     * Honest sales report: only financially settled or refunded bookings (never DRAFT/HOLDING/EXPIRED/PENDING_PAYMENT).
     */
    public List<Map<String, Object>> findRecentSales() {
        return jdbcTemplate.query(
                "SELECT b.booking_id, b.booking_code, DATE_FORMAT(b.booked_at, '%Y-%m-%d') as sale_date, " +
                        "s.movie_id, COALESCE(b.cinema_id, r.cinema_id) AS cinema_id, " +
                        "(SELECT COUNT(*) FROM booking_seats bs WHERE bs.booking_id = b.booking_id) as seat_cnt, " +
                        "b.total_amount, b.booking_status " +
                        "FROM bookings b " +
                        "LEFT JOIN showtimes s ON b.showtime_id = s.showtime_id " +
                        "LEFT JOIN rooms r ON s.room_id = r.room_id " +
                        "WHERE b.booking_status IN ('CONFIRMED', 'PAID', 'REFUNDED', 'PARTIALLY_REFUNDED', 'PAID_SEAT_CONFLICT', 'CANCELLED') " +
                        "ORDER BY b.booked_at DESC LIMIT 500",
                (rs, rowNum) -> {
                    Map<String, Object> sl = new LinkedHashMap<>();
                    sl.put("id", rs.getString("booking_code"));
                    sl.put("date", rs.getString("sale_date"));
                    Object movieIdObj = rs.getObject("movie_id");
                    sl.put("movieId", movieIdObj != null ? String.valueOf(((Number) movieIdObj).longValue()) : "");
                    Object cinemaIdObj = rs.getObject("cinema_id");
                    sl.put("cinemaId", cinemaIdObj != null ? String.valueOf(((Number) cinemaIdObj).longValue()) : "");
                    sl.put("tickets", rs.getInt("seat_cnt"));
                    String st = rs.getString("booking_status");
                    BigDecimal totalAmt = rs.getBigDecimal("total_amount");
                    sl.put("revenue", ("CANCELLED".equalsIgnoreCase(st) || "REFUNDED".equalsIgnoreCase(st))
                            ? BigDecimal.ZERO
                            : (totalAmt != null ? totalAmt : BigDecimal.ZERO));
                    sl.put("status", st);
                    return sl;
                });
    }

    /**
     * Honest booking report: reads actual booking_seats, booking_food_item, and latest payment without fake fallbacks.
     */
    public List<Map<String, Object>> findRecentRichBookings() {
        return jdbcTemplate.query(
                "SELECT b.booking_id, b.booking_code, b.booking_status, " +
                        "DATE_FORMAT(b.booked_at, '%Y-%m-%d %H:%i') as booked_at_formatted, " +
                        "b.subtotal, b.discount_amount, b.total_amount, b.booking_channel, " +
                        "COALESCE(b.guest_name, u.full_name, '') as customer_name, " +
                        "COALESCE(b.guest_email, u.email, '') as customer_email, " +
                        "COALESCE(b.guest_phone, u.phone, '') as customer_phone, " +
                        "s.showtime_id, DATE_FORMAT(s.start_at, '%Y-%m-%d %H:%i') as showtime_start, " +
                        "s.base_price, m.movie_id, m.title as movie_title, m.poster_url, m.age_rating, " +
                        "r.room_id, r.room_name, r.room_type, " +
                        "c.cinema_id, c.cinema_name, c.city, " +
                        "(SELECT p2.payment_provider FROM payments p2 WHERE p2.booking_id = b.booking_id ORDER BY p2.payment_id DESC LIMIT 1) AS payment_provider, " +
                        "(SELECT p2.payment_method FROM payments p2 WHERE p2.booking_id = b.booking_id ORDER BY p2.payment_id DESC LIMIT 1) AS payment_method, " +
                        "(SELECT p2.payment_status FROM payments p2 WHERE p2.booking_id = b.booking_id ORDER BY p2.payment_id DESC LIMIT 1) AS payment_status, " +
                        "pr.promotion_name, b.voucher_code_snapshot, " +
                        "(SELECT GROUP_CONCAT(CONCAT(bs.seat_label_snapshot, ' (', st.type_name, ' - ', FORMAT(bs.unit_price, 0), ' VND)') SEPARATOR ', ') " +
                        " FROM booking_seats bs " +
                        " JOIN showtime_seats ss ON bs.showtime_seat_id = ss.showtime_seat_id " +
                        " JOIN seats seat ON ss.seat_id = seat.seat_id " +
                        " JOIN seat_types st ON seat.seat_type_id = st.seat_type_id " +
                        " WHERE bs.booking_id = b.booking_id) as seats_detail, " +
                        "(SELECT GROUP_CONCAT(bs.seat_label_snapshot SEPARATOR ', ') " +
                        " FROM booking_seats bs WHERE bs.booking_id = b.booking_id) as seats_summary, " +
                        "(SELECT COUNT(*) FROM booking_seats bs WHERE bs.booking_id = b.booking_id) as seat_cnt, " +
                        "(SELECT GROUP_CONCAT(CONCAT(bfi.product_name_snapshot, ' x', bfi.quantity, ' (', FORMAT(bfi.subtotal, 0), ' VND)') SEPARATOR ', ') " +
                        " FROM booking_food_item bfi WHERE bfi.booking_id = b.booking_id) as combos_detail " +
                        "FROM bookings b " +
                        "LEFT JOIN users u ON b.user_id = u.user_id " +
                        "LEFT JOIN showtimes s ON b.showtime_id = s.showtime_id " +
                        "LEFT JOIN movies m ON s.movie_id = m.movie_id " +
                        "LEFT JOIN rooms r ON s.room_id = r.room_id " +
                        "LEFT JOIN cinemas c ON COALESCE(b.cinema_id, r.cinema_id) = c.cinema_id " +
                        "LEFT JOIN promotions pr ON b.promotion_id = pr.promotion_id " +
                        "ORDER BY b.booked_at DESC, b.booking_id DESC LIMIT 150",
                (rs, rowNum) -> {
                    Map<String, Object> bk = new LinkedHashMap<>();
                    bk.put("id", String.valueOf(rs.getLong("booking_id")));
                    bk.put("bookingCode", rs.getString("booking_code"));
                    bk.put("bookingStatus", rs.getString("booking_status"));
                    bk.put("bookedAt", rs.getString("booked_at_formatted"));
                    bk.put("subtotal", rs.getBigDecimal("subtotal"));
                    bk.put("discountAmount", rs.getBigDecimal("discount_amount"));
                    bk.put("totalAmount", rs.getBigDecimal("total_amount"));
                    bk.put("bookingChannel",
                            rs.getString("booking_channel") != null ? rs.getString("booking_channel") : "ONLINE");
                    bk.put("customerName", rs.getString("customer_name"));
                    bk.put("customerEmail", rs.getString("customer_email"));
                    bk.put("customerPhone", rs.getString("customer_phone"));
                    Object stId = rs.getObject("showtime_id");
                    bk.put("showtimeId", stId != null ? String.valueOf(((Number) stId).longValue()) : "");
                    bk.put("showtimeStart", rs.getString("showtime_start") != null ? rs.getString("showtime_start") : "");
                    bk.put("basePrice", rs.getBigDecimal("base_price"));
                    Object mvId = rs.getObject("movie_id");
                    bk.put("movieId", mvId != null ? String.valueOf(((Number) mvId).longValue()) : "");
                    bk.put("movieTitle", rs.getString("movie_title") != null ? rs.getString("movie_title") : "Food & Beverage Order");
                    bk.put("posterUrl", rs.getString("poster_url") != null ? rs.getString("poster_url") : "");
                    bk.put("ageRating", rs.getString("age_rating") != null ? rs.getString("age_rating") : "");
                    Object rmId = rs.getObject("room_id");
                    bk.put("roomId", rmId != null ? String.valueOf(((Number) rmId).longValue()) : "");
                    bk.put("roomName", rs.getString("room_name") != null ? rs.getString("room_name") : "");
                    bk.put("roomType", rs.getString("room_type") != null ? rs.getString("room_type") : "");
                    Object cmId = rs.getObject("cinema_id");
                    bk.put("cinemaId", cmId != null ? String.valueOf(((Number) cmId).longValue()) : "");
                    bk.put("cinemaName", rs.getString("cinema_name") != null ? rs.getString("cinema_name") : "");
                    bk.put("city", rs.getString("city") != null ? rs.getString("city") : "");
                    bk.put("paymentProvider", rs.getString("payment_provider") != null ? rs.getString("payment_provider") : "");
                    bk.put("paymentMethod", rs.getString("payment_method") != null ? rs.getString("payment_method") : "");
                    bk.put("paymentStatus", rs.getString("payment_status") != null ? rs.getString("payment_status") : "UNPAID");
                    bk.put("promotionName", rs.getString("promotion_name") != null ? rs.getString("promotion_name") : "");
                    bk.put("voucherCode", rs.getString("voucher_code_snapshot") != null ? rs.getString("voucher_code_snapshot") : "");

                    bk.put("ticketCount", rs.getInt("seat_cnt"));
                    String seatsSummary = rs.getString("seats_summary");
                    bk.put("seatsSummary", seatsSummary != null ? seatsSummary : "");
                    String seatsDetail = rs.getString("seats_detail");
                    bk.put("seatsDetail", seatsDetail != null ? seatsDetail : "");
                    String combosDetail = rs.getString("combos_detail");
                    bk.put("combosDetail", combosDetail != null ? combosDetail : "");
                    return bk;
                });
    }

    public List<Map<String, Object>> findImpactedBookings(String filterColumn, Long id) {
        if (!"r.cinema_id".equals(filterColumn) && !"r.room_id".equals(filterColumn)) {
            throw new IllegalArgumentException("Invalid impact filter column.");
        }
        String sql = "SELECT b.booking_id, b.booking_code, b.total_amount, " +
                "COALESCE(b.guest_name, u.full_name, 'Customer') AS customer_name, " +
                "COALESCE(b.guest_email, u.email, '') AS customer_email, " +
                "COALESCE(b.guest_phone, u.phone, '') AS customer_phone, " +
                "s.showtime_id, s.start_at, m.title AS movie_title, r.room_name, " +
                "(SELECT COUNT(*) FROM booking_seats bs WHERE bs.booking_id = b.booking_id) AS ticket_count " +
                "FROM bookings b " +
                "LEFT JOIN users u ON b.user_id = u.user_id " +
                "JOIN showtimes s ON b.showtime_id = s.showtime_id " +
                "JOIN rooms r ON s.room_id = r.room_id " +
                "JOIN movies m ON s.movie_id = m.movie_id " +
                "WHERE " + filterColumn + " = ? " +
                "AND s.start_at >= NOW() " +
                "AND b.booking_status IN ('CONFIRMED', 'PAID', 'HOLDING', 'PENDING_PAYMENT') " +
                "ORDER BY s.start_at ASC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
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
            item.put("ticketCount", rs.getInt("ticket_count"));
            item.put("totalAmount", rs.getBigDecimal("total_amount"));
            return item;
        }, id);
    }

    public int countUpcomingActiveShowtimesByCinema(Long cinemaId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM showtimes s JOIN rooms r ON s.room_id = r.room_id WHERE r.cinema_id = ? AND s.start_at >= NOW() AND s.status = 'ACTIVE'",
                Integer.class, cinemaId);
        return count != null ? count : 0;
    }

    public int countUpcomingActiveShowtimesByRoom(Long roomId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM showtimes WHERE room_id = ? AND start_at >= NOW() AND status = 'ACTIVE'",
                Integer.class, roomId);
        return count != null ? count : 0;
    }

    public List<Long> findFutureShowtimeIdsByCinema(Long cinemaId) {
        return jdbcTemplate.queryForList(
                "SELECT s.showtime_id FROM showtimes s JOIN rooms r ON s.room_id = r.room_id WHERE r.cinema_id = ? AND s.start_at >= NOW()",
                Long.class, cinemaId);
    }

    public List<Long> findFutureShowtimeIdsByRoom(Long roomId) {
        return jdbcTemplate.queryForList(
                "SELECT showtime_id FROM showtimes WHERE room_id = ? AND start_at >= NOW()",
                Long.class, roomId);
    }

    public List<Long> findBookingsOnShowtimes(List<Long> showtimeIds) {
        if (showtimeIds == null || showtimeIds.isEmpty()) {
            return Collections.emptyList();
        }
        String inSql = String.join(",", Collections.nCopies(showtimeIds.size(), "?"));
        return jdbcTemplate.queryForList(
                "SELECT booking_id FROM bookings WHERE showtime_id IN (" + inSql + ") " +
                        "AND booking_status IN ('DRAFT', 'HOLDING', 'PENDING_PAYMENT', 'CONFIRMED', 'PAID') " +
                        "ORDER BY booking_id ASC",
                Long.class,
                showtimeIds.toArray());
    }

    public void cancelShowtimesAndReleaseEmptySeats(List<Long> showtimeIds) {
        if (showtimeIds == null || showtimeIds.isEmpty()) {
            return;
        }
        String inSql = String.join(",", Collections.nCopies(showtimeIds.size(), "?"));
        Object[] args = showtimeIds.toArray();
        jdbcTemplate.update(
                "UPDATE showtimes SET status = 'CANCELLED' WHERE showtime_id IN (" + inSql + ")",
                args);
        jdbcTemplate.update(
                "UPDATE showtime_seats SET seat_status = 'AVAILABLE', hold_token_hash = NULL, held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL " +
                        "WHERE showtime_id IN (" + inSql + ") AND seat_status IN ('AVAILABLE', 'HELD')",
                args);
    }

    public void deactivateCinemaAndRooms(Long cinemaId) {
        jdbcTemplate.update("UPDATE rooms SET status = 'INACTIVE' WHERE cinema_id = ?", cinemaId);
        jdbcTemplate.update("UPDATE cinemas SET status = 'INACTIVE' WHERE cinema_id = ?", cinemaId);
    }

    public void deactivateRoom(Long roomId) {
        jdbcTemplate.update("UPDATE rooms SET status = 'INACTIVE' WHERE room_id = ?", roomId);
    }

    public void gracefulDecommissionCinema(Long cinemaId) {
        jdbcTemplate.update(
                "UPDATE showtimes SET status = 'CANCELLED' WHERE room_id IN (SELECT room_id FROM rooms WHERE cinema_id = ?) " +
                        "AND start_at >= NOW() AND status = 'ACTIVE' " +
                        "AND showtime_id NOT IN (SELECT showtime_id FROM bookings WHERE booking_status IN ('CONFIRMED', 'PAID', 'PENDING_PAYMENT'))",
                cinemaId);
        jdbcTemplate.update("UPDATE cinemas SET status = 'STOP_BOOKING' WHERE cinema_id = ?", cinemaId);
        jdbcTemplate.update(
                "UPDATE rooms SET status = 'STOP_BOOKING' WHERE cinema_id = ? AND room_id IN (SELECT room_id FROM showtimes WHERE start_at >= NOW() AND status = 'ACTIVE')",
                cinemaId);
        jdbcTemplate.update(
                "UPDATE rooms SET status = 'INACTIVE' WHERE cinema_id = ? AND room_id NOT IN (SELECT room_id FROM showtimes WHERE start_at >= NOW() AND status = 'ACTIVE')",
                cinemaId);
    }

    public void gracefulDecommissionRoom(Long roomId) {
        jdbcTemplate.update(
                "UPDATE showtimes SET status = 'CANCELLED' WHERE room_id = ? AND start_at >= NOW() AND status = 'ACTIVE' " +
                        "AND showtime_id NOT IN (SELECT showtime_id FROM bookings WHERE booking_status IN ('CONFIRMED', 'PAID', 'PENDING_PAYMENT'))",
                roomId);
        jdbcTemplate.update("UPDATE rooms SET status = 'STOP_BOOKING' WHERE room_id = ?", roomId);
    }

    public boolean hasOverlappingActiveShowtime(Long roomId, String startAt, String endAt, Long excludeShowtimeId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM showtimes WHERE room_id = ? AND status = 'ACTIVE' " +
                        "AND (? IS NULL OR showtime_id <> ?) AND start_at < ? AND end_at > ?",
                Integer.class, roomId, excludeShowtimeId, excludeShowtimeId, endAt, startAt);
        return count != null && count > 0;
    }

    public Map<String, Object> findMovieScheduleInfo(Long movieId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT movie_id, status, duration_minutes FROM movies WHERE movie_id = ?", movieId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findRoomScheduleInfo(Long roomId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT r.room_id, r.status AS room_status, c.status AS cinema_status " +
                        "FROM rooms r JOIN cinemas c ON r.cinema_id = c.cinema_id WHERE r.room_id = ?", roomId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Long upsertMovie(Long id, String title, int durationMinutes, String ageRating, String language,
                            String releaseDate, String endDate, String status, String synopsis,
                            String posterUrl, String trailerUrl, List<?> genreIds) {
        Long movieId = id;
        if (movieId != null) {
            jdbcTemplate.update(
                    "UPDATE movies SET title=?, duration_minutes=?, age_rating=?, language=?, release_date=?, end_date=?, status=?, synopsis=?, poster_url=?, trailer_url=? WHERE movie_id=?",
                    title, durationMinutes, ageRating, language, releaseDate, endDate, status, synopsis,
                    posterUrl, trailerUrl, movieId);
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
            movieId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        }

        if (genreIds != null) {
            jdbcTemplate.update("DELETE FROM movie_genres WHERE movie_id=?", movieId);
            for (Object gIdObj : genreIds) {
                String gIdStr = String.valueOf(gIdObj);
                if (gIdStr.matches("^\\d+$")) {
                    jdbcTemplate.update(
                            "INSERT IGNORE INTO movie_genres (movie_id, genre_id) VALUES (?, ?)", movieId,
                            Long.valueOf(gIdStr));
                }
            }
        }
        return movieId;
    }

    public Long upsertGenre(Long id, String genreName, String description) {
        if (id != null) {
            jdbcTemplate.update("UPDATE genres SET genre_name=?, description=? WHERE genre_id=?", genreName,
                    description, id);
            return id;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO genres (genre_name, description) VALUES (?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, genreName);
            ps.setString(2, description);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public Long upsertCinema(Long id, String cinemaName, String city, String address, String phone, String email, String status) {
        if (id != null) {
            jdbcTemplate.update(
                    "UPDATE cinemas SET cinema_name=?, city=?, address=?, phone=?, email=?, status=? WHERE cinema_id=?",
                    cinemaName, city, address, phone, email, status, id);
            if ("INACTIVE".equalsIgnoreCase(status)) {
                jdbcTemplate.update("UPDATE rooms SET status='INACTIVE' WHERE cinema_id=?", id);
                jdbcTemplate.update(
                        "UPDATE showtimes SET status='CANCELLED' WHERE room_id IN (SELECT room_id FROM rooms WHERE cinema_id=?) " +
                                "AND start_at >= NOW() AND status='ACTIVE' " +
                                "AND showtime_id NOT IN (SELECT showtime_id FROM bookings WHERE booking_status IN ('CONFIRMED', 'PAID', 'PENDING_PAYMENT'))",
                        id);
            }
            return id;
        }
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
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public Long upsertRoom(Long id, String roomName, Long cinemaId, String roomType, String status) {
        if (id != null) {
            jdbcTemplate.update(
                    "UPDATE rooms SET room_name=?, cinema_id=?, room_type=?, status=? WHERE room_id=?",
                    roomName, cinemaId, roomType, status, id);
            if ("INACTIVE".equalsIgnoreCase(status)) {
                jdbcTemplate.update("UPDATE showtimes SET status='CANCELLED' WHERE room_id=? " +
                                "AND start_at >= NOW() AND status='ACTIVE' " +
                                "AND showtime_id NOT IN (SELECT showtime_id FROM bookings WHERE booking_status IN ('CONFIRMED', 'PAID', 'PENDING_PAYMENT'))",
                        id);
            }
            return id;
        }
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
        return newRoomId;
    }

    public Long upsertShowtime(Long id, Long movieId, Long roomId, String startAt, String endAt, BigDecimal basePrice, String status) {
        if (id != null) {
            jdbcTemplate.update(
                    "UPDATE showtimes SET movie_id=?, room_id=?, start_at=?, end_at=?, base_price=?, status=? WHERE showtime_id=?",
                    movieId, roomId, startAt, endAt, basePrice, status, id);
            return id;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO showtimes (movie_id, room_id, start_at, end_at, base_price, status) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, movieId);
            ps.setLong(2, roomId);
            ps.setString(3, startAt);
            ps.setString(4, endAt);
            ps.setBigDecimal(5, basePrice);
            ps.setString(6, status);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public Long upsertPricing(Long id, String typeName, BigDecimal priceSurcharge, String description, String shapeType) {
        if (id != null) {
            jdbcTemplate.update(
                    "UPDATE seat_types SET type_name=?, price_surcharge=?, description=?, shape_type=? WHERE seat_type_id=?",
                    typeName, priceSurcharge, description, shapeType, id);
            return id;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO seat_types (type_name, price_surcharge, description, shape_type) VALUES (?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, typeName);
            ps.setBigDecimal(2, priceSurcharge);
            ps.setString(3, description);
            ps.setString(4, shapeType);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    public Long upsertPromotion(Long id, String promotionName, String voucherCode, String discountType,
                                BigDecimal discountValue, BigDecimal maxDiscount, BigDecimal minOrderAmount,
                                String startAt, String endAt, int usageLimit, int perUserLimit, String status) {
        if (id != null) {
            jdbcTemplate.update(
                    "UPDATE promotions SET promotion_name=?, voucher_code=?, discount_type=?, discount_value=?, max_discount=?, min_order_amount=?, start_at=?, end_at=?, usage_limit=?, per_user_limit=?, status=? WHERE promotion_id=?",
                    promotionName, voucherCode, discountType, discountValue, maxDiscount, minOrderAmount,
                    startAt, endAt, usageLimit, perUserLimit, status, id);
            return id;
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO promotions (promotion_name, voucher_code, discount_type, discount_value, max_discount, min_order_amount, start_at, end_at, usage_limit, per_user_limit, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, promotionName);
            ps.setString(2, voucherCode);
            ps.setString(3, discountType);
            ps.setBigDecimal(4, discountValue);
            ps.setBigDecimal(5, maxDiscount);
            ps.setBigDecimal(6, minOrderAmount);
            ps.setString(7, startAt);
            ps.setString(8, endAt);
            ps.setInt(9, usageLimit);
            ps.setInt(10, perUserLimit);
            ps.setString(11, status);
            return ps;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * Unified F&B catalog upsert onto food_combo / food_item (+ food_inventory).
     */
    public String upsertUnifiedCombo(String rawId, String productName, String productType,
                                     BigDecimal price, String description, String status) {
        boolean isCombo = "COMBO".equalsIgnoreCase(productType);
        boolean active = "ACTIVE".equalsIgnoreCase(status);
        if (rawId != null && rawId.startsWith("combo-")) {
            Long id = Long.valueOf(rawId.substring("combo-".length()));
            jdbcTemplate.update(
                    "UPDATE food_combo SET name=?, price=?, description=?, status=?, is_available=? WHERE id=?",
                    productName, price, description, status, active, id);
            return rawId;
        }
        if (rawId != null && rawId.startsWith("item-")) {
            Long id = Long.valueOf(rawId.substring("item-".length()));
            jdbcTemplate.update(
                    "UPDATE food_item SET name=?, price=?, description=?, status=?, is_available=? WHERE id=?",
                    productName, price, description, status, active, id);
            return rawId;
        }
        if (rawId != null && rawId.matches("^\\d+$")) {
            Long id = Long.valueOf(rawId);
            if (isCombo) {
                int updated = jdbcTemplate.update(
                        "UPDATE food_combo SET name=?, price=?, description=?, status=?, is_available=? WHERE id=?",
                        productName, price, description, status, active, id);
                if (updated > 0) return "combo-" + id;
            } else {
                int updated = jdbcTemplate.update(
                        "UPDATE food_item SET name=?, price=?, description=?, status=?, is_available=? WHERE id=?",
                        productName, price, description, status, active, id);
                if (updated > 0) return "item-" + id;
            }
        }

        if (isCombo) {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(conn -> {
                PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO food_combo (name, description, price, status, is_available) VALUES (?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, productName);
                ps.setString(2, description);
                ps.setBigDecimal(3, price);
                ps.setString(4, status);
                ps.setBoolean(5, active);
                return ps;
            }, keyHolder);
            return "combo-" + Objects.requireNonNull(keyHolder.getKey()).longValue();
        }

        long categoryId = resolveFoodCategoryId(productType);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(conn -> {
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO food_item (category_id, name, description, price, status, is_available) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, categoryId);
            ps.setString(2, productName);
            ps.setString(3, description);
            ps.setBigDecimal(4, price);
            ps.setString(5, status);
            ps.setBoolean(6, active);
            return ps;
        }, keyHolder);
        long itemId = Objects.requireNonNull(keyHolder.getKey()).longValue();
        jdbcTemplate.update(
                "INSERT IGNORE INTO food_inventory (food_item_id, on_hand_quantity, reserved_quantity) VALUES (?, 100, 0)",
                itemId);
        return "item-" + itemId;
    }

    private long resolveFoodCategoryId(String productType) {
        String pattern = "DRINK".equalsIgnoreCase(productType) ? "%Nước%" : "%Bắp%";
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM food_category WHERE name LIKE ? ORDER BY id ASC LIMIT 1",
                Long.class, pattern);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        List<Long> any = jdbcTemplate.queryForList("SELECT id FROM food_category ORDER BY id ASC LIMIT 1", Long.class);
        if (!any.isEmpty()) {
            return any.get(0);
        }
        throw new IllegalStateException("No food_category rows exist to create a food item.");
    }

    /**
     * Soft-deactivates records with dependency checks instead of destructive deletes.
     */
    public void safeDeleteOrDeactivate(String section, String rawId) {
        if ("combos".equals(section)) {
            if (rawId.startsWith("combo-")) {
                Long id = Long.valueOf(rawId.substring("combo-".length()));
                jdbcTemplate.update("UPDATE food_combo SET status='INACTIVE', is_available=0 WHERE id=?", id);
                return;
            }
            if (rawId.startsWith("item-")) {
                Long id = Long.valueOf(rawId.substring("item-".length()));
                jdbcTemplate.update("UPDATE food_item SET status='INACTIVE', is_available=0 WHERE id=?", id);
                return;
            }
        }
        Long id = Long.valueOf(rawId);
        switch (section) {
            case "movies" -> {
                Integer activeShowtimes = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM showtimes WHERE movie_id=? AND status='ACTIVE' AND start_at >= NOW()",
                        Integer.class, id);
                if (activeShowtimes != null && activeShowtimes > 0) {
                    throw new IllegalStateException("Cannot deactivate movie while upcoming active showtimes exist.");
                }
                jdbcTemplate.update("UPDATE movies SET status='INACTIVE' WHERE movie_id=?", id);
            }
            case "genres" -> {
                Integer linkedMovies = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM movie_genres WHERE genre_id=?", Integer.class, id);
                if (linkedMovies != null && linkedMovies > 0) {
                    throw new IllegalStateException("Cannot delete genre while movies are assigned to it.");
                }
                jdbcTemplate.update("DELETE FROM genres WHERE genre_id=?", id);
            }
            case "cinemas" -> {
                if (countUpcomingActiveShowtimesByCinema(id) > 0) {
                    throw new IllegalStateException("Cannot deactivate cinema with upcoming active showtimes. Use Deactivation Impact workflow.");
                }
                deactivateCinemaAndRooms(id);
            }
            case "rooms" -> {
                if (countUpcomingActiveShowtimesByRoom(id) > 0) {
                    throw new IllegalStateException("Cannot deactivate room with upcoming active showtimes. Use Deactivation Impact workflow.");
                }
                deactivateRoom(id);
            }
            case "showtimes" -> {
                Integer activeBookings = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM bookings WHERE showtime_id=? AND booking_status IN ('CONFIRMED','PAID','PENDING_PAYMENT','HOLDING')",
                        Integer.class, id);
                if (activeBookings != null && activeBookings > 0) {
                    throw new IllegalStateException("Cannot cancel showtime with active or paid bookings.");
                }
                jdbcTemplate.update("UPDATE showtimes SET status='CANCELLED' WHERE showtime_id=?", id);
            }
            case "pricing" -> {
                Integer usedBySeats = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM seats WHERE seat_type_id=?", Integer.class, id);
                if (usedBySeats != null && usedBySeats > 0) {
                    throw new IllegalStateException("Cannot delete seat pricing type while seats reference it.");
                }
                jdbcTemplate.update("DELETE FROM seat_types WHERE seat_type_id=?", id);
            }
            case "promotions" -> jdbcTemplate.update("UPDATE promotions SET status='INACTIVE' WHERE promotion_id=?", id);
            case "combos" -> {
                int cUpd = jdbcTemplate.update("UPDATE food_combo SET status='INACTIVE', is_available=0 WHERE id=?", id);
                if (cUpd == 0) {
                    jdbcTemplate.update("UPDATE food_item SET status='INACTIVE', is_available=0 WHERE id=?", id);
                }
            }
            default -> throw new IllegalArgumentException("Unsupported section: " + section);
        }
    }

    /**
     * Never deletes historical booking_seats; soft-disables referenced seats removed from layout.
     */
    public void replaceRoomSeats(Long roomId, List<Map<String, Object>> seats) {
        Set<String> activeKeys = new HashSet<>();
        for (Map<String, Object> seat : seats) {
            String row = String.valueOf(seat.get("row")).trim().toUpperCase();
            int number = Integer.parseInt(String.valueOf(seat.get("number")));
            Long typeId = Long.valueOf(String.valueOf(seat.get("typeId")));
            String status = String.valueOf(seat.get("status")).trim().toUpperCase(Locale.ROOT);
            if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status) && !"UNAVAILABLE".equals(status)) {
                throw new IllegalArgumentException("Invalid seat status: " + status);
            }
            String pairId = seat.get("pairId") != null && !String.valueOf(seat.get("pairId")).isBlank()
                    ? String.valueOf(seat.get("pairId")).trim()
                    : null;
            activeKeys.add(row + ":" + number);
            jdbcTemplate.update(
                    "INSERT INTO seats (room_id, seat_type_id, seat_row, seat_number, status, pair_id) VALUES (?, ?, ?, ?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE seat_type_id = VALUES(seat_type_id), status = VALUES(status), pair_id = VALUES(pair_id)",
                    roomId, typeId, row, number, status, pairId);
        }

        List<Map<String, Object>> existingSeats = jdbcTemplate.queryForList(
                "SELECT seat_id, seat_row, seat_number FROM seats WHERE room_id = ?", roomId);
        for (Map<String, Object> ex : existingSeats) {
            String key = String.valueOf(ex.get("seat_row")).trim().toUpperCase() + ":" + ex.get("seat_number");
            if (!activeKeys.contains(key)) {
                Long seatId = ((Number) ex.get("seat_id")).longValue();
                Integer bookingRefs = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM booking_seats bs JOIN showtime_seats ss ON bs.showtime_seat_id = ss.showtime_seat_id WHERE ss.seat_id = ?",
                        Integer.class, seatId);
                Integer showtimeRefs = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM showtime_seats WHERE seat_id = ?", Integer.class, seatId);
                if ((bookingRefs != null && bookingRefs > 0) || (showtimeRefs != null && showtimeRefs > 0)) {
                    jdbcTemplate.update("UPDATE seats SET status = 'INACTIVE' WHERE seat_id = ?", seatId);
                } else {
                    jdbcTemplate.update("DELETE FROM seats WHERE seat_id = ?", seatId);
                }
            }
        }
    }
}
