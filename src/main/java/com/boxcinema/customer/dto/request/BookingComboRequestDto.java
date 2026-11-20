package com.boxcinema.customer.dto.request;

public class BookingComboRequestDto {
    private Long comboId;
    private int quantity;

    public BookingComboRequestDto() {}

    public BookingComboRequestDto(Long comboId, int quantity) {
        this.comboId = comboId;
        this.quantity = quantity;
    }

    public Long getComboId() { return comboId; }
    public void setComboId(Long comboId) { this.comboId = comboId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
