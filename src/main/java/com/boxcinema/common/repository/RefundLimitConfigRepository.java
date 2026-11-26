package com.boxcinema.common.repository;

import com.boxcinema.common.entity.RefundLimitConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefundLimitConfigRepository extends JpaRepository<RefundLimitConfig, Long> {
    Optional<RefundLimitConfig> findByConfigKey(String configKey);
    List<RefundLimitConfig> findAllByOrderByConfigKeyAsc();
}
