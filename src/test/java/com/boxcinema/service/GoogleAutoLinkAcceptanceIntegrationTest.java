package com.boxcinema.service;

import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.entity.OtpPurpose;
import com.boxcinema.entity.PasswordReset;
import com.boxcinema.entity.PasswordResetStatus;
import com.boxcinema.entity.User;
import com.boxcinema.exception.AuthException;
import com.boxcinema.repository.PasswordResetRepository;
import com.boxcinema.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full acceptance suite covering Google Login Automatic Account Linking Spec Sections I-VIII:
 * - GGL-001..GGL-015
 * - SEC-001..SEC-010
 * - DB-001..DB-008 (real MySQL/InnoDB concurrency & constraints)
 * - SES-001..SES-007 (session fixation rotation, logout, lock & role-change invalidation)
 * - REG-001..REG-011 (OTP purpose isolation & regression preservation)
 */
@SpringBootTest(properties = "app.security.login.progressive-delay-enabled=false")
@AutoConfigureMockMvc
public class GoogleAutoLinkAcceptanceIntegrationTest {

    private static final String PREFIX = "gal_acc_it_";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private PasswordResetService passwordResetService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordResetRepository passwordResetRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SessionManagementService sessionManagementService;

    @Autowired
    private AuthRateLimitService authRateLimitService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private EmailService emailService;

    @SpyBean
    private GoogleOidcService googleOidcService;

