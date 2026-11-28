package com.boxcinema.refund.service;

import com.boxcinema.common.entity.RefundAuditLog;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.RefundAuditLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Admin-controlled runtime gate for REAL-MONEY PayOS payouts.
 *
 * <p>State is intentionally process-local and volatile: after every application start the gate is
 * {@code DISABLED}, no maximum test amount is configured, and no authorization exists. A live payout
 * can only be created when ALL of the following hold at dispatch time (see
 * {@link #consumeDispatchAuthorizationOrThrow}):
 * <ol>
 *   <li>the environment feature flag ({@code LIVE_PAYOUT_ENABLED}) and {@code PAYOS_PAYOUT_MODE=LIVE}
 *       permit live dispatch (enforced by {@link PayOsPayoutService});</li>
 *   <li>an Admin explicitly switched this runtime gate to LIVE (audited);</li>
 *   <li>an Admin configured a maximum test amount and the payout amount does not exceed it;</li>
 *   <li>a single-use, per-refund, per-amount dispatch authorization exists (granted only by
 *       {@link LivePayoutWorkflowService#execute} after a typed confirmation phrase).</li>
 * </ol>
 * Nothing in this class talks to PayOS and no secret is ever stored or logged here.
 */
@Service
public class LivePayoutGate {

    public static final String CONFIRM_PHRASE = "CONFIRM LIVE PAYOUT";
    public static final String ENTITY_TYPE = "LIVE_PAYOUT_CONTROL";

    static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5);
    static final Duration AUTHORIZATION_TTL = Duration.ofMinutes(2);

    private final RefundAuditLogRepository auditLogRepository;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ReentrantLock singleFlight = new ReentrantLock();
    private final Map<String, Confirmation> confirmations = new ConcurrentHashMap<>();
    private final Map<Long, Authorization> authorizations = new ConcurrentHashMap<>();

    private volatile Clock clock = Clock.systemUTC();
    private volatile boolean adminLiveEnabled = false;
    private volatile Long maxTestAmountVnd = null;
    private volatile Long changedByUserId = null;
    private volatile Instant changedAt = null;

    record Confirmation(String token, Long refundRequestId, long amountVnd, Long adminUserId,
                        String referenceId, Instant expiresAt) {}

    record Authorization(Long refundRequestId, long amountVnd, Long adminUserId, Instant expiresAt) {}

    @Autowired
    public LivePayoutGate(RefundAuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    // ------------------------------------------------------------------ state

    public boolean isAdminLiveEnabled() {
        return adminLiveEnabled;
    }

    public Long getMaxTestAmountVnd() {
        return maxTestAmountVnd;
    }

    public String modeLabel() {
        return adminLiveEnabled ? "LIVE" : "DISABLED";
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("adminLiveMode", modeLabel());
        out.put("adminLiveEnabled", adminLiveEnabled);
        out.put("maxTestAmountVnd", maxTestAmountVnd);
        out.put("maxTestAmountConfigured", maxTestAmountVnd != null);
        out.put("lastChangedByUserId", changedByUserId);
        out.put("lastChangedAt", changedAt != null ? changedAt.toString() : null);
        out.put("singleFlightBusy", singleFlight.isLocked());
        return out;
    }

    // --------------------------------------------------------------- control

    /**
     * Switches the runtime gate. The audit record is written BEFORE the state changes, so a payout gate can
     * never flip without a durable audit trail. Enabling requires the environment layer to permit live dispatch.
     */
    public synchronized void setAdminLiveEnabled(boolean enable, Long actorUserId, String note, String ip,
                                                 boolean envFlagEnabled, boolean payoutModeLive) {
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "ADMIN_REQUIRED", "Admin session required.");
        }
        if (enable) {
            if (!envFlagEnabled) {
                throw new BusinessException(HttpStatus.CONFLICT, "LIVE_ENV_FLAG_OFF",
                        "Environment flag LIVE_PAYOUT_ENABLED is false; both the environment flag and the Admin control must permit live payout.");
            }
            if (!payoutModeLive) {
                throw new BusinessException(HttpStatus.CONFLICT, "LIVE_MODE_NOT_CONFIGURED",
                        "PAYOS_PAYOUT_MODE is not LIVE; live payout cannot be enabled.");
            }
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("from", modeLabel());
        details.put("to", enable ? "LIVE" : "DISABLED");
        details.put("note", sanitizeNote(note));
        audit(null, enable ? "LIVE_PAYOUT_MODE_ENABLED" : "LIVE_PAYOUT_MODE_DISABLED", actorUserId, ip, details);
        this.adminLiveEnabled = enable;
        this.changedByUserId = actorUserId;
        this.changedAt = clock.instant();
        if (!enable) {
            confirmations.clear();
            authorizations.clear();
        }
    }

    public synchronized void setMaxTestAmountVnd(long amountVnd, Long actorUserId, String note, String ip) {
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "ADMIN_REQUIRED", "Admin session required.");
        }
        if (amountVnd <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "LIVE_MAX_AMOUNT_INVALID",
                    "Maximum test amount must be a positive integer VND value entered by the Admin.");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("previousMaxVnd", maxTestAmountVnd);
        details.put("newMaxVnd", amountVnd);
        details.put("note", sanitizeNote(note));
        audit(null, "LIVE_PAYOUT_MAX_AMOUNT_SET", actorUserId, ip, details);
        this.maxTestAmountVnd = amountVnd;
        this.changedByUserId = actorUserId;
        this.changedAt = clock.instant();
        // A previously issued confirmation was reviewed against an older limit.
        confirmations.clear();
        authorizations.clear();
    }

    // ------------------------------------------------------- confirmations

    /** Issues a single-use confirmation token bound to refund + amount + admin. Previous tokens for the refund are voided. */
    public Confirmation issueConfirmation(Long refundRequestId, long amountVnd, Long adminUserId,
                                          String referenceId, String ip) {
        confirmations.values().removeIf(c -> c.refundRequestId().equals(refundRequestId));
        byte[] raw = new byte[24];
        secureRandom.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Confirmation c = new Confirmation(token, refundRequestId, amountVnd, adminUserId, referenceId,
                clock.instant().plus(CONFIRMATION_TTL));
        confirmations.put(token, c);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("amountVnd", amountVnd);
        details.put("referenceId", referenceId);
        audit(refundRequestId, "LIVE_PAYOUT_CONFIRMATION_ISSUED", adminUserId, ip, details);
        return c;
    }

    /** Atomically consumes the token (second concurrent/duplicate request always fails). */
    public void consumeConfirmation(String token, Long refundRequestId, long amountVnd, Long adminUserId) {
        Confirmation c = token == null ? null : confirmations.remove(token);
        if (c == null
                || !c.refundRequestId().equals(refundRequestId)
                || c.amountVnd() != amountVnd
                || !c.adminUserId().equals(adminUserId)
                || !clock.instant().isBefore(c.expiresAt())) {
            throw new BusinessException(HttpStatus.CONFLICT, "LIVE_CONFIRMATION_INVALID",
                    "Confirmation token is missing, expired, already used, or does not match this refund/amount. Re-open the confirmation screen.");
        }
    }

    // ----------------------------------------------------- single in-flight

    public void acquireSingleFlight() {
        if (!singleFlight.tryLock()) {
            throw new BusinessException(HttpStatus.CONFLICT, "LIVE_PAYOUT_IN_PROGRESS",
                    "Another live payout request is being processed. Only one individually confirmed payout may run at a time.");
        }
    }

    public void releaseSingleFlight() {
        if (singleFlight.isHeldByCurrentThread()) {
            singleFlight.unlock();
        }
    }

    // ----------------------------------------------- dispatch authorization

    public void grantDispatchAuthorization(Long refundRequestId, long amountVnd, Long adminUserId) {
        authorizations.put(refundRequestId, new Authorization(refundRequestId, amountVnd, adminUserId,
                clock.instant().plus(AUTHORIZATION_TTL)));
    }

    public void revokeDispatchAuthorization(Long refundRequestId) {
        if (refundRequestId != null) {
            authorizations.remove(refundRequestId);
        }
    }

    /** Non-consuming check used for fast-fail before any state mutation. */
    public boolean hasValidDispatchAuthorization(Long refundRequestId, long amountVnd) {
        Authorization a = authorizations.get(refundRequestId);
        return adminLiveEnabled && a != null && a.amountVnd() == amountVnd && clock.instant().isBefore(a.expiresAt());
    }

    public boolean hasAnyDispatchAuthorization(Long refundRequestId) {
        Authorization a = authorizations.get(refundRequestId);
        return adminLiveEnabled && a != null && clock.instant().isBefore(a.expiresAt());
    }

    /**
     * Called by {@link PayOsPayoutService} immediately before a NEW real {@code POST /v1/payouts} is created.
     * Throws (fail-closed) unless the Admin runtime gate, max amount and per-refund authorization all allow it.
     */
    public void consumeDispatchAuthorizationOrThrow(Long refundRequestId, long amountVnd) {
        if (!adminLiveEnabled) {
            throw new BusinessException(HttpStatus.CONFLICT, "LIVE_ADMIN_DISABLED",
                    "Live payout is DISABLED by the Admin control. An Admin must switch it to LIVE first.");
        }
        Long max = maxTestAmountVnd;
        if (max == null) {
            throw new BusinessException(HttpStatus.CONFLICT, "LIVE_MAX_AMOUNT_NOT_SET",
                    "No maximum test amount configured by the Admin; live payout is blocked.");
        }
        if (amountVnd > max) {
            throw new BusinessException(HttpStatus.CONFLICT, "LIVE_AMOUNT_EXCEEDS_MAX",
                    "Payout amount exceeds the Admin-configured maximum test amount.");
        }
        Authorization a = refundRequestId == null ? null : authorizations.remove(refundRequestId);
        if (a == null || a.amountVnd() != amountVnd || !clock.instant().isBefore(a.expiresAt())) {
            throw new BusinessException(HttpStatus.CONFLICT, "LIVE_ADMIN_AUTHORIZATION_REQUIRED",
                    "A real payout requires an explicit Admin live confirmation (typed phrase) for this refund and amount.");
        }
    }

    // ----------------------------------------------------------------- audit

    public void audit(Long refundRequestId, String eventType, Long actorUserId, String ip, Map<String, Object> details) {
        RefundAuditLog log = new RefundAuditLog();
        log.setRefundRequestId(refundRequestId);
        log.setEntityType(refundRequestId == null ? ENTITY_TYPE : "REFUND_REQUEST");
        log.setEntityId(refundRequestId == null ? 0L : refundRequestId);
        log.setEventType(eventType);
        log.setActorUserId(actorUserId);
        log.setActorRole("ADMIN");
        log.setIpAddress(ip != null && ip.length() > 64 ? ip.substring(0, 64) : ip);
        log.setDetailsJson(toJson(details));
        auditLogRepository.save(log);
    }

    private static String sanitizeNote(String note) {
        if (note == null) {
            return null;
        }
        String n = note.replaceAll("[\\r\\n\\t]+", " ").trim();
        return n.length() > 300 ? n.substring(0, 300) : n;
    }

    private static String toJson(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : details.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Test hook only. */
    void setClock(Clock clock) {
        this.clock = clock;
    }
}
