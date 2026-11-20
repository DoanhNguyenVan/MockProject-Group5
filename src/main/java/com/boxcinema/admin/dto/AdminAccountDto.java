package com.boxcinema.admin.dto;

import com.boxcinema.common.entity.User;
import java.time.LocalDateTime;

/**
 * Publicly safe Admin account projection: no credentials, Google IDs or
 * password hashes.
 */
public record AdminAccountDto(
        Long userId,
        String fullName,
        String email,
        String phone,
        String role,
        String accountStatus,
        Long cinemaId,
        LocalDateTime createdAt) {

    public AdminAccountDto(
            Long userId,
            String fullName,
            String email,
            String phone,
            String role,
            String accountStatus,
            LocalDateTime createdAt) {
        this(userId, fullName, email, phone, role, accountStatus, null, createdAt);
    }

    public static AdminAccountDto from(User user) {
        return new AdminAccountDto(
                user.getUserId(),
                user.getFullName(),
                user.getEmail(),
                user.getPhone(),
                user.getRole(),
                user.getAccountStatus(),
                user.getCinemaId(),
                user.getCreatedAt());
    }
}
