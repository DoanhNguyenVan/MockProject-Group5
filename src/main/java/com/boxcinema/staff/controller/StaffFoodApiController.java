package com.boxcinema.staff.controller;

import com.boxcinema.staff.dto.response.FoodFulfillmentDto;
import com.boxcinema.staff.dto.request.FoodFulfillmentStatusUpdateRequestDto;
import com.boxcinema.staff.service.FoodFulfillmentService;
import com.boxcinema.common.util.SecurityContextUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/staff/food-fulfillments")
@PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
public class StaffFoodApiController {

    private final FoodFulfillmentService foodFulfillmentService;

    public StaffFoodApiController(FoodFulfillmentService foodFulfillmentService) {
        this.foodFulfillmentService = foodFulfillmentService;
    }

    @GetMapping
    public ResponseEntity<List<FoodFulfillmentDto>> getOrders(@RequestParam(required = false) String status) {
        return ResponseEntity.ok(foodFulfillmentService.getOrders(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FoodFulfillmentDto> getOrderById(@PathVariable Long id) {
        return ResponseEntity.ok(foodFulfillmentService.getOrderById(id));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<FoodFulfillmentDto> updateStatus(
            @PathVariable Long id,
            @RequestBody FoodFulfillmentStatusUpdateRequestDto request
    ) {
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        FoodFulfillmentDto updated = foodFulfillmentService.updateStatus(
                id,
                request.getStatus(),
                request.getNote(),
                staffUserId
        );
        return ResponseEntity.ok(updated);
    }
}
