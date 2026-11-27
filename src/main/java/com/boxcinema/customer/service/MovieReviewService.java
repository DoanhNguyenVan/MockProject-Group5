package com.boxcinema.customer.service;

import com.boxcinema.customer.dto.request.MovieReviewSubmitRequestDto;
import com.boxcinema.customer.dto.response.MovieReviewSummaryDto;

public interface MovieReviewService {
    MovieReviewSummaryDto getReviewsForMovie(Long movieId, Long currentUserId, boolean isAdmin, String sort);
    MovieReviewSummaryDto submitReview(Long movieId, Long currentUserId, boolean isAdmin, MovieReviewSubmitRequestDto request);
    MovieReviewSummaryDto deleteReview(Long movieId, Long reviewId, Long currentUserId, boolean isAdmin);
}
