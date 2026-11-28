package com.boxcinema.common.repository;

import com.boxcinema.common.entity.BookingFoodItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingFoodItemRepository extends JpaRepository<BookingFoodItem, Long> {
    List<BookingFoodItem> findByBooking_BookingId(Long bookingId);
    void deleteByBooking_BookingId(Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT bfi FROM BookingFoodItem bfi WHERE bfi.id = :id")
    Optional<BookingFoodItem> findByIdForUpdate(@Param("id") Long id);
}
