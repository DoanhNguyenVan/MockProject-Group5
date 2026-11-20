package com.boxcinema.customer.controller;

import com.boxcinema.common.dto.BookingTicketViewDto;
import com.boxcinema.customer.service.TicketService;
import com.boxcinema.common.util.SecurityContextUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping({"/api/customer/bookings", "/api/bookings"})
public class TicketApiController {

    private final TicketService ticketService;

    public TicketApiController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping("/{id}/ticket")
    public ResponseEntity<?> getTicketView(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        try {
            BookingTicketViewDto ticketView = ticketService.getTicketView(id, userId);
            return ResponseEntity.ok(ticketView);
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/{id}/ticket/qr")
    public ResponseEntity<byte[]> getBookingQrImage(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        try {
            byte[] imageBytes = ticketService.getBookingQrImage(id, userId);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0, must-revalidate")
                    .body(imageBytes);
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping("/{id}/email/resend")
    public ResponseEntity<Map<String, Object>> resendEmail(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        try {
            ticketService.resendEmail(id, userId);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Request to resend ticket via email has been accepted."
            ));
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        }
    }
}
