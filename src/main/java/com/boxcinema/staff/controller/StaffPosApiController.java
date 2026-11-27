package com.boxcinema.staff.controller;

import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.staff.dto.request.StaffPosCreateBookingRequest;
import com.boxcinema.staff.dto.response.StaffPosBookingResultDto;
import com.boxcinema.staff.dto.response.StaffPosOrderSummaryDto;
import com.boxcinema.staff.dto.response.StaffPosShiftSummaryDto;
import com.boxcinema.staff.dto.response.StaffPosShowtimeDto;
import com.boxcinema.staff.service.StaffPosService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/staff/pos")
@PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
public class StaffPosApiController {

    private static final Logger log = LoggerFactory.getLogger(StaffPosApiController.class);

    private final StaffPosService staffPosService;

    public StaffPosApiController(StaffPosService staffPosService) {
        this.staffPosService = staffPosService;
    }

    @GetMapping("/showtimes")
    public ResponseEntity<List<StaffPosShowtimeDto>> getShowtimes() {
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(staffPosService.getUpcomingShowtimes(staffUserId));
    }

    @PostMapping("/bookings")
    public ResponseEntity<?> createBooking(@RequestBody StaffPosCreateBookingRequest request) {
        try {
            Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(staffPosService.createCounterBooking(request, staffUserId));
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        } catch (Exception ex) {
            log.error("Unexpected error while creating counter POS booking", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "success", false,
                    "message", "System error while creating transaction: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())
            ));
        }
    }

    @PostMapping("/bookings/{id}/cancel")
    public ResponseEntity<?> cancelPendingBooking(@PathVariable Long id) {
        try {
            Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
            staffPosService.cancelPendingPosBooking(id, staffUserId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (Exception ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        }
    }

    @GetMapping("/bookings/{id}/result")
    public ResponseEntity<StaffPosBookingResultDto> getBookingResult(@PathVariable Long id) {
        return ResponseEntity.ok(staffPosService.getBookingResult(id));
    }

    @GetMapping("/bookings/{id}/qr")
    public ResponseEntity<byte[]> getBookingQrImage(@PathVariable Long id) {
        try {
            byte[] png = staffPosService.getBookingQrPng(id);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header(HttpHeaders.CACHE_CONTROL, "private, no-store, max-age=0, must-revalidate")
                    .body(png);
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/bookings/lookup")
    public ResponseEntity<List<StaffPosOrderSummaryDto>> lookupBookings(
            @RequestParam(required = false, defaultValue = "") String q) {
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(staffPosService.lookupBookings(staffUserId, q));
    }

    @GetMapping("/shift-summary")
    public ResponseEntity<StaffPosShiftSummaryDto> getShiftSummary(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(staffPosService.getShiftSummary(staffUserId, date));
    }
}
