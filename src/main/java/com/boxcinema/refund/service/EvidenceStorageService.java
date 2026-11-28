package com.boxcinema.refund.service;

import com.boxcinema.common.entity.IncidentReport;
import com.boxcinema.common.entity.RefundAuditLog;
import com.boxcinema.common.entity.RefundEvidenceFile;
import com.boxcinema.common.entity.RefundRequest;
import com.boxcinema.common.entity.User;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.IncidentReportRepository;
import com.boxcinema.common.repository.RefundAuditLogRepository;
import com.boxcinema.common.repository.RefundEvidenceFileRepository;
import com.boxcinema.common.repository.RefundRequestRepository;
import com.boxcinema.common.repository.UserRepository;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.common.util.SecurityContextUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

/**
 * Stores uploaded evidence images outside the classpath webroot with strict MIME/extension/magic-byte
 * allowlisting, double-extension & path-traversal rejection, synchronous deep content malware/polyglot
 * scanning ({@link EvidenceMalwareScanner}), quarantine isolation, authenticated role/scope/owner-checked
 * download, and audit logging.
 */
@Service
public class EvidenceStorageService {

    private static final long DEFAULT_MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L;
    private static final int DEFAULT_MAX_FILES_PER_REQUEST = 5;
    private static final long DEFAULT_MAX_TOTAL_SIZE_BYTES = 15L * 1024L * 1024L;

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp");
    private static final Map<String, String> CONTENT_TYPE_TO_EXT = Map.of(
            "image/jpeg", "jpg",
            "image/jpg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    public record StoredEvidenceDto(
            Long id,
            String storageName,
            String originalFilename,
            String contentType,
            long fileSizeBytes,
            String sha256Hash,
            String scanStatus,
            String scanDetails,
            String url,
            String publicUrl
    ) {}

    private final Path uploadRoot;
    private final Path quarantineRoot;

    @Value("${app.refund.evidence.max-file-size-bytes:5242880}")
    private long maxFileSizeBytes = DEFAULT_MAX_FILE_SIZE_BYTES;

    @Value("${app.refund.evidence.max-files-per-request:5}")
    private int maxFilesPerRequest = DEFAULT_MAX_FILES_PER_REQUEST;

    @Value("${app.refund.evidence.max-total-size-bytes:15728640}")
    private long maxTotalSizeBytes = DEFAULT_MAX_TOTAL_SIZE_BYTES;

    private final RefundEvidenceFileRepository evidenceFileRepository;
    private final EvidenceMalwareScanner malwareScanner;
    private final RefundRequestRepository refundRequestRepository;
    private final IncidentReportRepository incidentReportRepository;
    private final RefundAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate requiresNewTx;

    @PersistenceContext
    private EntityManager entityManager;

    public EvidenceStorageService(String evidenceDir) {
        this(evidenceDir, evidenceDir + "-quarantine", null, new EvidenceMalwareScanner(), null, null, null, null, null);
    }

    @Autowired
    public EvidenceStorageService(
            @Value("${app.refund.evidence-dir:uploads/refund-evidence}") String evidenceDir,
            @Value("${app.refund.evidence-quarantine-dir:uploads/refund-evidence-quarantine}") String quarantineDir,
            @Autowired(required = false) RefundEvidenceFileRepository evidenceFileRepository,
            @Autowired(required = false) EvidenceMalwareScanner malwareScanner,
            @Autowired(required = false) RefundRequestRepository refundRequestRepository,
            @Autowired(required = false) IncidentReportRepository incidentReportRepository,
            @Autowired(required = false) RefundAuditLogRepository auditLogRepository,
            @Autowired(required = false) UserRepository userRepository,
            @Autowired(required = false) PlatformTransactionManager transactionManager) {
        this.uploadRoot = Paths.get(evidenceDir).toAbsolutePath().normalize();
        this.quarantineRoot = Paths.get(quarantineDir != null && !quarantineDir.isBlank()
                ? quarantineDir : (evidenceDir + "-quarantine")).toAbsolutePath().normalize();
        this.evidenceFileRepository = evidenceFileRepository;
        this.malwareScanner = malwareScanner != null ? malwareScanner : new EvidenceMalwareScanner();
        this.refundRequestRepository = refundRequestRepository;
        this.incidentReportRepository = incidentReportRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        if (transactionManager != null) {
            TransactionTemplate tpl = new TransactionTemplate(transactionManager);
            tpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            this.requiresNewTx = tpl;
        } else {
            this.requiresNewTx = null;
        }
    }

