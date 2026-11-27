package com.boxcinema.admin.controller;

import com.boxcinema.admin.dto.AdminMovieReviewDto;
import com.boxcinema.admin.dto.AdminMovieReviewStatsDto;
import com.boxcinema.admin.dto.AdminTopRatedMovieDto;
import com.boxcinema.admin.service.AdminMovieReviewService;
import com.boxcinema.common.config.SecurityConfig;
import com.boxcinema.common.dto.MovieOptionDto;
import com.boxcinema.common.dto.UserSummaryDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminMovieReviewController.class)
@Import(SecurityConfig.class)
class AdminMovieReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminMovieReviewService reviewService;

    @Test
    void adminRendersMovieCommentsPageWithStatsRowsAndCsrf() throws Exception {
        AdminMovieReviewDto dto = new AdminMovieReviewDto();
        dto.setReviewId(55L);
        dto.setMovieId(7L);
        dto.setMovieTitle("Dune: Part Two");
        dto.setReviewerName("Doanh Nguyen");
        dto.setReviewerInitial("D");
        dto.setReviewerEmail("doanh@boxcinema.vn");
        dto.setRating(5);
        dto.setComment("Breathtaking sound design!");
        dto.setVerifiedViewer(true);
        dto.setStatus("VISIBLE");
        dto.setAdminReply("Thank you for visiting BOX Cinema!");
        dto.setCreatedAt(LocalDateTime.now());

        when(reviewService.getReviews(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(dto)));
        when(reviewService.getStats()).thenReturn(new AdminMovieReviewStatsDto(12, 10, 2, 8, 4.7));
        when(reviewService.getTopRatedMovies(5)).thenReturn(List.of(
                new AdminTopRatedMovieDto(1, 7L, "Dune: Part Two",
                        "/assets/img/posters/dune-part-two.jpg",
                        "/assets/img/banners/dune-part-two.jpg",
                        4.9, 148L)
        ));
        when(reviewService.getMovieFilterOptions())
                .thenReturn(List.of(new MovieOptionDto(7L, "Dune: Part Two", "T13", 166)));

        mockMvc.perform(get("/admin/reviews")
                        .with(user("admin@boxcinema.vn").roles("ADMIN"))
                        .sessionAttr("currentUser", new UserSummaryDto(1L, "System Admin", "admin@boxcinema.vn", "0901000001", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/reviews/index"))
                .andExpect(content().string(containsString("Movie Comments &amp; Reviews")))
                .andExpect(content().string(containsString("Top 5 Highest-Rated Movies")))
                .andExpect(content().string(containsString("Dune: Part Two")))
                .andExpect(content().string(containsString("148")))
                .andExpect(content().string(containsString("Breathtaking sound design!")))
                .andExpect(content().string(containsString("Thank you for visiting BOX Cinema!")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "STAFF", "MANAGER"})
    void nonAdminsReceiveForbiddenOnReviewsConsole(String role) throws Exception {
        mockMvc.perform(get("/admin/reviews").with(user("user").roles(role)))
                .andExpect(status().isForbidden());
    }

    @Test
    void enforcesCsrfAndExecutesModerationPostsForAdmin() throws Exception {
        // Missing CSRF -> 403 Forbidden
        mockMvc.perform(post("/admin/reviews/55/status")
                        .with(user("admin").roles("ADMIN"))
                        .param("targetStatus", "HIDDEN"))
                .andExpect(status().isForbidden());

        AdminMovieReviewDto updated = new AdminMovieReviewDto();
        updated.setReviewId(55L);
        updated.setStatus("HIDDEN");
        when(reviewService.updateStatus(eq(55L), eq("HIDDEN"), any())).thenReturn(updated);

        mockMvc.perform(post("/admin/reviews/55/status")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("targetStatus", "HIDDEN"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reviews"));

        AdminMovieReviewDto replied = new AdminMovieReviewDto();
        replied.setReviewId(55L);
        replied.setAdminReply("Thanks!");
        when(reviewService.replyToReview(eq(55L), eq("Thanks!"), any())).thenReturn(replied);

        mockMvc.perform(post("/admin/reviews/55/reply")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("adminReply", "Thanks!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reviews"));

        mockMvc.perform(post("/admin/reviews/bulk")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("reviewIds", "55", "56")
                        .param("action", "HIDE"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/reviews"));

        verify(reviewService).bulkModerate(eq(List.of(55L, 56L)), eq("HIDE"), any());
    }
}
