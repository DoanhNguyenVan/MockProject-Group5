package com.boxcinema.service;

import com.boxcinema.dto.AuthMethodsDto;
import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.entity.OtpPurpose;
import com.boxcinema.entity.PasswordReset;
import com.boxcinema.entity.PasswordResetStatus;
import com.boxcinema.entity.User;
import com.boxcinema.repository.PasswordResetRepository;
import com.boxcinema.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Full Spring Boot + MySQL 8.0 integration verification of BOX Cinema
 * Authentication Architecture (AUTH-01 .. AUTH-10).
 */
@SpringBootTest(properties = "app.security.login.progressive-delay-enabled=false")
public class AuthenticationArchitectureIntegrationTest {

        @Autowired
        private EmailVerificationService emailVerificationService;

        @Autowired
        private UserService userService;

        @Autowired
        private PasswordResetService passwordResetService;

        @Autowired
        private PendingAccountCleanupService pendingAccountCleanupService;

        @Autowired
        private PasswordPolicyService passwordPolicyService;

        @Autowired
        private AuthRateLimitService authRateLimitService;

        @Autowired
        private LoginAttemptService loginAttemptService;

        @Autowired
        private OtpService otpService;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private PasswordResetRepository passwordResetRepository;

        @Autowired
        private PasswordEncoder passwordEncoder;

        @Autowired
        private JdbcTemplate jdbcTemplate;

        @MockBean
        private EmailService emailService;

        private static final String EMAIL_PREFIX = "auth_arch_it_";

        @BeforeEach
        void setUp() {
                when(emailService.sendVerificationOtpEmail(anyString(), anyString(), anyString())).thenReturn(true);
                when(emailService.sendPasswordResetOtpEmail(anyString(), anyString(), anyString())).thenReturn(true);
                when(emailService.sendPasswordResetSuccessEmail(anyString(), anyString())).thenReturn(true);
                authRateLimitService.clearAll();
                cleanUpTestAccounts();
        }

        @AfterEach
        void tearDown() {
                authRateLimitService.clearAll();
                cleanUpTestAccounts();
        }

        private void cleanUpTestAccounts() {
                List<Long> ids = jdbcTemplate.queryForList(
                                "SELECT user_id FROM USERS WHERE email LIKE ?",
                                Long.class,
                                EMAIL_PREFIX + "%");
                for (Long id : ids) {
                        jdbcTemplate.update("DELETE FROM INQUIRIES WHERE user_id = ?", id);
                        passwordResetRepository.deleteByUserId(id);
                        userRepository.deleteById(id);
                        loginAttemptService.clearAttempts(EMAIL_PREFIX);
                }
        }

