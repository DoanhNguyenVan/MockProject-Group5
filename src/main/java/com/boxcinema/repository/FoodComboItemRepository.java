package com.boxcinema.repository;

import com.boxcinema.entity.FoodComboItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FoodComboItemRepository extends JpaRepository<FoodComboItem, Long> {
    List<FoodComboItem> findByCombo_Id(Long comboId);
}
