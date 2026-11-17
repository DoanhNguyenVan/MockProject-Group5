package com.boxcinema.controller;

import com.boxcinema.dto.BookingCreateRequestDto;
import com.boxcinema.dto.BookingResponseDto;
import com.boxcinema.dto.UpdateFoodItemsRequestDto;
import com.boxcinema.security.BoxCinemaUserDetails;
import com.boxcinema.service.BookingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/bookings")
public class BookingApiController {

    private final BookingService bookingService;

    public BookingApiController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    private Long resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof BoxCinemaUserDetails details) {
            if (details.getUserSummary() != null && details.getUserSummary().getUserId() != null) {
                return details.getUserSummary().getUserId();
            }
        }
        return null;
    }

    @PostMapping
    public ResponseEntity<?> createBooking(@RequestBody BookingCreateRequestDto request) {
        try {
            Long userId = resolveCurrentUserId();
            BookingResponseDto response = bookingService.createBooking(request, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(java.util.Map.of(
                    "success", false,
                    "errorCode", "SHOWTIME_STARTED",
                    "message", e.getMessage()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(java.util.Map.of(
                    "success", false,
                    "errorCode", "INVALID_REQUEST",
                    "message", e.getMessage()
            ));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<BookingResponseDto> getBooking(@PathVariable Long id) {
        return ResponseEntity.ok(bookingService.getBookingById(id));
    }

    @PostMapping("/{id}/food-items")
    public ResponseEntity<BookingResponseDto> updateFoodItems(
            @PathVariable Long id,
            @RequestBody UpdateFoodItemsRequestDto request
    ) {
        Long userId = resolveCurrentUserId();
        return ResponseEntity.ok(bookingService.updateFoodItems(id, request, userId));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<BookingResponseDto> confirmBooking(@PathVariable Long id) {
        Long userId = resolveCurrentUserId();
        return ResponseEntity.ok(bookingService.confirmBooking(id, userId));
    }
}
