package com.boxcinema.staff.controller;

import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.refund.dto.IncidentCreateRequestDto;
import com.boxcinema.refund.dto.IncidentResponseDto;
import com.boxcinema.refund.service.EvidenceStorageService;
import com.boxcinema.refund.service.IncidentService;
import com.boxcinema.refund.service.RefundRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/staff/incidents")
@PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
public class StaffIncidentApiController {

    private final IncidentService incidentService;
    private final EvidenceStorageService evidenceStorageService;

    @Autowired(required = false)
    private RefundRateLimitService refundRateLimitService;

    public StaffIncidentApiController(IncidentService incidentService, EvidenceStorageService evidenceStorageService) {
        this.incidentService = incidentService;
        this.evidenceStorageService = evidenceStorageService;
    }

    @GetMapping
    public ResponseEntity<Page<IncidentResponseDto>> listIncidents(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String group,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(incidentService.listIncidents(null, status, group, page, size, userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<IncidentResponseDto> getIncident(@PathVariable Long id) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(incidentService.getIncident(id, userId));
    }

    @PostMapping
    public ResponseEntity<IncidentResponseDto> createIncident(@RequestBody IncidentCreateRequestDto request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(incidentService.createIncident(request, userId));
    }

    @PostMapping("/{id}/evidence")
    public ResponseEntity<IncidentResponseDto> addEvidence(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(incidentService.appendEvidence(
                id,
                body != null ? body.get("note") : null,
                body != null ? body.get("evidenceUrl") : null,
                userId));
    }

    @PostMapping("/{id}/appeal")
    public ResponseEntity<IncidentResponseDto> appeal(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(incidentService.submitAppeal(
                id,
                body != null ? body.get("appealReason") : null,
                userId));
    }

    @PostMapping("/evidence-upload")
    public ResponseEntity<Map<String, String>> uploadEvidence(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        if (refundRateLimitService != null) {
            refundRateLimitService.checkEvidenceUploadLimit(userId, request != null ? request.getRemoteAddr() : null);
        }
        String url = evidenceStorageService.storeImage(file);
        return ResponseEntity.ok(Map.of("url", url));
    }
}
