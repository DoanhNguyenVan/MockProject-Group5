package com.boxcinema.admin.dto;

public class AdminTopRatedMovieDto {
    private int rank;
    private Long movieId;
    private String title;
    private String posterUrl;
    private String bannerUrl;
    private double averageRating;
    private long reviewCount;

    public AdminTopRatedMovieDto() {
    }

    public AdminTopRatedMovieDto(
            int rank,
            Long movieId,
            String title,
            String posterUrl,
            String bannerUrl,
            double averageRating,
            long reviewCount
    ) {
        this.rank = rank;
        this.movieId = movieId;
        this.title = title;
        this.posterUrl = posterUrl;
        this.bannerUrl = bannerUrl;
        this.averageRating = averageRating;
        this.reviewCount = reviewCount;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public Long getMovieId() {
        return movieId;
    }

    public void setMovieId(Long movieId) {
        this.movieId = movieId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPosterUrl() {
        return posterUrl;
    }

    public void setPosterUrl(String posterUrl) {
        this.posterUrl = posterUrl;
    }

    public String getBannerUrl() {
        return bannerUrl;
    }

    public void setBannerUrl(String bannerUrl) {
        this.bannerUrl = bannerUrl;
    }

    public double getAverageRating() {
        return averageRating;
    }

    public void setAverageRating(double averageRating) {
        this.averageRating = averageRating;
    }

    public long getReviewCount() {
        return reviewCount;
    }

    public void setReviewCount(long reviewCount) {
        this.reviewCount = reviewCount;
    }
}
