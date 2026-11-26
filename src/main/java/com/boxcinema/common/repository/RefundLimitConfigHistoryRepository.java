package com.boxcinema.common.repository;

import com.boxcinema.common.entity.RefundLimitConfigHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RefundLimitConfigHistoryRepository extends JpaRepository<RefundLimitConfigHistory, Long> {
    List<RefundLimitConfigHistory> findByConfigKeyOrderByCreatedAtDesc(String configKey);
    List<RefundLimitConfigHistory> findAllByOrderByCreatedAtDesc();
}
