package com.boxcinema.repository;

import com.boxcinema.entity.FoodInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface FoodInventoryRepository extends JpaRepository<FoodInventory, Long> {
    Optional<FoodInventory> findByFoodItem_Id(Long foodItemId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT fi FROM FoodInventory fi WHERE fi.foodItem.id = :foodItemId")
    Optional<FoodInventory> findByFoodItemIdForUpdate(@Param("foodItemId") Long foodItemId);
}
