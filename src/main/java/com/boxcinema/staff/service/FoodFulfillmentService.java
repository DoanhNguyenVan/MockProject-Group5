package com.boxcinema.staff.service;

import com.boxcinema.staff.dto.response.FoodFulfillmentDto;
import java.util.List;

public interface FoodFulfillmentService {
    List<FoodFulfillmentDto> getOrders(String status);
    FoodFulfillmentDto getOrderById(Long id);
    FoodFulfillmentDto updateStatus(Long id, String newStatus, String note, Long staffUserId);
}
