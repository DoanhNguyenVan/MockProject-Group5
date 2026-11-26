package com.boxcinema.refund.service;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.*;
import com.boxcinema.refund.dto.RefundLimitConfigDto;
import com.boxcinema.refund.dto.RefundLimitSummaryDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Stage 2 Configurable Refund Approval Limits & Atomic Quota Reservation Service.
 *
 * Enforces:
 * - MANAGER_PER_REQUEST (default 500,000 VND)
 * - MANAGER_DAILY (default 2,000,000 VND, Asia/Ho_Chi_Minh)
 * - CINEMA_DAILY (default 5,000,000 VND, Asia/Ho_Chi_Minh)
 * - INCIDENT_CUMULATIVE (default 10,000,000 VND)
 *
 * Uses deadlock-free sorted InnoDB row locking on refund_limit_buckets (CIN:..., INC:..., MGR:...)
 * and lifecycle-tracked rows in refund_limit_reservations (RESERVED -> COMMITTED / RELEASED),
 * including Asia/Ho_Chi_Minh midnight rollover re-validation before payout.
 */
@Service
public class RefundLimitService {

    public static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    public static final long DEFAULT_MANAGER_PER_REQUEST_VND = 500_000L;
    public static final long DEFAULT_MANAGER_DAILY_VND = 2_000_000L;
    public static final long DEFAULT_CINEMA_DAILY_VND = 5_000_000L;
    public static final long DEFAULT_INCIDENT_CUMULATIVE_VND = 10_000_000L;

    private static final Set<String> VALID_CONFIG_KEYS = Set.of(
            RefundLimitConfig.KEY_MANAGER_PER_REQUEST,
            RefundLimitConfig.KEY_MANAGER_DAILY,
            RefundLimitConfig.KEY_CINEMA_DAILY,
            RefundLimitConfig.KEY_INCIDENT_CUMULATIVE
    );

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final RefundLimitConfigRepository configRepository;
    private final RefundLimitConfigHistoryRepository configHistoryRepository;
    private final RefundLimitBucketRepository bucketRepository;
    private final RefundLimitReservationRepository reservationRepository;
    private final RefundLimitOverrideRepository overrideRepository;
    private final RefundAuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final JdbcTemplate jdbcTemplate;

    private volatile Clock clock = Clock.system(VN_ZONE);

    @Autowired
    public RefundLimitService(RefundLimitConfigRepository configRepository,
                              RefundLimitConfigHistoryRepository configHistoryRepository,
                              RefundLimitBucketRepository bucketRepository,
                              RefundLimitReservationRepository reservationRepository,
                              RefundLimitOverrideRepository overrideRepository,
                              RefundAuditLogRepository auditLogRepository,
                              UserRepository userRepository,
                              JdbcTemplate jdbcTemplate) {
        this.configRepository = configRepository;
        this.configHistoryRepository = configHistoryRepository;
        this.bucketRepository = bucketRepository;
        this.reservationRepository = reservationRepository;
        this.overrideRepository = overrideRepository;
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Clock getClock() {
        return clock;
    }

    public void setClock(Clock clock) {
        this.clock = (clock != null) ? clock.withZone(VN_ZONE) : Clock.system(VN_ZONE);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public static String cinemaBucketKey(Long cinemaId, LocalDate date) {
        return "CIN:" + cinemaId + ":" + date;
    }

    public static String incidentBucketKey(Long incidentId) {
        return "INC:" + incidentId;
    }

    public static String managerBucketKey(Long managerId, LocalDate date) {
        return "MGR:" + managerId + ":" + date;
    }

    public long getLimitAmountVnd(String configKey) {
        return configRepository.findByConfigKey(configKey)
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()) && c.getLimitAmountVnd() != null)
                .map(RefundLimitConfig::getLimitAmountVnd)
                .orElseGet(() -> defaultLimitFor(configKey));
    }

