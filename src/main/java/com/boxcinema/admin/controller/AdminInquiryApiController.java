package com.boxcinema.admin.controller;

import com.boxcinema.common.entity.Inquiry;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.InquiryRepository;
import com.boxcinema.common.util.SecurityContextUtils;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/admin/inquiries")
@PreAuthorize("hasRole('ADMIN')")
public class AdminInquiryApiController {

    private static final Set<String> VALID_STATUSES = Set.of("PENDING", "IN_PROGRESS", "RESOLVED", "CLOSED");

    private final InquiryRepository inquiryRepository;

    public AdminInquiryApiController(InquiryRepository inquiryRepository) {
        this.inquiryRepository = inquiryRepository;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<Page<Inquiry>> listInquiries(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.max(1, Math.min(100, size));

        List<Inquiry> all;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status.trim())) {
            String cleanStatus = status.trim().toUpperCase(Locale.ROOT);
            if (!VALID_STATUSES.contains(cleanStatus)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Invalid inquiry status filter.");
            }
            all = inquiryRepository.findByInquiryStatusOrderByCreatedAtDesc(cleanStatus);
        } else {
            all = inquiryRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt"));
        }

        if (search != null && !search.isBlank()) {
            String needle = search.trim().toLowerCase(Locale.ROOT);
            all = all.stream().filter(i ->
                    (i.getFullName() != null && i.getFullName().toLowerCase(Locale.ROOT).contains(needle))
                            || (i.getEmail() != null && i.getEmail().toLowerCase(Locale.ROOT).contains(needle))
                            || (i.getInquiryTopic() != null && i.getInquiryTopic().toLowerCase(Locale.ROOT).contains(needle))
                            || (i.getMessage() != null && i.getMessage().toLowerCase(Locale.ROOT).contains(needle))
            ).toList();
        }

        int from = Math.min(safePage * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        Page<Inquiry> result = new PageImpl<>(all.subList(from, to), PageRequest.of(safePage, safeSize), all.size());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/respond")
    @Transactional
    public ResponseEntity<Inquiry> respondToInquiry(
            @PathVariable Long id,
            @RequestBody Map<String, String> body) {
        Long adminUserId = SecurityContextUtils.resolveCurrentUserId();
        Inquiry inquiry = inquiryRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INQUIRY_NOT_FOUND", "Inquiry not found: " + id));

        String rawStatus = body != null ? body.get("inquiryStatus") : null;
        String nextStatus = (rawStatus != null && !rawStatus.isBlank())
                ? rawStatus.trim().toUpperCase(Locale.ROOT)
                : "RESOLVED";
        if (!VALID_STATUSES.contains(nextStatus)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Unsupported inquiry status: " + nextStatus);
        }

        String adminResponse = body != null ? body.get("adminResponse") : null;
        if (("RESOLVED".equals(nextStatus) || "CLOSED".equals(nextStatus))
                && (adminResponse == null || adminResponse.isBlank())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RESPONSE_REQUIRED",
                    "Admin response text is required when resolving or closing an inquiry.");
        }

        inquiry.setInquiryStatus(nextStatus);
        inquiry.setAssignedTo(adminUserId);
        if (adminResponse != null && !adminResponse.isBlank()) {
            inquiry.setAdminResponse(adminResponse.trim());
            inquiry.setRespondedAt(LocalDateTime.now());
        }
        return ResponseEntity.ok(inquiryRepository.save(inquiry));
    }
}
