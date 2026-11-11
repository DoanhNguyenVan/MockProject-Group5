package com.boxcinema.service;

import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;

import java.util.Optional;

public interface UserService {
    UserSummaryDto registerCustomer(RegisterRequestDto request);
    UserSummaryDto authenticate(String email, String rawPassword);
    UserSummaryDto authenticateSocial(String provider, String providerId, String email, String fullName);
    UserSummaryDto registerGoogleCustomer(String googleSub, String email, String fullName, String phone);
    void linkGoogleAccount(Long userId, String googleSub, String googleEmail);
    void unlinkGoogleAccount(Long userId);
    void resetPassword(String email, String newPassword, String confirmPassword);

    // Deprecated OTP verification methods kept for backwards compatibility if referenced
    default UserSummaryDto verifyEmailOtp(String email, String otpCode) {
        throw new UnsupportedOperationException("OTP registration has been discontinued. Accounts are activated immediately.");
    }
    default boolean resendVerificationOtp(String email) {
        return false;
    }
    default Optional<String> getPendingOtpPreview(String email) {
        return Optional.empty();
    }
}
