package com.boxcinema.service;

import com.boxcinema.entity.User;
import com.boxcinema.repository.PasswordResetRepository;
import com.boxcinema.repository.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Periodically cleans up abandoned PENDING_VERIFICATION registrations older than expiryHours
 * only when the user record has zero references in business tables.
 */
@Service
public class PendingAccountCleanupService {

    private final UserRepository userRepository;
    private final PasswordResetRepository passwordResetRepository;
    private final JdbcTemplate jdbcTemplate;
    private final AuthAuditService authAuditService;

    @Value("${app.cleanup.pending-accounts.enabled:true}")
    private boolean cleanupEnabled = true;

    @Value("${app.cleanup.pending-accounts.expiry-hours:24}")
    private long expiryHours = 24L;

    @Autowired
    public PendingAccountCleanupService(
            UserRepository userRepository,
            PasswordResetRepository passwordResetRepository,
            JdbcTemplate jdbcTemplate,
            ObjectProvider<AuthAuditService> authAuditServiceProvider) {
        this.userRepository = userRepository;
        this.passwordResetRepository = passwordResetRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.authAuditService = authAuditServiceProvider != null
                ? authAuditServiceProvider.getIfAvailable(AuthAuditService::new)
                : new AuthAuditService();
    }

    @Scheduled(initialDelay = 3_600_000L, fixedDelay = 3_600_000L)
    public void runScheduledCleanup() {
        if (!cleanupEnabled) {
            return;
        }
        cleanupExpiredPendingAccounts(LocalDateTime.now().minusHours(Math.max(1L, expiryHours)));
    }

    @Transactional
    public int cleanupExpiredPendingAccounts(LocalDateTime cutoff) {
        if (cutoff == null) {
            return 0;
        }
        List<User> candidates = userRepository.findPendingAccountsOlderThan(cutoff);
        int deleted = 0;
        for (User user : candidates) {
            String status = user.getAccountStatus() != null ? user.getAccountStatus().trim().toUpperCase() : "";
            if (!"PENDING_VERIFICATION".equals(status) && !"PENDING".equals(status)) {
                continue;
            }
            if (isVerifiedInChallenge(user.getUserId()) || hasBusinessRecords(user.getUserId())) {
                continue;
            }
            passwordResetRepository.deleteByUserId(user.getUserId());
            userRepository.delete(user);
            deleted++;
            authAuditService.logEvent(
                    "PENDING_ACCOUNT_CLEANUP",
                    user.getUserId(),
                    user.getEmail(),
                    "DELETED",
                    "Abandoned unverified account expired before " + cutoff
            );
        }
        return deleted;
    }

    private boolean isVerifiedInChallenge(Long userId) {
        if (userId == null || jdbcTemplate == null) {
            return true;
        }
        Long verifiedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM PASSWORD_RESET WHERE user_id = ? AND verified_at IS NOT NULL",
                Long.class, userId
        );
        return verifiedCount != null && verifiedCount > 0;
    }

    public boolean hasBusinessRecords(Long userId) {
        if (userId == null || jdbcTemplate == null) {
            return true;
        }
        Long bookingsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM BOOKINGS WHERE user_id = ? OR sold_by_staff_id = ?",
                Long.class, userId, userId
        );
        if (bookingsCount != null && bookingsCount > 0) {
            return true;
        }
        Long inquiriesCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INQUIRIES WHERE user_id = ? OR assigned_to = ?",
                Long.class, userId, userId
        );
        if (inquiriesCount != null && inquiriesCount > 0) {
            return true;
        }
        Long heldSeatsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM SHOWTIME_SEATS WHERE held_by_user_id = ?",
                Long.class, userId
        );
        if (heldSeatsCount != null && heldSeatsCount > 0) {
            return true;
        }
        Long checkedInTicketsCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM TICKETS WHERE checked_in_by = ?",
                Long.class, userId
        );
        return checkedInTicketsCount != null && checkedInTicketsCount > 0;
    }
}

