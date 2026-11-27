package com.boxcinema.admin.service;

import com.boxcinema.admin.dto.AdminMovieReviewDto;
import com.boxcinema.admin.dto.AdminMovieReviewStatsDto;
import com.boxcinema.admin.dto.AdminTopRatedMovieDto;
import com.boxcinema.common.dto.MovieOptionDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Admin moderation service for customer movie reviews and comments.
 */
public interface AdminMovieReviewService {

    Page<AdminMovieReviewDto> getReviews(
            Long movieId,
            Integer rating,
            String status,
            Boolean verified,
            String keyword,
            Pageable pageable
    );

    AdminMovieReviewStatsDto getStats();

    List<AdminTopRatedMovieDto> getTopRatedMovies(int limit);

    List<MovieOptionDto> getMovieFilterOptions();

    AdminMovieReviewDto updateStatus(Long reviewId, String targetStatus, Long adminUserId);

    AdminMovieReviewDto replyToReview(Long reviewId, String replyText, Long adminUserId);

    void deleteReview(Long reviewId, Long adminUserId);

    int bulkModerate(List<Long> reviewIds, String action, Long adminUserId);
}
