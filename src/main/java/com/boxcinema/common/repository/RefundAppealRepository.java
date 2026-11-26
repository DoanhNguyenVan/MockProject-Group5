package com.boxcinema.common.repository;

import com.boxcinema.common.entity.RefundAppeal;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefundAppealRepository extends JpaRepository<RefundAppeal, Long> {

    Optional<RefundAppeal> findByAppealCode(String appealCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM RefundAppeal a JOIN FETCH a.refundRequest r JOIN FETCH r.cinema WHERE a.id = :id")
    Optional<RefundAppeal> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT a FROM RefundAppeal a JOIN FETCH a.refundRequest r JOIN FETCH r.cinema " +
           "WHERE r.id = :refundRequestId ORDER BY a.submittedAt DESC, a.id DESC")
    List<RefundAppeal> findByRefundRequestIdOrderBySubmittedAtDesc(@Param("refundRequestId") Long refundRequestId);

    Optional<RefundAppeal> findByActiveAppealLock(Long activeAppealLock);

    @Query("SELECT a FROM RefundAppeal a JOIN FETCH a.refundRequest r JOIN FETCH r.cinema " +
           "WHERE (:cinemaId IS NULL OR r.cinema.cinemaId = :cinemaId) " +
           "AND (:status IS NULL OR a.status = :status) " +
           "ORDER BY a.submittedAt DESC, a.id DESC")
    List<RefundAppeal> findAppealsForQueue(@Param("cinemaId") Long cinemaId,
                                           @Param("status") String status);
}
