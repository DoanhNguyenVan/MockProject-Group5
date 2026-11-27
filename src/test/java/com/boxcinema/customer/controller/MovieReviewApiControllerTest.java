package com.boxcinema.customer.controller;

import com.boxcinema.customer.dto.response.MovieReviewSummaryDto;
import com.boxcinema.customer.service.MovieReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MovieReviewApiControllerTest {

    @Test
    void returnsMovieReviewsSummary() throws Exception {
        MovieReviewService service = mock(MovieReviewService.class);
        MovieReviewSummaryDto dto = new MovieReviewSummaryDto();
        dto.setMovieId(7L);
        dto.setAverageRating(4.8);
        dto.setTotalReviews(5);
        dto.setRatingCounts(Map.of(5, 4, 4, 1, 3, 0, 2, 0, 1, 0));
        dto.setReviews(List.of());

        when(service.getReviewsForMovie(eq(7L), isNull(), eq(false), eq("newest"))).thenReturn(dto);

        MockMvcBuilders.standaloneSetup(new MovieReviewApiController(service)).build()
                .perform(get("/api/movies/7/reviews"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(4.8))
                .andExpect(jsonPath("$.totalReviews").value(5));
    }

    @Test
    void returnsForbiddenWhenUnauthenticatedSubmitHitsService() throws Exception {
        MovieReviewService service = mock(MovieReviewService.class);
        when(service.submitReview(eq(7L), isNull(), eq(false), any()))
                .thenThrow(new AccessDeniedException("Please sign in to review this movie."));

        MockMvcBuilders.standaloneSetup(new MovieReviewApiController(service)).build()
                .perform(post("/api/movies/7/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"comment\":\"Awesome\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void returnsBadRequestOnInvalidRating() throws Exception {
        MovieReviewService service = mock(MovieReviewService.class);
        when(service.submitReview(eq(7L), isNull(), eq(false), any()))
                .thenThrow(new IllegalArgumentException("Rating must be between 1 and 5 stars."));

        MockMvcBuilders.standaloneSetup(new MovieReviewApiController(service)).build()
                .perform(post("/api/movies/7/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":9,\"comment\":\"Bad\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Rating must be between 1 and 5 stars."));
    }

    @Test
    void returnsConflictWhenUserAlreadyReviewed() throws Exception {
        MovieReviewService service = mock(MovieReviewService.class);
        when(service.submitReview(eq(7L), isNull(), eq(false), any()))
                .thenThrow(new IllegalStateException("You have already reviewed this movie. Reviews cannot be edited once posted."));

        MockMvcBuilders.standaloneSetup(new MovieReviewApiController(service)).build()
                .perform(post("/api/movies/7/reviews")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\":5,\"comment\":\"Edited\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void deletesReviewSuccessfully() throws Exception {
        MovieReviewService service = mock(MovieReviewService.class);
        MovieReviewSummaryDto dto = new MovieReviewSummaryDto();
        dto.setMovieId(7L);
        dto.setTotalReviews(0);
        when(service.deleteReview(eq(7L), eq(12L), isNull(), eq(false))).thenReturn(dto);

        MockMvcBuilders.standaloneSetup(new MovieReviewApiController(service)).build()
                .perform(delete("/api/movies/7/reviews/12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalReviews").value(0));
    }
}
