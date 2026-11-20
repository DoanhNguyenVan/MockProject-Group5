package com.boxcinema.customer.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class BookingHistoryItemDto {
    private Long bookingId;
    private String bookingCode;
    private String movieTitle;
    private String moviePosterUrl;
    private String showDate;
    private String showTime;
    private String showDateTimeText;
    private String cinemaName;
    private BigDecimal totalAmount;
    private String totalAmountFormatted;
    private String rewardPointsFormatted;
    private String pointsExpiryDate;
    private String bookingStatus;
    private LocalDateTime bookedAt;

    public BookingHistoryItemDto() {}

    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }

    public String getBookingCode() { return bookingCode; }
    public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }

    public String getMovieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }

    public String getMoviePosterUrl() { return moviePosterUrl; }
    public void setMoviePosterUrl(String moviePosterUrl) { this.moviePosterUrl = moviePosterUrl; }

    public String getShowDate() { return showDate; }
    public void setShowDate(String showDate) { this.showDate = showDate; }

    public String getShowTime() { return showTime; }
    public void setShowTime(String showTime) { this.showTime = showTime; }

    public String getShowDateTimeText() { return showDateTimeText; }
    public void setShowDateTimeText(String showDateTimeText) { this.showDateTimeText = showDateTimeText; }

    public String getCinemaName() { return cinemaName; }
    public void setCinemaName(String cinemaName) { this.cinemaName = cinemaName; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public String getTotalAmountFormatted() { return totalAmountFormatted; }
    public void setTotalAmountFormatted(String totalAmountFormatted) { this.totalAmountFormatted = totalAmountFormatted; }

    public String getRewardPointsFormatted() { return rewardPointsFormatted; }
    public void setRewardPointsFormatted(String rewardPointsFormatted) { this.rewardPointsFormatted = rewardPointsFormatted; }

    public String getPointsExpiryDate() { return pointsExpiryDate; }
    public void setPointsExpiryDate(String pointsExpiryDate) { this.pointsExpiryDate = pointsExpiryDate; }

    public String getBookingStatus() { return bookingStatus; }
    public void setBookingStatus(String bookingStatus) { this.bookingStatus = bookingStatus; }

    public LocalDateTime getBookedAt() { return bookedAt; }
    public void setBookedAt(LocalDateTime bookedAt) { this.bookedAt = bookedAt; }
}
