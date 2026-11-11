package com.boxcinema.controller;

import com.boxcinema.dto.*;
import com.boxcinema.service.PasswordResetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth/password-reset")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    /**
     * 1. Request OTP for password reset.
     */
    @PostMapping("/request")
    public ResponseEntity<PasswordResetResponseDto> requestPasswordReset(@RequestBody PasswordResetRequestDto request) {
        try {
            PasswordResetResponseDto response = passwordResetService.requestPasswordReset(request.getEmail());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(PasswordResetResponseDto.error(ex.getMessage()));
        }
    }

    /**
     * 2. Verify OTP code.
     */
    @PostMapping("/verify")
    public ResponseEntity<PasswordResetResponseDto> verifyOtp(@RequestBody VerifyOtpRequestDto request) {
        try {
            PasswordResetResponseDto response = passwordResetService.verifyOtp(request.getEmail(), request.getOtp());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(PasswordResetResponseDto.error(ex.getMessage()));
        }
    }

    /**
     * 3. Resend OTP code.
     */
    @PostMapping("/resend")
    public ResponseEntity<PasswordResetResponseDto> resendOtp(@RequestBody ResendOtpRequestDto request) {
        try {
            PasswordResetResponseDto response = passwordResetService.resendOtp(request.getEmail());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(PasswordResetResponseDto.error(ex.getMessage()));
        }
    }

    /**
     * 4. Complete password reset with new password.
     */
    @PostMapping("/complete")
    public ResponseEntity<PasswordResetResponseDto> completePasswordReset(@RequestBody CompletePasswordResetDto request) {
        try {
            PasswordResetResponseDto response = passwordResetService.completePasswordReset(
                    request.getResetToken(),
                    request.getNewPassword(),
                    request.getConfirmPassword()
            );
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(PasswordResetResponseDto.error(ex.getMessage()));
        }
    }
}