        @Test
        void testAUTH01AndAUTH02AndAUTH10_EmailRegisterOtpVerifyAndLoginLifecycle() {
                String email = EMAIL_PREFIX + "reg1@boxcinema.com";
                RegisterRequestDto req = new RegisterRequestDto(
                                "Auth Tester",
                                email,
                                "0912345001",
                                "CinemaPass@2026!",
                                "CinemaPass@2026!",
                                true);

                // 1. Register creates PENDING account & EMAIL_VERIFICATION OTP
                EmailVerificationService.RegistrationResult reg = emailVerificationService.registerWithEmailOtp(req);
                assertEquals("PENDING", reg.user().getAccountStatus());
                assertNotNull(reg.challengeId());

                PasswordReset pr = passwordResetRepository
                                .findByUserIdAndPurpose(reg.user().getUserId(), OtpPurpose.EMAIL_VERIFICATION)
                                .orElseThrow();
                assertEquals(PasswordResetStatus.SENT, pr.getStatus());

                // 2. Unverified account CANNOT login before OTP verification
                IllegalStateException pendingLoginEx = assertThrows(
                                IllegalStateException.class,
                                () -> userService.authenticate(email, "CinemaPass@2026!"));
                assertTrue(pendingLoginEx.getMessage().startsWith("PENDING_VERIFICATION:"));

                // 3. Re-registering same email while pending does not create duplicate row
                assertThrows(
                                OtpChallengeTxHelper.PendingVerificationException.class,
                                () -> emailVerificationService.registerWithEmailOtp(req));

                // 4. Wrong OTP fails without activating user
                assertThrows(
                                IllegalArgumentException.class,
                                () -> emailVerificationService.verifyRegistrationOtp(email, reg.challengeId(),
                                                "000000"));
                assertEquals("PENDING", userRepository.findByEmail(email).orElseThrow().getAccountStatus());

                // 5. Valid purpose-bound OTP activates user to ACTIVE
                String knownOtp = "889900";
                pr = passwordResetRepository
                                .findByUserIdAndPurpose(reg.user().getUserId(), OtpPurpose.EMAIL_VERIFICATION)
                                .orElseThrow();
                pr.setOtpHash(otpService.computeOtpHmac(reg.user().getUserId(), OtpPurpose.EMAIL_VERIFICATION,
                                pr.getChallengeId(), knownOtp));
                passwordResetRepository.save(pr);

                UserSummaryDto verified = emailVerificationService.verifyRegistrationOtp(email, pr.getChallengeId(),
                                knownOtp);
                assertEquals("ACTIVE", verified.getAccountStatus());

                // 6. Now email+password login succeeds!
                UserSummaryDto loggedIn = userService.authenticate(email, "CinemaPass@2026!");
                assertEquals(verified.getUserId(), loggedIn.getUserId());

                // 7. Locking account immediately blocks login (AUTH-10)
                User dbUser = userRepository.findById(loggedIn.getUserId()).orElseThrow();
                dbUser.setAccountStatus("LOCKED");
                userRepository.save(dbUser);
                assertThrows(IllegalArgumentException.class, () -> userService.authenticate(email, "CinemaPass@2026!"));
        }

