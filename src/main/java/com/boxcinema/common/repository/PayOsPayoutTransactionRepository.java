package com.boxcinema.common.repository;

import com.boxcinema.common.entity.PayOsPayoutTransaction;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PayOsPayoutTransactionRepository extends JpaRepository<PayOsPayoutTransaction, Long> {
    Optional<PayOsPayoutTransaction> findByReferenceId(String referenceId);
    Optional<PayOsPayoutTransaction> findByPayosPayoutId(String payosPayoutId);
    List<PayOsPayoutTransaction> findByRefundRequest_IdOrderByAttemptCountDesc(Long refundRequestId);
    Optional<PayOsPayoutTransaction> findTopByRefundRequest_IdOrderByAttemptCountDesc(Long refundRequestId);
    Optional<PayOsPayoutTransaction> findByActiveRequestLock(Long refundRequestId);
    List<PayOsPayoutTransaction> findByStatusOrderByInitiatedAtAsc(String status);
    List<PayOsPayoutTransaction> findByStatusInOrderByInitiatedAtAsc(Collection<String> statuses);
    List<PayOsPayoutTransaction> findByBatchCodeOrderByIdAsc(String batchCode);

    @Query("""
            SELECT p FROM PayOsPayoutTransaction p
            WHERE (:status IS NULL OR p.status = :status)
              AND (:batchCode IS NULL OR LOWER(COALESCE(p.batchCode, '')) LIKE LOWER(CONCAT('%', :batchCode, '%')))
              AND (:fromDate IS NULL OR p.initiatedAt >= :fromDate)
              AND (:toDate IS NULL OR p.initiatedAt <= :toDate)
              AND (:search IS NULL
                   OR LOWER(p.referenceId) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(COALESCE(p.payosPayoutId, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(COALESCE(p.batchCode, '')) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(p.refundRequest.requestCode) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(p.refundRequest.booking.bookingCode) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(COALESCE(p.toAccountHolderName, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            ORDER BY p.initiatedAt DESC, p.id DESC
            """)
    Page<PayOsPayoutTransaction> searchPayoutTransactions(@Param("status") String status,
                                                          @Param("batchCode") String batchCode,
                                                          @Param("search") String search,
                                                          @Param("fromDate") LocalDateTime fromDate,
                                                          @Param("toDate") LocalDateTime toDate,
                                                          Pageable pageable);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM PayOsPayoutTransaction p " +
           "WHERE p.refundRequest.booking.bookingId = :bookingId " +
           "AND p.status IN ('PENDING', 'PROCESSING', 'SUCCEEDED')")
    long sumCommittedOrInFlightAmountByBookingId(@Param("bookingId") Long bookingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PayOsPayoutTransaction p WHERE p.id = :id")
    Optional<PayOsPayoutTransaction> findByIdForUpdate(@Param("id") Long id);
}
