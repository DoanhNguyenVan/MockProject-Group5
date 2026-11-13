package com.boxcinema.service;

import com.boxcinema.dto.AuthMethodsDto;
import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;

public interface UserService {

    UserSummaryDto registerCustomer(RegisterRequestDto request);

    UserSummaryDto authenticate(String email, String rawPassword);

    UserSummaryDto authenticateSocial(String provider, String providerId, String email, String fullName);

    default UserSummaryDto authenticateSocial(
            String provider,
            String providerId,
            String email,
            String fullName,
            boolean trustedGoogleEmail) {
        return authenticateSocial(provider, providerId, email, fullName);
    }

    default UserSummaryDto verifyPasswordAndLinkPendingGoogle(
            String pendingGoogleSub,
            String boxEmail,
            String rawPassword) {
        throw new UnsupportedOperationException("Password verification for Google linking is not configured.");
    }

    default UserSummaryDto verifyOtpAndLinkPendingGoogle(
            String pendingGoogleSub,
            String boxEmail,
            String challengeId,
            String otp) {
        throw new UnsupportedOperationException("OTP verification for Google linking is not configured.");
    }

    UserSummaryDto registerGoogleCustomer(String googleSub, String email, String fullName, String phone);

    void linkGoogleAccount(Long userId, String googleSub, String googleEmail);

    void unlinkGoogleAccount(Long userId);

    void reauthenticatePassword(Long userId, String rawPassword);

    default void setPasswordForGoogleUser(Long userId, String newPassword, String confirmPassword) {
        throw new UnsupportedOperationException("Set password is not configured.");
    }

    default void verifyGoogleSubOwnership(Long userId, String googleSub) {
        throw new UnsupportedOperationException("Google identity verification is not configured.");
    }

    AuthMethodsDto getAuthMethods(Long userId);
}
