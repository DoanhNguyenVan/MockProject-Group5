package com.boxcinema.service;

import com.boxcinema.dto.UserSummaryDto;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active HttpSessions per user, enforces role-specific idle and absolute
 * session lifetimes, and invalidates sessions after password resets or revocations.
 */
@Service
public class SessionManagementService implements HttpSessionListener {

    public static final String SESSION_AUTH_USER_ID = "BOX_AUTH_USER_ID";
    public static final String SESSION_AUTH_ISSUED_AT_EPOCH_MS = "BOX_AUTH_ISSUED_AT_MS";
    public static final String SESSION_AUTH_ROLE = "BOX_AUTH_ROLE";
    public static final String SESSION_AUTH_MAX_LIFETIME_MS = "BOX_AUTH_MAX_LIFETIME_MS";

    public static final int CUSTOMER_IDLE_TIMEOUT_SECONDS = 30 * 60;
    public static final long CUSTOMER_MAX_LIFETIME_MS = 12L * 60 * 60 * 1000L;
    public static final int PRIVILEGED_IDLE_TIMEOUT_SECONDS = 15 * 60;
    public static final long PRIVILEGED_MAX_LIFETIME_MS = 8L * 60 * 60 * 1000L;

    private final Map<Long, Set<HttpSession>> sessionsByUser = new ConcurrentHashMap<>();
    private final Map<Long, Long> invalidatedBeforeEpochMs = new ConcurrentHashMap<>();

    public void registerSession(Long userId, HttpSession session) {
        String role = null;
        if (session != null && session.getAttribute("currentUser") instanceof UserSummaryDto summary) {
            role = summary.getRole();
        }
        registerSession(userId, role, session);
    }

    public void registerSession(Long userId, String role, HttpSession session) {
        if (userId == null || session == null) {
            return;
        }
        String cleanRole = normalizeRole(role);
        boolean privileged = isPrivilegedRole(cleanRole);
        int idleTimeoutSeconds = privileged ? PRIVILEGED_IDLE_TIMEOUT_SECONDS : CUSTOMER_IDLE_TIMEOUT_SECONDS;
        long maxLifetimeMs = privileged ? PRIVILEGED_MAX_LIFETIME_MS : CUSTOMER_MAX_LIFETIME_MS;

        long nowMs = Instant.now().toEpochMilli();
        session.setAttribute(SESSION_AUTH_USER_ID, userId);
        session.setAttribute(SESSION_AUTH_ISSUED_AT_EPOCH_MS, nowMs);
        session.setAttribute(SESSION_AUTH_ROLE, cleanRole);
        session.setAttribute(SESSION_AUTH_MAX_LIFETIME_MS, maxLifetimeMs);
        session.setMaxInactiveInterval(idleTimeoutSeconds);
        sessionsByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregisterSession(Long userId, HttpSession session) {
        if (userId == null || session == null) {
            return;
        }
        Set<HttpSession> userSessions = sessionsByUser.get(userId);
        if (userSessions != null) {
            userSessions.remove(session);
            if (userSessions.isEmpty()) {
                sessionsByUser.remove(userId, userSessions);
            }
        }
    }

    /**
     * Revokes all active sessions for a user and sets the minimum valid credential timestamp.
     */
    public void invalidateUserSessions(Long userId) {
        if (userId == null) {
            return;
        }
        invalidatedBeforeEpochMs.put(userId, Instant.now().toEpochMilli());
        Set<HttpSession> sessions = sessionsByUser.remove(userId);
        if (sessions != null) {
            for (HttpSession session : sessions) {
                try {
                    session.invalidate();
                } catch (IllegalStateException ignored) {
                    // Already invalidated
                }
            }
        }
    }

    /**
     * Checks whether the current session predates a password reset/revocation
     * or exceeds its role-based absolute maximum lifetime.
     */
    public boolean isSessionValid(Long userId, HttpSession session) {
        if (userId == null || session == null) {
            return false;
        }
        try {
            Object issuedObj = session.getAttribute(SESSION_AUTH_ISSUED_AT_EPOCH_MS);
            long issuedAtMs = (issuedObj instanceof Long l) ? l : -1L;

            Long revokedBefore = invalidatedBeforeEpochMs.get(userId);
            if (revokedBefore != null && (issuedAtMs < 0 || issuedAtMs < revokedBefore)) {
                return false;
            }

            if (issuedAtMs > 0) {
                Object maxLifetimeObj = session.getAttribute(SESSION_AUTH_MAX_LIFETIME_MS);
                long maxLifetimeMs = (maxLifetimeObj instanceof Long l)
                        ? l
                        : resolveMaxLifetimeMs(session);
                if (Instant.now().toEpochMilli() - issuedAtMs > maxLifetimeMs) {
                    return false;
                }
            }
            return true;
        } catch (IllegalStateException ex) {
            return false;
        }
    }

    private long resolveMaxLifetimeMs(HttpSession session) {
        Object roleObj = session.getAttribute(SESSION_AUTH_ROLE);
        if (roleObj instanceof String roleStr && isPrivilegedRole(normalizeRole(roleStr))) {
            return PRIVILEGED_MAX_LIFETIME_MS;
        }
        if (session.getAttribute("currentUser") instanceof UserSummaryDto dto && isPrivilegedRole(normalizeRole(dto.getRole()))) {
            return PRIVILEGED_MAX_LIFETIME_MS;
        }
        return CUSTOMER_MAX_LIFETIME_MS;
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "CUSTOMER";
        }
        String normalized = role.trim().toUpperCase();
        return normalized.startsWith("ROLE_") ? normalized.substring("ROLE_".length()) : normalized;
    }

    private static boolean isPrivilegedRole(String role) {
        return "STAFF".equals(role) || "MANAGER".equals(role) || "ADMIN".equals(role);
    }

    public Long getInvalidatedBeforeEpochMs(Long userId) {
        return userId != null ? invalidatedBeforeEpochMs.get(userId) : null;
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        HttpSession session = se.getSession();
        if (session == null) {
            return;
        }
        try {
            Object userIdObj = session.getAttribute(SESSION_AUTH_USER_ID);
            if (userIdObj instanceof Long userId) {
                unregisterSession(userId, session);
            }
        } catch (IllegalStateException ignored) {
            // Session attributes may be unavailable after invalidation
        }
    }
}

