package com.boxcinema.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory brute-force protection for Email + Password authentication (Specification V1.2 Sections E1 & L):
 * - Failure window: 15 minutes
 * - Threshold: 5 failed attempts -> 15-minute cooldown lock
 * - Progressive delay before failure response: 1s -> 2s -> 4s -> 8s on failures 1..4
 */
@Service
public class LoginAttemptService {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final int LOCK_DURATION_MINUTES = 15;
    public static final int ATTEMPT_WINDOW_MINUTES = 15;

    public static class LoginLockoutException extends IllegalArgumentException {
        private final long retryAfterSeconds;

        public LoginLockoutException(String message) {
            this(message, LOCK_DURATION_MINUTES * 60L);
        }

        public LoginLockoutException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    @Value("${app.security.login.progressive-delay-enabled:true}")
    private boolean progressiveDelayEnabled = true;

    private final Map<String, AttemptState> loginAttempts = new ConcurrentHashMap<>();

    public void setProgressiveDelayEnabled(boolean progressiveDelayEnabled) {
        this.progressiveDelayEnabled = progressiveDelayEnabled;
    }

    public void checkNotLocked(String normalizedEmail) {
        checkNotLocked(normalizedEmail, null);
    }

    public void checkNotLocked(String normalizedEmail, String clientIp) {
        String key = compositeKey(normalizedEmail, clientIp);
        if (key == null) {
            return;
        }
        AttemptState state = loginAttempts.get(key);
        if (state == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (state.lockUntil != null) {
            if (state.lockUntil.isAfter(now)) {
                long remainingSeconds = Math.max(1L, Duration.between(now, state.lockUntil).getSeconds());
                long remainingMinutes = Math.max(1L, (remainingSeconds + 59L) / 60L);
                throw new LoginLockoutException(
                        "Too many failed login attempts. Please try again in " + remainingMinutes + " minute(s).",
                        remainingSeconds
                );
            }
            loginAttempts.remove(key);
        }
    }

    public boolean recordFailure(String normalizedEmail) {
        return recordFailure(normalizedEmail, null);
    }

    public boolean recordFailure(String normalizedEmail, String clientIp) {
        String key = compositeKey(normalizedEmail, clientIp);
        if (key == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        AttemptState updated = loginAttempts.compute(key, (k, current) -> {
            if (current == null
                    || current.windowStartedAt.plusMinutes(ATTEMPT_WINDOW_MINUTES).isBefore(now)
                    || (current.lockUntil != null && !current.lockUntil.isAfter(now))) {
                return new AttemptState(1, now, null);
            }
            int nextCount = current.failedCount + 1;
            LocalDateTime nextLockUntil = nextCount >= MAX_FAILED_ATTEMPTS
                    ? now.plusMinutes(LOCK_DURATION_MINUTES)
                    : current.lockUntil;
            return new AttemptState(nextCount, current.windowStartedAt, nextLockUntil);
        });
        return updated.lockUntil != null && updated.lockUntil.isAfter(now);
    }

    /**
     * Computes progressive delay in seconds per Specification V1.2 Section E1:
     * failures 1..4 -> 1s, 2s, 4s, 8s respectively; 0 or >=5 -> 0s.
     */
    public int getProgressiveDelaySeconds(String normalizedEmail, String clientIp) {
        int count = getFailedAttempts(normalizedEmail, clientIp);
        return switch (count) {
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 4;
            case 4 -> 8;
            default -> 0;
        };
    }

    public void applyProgressiveDelayIfEnabled(String normalizedEmail, String clientIp) {
        if (!progressiveDelayEnabled) {
            return;
        }
        int delaySeconds = getProgressiveDelaySeconds(normalizedEmail, clientIp);
        if (delaySeconds <= 0) {
            return;
        }
        try {
            Thread.sleep(delaySeconds * 1000L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public long getRemainingLockSeconds(String normalizedEmail, String clientIp) {
        String key = compositeKey(normalizedEmail, clientIp);
        if (key == null) {
            return 0L;
        }
        AttemptState state = loginAttempts.get(key);
        if (state == null || state.lockUntil == null) {
            return 0L;
        }
        LocalDateTime now = LocalDateTime.now();
        return state.lockUntil.isAfter(now) ? Math.max(1L, Duration.between(now, state.lockUntil).getSeconds()) : 0L;
    }

    public void clearAttempts(String normalizedEmail) {
        clearAttempts(normalizedEmail, null);
    }

    public void clearAttempts(String normalizedEmail, String clientIp) {
        String key = compositeKey(normalizedEmail, clientIp);
        if (key != null) {
            loginAttempts.remove(key);
        }
    }

    public void clearAllAttemptsForEmail(String normalizedEmail) {
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return;
        }
        String prefix = normalizedEmail.trim().toLowerCase(Locale.ROOT) + "|";
        loginAttempts.keySet().removeIf(k -> k.startsWith(prefix));
    }

    public int getFailedAttempts(String normalizedEmail) {
        return getFailedAttempts(normalizedEmail, null);
    }

    public int getFailedAttempts(String normalizedEmail, String clientIp) {
        String key = compositeKey(normalizedEmail, clientIp);
        if (key == null) {
            return 0;
        }
        AttemptState state = loginAttempts.get(key);
        if (state == null || state.windowStartedAt.plusMinutes(ATTEMPT_WINDOW_MINUTES).isBefore(LocalDateTime.now())) {
            return 0;
        }
        return state.failedCount;
    }

    private String compositeKey(String normalizedEmail, String clientIp) {
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return null;
        }
        String cleanEmail = normalizedEmail.trim().toLowerCase(Locale.ROOT);
        String cleanIp = (clientIp == null || clientIp.isBlank()) ? "local" : clientIp.trim();
        return cleanEmail + "|" + cleanIp;
    }

    private record AttemptState(int failedCount, LocalDateTime windowStartedAt, LocalDateTime lockUntil) {
    }
}
