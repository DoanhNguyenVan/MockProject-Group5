package com.boxcinema.common.repository;

import com.boxcinema.common.entity.Ticket;
import com.boxcinema.common.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
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

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Ticket t SET t.ticketStatus = 'USED', t.checkedInAt = :now, t.usedAt = :now, t.checkedInBy = :staff " +
           "WHERE t.booking.bookingId = :bookingId AND UPPER(t.ticketStatus) = 'VALID'")
    int markBookingTicketsUsed(@Param("bookingId") Long bookingId, @Param("now") LocalDateTime now, @Param("staff") User staff);
}
