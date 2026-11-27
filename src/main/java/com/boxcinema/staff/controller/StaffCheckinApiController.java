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
import java.util.Map;

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
            String rawMsg = ex.getMessage() != null ? ex.getMessage() : "Access denied.";
            boolean isWrongBranch = rawMsg.startsWith("WRONG_CINEMA_BRANCH|");
            StaffCheckinResponseDto denied = new StaffCheckinResponseDto();
            denied.setSuccess(false);
            denied.setCode(isWrongBranch ? "WRONG_CINEMA_BRANCH" : "FORBIDDEN");
            denied.setMessage(isWrongBranch ? rawMsg.substring("WRONG_CINEMA_BRANCH|".length()) : rawMsg);
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
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(staffCheckinService.getCheckinHistoryForStaff(staffUserId));
    }

    @PostMapping("/bookings/{id}/print-log")
    public ResponseEntity<?> logPhysicalPrint(@PathVariable Long id) {
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        if (staffUserId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "code", "UNAUTHENTICATED", "message", "Staff authentication is required."));
        }
        try {
            return ResponseEntity.ok(staffCheckinService.recordTicketPrint(id, staffUserId));
        } catch (AccessDeniedException ex) {
            String rawMsg = ex.getMessage() != null ? ex.getMessage() : "Access denied.";
            boolean isWrongBranch = rawMsg.startsWith("WRONG_CINEMA_BRANCH|");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "code", isWrongBranch ? "WRONG_CINEMA_BRANCH" : "FORBIDDEN",
                    "message", isWrongBranch ? rawMsg.substring("WRONG_CINEMA_BRANCH|".length()) : rawMsg
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "code", "BOOKING_NOT_FOUND",
                    "message", ex.getMessage()
            ));
        }
    }
}
