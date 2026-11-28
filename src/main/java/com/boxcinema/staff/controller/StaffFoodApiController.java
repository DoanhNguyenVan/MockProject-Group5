package com.boxcinema.staff.controller;

import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.refund.dto.StaffFoodActionRequestDto;
import com.boxcinema.staff.dto.request.FoodFulfillmentStatusUpdateRequestDto;
import com.boxcinema.staff.dto.response.FoodFulfillmentDto;
import com.boxcinema.staff.service.FoodFulfillmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

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
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(foodFulfillmentService.getOrdersForActor(status, staffUserId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<FoodFulfillmentDto> getOrderById(@PathVariable Long id) {
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(foodFulfillmentService.getOrderByIdForActor(id, staffUserId));
    }

    @PostMapping("/scan")
    public ResponseEntity<FoodFulfillmentDto> scanOrder(@RequestBody Map<String, String> payload) {
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        String code = payload != null ? payload.getOrDefault("qrPayload", payload.get("code")) : null;
        return ResponseEntity.ok(foodFulfillmentService.scanOrder(code, staffUserId));
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

    @PostMapping("/{id}/deliver")
    public ResponseEntity<FoodFulfillmentDto> recordDeliveryAction(
            @PathVariable Long id,
            @RequestBody StaffFoodActionRequestDto request
    ) {
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        return ResponseEntity.ok(foodFulfillmentService.recordDeliveryAction(id, request, staffUserId));
    }
}
