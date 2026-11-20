package com.boxcinema.common.repository;

import com.boxcinema.common.entity.FoodCombo;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FoodComboRepository extends JpaRepository<FoodCombo, Long> {
    List<FoodCombo> findByStatusAndIsAvailableTrueOrderByIdAsc(String status);

    @Query("SELECT fc FROM FoodCombo fc LEFT JOIN FETCH fc.comboItems ci LEFT JOIN FETCH ci.foodItem fi LEFT JOIN FETCH ci.variant v WHERE fc.status = 'ACTIVE' AND fc.isAvailable = true ORDER BY fc.id ASC")
    List<FoodCombo> findAllActiveWithItems();
}
