package com.boxcinema.common.repository;

import com.boxcinema.common.entity.FoodItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FoodItemRepository extends JpaRepository<FoodItem, Long> {
    List<FoodItem> findByStatusAndIsAvailableTrueOrderByIdAsc(String status);
    List<FoodItem> findByCategory_IdAndStatusAndIsAvailableTrueOrderByIdAsc(Long categoryId, String status);

    @Query("SELECT fi FROM FoodItem fi LEFT JOIN FETCH fi.variants v WHERE fi.status = 'ACTIVE' AND fi.isAvailable = true ORDER BY fi.id ASC")
    List<FoodItem> findAllActiveWithVariants();
}
