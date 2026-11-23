package com.boxcinema.admin.controller;

import com.boxcinema.admin.service.UserActivityLogService;
import com.boxcinema.common.entity.UserActivityLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Map;

@Controller
@RequestMapping("/admin/activity-logs")
@PreAuthorize("hasRole('ADMIN')")
public class AdminActivityLogController {

    private final UserActivityLogService activityLogService;

    @Autowired
    public AdminActivityLogController(UserActivityLogService activityLogService) {
        this.activityLogService = activityLogService;
    }

    @GetMapping
    public String showActivityLogs(
            @RequestParam(name = "role", required = false) String role,
            @RequestParam(name = "actionType", required = false) String actionType,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            Model model
    ) {
        LocalDateTime startDateTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDateTime = endDate != null ? endDate.atTime(LocalTime.MAX) : null;

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<UserActivityLog> logPage = activityLogService.getLogs(role, actionType, search, startDateTime, endDateTime, pageable);
        Map<String, Object> statistics = activityLogService.getActivityStatistics();

        model.addAttribute("logs", logPage);
        model.addAttribute("stats", statistics);
        model.addAttribute("selectedRole", role != null ? role : "");
        model.addAttribute("selectedActionType", actionType != null ? actionType : "");
        model.addAttribute("searchQuery", search != null ? search : "");
        model.addAttribute("startDate", startDate);
        model.addAttribute("endDate", endDate);
        model.addAttribute("activeNav", "activity-logs");

        return "admin/activity-logs/index";
    }
}
