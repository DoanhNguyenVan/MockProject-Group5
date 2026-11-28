package com.boxcinema.common.repository;

import com.boxcinema.common.entity.IncidentVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IncidentVerificationRepository extends JpaRepository<IncidentVerification, Long> {
    List<IncidentVerification> findByIncidentReport_IdOrderByCreatedAtAsc(Long incidentReportId);
}
