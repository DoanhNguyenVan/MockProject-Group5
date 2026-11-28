package com.boxcinema.common.repository;

import com.boxcinema.common.entity.BookingFoodItemComponent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingFoodItemComponentRepository extends JpaRepository<BookingFoodItemComponent, Long> {
    List<BookingFoodItemComponent> findByBooking_BookingId(Long bookingId);
    List<BookingFoodItemComponent> findByBookingFoodItem_Id(Long bookingFoodItemId);
    void deleteByBooking_BookingId(Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM BookingFoodItemComponent c WHERE c.id = :id")
    Optional<BookingFoodItemComponent> findByIdForUpdate(@Param("id") Long id);
}
