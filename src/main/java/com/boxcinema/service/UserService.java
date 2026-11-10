package com.boxcinema.service;

import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;

import java.util.Optional;

public interface UserService {
    UserSummaryDto registerCustomer(RegisterRequestDto request);
    UserSummaryDto verifyEmailOtp(String email, String otpCode);
    boolean resendVerificationOtp(String email);
    Optional<String> getPendingOtpPreview(String email);
    UserSummaryDto authenticate(String email, String rawPassword);
    UserSummaryDto authenticateSocial(String provider, String providerId, String email, String fullName);
    void resetPassword(String email, String newPassword, String confirmPassword);
}
