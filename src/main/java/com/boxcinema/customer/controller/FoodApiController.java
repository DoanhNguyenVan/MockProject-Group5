package com.boxcinema.customer.controller;

import com.boxcinema.customer.dto.response.FoodCategoryDto;
import com.boxcinema.customer.dto.response.FoodComboDto;
import com.boxcinema.customer.dto.response.FoodItemDto;
import com.boxcinema.customer.service.FoodService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/food")
public class FoodApiController {

    private final FoodService foodService;

    public FoodApiController(FoodService foodService) {
        this.foodService = foodService;
    }

    @GetMapping("/categories")
    public ResponseEntity<List<FoodCategoryDto>> getCategories() {
        return ResponseEntity.ok(foodService.getActiveCategories());
    }

    @GetMapping("/items")
    public ResponseEntity<List<FoodItemDto>> getItems(@RequestParam(required = false) Long categoryId) {
        return ResponseEntity.ok(foodService.getActiveFoodItems(categoryId));
    }

    @GetMapping("/items/{id}")
    public ResponseEntity<FoodItemDto> getItemById(@PathVariable Long id) {
        return ResponseEntity.ok(foodService.getFoodItemById(id));
    }

    @GetMapping("/combos")
    public ResponseEntity<List<FoodComboDto>> getCombos() {
        return ResponseEntity.ok(foodService.getActiveCombos());
    }

    @GetMapping("/combos/{id}")
    public ResponseEntity<FoodComboDto> getComboById(@PathVariable Long id) {
        return ResponseEntity.ok(foodService.getComboById(id));
    }
}