        @Test
        void testAUTH03ThroughAUTH08_GoogleRegisterNoAutoMergeLinkDualLoginAndSafeUnlink() {
                String googleOnlyEmail = EMAIL_PREFIX + "gonly@gmail.com";
                String googleSub1 = "oidc_sub_it_001";

                // 1. Brand new Google identity requires onboarding
                // (/auth/google/complete-profile) and does NOT create placeholder row
                IllegalStateException onboardingEx = assertThrows(
                                IllegalStateException.class,
                                () -> userService.authenticateSocial("GOOGLE", googleSub1, googleOnlyEmail,
                                                "Dũng Đặng"));
                assertTrue(onboardingEx.getMessage().startsWith("GOOGLE_ONBOARDING_REQUIRED:"));
                assertTrue(userRepository.findByGoogleId(googleSub1).isEmpty());
                assertTrue(userRepository.findByEmail(googleOnlyEmail).isEmpty());

                // 1b. Completing Google onboarding with real phone creates ACTIVE Google-only
                // user (passwordHash = NULL)
                UserSummaryDto gUser = userService.registerGoogleCustomer(googleSub1, googleOnlyEmail, "Dũng Đặng",
                                "0912345009");
                assertEquals("Dũng Đặng", gUser.getFullName());
                assertEquals("ACTIVE", gUser.getAccountStatus());
                assertEquals("+84912345009", gUser.getPhone());
                assertFalse(gUser.isHasPassword());
                assertTrue(gUser.isGoogleLinked());

                // 2. Subsequent Google sign-in authenticates directly into the same userId
                // (AUTH-04), cannot login by password or unlink (AUTH-08)
                UserSummaryDto gLogged = userService.authenticateSocial("GOOGLE", googleSub1, googleOnlyEmail,
                                "Dũng Đặng");
                assertEquals(gUser.getUserId(), gLogged.getUserId());
                assertThrows(IllegalArgumentException.class,
                                () -> userService.authenticate(googleOnlyEmail, "Anything1!"));
                assertThrows(IllegalArgumentException.class, () -> userService.unlinkGoogleAccount(gUser.getUserId()));

                // 4. Separate Email+Password account with email matching a NEW Google login
                // WITHOUT EMAIL_VERIFICATION verified_at evidence requires one-time
                // verification (GOOGLE_EMAIL_OTP_REQUIRED)
                String pwEmail = EMAIL_PREFIX + "pwuser@gmail.com";
                User pwUser = new User("PW User", pwEmail, "0912345003", passwordEncoder.encode("DualPass@2026!"),
                                "CUSTOMER");
                pwUser.setAccountStatus("ACTIVE");
                pwUser = userRepository.save(pwUser);
                final Long pwUserId = pwUser.getUserId();

                String pwUserGoogleSub = "oidc_sub_it_002";
                IllegalStateException conflictEx = assertThrows(
                                IllegalStateException.class,
                                () -> userService.authenticateSocial("GOOGLE", pwUserGoogleSub, pwEmail, "PW User"));
                assertTrue(conflictEx.getMessage().startsWith("GOOGLE_EMAIL_OTP_REQUIRED:"));
                assertNull(userRepository.findById(pwUserId).orElseThrow().getGoogleId());

                // 5. Re-authenticate password -> reject Google email belonging to another user
                // (GOOGLE_EMAIL_CONFLICT)
                // & duplicate Google sub (Test E) -> succeed on DIFFERENT unused Gmail without
                // altering USERS.email
                assertThrows(IllegalArgumentException.class,
                                () -> userService.reauthenticatePassword(pwUserId, "WrongPass@2026!"));
                userService.reauthenticatePassword(pwUserId, "DualPass@2026!");

                // GOOGLE_EMAIL_CONFLICT: Google email matches another user's primary email ->
                // MUST be rejected
                com.boxcinema.exception.AuthException emailConflictEx = assertThrows(
                                com.boxcinema.exception.AuthException.class,
                                () -> userService.linkGoogleAccount(pwUserId, "unused_sub_mismatch_99",
                                                googleOnlyEmail));
                assertEquals("GOOGLE_EMAIL_CONFLICT", emailConflictEx.getErrorCode());
                assertNull(userRepository.findById(pwUserId).orElseThrow().getGoogleId());

                // Test E: Google sub already owned by another user MUST be rejected even if
                // email argument matches
                assertThrows(
                                IllegalArgumentException.class,
                                () -> userService.linkGoogleAccount(pwUserId, googleSub1, pwEmail));
                assertNull(userRepository.findById(pwUserId).orElseThrow().getGoogleId());

                // Test A (Cross-email linking): Linking unused Gmail different from USERS.email
                // SUCCEEDS and keeps USERS.email
                String crossWorkGmail = EMAIL_PREFIX + "pwuser.work@gmail.com";
                long usersBeforeLink = userRepository.count();
                String originalHash = userRepository.findById(pwUserId).orElseThrow().getPasswordHash();
                userService.linkGoogleAccount(pwUserId, pwUserGoogleSub, crossWorkGmail);

                User linkedUser = userRepository.findById(pwUserId).orElseThrow();
                assertEquals(pwUserGoogleSub, linkedUser.getGoogleId());
                assertEquals(pwEmail, linkedUser.getEmail(),
                                "Primary USERS.email must never change when linking a different Gmail");
                assertEquals(originalHash, linkedUser.getPasswordHash());
                assertEquals("CUSTOMER", linkedUser.getRole());
                assertEquals("ACTIVE", linkedUser.getAccountStatus());
                assertEquals(usersBeforeLink, userRepository.count(), "Linking must not create duplicate users");

                // 6. Test B & Test C: Both Email+PW and Google login resolve to exact same
                // userId (AUTH-06)
                assertEquals(pwUserId, userService.authenticate(pwEmail, "DualPass@2026!").getUserId());
                assertEquals(pwUserId,
                                userService.authenticateSocial("GOOGLE", pwUserGoogleSub, crossWorkGmail, "PW User")
                                                .getUserId());
                assertEquals(usersBeforeLink, userRepository.count(),
                                "Google login after link must not create duplicate user");

                // Same-user same-sub link is idempotent, while different-sub overwrite is
                // blocked
                assertDoesNotThrow(() -> userService.linkGoogleAccount(pwUserId, pwUserGoogleSub, pwEmail));
                assertThrows(IllegalArgumentException.class,
                                () -> userService.linkGoogleAccount(pwUserId, "different_sub_999", pwEmail));

                // Verify Account B (gUser) remains untouched throughout
                assertEquals(googleSub1, userRepository.findById(gUser.getUserId()).orElseThrow().getGoogleId());

                AuthMethodsDto dualMethods = userService.getAuthMethods(pwUserId);
                assertTrue(dualMethods.isHasPassword());
                assertTrue(dualMethods.isGoogleLinked());
                assertTrue(dualMethods.isCanUnlinkGoogle());

                // 7. Forgot password still works when dual-linked without disturbing
                // EMAIL_VERIFICATION purpose
                assertTrue(passwordResetService.requestPasswordReset(pwEmail).isSuccess());
                assertTrue(passwordResetRepository.findByUserIdAndPurpose(pwUserId, OtpPurpose.PASSWORD_RESET)
                                .isPresent());
                assertTrue(passwordResetRepository.findByUserIdAndPurpose(pwUserId, OtpPurpose.EMAIL_VERIFICATION)
                                .isEmpty());

                // 8. Test H: Locked user cannot reauth, link, unlink, or Google login
                linkedUser.setAccountStatus("LOCKED");
                userRepository.saveAndFlush(linkedUser);
                assertThrows(IllegalArgumentException.class,
                                () -> userService.reauthenticatePassword(pwUserId, "DualPass@2026!"));
                assertThrows(IllegalArgumentException.class, () -> userService.unlinkGoogleAccount(pwUserId));
                assertThrows(IllegalArgumentException.class,
                                () -> userService.authenticateSocial("GOOGLE", pwUserGoogleSub, pwEmail, "PW User"));

                linkedUser.setAccountStatus("ACTIVE");
                userRepository.saveAndFlush(linkedUser);

                // 9. Unlink Google succeeds because password exists (AUTH-08), keeping email
                // login intact
                userService.unlinkGoogleAccount(pwUserId);
                assertFalse(userRepository.findById(pwUserId).orElseThrow().getGoogleId() != null);
                assertFalse(userService.getAuthMethods(pwUserId).isGoogleLinked());
                assertEquals(pwUserId, userService.authenticate(pwEmail, "DualPass@2026!").getUserId());

                // 10. Google-only account sets a password -> gains dual login while preserving
                // google_id/role/cinema_id
                userService.setPasswordForGoogleUser(gUser.getUserId(), "GooglePass@2026", "GooglePass@2026");
                User upgradedGoogleUser = userRepository.findById(gUser.getUserId()).orElseThrow();
                assertNotNull(upgradedGoogleUser.getPasswordHash());
                assertEquals(googleSub1, upgradedGoogleUser.getGoogleId());
                assertEquals("CUSTOMER", upgradedGoogleUser.getRole());
                assertNull(upgradedGoogleUser.getCinemaId());
                assertEquals(gUser.getUserId(),
                                userService.authenticate(googleOnlyEmail, "GooglePass@2026").getUserId());
                assertEquals(gUser.getUserId(), userService
                                .authenticateSocial("GOOGLE", googleSub1, googleOnlyEmail, "Dũng Đặng").getUserId());
        }

