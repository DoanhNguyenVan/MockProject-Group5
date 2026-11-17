package com.boxcinema.controller;

import com.boxcinema.dto.BookingResponseDto;
import com.boxcinema.dto.StaffCheckinRequestDto;
import com.boxcinema.dto.StaffCheckinResponseDto;
import com.boxcinema.entity.BookingCheckin;
import com.boxcinema.security.BoxCinemaUserDetails;
import com.boxcinema.service.StaffCheckinService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

    private Long resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof BoxCinemaUserDetails details) {
            if (details.getUserSummary() != null && details.getUserSummary().getUserId() != null) {
                return details.getUserSummary().getUserId();
            }
        }
        return null;
    }

    @PostMapping("/bookings/check-in")
    public ResponseEntity<StaffCheckinResponseDto> checkin(@RequestBody StaffCheckinRequestDto request) {
        String token = request != null ? request.getQrPayload() : null;
        if (token == null || token.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        Long staffUserId = resolveCurrentUserId();
        StaffCheckinResponseDto response = staffCheckinService.checkinByQrToken(token.trim(), staffUserId);
        return ResponseEntity.ok(response);
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
