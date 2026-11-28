package com.boxcinema.common.repository;

import com.boxcinema.common.entity.IncidentReport;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IncidentReportRepository extends JpaRepository<IncidentReport, Long> {
    Optional<IncidentReport> findByIncidentCode(String incidentCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ir FROM IncidentReport ir WHERE ir.id = :id")
    Optional<IncidentReport> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            SELECT ir FROM IncidentReport ir
            WHERE (:cinemaId IS NULL OR ir.cinema.cinemaId = :cinemaId)
              AND (:status IS NULL OR ir.status = :status)
              AND (:incidentGroup IS NULL OR ir.incidentGroup = :incidentGroup)
            ORDER BY ir.createdAt DESC
            """)
    Page<IncidentReport> searchIncidents(@Param("cinemaId") Long cinemaId,
                                         @Param("status") String status,
                                         @Param("incidentGroup") String incidentGroup,
                                         Pageable pageable);

    List<IncidentReport> findByBooking_BookingIdAndStatus(Long bookingId, String status);
    List<IncidentReport> findByShowtime_ShowtimeIdAndStatus(Long showtimeId, String status);
    List<IncidentReport> findByCinema_CinemaIdOrderByCreatedAtDesc(Long cinemaId);
    List<IncidentReport> findByReportedByStaff_UserIdOrderByCreatedAtDesc(Long staffId);

    @Query("""
            SELECT COUNT(ir) > 0 FROM IncidentReport ir
            WHERE ir.status = 'VERIFIED'
              AND ir.incidentGroup = :group
              AND (ir.booking.bookingId = :bookingId OR (:showtimeId IS NOT NULL AND ir.showtime.showtimeId = :showtimeId))
            """)
    boolean existsVerifiedIncidentForBookingOrShowtime(@Param("group") String group,
                                                       @Param("bookingId") Long bookingId,
                                                       @Param("showtimeId") Long showtimeId);
}
