package com.boxcinema.refund.service;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.*;
import com.boxcinema.refund.dto.IncidentCreateRequestDto;
import com.boxcinema.refund.dto.IncidentResponseDto;
import com.boxcinema.refund.dto.IncidentUpdateRequestDto;
import com.boxcinema.refund.dto.StaffErrorLogDto;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@Transactional
public class IncidentService {

    private static final Set<String> VALID_GROUPS = Set.of("CINEMA_INCIDENT", "PRODUCT_INCIDENT", "STAFF_ERROR");
    private static final Set<String> VALID_SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final IncidentReportRepository incidentRepository;
    private final IncidentVerificationRepository verificationRepository;
    private final StaffErrorLogRepository staffErrorLogRepository;
    private final CinemaRepository cinemaRepository;
    private final ShowtimeRepository showtimeRepository;
    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final EvidenceStorageService evidenceStorageService;
    private final SecureRandom random = new SecureRandom();

    @Autowired(required = false)
    private RefundAuditLogRepository auditLogRepository;

    @PersistenceContext
    private EntityManager entityManager;

    public IncidentService(
            IncidentReportRepository incidentRepository,
            IncidentVerificationRepository verificationRepository,
            StaffErrorLogRepository staffErrorLogRepository,
            CinemaRepository cinemaRepository,
            ShowtimeRepository showtimeRepository,
            BookingRepository bookingRepository,
            UserRepository userRepository,
            EvidenceStorageService evidenceStorageService) {
        this.incidentRepository = incidentRepository;
        this.verificationRepository = verificationRepository;
        this.staffErrorLogRepository = staffErrorLogRepository;
        this.cinemaRepository = cinemaRepository;
        this.showtimeRepository = showtimeRepository;
        this.bookingRepository = bookingRepository;
        this.userRepository = userRepository;
        this.evidenceStorageService = evidenceStorageService;
    }

    @PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
    public IncidentResponseDto createIncident(IncidentCreateRequestDto req, Long actorUserId) {
        User reporter = requireUser(actorUserId);
        String group = req.getIncidentGroup() != null ? req.getIncidentGroup().trim().toUpperCase() : "";
        if (!VALID_GROUPS.contains(group)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_INCIDENT_GROUP",
                    "Incident group must be CINEMA_INCIDENT, PRODUCT_INCIDENT, or STAFF_ERROR.");
        }
        if (req.getIncidentType() == null || req.getIncidentType().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Incident type is required.");
        }
        if (req.getTitle() == null || req.getTitle().isBlank() || req.getDescription() == null || req.getDescription().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Title and description are required.");
        }

