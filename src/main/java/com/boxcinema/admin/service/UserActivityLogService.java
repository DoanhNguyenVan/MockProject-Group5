package com.boxcinema.admin.service;

import com.boxcinema.common.entity.UserActivityLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.Map;

public interface UserActivityLogService {

    void logActivityAsync(Long userId, String email, String role, String actionType,
                          String pageUrl, String pageTitle, String ipAddress,
                          String location, String deviceInfo);

    Page<UserActivityLog> getLogs(String role, String actionType, String search,
                                  LocalDateTime startDate, LocalDateTime endDate,
                                  Pageable pageable);

    Map<String, Object> getActivityStatistics();
}
