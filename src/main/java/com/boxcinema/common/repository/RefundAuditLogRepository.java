package com.boxcinema.common.repository;

import com.boxcinema.common.entity.RefundAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RefundAuditLogRepository extends JpaRepository<RefundAuditLog, Long> {
    List<RefundAuditLog> findByRefundRequestIdOrderByCreatedAtDesc(Long refundRequestId);
}
