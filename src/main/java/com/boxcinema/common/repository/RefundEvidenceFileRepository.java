package com.boxcinema.common.repository;

import com.boxcinema.common.entity.RefundEvidenceFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RefundEvidenceFileRepository extends JpaRepository<RefundEvidenceFile, Long> {

    Optional<RefundEvidenceFile> findByStorageName(String storageName);

    Optional<RefundEvidenceFile> findByStorageNameIgnoreCase(String storageName);

    List<RefundEvidenceFile> findByRefundRequestIdOrderByCreatedAtAsc(Long refundRequestId);

    List<RefundEvidenceFile> findByIncidentReportIdOrderByCreatedAtAsc(Long incidentReportId);

    List<RefundEvidenceFile> findByUploadedByUserIdOrderByCreatedAtDesc(Long uploadedByUserId);

    List<RefundEvidenceFile> findBySha256Hash(String sha256Hash);

    long countByRefundRequestId(Long refundRequestId);

    long countByIncidentReportId(Long incidentReportId);
}
