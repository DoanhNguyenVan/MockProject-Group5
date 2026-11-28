package com.boxcinema.common.repository;

import com.boxcinema.common.entity.RefundApprovalHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RefundApprovalHistoryRepository extends JpaRepository<RefundApprovalHistory, Long> {
    List<RefundApprovalHistory> findByRefundRequest_IdOrderByCreatedAtAsc(Long refundRequestId);
}
