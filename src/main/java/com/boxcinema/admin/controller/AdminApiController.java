package com.boxcinema.admin.controller;

import com.boxcinema.admin.service.AdminWorkspaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/api")
@PreAuthorize("hasRole('ADMIN')")
public class AdminApiController {

    private final AdminWorkspaceService adminWorkspaceService;

    public AdminApiController(AdminWorkspaceService adminWorkspaceService) {
        this.adminWorkspaceService = adminWorkspaceService;
    }

    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> getAdminData() {
        return ResponseEntity.ok(adminWorkspaceService.getAdminWorkspaceData());
    }

    @GetMapping("/impact-check")
    public ResponseEntity<Map<String, Object>> checkImpact(
            @RequestParam String section,
            @RequestParam Long id) {
        return ResponseEntity.ok(adminWorkspaceService.checkDeactivationImpact(section, id));
    }

    @PostMapping("/resolve-deactivation")
    public ResponseEntity<Map<String, Object>> resolveDeactivation(@RequestBody Map<String, Object> payload) {
        if (payload == null || payload.get("section") == null || payload.get("id") == null || payload.get("strategy") == null) {
            throw new IllegalArgumentException("section, id, and strategy are required.");
        }
        String section = String.valueOf(payload.get("section"));
        Long id = Long.valueOf(String.valueOf(payload.get("id")));
        String strategy = String.valueOf(payload.get("strategy"));
        return ResponseEntity.ok(adminWorkspaceService.resolveDeactivation(section, id, strategy));
    }

    @PostMapping("/save/{section}")
    public ResponseEntity<Map<String, Object>> saveRecord(
            @PathVariable String section,
            @RequestBody Map<String, Object> body) {
        if (body == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        return ResponseEntity.ok(adminWorkspaceService.saveRecord(section, body));
    }

    @DeleteMapping("/delete/{section}/{id}")
    public ResponseEntity<Map<String, Object>> deleteRecord(
            @PathVariable String section,
            @PathVariable String id) {
        return ResponseEntity.ok(adminWorkspaceService.deleteRecord(section, id));
    }

    @PostMapping("/seats/{roomId}")
    public ResponseEntity<Map<String, Object>> saveSeats(
            @PathVariable Long roomId,
            @RequestBody List<Map<String, Object>> seats) {
        if (seats == null || seats.isEmpty()) {
            throw new IllegalArgumentException("Seat layout payload must not be empty.");
        }
        return ResponseEntity.ok(adminWorkspaceService.saveRoomSeats(roomId, seats));
    }
}
