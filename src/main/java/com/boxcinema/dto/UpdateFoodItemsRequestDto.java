package com.boxcinema.dto;

import java.util.ArrayList;
import java.util.List;

public class UpdateFoodItemsRequestDto {
    private List<BookingFoodItemRequestDto> foodItems = new ArrayList<>();
    private List<BookingComboRequestDto> combos = new ArrayList<>();

    public UpdateFoodItemsRequestDto() {}

    public List<BookingFoodItemRequestDto> getFoodItems() { return foodItems; }
    public void setFoodItems(List<BookingFoodItemRequestDto> foodItems) { this.foodItems = foodItems; }
    public List<BookingComboRequestDto> getCombos() { return combos; }
    public void setCombos(List<BookingComboRequestDto> combos) { this.combos = combos; }
}
