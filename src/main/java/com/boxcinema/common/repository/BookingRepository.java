package com.boxcinema.common.repository;

import com.boxcinema.common.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    Optional<Booking> findByBookingCode(String bookingCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.bookingId = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    List<Booking> findByUser_UserIdOrderByBookedAtDesc(Long userId);

    long countByShowtime_ShowtimeId(Long showtimeId);

    @Query("SELECT COUNT(b) > 0 FROM Booking b " +
           "WHERE b.user.userId = :userId " +
           "AND b.showtime.movie.movieId = :movieId " +
           "AND b.bookingStatus = 'CONFIRMED'")
    boolean existsConfirmedBookingForMovie(@Param("userId") Long userId, @Param("movieId") Long movieId);

    @Query("SELECT b FROM Booking b " +
           "WHERE b.soldByStaff.userId = :staffId " +
           "AND b.bookingChannel = 'COUNTER' " +
           "AND b.bookingStatus = 'CONFIRMED' " +
           "AND (:q IS NULL OR :q = '' " +
           "  OR LOWER(b.bookingCode) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "  OR LOWER(b.guestPhone) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "  OR LOWER(b.guestName) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "  OR LOWER(b.guestEmail) LIKE LOWER(CONCAT('%', :q, '%'))) " +
           "ORDER BY b.bookedAt DESC")
    List<Booking> searchConfirmedForStaff(@Param("staffId") Long staffId, @Param("q") String query);

    @Query("SELECT b FROM Booking b " +
           "WHERE b.soldByStaff.userId = :staffId " +
           "AND b.bookingChannel = 'COUNTER' " +
           "AND b.bookingStatus = 'CONFIRMED' " +
           "AND b.bookedAt BETWEEN :start AND :end " +
           "ORDER BY b.bookedAt DESC")
    List<Booking> findBySoldByStaffAndDateRange(
            @Param("staffId") Long staffId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );
}
