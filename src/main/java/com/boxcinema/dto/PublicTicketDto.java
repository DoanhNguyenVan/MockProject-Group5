package com.boxcinema.dto;

import java.util.ArrayList;
import java.util.List;

public class PublicTicketDto {
    private boolean valid;
    private String message;
    private String bookingCode;
    private String paymentStatus;
    private String bookingStatus;
    private String movieTitle;
    private String moviePosterUrl;
    private String cinemaName;
    private String roomName;
    private String showDate;
    private String showTime;
    private List<String> seats = new ArrayList<>();
    private int ticketCount;
    private List<PublicFoodItemDto> foodItems = new ArrayList<>();
    private String foodFulfillmentStatus;

    public PublicTicketDto() {
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
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

    public String getSeatsFormatted() {
        if (seats == null || seats.isEmpty()) {
            return "Chưa chọn ghế";
        }
        return String.join(", ", seats);
    }
}
