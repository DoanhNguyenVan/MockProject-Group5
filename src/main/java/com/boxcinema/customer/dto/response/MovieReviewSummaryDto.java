package com.boxcinema.customer.dto.response;

import java.util.List;
import java.util.Map;

public class MovieReviewSummaryDto {
    private Long movieId;
    private double averageRating;
    private int totalReviews;
    private int totalRatings;
    private int totalDiscussions;
    private Map<Integer, Integer> ratingCounts;
    private boolean authenticated;
    private boolean canReview;
    private boolean verifiedViewer;
    private MovieReviewItemDto myReview;
    private List<MovieReviewItemDto> reviews;

    public MovieReviewSummaryDto() {
    }

    public Long getMovieId() {
        return movieId;
    }

    public void setMovieId(Long movieId) {
        this.movieId = movieId;
    }

    public double getAverageRating() {
        return averageRating;
    }

    public void setAverageRating(double averageRating) {
        this.averageRating = averageRating;
    }

    public int getTotalReviews() {
        return totalReviews;
    }

    public void setTotalReviews(int totalReviews) {
        this.totalReviews = totalReviews;
    }

    public int getTotalRatings() {
        return totalRatings;
    }

    public void setTotalRatings(int totalRatings) {
        this.totalRatings = totalRatings;
    }

    public int getTotalDiscussions() {
        return totalDiscussions;
    }

    public void setTotalDiscussions(int totalDiscussions) {
        this.totalDiscussions = totalDiscussions;
    }

    public Map<Integer, Integer> getRatingCounts() {
        return ratingCounts;
    }

    public void setRatingCounts(Map<Integer, Integer> ratingCounts) {
        this.ratingCounts = ratingCounts;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    public void setAuthenticated(boolean authenticated) {
        this.authenticated = authenticated;
    }

    public boolean isCanReview() {
        return canReview;
    }

    public void setCanReview(boolean canReview) {
        this.canReview = canReview;
    }

    public boolean isVerifiedViewer() {
        return verifiedViewer;
    }

    public void setVerifiedViewer(boolean verifiedViewer) {
        this.verifiedViewer = verifiedViewer;
    }

    public MovieReviewItemDto getMyReview() {
        return myReview;
    }

    public void setMyReview(MovieReviewItemDto myReview) {
        this.myReview = myReview;
    }

    public List<MovieReviewItemDto> getReviews() {
        return reviews;
    }

    public void setReviews(List<MovieReviewItemDto> reviews) {
        this.reviews = reviews;
    }
}
