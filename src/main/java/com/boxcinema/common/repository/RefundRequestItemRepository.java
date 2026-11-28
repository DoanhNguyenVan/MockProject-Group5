package com.boxcinema.common.repository;

import com.boxcinema.common.entity.RefundRequestItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefundRequestItemRepository extends JpaRepository<RefundRequestItem, Long> {
    List<RefundRequestItem> findByRefundRequest_IdOrderByIdAsc(Long refundRequestId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT rri FROM RefundRequestItem rri WHERE rri.refundRequest.id = :refundRequestId ORDER BY rri.id ASC")
    List<RefundRequestItem> findByRefundRequestIdForUpdate(@Param("refundRequestId") Long refundRequestId);

    Optional<RefundRequestItem> findByActiveLockKey(String activeLockKey);

    @Query("""
            SELECT COALESCE(SUM(rri.requestedQuantity), 0)
            FROM RefundRequestItem rri
            WHERE rri.bookingFoodItemId = :bfiId
              AND rri.itemKind = 'FOOD_ITEM'
              AND rri.itemStatus IN ('PENDING', 'MORE_INFO_REQUIRED')
            """)
    int sumPendingFoodQty(@Param("bfiId") Long bookingFoodItemId);

    @Query("""
            SELECT COALESCE(SUM(rri.requestedQuantity), 0)
            FROM RefundRequestItem rri
            WHERE rri.bookingFoodItemComponentId = :compId
              AND rri.itemKind = 'COMBO_COMPONENT'
              AND rri.itemStatus IN ('PENDING', 'MORE_INFO_REQUIRED')
            """)
    int sumPendingComponentQty(@Param("compId") Long componentId);

    @Query("""
            SELECT COUNT(rri) > 0
            FROM RefundRequestItem rri
            WHERE rri.ticketId = :ticketId
              AND rri.itemStatus IN ('PENDING', 'MORE_INFO_REQUIRED', 'APPROVED')
            """)
    boolean existsActiveOrApprovedTicket(@Param("ticketId") Long ticketId);
}
