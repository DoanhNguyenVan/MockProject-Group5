package com.boxcinema.common.repository;

import com.boxcinema.common.entity.FoodFulfillmentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Append-only repository for immutable food delivery history.
 * Do not expose update/delete operations on FoodFulfillmentHistory.
 */
@Repository
public interface FoodFulfillmentHistoryRepository extends JpaRepository<FoodFulfillmentHistory, Long> {
    List<FoodFulfillmentHistory> findByFoodFulfillment_IdOrderByCreatedAtDesc(Long foodFulfillmentId);
    List<FoodFulfillmentHistory> findByBooking_BookingIdOrderByCreatedAtDesc(Long bookingId);
}
