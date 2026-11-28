package com.boxcinema.common.repository;

import com.boxcinema.common.entity.BookingCheckin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingCheckinRepository extends JpaRepository<BookingCheckin, Long> {
    Optional<BookingCheckin> findByBooking_BookingId(Long bookingId);
    List<BookingCheckin> findAllByOrderByCheckedInAtDesc();

    @Query("""
            SELECT bc FROM BookingCheckin bc
            LEFT JOIN FETCH bc.booking b
            LEFT JOIN b.cinema c
            LEFT JOIN b.showtime st
            LEFT JOIN st.room r
            LEFT JOIN r.cinema sc
            WHERE c.cinemaId = :cinemaId
               OR sc.cinemaId = :cinemaId
            ORDER BY bc.checkedInAt DESC
            """)
    List<BookingCheckin> findByCinemaScopeOrderByCheckedInAtDesc(@Param("cinemaId") Long cinemaId);
}