        @Test
        void testPendingAccountCleanupDeletesOnlyAbandonedWithoutBusinessRecords() {
                String abandonedEmail = EMAIL_PREFIX + "abandoned@boxcinema.com";
                String busyPendingEmail = EMAIL_PREFIX + "busypending@boxcinema.com";
                String activeOldEmail = EMAIL_PREFIX + "activeold@boxcinema.com";

                User abandoned = new User("Old Pending", abandonedEmail, "0912345011",
                                passwordEncoder.encode("Strong@2026!"), "CUSTOMER");
                abandoned.setAccountStatus("PENDING_VERIFICATION");
                abandoned = userRepository.save(abandoned);

                User busyPending = new User("Busy Pending", busyPendingEmail, "0912345012",
                                passwordEncoder.encode("Strong@2026!"), "CUSTOMER");
                busyPending.setAccountStatus("PENDING_VERIFICATION");
                busyPending = userRepository.save(busyPending);

                User activeOld = new User("Old Active", activeOldEmail, "0912345013",
                                passwordEncoder.encode("Strong@2026!"), "CUSTOMER");
                activeOld.setAccountStatus("ACTIVE");
                activeOld = userRepository.save(activeOld);

                // Backdate created_at 48 hours ago in MySQL
                Timestamp oldTs = Timestamp.valueOf(LocalDateTime.now().minusHours(48));
                jdbcTemplate.update("UPDATE USERS SET created_at = ? WHERE user_id IN (?, ?, ?)",
                                oldTs, abandoned.getUserId(), busyPending.getUserId(), activeOld.getUserId());

                // Attach a business INQUIRIES row to busyPending
                jdbcTemplate.update(
                                "INSERT INTO INQUIRIES (user_id, full_name, email, inquiry_topic, message, inquiry_status) VALUES (?, ?, ?, ?, ?, ?)",
                                busyPending.getUserId(), "Busy Pending", busyPendingEmail, "Support", "Help me",
                                "PENDING");

                int deleted = pendingAccountCleanupService
                                .cleanupExpiredPendingAccounts(LocalDateTime.now().minusHours(24));
                assertTrue(deleted >= 1);

                assertTrue(userRepository.findById(abandoned.getUserId()).isEmpty(),
                                "Abandoned unverified user must be deleted");
                assertTrue(userRepository.findById(busyPending.getUserId()).isPresent(),
                                "Pending user with business record must NOT be deleted");
                assertTrue(userRepository.findById(activeOld.getUserId()).isPresent(),
                                "Active user must NEVER be deleted");
        }

