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
import com.boxcinema.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
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
 * Full Specification V1.2 Section Q end-to-end MySQL + MockMvc security
 * verification suite.
 */
@SpringBootTest(properties = "app.security.login.progressive-delay-enabled=false")
@AutoConfigureMockMvc
public class V12SecurityIntegrationTest {

        private static final String PREFIX = "v12_sec_it_";

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private UserService userService;

        @Autowired
        private EmailVerificationService emailVerificationService;

        @Autowired
        private OtpService otpService;

        @Autowired
        private UserRepository userRepository;

        @Autowired
        private PasswordResetRepository passwordResetRepository;

        @Autowired
        private PasswordEncoder passwordEncoder;

        @Autowired
        private LoginAttemptService loginAttemptService;

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
                reset(googleOidcService);
                when(emailService.sendVerificationOtpEmail(anyString(), anyString(), anyString())).thenReturn(true);
                when(emailService.sendPasswordResetOtpEmail(anyString(), anyString(), anyString())).thenReturn(true);
                when(emailService.sendPasswordResetSuccessEmail(anyString(), anyString())).thenReturn(true);
                authRateLimitService.clearAll();
                cleanUpSyntheticData();
        }

        @AfterEach
        void tearDown() {
                authRateLimitService.clearAll();
                cleanUpSyntheticData();
        }

        private void cleanUpSyntheticData() {
                List<Long> ids = jdbcTemplate.queryForList(
                                "SELECT user_id FROM USERS WHERE email LIKE ?",
                                Long.class,
                                PREFIX + "%");
                for (Long id : ids) {
                        jdbcTemplate.update("DELETE FROM INQUIRIES WHERE user_id = ?", id);
                        passwordResetRepository.deleteByUserId(id);
                        userRepository.deleteById(id);
                }
        }

        @Test
        void testRegister202AntiEnumerationVerifyAutoLoginSessionRotationAndP1OtpResendPreservesAttempts()
                        throws Exception {
                String existingEmail = PREFIX + "active_enum@gmail.com";
                User existing = new User("Existing User", existingEmail, "0912346001",
                                passwordEncoder.encode("Cinema_Unique_Passphrase_15"), "CUSTOMER");
                existing.setAccountStatus("ACTIVE");
                userRepository.saveAndFlush(existing);

                String brandNewEmail = PREFIX + "fresh_reg@gmail.com";

                String existingPayload = """
                                {
                                  "fullName": "Any Name",
                                  "email": "%s",
                                  "phone": "0912346002",
                                  "password": "Cinema_Unique_Passphrase_15",
                                  "confirmPassword": "Cinema_Unique_Passphrase_15",
                                  "termsAccepted": true
                                }
                                """.formatted(existingEmail);

                String newPayload = """
                                {
                                  "fullName": "Fresh User",
                                  "email": "%s",
                                  "phone": "0912346003",
                                  "password": "Cinema_Unique_Passphrase_15",
                                  "confirmPassword": "Cinema_Unique_Passphrase_15",
                                  "termsAccepted": true
                                }
                                """.formatted(brandNewEmail);

                // Missing CSRF rejected with 403
                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(newPayload))
                                .andExpect(status().isForbidden());

                MvcResult resExisting = mockMvc.perform(post("/api/auth/register")
                                .with(csrf())
                                .with(req -> {
                                        req.setRemoteAddr("198.51.100.31");
                                        return req;
                                })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(existingPayload))
                                .andExpect(status().isAccepted())
                                .andReturn();

                MvcResult resNew = mockMvc.perform(post("/api/auth/register")
                                .with(csrf())
                                .with(req -> {
                                        req.setRemoteAddr("198.51.100.32");
                                        return req;
                                })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(newPayload))
                                .andExpect(status().isAccepted())
                                .andReturn();

                JsonNode existingJson = objectMapper.readTree(resExisting.getResponse().getContentAsString());
                JsonNode newJson = objectMapper.readTree(resNew.getResponse().getContentAsString());
                assertEquals(existingJson.get("message").asText(), newJson.get("message").asText());
                assertEquals(existingJson.get("success").asBoolean(), newJson.get("success").asBoolean());

                User pendingUser = userRepository.findByEmail(brandNewEmail).orElseThrow();
                assertEquals("PENDING", pendingUser.getAccountStatus());

                // P1 Bug regression check: 4 wrong OTP attempts -> Resend OTP -> failedAttempts
                // MUST stay 4 -> 5th fails & locks!
                for (int i = 0; i < 4; i++) {
                        assertThrows(IllegalArgumentException.class,
                                        () -> emailVerificationService.verifyRegistrationOtp(brandNewEmail, null,
                                                        "000000"));
                }
                PasswordReset prBeforeResend = passwordResetRepository
                                .findByUserIdAndPurpose(pendingUser.getUserId(), OtpPurpose.EMAIL_VERIFICATION)
                                .orElseThrow();
                assertEquals(4, prBeforeResend.getFailedAttempts());
                prBeforeResend.setLastSentAt(java.time.LocalDateTime.now().minusSeconds(65));
                passwordResetRepository.saveAndFlush(prBeforeResend);

                emailVerificationService.resendVerificationOtp(brandNewEmail, prBeforeResend.getChallengeId());
                PasswordReset prAfterResend = passwordResetRepository
                                .findByUserIdAndPurpose(pendingUser.getUserId(), OtpPurpose.EMAIL_VERIFICATION)
                                .orElseThrow();
                assertEquals(4, prAfterResend.getFailedAttempts(), "Resend must NEVER reset failedAttempts");

                assertThrows(IllegalArgumentException.class,
                                () -> emailVerificationService.verifyRegistrationOtp(brandNewEmail, null, "000000"));
                PasswordReset lockedChallenge = passwordResetRepository
                                .findByUserIdAndPurpose(pendingUser.getUserId(), OtpPurpose.EMAIL_VERIFICATION)
                                .orElseThrow();
                assertEquals(PasswordResetStatus.LOCKED, lockedChallenge.getStatus());

                // Set fresh sent challenge to test POST /api/auth/register/verify auto-login +
                // session rotation
                lockedChallenge.setStatus(PasswordResetStatus.SENT);
                lockedChallenge.setFailedAttempts(0);
                lockedChallenge.setLockedUntil(null);
                lockedChallenge.setOtpHash(otpService.computeOtpHmac(
                                pendingUser.getUserId(), OtpPurpose.EMAIL_VERIFICATION,
                                lockedChallenge.getChallengeId(), "654987"));
                passwordResetRepository.saveAndFlush(lockedChallenge);

                MockHttpSession preVerifySession = new MockHttpSession();
                String oldSessionId = preVerifySession.getId();
                MvcResult verifyRes = mockMvc.perform(post("/api/auth/register/verify")
                                .session(preVerifySession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + brandNewEmail + "\",\"otp\":\"654987\"}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.success").value(true))
                                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                                .andExpect(authenticated().withUsername(brandNewEmail))
                                .andReturn();

                HttpSession rotatedSession = verifyRes.getRequest().getSession(false);
                assertNotNull(rotatedSession);
                assertNotEquals(oldSessionId, rotatedSession.getId(), "Verify auto-login must rotate session ID");
        }

        @Test
        void testSuppressGoogleOnlyDisclosureAndIsolatedLinkFlowAndIntegrity() throws Exception {
                // Account A: Email + Argon2id Password, google_id = NULL, business inquiry row
                // attached
                String emailA = PREFIX + "account_a@gmail.com";
                User accountA = new User("Account A", emailA, "0912346010",
                                passwordEncoder.encode("AccountA_Passphrase_2026!"), "CUSTOMER");
                accountA.setAccountStatus("ACTIVE");
                accountA = userRepository.saveAndFlush(accountA);
                final Long idA = accountA.getUserId();

                jdbcTemplate.update(
                                "INSERT INTO INQUIRIES (user_id, full_name, email, inquiry_topic, message, inquiry_status) VALUES (?, ?, ?, ?, ?, ?)",
                                idA, "Account A", emailA, "General", "Preserve me during link", "PENDING");

                // Account B: Google-only user with google_id = "v12_sub_b", passwordHash = NULL
                String emailB = PREFIX + "account_b@gmail.com";
                User accountB = new User("Account B", emailB, "0912346011", null, "CUSTOMER");
                accountB.setGoogleId("v12_sub_b");
                accountB.setAccountStatus("ACTIVE");
                accountB = userRepository.saveAndFlush(accountB);
                final Long idB = accountB.getUserId();

                try {
                        // 1. Suppress Google-only enumeration on unauthenticated /api/auth/login ->
                        // identical generic 401
                        MvcResult googleOnlyFail = mockMvc.perform(post("/api/auth/login")
                                        .with(csrf())
                                        .with(req -> {
                                                req.setRemoteAddr("198.51.100.41");
                                                return req;
                                        })
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"email\":\"" + emailB + "\",\"password\":\"SomeAttempt12345!\"}"))
                                        .andExpect(status().isUnauthorized())
                                        .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"))
                                        .andReturn();

                        MvcResult nonexistentFail = mockMvc.perform(post("/api/auth/login")
                                        .with(csrf())
                                        .with(req -> {
                                                req.setRemoteAddr("198.51.100.42");
                                                return req;
                                        })
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"email\":\"" + PREFIX
                                                        + "missing@gmail.com\",\"password\":\"SomeAttempt12345!\"}"))
                                        .andExpect(status().isUnauthorized())
                                        .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"))
                                        .andReturn();

                        assertEquals(
                                        objectMapper.readTree(nonexistentFail.getResponse().getContentAsString())
                                                        .get("message").asText(),
                                        objectMapper.readTree(googleOnlyFail.getResponse().getContentAsString())
                                                        .get("message").asText());

                        // 2. Log into Account A cleanly
                        MvcResult loginResA = mockMvc.perform(post("/api/auth/login")
                                        .with(csrf())
                                        .with(req -> {
                                                req.setRemoteAddr("198.51.100.43");
                                                return req;
                                        })
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"email\":\"" + emailA
                                                        + "\",\"password\":\"AccountA_Passphrase_2026!\"}"))
                                        .andExpect(status().isOk())
                                        .andReturn();
                        MockHttpSession sessionA = (MockHttpSession) loginResA.getRequest().getSession(false);

                        // 3. Reauth -> Start isolated link -> Reject email mismatch & unverified email
                        // & conflict sub B
                        doReturn(true).when(googleOidcService).isConfigured();
                        mockMvc.perform(post("/api/account/google-link/reauth")
                                        .session(sessionA)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"password\":\"AccountA_Passphrase_2026!\"}"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.linkStartUrl").value("/oauth2/link/google/start"));

                        mockMvc.perform(get("/oauth2/link/google/start").session(sessionA))
                                        .andExpect(status().is3xxRedirection());
                        String stateMismatch = (String) sessionA.getAttribute("BOX_LINK_OAUTH_STATE");
                        String nonceMismatch = (String) sessionA.getAttribute("BOX_LINK_OAUTH_NONCE");
                        String verifierMismatch = (String) sessionA.getAttribute("BOX_LINK_OAUTH_VERIFIER");

            // Reject Google email belonging to Account B (GOOGLE_EMAIL_CONFLICT), even with unused Google sub
                        doReturn(new GoogleOidcService.VerifiedGoogleIdentity("v12_sub_wrong", emailB, "Wrong", true))
                                        .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("code-mm"),
                                                        anyString(), eq(nonceMismatch), eq(verifierMismatch));

            mockMvc.perform(get("/oauth2/link/google/callback")
                            .session(sessionA)
                            .param("code", "code-mm")
                            .param("state", stateMismatch))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/profile?linkStatus=error&linkError=GOOGLE_EMAIL_CONFLICT"))
                    .andExpect(authenticated().withUsername(emailA));
            assertNull(userRepository.findById(idA).orElseThrow().getGoogleId());

                        // Reject Google sub already owned by Account B even if Gmail matches A
                        mockMvc.perform(post("/api/account/google-link/reauth")
                                        .session(sessionA)
                                        .with(csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"password\":\"AccountA_Passphrase_2026!\"}"))
                                        .andExpect(status().isOk());
                        mockMvc.perform(get("/oauth2/link/google/start").session(sessionA))
                                        .andExpect(status().is3xxRedirection());
                        String stateConflict = (String) sessionA.getAttribute("BOX_LINK_OAUTH_STATE");
                        String nonceConflict = (String) sessionA.getAttribute("BOX_LINK_OAUTH_NONCE");
                        String verifierConflict = (String) sessionA.getAttribute("BOX_LINK_OAUTH_VERIFIER");

                        doReturn(new GoogleOidcService.VerifiedGoogleIdentity("v12_sub_b", emailA, "Account A", true))
                                        .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("code-conflict"),
                                                        anyString(), eq(nonceConflict), eq(verifierConflict));

                        mockMvc.perform(get("/oauth2/link/google/callback")
                                        .session(sessionA)
                                        .param("code", "code-conflict")
                                        .param("state", stateConflict))
                                        .andExpect(status().is3xxRedirection())
                                        .andExpect(redirectedUrl(
                                                        "/profile?linkStatus=error&linkError=GOOGLE_IDENTITY_ALREADY_LINKED"));
                        assertNull(userRepository.findById(idA).orElseThrow().getGoogleId());

            // 4. Recent password login (< 5m) allows /api/account/google/link/start -> /oauth2/authorization/google-link -> /login/oauth2/code/google-link
            // with DIFFERENT Gmail (cross-email linking), preserving idA, emailA, role, inquiries, and leaving Account B untouched
            String crossWorkEmailA = "work." + emailA;
            mockMvc.perform(post("/api/account/google/link/start")
                            .session(sessionA)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.authorizationUrl").value("/oauth2/authorization/google-link"));
            MvcResult linkRedirect = mockMvc.perform(get("/oauth2/authorization/google-link").session(sessionA))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();
            assertTrue(linkRedirect.getResponse().getRedirectedUrl().contains("prompt=select_account"));
            assertFalse(linkRedirect.getResponse().getRedirectedUrl().contains("prompt=consent"));
            String stateOk = (String) sessionA.getAttribute("BOX_LINK_OAUTH_STATE");
            String nonceOk = (String) sessionA.getAttribute("BOX_LINK_OAUTH_NONCE");
            String verifierOk = (String) sessionA.getAttribute("BOX_LINK_OAUTH_VERIFIER");

            doReturn(new GoogleOidcService.VerifiedGoogleIdentity("v12_sub_a", crossWorkEmailA, "Account A Work", true))
                    .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("code-ok"), anyString(), eq(nonceOk), eq(verifierOk));

            mockMvc.perform(get("/login/oauth2/code/google-link")
                            .session(sessionA)
                            .param("code", "code-ok")
                            .param("state", stateOk))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/profile?linkStatus=success"))
                    .andExpect(authenticated().withUsername(emailA));

            User linkedA = userRepository.findById(idA).orElseThrow();
            assertEquals("v12_sub_a", linkedA.getGoogleId());
            assertEquals(emailA, linkedA.getEmail(), "Cross-email linking must preserve primary USERS.email");
            assertEquals("CUSTOMER", linkedA.getRole());
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM INQUIRIES WHERE user_id = ?", Integer.class, idA));
            assertEquals("v12_sub_b", userRepository.findById(idB).orElseThrow().getGoogleId());

            mockMvc.perform(get("/api/account/auth-methods").session(sessionA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.authMethods.emailPasswordEnabled").value(true))
                    .andExpect(jsonPath("$.authMethods.googleConnected").value(true));

                        // 5. Google email drift later does NOT overwrite USERS.email
                        UserSummaryDto loggedSocial = userService.authenticateSocial("GOOGLE", "v12_sub_a",
                                        "new_alias@gmail.com", "Alias");
                        assertEquals(idA, loggedSocial.getUserId());
                        assertEquals(emailA, userRepository.findById(idA).orElseThrow().getEmail());

                        // 6. Account B (Google-only) trying Set Password with wrong Google sub
                        // ("v12_sub_a") gets GOOGLE_SUB_MISMATCH
                        UserSummaryDto dtoB = userService.authenticateSocial("GOOGLE", "v12_sub_b", emailB,
                                        "Account B");
                        MockHttpSession sessionB = new MockHttpSession();
                        com.boxcinema.security.SecuritySessionHelper.authenticateUserSession(
                                        dtoB,
                                        new org.springframework.mock.web.MockHttpServletRequest(),
                                        new org.springframework.mock.web.MockHttpServletResponse(),
                                        null,
                                        null);
                        mockMvc.perform(get("/oauth2/set-password/google/start")
                                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                        .user(
                                                                        new com.boxcinema.security.BoxCinemaUserDetails(
                                                                                        dtoB)))
                                        .session(sessionB))
                                        .andExpect(status().is3xxRedirection());
                        String setPwState = (String) sessionB.getAttribute("BOX_SETPW_OAUTH_STATE");
                        String setPwNonce = (String) sessionB.getAttribute("BOX_SETPW_OAUTH_NONCE");
                        String setPwVerifier = (String) sessionB.getAttribute("BOX_SETPW_OAUTH_VERIFIER");

                        doReturn(new GoogleOidcService.VerifiedGoogleIdentity("v12_sub_a", emailA, "Account A", true))
                                        .when(googleOidcService).exchangeCodeAndVerifyIdToken(eq("code-wrong-sub"),
                                                        anyString(), eq(setPwNonce), eq(setPwVerifier));

                        mockMvc.perform(get("/oauth2/set-password/google/callback")
                                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                                        .user(
                                                                        new com.boxcinema.security.BoxCinemaUserDetails(
                                                                                        dtoB)))
                                        .session(sessionB)
                                        .param("code", "code-wrong-sub")
                                        .param("state", setPwState))
                                        .andExpect(status().is3xxRedirection())
                                        .andExpect(redirectedUrl("/profile?setPasswordError=GOOGLE_SUB_MISMATCH"));

            // 7. Account B cannot unlink last auth method (CANNOT_UNLINK_LAST_AUTH_METHOD);
            // Account A can unlink via /api/account/google/unlink & still log in via password
            mockMvc.perform(post("/api/account/google/unlink")
                            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(
                                    new com.boxcinema.security.BoxCinemaUserDetails(dtoB)))
                            .session(sessionB)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.errorCode").value("CANNOT_UNLINK_LAST_AUTH_METHOD"));

            mockMvc.perform(post("/api/account/google/unlink")
                            .session(sessionA)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\":\"AccountA_Passphrase_2026!\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.googleLinked").value(false))
                    .andExpect(jsonPath("$.googleConnected").value(false));
            assertNull(userRepository.findById(idA).orElseThrow().getGoogleId());
            assertEquals(idA, userService.authenticate(emailA, "AccountA_Passphrase_2026!").getUserId());

                        // 8. Verify live production test users 260 and 288 remain untouched & distinct
                        // if present
                        List<Map<String, Object>> protectedUsers = jdbcTemplate.queryForList(
                                        "SELECT user_id, email, role FROM USERS WHERE user_id IN (260, 288) ORDER BY user_id ASC");
                        if (protectedUsers.size() == 2) {
                                assertEquals(260L, ((Number) protectedUsers.get(0).get("user_id")).longValue());
                                assertEquals("dangichdung87@gmail.com", protectedUsers.get(0).get("email"));
                                assertEquals(288L, ((Number) protectedUsers.get(1).get("user_id")).longValue());
                                assertEquals("dangichdung88@gmail.com", protectedUsers.get(1).get("email"));
                        }
                } finally {
                        loginAttemptService.clearAllAttemptsForEmail(emailA);
                        loginAttemptService.clearAllAttemptsForEmail(emailB);
                }
        }
}
