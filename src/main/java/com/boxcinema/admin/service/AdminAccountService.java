package com.boxcinema.admin.service;

import com.boxcinema.admin.dto.AdminAccountDto;
import com.boxcinema.common.entity.Cinema;
import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.CinemaRepository;
import com.boxcinema.common.repository.UserRepository;
import com.boxcinema.common.service.PasswordHashService;
import com.boxcinema.common.service.SessionManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

@Service
public class AdminAccountService {
    private static final Set<String> ROLES = Set.of("CUSTOMER", "STAFF", "MANAGER", "ADMIN");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "LOCKED", "INACTIVE", "SUSPENDED", "PENDING", "PENDING_VERIFICATION");

    private final UserRepository userRepository;
    private final SessionManagementService sessionManagementService;
    private final PasswordHashService passwordHashService;
    private final CinemaRepository cinemaRepository;

    public AdminAccountService(UserRepository userRepository, SessionManagementService sessionManagementService) {
        this(userRepository, sessionManagementService, new PasswordHashService(), null);
    }

    @Autowired
    public AdminAccountService(
            UserRepository userRepository,
            SessionManagementService sessionManagementService,
            @Autowired(required = false) PasswordHashService passwordHashService,
            @Autowired(required = false) CinemaRepository cinemaRepository) {
        this.userRepository = userRepository;
        this.sessionManagementService = sessionManagementService;
        this.passwordHashService = passwordHashService != null ? passwordHashService : new PasswordHashService();
        this.cinemaRepository = cinemaRepository;
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
                        PageRequest.of(Math.max(0, Math.min(page, 100000)), 10, Sort.by(Sort.Direction.ASC, "userId")))
                .map(AdminAccountDto::from);
    }

    @Transactional(readOnly = true)
    public List<Cinema> getAllCinemas() {
        if (cinemaRepository == null) {
            return Collections.emptyList();
        }
        return cinemaRepository.findAll(Sort.by(Sort.Direction.ASC, "cinemaId"));
    }

    @Transactional(readOnly = true)
    public Map<Long, String> getCinemaNameMap() {
        return toCinemaNameMap(getAllCinemas());
    }

    public Map<Long, String> toCinemaNameMap(List<Cinema> cinemas) {
        Map<Long, String> map = new LinkedHashMap<>();
        if (cinemas != null) {
            for (Cinema c : cinemas) {
                if (c != null && c.getCinemaId() != null) {
                    map.put(c.getCinemaId(), c.getCinemaName());
                }
            }
        }
        return map;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getAccountSummaryMetrics() {
        long total = userRepository.count();
        long active = userRepository.countByAccountStatusIgnoreCase("ACTIVE");
        long locked = userRepository.countByAccountStatusIgnoreCase("LOCKED");
        long staffAndAdmins = userRepository.countByRoleNotIgnoreCase("CUSTOMER");
        Map<String, Long> metrics = new LinkedHashMap<>();
        metrics.put("total", total);
        metrics.put("active", active);
        metrics.put("locked", locked);
        metrics.put("staffAndAdmins", staffAndAdmins);
        return metrics;
    }

    @Transactional
    public void createAccount(String fullName, String email, String phone, String role, Long cinemaId, String rawPassword, Long actorId) {
        if (actorId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin account not identified.");
        }
        String cleanName = fullName != null ? fullName.trim() : "";
        if (cleanName.isEmpty() || cleanName.length() > 100) {
            throw new IllegalArgumentException("Full name is required (max 100 characters).");
        }
        String cleanEmail = email != null ? email.trim().toLowerCase(Locale.ROOT) : "";
        if (cleanEmail.isEmpty() || !cleanEmail.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("Please enter a valid email address.");
        }
        if (userRepository.existsByEmail(cleanEmail)) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }
        String cleanPhone = phone != null ? phone.trim() : "";
        if (cleanPhone.isEmpty() || !cleanPhone.matches("^\\+?[0-9 ()-]{8,20}$")) {
            throw new IllegalArgumentException("Please enter a valid phone number (8-20 digits).");
        }
        if (userRepository.existsByPhone(cleanPhone)) {
            throw new IllegalArgumentException("An account with this phone number already exists.");
        }
        String normalizedRole = normalizedFilter(role, ROLES);
        if (normalizedRole == null) {
            normalizedRole = "STAFF";
        }
        String pwdToEncode = (rawPassword != null && !rawPassword.trim().isEmpty()) ? rawPassword.trim() : "Staff@123";
        if (pwdToEncode.length() < 6) {
            throw new IllegalArgumentException("Password must be at least 6 characters.");
        }

        User newUser = new User(cleanName, cleanEmail, cleanPhone, passwordHashService.encode(pwdToEncode), normalizedRole);
        newUser.setAccountStatus("ACTIVE");
        if (!"CUSTOMER".equalsIgnoreCase(normalizedRole) && cinemaId != null && cinemaId > 0) {
            newUser.setCinemaId(cinemaId);
        } else {
            newUser.setCinemaId(null);
        }
        userRepository.saveAndFlush(newUser);
    }

    @Transactional
    public void updateAccount(Long targetId, String fullName, String phone, String role, Long cinemaId, Long actorId) {
        if (actorId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin account not identified.");
        }
        User target = userRepository.findByIdForUpdate(targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found."));

        String cleanName = fullName != null ? fullName.trim() : "";
        if (cleanName.isEmpty() || cleanName.length() > 100) {
            throw new IllegalArgumentException("Full name is required (max 100 characters).");
        }
        String cleanPhone = phone != null ? phone.trim() : "";
        if (cleanPhone.isEmpty() || !cleanPhone.matches("^\\+?[0-9 ()-]{8,20}$")) {
            throw new IllegalArgumentException("Please enter a valid phone number (8-20 digits).");
        }
        if (userRepository.existsByPhoneAndUserIdNot(cleanPhone, targetId)) {
            throw new IllegalArgumentException("Phone number is already used by another account.");
        }

        String normalizedRole = normalizedFilter(role, ROLES);
        if (normalizedRole == null) {
            throw new IllegalArgumentException("Please select a valid role.");
        }

        String oldRole = target.getRole() != null ? target.getRole().trim().toUpperCase(Locale.ROOT) : "CUSTOMER";
        boolean roleChanged = !oldRole.equals(normalizedRole);

        if (roleChanged) {
            if (actorId.equals(target.getUserId()) && !"ADMIN".equalsIgnoreCase(normalizedRole)) {
                throw new IllegalStateException("You cannot demote your own administrator role.");
            }
            if ("ADMIN".equalsIgnoreCase(oldRole)
                    && !"ADMIN".equalsIgnoreCase(normalizedRole)
                    && "ACTIVE".equalsIgnoreCase(target.getAccountStatus())
                    && userRepository.countByRoleIgnoreCaseAndAccountStatusIgnoreCase("ADMIN", "ACTIVE") <= 1) {
                throw new IllegalStateException("Cannot demote the last active administrator.");
            }
        }

        target.setFullName(cleanName);
        target.setPhone(cleanPhone);
        target.setRole(normalizedRole);
        if (!"CUSTOMER".equalsIgnoreCase(normalizedRole) && cinemaId != null && cinemaId > 0) {
            target.setCinemaId(cinemaId);
        } else {
            target.setCinemaId(null);
        }

        userRepository.saveAndFlush(target);
        if (roleChanged && sessionManagementService != null) {
            sessionManagementService.invalidateUserSessions(targetId);
        }
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
        if (lock && sessionManagementService != null) {
            sessionManagementService.invalidateUserSessions(targetId);
        }
    }
}
