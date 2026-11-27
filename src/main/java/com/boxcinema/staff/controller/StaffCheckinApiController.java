package com.boxcinema.staff.controller;

import com.boxcinema.common.dto.BookingResponseDto;
import com.boxcinema.common.entity.BookingCheckin;
import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.staff.dto.request.StaffCheckinRequestDto;
import com.boxcinema.staff.dto.response.StaffCheckinResponseDto;
import com.boxcinema.staff.service.StaffCheckinService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/staff")
@PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
public class StaffCheckinApiController {

    private final StaffCheckinService staffCheckinService;

    public StaffCheckinApiController(StaffCheckinService staffCheckinService) {
        this.staffCheckinService = staffCheckinService;
    }

    @PostMapping("/bookings/check-in")
    public ResponseEntity<StaffCheckinResponseDto> checkin(@RequestBody StaffCheckinRequestDto request) {
        String token = request != null ? request.getQrPayload() : null;
        if (token == null || token.isBlank()) {
            StaffCheckinResponseDto bad = new StaffCheckinResponseDto();
            bad.setSuccess(false);
            bad.setCode("INVALID_QR_TOKEN");
            bad.setMessage("QR code payload is required.");
            return ResponseEntity.badRequest().body(bad);
        }
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        if (staffUserId == null) {
            StaffCheckinResponseDto unauth = new StaffCheckinResponseDto();
            unauth.setSuccess(false);
            unauth.setCode("UNAUTHENTICATED");
            unauth.setMessage("Staff authentication is required.");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(unauth);
        }
        try {
            StaffCheckinResponseDto response = staffCheckinService.checkinByQrToken(token.trim(), staffUserId);
            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            }
            HttpStatus status = switch (response.getCode() != null ? response.getCode() : "") {
                case "BOOKING_ALREADY_CHECKED_IN" -> HttpStatus.CONFLICT;
                case "INVALID_QR_TOKEN" -> HttpStatus.NOT_FOUND;
                default -> HttpStatus.BAD_REQUEST;
            };
            return ResponseEntity.status(status).body(response);
        } catch (AccessDeniedException ex) {
            StaffCheckinResponseDto denied = new StaffCheckinResponseDto();
            denied.setSuccess(false);
            denied.setCode("FORBIDDEN");
            denied.setMessage(ex.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(denied);
        } catch (IllegalArgumentException ex) {
            StaffCheckinResponseDto invalid = new StaffCheckinResponseDto();
            invalid.setSuccess(false);
            invalid.setCode("INVALID_REQUEST");
            invalid.setMessage(ex.getMessage());
            return ResponseEntity.badRequest().body(invalid);
        }
    }

    @GetMapping("/bookings/{id}")
    public ResponseEntity<BookingResponseDto> getBookingDetails(@PathVariable Long id) {
        return ResponseEntity.ok(staffCheckinService.getBookingForStaff(id));
    }

    @GetMapping("/check-ins")
    public ResponseEntity<List<BookingCheckin>> getCheckinHistory() {
        return ResponseEntity.ok(staffCheckinService.getCheckinHistory());
    }
}
