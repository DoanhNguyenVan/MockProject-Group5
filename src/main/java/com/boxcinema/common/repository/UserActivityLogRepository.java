package com.boxcinema.common.repository;

import com.boxcinema.common.entity.UserActivityLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface UserActivityLogRepository extends JpaRepository<UserActivityLog, Long> {

    @Query("SELECT l FROM UserActivityLog l WHERE " +
           "(:role IS NULL OR :role = '' OR l.role = :role) AND " +
           "(:actionType IS NULL OR :actionType = '' OR l.actionType = :actionType) AND " +
           "(:search IS NULL OR :search = '' OR LOWER(l.email) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(l.pageUrl) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(l.location) LIKE LOWER(CONCAT('%', :search, '%'))) AND " +
           "(:startDate IS NULL OR l.createdAt >= :startDate) AND " +
           "(:endDate IS NULL OR l.createdAt <= :endDate) " +
           "ORDER BY l.createdAt DESC")
    Page<UserActivityLog> findFilteredLogs(
            @Param("role") String role,
            @Param("actionType") String actionType,
            @Param("search") String search,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate,
            Pageable pageable
    );

    long countByRole(String role);

    long countByActionType(String actionType);
}