        Booking booking = null;
        if (req.getBookingCode() != null && !req.getBookingCode().isBlank()) {
            booking = bookingRepository.findByBookingCodeIgnoreCase(req.getBookingCode().trim())
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "BOOKING_NOT_FOUND", "Booking not found: " + req.getBookingCode()));
        }

        Showtime showtime = null;
        if (req.getShowtimeId() != null) {
            showtime = showtimeRepository.findById(req.getShowtimeId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "SHOWTIME_NOT_FOUND",
                            "Showtime not found: " + req.getShowtimeId()));
        } else if (booking != null) {
            showtime = booking.getShowtime();
        }

        if (booking != null && showtime != null && booking.getShowtime() != null
                && !booking.getShowtime().getShowtimeId().equals(showtime.getShowtimeId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "BOOKING_SHOWTIME_MISMATCH",
                    "Provided bookingCode and showtimeId do not belong to the same showtime.");
        }

        Long bookingCinemaId = booking != null ? booking.resolveCinemaId() : null;
        Long showtimeCinemaId = (showtime != null && showtime.getRoom() != null && showtime.getRoom().getCinema() != null)
                ? showtime.getRoom().getCinema().getCinemaId()
                : null;
        if (bookingCinemaId != null && showtimeCinemaId != null && !bookingCinemaId.equals(showtimeCinemaId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CINEMA_MISMATCH",
                    "Booking and showtime belong to different cinema branches.");
        }

        Long authoritativeCinemaId = bookingCinemaId != null ? bookingCinemaId : showtimeCinemaId;
        if (authoritativeCinemaId != null && req.getCinemaId() != null && !authoritativeCinemaId.equals(req.getCinemaId())) {
            if (!"ADMIN".equalsIgnoreCase(reporter.getRole())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "BRANCH_ACCESS_DENIED",
                        "Referenced booking or showtime belongs to another cinema branch.");
            }
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CINEMA_MISMATCH",
                    "Provided cinemaId does not match the booking/showtime cinema branch.");
        }

        Long resolvedCinemaId = authoritativeCinemaId != null ? authoritativeCinemaId : req.getCinemaId();
        if (resolvedCinemaId == null) {
            resolvedCinemaId = reporter.getCinemaId();
        }
        if (resolvedCinemaId == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CINEMA_REQUIRED", "Cinema branch could not be determined for this incident.");
        }
        Cinema cinema = cinemaRepository.findById(resolvedCinemaId)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "CINEMA_NOT_FOUND", "Cinema not found."));

        // Enforce staff/manager branch isolation on incident submission if user is assigned to a cinema
        if (!"ADMIN".equalsIgnoreCase(reporter.getRole())) {
            if (reporter.getCinemaId() == null) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "NO_BRANCH_ASSIGNED", "Your account has no cinema branch assigned.");
            }
            if (!reporter.getCinemaId().equals(cinema.getCinemaId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "BRANCH_ACCESS_DENIED", "You cannot report incidents for another cinema branch.");
            }
        }

        String severity = req.getSeverity() != null ? req.getSeverity().trim().toUpperCase() : "MEDIUM";
        if (!VALID_SEVERITIES.contains(severity)) severity = "MEDIUM";

        IncidentReport ir = new IncidentReport();
        ir.setIncidentCode(generateIncidentCode());
        ir.setCinema(cinema);
        ir.setShowtime(showtime);
        if (showtime != null && showtime.getRoom() != null) {
            ir.setRoomId(showtime.getRoom().getRoomId());
        }
        ir.setBooking(booking);
        ir.setBookingFoodItemId(req.getBookingFoodItemId());
        ir.setBookingFoodItemComponentId(req.getBookingFoodItemComponentId());
        ir.setIncidentGroup(group);
        ir.setIncidentType(req.getIncidentType().trim().toUpperCase());
        ir.setSeverity(severity);
        ir.setTitle(req.getTitle().trim());
        ir.setDescription(req.getDescription().trim());
        ir.setEvidenceUrl(evidenceStorageService.sanitizeEvidenceUrl(req.getEvidenceUrl()));
        ir.setAffectedQuantity(req.getAffectedQuantity());
        ir.setCustomerNotifiedAt(req.getCustomerNotifiedAt());
        ir.setReportedByStaff(reporter);
        ir.setUpdatedBy(reporter.getUserId());
        ir.setStatus("PENDING_VERIFICATION");
        ir.setFinalConclusion("PENDING");
        ir = incidentRepository.save(ir);
        if (evidenceStorageService != null && ir.getEvidenceUrl() != null) {
            evidenceStorageService.linkEvidenceToIncident(ir.getEvidenceUrl(), ir.getId(), reporter.getUserId(), null);
        }

        appendVerification(ir, reporter, "SUBMITTED", null, ir.getDescription(), ir.getEvidenceUrl());
        recordIncidentAudit(ir, reporter, "INCIDENT_CREATED", "Created incident " + ir.getIncidentCode());

        // Separate staff error log (never merged into refund history)
        if ("STAFF_ERROR".equals(group)) {
            User errStaff = reporter;
            if (req.getStaffErrorUserId() != null) {
                errStaff = userRepository.findById(req.getStaffErrorUserId())
                        .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "STAFF_USER_NOT_FOUND",
                                "Target staff user not found."));
                String targetRole = errStaff.getRole() != null ? errStaff.getRole().trim().toUpperCase() : "";
                if (!Set.of("STAFF", "MANAGER").contains(targetRole)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STAFF_TARGET",
                            "Staff error log can only be attributed to STAFF or MANAGER accounts.");
                }
                if (errStaff.getCinemaId() != null && !errStaff.getCinemaId().equals(cinema.getCinemaId())) {
                    throw new BusinessException(HttpStatus.FORBIDDEN, "BRANCH_ACCESS_DENIED",
                            "Target staff user belongs to another cinema branch.");
                }
            }
            StaffErrorLog sel = new StaffErrorLog();
            sel.setCinema(cinema);
            sel.setStaffUser(errStaff);
            sel.setReportedByUser(reporter);
            sel.setIncidentReport(ir);
            sel.setBooking(booking);
            sel.setErrorCategory(ir.getIncidentType());
            sel.setSeverity(severity);
            sel.setDescription(ir.getDescription());
            sel.setStatus("LOGGED");
            staffErrorLogRepository.save(sel);
        }

        return mapToDto(ir);
    }

    @PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
    public IncidentResponseDto appendEvidence(Long incidentId, String note, String evidenceUrl, Long actorUserId) {
        User actor = requireUser(actorUserId);
        IncidentReport ir = incidentRepository.findByIdForUpdate(incidentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "Incident not found."));
        enforceBranchIfNotAdmin(actor, ir);

        String cleanUrl = evidenceStorageService.sanitizeEvidenceUrl(evidenceUrl);
        if ((note == null || note.isBlank()) && cleanUrl == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Please provide additional note or evidence.");
        }
        if (cleanUrl != null) {
            ir.setEvidenceUrl(cleanUrl);
        }
        if ("MORE_EVIDENCE_REQUIRED".equalsIgnoreCase(ir.getStatus())) {
            ir.setStatus("PENDING_VERIFICATION");
        }
        ir = incidentRepository.save(ir);
        if (evidenceStorageService != null && cleanUrl != null) {
            evidenceStorageService.linkEvidenceToIncident(cleanUrl, ir.getId(), actor.getUserId(), null);
        }
        appendVerification(ir, actor, "EVIDENCE_ADDED", null, note, cleanUrl);
        return mapToDto(ir);
    }

    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public IncidentResponseDto verifyByManager(Long incidentId, String decision, String note, Long managerUserId) {
        User manager = requireUser(managerUserId);
        IncidentReport ir = incidentRepository.findByIdForUpdate(incidentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "Incident not found."));
        enforceBranchIfNotAdmin(manager, ir);

        String cleanDec = decision != null ? decision.trim().toUpperCase() : "";
        if (!Set.of("VERIFIED", "REQUEST_MORE_EVIDENCE", "REJECTED").contains(cleanDec)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DECISION",
                    "Decision must be VERIFIED, REQUEST_MORE_EVIDENCE, or REJECTED.");
        }
        if (("REJECTED".equals(cleanDec) || "REQUEST_MORE_EVIDENCE".equals(cleanDec))
                && (note == null || note.isBlank())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED",
                    "A mandatory reason/note is required when rejecting or requesting more evidence.");
        }

        if (ir.getReportedByStaff() != null && ir.getReportedByStaff().getUserId().equals(manager.getUserId())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "SELF_VERIFICATION_FORBIDDEN",
                    "Maker-checker violation: Reporter cannot self-verify their own incident report.");
        }
        List<StaffErrorLog> linkedErrorLogs = findStaffErrorLogsByIncident(ir.getId());
        for (StaffErrorLog sel : linkedErrorLogs) {
            if (sel.getStaffUser() != null && sel.getStaffUser().getUserId().equals(manager.getUserId())) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "SELF_VERIFICATION_FORBIDDEN",
                        "Maker-checker violation: Implicated user cannot self-verify a staff error incident.");
            }
        }

        ir.setManagerVerifiedBy(manager);
        ir.setManagerDecision(cleanDec);
        ir.setManagerNote(note != null ? note.trim() : null);
        ir.setManagerDecidedAt(LocalDateTime.now());
        ir.setUpdatedBy(manager.getUserId());

        String action;
        switch (cleanDec) {
            case "VERIFIED" -> {
                ir.setStatus("VERIFIED");
                ir.setFinalConclusion("CONFIRMED");
                action = "MANAGER_VERIFIED";
            }
            case "REQUEST_MORE_EVIDENCE" -> {
                ir.setStatus("MORE_EVIDENCE_REQUIRED");
                action = "MANAGER_REQUEST_EVIDENCE";
            }
            default -> {
                ir.setStatus("REJECTED");
                ir.setFinalConclusion("REJECTED");
                action = "MANAGER_REJECTED";
            }
        }
        ir = incidentRepository.save(ir);
        for (StaffErrorLog sel : linkedErrorLogs) {
            sel.setVerifiedByManager(manager);
            if (ir.getManagerNote() != null) {
                sel.setCorrectiveAction(ir.getManagerNote());
            }
            sel.setStatus("VERIFIED".equals(cleanDec) ? "VERIFIED"
                    : ("REJECTED".equals(cleanDec) ? "REJECTED" : "MORE_EVIDENCE_REQUIRED"));
            staffErrorLogRepository.save(sel);
        }
        appendVerification(ir, manager, action, cleanDec, ir.getManagerNote(), null);
        return mapToDto(ir);
    }

    @PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
    public IncidentResponseDto submitAppeal(Long incidentId, String appealReason, Long actorUserId) {
        User actor = requireUser(actorUserId);
        IncidentReport ir = incidentRepository.findByIdForUpdate(incidentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "Incident not found."));
        enforceBranchIfNotAdmin(actor, ir);

        if (appealReason == null || appealReason.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Appeal reason is required.");
        }
        if (ir.getManagerDecision() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "NO_MANAGER_DECISION", "Cannot appeal before Manager reviews the incident.");
        }

        ir.setAppealedAt(LocalDateTime.now());
        ir.setAppealedBy(actor);
        ir.setAppealReason(appealReason.trim());
        ir.setStatus("APPEALED");
        ir = incidentRepository.save(ir);
        appendVerification(ir, actor, "APPEAL_SUBMITTED", null, appealReason.trim(), null);
        return mapToDto(ir);
    }

    /**
     * Admin resolves appeals/disputes against Manager conclusions across all branches,
     * strictly preserving Manager's original conclusion alongside Admin's final resolution.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public IncidentResponseDto resolveAppealByAdmin(Long incidentId, String adminDecision, String adminNote, Long adminUserId) {
        User admin = requireUser(adminUserId);
        IncidentReport ir = incidentRepository.findByIdForUpdate(incidentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "Incident not found."));

        String cleanDec = adminDecision != null ? adminDecision.trim().toUpperCase() : "";
        if (!Set.of("UPHELD_MANAGER", "OVERRIDDEN_VERIFIED", "OVERRIDDEN_REJECTED").contains(cleanDec)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DECISION",
                    "Admin decision must be UPHELD_MANAGER, OVERRIDDEN_VERIFIED, or OVERRIDDEN_REJECTED.");
        }
        if (adminNote == null || adminNote.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Admin resolution note is mandatory.");
        }
        if (!"APPEALED".equalsIgnoreCase(ir.getStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "INCIDENT_NOT_APPEALED",
                    "Only incidents in APPEALED status can be resolved via appeal (current: " + ir.getStatus() + ").");
        }
        if ((ir.getManagerVerifiedBy() != null && ir.getManagerVerifiedBy().getUserId().equals(admin.getUserId()))
                || (ir.getReportedByStaff() != null && ir.getReportedByStaff().getUserId().equals(admin.getUserId()))
                || (ir.getAppealedBy() != null && ir.getAppealedBy().getUserId().equals(admin.getUserId()))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "MAKER_CHECKER_VIOLATION",
                    "Maker-checker violation: Admin who reported, verified, or appealed this incident cannot resolve the appeal.");
        }

        // Preserve managerDecision, managerNote, managerVerifiedBy, managerDecidedAt untouched!
        ir.setAdminResolvedBy(admin);
        ir.setAdminDecision(cleanDec);
        ir.setAdminNote(adminNote.trim());
        ir.setAdminResolvedAt(LocalDateTime.now());
        ir.setUpdatedBy(admin.getUserId());

        if ("OVERRIDDEN_VERIFIED".equals(cleanDec)) {
            ir.setStatus("VERIFIED");
            ir.setFinalConclusion("CONFIRMED");
        } else if ("OVERRIDDEN_REJECTED".equals(cleanDec)) {
            ir.setStatus("REJECTED");
            ir.setFinalConclusion("REJECTED");
        } else {
            // Upheld manager conclusion (must be VERIFIED or REJECTED)
            if ("VERIFIED".equalsIgnoreCase(ir.getManagerDecision())) {
                ir.setStatus("VERIFIED");
                ir.setFinalConclusion("CONFIRMED");
            } else if ("REJECTED".equalsIgnoreCase(ir.getManagerDecision())) {
                ir.setStatus("REJECTED");
                ir.setFinalConclusion("REJECTED");
            } else {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "CANNOT_UPHOLD_NON_FINAL_DECISION",
                        "Manager's current decision (" + ir.getManagerDecision() + ") is not a final VERIFIED/REJECTED conclusion.");
            }
        }

        ir = incidentRepository.save(ir);
        for (StaffErrorLog sel : findStaffErrorLogsByIncident(ir.getId())) {
            sel.setStatus("VERIFIED".equalsIgnoreCase(ir.getStatus()) ? "VERIFIED" : "REJECTED");
            sel.setCorrectiveAction(ir.getAdminNote());
            staffErrorLogRepository.save(sel);
        }
        appendVerification(ir, admin, "ADMIN_RESOLVED", cleanDec, ir.getAdminNote(), null);
        return mapToDto(ir);
    }

    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public IncidentResponseDto updateIncident(Long incidentId, IncidentUpdateRequestDto req, Long actorUserId) {
        User actor = requireManagerOrAdminUser(actorUserId);
        IncidentReport ir = incidentRepository.findByIdForUpdate(incidentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "Incident not found."));
        enforceBranchIfNotAdmin(actor, ir);

        if (req != null) {
            if (req.getIncidentType() != null && !req.getIncidentType().isBlank()) {
                ir.setIncidentType(req.getIncidentType().trim().toUpperCase(Locale.ROOT));
            }
            if (req.getSeverity() != null && !req.getSeverity().isBlank()) {
                String sev = req.getSeverity().trim().toUpperCase(Locale.ROOT);
                if (VALID_SEVERITIES.contains(sev)) {
                    ir.setSeverity(sev);
                }
            }
            if (req.getTitle() != null && !req.getTitle().isBlank()) {
                ir.setTitle(req.getTitle().trim());
            }
            if (req.getDescription() != null && !req.getDescription().isBlank()) {
                ir.setDescription(req.getDescription().trim());
            }
            if (req.getEvidenceUrl() != null) {
                ir.setEvidenceUrl(evidenceStorageService.sanitizeEvidenceUrl(req.getEvidenceUrl()));
            }
            if (req.getAffectedQuantity() != null) {
                ir.setAffectedQuantity(req.getAffectedQuantity());
            }
            if (req.getStatus() != null && !req.getStatus().isBlank()) {
                String newSt = req.getStatus().trim().toUpperCase(Locale.ROOT);
                ir.setStatus(newSt);
                if ("VERIFIED".equals(newSt)) {
                    ir.setFinalConclusion("CONFIRMED");
                } else if ("REJECTED".equals(newSt)) {
                    ir.setFinalConclusion("REJECTED");
                }
            }
            if (req.getCustomerNotifiedAt() != null) {
                ir.setCustomerNotifiedAt(req.getCustomerNotifiedAt());
            }
        }
        ir.setUpdatedBy(actor.getUserId());
        ir = incidentRepository.save(ir);
        if (evidenceStorageService != null && ir.getEvidenceUrl() != null) {
            evidenceStorageService.linkEvidenceToIncident(ir.getEvidenceUrl(), ir.getId(), actor.getUserId(), null);
        }
        appendVerification(ir, actor, "INCIDENT_UPDATED", null,
                "Updated incident details (customerNotifiedAt=" + ir.getCustomerNotifiedAt() + ")", ir.getEvidenceUrl());
        recordIncidentAudit(ir, actor, "INCIDENT_UPDATED",
                "Updated incident " + ir.getIncidentCode() + " (customerNotifiedAt=" + ir.getCustomerNotifiedAt() + ")");
        return mapToDto(ir);
    }

    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public IncidentResponseDto updateCustomerNotifiedAt(Long incidentId, LocalDateTime customerNotifiedAt, Long actorUserId) {
        User actor = requireManagerOrAdminUser(actorUserId);
        IncidentReport ir = incidentRepository.findByIdForUpdate(incidentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "Incident not found."));
        enforceBranchIfNotAdmin(actor, ir);
        ir.setCustomerNotifiedAt(customerNotifiedAt);
        ir.setUpdatedBy(actor.getUserId());
        ir = incidentRepository.save(ir);
        appendVerification(ir, actor, "CUSTOMER_NOTIFIED_UPDATED", null,
                "Updated customer_notified_at to " + customerNotifiedAt, null);
        recordIncidentAudit(ir, actor, "INCIDENT_CUSTOMER_NOTIFIED_UPDATED",
                "Updated customer_notified_at to " + customerNotifiedAt);
        return mapToDto(ir);
    }

    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public void deleteIncident(Long incidentId, Long actorUserId) {
        User actor = requireManagerOrAdminUser(actorUserId);
        IncidentReport ir = incidentRepository.findByIdForUpdate(incidentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "Incident not found."));
        enforceBranchIfNotAdmin(actor, ir);
        if (entityManager != null) {
            Long linkedRefunds = entityManager.createQuery(
                            "SELECT COUNT(rr) FROM RefundRequest rr WHERE rr.incidentReport.id = :irId", Long.class)
                    .setParameter("irId", incidentId)
                    .getSingleResult();
            if (linkedRefunds != null && linkedRefunds > 0) {
                throw new BusinessException(HttpStatus.CONFLICT, "INCIDENT_LINKED_TO_REFUNDS",
                        "Cannot delete incident report because it is linked to " + linkedRefunds + " refund request(s).");
            }
            entityManager.createQuery("DELETE FROM IncidentVerification iv WHERE iv.incidentReport.id = :irId")
                    .setParameter("irId", incidentId)
                    .executeUpdate();
            entityManager.createQuery("DELETE FROM StaffErrorLog sel WHERE sel.incidentReport.id = :irId")
                    .setParameter("irId", incidentId)
                    .executeUpdate();
        }
        recordIncidentAudit(ir, actor, "INCIDENT_DELETED", "Deleted incident " + ir.getIncidentCode());
        incidentRepository.delete(ir);
    }

    private List<StaffErrorLog> findStaffErrorLogsByIncident(Long incidentId) {
        if (entityManager == null || incidentId == null) {
            return List.of();
        }
        return entityManager.createQuery(
                        "SELECT sel FROM StaffErrorLog sel WHERE sel.incidentReport.id = :irId",
                        StaffErrorLog.class)
                .setParameter("irId", incidentId)
                .getResultList();
    }

    @PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
    @Transactional(readOnly = true)
    public Page<IncidentResponseDto> listIncidents(Long requestedCinemaId, String status, String group, int page, int size, Long currentUserId) {
        User currentUser = requireUser(currentUserId);
        Long effectiveCinemaId = requestedCinemaId;
        if (!"ADMIN".equalsIgnoreCase(currentUser.getRole())) {
            if (currentUser.getCinemaId() == null) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "NO_BRANCH_ASSIGNED", "Your account has no cinema branch assigned.");
            }
            effectiveCinemaId = currentUser.getCinemaId();
        }
        String st = (status != null && !status.isBlank()) ? status.trim().toUpperCase() : null;
        String grp = (group != null && !group.isBlank()) ? group.trim().toUpperCase() : null;
        return incidentRepository.searchIncidents(effectiveCinemaId, st, grp, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)))
                .map(this::mapToDto);
    }

    @PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
    @Transactional(readOnly = true)
    public IncidentResponseDto getIncident(Long incidentId, Long currentUserId) {
        User currentUser = requireUser(currentUserId);
        IncidentReport ir = incidentRepository.findById(incidentId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "Incident not found."));
        enforceBranchIfNotAdmin(currentUser, ir);
        return mapToDto(ir);
    }

    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    @Transactional(readOnly = true)
    public Page<StaffErrorLogDto> listStaffErrors(Long requestedCinemaId, Long staffUserId, int page, int size, Long currentUserId) {
        User currentUser = requireUser(currentUserId);
        Long effectiveCinemaId = requestedCinemaId;
        if (!"ADMIN".equalsIgnoreCase(currentUser.getRole())) {
            if (currentUser.getCinemaId() == null) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "NO_BRANCH_ASSIGNED", "Your account has no cinema branch assigned.");
            }
            effectiveCinemaId = currentUser.getCinemaId();
        }
        return staffErrorLogRepository.findFiltered(effectiveCinemaId, staffUserId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)))
                .map(sel -> {
                    StaffErrorLogDto dto = new StaffErrorLogDto();
                    dto.setId(sel.getId());
                    dto.setCinemaId(sel.getCinema() != null ? sel.getCinema().getCinemaId() : null);
                    dto.setCinemaName(sel.getCinema() != null ? sel.getCinema().getCinemaName() : null);
                    dto.setStaffUserId(sel.getStaffUser() != null ? sel.getStaffUser().getUserId() : null);
                    dto.setStaffName(sel.getStaffUser() != null ? sel.getStaffUser().getFullName() : null);
                    dto.setReportedByName(sel.getReportedByUser() != null ? sel.getReportedByUser().getFullName() : null);
                    dto.setIncidentCode(sel.getIncidentReport() != null ? sel.getIncidentReport().getIncidentCode() : null);
                    dto.setBookingCode(sel.getBooking() != null ? sel.getBooking().getBookingCode() : null);
                    dto.setErrorCategory(sel.getErrorCategory());
                    dto.setSeverity(sel.getSeverity());
                    dto.setDescription(sel.getDescription());
                    dto.setCorrectiveAction(sel.getCorrectiveAction());
                    dto.setStatus(sel.getStatus());
                    dto.setOccurredAt(sel.getOccurredAt());
                    dto.setCreatedAt(sel.getCreatedAt());
                    return dto;
                });
    }

    public IncidentResponseDto mapToDto(IncidentReport ir) {
        IncidentResponseDto dto = new IncidentResponseDto();
        dto.setId(ir.getId());
        dto.setIncidentCode(ir.getIncidentCode());
        dto.setCinemaId(ir.getCinema() != null ? ir.getCinema().getCinemaId() : null);
        dto.setCinemaName(ir.getCinema() != null ? ir.getCinema().getCinemaName() : null);
        dto.setShowtimeId(ir.getShowtime() != null ? ir.getShowtime().getShowtimeId() : null);
        dto.setBookingId(ir.getBooking() != null ? ir.getBooking().getBookingId() : null);
        dto.setBookingCode(ir.getBooking() != null ? ir.getBooking().getBookingCode() : null);
        dto.setBookingFoodItemId(ir.getBookingFoodItemId());
        dto.setBookingFoodItemComponentId(ir.getBookingFoodItemComponentId());
        dto.setIncidentGroup(ir.getIncidentGroup());
        dto.setIncidentType(ir.getIncidentType());
        dto.setSeverity(ir.getSeverity());
        dto.setTitle(ir.getTitle());
        dto.setDescription(ir.getDescription());
        dto.setEvidenceUrl(ir.getEvidenceUrl());
        dto.setAffectedQuantity(ir.getAffectedQuantity());
        dto.setOccurredAt(ir.getOccurredAt());
        dto.setCustomerNotifiedAt(ir.getCustomerNotifiedAt());
        dto.setReportedByName(ir.getReportedByStaff() != null ? ir.getReportedByStaff().getFullName() : null);
        dto.setStatus(ir.getStatus());
        dto.setManagerVerifiedByName(ir.getManagerVerifiedBy() != null ? ir.getManagerVerifiedBy().getFullName() : null);
        dto.setManagerDecision(ir.getManagerDecision());
        dto.setManagerNote(ir.getManagerNote());
        dto.setManagerDecidedAt(ir.getManagerDecidedAt());
        dto.setAppealedAt(ir.getAppealedAt());
        dto.setAppealedByName(ir.getAppealedBy() != null ? ir.getAppealedBy().getFullName() : null);
        dto.setAppealReason(ir.getAppealReason());
        dto.setAdminResolvedByName(ir.getAdminResolvedBy() != null ? ir.getAdminResolvedBy().getFullName() : null);
        dto.setAdminDecision(ir.getAdminDecision());
        dto.setAdminNote(ir.getAdminNote());
        dto.setAdminResolvedAt(ir.getAdminResolvedAt());
        dto.setFinalConclusion(ir.getFinalConclusion());
        dto.setUpdatedBy(ir.getUpdatedBy());
        dto.setCreatedAt(ir.getCreatedAt());

        List<IncidentVerification> trail = verificationRepository.findByIncidentReport_IdOrderByCreatedAtAsc(ir.getId());
        for (IncidentVerification v : trail) {
            IncidentResponseDto.VerificationEntryDto entry = new IncidentResponseDto.VerificationEntryDto();
            entry.setActorName(v.getActorUser() != null ? v.getActorUser().getFullName() : null);
            entry.setActorRole(v.getActorRole());
            entry.setAction(v.getAction());
            entry.setDecision(v.getDecision());
            entry.setComment(v.getComment());
            entry.setEvidenceUrl(v.getEvidenceUrl());
            entry.setCreatedAt(v.getCreatedAt());
            dto.getVerifications().add(entry);
        }
        return dto;
    }

    private void appendVerification(IncidentReport ir, User actor, String action, String decision, String comment, String evidenceUrl) {
        IncidentVerification iv = new IncidentVerification();
        iv.setIncidentReport(ir);
        iv.setActorUser(actor);
        iv.setActorRole(actor.getRole());
        iv.setAction(action);
        iv.setDecision(decision);
        iv.setComment(comment);
        iv.setEvidenceUrl(evidenceUrl);
        verificationRepository.save(iv);
    }

    private User requireUser(Long userId) {
        if (userId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required.");
        }
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND", "User not found."));
    }

    private User requireManagerOrAdminUser(Long userId) {
        User user = requireUser(userId);
        String role = user.getRole() != null ? user.getRole().trim().toUpperCase(Locale.ROOT) : "";
        if (!"MANAGER".equals(role) && !"ADMIN".equals(role)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only MANAGER or ADMIN is permitted to perform this incident operation.");
        }
        return user;
    }

    private void recordIncidentAudit(IncidentReport ir, User actor, String eventType, String note) {
        if (auditLogRepository == null || ir == null) {
            return;
        }
        RefundAuditLog log = new RefundAuditLog();
        log.setEntityType("INCIDENT_REPORT");
        log.setEntityId(ir.getId());
        log.setEventType(eventType);
        if (actor != null) {
            log.setActorUserId(actor.getUserId());
            log.setActorRole(actor.getRole());
        }
        if (ir.getCinema() != null) {
            log.setCinemaId(ir.getCinema().getCinemaId());
        }
        log.setDetailsJson("{\"incidentCode\":\"" + (ir.getIncidentCode() != null ? ir.getIncidentCode() : "")
                + "\",\"note\":\"" + (note != null ? note.replace("\"", "'") : "") + "\"}");
        auditLogRepository.save(log);
    }

    private void enforceBranchIfNotAdmin(User user, IncidentReport ir) {
        if ("ADMIN".equalsIgnoreCase(user.getRole())) return;
        Long userCinemaId = user.getCinemaId();
        Long irCinemaId = ir.getCinema() != null ? ir.getCinema().getCinemaId() : null;
        if (userCinemaId == null || !userCinemaId.equals(irCinemaId)) {
            // Return 404/403 without leaking cross-branch resource existence
            throw new BusinessException(HttpStatus.FORBIDDEN, "BRANCH_ACCESS_DENIED", "Access denied for this cinema branch.");
        }
    }

    private String generateIncidentCode() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String code;
        do {
            code = "INC-" + date + "-" + (1000 + random.nextInt(9000));
        } while (incidentRepository.findByIncidentCode(code).isPresent());
        return code;
    }
}
