package com.boxcinema.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Structured JSON Security Audit Logger for authentication and account lifecycle events
 * (Specification V1.2 Section O).
 *
 * Strictly forbids logging plaintext passwords, OTPs, reset tokens, Google ID tokens,
 * OAuth authorization codes, or session IDs.
 */
@Service
public class AuthAuditService {

    private static final Logger auditLog = LoggerFactory.getLogger("BOX_CINEMA_AUDIT");
    private static final int MAX_USER_AGENT_LENGTH = 200;
    private static final int MAX_REASON_LENGTH = 200;

    private final ObjectMapper objectMapper;

    public AuthAuditService() {
        this.objectMapper = new ObjectMapper();
    }

    @Autowired
    public AuthAuditService(ObjectMapper objectMapper) {
        this.objectMapper = (objectMapper != null) ? objectMapper : new ObjectMapper();
    }

    public void logEvent(String event, Long userId, String email, String outcome, String reason) {
        logStructured(event, null, userId, email, outcome, reason, null);
    }

    public void logEvent(String event, Long userId, String email, String outcome, String reason, String sourceIp) {
        logStructured(event, null, userId, email, outcome, reason, sourceIp);
    }

    public void logGoogleEvent(String event, Long userId, String email, String outcome, String reason) {
        logStructured(event, "GOOGLE_OIDC", userId, email, outcome, reason, null);
    }

    public void logGoogleEvent(String event, Long userId, String email, String outcome, String reason, String sourceIp) {
        logStructured(event, "GOOGLE_OIDC", userId, email, outcome, reason, sourceIp);
    }

    private void logStructured(
            String eventType,
            String channel,
            Long userId,
            String email,
            String result,
            String reason,
            String explicitSourceIp
    ) {
        HttpServletRequest currentRequest = resolveCurrentRequest();
        String sourceIp = resolveSourceIp(explicitSourceIp, currentRequest);
        String userAgent = resolveUserAgent(currentRequest);
        String correlationId = resolveCorrelationId(currentRequest);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("event_type", sanitize(eventType, 64));
        if (channel != null) {
            payload.put("channel", channel);
        }
        payload.put("user_id", userId);
        payload.put("result", sanitize(result, 32));
        payload.put("correlation_id", correlationId);
        payload.put("source_ip", sanitize(sourceIp, 64));
        payload.put("user_agent", truncate(sanitize(userAgent, MAX_USER_AGENT_LENGTH), MAX_USER_AGENT_LENGTH));
        payload.put("email_masked", maskEmail(email));
        payload.put("reason", truncate(sanitize(reason, MAX_REASON_LENGTH), MAX_REASON_LENGTH));

        try {
            auditLog.info("{}", objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            auditLog.info(
                    "{\"timestamp\":\"{}\",\"event_type\":\"{}\",\"user_id\":{},\"result\":\"{}\",\"email_masked\":\"{}\"}",
                    Instant.now(),
                    sanitize(eventType, 64),
                    userId,
                    sanitize(result, 32),
                    maskEmail(email)
            );
        }
    }

    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "-";
        }
        String trimmed = email.trim();
        int at = trimmed.indexOf('@');
        if (at <= 1) {
            return at > 0 ? "***" + trimmed.substring(at) : "***";
        }
        return trimmed.substring(0, 2) + "***" + trimmed.substring(at);
    }

    private HttpServletRequest resolveCurrentRequest() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                return attrs.getRequest();
            }
        } catch (Exception ignored) {
            // Non-web / async execution context
        }
        return null;
    }

    private String resolveSourceIp(String explicitIp, HttpServletRequest request) {
        if (explicitIp != null && !explicitIp.isBlank()) {
            return explicitIp.trim();
        }
        if (request == null) {
            return "-";
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String remote = request.getRemoteAddr();
        return (remote == null || remote.isBlank()) ? "-" : remote;
    }

    private String resolveUserAgent(HttpServletRequest request) {
        if (request == null) {
            return "-";
        }
        String ua = request.getHeader("User-Agent");
        return (ua == null || ua.isBlank()) ? "-" : ua;
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        String mdc = MDC.get("correlationId");
        if (mdc != null && !mdc.isBlank()) {
            return sanitize(mdc, 64);
        }
        if (request != null) {
            Object attr = request.getAttribute("X-Correlation-Id");
            if (attr instanceof String s && !s.isBlank()) {
                return sanitize(s, 64);
            }
            String header = request.getHeader("X-Correlation-Id");
            if (header != null && !header.isBlank()) {
                return sanitize(header, 64);
            }
        }
        return UUID.randomUUID().toString();
    }

    private String sanitize(String val, int maxLen) {
        if (val == null || val.isBlank()) {
            return "-";
        }
        String clean = val.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        return clean.length() <= maxLen ? clean : clean.substring(0, maxLen);
    }

    private String truncate(String val, int maxLen) {
        if (val == null) {
            return "-";
        }
        return val.length() <= maxLen ? val : val.substring(0, maxLen);
    }
}