    @Test
    void testPasswordPolicyAndIpRateLimit() {
        assertThrows(IllegalArgumentException.class, () -> passwordPolicyService.validatePasswordStrength("      "));
        IllegalArgumentException tooShort = assertThrows(IllegalArgumentException.class,
                () -> passwordPolicyService.validatePasswordStrength("Abc@1"));
        assertTrue(tooShort.getMessage().contains("at least 6 characters"));
        IllegalArgumentException noUpper = assertThrows(IllegalArgumentException.class,
                () -> passwordPolicyService.validatePasswordStrength("box@6a"));
        assertTrue(noUpper.getMessage().contains("1 uppercase letter, 1 number, and 1 special character"));
        assertThrows(IllegalArgumentException.class, () -> passwordPolicyService.validatePasswordStrength("Box@aa"));
        assertThrows(IllegalArgumentException.class, () -> passwordPolicyService.validatePasswordStrength("Box123"));
        assertThrows(IllegalArgumentException.class, () -> passwordPolicyService.validatePasswordStrength("UniquePass_1".repeat(11)));
        assertDoesNotThrow(() -> passwordPolicyService.validatePasswordStrength("Box@6a"));
        assertDoesNotThrow(() -> passwordPolicyService.validatePasswordStrength("Pass@1"));
        assertDoesNotThrow(() -> passwordPolicyService.validatePasswordStrength("CinemaSafePassphrase@2026!"));
        assertDoesNotThrow(() -> passwordPolicyService.validatePasswordStrength("Café_München_Tokyo_🎉_Cinema_2026_Secret_" + "é".repeat(20)));

                assertEquals("+84901234567", passwordPolicyService.validateAndNormalizePhone(" +84 901-234-567 "));
                assertThrows(IllegalArgumentException.class,
                                () -> passwordPolicyService.validateAndNormalizePhone("12345"));

                for (int i = 0; i < 3; i++) {
                        authRateLimitService.checkBucket("TEST_IP", "10.0.0.1", 3, 60);
                }
                assertThrows(
                                AuthRateLimitService.RateLimitExceededException.class,
                                () -> authRateLimitService.checkBucket("TEST_IP", "10.0.0.1", 3, 60));
        }

