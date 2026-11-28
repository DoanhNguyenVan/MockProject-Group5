package com.boxcinema.staff.dto.response;

import com.boxcinema.common.dto.BookingFoodItemDto;

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
    private Long cinemaId;
    private String cinemaName;
    private String status; // QUEUED, PREPARING, READY, PICKED_UP, PARTIAL_DELIVERED, ISSUE_REPORTED, CANCELLED
    private String fulfillmentType;
    private String pickupLocation;
    private String assignedStaffName;
    private LocalDateTime createdAt;
    private LocalDateTime preparingAt;
    private LocalDateTime readyAt;
    private LocalDateTime pickedUpAt;
    private String note;
    private List<BookingFoodItemDto> items = new ArrayList<>();
    private List<HistoryEntryDto> history = new ArrayList<>();

    public static class HistoryEntryDto {
        private Long id;
        private String actionType;
        private String previousStatus;
        private String newStatus;
        private int quantityAffected;
        private String deliveredItemName;
        private String wrongItemDescription;
        private String replacementDescription;
        private Boolean customerAcceptedReplacement;
        private String staffName;
        private String note;
        private LocalDateTime createdAt;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getActionType() { return actionType; }
        public void setActionType(String actionType) { this.actionType = actionType; }
        public String getPreviousStatus() { return previousStatus; }
        public void setPreviousStatus(String previousStatus) { this.previousStatus = previousStatus; }
        public String getNewStatus() { return newStatus; }
        public void setNewStatus(String newStatus) { this.newStatus = newStatus; }
        public int getQuantityAffected() { return quantityAffected; }
        public void setQuantityAffected(int quantityAffected) { this.quantityAffected = quantityAffected; }
        public String getDeliveredItemName() { return deliveredItemName; }
        public void setDeliveredItemName(String deliveredItemName) { this.deliveredItemName = deliveredItemName; }
        public String getWrongItemDescription() { return wrongItemDescription; }
        public void setWrongItemDescription(String wrongItemDescription) { this.wrongItemDescription = wrongItemDescription; }
        public String getReplacementDescription() { return replacementDescription; }
        public void setReplacementDescription(String replacementDescription) { this.replacementDescription = replacementDescription; }
        public Boolean getCustomerAcceptedReplacement() { return customerAcceptedReplacement; }
        public void setCustomerAcceptedReplacement(Boolean customerAcceptedReplacement) { this.customerAcceptedReplacement = customerAcceptedReplacement; }
        public String getStaffName() { return staffName; }
        public void setStaffName(String staffName) { this.staffName = staffName; }
        public String getNote() { return note; }
        public void setNote(String note) { this.note = note; }
        public LocalDateTime getCreatedAt() { return createdAt; }
        public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    }

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
    public Long getCinemaId() { return cinemaId; }
    public void setCinemaId(Long cinemaId) { this.cinemaId = cinemaId; }
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
    public List<HistoryEntryDto> getHistory() { return history; }
    public void setHistory(List<HistoryEntryDto> history) { this.history = history; }
}
