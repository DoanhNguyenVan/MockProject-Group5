package com.boxcinema.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class BookingHistoryDetailDto {
    private Long bookingId;
    private String bookingCode;
    private String movieTitle;
    private String cinemaName;
    private String showDate;
    private String showTime;
    private String roomName;
    private int seatCount;
    private String seatsText;
    private int comboCount;
    private String comboText;
    private BigDecimal totalAmount;
    private String totalAmountFormatted;
    private String rewardPointsFormatted;
    private String pointsExpiryDate;
    private String paymentMethod;
    private String paymentStatus;
    private String bookingStatus;
    private String checkinStatus;
    private String qrCodeDataUri;
    private LocalDateTime bookedAt;

    public BookingHistoryDetailDto() {}

    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }

    public String getBookingCode() { return bookingCode; }
    public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }

    public String getMovieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }

    public String getCinemaName() { return cinemaName; }
    public void setCinemaName(String cinemaName) { this.cinemaName = cinemaName; }

    public String getShowDate() { return showDate; }
    public void setShowDate(String showDate) { this.showDate = showDate; }

    public String getShowTime() { return showTime; }
    public void setShowTime(String showTime) { this.showTime = showTime; }

    public String getRoomName() { return roomName; }
    public void setRoomName(String roomName) { this.roomName = roomName; }

    public int getSeatCount() { return seatCount; }
    public void setSeatCount(int seatCount) { this.seatCount = seatCount; }

    public String getSeatsText() { return seatsText; }
    public void setSeatsText(String seatsText) { this.seatsText = seatsText; }

    public int getComboCount() { return comboCount; }
    public void setComboCount(int comboCount) { this.comboCount = comboCount; }

    public String getComboText() { return comboText; }
    public void setComboText(String comboText) { this.comboText = comboText; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public String getTotalAmountFormatted() { return totalAmountFormatted; }
    public void setTotalAmountFormatted(String totalAmountFormatted) { this.totalAmountFormatted = totalAmountFormatted; }

    public String getRewardPointsFormatted() { return rewardPointsFormatted; }
    public void setRewardPointsFormatted(String rewardPointsFormatted) { this.rewardPointsFormatted = rewardPointsFormatted; }

    public String getPointsExpiryDate() { return pointsExpiryDate; }
    public void setPointsExpiryDate(String pointsExpiryDate) { this.pointsExpiryDate = pointsExpiryDate; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }

    public String getBookingStatus() { return bookingStatus; }
    public void setBookingStatus(String bookingStatus) { this.bookingStatus = bookingStatus; }

    public String getCheckinStatus() { return checkinStatus; }
    public void setCheckinStatus(String checkinStatus) { this.checkinStatus = checkinStatus; }

    public String getQrCodeDataUri() { return qrCodeDataUri; }
    public void setQrCodeDataUri(String qrCodeDataUri) { this.qrCodeDataUri = qrCodeDataUri; }

    public LocalDateTime getBookedAt() { return bookedAt; }
    public void setBookedAt(LocalDateTime bookedAt) { this.bookedAt = bookedAt; }
}
