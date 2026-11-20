package com.boxcinema.customer.controller;

import com.boxcinema.customer.dto.request.BookingCreateRequestDto;
import com.boxcinema.common.dto.BookingResponseDto;
import com.boxcinema.customer.dto.request.UpdateFoodItemsRequestDto;
import com.boxcinema.common.service.BookingService;
import com.boxcinema.common.util.SecurityContextUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/bookings")
public class BookingApiController {

    private final BookingService bookingService;

    public BookingApiController(BookingService bookingService) {
        this.bookingService = bookingService;
    }

    @PostMapping
    public ResponseEntity<?> createBooking(@RequestBody BookingCreateRequestDto request) {
        try {
            Long userId = SecurityContextUtils.resolveCurrentUserId();
            BookingResponseDto response = bookingService.createBooking(request, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "errorCode", "SHOWTIME_STARTED",
                    "message", e.getMessage()
            ));
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
        return ResponseEntity.ok(bookingService.getBookingById(id));
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
}
