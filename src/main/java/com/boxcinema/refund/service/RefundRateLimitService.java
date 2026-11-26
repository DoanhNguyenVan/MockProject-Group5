package com.boxcinema.refund.service;

import com.boxcinema.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe backend rate-limiting service for Refund Creation, Evidence Upload, and Refund Appeals.
 *
 * <p>Uses an in-process {@link ConcurrentHashMap} of sliding-window timestamp buckets with an injectable
 * {@link Clock} (as Redis is not installed locally). Enforces per-customer and per-source-IP limits
 * atomically so that a customer blocked by their own customer quota does not consume shared IP tokens
 * for other customers on the same IP, and returns HTTP {@code 429 Too Many Requests} with a mandatory
 * {@code Retry-After} header.</p>
 */
@Service
public class RefundRateLimitService {

    public static class RateLimitExceededException extends BusinessException {
        private final long retryAfterSeconds;
        private final String limitScope;

        public RateLimitExceededException(String message, long retryAfterSeconds, String limitScope) {
            super(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMIT_EXCEEDED",
                    message,
                    Map.of(
                            "retryAfterSeconds", Math.max(1L, retryAfterSeconds),
                            "limitScope", limitScope != null ? limitScope : "RATE_LIMIT"
                    )
            );
            this.retryAfterSeconds = Math.max(1L, retryAfterSeconds);
            this.limitScope = limitScope != null ? limitScope : "RATE_LIMIT";
        }

        public long getRetryAfterSeconds() {
            return retryAfterSeconds;
        }

        public String getLimitScope() {
            return limitScope;
        }
    }

    private static final long TEN_MINUTES_SECONDS = 600L;
    private static final long ONE_HOUR_SECONDS = 3600L;

    private final Map<String, Deque<Long>> slidingBuckets = new ConcurrentHashMap<>();
    private final Object reservationMonitor = new Object();
    private volatile Clock clock = Clock.systemUTC();

    @Value("${app.refund.rate-limit.create-customer-max:5}")
    private int createCustomerMax = 5;

    @Value("${app.refund.rate-limit.create-customer-window-seconds:600}")
    private long createCustomerWindowSeconds = TEN_MINUTES_SECONDS;

    @Value("${app.refund.rate-limit.create-ip-max:20}")
    private int createIpMax = 20;

    @Value("${app.refund.rate-limit.create-ip-window-seconds:3600}")
    private long createIpWindowSeconds = ONE_HOUR_SECONDS;

    @Value("${app.refund.rate-limit.upload-customer-max:15}")
    private int uploadCustomerMax = 15;

    @Value("${app.refund.rate-limit.upload-customer-window-seconds:600}")
    private long uploadCustomerWindowSeconds = TEN_MINUTES_SECONDS;

    @Value("${app.refund.rate-limit.upload-ip-max:30}")
    private int uploadIpMax = 30;

    @Value("${app.refund.rate-limit.upload-ip-window-seconds:3600}")
    private long uploadIpWindowSeconds = ONE_HOUR_SECONDS;

    @Value("${app.refund.rate-limit.appeal-customer-max:5}")
    private int appealCustomerMax = 5;

    @Value("${app.refund.rate-limit.appeal-customer-window-seconds:600}")
    private long appealCustomerWindowSeconds = TEN_MINUTES_SECONDS;

    @Value("${app.refund.rate-limit.appeal-ip-max:15}")
    private int appealIpMax = 15;

    @Value("${app.refund.rate-limit.appeal-ip-window-seconds:3600}")
    private long appealIpWindowSeconds = ONE_HOUR_SECONDS;

    public void setClock(Clock clock) {
        this.clock = (clock != null) ? clock : Clock.systemUTC();
    }

    public Clock getClock() {
        return this.clock;
    }

    public void resetForTesting() {
        synchronized (reservationMonitor) {
            slidingBuckets.clear();
        }
    }

    /**
     * Enforces refund request creation rate limits:
     * <ul>
     *   <li>5 requests / authenticated customer / 10 minutes</li>
     *   <li>20 requests / source IP / hour</li>
     * </ul>
     */
    public void checkRefundCreateLimit(Long customerUserId, String clientIp) {
        checkRefundCreateLimit(customerUserId, null, clientIp);
    }

    public void checkRefundCreateLimit(Long customerUserId, String purchaseEmail, String clientIp) {
        String customerKey = resolveCustomerIdentityKey(customerUserId, purchaseEmail);
        String ipKey = normalizeIpKey(clientIp);
        checkAndConsumeDualLimit(
                "REFUND_CREATE_CUSTOMER", customerKey, createCustomerMax, createCustomerWindowSeconds,
                "REFUND_CREATE_IP", ipKey, createIpMax, createIpWindowSeconds
        );
    }

