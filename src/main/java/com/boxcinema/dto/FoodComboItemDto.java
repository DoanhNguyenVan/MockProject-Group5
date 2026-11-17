package com.boxcinema.dto;

public class FoodComboItemDto {
    private Long id;
    private Long foodItemId;
    private String foodItemName;
    private Long variantId;
    private String variantName;
    private int quantity;

    public FoodComboItemDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFoodItemId() { return foodItemId; }
    public void setFoodItemId(Long foodItemId) { this.foodItemId = foodItemId; }
    public String getFoodItemName() { return foodItemName; }
    public void setFoodItemName(String foodItemName) { this.foodItemName = foodItemName; }
    public Long getVariantId() { return variantId; }
    public void setVariantId(Long variantId) { this.variantId = variantId; }
    public String getVariantName() { return variantName; }
    public void setVariantName(String variantName) { this.variantName = variantName; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
