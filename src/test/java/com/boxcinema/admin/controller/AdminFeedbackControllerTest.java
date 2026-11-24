package com.boxcinema.admin.controller;

import com.boxcinema.admin.dto.AdminFeedbackDto;
import com.boxcinema.admin.dto.AdminFeedbackStatsDto;
import com.boxcinema.admin.service.AdminFeedbackService;
import com.boxcinema.common.config.SecurityConfig;
import com.boxcinema.common.dto.UserSummaryDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminFeedbackController.class)
@Import(SecurityConfig.class)
class AdminFeedbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminFeedbackService feedbackService;

    @Test
    void testListFeedbacksRendersViewSuccessfully() throws Exception {
        AdminFeedbackStatsDto stats = new AdminFeedbackStatsDto(10, 3, 2, 4, 1);
        AdminFeedbackDto dto = new AdminFeedbackDto();
        dto.setInquiryId(1L);
        dto.setFullName("Nguyen Van A");
        dto.setEmail("customer@test.com");
        dto.setInquiryTopic("TICKETING");
        dto.setMessage("Need assistance with seat booking");
        dto.setInquiryStatus("PENDING");
        dto.setCreatedAt(LocalDateTime.now());

        Page<AdminFeedbackDto> page = new PageImpl<>(Collections.singletonList(dto), PageRequest.of(0, 10), 1);

        when(feedbackService.getFeedbacks(any(), any(), any(), any())).thenReturn(page);
        when(feedbackService.getStatistics()).thenReturn(stats);

        mockMvc.perform(get("/admin/feedbacks")
                        .with(user("admin").roles("ADMIN"))
                        .sessionAttr("currentUser", new UserSummaryDto(1L, "System Admin", "admin@boxcinema.vn", "0900000000", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/feedbacks/index"))
                .andExpect(model().attributeExists("feedbacks"))
                .andExpect(model().attributeExists("stats"));
    }

    @Test
    void testGetFeedbackApiReturnsJson() throws Exception {
        AdminFeedbackDto dto = new AdminFeedbackDto();
        dto.setInquiryId(1L);
        dto.setFullName("Nguyen Van A");
        dto.setEmail("customer@test.com");
        dto.setInquiryTopic("REFUND");
        dto.setMessage("Refund request");
        dto.setInquiryStatus("PENDING");

        when(feedbackService.getFeedbackById(1L)).thenReturn(dto);

        mockMvc.perform(get("/admin/feedbacks/api/1")
                        .with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inquiryId").value(1))
                .andExpect(jsonPath("$.fullName").value("Nguyen Van A"))
                .andExpect(jsonPath("$.inquiryTopic").value("REFUND"));
    }

    @Test
    void testReplyFeedbackRedirectsWithSuccess() throws Exception {
        mockMvc.perform(post("/admin/feedbacks/1/reply")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("adminResponse", "We have processed your refund request.")
                        .param("newStatus", "RESOLVED")
                        .param("sendEmail", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/feedbacks"))
                .andExpect(flash().attributeExists("feedbackSuccessMessage"));
    }
}