    /**
     * Enforces evidence upload rate limits:
     * <ul>
     *   <li>15 uploads / customer / 10 minutes</li>
     *   <li>30 uploads / source IP / hour</li>
     * </ul>
     */
    public void checkEvidenceUploadLimit(Long customerUserId, String clientIp) {
        checkEvidenceUploadLimit(customerUserId, null, clientIp);
    }

    public void checkEvidenceUploadLimit(Long customerUserId, String customerEmail, String clientIp) {
        String customerKey = resolveCustomerIdentityKey(customerUserId, customerEmail);
        String ipKey = normalizeIpKey(clientIp);
        checkAndConsumeDualLimit(
                "EVIDENCE_UPLOAD_CUSTOMER", customerKey, uploadCustomerMax, uploadCustomerWindowSeconds,
                "EVIDENCE_UPLOAD_IP", ipKey, uploadIpMax, uploadIpWindowSeconds
        );
    }

    /**
     * Enforces appeal submission rate limits:
     * <ul>
     *   <li>5 appeals / customer / 10 minutes</li>
     *   <li>15 appeals / source IP / hour</li>
     * </ul>
     */
    public void checkAppealSubmitLimit(Long customerUserId, String clientIp) {
        checkAppealSubmitLimit(customerUserId, null, clientIp);
    }

    public void checkAppealSubmitLimit(Long customerUserId, String purchaseEmail, String clientIp) {
        String customerKey = resolveCustomerIdentityKey(customerUserId, purchaseEmail);
        String ipKey = normalizeIpKey(clientIp);
        checkAndConsumeDualLimit(
                "REFUND_APPEAL_CUSTOMER", customerKey, appealCustomerMax, appealCustomerWindowSeconds,
                "REFUND_APPEAL_IP", ipKey, appealIpMax, appealIpWindowSeconds
        );
    }

    private void checkAndConsumeDualLimit(
            String customerScope, String customerKey, int customerMax, long customerWindowSec,
            String ipScope, String ipKey, int ipMax, long ipWindowSec) {
        long nowMs = clock.millis();
        long customerWindowMs = Math.max(1L, customerWindowSec) * 1000L;
        long ipWindowMs = Math.max(1L, ipWindowSec) * 1000L;

        synchronized (reservationMonitor) {
            Deque<Long> customerDeque = null;
            if (customerKey != null) {
                customerDeque = slidingBuckets.computeIfAbsent(customerScope + ":" + customerKey, k -> new ArrayDeque<>());
                evictExpired(customerDeque, nowMs, customerWindowMs);
                if (customerDeque.size() >= customerMax) {
                    long oldestMs = customerDeque.peekFirst() != null ? customerDeque.peekFirst() : nowMs;
                    long retryAfterSec = Math.max(1L, (oldestMs + customerWindowMs - nowMs + 999L) / 1000L);
                    throw new RateLimitExceededException(
                            "Too many requests. Please wait " + retryAfterSec + " seconds before trying again.",
                            retryAfterSec,
                            customerScope
                    );
                }
            }

            Deque<Long> ipDeque = slidingBuckets.computeIfAbsent(ipScope + ":" + ipKey, k -> new ArrayDeque<>());
            evictExpired(ipDeque, nowMs, ipWindowMs);
            if (ipDeque.size() >= ipMax) {
                long oldestMs = ipDeque.peekFirst() != null ? ipDeque.peekFirst() : nowMs;
                long retryAfterSec = Math.max(1L, (oldestMs + ipWindowMs - nowMs + 999L) / 1000L);
                throw new RateLimitExceededException(
                        "Too many requests from this network address. Please wait " + retryAfterSec + " seconds before trying again.",
                        retryAfterSec,
                        ipScope
                );
            }

            if (customerDeque != null) {
                customerDeque.addLast(nowMs);
            }
            ipDeque.addLast(nowMs);
        }
    }

    private static void evictExpired(Deque<Long> deque, long nowMs, long windowMs) {
        while (!deque.isEmpty() && (nowMs - deque.peekFirst()) >= windowMs) {
            deque.pollFirst();
        }
    }

    private static String resolveCustomerIdentityKey(Long customerUserId, String email) {
        if (customerUserId != null) {
            return "UID:" + customerUserId;
        }
        if (email != null && !email.isBlank()) {
            return "EMAIL:" + email.trim().toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static String normalizeIpKey(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "127.0.0.1";
        }
        return clientIp.trim().toLowerCase(Locale.ROOT);
    }
}
