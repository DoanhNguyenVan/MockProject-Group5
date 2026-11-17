package com.boxcinema.repository;

import com.boxcinema.entity.FoodVariant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FoodVariantRepository extends JpaRepository<FoodVariant, Long> {
    List<FoodVariant> findByFoodItem_IdAndStatus(Long foodItemId, String status);
}