    /**
     * Backward-compatible single-file upload method returning the evidence URL.
     * Automatically captures current authenticated actor & request IP if present in thread context.
     */
    public String storeImage(MultipartFile file) {
        HttpServletRequest req = resolveCurrentHttpRequest();
        User actor = resolveAuthenticatedActor(req);
        Long userId = actor != null && actor.getUserId() != null && actor.getUserId() > 0 ? actor.getUserId() : null;
        String email = actor != null ? actor.getEmail() : null;
        String ip = req != null ? req.getRemoteAddr() : null;
        return storeEvidence(file, userId, email, ip, null, null).publicUrl();
    }

    /**
     * Validates, scans, persists metadata, and stores a batch of evidence files (enforcing max count & total size).
     */
    public List<StoredEvidenceDto> storeEvidenceBatch(
            List<MultipartFile> files,
            Long uploaderUserId,
            String uploaderEmail,
            String ip,
            Long refundRequestId,
            Long incidentReportId) {
        if (files == null || files.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EVIDENCE_EMPTY", "At least one evidence image file is required.");
        }
        int effectiveMaxFiles = maxFilesPerRequest > 0 ? maxFilesPerRequest : DEFAULT_MAX_FILES_PER_REQUEST;
        if (files.size() > effectiveMaxFiles) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "EVIDENCE_FILE_COUNT_EXCEEDED",
                    "Maximum " + effectiveMaxFiles + " evidence files are allowed per upload."
            );
        }
        long effectiveMaxTotal = maxTotalSizeBytes > 0 ? maxTotalSizeBytes : DEFAULT_MAX_TOTAL_SIZE_BYTES;
        long totalSize = 0L;
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "EVIDENCE_EMPTY", "Evidence image file cannot be empty.");
            }
            totalSize += Math.max(0L, f.getSize());
            if (totalSize > effectiveMaxTotal) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        "EVIDENCE_TOTAL_SIZE_EXCEEDED",
                        "Total uploaded evidence size exceeds " + (effectiveMaxTotal / (1024L * 1024L)) + " MB limit."
                );
            }
        }

        List<StoredEvidenceDto> stored = new ArrayList<>(files.size());
        for (MultipartFile f : files) {
            stored.add(storeEvidence(f, uploaderUserId, uploaderEmail, ip, refundRequestId, incidentReportId));
        }
        return stored;
    }

    /**
     * Validates, deep-scans, and stores a single evidence file outside the webroot, recording metadata in {@code refund_evidence_files}.
     */
    public StoredEvidenceDto storeEvidence(
            MultipartFile file,
            Long uploaderUserId,
            String uploaderEmail,
            String ip,
            Long refundRequestId,
            Long incidentReportId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Evidence image file cannot be empty.");
        }
        long effectiveMaxFile = maxFileSizeBytes > 0 ? maxFileSizeBytes : DEFAULT_MAX_FILE_SIZE_BYTES;
        if (file.getSize() > effectiveMaxFile) {
            throw new IllegalArgumentException("Evidence image exceeds 5 MB limit.");
        }

        String rawOriginal = file.getOriginalFilename() != null ? file.getOriginalFilename() : "";
        String cleanOriginal = validateAndSanitizeOriginalFilename(rawOriginal);
        String ext = extractSingleExtension(cleanOriginal);

        String contentType = file.getContentType() != null ? file.getContentType().trim().toLowerCase(Locale.ROOT) : "";
        int semi = contentType.indexOf(';');
        if (semi >= 0) {
            contentType = contentType.substring(0, semi).trim();
        }
        String extFromMime = CONTENT_TYPE_TO_EXT.get(contentType);
        if (extFromMime == null || !ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Only JPG, PNG, or WEBP image files are allowed.");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read uploaded evidence file.", e);
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException("Evidence image file cannot be empty.");
        }
        if (bytes.length > effectiveMaxFile) {
            throw new IllegalArgumentException("Evidence image exceeds 5 MB limit.");
        }

        String detectedExt = detectImageExtensionByMagicBytes(bytes);
        if (detectedExt == null) {
            throw new IllegalArgumentException("Invalid image file signature. Only authentic JPG, PNG, or WEBP images are allowed.");
        }
        if (!detectedExt.equals(extFromMime) || !normalizeExt(ext).equals(detectedExt)) {
            throw new IllegalArgumentException("File content signature does not match declared image MIME type or extension.");
        }

        String sha256 = sha256Hex(bytes);
        String storedName = UUID.randomUUID() + "." + detectedExt;

        EvidenceMalwareScanner.ScanResult scanResult = malwareScanner.scan(cleanOriginal, detectedExt, contentType, bytes);
        if (!scanResult.clean() || scanResult.verdict() == EvidenceMalwareScanner.ScanVerdict.QUARANTINED) {
            writeToQuarantineAndRecordMeta(
                    storedName, cleanOriginal, contentType, bytes, sha256,
                    scanResult.details(), uploaderUserId, uploaderEmail, ip, refundRequestId, incidentReportId);
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "EVIDENCE_QUARANTINED",
                    "Uploaded file failed security content scan and was quarantined: " + scanResult.details(),
                    Map.of(
                            "storageName", storedName,
                            "scanStatus", "QUARANTINED",
                            "scanDetails", scanResult.details(),
                            "url", "/api/refunds/evidence/" + storedName,
                            "publicUrl", "/api/refunds/public/evidence/" + storedName
                    )
            );
        }

        try {
            Files.createDirectories(uploadRoot);
            Path target = uploadRoot.resolve(storedName).normalize();
            if (!target.startsWith(uploadRoot)) {
                throw new IllegalArgumentException("Invalid file path.");
            }
            Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to store evidence file.", e);
        }

        RefundEvidenceFile savedMeta = persistEvidenceMetadata(
                storedName, cleanOriginal, contentType, bytes.length, sha256,
                "CLEAN", scanResult.details(),
                uploaderUserId, uploaderEmail, ip, refundRequestId, incidentReportId, false);

        String publicUrl = "/api/refunds/public/evidence/" + storedName;
        String authUrl = "/api/refunds/evidence/" + storedName;
        return new StoredEvidenceDto(
                savedMeta != null ? savedMeta.getId() : null,
                storedName,
                cleanOriginal,
                contentType,
                bytes.length,
                sha256,
                "CLEAN",
                scanResult.details(),
                authUrl,
                publicUrl
        );
    }

    /**
     * Links an already-uploaded evidence file (by URL or storageName) to a {@link RefundRequest}.
     */
    public RefundEvidenceFile linkEvidenceToRefund(String urlOrStorageName, Long refundRequestId, Long actorUserId, String clientIp) {
        if (evidenceFileRepository == null || refundRequestId == null) {
            return null;
        }
        String storageName = extractLocalStorageName(urlOrStorageName);
        if (storageName == null) {
            return null;
        }
        Optional<RefundEvidenceFile> opt = evidenceFileRepository.findByStorageName(storageName);
        if (opt.isEmpty()) {
            return null;
        }
        RefundEvidenceFile meta = opt.get();
        if (!"CLEAN".equalsIgnoreCase(meta.getScanStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EVIDENCE_QUARANTINED",
                    "Cannot attach quarantined or unsafe evidence file.");
        }
        meta.setRefundRequestId(refundRequestId);
        if (meta.getUploadedByUserId() == null && actorUserId != null && actorUserId > 0) {
            meta.setUploadedByUserId(actorUserId);
        }
        if (meta.getUploadedByIp() == null && clientIp != null) {
            meta.setUploadedByIp(clientIp);
        }
        return evidenceFileRepository.save(meta);
    }

    /**
     * Links an already-uploaded evidence file (by URL or storageName) to an {@link IncidentReport}.
     */
    public RefundEvidenceFile linkEvidenceToIncident(String urlOrStorageName, Long incidentReportId, Long actorUserId, String clientIp) {
        if (evidenceFileRepository == null || incidentReportId == null) {
            return null;
        }
        String storageName = extractLocalStorageName(urlOrStorageName);
        if (storageName == null) {
            return null;
        }
        Optional<RefundEvidenceFile> opt = evidenceFileRepository.findByStorageName(storageName);
        if (opt.isEmpty()) {
            return null;
        }
        RefundEvidenceFile meta = opt.get();
        if (!"CLEAN".equalsIgnoreCase(meta.getScanStatus())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EVIDENCE_QUARANTINED",
                    "Cannot attach quarantined or unsafe evidence file.");
        }
        meta.setIncidentReportId(incidentReportId);
        if (meta.getUploadedByUserId() == null && actorUserId != null && actorUserId > 0) {
            meta.setUploadedByUserId(actorUserId);
        }
        if (meta.getUploadedByIp() == null && clientIp != null) {
            meta.setUploadedByIp(clientIp);
        }
        return evidenceFileRepository.save(meta);
    }

    /**
     * Explicitly marks an evidence file as QUARANTINED and removes it from the public/active upload directory.
     */
    public RefundEvidenceFile markQuarantined(String storageName, String reason, Long actorUserId) {
        if (storageName == null || storageName.isBlank()) {
            throw new IllegalArgumentException("Storage name is required.");
        }
        String extracted = extractLocalStorageName(storageName);
        final String cleanName = extracted != null ? extracted : storageName.trim();
        try {
            Path activeFile = uploadRoot.resolve(cleanName).normalize();
            if (activeFile.startsWith(uploadRoot) && Files.exists(activeFile)) {
                Files.createDirectories(quarantineRoot);
                Path qFile = quarantineRoot.resolve(cleanName).normalize();
                if (qFile.startsWith(quarantineRoot)) {
                    Files.move(activeFile, qFile, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(activeFile);
                }
            }
        } catch (IOException ignored) {
        }
        if (evidenceFileRepository == null) {
            return null;
        }
        RefundEvidenceFile meta = evidenceFileRepository.findByStorageName(cleanName).orElseGet(() -> {
            RefundEvidenceFile f = new RefundEvidenceFile();
            f.setStorageName(cleanName);
            f.setOriginalFilename(cleanName);
            f.setContentType("image/png");
            f.setFileSizeBytes(0L);
            f.setSha256Hash("0".repeat(64));
            return f;
        });
        meta.setScanStatus("QUARANTINED");
        meta.setScanDetails(reason != null ? reason : "Quarantined by security policy");
        meta = evidenceFileRepository.save(meta);
        recordAudit(meta.getRefundRequestId(), "REFUND_EVIDENCE_FILE", meta.getId(), "EVIDENCE_QUARANTINED",
                actorUserId, null, null, null, Map.of("storageName", cleanName, "reason", meta.getScanDetails()));
        return meta;
    }

    /**
     * Authenticates, authorizes (ADMIN all; MANAGER/STAFF assigned cinema only; CUSTOMER own upload/refund only;
     * blocks QUARANTINED with 403), audits download in {@code refund_audit_logs}, and loads the evidence {@link Resource}.
     */
    public Resource authorizeAndLoadEvidence(String filename, Long actorUserId, String ip) {
        User actor = null;
        if (actorUserId != null && userRepository != null) {
            actor = userRepository.findById(actorUserId).orElse(null);
        }
        if (actor == null) {
            actor = resolveAuthenticatedActor(resolveCurrentHttpRequest());
        }
        return authorizeAndLoadEvidence(filename, actor, ip);
    }

    public Resource authorizeAndLoadEvidence(String filename, User actor, String ip) {
        if (filename == null || filename.contains("..") || filename.contains("/") || filename.contains("\\")
                || !filename.matches("^[a-fA-F0-9\\-]+\\.(jpg|jpeg|png|webp)$")) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_EVIDENCE_FILENAME", "Invalid evidence filename.");
        }

        User effectiveActor = actor != null ? actor : resolveAuthenticatedActor(resolveCurrentHttpRequest());
        if (effectiveActor == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "ACCOUNT_NOT_AUTHENTICATED",
                    "Authentication required to download evidence files.");
        }

        RefundEvidenceFile meta = evidenceFileRepository != null
                ? evidenceFileRepository.findByStorageName(filename).orElse(null)
                : null;

        if (meta != null && !"CLEAN".equalsIgnoreCase(meta.getScanStatus())) {
            recordAudit(
                    meta.getRefundRequestId(),
                    "REFUND_EVIDENCE_FILE",
                    meta.getId() != null ? meta.getId() : 0L,
                    "EVIDENCE_DOWNLOAD_BLOCKED_QUARANTINED",
                    validUserId(effectiveActor.getUserId()),
                    effectiveActor.getRole(),
                    null,
                    ip,
                    Map.of("storageName", filename, "scanStatus", meta.getScanStatus())
            );
            throw new BusinessException(HttpStatus.FORBIDDEN, "EVIDENCE_QUARANTINED",
                    "Access denied: evidence file is quarantined and cannot be downloaded.");
        }

        RefundRequest linkedRefund = resolveLinkedRefundRequest(filename, meta);
        IncidentReport linkedIncident = resolveLinkedIncidentReport(filename, meta);
        Long resolvedCinemaId = null;
        if (linkedRefund != null && linkedRefund.getCinema() != null) {
            resolvedCinemaId = linkedRefund.getCinema().getCinemaId();
        } else if (linkedIncident != null && linkedIncident.getCinema() != null) {
            resolvedCinemaId = linkedIncident.getCinema().getCinemaId();
        } else if (meta != null && meta.getUploadedByUserId() != null && userRepository != null) {
            User uploader = userRepository.findById(meta.getUploadedByUserId()).orElse(null);
            if (uploader != null && uploader.getCinemaId() != null) {
                resolvedCinemaId = uploader.getCinemaId();
            }
        }

        String role = effectiveActor.getRole() != null ? effectiveActor.getRole().trim().toUpperCase(Locale.ROOT) : "CUSTOMER";
        if ("ADMIN".equals(role)) {
            // Admin is authorized across all cinemas
        } else if ("MANAGER".equals(role) || "STAFF".equals(role)) {
            Long actorCinemaId = effectiveActor.getCinemaId();
            boolean uploadedBySelf = meta != null && meta.getUploadedByUserId() != null
                    && meta.getUploadedByUserId().equals(effectiveActor.getUserId());
            if (!uploadedBySelf) {
                if (actorCinemaId == null || resolvedCinemaId == null || !actorCinemaId.equals(resolvedCinemaId)) {
                    throw new BusinessException(HttpStatus.FORBIDDEN, "BRANCH_ACCESS_DENIED",
                            "Access denied: evidence file does not belong to your assigned cinema branch.");
                }
            }
        } else {
            // CUSTOMER authorization: must own the uploaded file or own the linked refund request
            boolean ownsUpload = (meta != null && meta.getUploadedByUserId() != null
                    && effectiveActor.getUserId() != null && meta.getUploadedByUserId().equals(effectiveActor.getUserId()))
                    || (meta != null && meta.getUploadedByEmail() != null && !meta.getUploadedByEmail().isBlank()
                    && effectiveActor.getEmail() != null && meta.getUploadedByEmail().equalsIgnoreCase(effectiveActor.getEmail()));

            boolean ownsRefund = false;
            if (linkedRefund != null) {
                if (linkedRefund.getCustomerUser() != null && effectiveActor.getUserId() != null
                        && linkedRefund.getCustomerUser().getUserId().equals(effectiveActor.getUserId())) {
                    ownsRefund = true;
                } else if (linkedRefund.getCustomerUser() == null && linkedRefund.getPurchaseEmail() != null
                        && effectiveActor.getEmail() != null
                        && linkedRefund.getPurchaseEmail().equalsIgnoreCase(effectiveActor.getEmail())) {
                    ownsRefund = true;
                }
            }

            if (!ownsUpload && !ownsRefund) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "EVIDENCE_ACCESS_DENIED",
                        "Access denied: you do not have permission to view this evidence file.");
            }
        }

        Resource resource = loadAsResource(filename);

        recordAudit(
                linkedRefund != null ? linkedRefund.getId() : (meta != null ? meta.getRefundRequestId() : null),
                "REFUND_EVIDENCE_FILE",
                meta != null && meta.getId() != null ? meta.getId() : 0L,
                "EVIDENCE_DOWNLOADED",
                validUserId(effectiveActor.getUserId()),
                role,
                resolvedCinemaId,
                ip,
                Map.of(
                        "storageName", filename,
                        "scanStatus", meta != null ? meta.getScanStatus() : "CLEAN"
                )
        );
        return resource;
    }

    public Resource loadAsResource(String filename) {
        if (filename == null || filename.contains("..") || filename.contains("/") || filename.contains("\\")
                || !filename.matches("^[a-fA-F0-9\\-]+\\.(jpg|jpeg|png|webp)$")) {
            throw new IllegalArgumentException("Invalid evidence filename.");
        }
        if (evidenceFileRepository != null) {
            RefundEvidenceFile meta = evidenceFileRepository.findByStorageName(filename).orElse(null);
            if (meta != null && !"CLEAN".equalsIgnoreCase(meta.getScanStatus())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "EVIDENCE_QUARANTINED",
                        "Access denied: evidence file is quarantined.");
            }
        }
        try {
            Path file = uploadRoot.resolve(filename).normalize();
            if (!file.startsWith(uploadRoot) || !Files.exists(file)) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "EVIDENCE_NOT_FOUND", "Evidence file not found.");
            }
            return new UrlResource(file.toUri());
        } catch (IOException e) {
            throw new IllegalStateException("Could not load evidence file.", e);
        }
    }

    public String sanitizeEvidenceUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        String trimmed = rawUrl.trim();
        if (trimmed.length() > 500) {
            throw new IllegalArgumentException("Evidence URL must not exceed 500 characters.");
        }
        if (trimmed.startsWith("/api/refunds/public/evidence/") || trimmed.startsWith("/api/refunds/evidence/")) {
            String storageName = extractLocalStorageName(trimmed);
            if (storageName == null || !storageName.matches("^[a-fA-F0-9\\-]+\\.(jpg|jpeg|png|webp)$")) {
                throw new IllegalArgumentException("Invalid local evidence URL path.");
            }
            if (evidenceFileRepository != null) {
                RefundEvidenceFile meta = evidenceFileRepository.findByStorageName(storageName).orElse(null);
                if (meta != null && !"CLEAN".equalsIgnoreCase(meta.getScanStatus())) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "EVIDENCE_QUARANTINED",
                            "Quarantined evidence file cannot be used.");
                }
            }
            return trimmed;
        }
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                throw new IllegalArgumentException("Evidence URL must start with http:// or https://.");
            }
            return trimmed;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid evidence URL format.");
        }
    }

    public User resolveAuthenticatedActor(HttpServletRequest request) {
        Long userId = SecurityContextUtils.resolveCurrentUserId(request);
        if (userId != null && userRepository != null) {
            Optional<User> uOpt = userRepository.findById(userId);
            if (uOpt.isPresent()) {
                return uOpt.get();
            }
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return null;
        }
        if (auth.getPrincipal() instanceof BoxCinemaUserDetails details && details.getUserSummary() != null) {
            Long dId = details.getUserSummary().getUserId();
            if (dId != null && userRepository != null) {
                Optional<User> uOpt = userRepository.findById(dId);
                if (uOpt.isPresent()) {
                    return uOpt.get();
                }
            }
            User summaryUser = new User();
            summaryUser.setUserId(dId);
            summaryUser.setEmail(details.getUserSummary().getEmail());
            summaryUser.setFullName(details.getUserSummary().getFullName());
            summaryUser.setRole(details.getUserSummary().getRole());
            return summaryUser;
        }
        String name = auth.getName();
        if (name != null && !name.isBlank() && userRepository != null) {
            Optional<User> byEmail = userRepository.findByEmail(name.trim());
            if (byEmail.isPresent()) {
                return byEmail.get();
            }
        }
        String resolvedRole = "CUSTOMER";
        for (GrantedAuthority ga : auth.getAuthorities()) {
            String a = ga.getAuthority();
            if ("ROLE_ADMIN".equalsIgnoreCase(a) || "ADMIN".equalsIgnoreCase(a)) {
                resolvedRole = "ADMIN";
                break;
            } else if ("ROLE_MANAGER".equalsIgnoreCase(a) || "MANAGER".equalsIgnoreCase(a)) {
                resolvedRole = "MANAGER";
            } else if ("ROLE_STAFF".equalsIgnoreCase(a) || "STAFF".equalsIgnoreCase(a)) {
                resolvedRole = "STAFF";
            }
        }
        User fallback = new User();
        fallback.setUserId(-1L);
        fallback.setEmail(name);
        fallback.setFullName(name);
        fallback.setRole(resolvedRole);
        return fallback;
    }

    private static String validateAndSanitizeOriginalFilename(String rawFilename) {
        if (rawFilename == null || rawFilename.isBlank()) {
            throw new IllegalArgumentException("Evidence filename is required.");
        }
        String trimmed = rawFilename.trim();
        if (trimmed.contains("\0") || trimmed.contains("..") || trimmed.contains("/") || trimmed.contains("\\")) {
            throw new IllegalArgumentException("Invalid evidence filename: path traversal or directory separators are forbidden.");
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException("Invalid control characters in evidence filename.");
            }
        }
        if (trimmed.startsWith(".") || trimmed.endsWith(".")) {
            throw new IllegalArgumentException("Invalid evidence filename.");
        }
        int firstDot = trimmed.indexOf('.');
        int lastDot = trimmed.lastIndexOf('.');
        if (firstDot < 0) {
            throw new IllegalArgumentException("Evidence filename must have a valid image extension (.jpg, .jpeg, .png, .webp).");
        }
        if (firstDot != lastDot) {
            throw new IllegalArgumentException("Double or multiple file extensions are forbidden.");
        }
        if (trimmed.length() > 255) {
            String base = trimmed.substring(0, firstDot);
            String ext = trimmed.substring(firstDot);
            trimmed = base.substring(0, Math.max(1, 255 - ext.length())) + ext;
        }
        return trimmed;
    }

    private static String extractSingleExtension(String sanitizedFilename) {
        int dot = sanitizedFilename.lastIndexOf('.');
        if (dot <= 0 || dot == sanitizedFilename.length() - 1) {
            throw new IllegalArgumentException("Invalid file extension.");
        }
        return sanitizedFilename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String extractLocalStorageName(String urlOrName) {
        if (urlOrName == null || urlOrName.isBlank()) {
            return null;
        }
        String s = urlOrName.trim();
        if (s.startsWith("/api/refunds/public/evidence/")) {
            return s.substring("/api/refunds/public/evidence/".length());
        }
        if (s.startsWith("/api/refunds/evidence/")) {
            return s.substring("/api/refunds/evidence/".length());
        }
        if (s.matches("^[a-fA-F0-9\\-]+\\.(jpg|jpeg|png|webp)$")) {
            return s;
        }
        return null;
    }

    private void writeToQuarantineAndRecordMeta(
            String storedName,
            String originalFilename,
            String contentType,
            byte[] bytes,
            String sha256,
            String scanDetails,
            Long uploaderUserId,
            String uploaderEmail,
            String ip,
            Long refundRequestId,
            Long incidentReportId) {
        try {
            Files.createDirectories(quarantineRoot);
            Path qTarget = quarantineRoot.resolve(storedName).normalize();
            if (qTarget.startsWith(quarantineRoot)) {
                Files.write(qTarget, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
        } catch (IOException ignored) {
        }
        persistEvidenceMetadata(
                storedName, originalFilename, contentType, bytes.length, sha256,
                "QUARANTINED", scanDetails, uploaderUserId, uploaderEmail, ip,
                refundRequestId, incidentReportId, true);
    }

    private RefundEvidenceFile persistEvidenceMetadata(
            String storedName,
            String originalFilename,
            String contentType,
            long fileSizeBytes,
            String sha256,
            String scanStatus,
            String scanDetails,
            Long uploaderUserId,
            String uploaderEmail,
            String ip,
            Long refundRequestId,
            Long incidentReportId,
            boolean forceRequiresNew) {
        if (evidenceFileRepository == null) {
            return null;
        }
        java.util.function.Supplier<RefundEvidenceFile> action = () -> {
            RefundEvidenceFile entity = new RefundEvidenceFile();
            entity.setStorageName(storedName);
            entity.setOriginalFilename(originalFilename);
            entity.setContentType(contentType);
            entity.setFileSizeBytes(fileSizeBytes);
            entity.setSha256Hash(sha256);
            entity.setScanStatus(scanStatus);
            entity.setScanDetails(scanDetails != null && scanDetails.length() > 255 ? scanDetails.substring(0, 255) : scanDetails);
            entity.setRefundRequestId(refundRequestId);
            entity.setIncidentReportId(incidentReportId);
            entity.setUploadedByUserId(validUserId(uploaderUserId));
            entity.setUploadedByEmail(uploaderEmail);
            entity.setUploadedByIp(ip);
            RefundEvidenceFile saved = evidenceFileRepository.save(entity);
            recordAudit(
                    refundRequestId,
                    "REFUND_EVIDENCE_FILE",
                    saved.getId(),
                    "QUARANTINED".equalsIgnoreCase(scanStatus) ? "EVIDENCE_QUARANTINED" : "EVIDENCE_UPLOADED",
                    validUserId(uploaderUserId),
                    null,
                    null,
                    ip,
                    Map.of(
                            "storageName", storedName,
                            "originalFilename", originalFilename,
                            "fileSizeBytes", fileSizeBytes,
                            "sha256", sha256,
                            "scanStatus", scanStatus
                    )
            );
            return saved;
        };
        if (forceRequiresNew && requiresNewTx != null) {
            return requiresNewTx.execute(status -> action.get());
        }
        return action.get();
    }

    private RefundRequest resolveLinkedRefundRequest(String filename, RefundEvidenceFile meta) {
        if (refundRequestRepository == null) {
            return null;
        }
        if (meta != null && meta.getRefundRequestId() != null) {
            return refundRequestRepository.findById(meta.getRefundRequestId()).orElse(null);
        }
        if (entityManager != null) {
            List<RefundRequest> list = entityManager.createQuery(
                            "SELECT r FROM RefundRequest r WHERE r.evidenceUrl LIKE :suffix ORDER BY r.id DESC",
                            RefundRequest.class)
                    .setParameter("suffix", "%/" + filename)
                    .setMaxResults(1)
                    .getResultList();
            if (!list.isEmpty()) {
                return list.get(0);
            }
        }
        return null;
    }

    private IncidentReport resolveLinkedIncidentReport(String filename, RefundEvidenceFile meta) {
        if (incidentReportRepository == null) {
            return null;
        }
        if (meta != null && meta.getIncidentReportId() != null) {
            return incidentReportRepository.findById(meta.getIncidentReportId()).orElse(null);
        }
        if (entityManager != null) {
            List<IncidentReport> list = entityManager.createQuery(
                            "SELECT i FROM IncidentReport i WHERE i.evidenceUrl LIKE :suffix ORDER BY i.id DESC",
                            IncidentReport.class)
                    .setParameter("suffix", "%/" + filename)
                    .setMaxResults(1)
                    .getResultList();
            if (!list.isEmpty()) {
                return list.get(0);
            }
        }
        return null;
    }

    private void recordAudit(
            Long refundRequestId,
            String entityType,
            Long entityId,
            String eventType,
            Long actorUserId,
            String actorRole,
            Long cinemaId,
            String ipAddress,
            Map<String, ?> details) {
        if (auditLogRepository == null) {
            return;
        }
        RefundAuditLog log = new RefundAuditLog();
        log.setRefundRequestId(refundRequestId);
        log.setEntityType(entityType);
        log.setEntityId(entityId != null ? entityId : 0L);
        log.setEventType(eventType);
        log.setActorUserId(validUserId(actorUserId));
        log.setActorRole(actorRole);
        log.setCinemaId(cinemaId);
        log.setIpAddress(ipAddress);
        try {
            log.setDetailsJson(JSON_MAPPER.writeValueAsString(details != null ? details : Map.of()));
        } catch (JsonProcessingException e) {
            log.setDetailsJson("{}");
        }
        auditLogRepository.save(log);
    }

    private static Long validUserId(Long userId) {
        return (userId != null && userId > 0) ? userId : null;
    }

    private static HttpServletRequest resolveCurrentHttpRequest() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            return CryptoUtils.bytesToHex(md.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute SHA-256 hash", e);
        }
    }

    private static String normalizeExt(String ext) {
        return "jpeg".equals(ext) ? "jpg" : ext;
    }

    private static String detectImageExtensionByMagicBytes(byte[] b) {
        if (b == null || b.length < 3) {
            return null;
        }
        // JPEG: FF D8 FF
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (b.length >= 8
                && (b[0] & 0xFF) == 0x89
                && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A) {
            return "png";
        }
        // WEBP: RIFF....WEBP
        if (b.length >= 12
                && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
