package com.boxcinema.common.repository;

import com.boxcinema.common.entity.RefundCommunication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RefundCommunicationRepository extends JpaRepository<RefundCommunication, Long> {
    List<RefundCommunication> findByRefundRequest_IdOrderByCreatedAtAsc(Long refundRequestId);
}
