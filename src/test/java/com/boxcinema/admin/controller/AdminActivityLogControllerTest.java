package com.boxcinema.admin.controller;

import com.boxcinema.admin.service.UserActivityLogService;
import com.boxcinema.common.config.SecurityConfig;
import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.UserActivityLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@WebMvcTest(AdminActivityLogController.class)
@Import(SecurityConfig.class)
class AdminActivityLogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserActivityLogService activityLogService;

    @Test
    void testShowActivityLogsRendersSuccessfully() throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalLogs", 10L);
        stats.put("totalLogins", 5L);
        stats.put("adminActions", 3L);
        stats.put("managerActions", 2L);
        stats.put("staffActions", 1L);
        stats.put("customerActions", 4L);

        UserActivityLog log = new UserActivityLog(1L, "admin@test.com", "ADMIN", "LOGIN", "/login", "Login", "127.0.0.1", "Hanoi", "Chrome");
        Page<UserActivityLog> page = new PageImpl<>(Collections.singletonList(log), PageRequest.of(0, 20), 1);

        when(activityLogService.getLogs(any(), any(), any(), any(), any(), any())).thenReturn(page);
        when(activityLogService.getActivityStatistics()).thenReturn(stats);

        mockMvc.perform(get("/admin/activity-logs")
                        .with(user("admin").roles("ADMIN"))
                        .sessionAttr("currentUser", new UserSummaryDto(100L, "Preview Admin", "preview@example.com", "0900000000", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/activity-logs/index"));
    }
}
