package com.boxcinema.service;

import com.boxcinema.dto.AdminAccountDto;
import com.boxcinema.entity.User;
import com.boxcinema.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;
import java.util.Set;

@Service
public class AdminAccountService {
    private static final Set<String> ROLES = Set.of("CUSTOMER", "STAFF", "MANAGER", "ADMIN");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "LOCKED", "INACTIVE", "SUSPENDED", "PENDING", "PENDING_VERIFICATION");
    private final UserRepository userRepository;
    private final SessionManagementService sessionManagementService;

    public AdminAccountService(UserRepository userRepository, SessionManagementService sessionManagementService) {
        this.userRepository = userRepository;
        this.sessionManagementService = sessionManagementService;
    }

    private String normalizedFilter(String value, Set<String> choices) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!choices.contains(normalized)) throw new IllegalArgumentException("Invalid account filter.");
        return normalized;
    }

    @Transactional(readOnly = true)
    public Page<AdminAccountDto> findAccounts(String search, String role, String status, int page) {
        String normalizedSearch = search == null || search.isBlank() ? null : search.strip();
        if (normalizedSearch != null && normalizedSearch.length() > 120) {
            throw new IllegalArgumentException("Search text is too long.");
        }
        return userRepository.findAdminAccounts(normalizedSearch,
                        normalizedFilter(role, ROLES), normalizedFilter(status, STATUSES),
                        PageRequest.of(Math.max(0, Math.min(page, 100000)), 10, Sort.by(Sort.Direction.DESC, "userId")))
                .map(AdminAccountDto::from);
    }

    /** Lock ACTIVE accounts, or unlock LOCKED accounts only. PENDING accounts remain pending. */
    @Transactional
    public void changeLockStatus(Long targetId, boolean lock, Long actorId) {
        if (actorId == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin account not identified.");
        User target = userRepository.findByIdForUpdate(targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));
        String current = target.getAccountStatus() == null ? "" : target.getAccountStatus().toUpperCase(Locale.ROOT);
        String expected = lock ? "ACTIVE" : "LOCKED";
        if (!expected.equals(current)) {
            throw new IllegalStateException(lock ? "Only active accounts can be locked." : "Only locked accounts can be unlocked.");
        }
        if (lock && actorId.equals(target.getUserId())) {
            throw new IllegalStateException("You cannot lock your own admin account.");
        }
        if (lock && "ADMIN".equalsIgnoreCase(target.getRole())
                && userRepository.countByRoleIgnoreCaseAndAccountStatusIgnoreCase("ADMIN", "ACTIVE") <= 1) {
            throw new IllegalStateException("Cannot lock the last active administrator.");
        }
        target.setAccountStatus(lock ? "LOCKED" : "ACTIVE");
        userRepository.saveAndFlush(target);
        if (lock) sessionManagementService.invalidateUserSessions(targetId);
    }
}
