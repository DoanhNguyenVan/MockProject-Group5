package com.boxcinema.dto;

import java.math.BigDecimal;

public class BookingFoodItemDto {
    private Long id;
    private String itemType;
    private Long foodItemId;
    private Long variantId;
    private Long comboId;
    private String productName;
    private String variantName;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;

    public BookingFoodItemDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public Long getFoodItemId() { return foodItemId; }
    public void setFoodItemId(Long foodItemId) { this.foodItemId = foodItemId; }
    public Long getVariantId() { return variantId; }
    public void setVariantId(Long variantId) { this.variantId = variantId; }
    public Long getComboId() { return comboId; }
    public void setComboId(Long comboId) { this.comboId = comboId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getVariantName() { return variantName; }
    public void setVariantName(String variantName) { this.variantName = variantName; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
}
