package com.boxcinema.common.repository;

import com.boxcinema.common.entity.BookingFoodItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingFoodItemRepository extends JpaRepository<BookingFoodItem, Long> {
    List<BookingFoodItem> findByBooking_BookingId(Long bookingId);
    void deleteByBooking_BookingId(Long bookingId);
}
