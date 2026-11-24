package com.boxcinema.admin.service;

import com.boxcinema.admin.dto.AdminFeedbackDto;
import com.boxcinema.admin.dto.AdminFeedbackStatsDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminFeedbackService {
    Page<AdminFeedbackDto> getFeedbacks(String status, String topic, String search, Pageable pageable);

    AdminFeedbackStatsDto getStatistics();

    AdminFeedbackDto getFeedbackById(Long inquiryId);

    void replyFeedback(Long inquiryId, String adminResponse, String newStatus, Long adminUserId, boolean sendEmail);

    void updateStatus(Long inquiryId, String newStatus, Long adminUserId);

    void deleteFeedback(Long inquiryId);
}
