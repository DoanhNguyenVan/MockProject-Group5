package com.boxcinema.admin.service.impl;

import com.boxcinema.admin.service.UserActivityLogService;
import com.boxcinema.common.entity.UserActivityLog;
import com.boxcinema.common.repository.UserActivityLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UserActivityLogServiceImpl implements UserActivityLogService {

    private static final Logger log = LoggerFactory.getLogger(UserActivityLogServiceImpl.class);

    private final UserActivityLogRepository logRepository;

    @Autowired
    public UserActivityLogServiceImpl(UserActivityLogRepository logRepository) {
        this.logRepository = logRepository;
    }

    @Override
    @Async("activityLogExecutor")
    @Transactional
    public void logActivityAsync(Long userId, String email, String role, String actionType,
                                 String pageUrl, String pageTitle, String ipAddress,
                                 String location, String deviceInfo) {
        try {
            // Trim / sanitize fields to fit column sizes
            String sanitizedUrl = (pageUrl != null && pageUrl.length() > 250) ? pageUrl.substring(0, 250) : pageUrl;
            String sanitizedTitle = (pageTitle != null && pageTitle.length() > 140) ? pageTitle.substring(0, 140) : pageTitle;
            String sanitizedIp = (ipAddress != null && ipAddress.length() > 40) ? ipAddress.substring(0, 40) : (ipAddress != null ? ipAddress : "127.0.0.1");
            String sanitizedLocation = (location != null && location.length() > 90) ? location.substring(0, 90) : location;
            String sanitizedDevice = (deviceInfo != null && deviceInfo.length() > 240) ? deviceInfo.substring(0, 240) : deviceInfo;

            UserActivityLog entity = new UserActivityLog(
                    userId,
                    email,
                    role,
                    actionType,
                    sanitizedUrl != null ? sanitizedUrl : "/",
                    sanitizedTitle,
                    sanitizedIp,
                    sanitizedLocation,
                    sanitizedDevice
            );
            logRepository.save(entity);
        } catch (Exception ex) {
            log.warn("Failed to asynchronously save user activity log: {}", ex.getMessage());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserActivityLog> getLogs(String role, String actionType, String search,
                                         LocalDateTime startDate, LocalDateTime endDate,
                                         Pageable pageable) {
        return logRepository.findFilteredLogs(role, actionType, search, startDate, endDate, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> getActivityStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalLogs", logRepository.count());
        stats.put("totalLogins", logRepository.countByActionType("LOGIN"));
        stats.put("adminActions", logRepository.countByRole("ADMIN"));
        stats.put("managerActions", logRepository.countByRole("MANAGER"));
        stats.put("staffActions", logRepository.countByRole("STAFF"));
        stats.put("customerActions", logRepository.countByRole("CUSTOMER"));
        return stats;
    }
}
