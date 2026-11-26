package com.boxcinema.common.repository;

import com.boxcinema.common.entity.PayoutFeeAssessmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PayoutFeeAssessmentRepository extends JpaRepository<PayoutFeeAssessmentEntity, Long> {
    List<PayoutFeeAssessmentEntity> findByRefundRequestIdOrderByAssessedAtDescIdDesc(Long refundRequestId);
    Optional<PayoutFeeAssessmentEntity> findTopByRefundRequestIdOrderByAssessedAtDescIdDesc(Long refundRequestId);
}
