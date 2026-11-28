package com.boxcinema.refund.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class OrderLookupResponseDto {
    private Long bookingId;
    private String bookingCode;
    private String orderType;
    private String bookingStatus;
    private Long cinemaId;
    private String cinemaName;
    private String movieTitle;
    private String showtimeStart;
    private BigDecimal totalPaidAmount;
    private BigDecimal discountAmount;
    private String voucherCode;
    private boolean checkedIn;
    private String foodFulfillmentStatus;
    private LocalDateTime refundDeadlineAt;
    private String deadlineBasis;
    private boolean deadlineExceeded;
    private List<OrderItemOption> items = new ArrayList<>();

    public static class OrderItemOption {
        private String itemKind; // TICKET, FOOD_ITEM, COMBO_COMPONENT
        private Long targetId;
        private String label;
        private String parentLabel;
        private int purchasedQuantity;
        private int deliveredQuantity;
        private int replacementAcceptedQuantity;
        private int alreadyRefundedQuantity;
        private int activeRequestedQuantity;
        private int remainingRefundableQuantity;
        private BigDecimal grossAmount;
        private BigDecimal discountAllocated;
        private BigDecimal netPaidAmount;
        private String itemStatus;
        private boolean checkedIn;
        private boolean eligibleHint;
        private String eligibilityNote;

        public String getItemKind() { return itemKind; }
        public void setItemKind(String itemKind) { this.itemKind = itemKind; }
        public Long getTargetId() { return targetId; }
        public void setTargetId(Long targetId) { this.targetId = targetId; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        public String getParentLabel() { return parentLabel; }
        public void setParentLabel(String parentLabel) { this.parentLabel = parentLabel; }
        public int getPurchasedQuantity() { return purchasedQuantity; }
        public void setPurchasedQuantity(int purchasedQuantity) { this.purchasedQuantity = purchasedQuantity; }
        public int getDeliveredQuantity() { return deliveredQuantity; }
        public void setDeliveredQuantity(int deliveredQuantity) { this.deliveredQuantity = deliveredQuantity; }
        public int getReplacementAcceptedQuantity() { return replacementAcceptedQuantity; }
        public void setReplacementAcceptedQuantity(int replacementAcceptedQuantity) { this.replacementAcceptedQuantity = replacementAcceptedQuantity; }
        public int getAlreadyRefundedQuantity() { return alreadyRefundedQuantity; }
        public void setAlreadyRefundedQuantity(int alreadyRefundedQuantity) { this.alreadyRefundedQuantity = alreadyRefundedQuantity; }
        public int getActiveRequestedQuantity() { return activeRequestedQuantity; }
        public void setActiveRequestedQuantity(int activeRequestedQuantity) { this.activeRequestedQuantity = activeRequestedQuantity; }
        public int getRemainingRefundableQuantity() { return remainingRefundableQuantity; }
        public void setRemainingRefundableQuantity(int remainingRefundableQuantity) { this.remainingRefundableQuantity = remainingRefundableQuantity; }
        public BigDecimal getGrossAmount() { return grossAmount; }
        public void setGrossAmount(BigDecimal grossAmount) { this.grossAmount = grossAmount; }
        public BigDecimal getDiscountAllocated() { return discountAllocated; }
        public void setDiscountAllocated(BigDecimal discountAllocated) { this.discountAllocated = discountAllocated; }
        public BigDecimal getNetPaidAmount() { return netPaidAmount; }
        public void setNetPaidAmount(BigDecimal netPaidAmount) { this.netPaidAmount = netPaidAmount; }
        public String getItemStatus() { return itemStatus; }
        public void setItemStatus(String itemStatus) { this.itemStatus = itemStatus; }
        public boolean isCheckedIn() { return checkedIn; }
        public void setCheckedIn(boolean checkedIn) { this.checkedIn = checkedIn; }
        public boolean isEligibleHint() { return eligibleHint; }
        public void setEligibleHint(boolean eligibleHint) { this.eligibleHint = eligibleHint; }
        public String getEligibilityNote() { return eligibilityNote; }
        public void setEligibilityNote(String eligibilityNote) { this.eligibilityNote = eligibilityNote; }
    }

    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }
    public String getBookingCode() { return bookingCode; }
    public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }
    public String getOrderType() { return orderType; }
    public void setOrderType(String orderType) { this.orderType = orderType; }
    public String getBookingStatus() { return bookingStatus; }
    public void setBookingStatus(String bookingStatus) { this.bookingStatus = bookingStatus; }
    public Long getCinemaId() { return cinemaId; }
    public void setCinemaId(Long cinemaId) { this.cinemaId = cinemaId; }
    public String getCinemaName() { return cinemaName; }
    public void setCinemaName(String cinemaName) { this.cinemaName = cinemaName; }
    public String getMovieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }
    public String getShowtimeStart() { return showtimeStart; }
    public void setShowtimeStart(String showtimeStart) { this.showtimeStart = showtimeStart; }
    public BigDecimal getTotalPaidAmount() { return totalPaidAmount; }
    public void setTotalPaidAmount(BigDecimal totalPaidAmount) { this.totalPaidAmount = totalPaidAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public String getVoucherCode() { return voucherCode; }
    public void setVoucherCode(String voucherCode) { this.voucherCode = voucherCode; }
    public boolean isCheckedIn() { return checkedIn; }
    public void setCheckedIn(boolean checkedIn) { this.checkedIn = checkedIn; }
    public String getFoodFulfillmentStatus() { return foodFulfillmentStatus; }
    public void setFoodFulfillmentStatus(String foodFulfillmentStatus) { this.foodFulfillmentStatus = foodFulfillmentStatus; }
    public LocalDateTime getRefundDeadlineAt() { return refundDeadlineAt; }
    public void setRefundDeadlineAt(LocalDateTime refundDeadlineAt) { this.refundDeadlineAt = refundDeadlineAt; }
    public String getDeadlineBasis() { return deadlineBasis; }
    public void setDeadlineBasis(String deadlineBasis) { this.deadlineBasis = deadlineBasis; }
    public boolean isDeadlineExceeded() { return deadlineExceeded; }
    public void setDeadlineExceeded(boolean deadlineExceeded) { this.deadlineExceeded = deadlineExceeded; }
    public List<OrderItemOption> getItems() { return items; }
    public void setItems(List<OrderItemOption> items) { this.items = items; }
}
