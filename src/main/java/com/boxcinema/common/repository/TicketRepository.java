package com.boxcinema.common.repository;

import com.boxcinema.common.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {
    List<Ticket> findByBooking_BookingId(Long bookingId);
    Optional<Ticket> findByTicketCode(String ticketCode);
    Optional<Ticket> findByQrTokenHash(String qrTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Ticket t WHERE t.booking.bookingId = :bookingId")
    List<Ticket> findByBookingIdForUpdate(@Param("bookingId") Long bookingId);
}
