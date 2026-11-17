package com.boxcinema.service;

import com.boxcinema.dto.FoodFulfillmentDto;
import java.util.List;

public interface FoodFulfillmentService {
    List<FoodFulfillmentDto> getOrders(String status);
    FoodFulfillmentDto getOrderById(Long id);
    FoodFulfillmentDto updateStatus(Long id, String newStatus, String note, Long staffUserId);
}