    @BeforeEach
    void setUp() {
        authRateLimitService.clearAll();
        cleanupTestRecords();
        reset(googleOidcService, emailService);
        doReturn(true).when(googleOidcService).isConfigured();
        when(emailService.sendVerificationOtpEmail(anyString(), anyString(), anyString())).thenReturn(true);
        when(emailService.sendGoogleAutoLinkOtpEmail(anyString(), anyString(), anyString())).thenReturn(true);
        when(emailService.sendPasswordResetOtpEmail(anyString(), anyString(), anyString())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        authRateLimitService.clearAll();
        cleanupTestRecords();
    }

    private void cleanupTestRecords() {
        jdbcTemplate.update(
                "DELETE pr FROM password_reset pr INNER JOIN users u ON pr.user_id = u.user_id WHERE u.email LIKE ?",
                PREFIX + "%"
        );
        jdbcTemplate.update("DELETE FROM users WHERE email LIKE ?", PREFIX + "%");
    }

    private User createActiveCustomerWithVerifiedEmailOtp(String rawEmail, String phone, String rawPassword) {
        String email = rawEmail.toLowerCase();
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        when(emailService.sendVerificationOtpEmail(eq(email), anyString(), otpCaptor.capture())).thenReturn(true);

        RegisterRequestDto req = new RegisterRequestDto();
        req.setFullName("Test Customer");
        req.setEmail(email);
        req.setPhone(phone);
        req.setPassword(rawPassword);
        req.setConfirmPassword(rawPassword);
        var reg = emailVerificationService.registerWithEmailOtp(req);

        String otp = otpCaptor.getValue();
        assertNotNull(otp);
        emailVerificationService.verifyRegistrationOtp(email, reg.challengeId(), otp);
        return userRepository.findByEmail(email).orElseThrow();
    }

    private MockHttpSession startGoogleLoginFlow(String stateHint) throws Exception {
        MvcResult init = mockMvc.perform(get("/oauth2/authorization/google")
                        .with(r -> {
                            r.setRemoteAddr("10.10.0." + Math.abs(stateHint.hashCode() % 200 + 10));
                            return r;
                        }))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession session = (MockHttpSession) init.getRequest().getSession(false);
        assertNotNull(session);
        assertNotNull(session.getAttribute("BOX_OAUTH_STATE"));
        assertNotNull(session.getAttribute("BOX_OAUTH_NONCE"));
        assertNotNull(session.getAttribute("BOX_OAUTH_CODE_VERIFIER"));
        return session;
    }

    @Test
    void testGgl001Through004_008_AndReg005_AutoLinkAndSubLookupPreserveImmutableData() throws Exception {
        // GGL-001 + GGL-003 + REG: Registered & OTP-verified Gmail customer auto-links on Google login
        String email = PREFIX + "case2@gmail.com";
        User registered = createActiveCustomerWithVerifiedEmailOtp(email, "0981110001", "CinemaPass@2026");
        String originalHash = registered.getPasswordHash();
        long countBefore = userRepository.count();

        // Simulate Forgot Password AFTER activation -> must NOT erase EMAIL_VERIFICATION verified_at (Section VIII.1)
        assertTrue(passwordResetService.requestPasswordReset(email).isSuccess());
        PasswordReset emailVerRow = passwordResetRepository
                .findByUserIdAndPurpose(registered.getUserId(), OtpPurpose.EMAIL_VERIFICATION)
                .orElseThrow();
        assertEquals(PasswordResetStatus.COMPLETED, emailVerRow.getStatus());
        assertNotNull(emailVerRow.getVerifiedAt());

        String subA = PREFIX + "sub_case2_a";
        MockHttpSession session1 = startGoogleLoginFlow("ggl-001");
        doReturn(new GoogleOidcService.VerifiedGoogleIdentity(subA, PREFIX + "Case2@GMAIL.com", "Case2 User", true, null))
                .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("code-ggl-001"), anyString(), anyString(), anyString());

        String preLoginSessionId = session1.getId();
        MvcResult res1 = mockMvc.perform(get("/login/oauth2/code/google")
                        .session(session1)
                        .param("code", "code-ggl-001")
                        .param("state", (String) session1.getAttribute("BOX_OAUTH_STATE")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(email).withRoles("CUSTOMER"))
                .andReturn();

        // SES-001: session fixation protection rotated session id
        assertNotEquals(preLoginSessionId, res1.getRequest().getSession(false).getId());
        assertEquals(countBefore, userRepository.count(), "Auto-link must NOT create a duplicate user");

        User afterLink = userRepository.findById(registered.getUserId()).orElseThrow();
        // DB-006, DB-007, DB-008: immutable core fields
        assertEquals(subA, afterLink.getGoogleId());
        assertEquals(email, afterLink.getEmail());
        assertEquals(originalHash, afterLink.getPasswordHash());
        assertEquals("CUSTOMER", afterLink.getRole());

        // GGL-002 + GGL-008: Subsequent Google Login looks up google_id directly even if Google email alias changed
        UserSummaryDto aliasLogin = userService.authenticateSocial(
                "GOOGLE", subA, PREFIX + "changed.alias@gmail.com", "Alias Changed", true);
        assertEquals(registered.getUserId(), aliasLogin.getUserId());
        assertEquals(email, aliasLogin.getEmail());

        // Email + Password STILL logs into exact same user
        assertEquals(registered.getUserId(), userService.authenticate(email, "CinemaPass@2026").getUserId());

        // GGL-004: Google Workspace account with matching hd claim + verified OTP evidence auto-links
        String wsEmail = PREFIX + "ws@boxcinema.edu.vn";
        User wsUser = new User("Workspace Customer", wsEmail, "0981110002", passwordEncoder.encode("ws_cinema_passphrase_2026"), "CUSTOMER");
        wsUser.setAccountStatus("ACTIVE");
        wsUser = userRepository.saveAndFlush(wsUser);

        PasswordReset wsOtpProof = new PasswordReset(wsUser.getUserId(), OtpPurpose.EMAIL_VERIFICATION, UUID.randomUUID().toString());
        wsOtpProof.setStatus(PasswordResetStatus.COMPLETED);
        wsOtpProof.setVerifiedAt(LocalDateTime.now().minusMinutes(5));
        wsOtpProof.setCompletedAt(LocalDateTime.now().minusMinutes(5));
        passwordResetRepository.saveAndFlush(wsOtpProof);

        String wsSub = PREFIX + "sub_ws_004";
        UserSummaryDto wsResult = userService.authenticateSocial(
                "GOOGLE",
                wsSub,
                wsEmail,
                "Workspace Customer",
                GoogleOidcService.isTrustedGoogleEmailIdentity(wsEmail, "boxcinema.edu.vn")
        );
        assertEquals(wsUser.getUserId(), wsResult.getUserId());
        assertEquals(wsSub, userRepository.findById(wsUser.getUserId()).orElseThrow().getGoogleId());
    }

    @Test
    void testGgl005_MissingOtpEvidencePromptsOneTimeOtpAndLinksWithoutCreatingDuplicate() throws Exception {
        String email = PREFIX + "nootp@gmail.com";
        User legacyCustomer = new User("Legacy Active", email, "0981110003", passwordEncoder.encode("legacy_cinema_pass_2026"), "CUSTOMER");
        legacyCustomer.setAccountStatus("ACTIVE");
        legacyCustomer = userRepository.saveAndFlush(legacyCustomer);
        assertNull(legacyCustomer.getGoogleId());

        ArgumentCaptor<String> linkOtpCaptor = ArgumentCaptor.forClass(String.class);
        when(emailService.sendGoogleAutoLinkOtpEmail(eq(email), anyString(), linkOtpCaptor.capture())).thenReturn(true);

        String sub = PREFIX + "sub_ggl_005";
        MockHttpSession session = startGoogleLoginFlow("ggl-005");
        doReturn(new GoogleOidcService.VerifiedGoogleIdentity(sub, email, "Legacy Active", true))
                .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("code-ggl-005"), anyString(), anyString(), anyString());

        // Step 1: Callback does NOT auto-link; routes to /auth/google/verify-link and dispatches OTP
        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(session)
                        .param("code", "code-ggl-005")
                        .param("state", (String) session.getAttribute("BOX_OAUTH_STATE")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/google/verify-link"))
                .andExpect(unauthenticated());

        assertNull(userRepository.findById(legacyCustomer.getUserId()).orElseThrow().getGoogleId());
        String sentOtp = linkOtpCaptor.getValue();
        assertNotNull(sentOtp);

        // Step 2: Render verify-link page
        mockMvc.perform(get("/auth/google/verify-link").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/verify-link"));

        // Step 3: Submit wrong OTP -> rejected, google_id stays NULL
        mockMvc.perform(post("/auth/google/verify-link/otp")
                        .with(csrf())
                        .session(session)
                        .param("otp", "000000"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/verify-link"))
                .andExpect(unauthenticated());
        assertNull(userRepository.findById(legacyCustomer.getUserId()).orElseThrow().getGoogleId());

        // Step 4: Submit valid 6-digit OTP -> writes COMPLETED + verified_at, links google_id, rotates session, logs in
        String preSessionId = session.getId();
        MvcResult verified = mockMvc.perform(post("/auth/google/verify-link/otp")
                        .with(csrf())
                        .session(session)
                        .param("otp", sentOtp))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(email).withRoles("CUSTOMER"))
                .andReturn();

        assertNotEquals(preSessionId, verified.getRequest().getSession(false).getId());
        assertEquals(sub, userRepository.findById(legacyCustomer.getUserId()).orElseThrow().getGoogleId());

        PasswordReset pr = passwordResetRepository
                .findByUserIdAndPurpose(legacyCustomer.getUserId(), OtpPurpose.EMAIL_VERIFICATION)
                .orElseThrow();
        assertEquals(PasswordResetStatus.COMPLETED, pr.getStatus());
        assertNotNull(pr.getVerifiedAt());
    }

    @Test
    void testGgl006_007_009_010_011_012_GuardsAgainstUnverifiedThirdPartyConflictAndNonActiveAccounts() throws Exception {
        // GGL-007: Third-party domain without matching Workspace hd MUST NOT auto-link even with OTP evidence
        String externalEmail = PREFIX + "thirdparty@outlook.com";
        User externalUser = createActiveCustomerWithVerifiedEmailOtp(externalEmail, "0981110004", "ExtCinema@2026");

        String extSub = PREFIX + "sub_ext_007";
        MockHttpSession extSession = startGoogleLoginFlow("ggl-007");
        doReturn(new GoogleOidcService.VerifiedGoogleIdentity(extSub, externalEmail, "External User", true, null))
                .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("code-ext-007"), anyString(), anyString(), anyString());

        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(extSession)
                        .param("code", "code-ext-007")
                        .param("state", (String) extSession.getAttribute("BOX_OAUTH_STATE")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/google/verify-link"))
                .andExpect(request().sessionAttribute("BOX_PENDING_GOOGLE_MODE", "LINK_CHOICE"))
                .andExpect(unauthenticated());
        assertNull(userRepository.findById(externalUser.getUserId()).orElseThrow().getGoogleId());

        // GGL-009: Account already linked to subA rejects subB even with same email
        externalUser.setGoogleId(PREFIX + "existing_sub_a");
        userRepository.saveAndFlush(externalUser);
        AuthException diffSubEx = assertThrows(AuthException.class,
                () -> userService.authenticateSocial("GOOGLE", PREFIX + "different_sub_b", externalEmail, "Attacker", true));
        assertEquals("USER_ALREADY_HAS_DIFFERENT_GOOGLE", diffSubEx.getErrorCode());

        // GGL-010: PENDING account cannot auto-link or login
        String pendingEmail = PREFIX + "pending@gmail.com";
        RegisterRequestDto pReq = new RegisterRequestDto();
        pReq.setFullName("Pending User");
        pReq.setEmail(pendingEmail);
        pReq.setPhone("0981110005");
        pReq.setPassword("PendingPass@2026");
        pReq.setConfirmPassword("PendingPass@2026");
        emailVerificationService.registerWithEmailOtp(pReq);

        AuthException pendingEx = assertThrows(AuthException.class,
                () -> userService.authenticateSocial("GOOGLE", PREFIX + "sub_pending", pendingEmail, "Pending User", true));
        assertEquals("ACCOUNT_PENDING_VERIFICATION", pendingEx.getErrorCode());

        // GGL-011: LOCKED account rejected
        String lockedEmail = PREFIX + "locked@gmail.com";
        User locked = new User("Locked User", lockedEmail, "0981110006", passwordEncoder.encode("locked_cinema_pass_2026"), "CUSTOMER");
        locked.setAccountStatus("LOCKED");
        userRepository.saveAndFlush(locked);
        AuthException lockedEx = assertThrows(AuthException.class,
                () -> userService.authenticateSocial("GOOGLE", PREFIX + "sub_locked", lockedEmail, "Locked", true));
        assertEquals("ACCOUNT_LOCKED", lockedEx.getErrorCode());

        // GGL-012: SUSPENDED account rejected
        String suspendedEmail = PREFIX + "suspended@gmail.com";
        User suspended = new User("Suspended User", suspendedEmail, "0981110007", passwordEncoder.encode("suspend_cinema_pass_2026"), "CUSTOMER");
        suspended.setAccountStatus("SUSPENDED");
        userRepository.saveAndFlush(suspended);
        AuthException suspendedEx = assertThrows(AuthException.class,
                () -> userService.authenticateSocial("GOOGLE", PREFIX + "sub_suspended", suspendedEmail, "Suspended", true));
        assertEquals("ACCOUNT_SUSPENDED", suspendedEx.getErrorCode());

        // GGL-006: Unverified Google email rejected at callback
        MockHttpSession unverifiedSession = startGoogleLoginFlow("ggl-006");
        doReturn(new GoogleOidcService.VerifiedGoogleIdentity(PREFIX + "sub_unv", PREFIX + "unv@gmail.com", "Unv", false))
                .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("code-unv-006"), anyString(), anyString(), anyString());

        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(unverifiedSession)
                        .param("code", "code-unv-006")
                        .param("state", (String) unverifiedSession.getAttribute("BOX_OAUTH_STATE")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=GOOGLE_EMAIL_UNVERIFIED"))
                .andExpect(unauthenticated());
    }

    @Test
    void testSectionViii2_PrivilegedRolesNeverAutoLinkAndRequirePasswordOnly() throws Exception {
        for (String[] roleSpec : new String[][]{
                {"STAFF", "/staff", "0981110008"},
                {"MANAGER", "/manager", "0981110009"},
                {"ADMIN", "/admin", "0981110010"}
        }) {
            String role = roleSpec[0];
            String expectedRedirect = roleSpec[1];
            String phone = roleSpec[2];
            String email = PREFIX + role.toLowerCase() + "@gmail.com";
            String rawPass = "priv_cinema_pass_2026";

            User priv = new User("Privileged " + role, email, phone, passwordEncoder.encode(rawPass), role);
            priv.setAccountStatus("ACTIVE");
            priv = userRepository.saveAndFlush(priv);

            // Even if completed EMAIL_VERIFICATION exists, Section VIII.2 forbids silent auto-link for privileged roles
            PasswordReset otpProof = new PasswordReset(priv.getUserId(), OtpPurpose.EMAIL_VERIFICATION, UUID.randomUUID().toString());
            otpProof.setStatus(PasswordResetStatus.COMPLETED);
            otpProof.setVerifiedAt(LocalDateTime.now().minusMinutes(2));
            otpProof.setCompletedAt(LocalDateTime.now().minusMinutes(2));
            passwordResetRepository.saveAndFlush(otpProof);

            String sub = PREFIX + "sub_" + role.toLowerCase();
            MockHttpSession session = startGoogleLoginFlow("priv-" + role);
            doReturn(new GoogleOidcService.VerifiedGoogleIdentity(sub, email, "Priv User", true))
                    .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("code-" + role), anyString(), anyString(), anyString());

            mockMvc.perform(get("/login/oauth2/code/google")
                            .session(session)
                            .param("code", "code-" + role)
                            .param("state", (String) session.getAttribute("BOX_OAUTH_STATE")))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/auth/google/verify-link"))
                    .andExpect(request().sessionAttribute("BOX_PENDING_GOOGLE_MODE", "PASSWORD_REQUIRED"))
                    .andExpect(unauthenticated());

            assertNull(userRepository.findById(priv.getUserId()).orElseThrow().getGoogleId());

            // Must refuse OTP send or OTP verification for Staff/Manager/Admin
            mockMvc.perform(post("/auth/google/verify-link/send-otp")
                            .with(csrf())
                            .session(session)
                            .header("Accept", "application/json"))
                    .andExpect(status().isForbidden());

            assertThrows(IllegalArgumentException.class,
                    () -> emailVerificationService.requestGoogleLinkEmailOtp(email, false));

            // Wrong password rejected cleanly
            mockMvc.perform(post("/auth/google/verify-link/password")
                            .with(csrf())
                            .session(session)
                            .param("password", "wrong_cinema_pass_2026"))
                    .andExpect(status().isOk())
                    .andExpect(view().name("auth/verify-link"))
                    .andExpect(unauthenticated());
            assertNull(userRepository.findById(priv.getUserId()).orElseThrow().getGoogleId());

            // Correct password links Google sub and establishes role-appropriate session
            mockMvc.perform(post("/auth/google/verify-link/password")
                            .with(csrf())
                            .session(session)
                            .param("password", rawPass))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl(expectedRedirect))
                    .andExpect(authenticated().withUsername(email).withRoles(role));

            User linkedPriv = userRepository.findById(priv.getUserId()).orElseThrow();
            assertEquals(sub, linkedPriv.getGoogleId());
            assertEquals(role, linkedPriv.getRole());

            // Subsequent Google Login via Case 1 signs straight in with DB role
            assertEquals(priv.getUserId(), userService.authenticateSocial("GOOGLE", sub, email, "Priv", true).getUserId());
        }
    }

    @Test
    void testCase3AndCase4_CrossEmailLinkingFromCompleteProfileAndNewUserOnboarding() throws Exception {
        // Case 3: Existing BOX account (primary@gmail.com) logs in with DIFFERENT Google (work@gmail.com)
        String boxEmail = PREFIX + "primary@gmail.com";
        User boxUser = createActiveCustomerWithVerifiedEmailOtp(boxEmail, "0981110011", "Case3Cinema@2026");

        String workEmail = PREFIX + "work@gmail.com";
        String workSub = PREFIX + "sub_case3_work";
        MockHttpSession case3Session = startGoogleLoginFlow("case3");
        doReturn(new GoogleOidcService.VerifiedGoogleIdentity(workSub, workEmail, "Work Identity", true))
                .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("code-case3"), anyString(), anyString(), anyString());

        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(case3Session)
                        .param("code", "code-case3")
                        .param("state", (String) case3Session.getAttribute("BOX_OAUTH_STATE")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/google/complete-profile"))
                .andExpect(unauthenticated());

        long countBefore = userRepository.count();

        // User chooses "Link Existing Account" with BOX Email + Password
        mockMvc.perform(post("/auth/google/complete-profile/link-existing")
                        .with(csrf())
                        .session(case3Session)
                        .param("authMethod", "password")
                        .param("boxEmail", boxEmail)
                        .param("password", "Case3Cinema@2026"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(boxEmail).withRoles("CUSTOMER"));

        assertEquals(countBefore, userRepository.count(), "Case 3 linking must NOT create a second user");
        User linkedCase3 = userRepository.findById(boxUser.getUserId()).orElseThrow();
        assertEquals(workSub, linkedCase3.getGoogleId());
        assertEquals(boxEmail, linkedCase3.getEmail(), "Original primary email must remain preserved");

        // Subsequent Google login with workSub resolves directly to same user via Case 1
        assertEquals(boxUser.getUserId(), userService.authenticateSocial("GOOGLE", workSub, workEmail, "Work", true).getUserId());

        // Case 4: Brand new Google email completes Phone onboarding -> new CUSTOMER with password_hash=NULL
        String brandNewEmail = PREFIX + "brandnew@gmail.com";
        String brandNewSub = PREFIX + "sub_case4_new";
        MockHttpSession case4Session = startGoogleLoginFlow("case4");
        doReturn(new GoogleOidcService.VerifiedGoogleIdentity(brandNewSub, brandNewEmail, "New Member", true))
                .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("code-case4"), anyString(), anyString(), anyString());

        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(case4Session)
                        .param("code", "code-case4")
                        .param("state", (String) case4Session.getAttribute("BOX_OAUTH_STATE")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/google/complete-profile"));

        mockMvc.perform(post("/auth/google/complete-profile")
                        .with(csrf())
                        .session(case4Session)
                        .param("fullName", "New Member")
                        .param("phone", "0981110012"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(brandNewEmail).withRoles("CUSTOMER"));

        User createdCase4 = userRepository.findByGoogleId(brandNewSub).orElseThrow();
        assertEquals(brandNewEmail, createdCase4.getEmail());
        assertNull(createdCase4.getPasswordHash());
        assertEquals("CUSTOMER", createdCase4.getRole());
        assertEquals("ACTIVE", createdCase4.getAccountStatus());
    }

    @Test
    void testSec005Through010_StateCrossSessionExpiryAndReplayGuards() throws Exception {
        // SEC-006 / SEC-010: Cross-session or tampered state rejected without establishing session
        MockHttpSession sessionA = startGoogleLoginFlow("sec-a");
        MockHttpSession sessionB = startGoogleLoginFlow("sec-b");

        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(sessionB)
                        .param("code", "any-code")
                        .param("state", (String) sessionA.getAttribute("BOX_OAUTH_STATE")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attributeExists("errorMessage"))
                .andExpect(unauthenticated());

        // SEC-009: Expired OAuth state transaction rejected
        sessionA.setAttribute("BOX_OAUTH_EXPIRES_AT_MS", System.currentTimeMillis() - 10_000L);
        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(sessionA)
                        .param("code", "any-code")
                        .param("state", (String) sessionA.getAttribute("BOX_OAUTH_STATE")))
                .andExpect(status().is3xxRedirection())
                .andExpect(unauthenticated());

        // SEC-008: Replay of valid callback in same session after state cleared is rejected
        String brandEmail = PREFIX + "replay@gmail.com";
        MockHttpSession replaySession = startGoogleLoginFlow("sec-replay");
        String validState = (String) replaySession.getAttribute("BOX_OAUTH_STATE");
        doReturn(new GoogleOidcService.VerifiedGoogleIdentity(PREFIX + "sub_rep", brandEmail, "Replay User", true))
                .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("code-rep"), anyString(), anyString(), anyString());

        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(replaySession)
                        .param("code", "code-rep")
                        .param("state", validState))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/google/complete-profile"));

        // Replaying the same callback URL now fails OAuth state check
        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(replaySession)
                        .param("code", "code-rep")
                        .param("state", validState))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"))
                .andExpect(flash().attributeExists("errorMessage"));

