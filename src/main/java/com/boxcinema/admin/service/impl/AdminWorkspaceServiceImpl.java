package com.boxcinema.admin.service.impl;

import com.boxcinema.common.repository.AdminWorkspaceJdbcRepository;
import com.boxcinema.admin.service.AdminWorkspaceService;
import com.boxcinema.common.service.PasswordHashService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AdminWorkspaceServiceImpl implements AdminWorkspaceService {

    private final AdminWorkspaceJdbcRepository adminRepository;
    private final PasswordHashService passwordHashService;

    public AdminWorkspaceServiceImpl(AdminWorkspaceJdbcRepository adminRepository,
                                     PasswordHashService passwordHashService) {
        this.adminRepository = adminRepository;
        this.passwordHashService = passwordHashService;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getAdminWorkspaceData() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", 1);
        data.put("movies", adminRepository.findAllMoviesWithGenreIds());
        data.put("genres", adminRepository.findAllGenres());
        data.put("cinemas", adminRepository.findAllCinemas());
        data.put("rooms", adminRepository.findAllRooms());
        data.put("showtimes", adminRepository.findRecentShowtimes());
        data.put("pricing", adminRepository.findAllPricing());
        data.put("promotions", adminRepository.findAllPromotions());
        data.put("combos", adminRepository.findAllCombos());
        data.put("staff", adminRepository.findAllStaff());
        data.put("layouts", adminRepository.findSeatLayoutsByRoom());
        data.put("sales", adminRepository.findRecentSales());
        data.put("bookings", adminRepository.findRecentRichBookings());
        return data;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> checkDeactivationImpact(String section, Long id) {
        List<Map<String, Object>> bookings;
        int upcomingShowtimes;
        if ("cinemas".equalsIgnoreCase(section)) {
            bookings = adminRepository.findImpactedBookings("r.cinema_id", id);
            upcomingShowtimes = adminRepository.countUpcomingActiveShowtimesByCinema(id);
        } else if ("rooms".equalsIgnoreCase(section)) {
            bookings = adminRepository.findImpactedBookings("r.room_id", id);
            upcomingShowtimes = adminRepository.countUpcomingActiveShowtimesByRoom(id);
        } else {
            throw new IllegalArgumentException("Invalid section for impact check");
        }

        int totalTickets = bookings.stream().mapToInt(b -> (int) b.get("ticketCount")).sum();
        double totalAmount = bookings.stream().mapToDouble(b -> (double) b.get("totalAmount")).sum();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("hasImpact", !bookings.isEmpty());
        response.put("totalBookings", bookings.size());
        response.put("totalTickets", totalTickets);
        response.put("totalAmount", totalAmount);
        response.put("upcomingShowtimes", upcomingShowtimes);
        response.put("bookings", bookings);
        return response;
    }

    @Override
    @Transactional
    public Map<String, Object> resolveDeactivation(String section, Long id, String strategy) {
        Map<String, Object> result = new LinkedHashMap<>();
        if ("REFUND_AND_CANCEL".equalsIgnoreCase(strategy)) {
            if ("cinemas".equalsIgnoreCase(section)) {
                List<Long> showtimeIds = adminRepository.findFutureShowtimeIdsByCinema(id);
                adminRepository.refundAndCancelShowtimes(showtimeIds);
                adminRepository.deactivateCinemaAndRooms(id);
                result.put("success", true);
                result.put("message",
                        "Cinema facility deactivated. All auditoriums set to Inactive, future showtimes cancelled, and 100% refund processed automatically for booked customers.");
            } else if ("rooms".equalsIgnoreCase(section)) {
                List<Long> showtimeIds = adminRepository.findFutureShowtimeIdsByRoom(id);
                adminRepository.refundAndCancelShowtimes(showtimeIds);
                adminRepository.deactivateRoom(id);
                result.put("success", true);
                result.put("message",
                        "Auditorium deactivated. Future showtimes cancelled and 100% refund processed automatically for all customers.");
            } else {
                throw new IllegalArgumentException("Invalid section for deactivation");
            }
        } else if ("GRACEFUL_DECOMMISSION".equalsIgnoreCase(strategy)) {
            if ("cinemas".equalsIgnoreCase(section)) {
                adminRepository.gracefulDecommissionCinema(id);
                result.put("success", true);
                result.put("message",
                        "Cinema facility switched to Graceful Decommission mode (STOP_BOOKING). New bookings stopped; showtimes with existing bookings proceed normally.");
            } else if ("rooms".equalsIgnoreCase(section)) {
                adminRepository.gracefulDecommissionRoom(id);
                result.put("success", true);
                result.put("message",
                        "Auditorium switched to Stop Booking mode (STOP_BOOKING). Showtimes with existing bookings will proceed normally.");
            } else {
                throw new IllegalArgumentException("Invalid section for deactivation");
            }
        } else {
            throw new IllegalArgumentException("Unknown deactivation strategy");
        }
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> saveRecord(String section, Map<String, Object> body) {
        String rawId = body.get("id") != null ? String.valueOf(body.get("id")) : null;
        boolean isNumericId = rawId != null && rawId.matches("^\\d+$");
        Long id = isNumericId ? Long.valueOf(rawId) : null;

        Long savedId = switch (section) {
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
                List<?> genreIds = body.get("genreIds") instanceof List<?> list ? list : null;
                yield adminRepository.upsertMovie(id, title, durationMinutes, ageRating, language,
                        releaseDate, endDate, status, synopsis, posterUrl, trailerUrl, genreIds);
            }
            case "genres" -> {
                String genreName = String.valueOf(body.get("genreName"));
                String description = body.get("description") != null ? String.valueOf(body.get("description")) : "";
                yield adminRepository.upsertGenre(id, genreName, description);
            }
            case "cinemas" -> {
                String cinemaName = String.valueOf(body.get("cinemaName"));
                String city = String.valueOf(body.get("city"));
                String address = String.valueOf(body.get("address"));
                String phone = String.valueOf(body.get("phone"));
                String email = String.valueOf(body.get("email"));
                String status = String.valueOf(body.get("status"));
                yield adminRepository.upsertCinema(id, cinemaName, city, address, phone, email, status);
            }
            case "rooms" -> {
                String roomName = String.valueOf(body.get("roomName"));
                Long cinemaId = Long.valueOf(String.valueOf(body.get("cinemaId")));
                String roomType = String.valueOf(body.get("roomType"));
                String status = String.valueOf(body.get("status"));
                yield adminRepository.upsertRoom(id, roomName, cinemaId, roomType, status);
            }
            case "showtimes" -> {
                Long movieId = Long.valueOf(String.valueOf(body.get("movieId")));
                Long roomId = Long.valueOf(String.valueOf(body.get("roomId")));
                String startAt = normalizeDateTime(String.valueOf(body.get("startAt")));
                String endAt = normalizeDateTime(String.valueOf(body.get("endAt")));
                double basePrice = Double.parseDouble(String.valueOf(body.get("basePrice")));
                String status = String.valueOf(body.get("status"));
                yield adminRepository.upsertShowtime(id, movieId, roomId, startAt, endAt, basePrice, status);
            }
            case "pricing" -> {
                String typeName = String.valueOf(body.get("typeName"));
                double priceSurcharge = Double.parseDouble(String.valueOf(body.get("priceSurcharge")));
                String description = body.get("description") != null ? String.valueOf(body.get("description")) : "";
                String shapeType = body.get("shapeType") != null && !String.valueOf(body.get("shapeType")).isBlank()
                        ? String.valueOf(body.get("shapeType")).trim()
                        : "standard";
                yield adminRepository.upsertPricing(id, typeName, priceSurcharge, description, shapeType);
            }
            case "promotions" -> {
                String promotionName = String.valueOf(body.get("promotionName"));
                String voucherCode = String.valueOf(body.get("voucherCode"));
                String discountType = String.valueOf(body.get("discountType"));
                double discountValue = Double.parseDouble(String.valueOf(body.get("discountValue")));
                double maxDiscount = Double.parseDouble(String.valueOf(body.get("maxDiscount")));
                double minOrderAmount = Double.parseDouble(String.valueOf(body.get("minOrderAmount")));
                String startAt = normalizeDateTime(String.valueOf(body.get("startAt")));
                String endAt = normalizeDateTime(String.valueOf(body.get("endAt")));
                int usageLimit = Integer.parseInt(String.valueOf(body.get("usageLimit")));
                int perUserLimit = Integer.parseInt(String.valueOf(body.get("perUserLimit")));
                String status = String.valueOf(body.get("status"));
                yield adminRepository.upsertPromotion(id, promotionName, voucherCode, discountType,
                        discountValue, maxDiscount, minOrderAmount, startAt, endAt, usageLimit, perUserLimit, status);
            }
            case "combos" -> {
                String productName = String.valueOf(body.get("productName"));
                String productType = String.valueOf(body.get("productType"));
                double price = Double.parseDouble(String.valueOf(body.get("price")));
                String description = body.get("description") != null ? String.valueOf(body.get("description")) : "";
                String status = String.valueOf(body.get("status"));
                yield adminRepository.upsertCombo(id, productName, productType, price, description, status);
            }
            case "staff" -> {
                String fullName = String.valueOf(body.get("fullName"));
                String email = String.valueOf(body.get("email"));
                String phone = body.get("phone") != null ? String.valueOf(body.get("phone")) : "";
                Long cinemaId = body.get("cinemaId") != null && String.valueOf(body.get("cinemaId")).matches("^\\d+$")
                        ? Long.valueOf(String.valueOf(body.get("cinemaId")))
                        : null;
                String role = String.valueOf(body.get("role"));
                String status = String.valueOf(body.get("status"));
                String defaultPasswordHash = (id == null) ? passwordHashService.encode("Staff@123") : null;
                yield adminRepository.upsertStaff(id, fullName, email, phone, cinemaId, role, status, defaultPasswordHash);
            }
            default -> throw new IllegalArgumentException("Unsupported section: " + section);
        };

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", String.valueOf(savedId));
        result.put("success", true);
        result.put("message", "Record saved successfully.");
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> deleteRecord(String section, Long id) {
        adminRepository.deleteRecordBySection(section, id);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "Record deleted successfully.");
        return response;
    }

    @Override
    @Transactional
    public Map<String, Object> saveRoomSeats(Long roomId, List<Map<String, Object>> seats) {
        adminRepository.replaceRoomSeats(roomId, seats);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "Seat layout updated successfully.");
        return response;
    }

    private String normalizeDateTime(String raw) {
        String formatted = raw.replace('T', ' ');
        return formatted.length() == 16 ? formatted + ":00" : formatted;
    }
}
