package com.boxcinema.common.repository;

import com.boxcinema.common.entity.StaffErrorLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface StaffErrorLogRepository extends JpaRepository<StaffErrorLog, Long> {
    @Query("""
            SELECT sel FROM StaffErrorLog sel
            WHERE (:cinemaId IS NULL OR sel.cinema.cinemaId = :cinemaId)
              AND (:staffUserId IS NULL OR sel.staffUser.userId = :staffUserId)
            ORDER BY sel.createdAt DESC
            """)
    Page<StaffErrorLog> findFiltered(@Param("cinemaId") Long cinemaId,
                                     @Param("staffUserId") Long staffUserId,
                                     Pageable pageable);
}
