package com.boxcinema.admin.service.impl;

import com.boxcinema.admin.dto.AdminFeedbackDto;
import com.boxcinema.admin.dto.AdminFeedbackStatsDto;
import com.boxcinema.admin.service.AdminFeedbackService;
import com.boxcinema.common.entity.Inquiry;
import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.InquiryRepository;
import com.boxcinema.common.repository.UserRepository;
import com.boxcinema.common.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class AdminFeedbackServiceImpl implements AdminFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(AdminFeedbackServiceImpl.class);

    private final InquiryRepository inquiryRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    public AdminFeedbackServiceImpl(InquiryRepository inquiryRepository,
                                    UserRepository userRepository,
                                    EmailService emailService) {
        this.inquiryRepository = inquiryRepository;
        this.userRepository = userRepository;
        this.emailService = emailService;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminFeedbackDto> getFeedbacks(String status, String topic, String search, Pageable pageable) {
        String cleanStatus = (status != null && !status.isBlank() && !status.equalsIgnoreCase("ALL")) ? status.trim().toUpperCase() : null;
        String cleanTopic = (topic != null && !topic.isBlank() && !topic.equalsIgnoreCase("ALL")) ? topic.trim() : null;
        String cleanSearch = (search != null && !search.isBlank()) ? search.trim() : null;

        Page<Inquiry> entities = inquiryRepository.findWithFilters(cleanStatus, cleanTopic, cleanSearch, pageable);

        Map<Long, String> adminNamesCache = new HashMap<>();

        return entities.map(entity -> toDto(entity, adminNamesCache));
    }

    @Override
    @Transactional(readOnly = true)
    public AdminFeedbackStatsDto getStatistics() {
        long total = inquiryRepository.count();
        long pending = inquiryRepository.countByInquiryStatus("PENDING");
        long inProgress = inquiryRepository.countByInquiryStatus("IN_PROGRESS");
        long resolved = inquiryRepository.countByInquiryStatus("RESOLVED");
        long closed = inquiryRepository.countByInquiryStatus("CLOSED");

        return new AdminFeedbackStatsDto(total, pending, inProgress, resolved, closed);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminFeedbackDto getFeedbackById(Long inquiryId) {
        Inquiry entity = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new IllegalArgumentException("Feedback inquiry not found: " + inquiryId));
        return toDto(entity, new HashMap<>());
    }

    @Override
    @Transactional
    public void replyFeedback(Long inquiryId, String adminResponse, String newStatus, Long adminUserId, boolean sendEmail) {
        Inquiry entity = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new IllegalArgumentException("Feedback inquiry not found: " + inquiryId));

        String sanitizedResponse = (adminResponse != null) ? adminResponse.trim() : "";
        if (sanitizedResponse.isBlank()) {
            throw new IllegalArgumentException("Admin response cannot be empty.");
        }

        String targetStatus = (newStatus != null && !newStatus.isBlank()) ? newStatus.trim().toUpperCase() : "RESOLVED";

        entity.setAdminResponse(sanitizedResponse);
        entity.setInquiryStatus(targetStatus);
        entity.setAssignedTo(adminUserId);
        entity.setRespondedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        inquiryRepository.save(entity);
        log.info("[ADMIN FEEDBACK REPLY] Inquiry #{} updated to status: {} by admin user #{}", inquiryId, targetStatus, adminUserId);

        if (sendEmail && entity.getEmail() != null && !entity.getEmail().isBlank()) {
            try {
                emailService.sendInquiryResponseEmailAsync(
                        entity.getEmail(),
                        entity.getFullName(),
                        entity.getInquiryId(),
                        entity.getInquiryTopic(),
                        sanitizedResponse
                );
            } catch (Exception ex) {
                log.warn("[ADMIN FEEDBACK EMAIL ERROR] Failed to dispatch resolution email to {}: {}", entity.getEmail(), ex.getMessage());
            }
        }
    }

    @Override
    @Transactional
    public void updateStatus(Long inquiryId, String newStatus, Long adminUserId) {
        Inquiry entity = inquiryRepository.findById(inquiryId)
                .orElseThrow(() -> new IllegalArgumentException("Feedback inquiry not found: " + inquiryId));

        String targetStatus = (newStatus != null && !newStatus.isBlank()) ? newStatus.trim().toUpperCase() : "PENDING";
        entity.setInquiryStatus(targetStatus);
        entity.setUpdatedAt(LocalDateTime.now());
        if (adminUserId != null && entity.getAssignedTo() == null) {
            entity.setAssignedTo(adminUserId);
        }

        inquiryRepository.save(entity);
        log.info("[ADMIN FEEDBACK STATUS CHANGE] Inquiry #{} status changed to {}", inquiryId, targetStatus);
    }

    @Override
    @Transactional
    public void deleteFeedback(Long inquiryId) {
        if (!inquiryRepository.existsById(inquiryId)) {
            throw new IllegalArgumentException("Feedback inquiry not found: " + inquiryId);
        }
        inquiryRepository.deleteById(inquiryId);
        log.info("[ADMIN FEEDBACK DELETE] Inquiry #{} removed", inquiryId);
    }

    private AdminFeedbackDto toDto(Inquiry entity, Map<Long, String> adminNamesCache) {
        AdminFeedbackDto dto = new AdminFeedbackDto();
        dto.setInquiryId(entity.getInquiryId());
        dto.setUserId(entity.getUserId());
        dto.setFullName(entity.getFullName());
        dto.setEmail(entity.getEmail());
        dto.setPhone(entity.getPhone());
        dto.setInquiryTopic(entity.getInquiryTopic());
        dto.setMessage(entity.getMessage());
        dto.setInquiryStatus(entity.getInquiryStatus());
        dto.setAssignedTo(entity.getAssignedTo());
        dto.setAdminResponse(entity.getAdminResponse());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setRespondedAt(entity.getRespondedAt());

        if (entity.getAssignedTo() != null) {
            String adminName = adminNamesCache.computeIfAbsent(entity.getAssignedTo(), id -> {
                return userRepository.findById(id).map(User::getFullName).orElse("Admin #" + id);
            });
            dto.setAssignedToName(adminName);
        }

        return dto;
    }
}
