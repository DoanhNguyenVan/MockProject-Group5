package com.boxcinema.common.repository;

import com.boxcinema.common.entity.RefundAppealHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RefundAppealHistoryRepository extends JpaRepository<RefundAppealHistory, Long> {
    List<RefundAppealHistory> findByAppealIdOrderByCreatedAtAsc(Long appealId);
    List<RefundAppealHistory> findByRefundRequestIdOrderByCreatedAtAsc(Long refundRequestId);
}
