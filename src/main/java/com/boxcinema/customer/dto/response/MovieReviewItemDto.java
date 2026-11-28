package com.boxcinema.customer.dto.response;

import java.time.LocalDateTime;

public class MovieReviewItemDto {
    private Long reviewId;
    private Long userId;
    private String authorName;
    private String authorInitial;
    private String authorAvatarUrl;
    private Integer rating;
    private String comment;
    private boolean verifiedViewer;
    private boolean owner;
    private boolean canDelete;
    private boolean edited;
    private LocalDateTime createdAt;
    private String formattedDate;
    private String adminReply;
    private String formattedAdminRepliedAt;

    public MovieReviewItemDto() {
    }

    public Long getReviewId() {
        return reviewId;
    }

    public void setReviewId(Long reviewId) {
        this.reviewId = reviewId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getAuthorName() {
        return authorName;
    }

    public void setAuthorName(String authorName) {
        this.authorName = authorName;
    }

    public String getAuthorInitial() {
        return authorInitial;
    }

    public void setAuthorInitial(String authorInitial) {
        this.authorInitial = authorInitial;
    }

    public String getAuthorAvatarUrl() {
        return authorAvatarUrl;
    }

    public void setAuthorAvatarUrl(String authorAvatarUrl) {
        this.authorAvatarUrl = authorAvatarUrl;
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

    public boolean isVerifiedViewer() {
        return verifiedViewer;
    }

    public void setVerifiedViewer(boolean verifiedViewer) {
        this.verifiedViewer = verifiedViewer;
    }

    public boolean isOwner() {
        return owner;
    }

    public void setOwner(boolean owner) {
        this.owner = owner;
    }

    public boolean isCanDelete() {
        return canDelete;
    }

    public void setCanDelete(boolean canDelete) {
        this.canDelete = canDelete;
    }

    public boolean isEdited() {
        return edited;
    }

    public void setEdited(boolean edited) {
        this.edited = edited;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getFormattedDate() {
        return formattedDate;
    }

    public void setFormattedDate(String formattedDate) {
        this.formattedDate = formattedDate;
    }

    public String getAdminReply() {
        return adminReply;
    }

    public void setAdminReply(String adminReply) {
        this.adminReply = adminReply;
    }

    public String getFormattedAdminRepliedAt() {
        return formattedAdminRepliedAt;
    }

    public void setFormattedAdminRepliedAt(String formattedAdminRepliedAt) {
        this.formattedAdminRepliedAt = formattedAdminRepliedAt;
    }
}
