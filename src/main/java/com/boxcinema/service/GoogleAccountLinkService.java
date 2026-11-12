package com.boxcinema.service;

import com.boxcinema.entity.User;
import com.boxcinema.exception.AuthException;
import com.boxcinema.repository.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

/**
 * Transactional domain service handling idempotent atomic Google account
 * linking
 * and safe unlinking under Specification V1.2 Section G.
 */
@Service
public class GoogleAccountLinkService {

        private final UserRepository userRepository;
        private final EmailService emailService;
        private final PasswordPolicyService passwordPolicyService;
        private final AuthAuditService authAuditService;

        @Autowired
        public GoogleAccountLinkService(
                        UserRepository userRepository,
                        EmailService emailService,
                        PasswordPolicyService passwordPolicyService,
                        ObjectProvider<AuthAuditService> authAuditServiceProvider) {
                this.userRepository = userRepository;
                this.emailService = emailService;
                this.passwordPolicyService = (passwordPolicyService != null) ? passwordPolicyService
                                : new PasswordPolicyService();
                this.authAuditService = (authAuditServiceProvider != null)
                                ? authAuditServiceProvider.getIfAvailable(AuthAuditService::new)
                                : new AuthAuditService();
        }

        public GoogleAccountLinkService(
                        UserRepository userRepository,
                        EmailService emailService,
                        PasswordPolicyService passwordPolicyService,
                        AuthAuditService authAuditService) {
                this.userRepository = userRepository;
                this.emailService = emailService;
                this.passwordPolicyService = (passwordPolicyService != null) ? passwordPolicyService
                                : new PasswordPolicyService();
                this.authAuditService = (authAuditService != null) ? authAuditService : new AuthAuditService();
        }

        @Transactional(isolation = Isolation.READ_COMMITTED)
        public void linkGoogleAccount(Long userId, String googleSub, String googleEmail) {
                if (userId == null) {
                        throw AuthException.badRequest("VALIDATION_ERROR", "Authenticated user session is required.");
                }
                if (googleSub == null || googleSub.isBlank()) {
                        throw AuthException.badRequest("VALIDATION_ERROR", "Google user identifier (sub) is missing.");
                }
                String cleanSub = googleSub.trim();
                String cleanGoogleEmail = passwordPolicyService.validateAndNormalizeEmail(googleEmail);

                User user = userRepository.findByIdForUpdate(userId)
                                .orElseThrow(() -> AuthException.badRequest("VALIDATION_ERROR",
                                                "User account not found."));

                if (!"ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
                        throw AuthException.forbidden("ACCOUNT_NOT_ACTIVE",
                                        "Only active accounts can link Google sign-in.");
                }

                if (user.getGoogleId() != null && !user.getGoogleId().isBlank()) {
                        if (cleanSub.equals(user.getGoogleId())) {
                                authAuditService.logGoogleEvent("GOOGLE_LINK_SUCCESS", userId, user.getEmail(),
                                                "IDEMPOTENT",
                                                "Same Google sub already linked");
                                return;
                        }
                        authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", userId, user.getEmail(), "REJECTED",
                                        "Account already linked to a different Google sub");
                        throw AuthException.conflict("USER_ALREADY_HAS_DIFFERENT_GOOGLE",
                                        "Your BOX Cinema account is already linked to a different Google account.");
                }

