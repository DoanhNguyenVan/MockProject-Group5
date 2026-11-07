package com.boxcinema.dto;

public class MovieOptionDto {
    private Long movieId;
    private String title;
    private String ageRating;
    private Integer durationMinutes;

    public MovieOptionDto() {
    }

    public MovieOptionDto(Long movieId, String title, String ageRating, Integer durationMinutes) {
        this.movieId = movieId;
        this.title = title;
        this.ageRating = ageRating;
        this.durationMinutes = durationMinutes;
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

    public String getAgeRating() {
        return ageRating;
    }

    public void setAgeRating(String ageRating) {
        this.ageRating = ageRating;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }
}

