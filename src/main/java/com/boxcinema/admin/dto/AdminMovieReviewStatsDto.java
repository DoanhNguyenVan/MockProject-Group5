package com.boxcinema.admin.dto;

public class AdminMovieReviewStatsDto {
    private long totalReviews;
    private long visibleReviews;
    private long hiddenReviews;
    private long verifiedViewers;
    private double averageRating;

    public AdminMovieReviewStatsDto() {
    }

    public AdminMovieReviewStatsDto(
            long totalReviews,
            long visibleReviews,
            long hiddenReviews,
            long verifiedViewers,
            double averageRating
    ) {
        this.totalReviews = totalReviews;
        this.visibleReviews = visibleReviews;
        this.hiddenReviews = hiddenReviews;
        this.verifiedViewers = verifiedViewers;
        this.averageRating = averageRating;
    }

    public long getTotalReviews() { return totalReviews; }
    public void setTotalReviews(long totalReviews) { this.totalReviews = totalReviews; }

    public long getVisibleReviews() { return visibleReviews; }
    public void setVisibleReviews(long visibleReviews) { this.visibleReviews = visibleReviews; }

    public long getHiddenReviews() { return hiddenReviews; }
    public void setHiddenReviews(long hiddenReviews) { this.hiddenReviews = hiddenReviews; }

    public long getVerifiedViewers() { return verifiedViewers; }
    public void setVerifiedViewers(long verifiedViewers) { this.verifiedViewers = verifiedViewers; }

    public double getAverageRating() { return averageRating; }
    public void setAverageRating(double averageRating) { this.averageRating = averageRating; }
}
