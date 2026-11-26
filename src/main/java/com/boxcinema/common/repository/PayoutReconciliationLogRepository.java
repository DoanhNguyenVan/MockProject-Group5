package com.boxcinema.common.repository;

import com.boxcinema.common.entity.PayoutReconciliationLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PayoutReconciliationLogRepository extends JpaRepository<PayoutReconciliationLog, Long> {
    List<PayoutReconciliationLog> findByPayoutTransactionIdOrderByCreatedAtDescIdDesc(Long payoutTransactionId);
    List<PayoutReconciliationLog> findByRefundRequestIdOrderByCreatedAtDescIdDesc(Long refundRequestId);
}
