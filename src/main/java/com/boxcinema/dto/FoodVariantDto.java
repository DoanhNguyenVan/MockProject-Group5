package com.boxcinema.dto;

import java.math.BigDecimal;

public class FoodVariantDto {
    private Long id;
    private Long foodItemId;
    private String name;
    private BigDecimal price;
    private String status;

    public FoodVariantDto() {}

    public FoodVariantDto(Long id, Long foodItemId, String name, BigDecimal price, String status) {
        this.id = id;
        this.foodItemId = foodItemId;
        this.name = name;
        this.price = price;
        this.status = status;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFoodItemId() { return foodItemId; }
    public void setFoodItemId(Long foodItemId) { this.foodItemId = foodItemId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
