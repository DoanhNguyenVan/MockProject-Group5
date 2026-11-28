package com.boxcinema.common.repository;

import com.boxcinema.common.entity.VoucherRecovery;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface VoucherRecoveryRepository extends JpaRepository<VoucherRecovery, Long> {
    List<VoucherRecovery> findByRefundRequest_IdOrderByIdAsc(Long refundRequestId);
    List<VoucherRecovery> findByBooking_BookingIdOrderByIdAsc(Long bookingId);
    Optional<VoucherRecovery> findByActiveItemLock(Long activeItemLock);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT vr FROM VoucherRecovery vr WHERE vr.id = :id")
    Optional<VoucherRecovery> findByIdForUpdate(@Param("id") Long id);
}
