package com.boxcinema.service;

import com.boxcinema.entity.User;
import com.boxcinema.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Performs transparent BCrypt -> Argon2id password hash upgrades on verified login
 * inside an isolated writable REQUIRES_NEW transaction (Specification V1.2 Section D1).
 */
@Service
public class PasswordUpgradeService {

    private static final Logger log = LoggerFactory.getLogger(PasswordUpgradeService.class);

    private final UserRepository userRepository;
    private final PasswordHashService passwordHashService;

    public PasswordUpgradeService(UserRepository userRepository, PasswordHashService passwordHashService) {
        this.userRepository = userRepository;
        this.passwordHashService = passwordHashService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upgradeLegacyHashIfNeeded(Long userId, String rawPassword, String currentStoredHash) {
        if (userId == null || rawPassword == null || rawPassword.isEmpty()) {
            return;
        }
        if (passwordHashService == null || !passwordHashService.upgradeEncoding(currentStoredHash)) {
            return;
        }
        try {
            String upgradedHash = passwordHashService.upgradeVerifiedPasswordToArgon2id(rawPassword);
            User user = userRepository.findByIdForUpdate(userId).orElse(null);
            if (user == null) {
                return;
            }
            if (!passwordHashService.upgradeEncoding(user.getPasswordHash())) {
                return;
            }
            if (!passwordHashService.matches(rawPassword, user.getPasswordHash())) {
                return;
            }
            user.setPasswordHash(upgradedHash);
            userRepository.saveAndFlush(user);
        } catch (Exception ex) {
            // Never abort a verified login or corrupt the existing BCrypt hash if upgrade fails.
            log.warn("Deferred Argon2id upgrade for user {}: {}", userId, ex.getMessage());
        }
    }
}

