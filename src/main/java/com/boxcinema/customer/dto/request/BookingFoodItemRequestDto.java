package com.boxcinema.customer.dto.request;

public class BookingFoodItemRequestDto {
    private Long foodItemId;
    private Long variantId;
    private int quantity;

    public BookingFoodItemRequestDto() {}

    public BookingFoodItemRequestDto(Long foodItemId, Long variantId, int quantity) {
        this.foodItemId = foodItemId;
        this.variantId = variantId;
        this.quantity = quantity;
    }

    public Long getFoodItemId() { return foodItemId; }
    public void setFoodItemId(Long foodItemId) { this.foodItemId = foodItemId; }
    public Long getVariantId() { return variantId; }
    public void setVariantId(Long variantId) { this.variantId = variantId; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
