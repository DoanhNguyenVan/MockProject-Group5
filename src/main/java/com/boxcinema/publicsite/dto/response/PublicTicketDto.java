package com.boxcinema.publicsite.dto.response;

import java.util.ArrayList;
import java.util.List;

public class PublicTicketDto {
    private boolean valid;
    private boolean confirmable;
    private String message;
    private String bookingCode;
    private String paymentStatus;
    private String bookingStatus;
    private String ticketStatus;
    private String statusReason;
    private String checkedInAt;
    private String movieTitle;
    private String moviePosterUrl;
    private String cinemaName;
    private String cinemaAddress;
    private String roomName;
    private String showDate;
    private String showTime;
    private List<String> seats = new ArrayList<>();
    private List<String> ticketCodes = new ArrayList<>();
    private int ticketCount;
    private List<PublicFoodItemDto> foodItems = new ArrayList<>();
    private String foodFulfillmentStatus;
    private Long bookingId;
    private Long showtimeId;
    private Long cinemaId;
    private boolean viewerIsStaff;
    private boolean canDisplayQrImage;
    private String buyerName;
    private String buyerEmail;
    private String buyerPhone;
    private String errorCode;

    public PublicTicketDto() {
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public boolean isConfirmable() {
        return confirmable;
    }

    public void setConfirmable(boolean confirmable) {
        this.confirmable = confirmable;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getBookingCode() {
        return bookingCode;
    }

    public void setBookingCode(String bookingCode) {
        this.bookingCode = bookingCode;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public String getBookingStatus() {
        return bookingStatus;
    }

    public void setBookingStatus(String bookingStatus) {
        this.bookingStatus = bookingStatus;
    }

    public String getTicketStatus() {
        return ticketStatus;
    }

    public void setTicketStatus(String ticketStatus) {
        this.ticketStatus = ticketStatus;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public void setStatusReason(String statusReason) {
        this.statusReason = statusReason;
    }

    public String getCheckedInAt() {
        return checkedInAt;
    }

    public void setCheckedInAt(String checkedInAt) {
        this.checkedInAt = checkedInAt;
    }

    public String getMovieTitle() {
        return movieTitle;
    }

    public void setMovieTitle(String movieTitle) {
        this.movieTitle = movieTitle;
    }

    public String getMoviePosterUrl() {
        return moviePosterUrl;
    }

    public void setMoviePosterUrl(String moviePosterUrl) {
        this.moviePosterUrl = moviePosterUrl;
    }

    public String getCinemaName() {
        return cinemaName;
    }

    public void setCinemaName(String cinemaName) {
        this.cinemaName = cinemaName;
    }

    public String getCinemaAddress() {
        return cinemaAddress;
    }

    public void setCinemaAddress(String cinemaAddress) {
        this.cinemaAddress = cinemaAddress;
    }

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public String getShowDate() {
        return showDate;
    }

    public void setShowDate(String showDate) {
        this.showDate = showDate;
    }

    public String getShowTime() {
        return showTime;
    }

    public void setShowTime(String showTime) {
        this.showTime = showTime;
    }

    public List<String> getSeats() {
        return seats;
    }

    public void setSeats(List<String> seats) {
        this.seats = seats;
    }

    public List<String> getTicketCodes() {
        return ticketCodes;
    }

    public void setTicketCodes(List<String> ticketCodes) {
        this.ticketCodes = ticketCodes;
    }

    public int getTicketCount() {
        return ticketCount;
    }

    public void setTicketCount(int ticketCount) {
        this.ticketCount = ticketCount;
    }

    public List<PublicFoodItemDto> getFoodItems() {
        return foodItems;
    }

    public void setFoodItems(List<PublicFoodItemDto> foodItems) {
        this.foodItems = foodItems;
    }

    public String getFoodFulfillmentStatus() {
        return foodFulfillmentStatus;
    }

    public void setFoodFulfillmentStatus(String foodFulfillmentStatus) {
        this.foodFulfillmentStatus = foodFulfillmentStatus;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public void setBookingId(Long bookingId) {
        this.bookingId = bookingId;
    }

    public Long getShowtimeId() {
        return showtimeId;
    }

    public void setShowtimeId(Long showtimeId) {
        this.showtimeId = showtimeId;
    }

    public Long getCinemaId() {
        return cinemaId;
    }

    public void setCinemaId(Long cinemaId) {
        this.cinemaId = cinemaId;
    }

    public boolean isViewerIsStaff() {
        return viewerIsStaff;
    }

    public void setViewerIsStaff(boolean viewerIsStaff) {
        this.viewerIsStaff = viewerIsStaff;
    }

    public boolean isCanDisplayQrImage() {
        return canDisplayQrImage;
    }

    public void setCanDisplayQrImage(boolean canDisplayQrImage) {
        this.canDisplayQrImage = canDisplayQrImage;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public void setBuyerName(String buyerName) {
        this.buyerName = buyerName;
    }

    public String getBuyerEmail() {
        return buyerEmail;
    }

    public void setBuyerEmail(String buyerEmail) {
        this.buyerEmail = buyerEmail;
    }

    public String getBuyerPhone() {
        return buyerPhone;
    }

    public void setBuyerPhone(String buyerPhone) {
        this.buyerPhone = buyerPhone;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public boolean isHasMovieTickets() {
        return seats != null && !seats.isEmpty();
    }

    public boolean isHasFoodItems() {
        return foodItems != null && !foodItems.isEmpty();
    }

    public String getSeatsFormatted() {
        if (seats == null || seats.isEmpty()) {
            return "No seats selected";
        }
        return String.join(", ", seats);
    }
}
