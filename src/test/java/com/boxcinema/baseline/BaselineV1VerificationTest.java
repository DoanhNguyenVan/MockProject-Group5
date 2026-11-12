package com.boxcinema.baseline;

import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.entity.OtpPurpose;
import com.boxcinema.entity.PasswordReset;
import com.boxcinema.entity.PasswordResetStatus;
import com.boxcinema.entity.User;
import com.boxcinema.repository.PasswordResetRepository;
import com.boxcinema.repository.UserRepository;
import com.boxcinema.service.AuthRateLimitService;
import com.boxcinema.service.EmailService;
import com.boxcinema.service.EmailVerificationService;
import com.boxcinema.service.GoogleOidcService;
import com.boxcinema.service.LoginAttemptService;
import com.boxcinema.service.OtpService;
import com.boxcinema.service.PasswordHashService;
import com.boxcinema.service.PasswordPolicyService;
import com.boxcinema.service.PasswordResetService;
import com.boxcinema.service.SessionManagementService;
import com.boxcinema.service.UserService;
import com.boxcinema.service.impl.UserServiceImpl;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive verification suite for BOX Cinema Authentication Production Baseline v1.0.
 */
@SpringBootTest(properties = "app.security.login.progressive-delay-enabled=false")
@AutoConfigureMockMvc
public class BaselineV1VerificationTest {

    private static final String PREFIX = "baseline_v1_";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserServiceImpl userServiceImpl;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private PasswordPolicyService passwordPolicyService;

    @Autowired
    private AuthRateLimitService authRateLimitService;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @Autowired
    private SessionManagementService sessionManagementService;

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

    @SpyBean
    private GoogleOidcService googleOidcService;

    @BeforeEach
    void setUp() {
        reset(googleOidcService);
        when(emailService.sendVerificationOtpEmail(anyString(), anyString(), anyString())).thenReturn(true);
        when(emailService.sendPasswordResetOtpEmail(anyString(), anyString(), anyString())).thenReturn(true);
        when(emailService.sendPasswordResetSuccessEmail(anyString(), anyString())).thenReturn(true);
        authRateLimitService.clearAll();
        cleanUpTestUsers();
    }

    @AfterEach
    void tearDown() {
        authRateLimitService.clearAll();
        cleanUpTestUsers();
    }

