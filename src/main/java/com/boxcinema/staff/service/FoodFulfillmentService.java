package com.boxcinema.staff.service;

import com.boxcinema.refund.dto.StaffFoodActionRequestDto;
import com.boxcinema.staff.dto.response.FoodFulfillmentDto;
import java.util.List;

public interface FoodFulfillmentService {
    List<FoodFulfillmentDto> getOrders(String status);
    List<FoodFulfillmentDto> getOrdersForActor(String status, Long actorUserId);
    FoodFulfillmentDto getOrderById(Long id);
    FoodFulfillmentDto getOrderByIdForActor(Long id, Long actorUserId);
    FoodFulfillmentDto scanOrder(String qrOrCode, Long staffUserId);
    FoodFulfillmentDto updateStatus(Long id, String newStatus, String note, Long staffUserId);
    FoodFulfillmentDto recordDeliveryAction(Long id, StaffFoodActionRequestDto request, Long staffUserId);
}
