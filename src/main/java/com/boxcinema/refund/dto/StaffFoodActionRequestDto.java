package com.boxcinema.refund.dto;

public class StaffFoodActionRequestDto {
    private Long bookingFoodItemId;
    private Long bookingFoodItemComponentId;
    private String actionType; // INITIAL_DELIVERY, MISSING_REDELIVERY, WRONG_ITEM_EXCHANGE, REPLACEMENT_CONFIRMED, CANNOT_FULFILL
    private int quantity = 1;
    private String wrongItemDescription;
    private String replacementDescription;
    private Boolean customerAcceptedReplacement;
    private String note;
    private String evidenceUrl;
    private Long staffErrorUserId;

    public Long getBookingFoodItemId() { return bookingFoodItemId; }
    public void setBookingFoodItemId(Long bookingFoodItemId) { this.bookingFoodItemId = bookingFoodItemId; }
    public Long getBookingFoodItemComponentId() { return bookingFoodItemComponentId; }
    public void setBookingFoodItemComponentId(Long bookingFoodItemComponentId) { this.bookingFoodItemComponentId = bookingFoodItemComponentId; }
    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getWrongItemDescription() { return wrongItemDescription; }
    public void setWrongItemDescription(String wrongItemDescription) { this.wrongItemDescription = wrongItemDescription; }
    public String getReplacementDescription() { return replacementDescription; }
    public void setReplacementDescription(String replacementDescription) { this.replacementDescription = replacementDescription; }
    public Boolean getCustomerAcceptedReplacement() { return customerAcceptedReplacement; }
    public void setCustomerAcceptedReplacement(Boolean customerAcceptedReplacement) { this.customerAcceptedReplacement = customerAcceptedReplacement; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getEvidenceUrl() { return evidenceUrl; }
    public void setEvidenceUrl(String evidenceUrl) { this.evidenceUrl = evidenceUrl; }
    public Long getStaffErrorUserId() { return staffErrorUserId; }
    public void setStaffErrorUserId(Long staffErrorUserId) { this.staffErrorUserId = staffErrorUserId; }
}
