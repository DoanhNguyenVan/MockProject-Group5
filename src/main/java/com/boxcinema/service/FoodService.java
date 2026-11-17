package com.boxcinema.service;

import com.boxcinema.dto.*;
import java.util.List;

public interface FoodService {
    List<FoodCategoryDto> getActiveCategories();
    List<FoodItemDto> getActiveFoodItems(Long categoryId);
    FoodItemDto getFoodItemById(Long id);
    List<FoodComboDto> getActiveCombos();
    FoodComboDto getComboById(Long id);

    void checkAndReserveFoodStock(Long foodItemId, int quantity);
    void checkAndReserveComboStock(Long comboId, int comboQuantity);
    void commitFoodStock(Long foodItemId, int quantity);
    void commitComboStock(Long comboId, int comboQuantity);
    void releaseFoodStock(Long foodItemId, int quantity);
    void releaseComboStock(Long comboId, int comboQuantity);
}
