package com.boxcinema.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class FoodFulfillmentDto {
    private Long id;
    private Long bookingId;
    private String bookingCode;
    private String guestName;
    private String guestPhone;
    private String movieTitle;
    private String cinemaName;
    private String status; // QUEUED, PREPARING, READY, PICKED_UP, CANCELLED
    private String fulfillmentType;
    private String pickupLocation;
    private String assignedStaffName;
    private LocalDateTime createdAt;
    private LocalDateTime preparingAt;
    private LocalDateTime readyAt;
    private LocalDateTime pickedUpAt;
    private String note;
    private List<BookingFoodItemDto> items = new ArrayList<>();

    public FoodFulfillmentDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }
    public String getBookingCode() { return bookingCode; }
    public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }
    public String getGuestName() { return guestName; }
    public void setGuestName(String guestName) { this.guestName = guestName; }
    public String getGuestPhone() { return guestPhone; }
    public void setGuestPhone(String guestPhone) { this.guestPhone = guestPhone; }
    public String getMovieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }
    public String getCinemaName() { return cinemaName; }
    public void setCinemaName(String cinemaName) { this.cinemaName = cinemaName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getFulfillmentType() { return fulfillmentType; }
    public void setFulfillmentType(String fulfillmentType) { this.fulfillmentType = fulfillmentType; }
    public String getPickupLocation() { return pickupLocation; }
    public void setPickupLocation(String pickupLocation) { this.pickupLocation = pickupLocation; }
    public String getAssignedStaffName() { return assignedStaffName; }
    public void setAssignedStaffName(String assignedStaffName) { this.assignedStaffName = assignedStaffName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getPreparingAt() { return preparingAt; }
    public void setPreparingAt(LocalDateTime preparingAt) { this.preparingAt = preparingAt; }
    public LocalDateTime getReadyAt() { return readyAt; }
    public void setReadyAt(LocalDateTime readyAt) { this.readyAt = readyAt; }
    public LocalDateTime getPickedUpAt() { return pickedUpAt; }
    public void setPickedUpAt(LocalDateTime pickedUpAt) { this.pickedUpAt = pickedUpAt; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public List<BookingFoodItemDto> getItems() { return items; }
    public void setItems(List<BookingFoodItemDto> items) { this.items = items; }
}
