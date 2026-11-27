package com.boxcinema.customer.controller;

import com.boxcinema.customer.dto.request.BookingCreateRequestDto;
import com.boxcinema.common.dto.BookingResponseDto;
import com.boxcinema.customer.dto.request.UpdateFoodItemsRequestDto;
import com.boxcinema.common.service.BookingService;
import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.customer.service.TicketService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/bookings")
public class BookingApiController {

    private final BookingService bookingService;
    private final TicketService ticketService;

    public BookingApiController(BookingService bookingService) {
        this(bookingService, null);
    }

    @Autowired
    public BookingApiController(BookingService bookingService, TicketService ticketService) {
        this.bookingService = bookingService;
        this.ticketService = ticketService;
    }

    @PostMapping
    public ResponseEntity<?> createBooking(@RequestBody BookingCreateRequestDto request, HttpServletRequest httpRequest) {
        try {
            Long userId = SecurityContextUtils.resolveCurrentUserId(httpRequest);
            BookingResponseDto response = bookingService.createBooking(request, userId);
            if (userId == null && response != null && response.getBookingId() != null) {
                SecurityContextUtils.recordGuestBookingInSession(httpRequest, response.getBookingId());
            }
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
    public ResponseEntity<?> getBooking(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(httpRequest);
        Set<Long> guestIds = SecurityContextUtils.extractGuestSessionBookingIds(httpRequest);
        try {
            if (ticketService != null) {
                ticketService.assertCanAccessBooking(id, userId, guestIds);
            }
            return ResponseEntity.ok(bookingService.getBookingById(id));
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(userId == null ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("success", false, "message", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/food-items")
    public ResponseEntity<?> updateFoodItems(
            @PathVariable Long id,
            @RequestBody UpdateFoodItemsRequestDto request,
            HttpServletRequest httpRequest
    ) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(httpRequest);
        Set<Long> guestIds = SecurityContextUtils.extractGuestSessionBookingIds(httpRequest);
        try {
            if (ticketService != null) {
                ticketService.assertCanAccessBooking(id, userId, guestIds);
            }
            return ResponseEntity.ok(bookingService.updateFoodItems(id, request, userId));
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(userId == null ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", ex.getMessage()));
        }
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirmBooking(@PathVariable Long id, HttpServletRequest httpRequest) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(httpRequest);
        Set<Long> guestIds = SecurityContextUtils.extractGuestSessionBookingIds(httpRequest);
        try {
            if (ticketService != null) {
                ticketService.assertCanAccessBooking(id, userId, guestIds);
            }
            return ResponseEntity.ok(bookingService.confirmBooking(id, userId));
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(userId == null ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN)
                    .body(Map.of("success", false, "message", ex.getMessage()));
        }
    }
}
