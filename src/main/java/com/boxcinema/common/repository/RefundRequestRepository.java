package com.boxcinema.common.repository;

import com.boxcinema.common.entity.RefundRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {
    Optional<RefundRequest> findByRequestCodeIgnoreCase(String requestCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rr FROM RefundRequest rr WHERE rr.id = :id")
    Optional<RefundRequest> findByIdForUpdate(@Param("id") Long id);

    List<RefundRequest> findByIdIn(Collection<Long> ids);

    @Query("""
            SELECT rr FROM RefundRequest rr
            WHERE (:cinemaId IS NULL OR rr.cinema.cinemaId = :cinemaId)
              AND (:status IS NULL OR rr.requestStatus = :status)
              AND (:payoutStatus IS NULL OR rr.payoutStatus = :payoutStatus)
              AND (:search IS NULL OR LOWER(rr.requestCode) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(rr.purchaseEmail) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(rr.submittedOrderCode) LIKE LOWER(CONCAT('%', :search, '%')))
            ORDER BY rr.createdAt DESC
            """)
    Page<RefundRequest> searchRefundRequests(@Param("cinemaId") Long cinemaId,
                                             @Param("status") String status,
                                             @Param("payoutStatus") String payoutStatus,
                                             @Param("search") String search,
                                             Pageable pageable);

    @Query("""
            SELECT rr FROM RefundRequest rr
            LEFT JOIN rr.reviewedBy rev
            WHERE (:cinemaId IS NULL OR rr.cinema.cinemaId = :cinemaId)
              AND (:applyStatuses = false OR rr.requestStatus IN :statuses)
              AND (:payoutStatus IS NULL OR rr.payoutStatus = :payoutStatus)
              AND (:reasonCategory IS NULL OR UPPER(rr.reasonCategory) = UPPER(:reasonCategory))
              AND (:verifierId IS NULL OR rev.userId = :verifierId)
              AND (:verifierSearch IS NULL
                   OR LOWER(COALESCE(rev.fullName, '')) LIKE LOWER(CONCAT('%', :verifierSearch, '%'))
                   OR LOWER(COALESCE(rev.email, '')) LIKE LOWER(CONCAT('%', :verifierSearch, '%')))
              AND (:minAmount IS NULL OR (CASE WHEN rr.approvedCashTotal > 0 THEN rr.approvedCashTotal ELSE rr.requestedCashTotal END) >= :minAmount)
              AND (:maxAmount IS NULL OR (CASE WHEN rr.approvedCashTotal > 0 THEN rr.approvedCashTotal ELSE rr.requestedCashTotal END) <= :maxAmount)
              AND (:fromDate IS NULL OR rr.createdAt >= :fromDate)
              AND (:toDate IS NULL OR rr.createdAt <= :toDate)
              AND (:search IS NULL
                   OR LOWER(rr.requestCode) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(rr.purchaseEmail) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(rr.submittedOrderCode) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(rr.booking.bookingCode) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(COALESCE(rr.customerName, '')) LIKE LOWER(CONCAT('%', :search, '%')))
            ORDER BY rr.createdAt DESC
            """)
    Page<RefundRequest> searchRefundRequestsFiltered(@Param("cinemaId") Long cinemaId,
                                                     @Param("applyStatuses") boolean applyStatuses,
                                                     @Param("statuses") Collection<String> statuses,
                                                     @Param("payoutStatus") String payoutStatus,
                                                     @Param("reasonCategory") String reasonCategory,
                                                     @Param("verifierId") Long verifierId,
                                                     @Param("verifierSearch") String verifierSearch,
                                                     @Param("minAmount") BigDecimal minAmount,
                                                     @Param("maxAmount") BigDecimal maxAmount,
                                                     @Param("fromDate") LocalDateTime fromDate,
                                                     @Param("toDate") LocalDateTime toDate,
                                                     @Param("search") String search,
                                                     Pageable pageable);

    List<RefundRequest> findByBooking_BookingIdOrderByCreatedAtDesc(Long bookingId);
}