                if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
                        authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", userId, user.getEmail(), "DENIED",
                                        "Password required before linking");
                        throw AuthException.forbidden("RECENT_AUTH_REQUIRED",
                                        "You must set a BOX Cinema password before linking a Google account.");
                }

                Optional<User> existingSubOwner = userRepository.findByGoogleId(cleanSub);
                if (existingSubOwner.isPresent() && !existingSubOwner.get().getUserId().equals(userId)) {
                        authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", userId, user.getEmail(), "REJECTED",
                                        "Google sub owned by another user");
                        throw AuthException.conflict("GOOGLE_IDENTITY_ALREADY_LINKED",
                                        "This Google identity is already linked to another BOX Cinema account.");
                }

                Optional<User> existingEmailOwner = userRepository.findByEmail(cleanGoogleEmail);
                if (existingEmailOwner.isPresent() && !existingEmailOwner.get().getUserId().equals(userId)) {
                        authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", userId, user.getEmail(), "REJECTED",
                                        "Google email conflicts with another BOX Cinema user's primary email");
                        throw AuthException.conflict("GOOGLE_EMAIL_CONFLICT",
                                        "This Google email (" + cleanGoogleEmail
                                                        + ") is already associated with another BOX Cinema account.");
                }

                int updatedRows;
                try {
                        updatedRows = userRepository.atomicLinkGoogle(userId, cleanSub);
                } catch (DataIntegrityViolationException ex) {
                        authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", userId, user.getEmail(), "REJECTED",
                                        "Unique google_id constraint conflict");
                        throw AuthException.conflict("GOOGLE_IDENTITY_ALREADY_LINKED",
                                        "This Google identity is already linked to another BOX Cinema account.");
                }

                if (updatedRows == 0) {
                        User reloaded = userRepository.findById(userId)
                                        .orElseThrow(() -> AuthException.badRequest("VALIDATION_ERROR",
                                                        "User account not found."));
                        if (cleanSub.equals(reloaded.getGoogleId())) {
                                authAuditService.logGoogleEvent("GOOGLE_LINK_SUCCESS", userId, reloaded.getEmail(),
                                                "IDEMPOTENT",
                                                "Same Google sub linked concurrently");
                                return;
                        }
                        if (reloaded.getGoogleId() != null && !reloaded.getGoogleId().isBlank()) {
                                authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", userId, reloaded.getEmail(),
                                                "REJECTED",
                                                "Concurrent link to different Google sub");
                                throw AuthException.conflict("USER_ALREADY_HAS_DIFFERENT_GOOGLE",
                                                "Your BOX Cinema account is already linked to a different Google account.");
                        }
                        throw AuthException.conflict("GOOGLE_LINK_CONFLICT",
                                        "Unable to link Google account because account state changed concurrently.");
                }

                authAuditService.logGoogleEvent("GOOGLE_LINK_SUCCESS", userId, user.getEmail(), "SUCCESS",
                                "Google sub linked");
                runAfterCommit(() -> emailService.sendGoogleLinkedNotificationEmailAsync(user.getEmail(),
                                user.getFullName()));
        }

        @Transactional(isolation = Isolation.READ_COMMITTED)
        public void unlinkGoogleAccount(Long userId) {
                if (userId == null) {
                        throw AuthException.badRequest("VALIDATION_ERROR", "Authenticated user session is required.");
                }

                User user = userRepository.findByIdForUpdate(userId)
                                .orElseThrow(() -> AuthException.badRequest("VALIDATION_ERROR",
                                                "User account not found."));

                if (!"ACTIVE".equalsIgnoreCase(user.getAccountStatus())) {
                        throw AuthException.forbidden("ACCOUNT_NOT_ACTIVE",
                                        "Only active accounts can modify linked sign-in methods.");
                }

                if (user.getPasswordHash() == null || user.getPasswordHash().isBlank()) {
                        authAuditService.logGoogleEvent("GOOGLE_UNLINK_FAILURE", userId, user.getEmail(), "REJECTED",
                                        "Cannot unlink only authentication method");
                        throw AuthException.conflict("CANNOT_UNLINK_LAST_AUTH_METHOD",
                                        "You must set a BOX Cinema password before unlinking your Google account so you do not get locked out.");
                }

                String currentSub = user.getGoogleId();
                if (currentSub == null || currentSub.isBlank()) {
                        return;
                }

                int updatedRows = userRepository.atomicUnlinkGoogle(userId, currentSub);
                if (updatedRows == 0) {
                        User reloaded = userRepository.findById(userId)
                                        .orElseThrow(() -> AuthException.badRequest("VALIDATION_ERROR",
                                                        "User account not found."));
                        if (reloaded.getGoogleId() == null || reloaded.getGoogleId().isBlank()) {
                                return;
                        }
                        throw AuthException.conflict("GOOGLE_UNLINK_CONFLICT",
                                        "Unable to unlink Google account. Please try again.");
                }

                authAuditService.logGoogleEvent("GOOGLE_UNLINK_SUCCESS", userId, user.getEmail(), "SUCCESS",
                                "Google sub removed");
                runAfterCommit(() -> emailService.sendGoogleUnlinkedNotificationEmailAsync(user.getEmail(),
                                user.getFullName()));
        }

        private void runAfterCommit(Runnable task) {
                if (TransactionSynchronizationManager.isActualTransactionActive()
                                && TransactionSynchronizationManager.isSynchronizationActive()) {
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                @Override
                                public void afterCommit() {
                                        task.run();
                                }
                        });
                } else {
                        task.run();
                }
        }
}
