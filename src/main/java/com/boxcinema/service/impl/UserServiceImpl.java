package com.boxcinema.service.impl;

import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.entity.User;
import com.boxcinema.repository.UserRepository;
import com.boxcinema.service.UserService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public UserServiceImpl(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public UserSummaryDto registerCustomer(RegisterRequestDto request) {
        if (request.getEmail() == null || request.getEmail().isBlank()) {
            throw new IllegalArgumentException("Email is required.");
        }
        String cleanEmail = request.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(cleanEmail)) {
            throw new IllegalArgumentException("This email address is already registered.");
        }

        if (request.getPhone() == null || request.getPhone().isBlank()) {
            throw new IllegalArgumentException("Phone number is required.");
        }
        String cleanPhone = request.getPhone().trim();
        if (userRepository.existsByPhone(cleanPhone)) {
            throw new IllegalArgumentException("This phone number is already registered.");
        }

        if (request.getPassword() == null || request.getPassword().length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters long.");
        }

        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new IllegalArgumentException("Passwords do not match.");
        }

        String hashedPassword = passwordEncoder.encode(request.getPassword());
        User user = new User(request.getFullName().trim(), cleanEmail, cleanPhone, hashedPassword, "CUSTOMER");
        user = userRepository.save(user);

        return new UserSummaryDto(user.getUserId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getRole());
    }

    @Override
    @Transactional(readOnly = true)
    public UserSummaryDto authenticate(String email, String rawPassword) {
        if (email == null || email.isBlank() || rawPassword == null || rawPassword.isBlank()) {
            throw new IllegalArgumentException("Email and password are required.");
        }

        User user = userRepository.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password."));

        if (!"ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
            throw new IllegalArgumentException("Your account is currently locked or inactive.");
        }

        if (user.getPasswordHash() == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password.");
        }

        return new UserSummaryDto(user.getUserId(), user.getFullName(), user.getEmail(), user.getPhone(), user.getRole());
    }
}

