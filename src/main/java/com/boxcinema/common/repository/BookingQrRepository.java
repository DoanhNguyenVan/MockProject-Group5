package com.boxcinema.common.repository;

import com.boxcinema.common.entity.BookingQr;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface BookingQrRepository extends JpaRepository<BookingQr, Long> {
    Optional<BookingQr> findByBooking_BookingId(Long bookingId);
    Optional<BookingQr> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM BookingQr q WHERE q.tokenHash = :tokenHash")
    Optional<BookingQr> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT q FROM BookingQr q WHERE q.booking.bookingId = :bookingId")
    Optional<BookingQr> findByBookingIdForUpdate(@Param("bookingId") Long bookingId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE BookingQr q SET q.status = 'USED', q.usedAt = :now WHERE q.id = :qrId AND UPPER(q.status) = 'ACTIVE'")
    int markUsedIfActive(@Param("qrId") Long qrId, @Param("now") LocalDateTime now);
}
