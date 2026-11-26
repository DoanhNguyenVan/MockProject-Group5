package com.boxcinema.common.repository;

import com.boxcinema.common.entity.RefundLimitReservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefundLimitReservationRepository extends JpaRepository<RefundLimitReservation, Long> {

    List<RefundLimitReservation> findByRefundRequestIdOrderByIdAsc(Long refundRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefundLimitReservation r WHERE r.refundRequestId = :refundRequestId ORDER BY r.id ASC")
    List<RefundLimitReservation> findByRefundRequestIdForUpdate(@Param("refundRequestId") Long refundRequestId);

    Optional<RefundLimitReservation> findByActiveLockKey(String activeLockKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RefundLimitReservation r WHERE r.activeLockKey = :activeLockKey")
    Optional<RefundLimitReservation> findByActiveLockKeyForUpdate(@Param("activeLockKey") String activeLockKey);

    @Query("SELECT COALESCE(SUM(r.amountVnd), 0) FROM RefundLimitReservation r " +
           "WHERE r.managerUserId = :managerId AND r.quotaDate = :quotaDate AND r.status = :status " +
           "AND r.activeLockKey LIKE 'REQ:%:MGR'")
    Long sumManagerAmountByDateAndStatus(@Param("managerId") Long managerId,
                                         @Param("quotaDate") LocalDate quotaDate,
                                         @Param("status") String status);

    @Query("SELECT COALESCE(SUM(r.amountVnd), 0) FROM RefundLimitReservation r " +
           "WHERE r.managerUserId = :managerId AND r.quotaDate = :quotaDate AND r.status = 'RELEASED' " +
           "AND r.incidentReportId IS NULL AND r.cinemaId IS NOT NULL")
    Long sumManagerReleasedByDate(@Param("managerId") Long managerId,
                                  @Param("quotaDate") LocalDate quotaDate);

    @Query("SELECT COALESCE(SUM(r.amountVnd), 0) FROM RefundLimitReservation r " +
           "WHERE r.cinemaId = :cinemaId AND r.quotaDate = :quotaDate AND r.status = :status " +
           "AND r.activeLockKey LIKE 'REQ:%:CIN'")
    Long sumCinemaAmountByDateAndStatus(@Param("cinemaId") Long cinemaId,
                                        @Param("quotaDate") LocalDate quotaDate,
                                        @Param("status") String status);

    @Query("SELECT COALESCE(SUM(r.amountVnd), 0) FROM RefundLimitReservation r " +
           "WHERE r.incidentReportId = :incidentId AND r.status = :status " +
           "AND r.activeLockKey LIKE 'REQ:%:INC'")
    Long sumIncidentAmountByStatus(@Param("incidentId") Long incidentId,
                                   @Param("status") String status);

    List<RefundLimitReservation> findByManagerUserIdAndQuotaDateOrderByIdAsc(Long managerUserId, LocalDate quotaDate);

    List<RefundLimitReservation> findByCinemaIdAndQuotaDateOrderByIdAsc(Long cinemaId, LocalDate quotaDate);

    List<RefundLimitReservation> findByIncidentReportIdOrderByIdAsc(Long incidentReportId);
}
