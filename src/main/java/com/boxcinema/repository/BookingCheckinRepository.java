package com.boxcinema.repository;

import com.boxcinema.entity.BookingCheckin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingCheckinRepository extends JpaRepository<BookingCheckin, Long> {
    Optional<BookingCheckin> findByBooking_BookingId(Long bookingId);
    List<BookingCheckin> findAllByOrderByCheckedInAtDesc();
}
