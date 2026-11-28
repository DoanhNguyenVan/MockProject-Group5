package com.boxcinema.customer.controller;

import com.boxcinema.common.constant.SessionConstants;
import com.boxcinema.customer.dto.request.BookingCreateRequestDto;
import com.boxcinema.common.dto.BookingResponseDto;
import com.boxcinema.customer.dto.request.UpdateFoodItemsRequestDto;
import com.boxcinema.common.service.BookingService;
import com.boxcinema.common.util.SecurityContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/bookings")
public class BookingApiController {

    private final BookingService bookingService;

    public BookingApiController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase(Locale.ROOT) : "";
        String errorCode;
        if (msg.contains("when booking status is") || msg.contains("cannot confirm booking")) {
            errorCode = "INVALID_BOOKING_STATE";
        } else if (msg.contains("seat") || msg.contains("booked") || msg.contains("held")) {
            errorCode = "SEAT_CONFLICT";
        } else if (msg.contains("started") || msg.contains("closed")) {
            errorCode = "SHOWTIME_STARTED";
        } else {
            errorCode = "INVALID_BOOKING_STATE";
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "success", false,
                "errorCode", errorCode,
                "message", e.getMessage() != null ? e.getMessage() : "Booking state conflict."
        ));
    }

    @PostMapping
    public ResponseEntity<?> createBooking(
            @RequestBody BookingCreateRequestDto request,
            HttpServletRequest httpRequest) {
        try {
            Long userId = SecurityContextUtils.resolveCurrentUserId(httpRequest);
            BookingResponseDto response = bookingService.createBooking(request, userId);
            if (userId == null && response != null && response.getBookingId() != null && httpRequest != null) {
                HttpSession session = httpRequest.getSession(true);
                Set<Long> guestIds = session.getAttribute(SessionConstants.SESSION_GUEST_BOOKING_IDS) instanceof Set<?> raw
                        ? new LinkedHashSet<>(raw.stream().filter(Long.class::isInstance).map(Long.class::cast).toList())
                        : new LinkedHashSet<>();
                guestIds.add(response.getBookingId());
                session.setAttribute(SessionConstants.SESSION_GUEST_BOOKING_IDS, guestIds);
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalStateException e) {
            return handleIllegalState(e);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                    "success", false,
                    "errorCode", "INVALID_REQUEST",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookingResponseDto> getBooking(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(bookingService.getBookingById(id, userId));
    }

    @PostMapping("/{id}/food-items")
    public ResponseEntity<BookingResponseDto> updateFoodItems(
            @PathVariable Long id,
            @RequestBody UpdateFoodItemsRequestDto request
    ) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(bookingService.updateFoodItems(id, request, userId));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<BookingResponseDto> confirmBooking(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(bookingService.confirmBooking(id, userId));
    }

    @PostMapping("/{id}/voucher")
    public ResponseEntity<BookingResponseDto> applyVoucher(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        String voucherCode = body != null ? body.get("voucherCode") : null;
        return ResponseEntity.ok(bookingService.applyVoucherToBooking(id, voucherCode, userId));
    }
}
