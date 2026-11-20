package com.boxcinema.admin.controller;

import com.boxcinema.admin.service.AdminWorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
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
        try {
            return ResponseEntity.ok(adminWorkspaceService.checkDeactivationImpact(section, id));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/resolve-deactivation")
    public ResponseEntity<Map<String, Object>> resolveDeactivation(@RequestBody Map<String, Object> payload) {
        try {
            String section = String.valueOf(payload.get("section"));
            Long id = Long.valueOf(String.valueOf(payload.get("id")));
            String strategy = String.valueOf(payload.get("strategy"));
            return ResponseEntity.ok(adminWorkspaceService.resolveDeactivation(section, id, strategy));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @PostMapping("/save/{section}")
    public ResponseEntity<Map<String, Object>> saveRecord(
            @PathVariable String section,
            @RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(adminWorkspaceService.saveRecord(section, body));
        } catch (Exception ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    @DeleteMapping("/delete/{section}/{id}")
    public ResponseEntity<Map<String, Object>> deleteRecord(
            @PathVariable String section,
            @PathVariable Long id) {
        try {
            return ResponseEntity.ok(adminWorkspaceService.deleteRecord(section, id));
        } catch (Exception ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }

    @PostMapping("/seats/{roomId}")
    public ResponseEntity<Map<String, Object>> saveSeats(
            @PathVariable Long roomId,
            @RequestBody List<Map<String, Object>> seats) {
        try {
            return ResponseEntity.ok(adminWorkspaceService.saveRoomSeats(roomId, seats));
        } catch (Exception ex) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("error", ex.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
        }
    }
}
