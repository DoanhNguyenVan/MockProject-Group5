package com.boxcinema.customer.dto.response;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class FoodComboDto {
    private Long id;
    private String name;
    private String description;
    private BigDecimal price;
    private String imageUrl;
    private boolean isAvailable;
    private List<FoodComboItemDto> items = new ArrayList<>();

    public FoodComboDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    public boolean isAvailable() { return isAvailable; }
    public void setAvailable(boolean available) { isAvailable = available; }
    public List<FoodComboItemDto> getItems() { return items; }
    public void setItems(List<FoodComboItemDto> items) { this.items = items; }
}
