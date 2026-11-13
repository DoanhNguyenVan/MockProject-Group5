package com.boxcinema.service;

import com.boxcinema.config.OtpProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fixed/sliding-window authentication rate limiter implementing Specification V1.2 Section L.
 */
@Service
public class AuthRateLimitService {

    public static class RateLimitExceededException extends RuntimeException {
        private final long retryAfterSeconds;

        public RateLimitExceededException(String message, long retryAfterSeconds) {
            super(message);
            this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    private static final long ONE_HOUR_SECONDS = 3600L;
    private static final long FIFTEEN_MINUTES_SECONDS = 900L;

    private final Map<String, WindowState> counters = new ConcurrentHashMap<>();
    private final OtpProperties otpProperties;

    @Value("${app.security.rate-limit.ip-login-max-requests:60}")
    private int loginIpMaxRequests = 60;

    @Value("${app.security.rate-limit.register-ip-max-per-hour:5}")
    private int registerIpMaxPerHour = 5;

    @Value("${app.security.rate-limit.register-email-max-per-hour:10}")
    private int registerEmailMaxPerHour = 10;

    @Value("${app.security.rate-limit.google-link-user-max-per-hour:5}")
    private int googleLinkUserMaxPerHour = 5;

    @Value("${app.security.rate-limit.google-link-ip-max-per-hour:20}")
    private int googleLinkIpMaxPerHour = 20;

    public AuthRateLimitService() {
        this.otpProperties = new OtpProperties();
    }

    @Autowired
    public AuthRateLimitService(OtpProperties otpProperties) {
        this.otpProperties = (otpProperties != null) ? otpProperties : new OtpProperties();
    }

    public void checkLoginRateLimit(String clientIp) {
        checkBucket("LOGIN_IP", clientIp, loginIpMaxRequests, FIFTEEN_MINUTES_SECONDS);
    }

    public void checkRegisterRateLimit(String clientIp) {
        checkBucket("REGISTER_IP", clientIp, registerIpMaxPerHour, ONE_HOUR_SECONDS);
    }

    public void checkRegisterEmailRateLimit(String cleanEmail) {
        if (cleanEmail == null || cleanEmail.isBlank()) {
            return;
        }
        checkBucket("REGISTER_EMAIL", normalizeEmailKey(cleanEmail), registerEmailMaxPerHour, ONE_HOUR_SECONDS);
    }

    public void checkOtpRateLimit(String clientIp) {
        checkBucket("OTP_IP", clientIp, otpProperties.getMaxSendsPerIpPerHour(), ONE_HOUR_SECONDS);
    }

    public void checkOtpEmailHourlyLimit(String cleanEmail) {
        if (cleanEmail == null || cleanEmail.isBlank()) {
            return;
        }
        checkBucket("OTP_EMAIL", normalizeEmailKey(cleanEmail), otpProperties.getMaxSendsPerEmailPerHour(), ONE_HOUR_SECONDS);
    }

    /**
     * Backward-compatible alias that now enforces the V1.2 hourly limit (10/email/hour).
     */
    public void checkOtpDailyEmailLimit(String cleanEmail) {
        checkOtpEmailHourlyLimit(cleanEmail);
    }

    public void checkForgotPasswordIpRateLimit(String clientIp) {
        checkBucket("FORGOT_PW_IP", clientIp, otpProperties.getMaxSendsPerIpPerHour(), ONE_HOUR_SECONDS);
    }

    public void checkForgotPasswordEmailRateLimit(String cleanEmail) {
        if (cleanEmail == null || cleanEmail.isBlank()) {
            return;
        }
        checkBucket("FORGOT_PW_EMAIL", normalizeEmailKey(cleanEmail), otpProperties.getMaxSendsPerEmailPerHour(), ONE_HOUR_SECONDS);
    }

    public void checkGoogleLinkUserRateLimit(Long userId) {
        if (userId == null) {
            return;
        }
        checkBucket("GOOGLE_LINK_USER", String.valueOf(userId), googleLinkUserMaxPerHour, ONE_HOUR_SECONDS);
    }

    public void checkGoogleLinkIpRateLimit(String clientIp) {
        checkBucket("GOOGLE_LINK_IP", clientIp, googleLinkIpMaxPerHour, ONE_HOUR_SECONDS);
    }

    public void checkBucket(String bucket, String clientKey, int maxRequests, long durationSeconds) {
        String cleanKey = (clientKey == null || clientKey.isBlank()) ? "local" : clientKey.trim();
        String key = bucket + ":" + cleanKey;
        long nowSec = System.currentTimeMillis() / 1000L;
        AtomicReference<Long> exceededRetryAfter = new AtomicReference<>();

        counters.compute(key, (k, current) -> {
            if (current == null || current.windowEnd <= nowSec) {
                if (maxRequests < 1) {
                    exceededRetryAfter.set(durationSeconds);
                }
                return new WindowState(1, nowSec + durationSeconds);
            }
            if (current.count >= maxRequests) {
                exceededRetryAfter.set(Math.max(1L, current.windowEnd - nowSec));
                return current;
            }
            return new WindowState(current.count + 1, current.windowEnd);
        });

        Long retryAfter = exceededRetryAfter.get();
        if (retryAfter != null) {
            throw new RateLimitExceededException(
                    "Too many requests. Please wait " + retryAfter + " seconds before trying again.",
                    retryAfter
            );
        }
    }

    public void clearAll() {
        counters.clear();
    }

    private String normalizeEmailKey(String cleanEmail) {
        return cleanEmail.trim().toLowerCase(Locale.ROOT);
    }

    private record WindowState(int count, long windowEnd) {
    }
}
