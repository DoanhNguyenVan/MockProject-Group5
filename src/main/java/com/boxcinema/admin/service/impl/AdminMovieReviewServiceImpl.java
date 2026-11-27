package com.boxcinema.admin.service.impl;

import com.boxcinema.admin.dto.AdminMovieReviewDto;
import com.boxcinema.admin.dto.AdminMovieReviewStatsDto;
import com.boxcinema.admin.dto.AdminTopRatedMovieDto;
import com.boxcinema.admin.service.AdminMovieReviewService;
import com.boxcinema.admin.service.UserActivityLogService;
import com.boxcinema.common.dto.MovieOptionDto;
import com.boxcinema.common.entity.Movie;
import com.boxcinema.common.entity.MovieReview;
import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.MovieRepository;
import com.boxcinema.common.repository.MovieReviewRepository;
import com.boxcinema.common.repository.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Service
@Transactional
public class AdminMovieReviewServiceImpl implements AdminMovieReviewService {

    private static final int MAX_REPLY_LENGTH = 1000;

    private final MovieReviewRepository reviewRepository;
    private final MovieRepository movieRepository;
    private final UserRepository userRepository;
    private final UserActivityLogService activityLogService;

    public AdminMovieReviewServiceImpl(
            MovieReviewRepository reviewRepository,
            MovieRepository movieRepository,
            UserRepository userRepository,
            ObjectProvider<UserActivityLogService> activityLogServiceProvider
    ) {
        this.reviewRepository = reviewRepository;
        this.movieRepository = movieRepository;
        this.userRepository = userRepository;
        this.activityLogService = activityLogServiceProvider != null ? activityLogServiceProvider.getIfAvailable() : null;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminMovieReviewDto> getReviews(
            Long movieId,
            Integer rating,
            String status,
            Boolean verified,
            String keyword,
            Pageable pageable
    ) {
        Integer safeRating = (rating != null && rating >= 1 && rating <= 5) ? rating : null;
        String safeStatus = (status != null && !status.isBlank()) ? status.trim().toUpperCase(Locale.ROOT) : "";
        String safeKeyword = (keyword != null && !keyword.isBlank()) ? keyword.trim() : "";

        return reviewRepository.findAdminFilteredReviews(
                movieId,
                safeRating,
                safeStatus,
                verified,
                safeKeyword,
                pageable
        ).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminMovieReviewStatsDto getStats() {
        long total = reviewRepository.count();
        long visible = reviewRepository.countByStatusIgnoreCase("VISIBLE");
        long hidden = reviewRepository.countByStatusIgnoreCase("HIDDEN");
        long verified = reviewRepository.countByVerifiedViewerTrue();
        Double rawAvg = reviewRepository.findAverageVisibleRating();
        double avg = rawAvg != null ? Math.round(rawAvg * 10.0) / 10.0 : 0.0;
        return new AdminMovieReviewStatsDto(total, visible, hidden, verified, avg);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AdminTopRatedMovieDto> getTopRatedMovies(int limit) {
        int safeLimit = Math.max(1, Math.min(10, limit));
        List<Object[]> rows = reviewRepository.findTopRatedMovies(PageRequest.of(0, safeLimit));
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<AdminTopRatedMovieDto> result = new ArrayList<>(rows.size());
        int rank = 1;
        for (Object[] row : rows) {
            if (row == null || row.length < 6) {
                continue;
            }
            Long movieId = row[0] instanceof Number num ? num.longValue() : null;
            String title = row[1] != null ? String.valueOf(row[1]) : "Untitled Movie";
            String posterUrl = nonBlankOrFallback(row[2] != null ? String.valueOf(row[2]) : null,
                    "/assets/img/crimson-horizon.webp");
            String bannerUrl = nonBlankOrFallback(row[3] != null ? String.valueOf(row[3]) : null, posterUrl);
            double rawAvg = row[4] instanceof Number num ? num.doubleValue() : 0.0;
            double roundedAvg = Math.round(rawAvg * 10.0) / 10.0;
            long count = row[5] instanceof Number num ? num.longValue() : 0L;
            result.add(new AdminTopRatedMovieDto(rank++, movieId, title, posterUrl, bannerUrl, roundedAvg, count));
        }
        return result;
    }

    private String nonBlankOrFallback(String value, String fallback) {
        return (value != null && !value.trim().isEmpty()) ? value.trim() : fallback;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MovieOptionDto> getMovieFilterOptions() {
        return movieRepository.findAll().stream()
                .sorted(Comparator.comparing(Movie::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .map(m -> new MovieOptionDto(m.getMovieId(), m.getTitle(), m.getAgeRating(), m.getDurationMinutes()))
                .toList();
    }

    @Override
    public AdminMovieReviewDto updateStatus(Long reviewId, String targetStatus, Long adminUserId) {
        MovieReview review = requireReview(reviewId);
        String normalized = normalizeStatus(targetStatus);
        review.setStatus(normalized);
        MovieReview saved = reviewRepository.saveAndFlush(review);
        logModeration(adminUserId, "VISIBLE".equals(normalized) ? "RESTORE_REVIEW" : "HIDE_REVIEW",
                "Review #" + reviewId + " set to " + normalized);
        return toDto(saved);
    }

    @Override
    public AdminMovieReviewDto replyToReview(Long reviewId, String replyText, Long adminUserId) {
        MovieReview review = requireReview(reviewId);
        String clean = replyText != null ? replyText.trim() : "";
        if (clean.length() > MAX_REPLY_LENGTH) {
            throw new IllegalArgumentException("Official reply must not exceed " + MAX_REPLY_LENGTH + " characters.");
        }

        if (clean.isEmpty()) {
            review.setAdminReply(null);
            review.setAdminRepliedAt(null);
            review.setRepliedByAdmin(null);
            logModeration(adminUserId, "CLEAR_REVIEW_REPLY", "Cleared official reply on Review #" + reviewId);
        } else {
            User admin = adminUserId != null ? userRepository.findById(adminUserId).orElse(null) : null;
            review.setAdminReply(clean);
            review.setAdminRepliedAt(LocalDateTime.now());
            review.setRepliedByAdmin(admin);
            logModeration(adminUserId, "REPLY_REVIEW", "Posted official reply on Review #" + reviewId);
        }

        return toDto(reviewRepository.saveAndFlush(review));
    }

    @Override
    public void deleteReview(Long reviewId, Long adminUserId) {
        MovieReview review = requireReview(reviewId);
        reviewRepository.delete(review);
        reviewRepository.flush();
        logModeration(adminUserId, "DELETE_REVIEW", "Permanently deleted Review #" + reviewId);
    }

    @Override
    public int bulkModerate(List<Long> reviewIds, String action, Long adminUserId) {
        if (reviewIds == null || reviewIds.isEmpty()) {
            throw new IllegalArgumentException("Please select at least one comment.");
        }
        String normalizedAction = action != null ? action.trim().toUpperCase(Locale.ROOT) : "";
        List<MovieReview> targets = reviewRepository.findByReviewIdIn(reviewIds);
        if (targets.isEmpty()) {
            return 0;
        }

        switch (normalizedAction) {
            case "HIDE" -> {
                targets.forEach(r -> r.setStatus("HIDDEN"));
                reviewRepository.saveAll(targets);
                reviewRepository.flush();
            }
            case "RESTORE", "VISIBLE" -> {
                targets.forEach(r -> r.setStatus("VISIBLE"));
                reviewRepository.saveAll(targets);
                reviewRepository.flush();
            }
            case "DELETE" -> {
                reviewRepository.deleteAllInBatch(targets);
            }
            default -> throw new IllegalArgumentException("Unsupported bulk moderation action: " + action);
        }

        logModeration(adminUserId, "BULK_MODERATE_REVIEWS",
                normalizedAction + " applied to " + targets.size() + " review(s)");
        return targets.size();
    }

    private MovieReview requireReview(Long reviewId) {
        if (reviewId == null) {
            throw new IllegalArgumentException("Review ID is required.");
        }
        return reviewRepository.findById(reviewId)
                .orElseThrow(() -> new IllegalArgumentException("Comment / review not found with ID: " + reviewId));
    }

    private String normalizeStatus(String targetStatus) {
        if (targetStatus == null) {
            throw new IllegalArgumentException("Status is required.");
        }
        String normalized = targetStatus.trim().toUpperCase(Locale.ROOT);
        if (!"VISIBLE".equals(normalized) && !"HIDDEN".equals(normalized)) {
            throw new IllegalArgumentException("Review status must be VISIBLE or HIDDEN.");
        }
        return normalized;
    }

    private void logModeration(Long adminUserId, String actionType, String detail) {
        if (activityLogService == null) {
            return;
        }
        try {
            User admin = adminUserId != null ? userRepository.findById(adminUserId).orElse(null) : null;
            String email = admin != null ? admin.getEmail() : "admin@boxcinema.vn";
            activityLogService.logActivityAsync(
                    adminUserId,
                    email,
                    "ADMIN",
                    actionType,
                    "/admin/reviews",
                    detail,
                    "127.0.0.1",
                    "Admin Suite",
                    "Web Console"
            );
        } catch (Exception ignored) {
            // Non-fatal audit logging failure
        }
    }

    private AdminMovieReviewDto toDto(MovieReview r) {
        AdminMovieReviewDto dto = new AdminMovieReviewDto();
        dto.setReviewId(r.getReviewId());
        if (r.getMovie() != null) {
            dto.setMovieId(r.getMovie().getMovieId());
            dto.setMovieTitle(r.getMovie().getTitle());
            dto.setMoviePosterUrl(r.getMovie().getPosterUrl());
        }
        if (r.getUser() != null) {
            User u = r.getUser();
            dto.setUserId(u.getUserId());
            String name = (u.getFullName() != null && !u.getFullName().isBlank()) ? u.getFullName().trim() : "Customer";
            dto.setReviewerName(name);
            dto.setReviewerInitial(name.substring(0, 1).toUpperCase(Locale.ENGLISH));
            dto.setReviewerEmail(u.getEmail());
            dto.setReviewerAvatarUrl(u.getAvatarUrl());
        } else {
            dto.setReviewerName("Customer");
            dto.setReviewerInitial("C");
        }
        dto.setRating(r.getRating() != null ? r.getRating() : 5);
        dto.setComment(r.getComment());
        dto.setVerifiedViewer(r.isVerifiedViewer());
        dto.setStatus(r.getStatus() != null ? r.getStatus() : "VISIBLE");
        dto.setAdminReply(r.getAdminReply());
        dto.setAdminRepliedAt(r.getAdminRepliedAt());
        if (r.getRepliedByAdmin() != null) {
            dto.setRepliedByName(r.getRepliedByAdmin().getFullName());
        }
        dto.setCreatedAt(r.getCreatedAt());
        return dto;
    }
}
