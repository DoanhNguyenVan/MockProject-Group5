package com.boxcinema.refund.controller;

import com.boxcinema.common.entity.User;
import com.boxcinema.refund.service.EvidenceStorageService;
import com.boxcinema.refund.service.RefundRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Authenticated Evidence Upload & Authorized Download Controller ({@code /api/refunds/evidence/**}).
 */
@RestController
@RequestMapping("/api/refunds/evidence")
public class RefundEvidenceApiController {

    private final EvidenceStorageService evidenceStorageService;
    private final RefundRateLimitService refundRateLimitService;

    public RefundEvidenceApiController(
            EvidenceStorageService evidenceStorageService,
            RefundRateLimitService refundRateLimitService) {
        this.evidenceStorageService = evidenceStorageService;
        this.refundRateLimitService = refundRateLimitService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadEvidence(
            @RequestParam(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(value = "file", required = false) MultipartFile singleFile,
            @RequestParam(value = "refundRequestId", required = false) Long refundRequestId,
            @RequestParam(value = "incidentReportId", required = false) Long incidentReportId,
            HttpServletRequest request) {
        User actor = evidenceStorageService.resolveAuthenticatedActor(request);
        Long userId = actor != null && actor.getUserId() != null && actor.getUserId() > 0 ? actor.getUserId() : null;
        String email = actor != null ? actor.getEmail() : null;
        String clientIp = request != null ? request.getRemoteAddr() : null;

        refundRateLimitService.checkEvidenceUploadLimit(userId, email, clientIp);

        List<MultipartFile> batch;
        if (files != null && !files.isEmpty()) {
            batch = files;
        } else if (singleFile != null) {
            batch = List.of(singleFile);
        } else {
            batch = List.of();
        }

        List<EvidenceStorageService.StoredEvidenceDto> stored = evidenceStorageService.storeEvidenceBatch(
                batch, userId, email, clientIp, refundRequestId, incidentReportId);

        EvidenceStorageService.StoredEvidenceDto first = stored.get(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("url", first.url());
        body.put("publicUrl", first.publicUrl());
        body.put("storageName", first.storageName());
        body.put("scanStatus", first.scanStatus());
        body.put("files", stored);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{filename}")
    public ResponseEntity<Resource> downloadEvidence(
            @PathVariable String filename,
            HttpServletRequest request) {
        User actor = evidenceStorageService.resolveAuthenticatedActor(request);
        String clientIp = request != null ? request.getRemoteAddr() : null;
        Resource resource = evidenceStorageService.authorizeAndLoadEvidence(filename, actor, clientIp);
        String lower = filename.toLowerCase(Locale.ROOT);
        MediaType mediaType = lower.endsWith(".png") ? MediaType.IMAGE_PNG
                : (lower.endsWith(".webp") ? MediaType.parseMediaType("image/webp") : MediaType.IMAGE_JPEG);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, no-store, max-age=0")
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self'; style-src 'none'; sandbox")
                .body(resource);
    }
}
