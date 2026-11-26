package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "refund_evidence_files")
public class RefundEvidenceFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "storage_name", nullable = false, unique = true, length = 120)
    private String storageName;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 80)
    private String contentType;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "sha256_hash", nullable = false, length = 64)
    private String sha256Hash;

    @Column(name = "scan_status", nullable = false, length = 30)
    private String scanStatus = "CLEAN";

    @Column(name = "scan_details", length = 255)
    private String scanDetails;

    @Column(name = "refund_request_id")
    private Long refundRequestId;

    @Column(name = "incident_report_id")
    private Long incidentReportId;

    @Column(name = "uploaded_by_user_id")
    private Long uploadedByUserId;

    @Column(name = "uploaded_by_email", length = 255)
    private String uploadedByEmail;

    @Column(name = "uploaded_by_ip", length = 64)
    private String uploadedByIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
        if (this.scanStatus == null || this.scanStatus.isBlank()) {
            this.scanStatus = "CLEAN";
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStorageName() { return storageName; }
    public void setStorageName(String storageName) { this.storageName = storageName; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Long getFileSizeBytes() { return fileSizeBytes; }
    public void setFileSizeBytes(Long fileSizeBytes) { this.fileSizeBytes = fileSizeBytes; }

    public String getSha256Hash() { return sha256Hash; }
    public void setSha256Hash(String sha256Hash) { this.sha256Hash = sha256Hash; }

    public String getScanStatus() { return scanStatus; }
    public void setScanStatus(String scanStatus) { this.scanStatus = scanStatus; }

    public String getScanDetails() { return scanDetails; }
    public void setScanDetails(String scanDetails) { this.scanDetails = scanDetails; }

    public Long getRefundRequestId() { return refundRequestId; }
    public void setRefundRequestId(Long refundRequestId) { this.refundRequestId = refundRequestId; }

    public Long getIncidentReportId() { return incidentReportId; }
    public void setIncidentReportId(Long incidentReportId) { this.incidentReportId = incidentReportId; }

    public Long getUploadedByUserId() { return uploadedByUserId; }
    public void setUploadedByUserId(Long uploadedByUserId) { this.uploadedByUserId = uploadedByUserId; }

    public String getUploadedByEmail() { return uploadedByEmail; }
    public void setUploadedByEmail(String uploadedByEmail) { this.uploadedByEmail = uploadedByEmail; }

    public String getUploadedByIp() { return uploadedByIp; }
    public void setUploadedByIp(String uploadedByIp) { this.uploadedByIp = uploadedByIp; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
