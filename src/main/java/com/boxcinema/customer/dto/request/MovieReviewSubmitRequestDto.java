package com.boxcinema.customer.dto.request;

public class MovieReviewSubmitRequestDto {
    private Integer rating;
    private String comment;

    public MovieReviewSubmitRequestDto() {
    }

    public MovieReviewSubmitRequestDto(Integer rating, String comment) {
        this.rating = rating;
        this.comment = comment;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }
}
