package com.boxcinema.common.repository;

import com.boxcinema.common.entity.FoodFulfillment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface FoodFulfillmentRepository extends JpaRepository<FoodFulfillment, Long> {
    Optional<FoodFulfillment> findByBooking_BookingId(Long bookingId);

    List<FoodFulfillment> findByStatusOrderByCreatedAtAsc(String status);
    List<FoodFulfillment> findAllByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ff FROM FoodFulfillment ff WHERE ff.id = :id")
    Optional<FoodFulfillment> findByIdForUpdate(@Param("id") Long id);
}