        // SEC-009: Expired pending Google verification session rejected cleanly on /auth/google/verify-link
        replaySession.setAttribute("BOX_PENDING_GOOGLE_EXPIRES_AT_MS", System.currentTimeMillis() - 1_000L);
        mockMvc.perform(get("/auth/google/verify-link").session(replaySession))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }

    @Test
    void testDb001Through005_RealMysqlConcurrencyAndUniqueGoogleIdConstraints() throws Exception {
        // DB-001: 3 concurrent Case 2 auto-link requests for SAME user & SAME sub -> idempotent, zero 500s
        String email1 = PREFIX + "conc1@gmail.com";
        User user1 = createActiveCustomerWithVerifiedEmailOtp(email1, "0981110020", "ConcCinema@2026");
        String sub1 = PREFIX + "sub_conc_1";

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch ready1 = new CountDownLatch(3);
            CountDownLatch go1 = new CountDownLatch(1);
            List<Future<Long>> f1 = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                f1.add(pool.submit(() -> {
                    ready1.countDown();
                    assertTrue(go1.await(10, TimeUnit.SECONDS));
                    return userService.authenticateSocial("GOOGLE", sub1, email1, "Conc User 1", true).getUserId();
                }));
            }
            assertTrue(ready1.await(10, TimeUnit.SECONDS));
            go1.countDown();
            for (Future<Long> future : f1) {
                assertEquals(user1.getUserId(), future.get(15, TimeUnit.SECONDS));
            }
            assertEquals(sub1, userRepository.findById(user1.getUserId()).orElseThrow().getGoogleId());

            // DB-002: 2 concurrent requests trying to link SAME Google sub into TWO different users -> strictly 1 wins
            String emailA = PREFIX + "conc_a@gmail.com";
            String emailB = PREFIX + "conc_b@gmail.com";
            createActiveCustomerWithVerifiedEmailOtp(emailA, "0981110021", "ConcA@2026");
            createActiveCustomerWithVerifiedEmailOtp(emailB, "0981110022", "ConcB@2026");
            String contestedSub = PREFIX + "sub_contested";

            CountDownLatch ready2 = new CountDownLatch(2);
            CountDownLatch go2 = new CountDownLatch(1);
            List<Throwable> errors2 = Collections.synchronizedList(new ArrayList<>());
            List<Long> winners2 = Collections.synchronizedList(new ArrayList<>());

            for (String[] pair : new String[][]{{emailA, "ConcA@2026"}, {emailB, "ConcB@2026"}}) {
                pool.submit(() -> {
                    ready2.countDown();
                    try {
                        assertTrue(go2.await(10, TimeUnit.SECONDS));
                        UserSummaryDto u = userService.verifyPasswordAndLinkPendingGoogle(contestedSub, pair[0], pair[1]);
                        winners2.add(u.getUserId());
                    } catch (Throwable t) {
                        errors2.add(t);
                    }
                });
            }
            assertTrue(ready2.await(10, TimeUnit.SECONDS));
            go2.countDown();
            long deadline = System.currentTimeMillis() + 15_000L;
            while (winners2.size() + errors2.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
            assertEquals(1, winners2.size(), "Strictly one user may own contested google_sub");
            assertEquals(1, errors2.size(), "Second user must be rejected cleanly");
            assertTrue(errors2.get(0) instanceof AuthException);
            assertEquals("GOOGLE_IDENTITY_ALREADY_LINKED", ((AuthException) errors2.get(0)).getErrorCode());

            // DB-003: 2 concurrent attempts to link TWO DIFFERENT subs into ONE user -> strictly 1 wins
            String emailC = PREFIX + "conc_c@gmail.com";
            User userC = createActiveCustomerWithVerifiedEmailOtp(emailC, "0981110023", "ConcC@2026");
            CountDownLatch ready3 = new CountDownLatch(2);
            CountDownLatch go3 = new CountDownLatch(1);
            List<Long> winners3 = Collections.synchronizedList(new ArrayList<>());
            List<Throwable> errors3 = Collections.synchronizedList(new ArrayList<>());

            for (String candidateSub : new String[]{PREFIX + "sub_c_1", PREFIX + "sub_c_2"}) {
                pool.submit(() -> {
                    ready3.countDown();
                    try {
                        assertTrue(go3.await(10, TimeUnit.SECONDS));
                        UserSummaryDto u = userService.verifyPasswordAndLinkPendingGoogle(candidateSub, emailC, "ConcC@2026");
                        winners3.add(u.getUserId());
                    } catch (Throwable t) {
                        errors3.add(t);
                    }
                });
            }
            assertTrue(ready3.await(10, TimeUnit.SECONDS));
            go3.countDown();
            deadline = System.currentTimeMillis() + 15_000L;
            while (winners3.size() + errors3.size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
            assertEquals(1, winners3.size(), "Only one Google sub may link to the same user");
            assertEquals(1, errors3.size(), "Second different sub must fail with conflict");
            String finalSubC = userRepository.findById(userC.getUserId()).orElseThrow().getGoogleId();
            assertNotNull(finalSubC);

            // DB-005: Direct MySQL UNIQUE index uq_users_google_id rejects duplicate insert
            User dupSubUser = new User("Dup Sub", PREFIX + "dupsub@gmail.com", "0981110024", passwordEncoder.encode("dup_cinema_pass_2026"), "CUSTOMER");
            dupSubUser.setAccountStatus("ACTIVE");
            dupSubUser.setGoogleId(sub1);
            assertThrows(DataIntegrityViolationException.class, () -> userRepository.saveAndFlush(dupSubUser));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void testSes007_RoleChangeInDatabaseImmediatelyInvalidatesActiveSession() throws Exception {
        String email = PREFIX + "rolechange@gmail.com";
        User user = createActiveCustomerWithVerifiedEmailOtp(email, "0981110030", "RoleCinema@2026");
        String sub = PREFIX + "sub_role_007";

        MockHttpSession session = startGoogleLoginFlow("ses-007");
        doReturn(new GoogleOidcService.VerifiedGoogleIdentity(sub, email, "Role User", true))
                .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("code-ses-007"), anyString(), anyString(), anyString());

        MvcResult loginResult = mockMvc.perform(get("/login/oauth2/code/google")
                        .session(session)
                        .param("code", "code-ses-007")
                        .param("state", (String) session.getAttribute("BOX_OAUTH_STATE")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andReturn();

        HttpSession activeSession = loginResult.getRequest().getSession(false);
        assertNotNull(activeSession);

        // Session works while DB role is still CUSTOMER
        mockMvc.perform(get("/api/users/me/auth-methods").session((MockHttpSession) activeSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("CUSTOMER"));

        // Admin promotes/changes user role in DB from CUSTOMER -> STAFF while session exists
        user.setRole("STAFF");
        userRepository.saveAndFlush(user);

        // Next API request rejects immediately with 401 and invalidates session
        mockMvc.perform(get("/api/users/me/auth-methods").session((MockHttpSession) activeSession))
                .andExpect(status().isUnauthorized());

        assertFalse(sessionManagementService.isSessionValid(user.getUserId(), activeSession));
    }
}