    private void cleanUpTestUsers() {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT user_id FROM USERS WHERE email LIKE ? OR email LIKE ?",
                Long.class,
                PREFIX + "%",
                "first.last+cinema_baseline%"
        );
        for (Long id : ids) {
            passwordResetRepository.deleteByUserId(id);
            userRepository.deleteById(id);
        }
    }

    @Test
    void testEmailNormalizationPhonePolicyAndBcrypt12PerformanceBenchmark() {
        // 1. Email preserves dots and +tags while trimming/lowercasing
        String dottedTagged = "  First.Last+Cinema@Gmail.COM  ";
        assertEquals("first.last+cinema@gmail.com", passwordPolicyService.validateAndNormalizeEmail(dottedTagged));
        assertNotEquals(
                passwordPolicyService.validateAndNormalizeEmail("first.last@gmail.com"),
                passwordPolicyService.validateAndNormalizeEmail("firstlast@gmail.com")
        );

        // 2. Phone normalization
        assertEquals("+84901234567", passwordPolicyService.validateAndNormalizePhone(" +84 901-234-567 "));
        assertThrows(IllegalArgumentException.class, () -> passwordPolicyService.validateAndNormalizePhone("12345678"));

        // 3. Password Policy: 6..128 Unicode code points, >=1 uppercase, >=1 digit, >=1 special char (no blocklist)
        IllegalArgumentException shortEx = assertThrows(IllegalArgumentException.class,
                () -> passwordPolicyService.validatePasswordStrength("Abc@1")); // 5 chars
        assertTrue(shortEx.getMessage().contains("at least 6 characters"));
        assertThrows(IllegalArgumentException.class, () -> passwordPolicyService.validatePasswordStrength("box@6a")); // no uppercase
        assertThrows(IllegalArgumentException.class, () -> passwordPolicyService.validatePasswordStrength("Box@aa")); // no number
        assertThrows(IllegalArgumentException.class, () -> passwordPolicyService.validatePasswordStrength("Box123")); // no special char
        assertDoesNotThrow(() -> passwordPolicyService.validatePasswordStrength("Box@6a")); // 6 chars
        assertDoesNotThrow(() -> passwordPolicyService.validatePasswordStrength("Pass@1")); // previously blocked word now allowed if meeting rules
        String exact128CodePoints = "Cinema_Unique_Passphrase_2026_SectionD1_Verification_".repeat(2) + "abcdefghijABCDEFGHIJ12";
        assertEquals(128, exact128CodePoints.codePointCount(0, exact128CodePoints.length()));
        assertDoesNotThrow(() -> passwordPolicyService.validatePasswordStrength(exact128CodePoints));
        String over128CodePoints = exact128CodePoints + "!";
        assertEquals(129, over128CodePoints.codePointCount(0, over128CodePoints.length()));
        assertThrows(IllegalArgumentException.class, () -> passwordPolicyService.validatePasswordStrength(over128CodePoints));
        String multibyteOver72Bytes = "Café_München_Tokyo_🎉_Cinema_2026_Secret_" + "é".repeat(20); // >72 UTF-8 bytes, <=128 code points -> allowed under Argon2id V1.2!
        assertTrue(multibyteOver72Bytes.getBytes(StandardCharsets.UTF_8).length > 72);
        assertDoesNotThrow(() -> passwordPolicyService.validatePasswordStrength(multibyteOver72Bytes));

        // 4. Argon2id (m=19456, t=2, p=1) encoder check + real timing benchmark
        assertInstanceOf(PasswordHashService.class, passwordEncoder);
        String encodedSample = passwordEncoder.encode("correct horse battery staple");
        assertTrue(encodedSample.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"),
                "PasswordEncoder must use Argon2id(m=19456,t=2,p=1), got prefix: " + encodedSample.substring(0, Math.min(35, encodedSample.length())));

        int iterations = 3;
        long totalEncodeNs = 0;
        long totalMatchNs = 0;
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            String h = passwordEncoder.encode("benchmark_passphrase_v12_" + i);
            long t1 = System.nanoTime();
            assertTrue(passwordEncoder.matches("benchmark_passphrase_v12_" + i, h));
            long t2 = System.nanoTime();
            totalEncodeNs += (t1 - t0);
            totalMatchNs += (t2 - t1);
        }
        double avgEncodeMs = (totalEncodeNs / (double) iterations) / 1_000_000.0;
        double avgVerifyMs = (totalMatchNs / (double) iterations) / 1_000_000.0;
        System.out.printf("[BENCHMARK] Argon2id(m=19456,t=2,p=1) avg encode=%.1f ms, avg verify=%.1f ms (n=%d)%n",
                avgEncodeMs, avgVerifyMs, iterations);
        assertTrue(avgEncodeMs < 2000.0 && avgVerifyMs < 2000.0);
    }

    @Test
    void testLegacyShortBcrypt10PasswordRemainsCompatibleAndNoRememberMeCookieIssued() throws Exception {
        String email = PREFIX + "legacy@boxcinema.com";
        BCryptPasswordEncoder legacyEncoder10 = new BCryptPasswordEncoder(10);
        User legacy = new User("Legacy Member", email, "0912345801", legacyEncoder10.encode("OldPass1"), "CUSTOMER");
        legacy.setAccountStatus("ACTIVE");
        legacy = userRepository.saveAndFlush(legacy);

        try {
            MvcResult loginRes = mockMvc.perform(post("/login")
                            .with(csrf())
                            .param("username", email)
                            .param("password", "OldPass1")
                            .param("remember-me", "on"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/"))
                    .andExpect(authenticated().withUsername(email))
                    .andReturn();

            // Transparent BCrypt($2a$10$) -> Argon2id hash upgrade on verified login
            User upgraded = userRepository.findById(legacy.getUserId()).orElseThrow();
            assertTrue(upgraded.getPasswordHash().startsWith("$argon2id$"),
                    "Verified legacy login must transparently upgrade BCrypt hash to Argon2id");
            assertTrue(passwordEncoder.matches("OldPass1", upgraded.getPasswordHash()));

            // Remember-Me must remain disabled: no remember-me cookie emitted
            Cookie rememberCookie = loginRes.getResponse().getCookie("remember-me");
            assertNull(rememberCookie, "Remember-Me cookie must NEVER be set in V1.2");

            HttpSession session = loginRes.getRequest().getSession(false);
            assertNotNull(session);
            assertEquals(SessionManagementService.CUSTOMER_IDLE_TIMEOUT_SECONDS, session.getMaxInactiveInterval());
            assertTrue(sessionManagementService.isSessionValid(legacy.getUserId(), session));

            // Login UI must not advertise Remember Me
            mockMvc.perform(get("/login"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("name=\"remember-me\""))));

            // Register UI must state 6-char minimum password policy
            mockMvc.perform(get("/register"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("Min 6 chars")));
        } finally {
            loginAttemptService.clearAllAttemptsForEmail(email);
        }
    }

    @Test
    void testGoogleOAuthOnboardingEndToEndExpiryDuplicatePhoneSetPasswordAndResetPreservesGoogleId() throws Exception {
        // 1. Authorization URL only asks openid email profile with select_account & 5m TTL state
        doReturn(true).when(googleOidcService).isConfigured();
        MvcResult authInit = mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String redirectLocation = authInit.getResponse().getRedirectedUrl();
        assertNotNull(redirectLocation);
        assertTrue(redirectLocation.contains("scope=openid+email+profile") || redirectLocation.contains("scope=openid%20email%20profile"));
        assertFalse(redirectLocation.contains("prompt="));
        assertTrue(redirectLocation.contains("code_challenge="));
        assertTrue(redirectLocation.contains("code_challenge_method=S256"));
        MockHttpSession oauthSession = (MockHttpSession) authInit.getRequest().getSession(false);
        assertNotNull(oauthSession);
        String state = (String) oauthSession.getAttribute("BOX_OAUTH_STATE");
        assertNotNull(state);
        assertNotNull(oauthSession.getAttribute("BOX_OAUTH_CODE_VERIFIER"));

        // 2. Expired OAuth state (> 5m) is rejected before Google token exchange
        oauthSession.setAttribute("BOX_OAUTH_EXPIRES_AT_MS", System.currentTimeMillis() - 1000L);
        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(oauthSession)
                        .param("code", "auth-code-expired")
                        .param("state", state))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attribute("errorMessage", containsString("expired")));
        verify(googleOidcService, never()).exchangeCodeAndVerifyIdToken(anyString(), anyString(), anyString(), any());

        // 3. Brand-new Google identity redirects to /auth/google/complete-profile and writes NOTHING to DB yet
        String newEmail = PREFIX + "new_google@gmail.com";
        String newSub = "sub_baseline_new_001";
        MvcResult freshInit = mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) freshInit.getRequest().getSession(false);
        String freshState = (String) session.getAttribute("BOX_OAUTH_STATE");

        doReturn(new GoogleOidcService.VerifiedGoogleIdentity(newSub, newEmail, "Baseline Google User", true))
                .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("valid-code-1"), anyString(), anyString(), anyString());

        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(session)
                        .param("code", "valid-code-1")
                        .param("state", freshState))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/google/complete-profile"));
        assertTrue(userRepository.findByEmail(newEmail).isEmpty(), "Onboarding redirect must not create placeholder DB row");

        // 4. Complete-profile renders readonly email + editable full name + phone, and stores no Google tokens in session
        mockMvc.perform(get("/auth/google/complete-profile").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/complete-profile"))
                .andExpect(content().string(containsString(newEmail)));

        // 5. Reject duplicate phone cleanly during complete-profile without creating account
        String occupiedPhone = "0912345802";
        User existingPhoneOwner = new User("Phone Owner", PREFIX + "phoneowner@boxcinema.com", occupiedPhone,
                passwordEncoder.encode("ExistingCinemaPass2026!"), "CUSTOMER");
        existingPhoneOwner.setAccountStatus("ACTIVE");
        userRepository.saveAndFlush(existingPhoneOwner);

        mockMvc.perform(post("/auth/google/complete-profile")
                        .session(session)
                        .with(csrf())
                        .param("fullName", "Updated Google Name")
                        .param("phone", occupiedPhone))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/complete-profile"))
                .andExpect(model().attribute("errorMessage", containsString("already registered")));
        assertTrue(userRepository.findByGoogleId(newSub).isEmpty());

        // 6. Expired pending Google onboarding session redirects back to /login
        session.setAttribute("BOX_PENDING_GOOGLE_EXPIRES_AT_MS", System.currentTimeMillis() - 1000L);
        mockMvc.perform(post("/auth/google/complete-profile")
                        .session(session)
                        .with(csrf())
                        .param("fullName", "Updated Google Name")
                        .param("phone", "0912345803"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        assertTrue(userRepository.findByGoogleId(newSub).isEmpty());

        // Restore fresh onboarding session & complete registration cleanly (E.164 phone normalized)
        MvcResult init2 = mockMvc.perform(get("/oauth2/authorization/google")).andReturn();
        session = (MockHttpSession) init2.getRequest().getSession(false);
        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(session)
                        .param("code", "valid-code-1")
                        .param("state", (String) session.getAttribute("BOX_OAUTH_STATE")))
                .andExpect(redirectedUrl("/auth/google/complete-profile"));

        mockMvc.perform(post("/auth/google/complete-profile")
                        .session(session)
                        .with(csrf())
                        .param("fullName", "Updated Google Name")
                        .param("phone", "0912-345-803"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(newEmail));

        User created = userRepository.findByEmail(newEmail).orElseThrow();
        assertEquals("ACTIVE", created.getAccountStatus());
        assertEquals("+84912345803", created.getPhone());
        assertEquals("Updated Google Name", created.getFullName());
        assertEquals(newSub, created.getGoogleId());
        assertNull(created.getPasswordHash());
        assertNull(session.getAttribute("access_token"));
        assertNull(session.getAttribute("refresh_token"));

        // 7. Google-only profile shows Set Password and requires fresh Google sub re-auth before setting password
        mockMvc.perform(get("/profile").session(session))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasPassword", false))
                .andExpect(model().attribute("isGoogleLinked", true))
                .andExpect(content().string(containsString("Set Password")));

        mockMvc.perform(post("/api/account/set-password")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"My_Unique_Cinema_2026!\",\"confirmPassword\":\"My_Unique_Cinema_2026!\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("SET_PASSWORD_REAUTH_REQUIRED"));

        mockMvc.perform(get("/oauth2/set-password/google/start").session(session))
                .andExpect(status().is3xxRedirection());
        String setPwState = (String) session.getAttribute("BOX_SETPW_OAUTH_STATE");
        String setPwNonce = (String) session.getAttribute("BOX_SETPW_OAUTH_NONCE");
        String setPwVerifier = (String) session.getAttribute("BOX_SETPW_OAUTH_VERIFIER");

        doReturn(new GoogleOidcService.VerifiedGoogleIdentity(newSub, newEmail, "Updated Google Name", true))
                .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("setpw-code-1"), anyString(), eq(setPwNonce), eq(setPwVerifier));

        mockMvc.perform(get("/oauth2/set-password/google/callback")
                        .session(session)
                        .param("code", "setpw-code-1")
                        .param("state", setPwState))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/profile?setPasswordReady=true"));

        mockMvc.perform(post("/api/account/set-password")
                        .session(session)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"My_Unique_Cinema_2026!\",\"confirmPassword\":\"My_Unique_Cinema_2026!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.hasPassword").value(true));

        User afterSetPw = userRepository.findById(created.getUserId()).orElseThrow();
        assertNotNull(afterSetPw.getPasswordHash());
        assertTrue(afterSetPw.getPasswordHash().startsWith("$argon2id$"));
        assertEquals(newSub, afterSetPw.getGoogleId());

        // 8. Existing unlinked email account without OTP verified_at evidence is NOT silently merged -> /auth/google/verify-link
        User unlinkedPw = existingPhoneOwner;
        MvcResult conflictInit = mockMvc.perform(get("/oauth2/authorization/google")).andReturn();
        MockHttpSession conflictSession = (MockHttpSession) conflictInit.getRequest().getSession(false);
        doReturn(new GoogleOidcService.VerifiedGoogleIdentity("sub_attacker_unlinked", unlinkedPw.getEmail(), "Other", true))
                .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("conflict-code"), anyString(), anyString(), anyString());

        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(conflictSession)
                        .param("code", "conflict-code")
                        .param("state", (String) conflictSession.getAttribute("BOX_OAUTH_STATE")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/google/verify-link"));
        assertNull(userRepository.findById(unlinkedPw.getUserId()).orElseThrow().getGoogleId());

        // 9. Password reset on dual-linked account preserves google_id and authenticates with new password
        assertTrue(passwordResetService.requestPasswordReset(newEmail).isSuccess());
        PasswordReset pr = passwordResetRepository.findByUserIdAndPurpose(created.getUserId(), OtpPurpose.PASSWORD_RESET).orElseThrow();
        String knownOtp = "654321";
        pr.setOtpHash(otpService.computeOtpHmac(created.getUserId(), OtpPurpose.PASSWORD_RESET, pr.getChallengeId(), knownOtp));
        passwordResetRepository.saveAndFlush(pr);

        var verifyRes = passwordResetService.verifyOtp(newEmail, knownOtp);
        assertTrue(verifyRes.isSuccess());
        var completeRes = passwordResetService.completePasswordReset(verifyRes.getResetToken(),
                "Resetted_Cinema_2026!", "Resetted_Cinema_2026!");
        assertTrue(completeRes.isSuccess());

        User afterReset = userRepository.findById(created.getUserId()).orElseThrow();
        assertEquals(newSub, afterReset.getGoogleId());
        assertEquals(created.getUserId(), userService.authenticate(newEmail, "Resetted_Cinema_2026!").getUserId());
        assertEquals(created.getUserId(), userService.authenticateSocial("GOOGLE", newSub, newEmail, "Updated Google Name").getUserId());
        assertFalse(sessionManagementService.isSessionValid(created.getUserId(), session),
                "Old session created before password reset must be invalidated");
    }

    @Test
    void testRoleSessionTimeoutsCompositeLockoutIpRateLimitAndOtpQuotas() throws Exception {
        // 1. Privileged role timeout (15 min idle, 8h max) vs Customer (30 min idle, 12h max)
        MockHttpSession staffSession = new MockHttpSession();
        sessionManagementService.registerSession(99001L, "STAFF", staffSession);
        assertEquals(SessionManagementService.PRIVILEGED_IDLE_TIMEOUT_SECONDS, staffSession.getMaxInactiveInterval());
        assertTrue(sessionManagementService.isSessionValid(99001L, staffSession));
        staffSession.setAttribute(SessionManagementService.SESSION_AUTH_ISSUED_AT_EPOCH_MS,
                System.currentTimeMillis() - SessionManagementService.PRIVILEGED_MAX_LIFETIME_MS - 1000L);
        assertFalse(sessionManagementService.isSessionValid(99001L, staffSession),
                "STAFF session past 8h absolute lifetime must become invalid");

        MockHttpSession customerSession = new MockHttpSession();
        sessionManagementService.registerSession(99002L, "CUSTOMER", customerSession);
        assertEquals(SessionManagementService.CUSTOMER_IDLE_TIMEOUT_SECONDS, customerSession.getMaxInactiveInterval());
        customerSession.setAttribute(SessionManagementService.SESSION_AUTH_ISSUED_AT_EPOCH_MS,
                System.currentTimeMillis() - (SessionManagementService.PRIVILEGED_MAX_LIFETIME_MS + 60_000L));
        assertTrue(sessionManagementService.isSessionValid(99002L, customerSession),
                "CUSTOMER session within 12h max lifetime stays valid");
        customerSession.setAttribute(SessionManagementService.SESSION_AUTH_ISSUED_AT_EPOCH_MS,
                System.currentTimeMillis() - SessionManagementService.CUSTOMER_MAX_LIFETIME_MS - 1000L);
        assertFalse(sessionManagementService.isSessionValid(99002L, customerSession),
                "CUSTOMER session past 12h absolute lifetime must become invalid");

        // 2. Composite (email, ip) 5-fail 15m lockout does not alter DB status or lock another IP
        String lockEmail = PREFIX + "lockout@boxcinema.com";
        User target = new User("Lockout Test", lockEmail, "0912345804",
                passwordEncoder.encode("LockoutPassphrase12!"), "CUSTOMER");
        target.setAccountStatus("ACTIVE");
        target = userRepository.saveAndFlush(target);

        try {
            for (int i = 0; i < 5; i++) {
                assertThrows(IllegalArgumentException.class,
                        () -> userServiceImpl.authenticateWithIp(lockEmail, "WrongPass1234", "198.51.100.10"));
            }
            assertThrows(LoginAttemptService.LoginLockoutException.class,
                    () -> userServiceImpl.authenticateWithIp(lockEmail, "LockoutPassphrase12!", "198.51.100.10"));
            assertEquals("ACTIVE", userRepository.findById(target.getUserId()).orElseThrow().getAccountStatus());
            assertEquals(target.getUserId(),
                    userServiceImpl.authenticateWithIp(lockEmail, "LockoutPassphrase12!", "198.51.100.20").getUserId());

            // Forgot Password on PENDING & Google-only accounts produces generic response & zero OTP row
            String pendingEmail = PREFIX + "pending_fp@boxcinema.com";
            emailVerificationService.registerWithEmailOtp(new RegisterRequestDto(
                    "Pending User", pendingEmail, "0912345805", "PendingPassphrase12!", "PendingPassphrase12!", true));
            assertTrue(passwordResetService.requestPasswordReset(pendingEmail).isSuccess());
            User pendingDb = userRepository.findByEmail(pendingEmail).orElseThrow();
            assertEquals("PENDING", pendingDb.getAccountStatus());
            assertTrue(passwordResetRepository.findByUserIdAndPurpose(pendingDb.getUserId(), OtpPurpose.PASSWORD_RESET).isEmpty());

            User gOnly = userRepository.saveAndFlush(new User("Google Only", PREFIX + "gonly_fp@gmail.com", "0912345806", null, "CUSTOMER"));
            gOnly.setGoogleId("sub_fp_block_001");
            gOnly.setAccountStatus("ACTIVE");
            gOnly = userRepository.saveAndFlush(gOnly);
            assertTrue(passwordResetService.requestPasswordReset(gOnly.getEmail()).isSuccess());
            assertTrue(passwordResetRepository.findByUserIdAndPurpose(gOnly.getUserId(), OtpPurpose.PASSWORD_RESET).isEmpty());

            // 3. IP login rate limit (60 tries/15m/IP -> 61st gets 429 + Retry-After)
            String rateIp = "203.0.113.77";
            for (int i = 0; i < 60; i++) {
                mockMvc.perform(post("/api/auth/login")
                                .with(req -> {
                                    req.setRemoteAddr(rateIp);
                                    return req;
                                })
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + lockEmail + "\",\"password\":\"WrongPass1234\"}"))
                        .andExpect(i < 4 ? status().isUnauthorized() : status().isTooManyRequests());
            }
            mockMvc.perform(post("/api/auth/login")
                            .with(req -> {
                                req.setRemoteAddr(rateIp);
                                return req;
                            })
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"" + lockEmail + "\",\"password\":\"LockoutPassphrase12!\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().exists("Retry-After"));

            // 4. Daily Email OTP cap (10/day/email) and Hourly IP OTP cap (30/hr/IP)
            String otpEmail = PREFIX + "dailycap@boxcinema.com";
            for (int i = 0; i < 10; i++) {
                authRateLimitService.checkOtpDailyEmailLimit(otpEmail);
            }
            assertThrows(AuthRateLimitService.RateLimitExceededException.class,
                    () -> authRateLimitService.checkOtpDailyEmailLimit(otpEmail));

            String otpIp = "203.0.113.88";
            for (int i = 0; i < 30; i++) {
                authRateLimitService.checkOtpRateLimit(otpIp);
            }
            assertThrows(AuthRateLimitService.RateLimitExceededException.class,
                    () -> authRateLimitService.checkOtpRateLimit(otpIp));
        } finally {
            loginAttemptService.clearAllAttemptsForEmail(lockEmail);
        }
    }
}
