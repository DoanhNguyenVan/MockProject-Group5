package com.boxcinema.customer.controller;

import com.boxcinema.common.constant.AppConstants;
import com.boxcinema.common.repository.ShowtimeRepository;
import com.boxcinema.customer.service.SeatBookingContextService;
import com.boxcinema.customer.service.SeatRealtimePushService;
import com.boxcinema.customer.service.impl.SeatBookingContextServiceImpl;
import com.boxcinema.common.util.SecurityContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

/**
 * REST Controller for live screening metadata, seat map availability, and temporary seat holding.
 * All business rules and database queries are delegated to {@link SeatBookingContextService}.
 */
@RestController
public class BookingContextApiController {

    public static final int DEFAULT_HOLD_DURATION_SECONDS = (int) AppConstants.DEFAULT_HOLD_DURATION_SECONDS;
    public static final int MAX_SEATS_PER_BOOKING = AppConstants.MAX_SEATS_PER_BOOKING;

    private final SeatBookingContextService seatBookingContextService;
    private final SeatRealtimePushService seatRealtimePushService;

    @Autowired
    public BookingContextApiController(
            SeatBookingContextService seatBookingContextService,
            @Autowired(required = false) SeatRealtimePushService seatRealtimePushService) {
        this.seatBookingContextService = seatBookingContextService;
        this.seatRealtimePushService = seatRealtimePushService != null ? seatRealtimePushService : new SeatRealtimePushService();
    }

    public BookingContextApiController(ShowtimeRepository showtimes) {
        this(showtimes, null);
    }

    public BookingContextApiController(ShowtimeRepository showtimes, JdbcTemplate jdbcTemplate) {
        SeatRealtimePushService pushService = new SeatRealtimePushService();
        this.seatRealtimePushService = pushService;
        this.seatBookingContextService = new SeatBookingContextServiceImpl(showtimes, jdbcTemplate, pushService);
    }

    @GetMapping(value = "/api/booking/showtimes/{showtimeId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamShowtimeSeats(@PathVariable Long showtimeId) {
        return seatRealtimePushService.subscribe(showtimeId);
    }

    @GetMapping("/api/quick-booking/showtimes/{showtimeId}")
    public ResponseEntity<?> context(@PathVariable Long showtimeId) {
        return seatBookingContextService.getScreeningContext(showtimeId);
    }

    @GetMapping({"/api/booking/showtimes/{showtimeId}", "/api/quick-booking/showtimes/{showtimeId}/data"})
    public ResponseEntity<?> getBookingData(@PathVariable Long showtimeId, HttpServletRequest request) {
        Long callerUserId = SecurityContextUtils.resolveCurrentUserId(request);
        String callerSessionId = resolveHoldSessionId(request, callerUserId, null);
        return seatBookingContextService.getBookingData(showtimeId, callerUserId, callerSessionId);
    }

    @PostMapping("/api/booking/showtimes/{showtimeId}/hold")
    public ResponseEntity<?> holdSeats(
            @PathVariable Long showtimeId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String clientHoldToken = body != null && body.get("holdSessionId") != null ? String.valueOf(body.get("holdSessionId")) : null;
        Long callerUserId = SecurityContextUtils.resolveCurrentUserId(request);
        String callerSessionId = resolveHoldSessionId(request, callerUserId, clientHoldToken);
        return seatBookingContextService.holdSeats(showtimeId, body, callerUserId, callerSessionId);
    }

    @DeleteMapping("/api/booking/showtimes/{showtimeId}/hold")
    public ResponseEntity<?> deleteHeldSeats(
            @PathVariable Long showtimeId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        return releaseSeats(showtimeId, body, request);
    }

    @PostMapping("/api/booking/showtimes/{showtimeId}/release")
    public ResponseEntity<?> releaseSeats(
            @PathVariable Long showtimeId,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String clientHoldToken = body != null && body.get("holdSessionId") != null ? String.valueOf(body.get("holdSessionId")) : null;
        Long callerUserId = SecurityContextUtils.resolveCurrentUserId(request);
        String callerSessionId = resolveHoldSessionId(request, callerUserId, clientHoldToken);
        return seatBookingContextService.releaseSeats(showtimeId, callerUserId, callerSessionId);
    }

    private String resolveHoldSessionId(HttpServletRequest request, Long userId, String clientHoldToken) {
        if (userId != null) {
            return "acct_hold_user_" + userId;
        }
        if (clientHoldToken != null && !clientHoldToken.isBlank()) {
            String trimmed = clientHoldToken.trim();
            if (trimmed.length() <= 96 && !trimmed.startsWith("acct_hold_user_")) {
                return trimmed;
            }
        }
        if (request != null) {
            String headerToken = request.getHeader("X-Hold-Session");
            if (headerToken != null && !headerToken.isBlank()) {
                String trimmed = headerToken.trim();
                if (trimmed.length() <= 96 && !trimmed.startsWith("acct_hold_user_")) {
                    return trimmed;
                }
            }
            HttpSession session = request.getSession(true);
            if (session != null && session.getId() != null && !session.getId().isBlank()) {
                return "guest_http_" + session.getId();
            }
        }
        return "anonymous-session";
    }
}
