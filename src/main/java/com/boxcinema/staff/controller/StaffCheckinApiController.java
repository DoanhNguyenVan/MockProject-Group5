package com.boxcinema.staff.controller;

import com.boxcinema.common.dto.BookingResponseDto;
import com.boxcinema.staff.dto.request.StaffCheckinRequestDto;
import com.boxcinema.staff.dto.response.StaffCheckinResponseDto;
import com.boxcinema.common.entity.BookingCheckin;
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
            return ResponseEntity.badRequest().build();
        }
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
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
