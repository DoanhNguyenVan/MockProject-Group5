package com.boxcinema.controller;

import com.boxcinema.dto.FoodFulfillmentDto;
import com.boxcinema.dto.FoodFulfillmentStatusUpdateRequestDto;
import com.boxcinema.security.BoxCinemaUserDetails;
import com.boxcinema.service.FoodFulfillmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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

    private Long resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof BoxCinemaUserDetails details) {
            if (details.getUserSummary() != null && details.getUserSummary().getUserId() != null) {
                return details.getUserSummary().getUserId();
            }
        }
        return null;
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
        Long staffUserId = resolveCurrentUserId();
        FoodFulfillmentDto updated = foodFulfillmentService.updateStatus(
                id,
                request.getStatus(),
                request.getNote(),
                staffUserId
        );
        return ResponseEntity.ok(updated);
    }
}