        @Test
        void testConcurrentGoogleLinkAndRegisterRespectsDbUniqueness() throws Exception {
                String targetEmail = EMAIL_PREFIX + "race_link@gmail.com";
                User target = new User("Race Link User", targetEmail, "0912345091",
                                passwordEncoder.encode("RacePassphrase12"), "CUSTOMER");
                target.setAccountStatus("ACTIVE");
                target = userRepository.saveAndFlush(target);
                final Long targetUserId = target.getUserId();

                ExecutorService pool = Executors.newFixedThreadPool(2);
                try {
                        CountDownLatch readyA = new CountDownLatch(2);
                        CountDownLatch startA = new CountDownLatch(1);
                        AtomicInteger linkSuccesses = new AtomicInteger();
                        AtomicInteger linkFailures = new AtomicInteger();

                        List<Future<?>> futuresA = new ArrayList<>();
                        for (String subCandidate : List.of("race_sub_alpha", "race_sub_beta")) {
                                futuresA.add(pool.submit(() -> {
                                        readyA.countDown();
                                        startA.await();
                                        try {
                                                userService.linkGoogleAccount(targetUserId, subCandidate, targetEmail);
                                                linkSuccesses.incrementAndGet();
                                        } catch (Exception ex) {
                                                linkFailures.incrementAndGet();
                                        }
                                        return null;
                                }));
                        }
                        readyA.await();
                        startA.countDown();
                        for (Future<?> f : futuresA) {
                                f.get();
                        }

                        assertEquals(1, linkSuccesses.get(),
                                        "Exactly one concurrent link attempt must win on same user");
                        assertEquals(1, linkFailures.get(),
                                        "Second concurrent link with another sub must fail cleanly");
                        String finalGoogleId = userRepository.findById(targetUserId).orElseThrow().getGoogleId();
                        assertTrue("race_sub_alpha".equals(finalGoogleId) || "race_sub_beta".equals(finalGoogleId));

                        // Race B: two different accounts attempt to register concurrently with the
                        // EXACT same Google sub
                        String sharedSub = "race_shared_sub_unique";
                        CountDownLatch readyB = new CountDownLatch(2);
                        CountDownLatch startB = new CountDownLatch(1);
                        AtomicInteger regSuccesses = new AtomicInteger();
                        AtomicInteger regFailures = new AtomicInteger();

                        List<Future<?>> futuresB = new ArrayList<>();
                        for (int i = 0; i < 2; i++) {
                                final String email = EMAIL_PREFIX + "race_reg_" + i + "@gmail.com";
                                final String phone = "091234509" + (2 + i);
                                futuresB.add(pool.submit(() -> {
                                        readyB.countDown();
                                        startB.await();
                                        try {
                                                userService.registerGoogleCustomer(sharedSub, email, "Race Member",
                                                                phone);
                                                regSuccesses.incrementAndGet();
                                        } catch (Exception ex) {
                                                regFailures.incrementAndGet();
                                        }
                                        return null;
                                }));
                        }
                        readyB.await();
                        startB.countDown();
                        for (Future<?> f : futuresB) {
                                f.get();
                        }

                        assertEquals(1, regSuccesses.get(), "Exactly one Google identity owner must be persisted");
                        assertEquals(1, regFailures.get(),
                                        "Duplicate Google sub under race must be rejected by service/DB unique constraint");
                        assertTrue(userRepository.findByGoogleId(sharedSub).isPresent());
                } finally {
                        pool.shutdownNow();
                }
        }
}
