package com.boxcinema.staff.dto.request;

public class FoodFulfillmentStatusUpdateRequestDto {
    private String status; // PREPARING, READY, PICKED_UP, CANCELLED
    private String note;

    public FoodFulfillmentStatusUpdateRequestDto() {}

    public FoodFulfillmentStatusUpdateRequestDto(String status, String note) {
        this.status = status;
        this.note = note;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