    private long defaultLimitFor(String configKey) {
        if (RefundLimitConfig.KEY_MANAGER_PER_REQUEST.equals(configKey)) {
            return DEFAULT_MANAGER_PER_REQUEST_VND;
        }
        if (RefundLimitConfig.KEY_MANAGER_DAILY.equals(configKey)) {
            return DEFAULT_MANAGER_DAILY_VND;
        }
        if (RefundLimitConfig.KEY_CINEMA_DAILY.equals(configKey)) {
            return DEFAULT_CINEMA_DAILY_VND;
        }
        if (RefundLimitConfig.KEY_INCIDENT_CUMULATIVE.equals(configKey)) {
            return DEFAULT_INCIDENT_CUMULATIVE_VND;
        }
        throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT_CONFIG_KEY",
                "Unknown refund limit config key: " + configKey);
    }

    @Transactional
    public List<RefundLimitConfigDto> getAllLimitConfigs() {
        ensureDefaultConfigsSeeded();
        List<RefundLimitConfig> configs = configRepository.findAllByOrderByConfigKeyAsc();
        List<RefundLimitConfigDto> dtos = new ArrayList<>();
        for (RefundLimitConfig cfg : configs) {
            dtos.add(toConfigDto(cfg));
        }
        return dtos;
    }

    @Transactional
    public RefundLimitConfigDto updateLimitConfig(String configKey,
                                                  Long newLimitAmountVnd,
                                                  String reason,
                                                  Long adminUserId) {
        if (adminUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Admin authentication required.");
        }
        User admin = userRepository.findById(adminUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User not found."));
        if (!"ADMIN".equalsIgnoreCase(admin.getRole())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "Only ADMIN is authorized to update refund limit configurations.");
        }
        if (configKey == null || !VALID_CONFIG_KEYS.contains(configKey.trim().toUpperCase(Locale.ROOT))) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT_CONFIG_KEY",
                    "Invalid configKey. Allowed keys: " + VALID_CONFIG_KEYS);
        }
        String cleanKey = configKey.trim().toUpperCase(Locale.ROOT);
        if (newLimitAmountVnd == null || newLimitAmountVnd < 0L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_LIMIT_AMOUNT",
                    "limitAmountVnd must be a non-negative VND integer.");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "LIMIT_CHANGE_REASON_REQUIRED",
                    "A mandatory reason is required when updating refund limit configurations.");
        }
        String cleanReason = reason.trim();
        ensureDefaultConfigsSeeded();

        RefundLimitConfig cfg = configRepository.findByConfigKey(cleanKey)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "LIMIT_CONFIG_NOT_FOUND",
                        "Limit config not found: " + cleanKey));
        long previousAmount = cfg.getLimitAmountVnd() != null ? cfg.getLimitAmountVnd() : defaultLimitFor(cleanKey);

        LocalDateTime nowHcm = now();
        cfg.setLimitAmountVnd(newLimitAmountVnd);
        cfg.setUpdatedBy(admin.getUserId());
        cfg.setUpdatedAt(nowHcm);
        configRepository.save(cfg);

        RefundLimitConfigHistory history = new RefundLimitConfigHistory();
        history.setConfigKey(cleanKey);
        history.setPreviousAmountVnd(previousAmount);
        history.setNewAmountVnd(newLimitAmountVnd);
        history.setChangedBy(admin.getUserId());
        history.setReason(cleanReason);
        history.setCreatedAt(nowHcm);
        configHistoryRepository.save(history);

        RefundAuditLog audit = new RefundAuditLog();
        audit.setEntityType("REFUND_LIMIT_CONFIG");
        audit.setEntityId(cfg.getId());
        audit.setEventType("REFUND_LIMIT_CONFIG_UPDATED");
        audit.setActorUserId(admin.getUserId());
        audit.setActorRole(admin.getRole());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("configKey", cleanKey);
        details.put("previousAmountVnd", previousAmount);
        details.put("newAmountVnd", newLimitAmountVnd);
        details.put("reason", cleanReason);
        audit.setDetailsJson(toJson(details));
        auditLogRepository.save(audit);

        return toConfigDto(cfg);
    }

    /**
     * Atomically locks the relevant quota buckets in deterministic sorted order (CIN:..., INC:..., MGR:...),
     * validates MANAGER_PER_REQUEST, MANAGER_DAILY, CINEMA_DAILY, and INCIDENT_CUMULATIVE thresholds,
     * enforces ADMIN override rules when any threshold is exceeded by >= 1 VND, and records a RESERVED
     * row in refund_limit_reservations without double-counting the request against itself.
     */
    @Transactional
    public void reserveOnApproval(RefundRequest req, User approver, long cashVnd) {
        reserveOnApproval(req, approver, cashVnd, null, null);
    }

    @Transactional
    public void reserveOnApproval(RefundRequest req,
                                  User approver,
                                  long cashVnd,
                                  Boolean limitOverrideApproved,
                                  String limitOverrideReason) {
        if (req == null || cashVnd <= 0L) {
            return;
        }
        Long cinemaId = resolveCinemaId(req);
        Long incidentId = req.getIncidentReport() != null ? req.getIncidentReport().getId() : null;
        boolean isAdmin = approver != null && "ADMIN".equalsIgnoreCase(approver.getRole());
        boolean isManager = approver != null && "MANAGER".equalsIgnoreCase(approver.getRole());

        Long managerId = null;
        if (isManager) {
            managerId = approver.getUserId();
        } else if (req.getReviewedBy() != null && "MANAGER".equalsIgnoreCase(req.getReviewedBy().getRole())) {
            managerId = req.getReviewedBy().getUserId();
        }

        LocalDate approvalDate = today();
        LocalDateTime nowHcm = now();

        // Canonical Lock Hierarchy Step 1 (Non-locking metadata peek): build sorted bucket specs BEFORE locking any reservation row
        TreeMap<String, BucketSpec> specs = new TreeMap<>();
        specs.put(cinemaBucketKey(cinemaId, approvalDate),
                new BucketSpec(cinemaBucketKey(cinemaId, approvalDate), "CINEMA_DAILY", cinemaId, approvalDate));
        if (incidentId != null) {
            specs.put(incidentBucketKey(incidentId),
                    new BucketSpec(incidentBucketKey(incidentId), "INCIDENT_CUMULATIVE", incidentId, null));
        }
        if (managerId != null) {
            specs.put(managerBucketKey(managerId, approvalDate),
                    new BucketSpec(managerBucketKey(managerId, approvalDate), "MANAGER_DAILY", managerId, approvalDate));
        }
        peekExistingReservationSpecs(req.getId(), specs);

        // Canonical Lock Hierarchy Step 2 (Lock Level 1): lock refund_limit_buckets FOR UPDATE in deterministic TreeMap order
        Map<String, RefundLimitBucket> lockedBuckets = ensureAndLockBuckets(specs);

        // Canonical Lock Hierarchy Step 3 (Lock Level 2): lock refund_limit_reservations FOR UPDATE / FOR SHARE
        Optional<RefundLimitReservation> existingOpt = reservationRepository.findByActiveLockKeyForUpdate("REQ:" + req.getId());

        long mgrPerReqLimit = getLimitAmountVnd(RefundLimitConfig.KEY_MANAGER_PER_REQUEST);
        long mgrDailyLimit = getLimitAmountVnd(RefundLimitConfig.KEY_MANAGER_DAILY);
        long cinemaDailyLimit = getLimitAmountVnd(RefundLimitConfig.KEY_CINEMA_DAILY);
        long incidentLimit = getLimitAmountVnd(RefundLimitConfig.KEY_INCIDENT_CUMULATIVE);

        long cinemaUsed = sumCinemaActiveExcluding(cinemaId, approvalDate, req.getId());
        long incidentUsed = incidentId != null ? sumIncidentActiveExcluding(incidentId, req.getId()) : 0L;
        long managerUsed = managerId != null ? sumManagerActiveExcluding(managerId, approvalDate, req.getId()) : 0L;

        List<String> breachedLimits = new ArrayList<>();
        if (cashVnd > mgrPerReqLimit) {
            breachedLimits.add("MANAGER_PER_REQUEST(" + cashVnd + ">" + mgrPerReqLimit + ")");
        }
        if (managerId != null && (managerUsed + cashVnd > mgrDailyLimit)) {
            breachedLimits.add("MANAGER_DAILY(" + (managerUsed + cashVnd) + ">" + mgrDailyLimit + ")");
        }
        if (cinemaUsed + cashVnd > cinemaDailyLimit) {
            breachedLimits.add("CINEMA_DAILY(" + (cinemaUsed + cashVnd) + ">" + cinemaDailyLimit + ")");
        }
        if (incidentId != null && (incidentUsed + cashVnd > incidentLimit)) {
            breachedLimits.add("INCIDENT_CUMULATIVE(" + (incidentUsed + cashVnd) + ">" + incidentLimit + ")");
        }

        boolean overrideApplied = req.isLimitOverrideApplied();
        if (!isAdmin) {
            if (Boolean.TRUE.equals(limitOverrideApproved)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "MANAGER_CANNOT_OVERRIDE_LIMIT",
                        "Manager cannot override refund limits; escalate the request to ADMIN.");
            }
            if (!breachedLimits.isEmpty()) {
                throw new BusinessException(HttpStatus.CONFLICT, "LIMIT_EXCEEDED_ESCALATION_REQUIRED",
                        "Refund limit exceeded: " + String.join(", ", breachedLimits)
                                + ". Manager must escalate to ADMIN for explicit limit override.");
            }
        } else {
            boolean needsOverride = !breachedLimits.isEmpty() || Boolean.TRUE.equals(limitOverrideApproved);
            if (needsOverride) {
                if (!Boolean.TRUE.equals(limitOverrideApproved) && !overrideApplied) {
                    throw new BusinessException(HttpStatus.CONFLICT, "LIMIT_OVERRIDE_REQUIRED",
                            "Refund limit exceeded: " + String.join(", ", breachedLimits)
                                    + ". ADMIN must set limitOverrideApproved=true and provide a mandatory limitOverrideReason.");
                }
                String effectiveReason = (limitOverrideReason != null && !limitOverrideReason.trim().isEmpty())
                        ? limitOverrideReason.trim()
                        : req.getLimitOverrideReason();
                if (effectiveReason == null || effectiveReason.trim().isEmpty()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "LIMIT_OVERRIDE_REASON_REQUIRED",
                            "A mandatory limitOverrideReason is required when overriding refund limits.");
                }
                overrideApplied = true;
                req.setLimitOverrideApplied(true);
                req.setLimitOverrideBy(approver.getUserId());
                req.setLimitOverrideReason(effectiveReason);
                req.setLimitOverrideAt(nowHcm);

                RefundLimitOverride overrideEntity = new RefundLimitOverride();
                overrideEntity.setRefundRequestId(req.getId());
                overrideEntity.setAdminUserId(approver.getUserId());
                overrideEntity.setBreachedLimitsJson(toJson(breachedLimits.isEmpty()
                        ? List.of("ADMIN_EXPLICIT_OVERRIDE") : breachedLimits));
                overrideEntity.setApprovedAmountVnd(cashVnd);
                overrideEntity.setOverrideReason(effectiveReason);
                overrideEntity.setCreatedAt(nowHcm);
                overrideRepository.save(overrideEntity);

                RefundAuditLog audit = new RefundAuditLog();
                audit.setRefundRequestId(req.getId());
                audit.setEntityType("REFUND_REQUEST");
                audit.setEntityId(req.getId());
                audit.setEventType("REFUND_LIMIT_OVERRIDE_APPLIED");
                audit.setActorUserId(approver.getUserId());
                audit.setActorRole(approver.getRole());
                audit.setCinemaId(cinemaId);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("approvedAmountVnd", cashVnd);
                details.put("breachedLimits", breachedLimits);
                details.put("overrideReason", effectiveReason);
                audit.setDetailsJson(toJson(details));
                auditLogRepository.save(audit);
            }
        }

        RefundLimitReservation res = existingOpt.orElseGet(RefundLimitReservation::new);
        res.setRefundRequestId(req.getId());
        res.setManagerUserId(isManager ? approver.getUserId() : managerId);
        res.setCinemaId(cinemaId);
        res.setIncidentReportId(incidentId);
        res.setQuotaDate(approvalDate);
        res.setAmountVnd(cashVnd);
        res.setStatus(RefundLimitReservation.STATUS_RESERVED);
        res.setActiveLockKey("REQ:" + req.getId());
        res.setOverrideApplied(overrideApplied);
        res.setReservedAt(nowHcm);
        res.setUpdatedAt(nowHcm);
        res.setReleasedAt(null);
        res.setReleaseReason(null);
        reservationRepository.saveAndFlush(res);

        syncLockedBuckets(specs, lockedBuckets);
    }

    /**
     * Re-validates Cinema daily limit and Incident cumulative limit before payout dispatch.
     * If Asia/Ho_Chi_Minh date has rolled over past midnight (today > reservation.quotaDate),
     * locks both Day D and Day D+1 Cinema buckets in deterministic order, verifies Day D+1 capacity
     * without double-counting the request's own reservation, and rolls the Cinema reservation date
     * to Day D+1 while keeping the Manager's approval date anchored to Day D (reservedAt).
     */
    @Transactional
    public void revalidateAndRolloverBeforePayout(RefundRequest req, User payoutAdmin, long cashVnd) {
        revalidateAndRolloverBeforePayout(req, payoutAdmin, cashVnd, null, null);
    }

    @Transactional
    public void revalidateAndRolloverBeforePayout(RefundRequest req,
                                                  User payoutAdmin,
                                                  long cashVnd,
                                                  Boolean limitOverrideApproved,
                                                  String limitOverrideReason) {
        if (req == null || cashVnd <= 0L) {
            return;
        }
        User effectiveAdmin = payoutAdmin != null ? payoutAdmin : req.getPayoutDecidedBy();
        Integer activeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_limit_reservations WHERE active_lock_key = ?",
                Integer.class,
                "REQ:" + req.getId()
        );
        if (activeCount == null || activeCount == 0) {
            reserveOnApproval(req, effectiveAdmin, cashVnd, limitOverrideApproved, limitOverrideReason);
            return;
        }

        LocalDate todayHcm = today();
        LocalDateTime nowHcm = now();
        Long fallbackCinemaId = resolveCinemaId(req);
        Long fallbackIncidentId = req.getIncidentReport() != null ? req.getIncidentReport().getId() : null;

        // Canonical Lock Hierarchy Step 1 (Non-locking metadata peek): build sorted bucket specs including both old and today dates
        TreeMap<String, BucketSpec> specs = new TreeMap<>();
        specs.put(cinemaBucketKey(fallbackCinemaId, todayHcm),
                new BucketSpec(cinemaBucketKey(fallbackCinemaId, todayHcm), "CINEMA_DAILY", fallbackCinemaId, todayHcm));
        if (fallbackIncidentId != null) {
            specs.put(incidentBucketKey(fallbackIncidentId),
                    new BucketSpec(incidentBucketKey(fallbackIncidentId), "INCIDENT_CUMULATIVE", fallbackIncidentId, null));
        }
        peekExistingReservationSpecs(req.getId(), specs);

        // Canonical Lock Hierarchy Step 2 (Lock Level 1): lock refund_limit_buckets FOR UPDATE in deterministic TreeMap order
        Map<String, RefundLimitBucket> lockedBuckets = ensureAndLockBuckets(specs);

        // Canonical Lock Hierarchy Step 3 (Lock Level 2): lock refund_limit_reservations FOR UPDATE / FOR SHARE
        Optional<RefundLimitReservation> existingOpt = reservationRepository.findByActiveLockKeyForUpdate("REQ:" + req.getId());
        if (existingOpt.isEmpty()) {
            return;
        }
        RefundLimitReservation res = existingOpt.get();
        if (RefundLimitReservation.STATUS_COMMITTED.equalsIgnoreCase(res.getStatus())) {
            return;
        }

        Long cinemaId = res.getCinemaId() != null ? res.getCinemaId() : fallbackCinemaId;
        Long incidentId = res.getIncidentReportId() != null ? res.getIncidentReportId() : fallbackIncidentId;
        Long managerId = res.getManagerUserId();
        LocalDate oldCinemaDate = res.getQuotaDate() != null ? res.getQuotaDate() : todayHcm;
        LocalDate managerApprovalDate = res.getReservedAt() != null
                ? res.getReservedAt().toLocalDate()
                : oldCinemaDate;
        boolean isMidnightRollover = !todayHcm.equals(oldCinemaDate);

        long mgrPerReqLimit = getLimitAmountVnd(RefundLimitConfig.KEY_MANAGER_PER_REQUEST);
        long mgrDailyLimit = getLimitAmountVnd(RefundLimitConfig.KEY_MANAGER_DAILY);
        long cinemaDailyLimit = getLimitAmountVnd(RefundLimitConfig.KEY_CINEMA_DAILY);
        long incidentLimit = getLimitAmountVnd(RefundLimitConfig.KEY_INCIDENT_CUMULATIVE);

        long cinemaUsedToday = sumCinemaActiveExcluding(cinemaId, todayHcm, req.getId());
        long incidentUsed = incidentId != null ? sumIncidentActiveExcluding(incidentId, req.getId()) : 0L;
        long managerUsedOnApprovalDay = managerId != null
                ? sumManagerActiveExcluding(managerId, managerApprovalDate, req.getId())
                : 0L;

        List<String> breachedLimits = new ArrayList<>();
        if (managerId != null && cashVnd > mgrPerReqLimit) {
            breachedLimits.add("MANAGER_PER_REQUEST(" + cashVnd + ">" + mgrPerReqLimit + ")");
        }
        if (managerId != null && (managerUsedOnApprovalDay + cashVnd > mgrDailyLimit)) {
            breachedLimits.add("MANAGER_DAILY(" + (managerUsedOnApprovalDay + cashVnd) + ">" + mgrDailyLimit + ")");
        }
        if (cinemaUsedToday + cashVnd > cinemaDailyLimit) {
            breachedLimits.add("CINEMA_DAILY(" + (cinemaUsedToday + cashVnd) + ">" + cinemaDailyLimit + ")");
        }
        if (incidentId != null && (incidentUsed + cashVnd > incidentLimit)) {
            breachedLimits.add("INCIDENT_CUMULATIVE(" + (incidentUsed + cashVnd) + ">" + incidentLimit + ")");
        }

        boolean overrideAlreadyApplied = Boolean.TRUE.equals(res.getOverrideApplied())
                || req.isLimitOverrideApplied();
        boolean newOverrideRequested = Boolean.TRUE.equals(limitOverrideApproved);

        if (!breachedLimits.isEmpty() && !overrideAlreadyApplied && !newOverrideRequested) {
            String errCode = isMidnightRollover ? "CINEMA_DAILY_LIMIT_EXCEEDED_ON_ROLLOVER" : "LIMIT_EXCEEDED_BEFORE_PAYOUT";
            throw new BusinessException(HttpStatus.CONFLICT, errCode,
                    "Refund limit exceeded on payout date " + todayHcm + ": " + String.join(", ", breachedLimits)
                            + ". Explicit ADMIN limit override required.");
        }

        if (newOverrideRequested && payoutAdmin != null) {
            String effectiveReason = (limitOverrideReason != null && !limitOverrideReason.trim().isEmpty())
                    ? limitOverrideReason.trim()
                    : req.getLimitOverrideReason();
            if (effectiveReason == null || effectiveReason.trim().isEmpty()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "LIMIT_OVERRIDE_REASON_REQUIRED",
                        "A mandatory limitOverrideReason is required when overriding refund limits.");
            }
            req.setLimitOverrideApplied(true);
            req.setLimitOverrideBy(payoutAdmin.getUserId());
            req.setLimitOverrideReason(effectiveReason);
            req.setLimitOverrideAt(nowHcm);
            res.setOverrideApplied(true);

            RefundLimitOverride overrideEntity = new RefundLimitOverride();
            overrideEntity.setRefundRequestId(req.getId());
            overrideEntity.setAdminUserId(payoutAdmin.getUserId());
            overrideEntity.setBreachedLimitsJson(toJson(breachedLimits.isEmpty()
                    ? List.of("ADMIN_PAYOUT_ROLLOVER_OVERRIDE") : breachedLimits));
            overrideEntity.setApprovedAmountVnd(cashVnd);
            overrideEntity.setOverrideReason(effectiveReason);
            overrideEntity.setCreatedAt(nowHcm);
            overrideRepository.save(overrideEntity);

            RefundAuditLog audit = new RefundAuditLog();
            audit.setRefundRequestId(req.getId());
            audit.setEntityType("REFUND_REQUEST");
            audit.setEntityId(req.getId());
            audit.setEventType("REFUND_LIMIT_OVERRIDE_APPLIED");
            audit.setActorUserId(payoutAdmin.getUserId());
            audit.setActorRole(payoutAdmin.getRole());
            audit.setCinemaId(cinemaId);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("approvedAmountVnd", cashVnd);
            details.put("breachedLimits", breachedLimits);
            details.put("overrideReason", effectiveReason);
            details.put("rolloverDate", todayHcm.toString());
            audit.setDetailsJson(toJson(details));
            auditLogRepository.save(audit);
        }

        res.setAmountVnd(cashVnd);
        res.setCinemaId(cinemaId);
        res.setIncidentReportId(incidentId);
        if (isMidnightRollover) {
            res.setQuotaDate(todayHcm);
        }
        res.setUpdatedAt(nowHcm);
        reservationRepository.saveAndFlush(res);

        syncLockedBuckets(specs, lockedBuckets);

        if (isMidnightRollover) {
            RefundAuditLog audit = new RefundAuditLog();
            audit.setRefundRequestId(req.getId());
            audit.setEntityType("REFUND_LIMIT_RESERVATION");
            audit.setEntityId(res.getId());
            audit.setEventType("REFUND_LIMIT_RESERVATION_ROLLED_OVER");
            if (payoutAdmin != null) {
                audit.setActorUserId(payoutAdmin.getUserId());
                audit.setActorRole(payoutAdmin.getRole());
            }
            audit.setCinemaId(cinemaId);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("previousCinemaQuotaDate", oldCinemaDate.toString());
            details.put("newCinemaQuotaDate", todayHcm.toString());
            details.put("managerApprovalAnchorDate", managerApprovalDate.toString());
            details.put("amountVnd", cashVnd);
            audit.setDetailsJson(toJson(details));
            auditLogRepository.save(audit);
        }
    }

    /**
     * Transitions RESERVED -> COMMITTED when payout reaches terminal SUCCESS.
     */
    @Transactional
    public void commitReservationsOnPayoutSuccess(Long refundRequestId) {
        if (refundRequestId == null) {
            return;
        }
        TreeMap<String, BucketSpec> specs = new TreeMap<>();
        peekExistingReservationSpecs(refundRequestId, specs);
        if (specs.isEmpty()) {
            return;
        }
        // Canonical Lock Hierarchy: lock refund_limit_buckets FOR UPDATE first, then refund_limit_reservations FOR UPDATE
        Map<String, RefundLimitBucket> lockedBuckets = ensureAndLockBuckets(specs);
        List<RefundLimitReservation> rows = reservationRepository.findByRefundRequestIdForUpdate(refundRequestId);
        if (rows.isEmpty()) {
            return;
        }
        LocalDateTime nowHcm = now();
        boolean anyChanged = false;
        for (RefundLimitReservation res : rows) {
            if (!RefundLimitReservation.STATUS_RESERVED.equalsIgnoreCase(res.getStatus())) {
                continue;
            }
            res.setStatus(RefundLimitReservation.STATUS_COMMITTED);
            res.setCommittedAt(nowHcm);
            res.setUpdatedAt(nowHcm);
            reservationRepository.saveAndFlush(res);
            anyChanged = true;
        }
        if (anyChanged) {
            syncLockedBuckets(specs, lockedBuckets);
        }
    }

    /**
     * Retains RESERVED status when payout is in PROCESSING or UNKNOWN state.
     */
    @Transactional
    public void retainReservationsOnProcessingOrUnknown(Long refundRequestId) {
        if (refundRequestId == null) {
            return;
        }
        TreeMap<String, BucketSpec> specs = new TreeMap<>();
        peekExistingReservationSpecs(refundRequestId, specs);
        if (specs.isEmpty()) {
            return;
        }
        // Canonical Lock Hierarchy: lock refund_limit_buckets FOR UPDATE first, then refund_limit_reservations FOR UPDATE
        ensureAndLockBuckets(specs);
        List<RefundLimitReservation> rows = reservationRepository.findByRefundRequestIdForUpdate(refundRequestId);
        LocalDateTime nowHcm = now();
        for (RefundLimitReservation res : rows) {
            if (RefundLimitReservation.STATUS_RESERVED.equalsIgnoreCase(res.getStatus())) {
                res.setUpdatedAt(nowHcm);
                reservationRepository.save(res);
            }
        }
    }

    /**
     * Transitions RESERVED -> RELEASED (and clears active_lock_key) when a refund or payout is
     * FAILED, CANCELLED, or REJECTED. Never releases already COMMITTED reservations.
     */
    @Transactional
    public void releaseReservationsOnFailureCancelOrReject(Long refundRequestId, String reason) {
        if (refundRequestId == null) {
            return;
        }
        TreeMap<String, BucketSpec> specs = new TreeMap<>();
        peekExistingReservationSpecs(refundRequestId, specs);
        if (specs.isEmpty()) {
            return;
        }
        // Canonical Lock Hierarchy: lock refund_limit_buckets FOR UPDATE first, then refund_limit_reservations FOR UPDATE
        Map<String, RefundLimitBucket> lockedBuckets = ensureAndLockBuckets(specs);
        List<RefundLimitReservation> rows = reservationRepository.findByRefundRequestIdForUpdate(refundRequestId);
        if (rows.isEmpty()) {
            return;
        }
        LocalDateTime nowHcm = now();
        String cleanReason = (reason != null && !reason.trim().isEmpty())
                ? (reason.trim().length() > 250 ? reason.trim().substring(0, 250) : reason.trim())
                : "RELEASED";
        boolean anyChanged = false;
        for (RefundLimitReservation res : rows) {
            if (!RefundLimitReservation.STATUS_RESERVED.equalsIgnoreCase(res.getStatus())) {
                continue;
            }
            res.setStatus(RefundLimitReservation.STATUS_RELEASED);
            res.setActiveLockKey(null);
            res.setReleasedAt(nowHcm);
            res.setReleaseReason(cleanReason);
            res.setUpdatedAt(nowHcm);
            reservationRepository.saveAndFlush(res);
            anyChanged = true;
        }
        if (anyChanged) {
            syncLockedBuckets(specs, lockedBuckets);
        }
    }

    @Transactional(readOnly = true)
    public RefundLimitSummaryDto getQuotaSummary(Long cinemaId,
                                                 Long managerUserId,
                                                 Long incidentId,
                                                 LocalDate date) {
        LocalDate targetDate = (date != null) ? date : today();
        long mgrPerReqLimit = getLimitAmountVnd(RefundLimitConfig.KEY_MANAGER_PER_REQUEST);
        long mgrDailyLimit = getLimitAmountVnd(RefundLimitConfig.KEY_MANAGER_DAILY);
        long cinemaDailyLimit = getLimitAmountVnd(RefundLimitConfig.KEY_CINEMA_DAILY);
        long incidentLimit = getLimitAmountVnd(RefundLimitConfig.KEY_INCIDENT_CUMULATIVE);

        RefundLimitSummaryDto dto = new RefundLimitSummaryDto();
        dto.setQuotaDate(targetDate);
        dto.setManagerPerRequestLimitVnd(mgrPerReqLimit);
        dto.setManagerDailyLimitVnd(mgrDailyLimit);
        dto.setCinemaDailyLimitVnd(cinemaDailyLimit);
        dto.setIncidentCumulativeLimitVnd(incidentLimit);

        if (managerUserId != null) {
            dto.setManagerUserId(managerUserId);
            long committed = sumManagerByDateAndStatus(managerUserId, targetDate, RefundLimitReservation.STATUS_COMMITTED);
            long reserved = sumManagerByDateAndStatus(managerUserId, targetDate, RefundLimitReservation.STATUS_RESERVED);
            long released = sumManagerByDateAndStatus(managerUserId, targetDate, RefundLimitReservation.STATUS_RELEASED);
            dto.setManagerCommittedVnd(committed);
            dto.setManagerReservedVnd(reserved);
            dto.setManagerReleasedVnd(released);
            dto.setManagerAvailableVnd(Math.max(0L, mgrDailyLimit - committed - reserved));
        }

        if (cinemaId != null) {
            dto.setCinemaId(cinemaId);
            long committed = sumCinemaByDateAndStatus(cinemaId, targetDate, RefundLimitReservation.STATUS_COMMITTED);
            long reserved = sumCinemaByDateAndStatus(cinemaId, targetDate, RefundLimitReservation.STATUS_RESERVED);
            long released = sumCinemaByDateAndStatus(cinemaId, targetDate, RefundLimitReservation.STATUS_RELEASED);
            dto.setCinemaCommittedVnd(committed);
            dto.setCinemaReservedVnd(reserved);
            dto.setCinemaReleasedVnd(released);
            dto.setCinemaAvailableVnd(Math.max(0L, cinemaDailyLimit - committed - reserved));
        }

        if (incidentId != null) {
            dto.setIncidentId(incidentId);
            long committed = sumIncidentByStatus(incidentId, RefundLimitReservation.STATUS_COMMITTED);
            long reserved = sumIncidentByStatus(incidentId, RefundLimitReservation.STATUS_RESERVED);
            long released = sumIncidentByStatus(incidentId, RefundLimitReservation.STATUS_RELEASED);
            dto.setIncidentCommittedVnd(committed);
            dto.setIncidentReservedVnd(reserved);
            dto.setIncidentReleasedVnd(released);
            dto.setIncidentAvailableVnd(Math.max(0L, incidentLimit - committed - reserved));
        }

        return dto;
    }

    private void peekExistingReservationSpecs(Long refundRequestId, TreeMap<String, BucketSpec> specs) {
        if (refundRequestId == null) {
            return;
        }
        jdbcTemplate.query(
                "SELECT cinema_id, incident_report_id, manager_user_id, quota_date, DATE(reserved_at) AS mgr_date " +
                        "FROM refund_limit_reservations WHERE refund_request_id = ?",
                rs -> {
                    long cinId = rs.getLong("cinema_id");
                    boolean hasCin = !rs.wasNull();
                    Date qDateSql = rs.getDate("quota_date");
                    LocalDate qDate = qDateSql != null ? qDateSql.toLocalDate() : null;
                    if (hasCin && qDate != null) {
                        String cinKey = cinemaBucketKey(cinId, qDate);
                        specs.putIfAbsent(cinKey, new BucketSpec(cinKey, "CINEMA_DAILY", cinId, qDate));
                    }

                    long incId = rs.getLong("incident_report_id");
                    if (!rs.wasNull()) {
                        String incKey = incidentBucketKey(incId);
                        specs.putIfAbsent(incKey, new BucketSpec(incKey, "INCIDENT_CUMULATIVE", incId, null));
                    }

                    long mgrId = rs.getLong("manager_user_id");
                    boolean hasMgr = !rs.wasNull();
                    Date mgrDateSql = rs.getDate("mgr_date");
                    LocalDate mgrDate = mgrDateSql != null ? mgrDateSql.toLocalDate() : qDate;
                    if (hasMgr && mgrDate != null) {
                        String mgrKey = managerBucketKey(mgrId, mgrDate);
                        specs.putIfAbsent(mgrKey, new BucketSpec(mgrKey, "MANAGER_DAILY", mgrId, mgrDate));
                    }
                },
                refundRequestId
        );
    }

    private Map<String, RefundLimitBucket> ensureAndLockBuckets(TreeMap<String, BucketSpec> sortedSpecs) {
        LocalDateTime nowHcm = now();
        for (BucketSpec spec : sortedSpecs.values()) {
            jdbcTemplate.update(
                    "INSERT IGNORE INTO refund_limit_buckets " +
                            "(bucket_key, bucket_type, scope_id, quota_date, reserved_amount_vnd, committed_amount_vnd, updated_at) " +
                            "VALUES (?, ?, ?, ?, 0, 0, ?)",
                    spec.bucketKey(),
                    spec.bucketType(),
                    spec.scopeId(),
                    spec.quotaDate() != null ? Date.valueOf(spec.quotaDate()) : null,
                    Timestamp.valueOf(nowHcm)
            );
        }
        Map<String, RefundLimitBucket> locked = new LinkedHashMap<>();
        for (String key : sortedSpecs.keySet()) {
            RefundLimitBucket bucket = bucketRepository.findByBucketKeyForUpdate(key)
                    .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "BUCKET_LOCK_FAILED",
                            "Failed to lock refund limit bucket: " + key));
            locked.put(key, bucket);
        }
        return locked;
    }

    private void syncLockedBuckets(TreeMap<String, BucketSpec> specs, Map<String, RefundLimitBucket> lockedBuckets) {
        LocalDateTime nowHcm = now();
        for (Map.Entry<String, BucketSpec> entry : specs.entrySet()) {
            BucketSpec spec = entry.getValue();
            RefundLimitBucket bucket = lockedBuckets.get(entry.getKey());
            if (bucket == null) {
                continue;
            }
            long reserved;
            long committed;
            if ("CINEMA_DAILY".equals(spec.bucketType())) {
                reserved = sumCinemaByDateAndStatusForShare(spec.scopeId(), spec.quotaDate(), RefundLimitReservation.STATUS_RESERVED);
                committed = sumCinemaByDateAndStatusForShare(spec.scopeId(), spec.quotaDate(), RefundLimitReservation.STATUS_COMMITTED);
            } else if ("INCIDENT_CUMULATIVE".equals(spec.bucketType())) {
                reserved = sumIncidentByStatusForShare(spec.scopeId(), RefundLimitReservation.STATUS_RESERVED);
                committed = sumIncidentByStatusForShare(spec.scopeId(), RefundLimitReservation.STATUS_COMMITTED);
            } else {
                reserved = sumManagerByDateAndStatusForShare(spec.scopeId(), spec.quotaDate(), RefundLimitReservation.STATUS_RESERVED);
                committed = sumManagerByDateAndStatusForShare(spec.scopeId(), spec.quotaDate(), RefundLimitReservation.STATUS_COMMITTED);
            }
            bucket.setReservedAmountVnd(reserved);
            bucket.setCommittedAmountVnd(committed);
            bucket.setUpdatedAt(nowHcm);
            bucketRepository.save(bucket);
        }
        bucketRepository.flush();
    }

    private long sumCinemaActiveExcluding(Long cinemaId, LocalDate quotaDate, Long excludeRefundRequestId) {
        List<Long> amounts = jdbcTemplate.queryForList(
                "SELECT amount_vnd FROM refund_limit_reservations " +
                        "WHERE cinema_id = ? AND quota_date = ? AND status IN ('RESERVED', 'COMMITTED') " +
                        "AND refund_request_id <> ? FOR SHARE",
                Long.class,
                cinemaId,
                Date.valueOf(quotaDate),
                excludeRefundRequestId != null ? excludeRefundRequestId : -1L
        );
        return sumList(amounts);
    }

    private long sumIncidentActiveExcluding(Long incidentId, Long excludeRefundRequestId) {
        List<Long> amounts = jdbcTemplate.queryForList(
                "SELECT amount_vnd FROM refund_limit_reservations " +
                        "WHERE incident_report_id = ? AND status IN ('RESERVED', 'COMMITTED') " +
                        "AND refund_request_id <> ? FOR SHARE",
                Long.class,
                incidentId,
                excludeRefundRequestId != null ? excludeRefundRequestId : -1L
        );
        return sumList(amounts);
    }

    private long sumManagerActiveExcluding(Long managerId, LocalDate approvalDate, Long excludeRefundRequestId) {
        List<Long> amounts = jdbcTemplate.queryForList(
                "SELECT amount_vnd FROM refund_limit_reservations " +
                        "WHERE manager_user_id = ? AND DATE(reserved_at) = ? AND status IN ('RESERVED', 'COMMITTED') " +
                        "AND refund_request_id <> ? FOR SHARE",
                Long.class,
                managerId,
                Date.valueOf(approvalDate),
                excludeRefundRequestId != null ? excludeRefundRequestId : -1L
        );
        return sumList(amounts);
    }

    private long sumCinemaByDateAndStatusForShare(Long cinemaId, LocalDate quotaDate, String status) {
        List<Long> amounts = jdbcTemplate.queryForList(
                "SELECT amount_vnd FROM refund_limit_reservations " +
                        "WHERE cinema_id = ? AND quota_date = ? AND status = ? FOR SHARE",
                Long.class,
                cinemaId,
                Date.valueOf(quotaDate),
                status
        );
        return sumList(amounts);
    }

    private long sumIncidentByStatusForShare(Long incidentId, String status) {
        List<Long> amounts = jdbcTemplate.queryForList(
                "SELECT amount_vnd FROM refund_limit_reservations " +
                        "WHERE incident_report_id = ? AND status = ? FOR SHARE",
                Long.class,
                incidentId,
                status
        );
        return sumList(amounts);
    }

    private long sumManagerByDateAndStatusForShare(Long managerId, LocalDate approvalDate, String status) {
        List<Long> amounts = jdbcTemplate.queryForList(
                "SELECT amount_vnd FROM refund_limit_reservations " +
                        "WHERE manager_user_id = ? AND DATE(reserved_at) = ? AND status = ? FOR SHARE",
                Long.class,
                managerId,
                Date.valueOf(approvalDate),
                status
        );
        return sumList(amounts);
    }

    private static long sumList(List<Long> amounts) {
        if (amounts == null || amounts.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (Long a : amounts) {
            if (a != null) {
                total += a;
            }
        }
        return total;
    }

    private long sumCinemaByDateAndStatus(Long cinemaId, LocalDate quotaDate, String status) {
        Long val = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_vnd), 0) FROM refund_limit_reservations " +
                        "WHERE cinema_id = ? AND quota_date = ? AND status = ?",
                Long.class,
                cinemaId,
                Date.valueOf(quotaDate),
                status
        );
        return val != null ? val : 0L;
    }

    private long sumIncidentByStatus(Long incidentId, String status) {
        Long val = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_vnd), 0) FROM refund_limit_reservations " +
                        "WHERE incident_report_id = ? AND status = ?",
                Long.class,
                incidentId,
                status
        );
        return val != null ? val : 0L;
    }

    private long sumManagerByDateAndStatus(Long managerId, LocalDate approvalDate, String status) {
        Long val = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_vnd), 0) FROM refund_limit_reservations " +
                        "WHERE manager_user_id = ? AND DATE(reserved_at) = ? AND status = ?",
                Long.class,
                managerId,
                Date.valueOf(approvalDate),
                status
        );
        return val != null ? val : 0L;
    }

    private void ensureDefaultConfigsSeeded() {
        seedConfigIfMissing(RefundLimitConfig.KEY_MANAGER_PER_REQUEST, DEFAULT_MANAGER_PER_REQUEST_VND,
                "Maximum cash refund amount per request a Manager can approve without Admin escalation");
        seedConfigIfMissing(RefundLimitConfig.KEY_MANAGER_DAILY, DEFAULT_MANAGER_DAILY_VND,
                "Maximum daily cash refund total (Asia/Ho_Chi_Minh) a single Manager can approve");
        seedConfigIfMissing(RefundLimitConfig.KEY_CINEMA_DAILY, DEFAULT_CINEMA_DAILY_VND,
                "Maximum daily cash refund total (Asia/Ho_Chi_Minh) across a single Cinema branch");
        seedConfigIfMissing(RefundLimitConfig.KEY_INCIDENT_CUMULATIVE, DEFAULT_INCIDENT_CUMULATIVE_VND,
                "Maximum cumulative cash refund total linked to a single Incident Report");
    }

    private void seedConfigIfMissing(String key, long amount, String description) {
        if (configRepository.findByConfigKey(key).isEmpty()) {
            jdbcTemplate.update(
                    "INSERT IGNORE INTO refund_limit_configs (config_key, limit_amount_vnd, description, is_active) VALUES (?, ?, ?, 1)",
                    key, amount, description
            );
        }
    }

    private Long resolveCinemaId(RefundRequest req) {
        if (req.getCinema() != null && req.getCinema().getCinemaId() != null) {
            return req.getCinema().getCinemaId();
        }
        if (req.getBooking() != null
                && req.getBooking().getShowtime() != null
                && req.getBooking().getShowtime().getRoom() != null
                && req.getBooking().getShowtime().getRoom().getCinema() != null
                && req.getBooking().getShowtime().getRoom().getCinema().getCinemaId() != null) {
            return req.getBooking().getShowtime().getRoom().getCinema().getCinemaId();
        }
        return 1L;
    }

    private RefundLimitConfigDto toConfigDto(RefundLimitConfig cfg) {
        RefundLimitConfigDto dto = new RefundLimitConfigDto();
        dto.setId(cfg.getId());
        dto.setConfigKey(cfg.getConfigKey());
        dto.setLimitAmountVnd(cfg.getLimitAmountVnd());
        dto.setDescription(cfg.getDescription());
        dto.setIsActive(cfg.getIsActive());
        dto.setUpdatedBy(cfg.getUpdatedBy());
        dto.setUpdatedAt(cfg.getUpdatedAt());
        return dto;
    }

    private String toJson(Object obj) {
        try {
            return JSON_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private record BucketSpec(String bucketKey, String bucketType, Long scopeId, LocalDate quotaDate) {
    }
}
