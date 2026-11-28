package com.boxcinema.staff.controller;

import com.boxcinema.common.dto.BookingResponseDto;
import com.boxcinema.staff.dto.request.StaffCheckinRequestDto;
import com.boxcinema.staff.dto.response.StaffCheckinHistoryDto;
import com.boxcinema.staff.dto.response.StaffCheckinResponseDto;
import com.boxcinema.staff.service.StaffCheckinService;
import com.boxcinema.common.util.SecurityContextUtils;
import org.springframework.http.ResponseEntity;
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
            throw new IllegalArgumentException("QR payload is required.");
        }
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        StaffCheckinResponseDto response = staffCheckinService.checkinByQrToken(token.trim(), staffUserId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/bookings/{id}")
    public ResponseEntity<BookingResponseDto> getBookingDetails(@PathVariable Long id) {
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(staffCheckinService.getBookingForStaff(id, staffUserId));
    }

    @GetMapping("/check-ins")
    public ResponseEntity<List<StaffCheckinHistoryDto>> getCheckinHistory() {
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(staffCheckinService.getCheckinHistoryDtos(staffUserId));
    }
}
