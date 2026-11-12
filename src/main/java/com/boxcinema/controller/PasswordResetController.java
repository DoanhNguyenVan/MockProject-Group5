package com.boxcinema.controller;

import com.boxcinema.dto.CompletePasswordResetDto;
import com.boxcinema.dto.PasswordResetRequestDto;
import com.boxcinema.dto.PasswordResetResponseDto;
import com.boxcinema.dto.ResendOtpRequestDto;
import com.boxcinema.dto.VerifyOtpRequestDto;
import com.boxcinema.service.AuthRateLimitService;
import com.boxcinema.service.OtpChallengeTxHelper;
import com.boxcinema.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PasswordResetController {

    private final PasswordResetService passwordResetService;
    private final AuthRateLimitService authRateLimitService;

    public PasswordResetController(
            PasswordResetService passwordResetService,
            ObjectProvider<AuthRateLimitService> authRateLimitServiceProvider) {
        this.passwordResetService = passwordResetService;
        this.authRateLimitService = authRateLimitServiceProvider != null
                ? authRateLimitServiceProvider.getIfAvailable()
                : null;
    }

    @PostMapping({"/api/auth/forgot-password", "/api/auth/password-reset/request"})
    public ResponseEntity<PasswordResetResponseDto> requestPasswordReset(
            @RequestBody(required = false) PasswordResetRequestDto request,
            HttpServletRequest httpRequest) {
        try {
            String rawEmail = request != null ? request.getEmail() : null;
            if (authRateLimitService != null) {
                authRateLimitService.checkForgotPasswordIpRateLimit(resolveClientIp(httpRequest));
                if (rawEmail != null && !rawEmail.isBlank()) {
                    authRateLimitService.checkForgotPasswordEmailRateLimit(rawEmail.trim().toLowerCase());
                }
            }
            PasswordResetResponseDto response = passwordResetService.requestPasswordReset(rawEmail);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (AuthRateLimitService.RateLimitExceededException ex) {
            return rateLimitedResponse(ex.getMessage(), ex.getRetryAfterSeconds());
        } catch (OtpChallengeTxHelper.OtpCooldownException ex) {
            return rateLimitedResponse(ex.getMessage(), ex.getCooldownSeconds());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(PasswordResetResponseDto.error("VALIDATION_ERROR", ex.getMessage()));
        }
    }

    @PostMapping({"/api/auth/forgot-password/verify", "/api/auth/password-reset/verify"})
    public ResponseEntity<PasswordResetResponseDto> verifyOtp(
            @RequestBody(required = false) VerifyOtpRequestDto request,
            HttpServletRequest httpRequest) {
        try {
            if (authRateLimitService != null) {
                authRateLimitService.checkForgotPasswordIpRateLimit(resolveClientIp(httpRequest));
            }
            PasswordResetResponseDto response = passwordResetService.verifyOtp(
                    request != null ? request.getEmail() : null,
                    request != null ? request.getOtp() : null
            );
            return ResponseEntity.ok(response);
        } catch (AuthRateLimitService.RateLimitExceededException ex) {
            return rateLimitedResponse(ex.getMessage(), ex.getRetryAfterSeconds());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(PasswordResetResponseDto.error("VALIDATION_ERROR", ex.getMessage()));
        }
    }

    @PostMapping({"/api/auth/forgot-password/resend", "/api/auth/password-reset/resend"})
    public ResponseEntity<PasswordResetResponseDto> resendOtp(
            @RequestBody(required = false) ResendOtpRequestDto request,
            HttpServletRequest httpRequest) {
        try {
            String rawEmail = request != null ? request.getEmail() : null;
            if (authRateLimitService != null) {
                authRateLimitService.checkForgotPasswordIpRateLimit(resolveClientIp(httpRequest));
                if (rawEmail != null && !rawEmail.isBlank()) {
                    authRateLimitService.checkForgotPasswordEmailRateLimit(rawEmail.trim().toLowerCase());
                }
            }
            PasswordResetResponseDto response = passwordResetService.resendOtp(rawEmail);
            return ResponseEntity.ok(response);
        } catch (AuthRateLimitService.RateLimitExceededException ex) {
            return rateLimitedResponse(ex.getMessage(), ex.getRetryAfterSeconds());
        } catch (OtpChallengeTxHelper.OtpCooldownException ex) {
            return rateLimitedResponse(ex.getMessage(), ex.getCooldownSeconds());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(PasswordResetResponseDto.error("VALIDATION_ERROR", ex.getMessage()));
        }
    }

    @PostMapping({"/api/auth/forgot-password/reset", "/api/auth/password-reset/complete"})
    public ResponseEntity<PasswordResetResponseDto> completePasswordReset(
            @RequestBody(required = false) CompletePasswordResetDto request) {
        try {
            PasswordResetResponseDto response = passwordResetService.completePasswordReset(
                    request != null ? request.getResetToken() : null,
                    request != null ? request.getNewPassword() : null,
                    request != null ? request.getConfirmPassword() : null
            );
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(PasswordResetResponseDto.error("VALIDATION_ERROR", ex.getMessage()));
        }
    }

    private ResponseEntity<PasswordResetResponseDto> rateLimitedResponse(String message, long retryAfterSeconds) {
        long clamped = Math.max(1L, retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(clamped))
                .body(PasswordResetResponseDto.errorWithCooldown(message, (int) clamped));
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
