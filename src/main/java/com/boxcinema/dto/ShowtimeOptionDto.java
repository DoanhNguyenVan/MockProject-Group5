package com.boxcinema.dto;

import java.math.BigDecimal;

public class ShowtimeOptionDto {
    private Long showtimeId;
    private String timeDisplay;
    private String roomName;
    private String roomType;
    private BigDecimal basePrice;
    private String label;

    public ShowtimeOptionDto() {
    }

    public ShowtimeOptionDto(Long showtimeId, String timeDisplay, String roomName, String roomType, BigDecimal basePrice) {
        this.showtimeId = showtimeId;
        this.timeDisplay = timeDisplay;
        this.roomName = roomName;
        this.roomType = roomType;
        this.basePrice = basePrice;
        this.label = timeDisplay + " - " + roomType;
    }

    public Long getShowtimeId() {
        return showtimeId;
    }

    public void setShowtimeId(Long showtimeId) {
        this.showtimeId = showtimeId;
    }

    public String getTimeDisplay() {
        return timeDisplay;
    }

    public void setTimeDisplay(String timeDisplay) {
        this.timeDisplay = timeDisplay;
    }

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public String getRoomType() {
        return roomType;
    }

    public void setRoomType(String roomType) {
        this.roomType = roomType;
    }

    public BigDecimal getBasePrice() {
        return basePrice;
    }

    public void setBasePrice(BigDecimal basePrice) {
        this.basePrice = basePrice;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }
}

