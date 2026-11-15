
package com.boxcinema.config;

import com.boxcinema.entity.User;
import com.boxcinema.repository.UserRepository;
import com.boxcinema.service.PasswordHashService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminAccountInitializer implements CommandLineRunner {

    private static final Logger log =
            LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${admin.initial.email:adminq@gmail.com}")
    private String adminEmail;

    @Value("${admin.initial.phone:0123456789}")
    private String adminPhone;

    @Value("${admin.initial.password:}")
    private String adminPassword;

    public AdminAccountInitializer(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder) {

        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder != null
                ? passwordEncoder
                : new PasswordHashService();
    }

    @Override
    public void run(String... args) {

        if (adminPassword == null || adminPassword.isBlank()) {
            log.warn("Admin account initialization skipped: password is not configured.");
            return;
        }

        if (userRepository.existsByEmail(adminEmail)) {
            log.info("Admin account already exists. No changes made.");
            return;
        }

        if (userRepository.existsByPhone(adminPhone)) {
            log.warn("Admin account initialization skipped: phone number already exists.");
            return;
        }

        User admin = new User(
                "BOX Cinema Administrator",
                adminEmail,
                adminPhone,
                passwordEncoder.encode(adminPassword),
                "ADMIN"
        );

        admin.setAccountStatus("ACTIVE");

        userRepository.save(admin);

        log.info("Initial ADMIN account created: {}", adminEmail);
    }
}
