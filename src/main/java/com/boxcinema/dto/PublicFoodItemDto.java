package com.boxcinema.dto;

public class PublicFoodItemDto {
    private String productName;
    private String variantName;
    private int quantity;
    private String status;

    public PublicFoodItemDto() {
    }

    public PublicFoodItemDto(String productName, String variantName, int quantity, String status) {
        this.productName = productName;
        this.variantName = variantName;
        this.quantity = quantity;
        this.status = status;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getVariantName() {
        return variantName;
    }

    public void setVariantName(String variantName) {
        this.variantName = variantName;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
