package com.boxcinema.controller;

import com.boxcinema.config.SecurityConfig;
import com.boxcinema.dto.AuthMethodsDto;
import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.entity.User;
import com.boxcinema.repository.PasswordResetRepository;
import com.boxcinema.repository.UserRepository;
import com.boxcinema.security.BoxCinemaUserDetails;
import com.boxcinema.service.EmailVerificationService;
import com.boxcinema.service.GoogleOidcService;
import com.boxcinema.service.OtpChallengeTxHelper;
import com.boxcinema.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
public class AuthControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @MockBean
        private UserService userService;

        @MockBean
        private EmailVerificationService emailVerificationService;

        @MockBean
        private GoogleOidcService googleOidcService;

        @MockBean
        private UserRepository userRepository;

        @MockBean
        private PasswordResetRepository passwordResetRepository;

        @BeforeEach
        void setUpActiveUserRepoMock() {
                User active = new User("Alex Nguyen", "alex@boxcinema.com", "0901234567", "$2a$10$hash", "CUSTOMER");
                active.setUserId(1L);
                active.setAccountStatus("ACTIVE");
                when(userRepository.findById(1L)).thenReturn(Optional.of(active));
        }

        @Test
        void testLoginPageRenders() throws Exception {
                when(googleOidcService.isConfigured()).thenReturn(true);
                mockMvc.perform(get("/login"))
                                .andExpect(status().isOk())
                                .andExpect(view().name("auth/login"))
                                .andExpect(model().attribute("activePage", "login"))
                                .andExpect(model().attribute("googleOAuthConfigured", true));
        }

        @Test
        void testProcessLoginSuccessViaSpringSecurity() throws Exception {
                UserSummaryDto userDto = new UserSummaryDto(1L, "Alex Nguyen", "alex@boxcinema.com", "0901234567",
                                "CUSTOMER");
                when(userService.authenticate("alex@boxcinema.com", "StrongPass@123")).thenReturn(userDto);

                mockMvc.perform(post("/login")
                                .with(csrf())
                                .param("username", "alex@boxcinema.com")
                                .param("password", "StrongPass@123"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/"))
                                .andExpect(authenticated().withUsername("alex@boxcinema.com").withRoles("CUSTOMER"))
                                .andExpect(request().sessionAttribute("currentUser", userDto));
        }

        @Test
        void testProcessLoginStaffRoleRedirectsToStaffDashboard() throws Exception {
                UserSummaryDto staffDto = new UserSummaryDto(10L, "Staff Member", "staff@boxcinema.com", "0902223334",
                                "STAFF");
                when(userService.authenticate("staff@boxcinema.com", "Staff@123!")).thenReturn(staffDto);

                mockMvc.perform(post("/login")
                                .with(csrf())
                                .param("username", "staff@boxcinema.com")
                                .param("password", "Staff@123!"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/staff"))
                                .andExpect(authenticated().withUsername("staff@boxcinema.com").withRoles("STAFF"));
        }

        @Test
        void testProcessLoginPendingEmailRedirectsToVerifyEmailPage() throws Exception {
                when(userService.authenticate("pending@boxcinema.com", "StrongPass@123"))
                                .thenThrow(new IllegalStateException("PENDING_VERIFICATION:pending@boxcinema.com"));

                mockMvc.perform(post("/login")
                                .with(csrf())
                                .param("username", "pending@boxcinema.com")
                                .param("password", "StrongPass@123"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/verify-email?email=pending%40boxcinema.com"))
                                .andExpect(unauthenticated());
        }

        @Test
        void testProcessLoginInvalidCredentialsSetsFlashAndRedirects() throws Exception {
                when(userService.authenticate("alex@boxcinema.com", "wrongpass"))
                                .thenThrow(new IllegalArgumentException("Invalid email or password."));

                MvcResult result = mockMvc.perform(post("/login")
                                .with(csrf())
                                .param("username", "alex@boxcinema.com")
                                .param("password", "wrongpass"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/login?error=true"))
                                .andExpect(unauthenticated())
                                .andReturn();

                MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
                mockMvc.perform(get("/login?error=true").session(session))
                                .andExpect(status().isOk())
                                .andExpect(model().attribute("errorMessage", "Invalid email or password."))
                                .andExpect(model().attribute("enteredEmail", "alex@boxcinema.com"));
        }

        @Test
        void testProcessLoginGoogleOnlyUserShowsHelpfulGuidance() throws Exception {
                when(userService.authenticate("googleuser@gmail.com", "anypass"))
                                .thenThrow(new IllegalArgumentException(
                                                "This account was registered via Google. Please sign in using 'Continue with Google'."));

                mockMvc.perform(post("/login")
                                .with(csrf())
                                .param("username", "googleuser@gmail.com")
                                .param("password", "anypass"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/login?error=true"))
                                .andExpect(unauthenticated());
        }

        @Test
        void testProcessRegisterRedirectsToVerifyEmailWithoutLoggingIn() throws Exception {
                UserSummaryDto pendingUser = new UserSummaryDto(2L, "Minh Tran", "minh@gmail.com", "0909888777",
                                "CUSTOMER");
                pendingUser.setAccountStatus("PENDING_VERIFICATION");
                when(emailVerificationService.registerWithEmailOtp(any(RegisterRequestDto.class)))
                                .thenReturn(new EmailVerificationService.RegistrationResult(pendingUser, "chal-123",
                                                true, 60));

                mockMvc.perform(post("/register")
                                .with(csrf())
                                .param("fullName", "Minh Tran")
                                .param("email", "minh@gmail.com")
                                .param("phone", "0909888777")
                                .param("password", "Strong@2026!")
                                .param("confirmPassword", "Strong@2026!"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/verify-email?email=minh%40gmail.com&challengeId=chal-123"))
                                .andExpect(unauthenticated());
        }

        @Test
        void testVerifyEmailSuccessRedirectsToLogin() throws Exception {
                UserSummaryDto verified = new UserSummaryDto(2L, "Minh Tran", "minh@gmail.com", "0909888777",
                                "CUSTOMER");
                when(emailVerificationService.verifyRegistrationOtp("minh@gmail.com", "chal-123", "123456"))
                                .thenReturn(verified);

                mockMvc.perform(post("/verify-email")
                                .with(csrf())
                                .param("email", "minh@gmail.com")
                                .param("challengeId", "chal-123")
                                .param("otpCode", "123456"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/login?verified=true"))
                                .andExpect(flash().attributeExists("successMessage"));
        }

        @Test
        void testApiRegisterAndVerifyAndLoginWithSessionRotation() throws Exception {
                UserSummaryDto pendingUser = new UserSummaryDto(2L, "REST User", "rest@boxcinema.com", "0905556667",
                                "CUSTOMER");
                when(emailVerificationService.registerWithEmailOtp(any(RegisterRequestDto.class)))
                                .thenReturn(new EmailVerificationService.RegistrationResult(pendingUser, "chal-rest",
                                                true, 60));

                mockMvc.perform(post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"nocsrf@boxcinema.com\"}"))
                                .andExpect(status().isForbidden());

                mockMvc.perform(post("/api/auth/register")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                {
                                                  "fullName": "REST User",
                                                  "email": "rest@boxcinema.com",
                                                  "phone": "0905556667",
                                                  "password": "Strong@Passphrase1",
                                                  "confirmPassword": "Strong@Passphrase1"
                                                }
                                                """))
                                .andExpect(status().isAccepted())
                                .andExpect(jsonPath("$.success").value(true));

                // Pending email attempting REST login gets 403 ACCOUNT_REQUIRES_VERIFICATION
                when(userService.authenticate("rest@boxcinema.com", "Strong@Passphrase1"))
                                .thenThrow(new IllegalStateException("PENDING_VERIFICATION:rest@boxcinema.com"));
                mockMvc.perform(post("/api/auth/login")
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                { "email": "rest@boxcinema.com", "password": "Strong@Passphrase1" }
                                                """))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_REQUIRES_VERIFICATION"))
                                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"))
                                .andExpect(jsonPath("$.requiresVerification").value(true));

                // Active login rotates session ID
                UserSummaryDto activeUser = new UserSummaryDto(1L, "Alex Nguyen", "alex@boxcinema.com", "0901234567",
                                "CUSTOMER");
                when(userService.authenticate("alex@boxcinema.com", "Strong@Passphrase1")).thenReturn(activeUser);
                MockHttpSession preLoginSession = new MockHttpSession();
                String originalSessionId = preLoginSession.getId();

                MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                                .session(preLoginSession)
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                                { "email": "alex@boxcinema.com", "password": "Strong@Passphrase1" }
                                                """))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.success").value(true))
                                .andExpect(jsonPath("$.data.email").value("alex@boxcinema.com"))
                                .andExpect(jsonPath("$.user.email").value("alex@boxcinema.com"))
                                .andReturn();

                assertNotEquals(originalSessionId, loginResult.getRequest().getSession(false).getId());
        }

        @Test
        void testSecurityBypassEndpointsAreDisabled() throws Exception {
                // Legacy backdoor POST /auth/social must not authenticate anyone (anonymous
                // blocked before controller, auth gets 4xx)
                mockMvc.perform(post("/auth/social")
                                .with(csrf())
                                .param("provider", "GOOGLE")
                                .param("email", "victim@gmail.com"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrlPattern("**/login"))
                                .andExpect(unauthenticated());

                UserSummaryDto userDto = new UserSummaryDto(1L, "Alex Nguyen", "alex@boxcinema.com", "0901234567",
                                "CUSTOMER");
                mockMvc.perform(post("/auth/social")
                                .with(user(new BoxCinemaUserDetails(userDto)))
                                .with(csrf())
                                .param("provider", "GOOGLE")
                                .param("email", "victim@gmail.com"))
                                .andExpect(status().is4xxClientError());

                // Legacy bypass POST /forgot-password without OTP must fail
                mockMvc.perform(post("/forgot-password")
                                .with(csrf())
                                .param("email", "alex@boxcinema.com")
                                .param("newPassword", "Hacked@123")
                                .param("confirmPassword", "Hacked@123"))
                                .andExpect(status().is4xxClientError());

                // GET /forgot-password redirects cleanly to modal
                mockMvc.perform(get("/forgot-password"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/login?forgot=true"));

                // Out-of-scope Facebook login is rejected cleanly
                mockMvc.perform(get("/auth/oauth2/facebook"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/login?social=unsupported"));
        }

        @Test
        void testGoogleOAuthInitiatesStateAndNonceAndCallbackValidatesState() throws Exception {
                when(googleOidcService.isConfigured()).thenReturn(true);
                when(googleOidcService.generateCryptographicToken()).thenReturn("state-xyz", "nonce-abc");
                when(googleOidcService.generatePkceCodeVerifier()).thenReturn("verifier-xyz");
                when(googleOidcService.computeS256CodeChallenge("verifier-xyz")).thenReturn("challenge-xyz");
                when(googleOidcService.resolveRedirectUri(anyString()))
                                .thenReturn("http://localhost:8080/login/oauth2/code/google");
                when(googleOidcService.buildAuthorizationUrl("state-xyz", "nonce-abc", "challenge-xyz",
                                "http://localhost:8080/login/oauth2/code/google"))
                                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=state-xyz&nonce=nonce-abc&code_challenge=challenge-xyz&code_challenge_method=S256");

                MvcResult initRes = mockMvc.perform(get("/oauth2/authorization/google"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl(
                                                "https://accounts.google.com/o/oauth2/v2/auth?state=state-xyz&nonce=nonce-abc&code_challenge=challenge-xyz&code_challenge_method=S256"))
                                .andReturn();

                MockHttpSession session = (MockHttpSession) initRes.getRequest().getSession(false);
                assertEquals("state-xyz", session.getAttribute("BOX_OAUTH_STATE"));
                assertEquals("nonce-abc", session.getAttribute("BOX_OAUTH_NONCE"));
                assertEquals("verifier-xyz", session.getAttribute("BOX_OAUTH_CODE_VERIFIER"));

                // Wrong OAuth state rejected (CSRF protection on callback)
                mockMvc.perform(get("/login/oauth2/code/google")
                                .session(session)
                                .param("code", "auth-code")
                                .param("state", "tampered-state"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/login"))
                                .andExpect(unauthenticated());

                // Valid Google callback signs directly into home page '/' without any
                // complete-profile step
                when(googleOidcService.generateCryptographicToken()).thenReturn("state-valid", "nonce-valid");
                when(googleOidcService.generatePkceCodeVerifier()).thenReturn("verifier-valid");
                when(googleOidcService.computeS256CodeChallenge("verifier-valid")).thenReturn("challenge-valid");
                when(googleOidcService.buildAuthorizationUrl("state-valid", "nonce-valid", "challenge-valid",
                                "http://localhost:8080/login/oauth2/code/google"))
                                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=state-valid&nonce=nonce-valid&code_challenge=challenge-valid&code_challenge_method=S256");
                MvcResult initValidRes = mockMvc.perform(get("/oauth2/authorization/google"))
                                .andExpect(status().is3xxRedirection())
                                .andReturn();
                MockHttpSession validSession = (MockHttpSession) initValidRes.getRequest().getSession(false);

        GoogleOidcService.VerifiedGoogleIdentity verifiedIdentity =
                new GoogleOidcService.VerifiedGoogleIdentity("google-sub-direct", "direct@gmail.com", "Dũng Đặng", true);
        UserSummaryDto googleCustomer = new UserSummaryDto(99L, "Dũng Đặng", "direct@gmail.com", "+84912345678", "CUSTOMER");
        googleCustomer.setAccountStatus("ACTIVE");
        googleCustomer.setHasPassword(false);
        googleCustomer.setGoogleLinked(true);
        when(googleOidcService.exchangeCodeAndVerifyIdToken("auth-code-ok", "http://localhost:8080/login/oauth2/code/google", "nonce-valid", "verifier-valid"))
                .thenReturn(verifiedIdentity);
        when(userService.authenticateSocial("GOOGLE", "google-sub-direct", "direct@gmail.com", "Dũng Đặng", true))
                .thenReturn(googleCustomer);

                mockMvc.perform(get("/login/oauth2/code/google")
                                .session(validSession)
                                .param("code", "auth-code-ok")
                                .param("state", "state-valid"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/"))
                                .andExpect(authenticated().withUsername("direct@gmail.com"));
        }

        @Test
        void testAuthMethodsAndGoogleUnlinkEnforceAuthCsrfAndReauth() throws Exception {
                // 1. Unauthenticated JSON request gets 401 Unauthorized
                mockMvc.perform(get("/api/users/me/auth-methods"))
                                .andExpect(status().isUnauthorized());

                UserSummaryDto userDto = new UserSummaryDto(1L, "Alex Nguyen", "alex@boxcinema.com", "0901234567",
                                "CUSTOMER");
                BoxCinemaUserDetails principal = new BoxCinemaUserDetails(userDto);
                when(userService.getAuthMethods(1L)).thenReturn(new AuthMethodsDto(
                                1L, "Alex Nguyen", "alex@boxcinema.com", "0901234567", "CUSTOMER", "ACTIVE", true, true,
                                true));

                // 2. Authenticated user gets AuthMethodsDto
                mockMvc.perform(get("/api/users/me/auth-methods").with(user(principal)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.success").value(true))
                                .andExpect(jsonPath("$.authMethods.googleLinked").value(true))
                                .andExpect(jsonPath("$.authMethods.canUnlinkGoogle").value(true));

                // 3. DELETE /api/auth/google/link without CSRF -> 403 Forbidden
                mockMvc.perform(delete("/api/auth/google/link")
                                .with(user(principal))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"Strong@123\"}"))
                                .andExpect(status().isForbidden());

                // 4. With CSRF but NO password or recent reauth -> 403 requiresReauth=true
                mockMvc.perform(delete("/api/auth/google/link")
                                .with(user(principal))
                                .with(csrf()))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.requiresReauth").value(true));

                // 5. With CSRF + verified password -> succeeds 200 OK
                mockMvc.perform(delete("/api/auth/google/link")
                                .with(user(principal))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"Strong@123\"}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.success").value(true));
                verify(userService, times(1)).reauthenticatePassword(1L, "Strong@123");
                verify(userService, times(1)).unlinkGoogleAccount(1L);
        }

        @Test
        void testProfileAndAccountLinkRequireAuthenticationAndReauth() throws Exception {
                // Test F: Unauthenticated callers rejected cleanly on MVC & REST LINK endpoints
                mockMvc.perform(get("/profile"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrlPattern("**/login"));
                mockMvc.perform(get("/auth/account/link/google"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrlPattern("**/login"));
                mockMvc.perform(post("/auth/account/link/google").with(csrf()))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrlPattern("**/login"));
                mockMvc.perform(get("/api/auth/google/link"))
                                .andExpect(status().isUnauthorized());
                mockMvc.perform(post("/api/auth/google/link").with(csrf()))
                                .andExpect(status().isUnauthorized());

                UserSummaryDto userDto = new UserSummaryDto(1L, "Alex Nguyen", "alex@boxcinema.com", "0901234567",
                                "CUSTOMER");
                BoxCinemaUserDetails principal = new BoxCinemaUserDetails(userDto);

                // Missing password on link Google redirects back with guidance
                mockMvc.perform(post("/auth/account/link/google")
                                .with(user(principal))
                                .with(csrf()))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/profile?linkStatus=error&linkError=RECENT_AUTH_REQUIRED"))
                                .andExpect(flash().attribute("errorMessage", containsString("password")));

                // Test G: Missing CSRF on POST /api/account/google-link/reauth & POST
                // /api/auth/google/link -> 403 Forbidden
                mockMvc.perform(post("/api/account/google-link/reauth")
                                .with(user(principal))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"Strong@123\"}"))
                                .andExpect(status().isForbidden());
                mockMvc.perform(post("/api/auth/google/link")
                                .with(user(principal))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"Strong@123\"}"))
                                .andExpect(status().isForbidden());

                // Test A + G: Isolated reauth -> /oauth2/link/google/start ->
                // /oauth2/link/google/callback preserves session
                when(googleOidcService.isConfigured()).thenReturn(true);
                when(googleOidcService.generateCryptographicToken()).thenReturn("tx-link-1", "link-state-1",
                                "link-nonce-1");
                when(googleOidcService.generatePkceCodeVerifier()).thenReturn("link-verifier-1");
                when(googleOidcService.computeS256CodeChallenge("link-verifier-1")).thenReturn("link-challenge-1");
                when(googleOidcService.resolveLinkRedirectUri(anyString()))
                                .thenReturn("http://localhost:8080/oauth2/link/google/callback");
                when(googleOidcService.buildAuthorizationUrl("link-state-1", "link-nonce-1", "link-challenge-1",
                                "http://localhost:8080/oauth2/link/google/callback", "select_account"))
                                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=link-state-1&nonce=link-nonce-1&code_challenge=link-challenge-1&code_challenge_method=S256&prompt=select_account");

                MvcResult reauthRes = mockMvc.perform(post("/api/account/google-link/reauth")
                                .with(user(principal))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"Strong@123\"}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.success").value(true))
                                .andExpect(jsonPath("$.linkStartUrl").value("/oauth2/link/google/start"))
                                .andExpect(jsonPath("$.authorizationUrl").value("/oauth2/authorization/google-link"))
                                .andReturn();

                MockHttpSession linkSession = (MockHttpSession) reauthRes.getRequest().getSession(false);
                assertNotNull(linkSession.getAttribute("BOX_LINK_TX_TOKEN"));

                mockMvc.perform(get("/oauth2/link/google/start")
                                .session(linkSession)
                                .with(user(principal)))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl(
                                                "https://accounts.google.com/o/oauth2/v2/auth?state=link-state-1&nonce=link-nonce-1&code_challenge=link-challenge-1&code_challenge_method=S256&prompt=select_account"));
                assertEquals(1L, linkSession.getAttribute("BOX_LINK_OAUTH_USER_ID"));

                GoogleOidcService.VerifiedGoogleIdentity matchingIdentity = new GoogleOidcService.VerifiedGoogleIdentity(
                                "google-sub-alex", "alex@boxcinema.com", "Alex Nguyen", true);
                when(googleOidcService.exchangeCodeAndVerifyIdToken("link-code-1",
                                "http://localhost:8080/oauth2/link/google/callback", "link-nonce-1", "link-verifier-1"))
                                .thenReturn(matchingIdentity);

                mockMvc.perform(get("/oauth2/link/google/callback")
                                .session(linkSession)
                                .with(user(principal))
                                .param("code", "link-code-1")
                                .param("state", "link-state-1"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/profile?linkStatus=success"))
                                .andExpect(authenticated().withUsername("alex@boxcinema.com"))
                                .andExpect(flash().attribute("successMessage",
                                                containsString("connected successfully")));
                verify(userService, times(1)).linkGoogleAccount(1L, "google-sub-alex", "alex@boxcinema.com");

                // Test G (Replay): Reusing the exact same state/callback a 2nd time is rejected
                // cleanly
                mockMvc.perform(get("/oauth2/link/google/callback")
                                .session(linkSession)
                                .with(user(principal))
                                .param("code", "link-code-1")
                                .param("state", "link-state-1"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/profile?linkStatus=error&linkError=OAUTH_STATE_INVALID"));

                // Test G (Expired State): OAuth state past expiration is rejected before
                // exchanging code
                when(googleOidcService.generateCryptographicToken()).thenReturn("tx-link-exp", "link-state-exp",
                                "link-nonce-exp");
                mockMvc.perform(post("/api/account/google-link/reauth")
                                .session(linkSession)
                                .with(user(principal))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"Strong@123\"}"))
                                .andExpect(status().isOk());
                when(googleOidcService.generatePkceCodeVerifier()).thenReturn("link-verifier-exp");
                when(googleOidcService.computeS256CodeChallenge("link-verifier-exp")).thenReturn("link-challenge-exp");
                when(googleOidcService.buildAuthorizationUrl("link-state-exp", "link-nonce-exp", "link-challenge-exp",
                                "http://localhost:8080/oauth2/link/google/callback", "select_account"))
                                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=link-state-exp");
                mockMvc.perform(get("/oauth2/link/google/start")
                                .session(linkSession)
                                .with(user(principal)))
                                .andExpect(status().is3xxRedirection());
                linkSession.setAttribute("BOX_LINK_OAUTH_EXPIRES_AT_MS", System.currentTimeMillis() - 5_000L);

                mockMvc.perform(get("/oauth2/link/google/callback")
                                .session(linkSession)
                                .with(user(principal))
                                .param("code", "code-exp")
                                .param("state", "link-state-exp"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl(
                                                "/profile?linkStatus=error&linkError=LINK_TRANSACTION_EXPIRED"))
                                .andExpect(flash().attribute("errorMessage", containsString("expired")));
                verify(googleOidcService, never()).exchangeCodeAndVerifyIdToken(eq("code-exp"), anyString(),
                                anyString(), any());

                // Test D (Callback error handling when service rejects conflict Gmail): stays
                // logged in & shows error on /profile
                when(googleOidcService.generateCryptographicToken()).thenReturn("tx-link-wrong", "link-state-wrong",
                                "link-nonce-wrong");
                mockMvc.perform(post("/api/account/google-link/reauth")
                                .session(linkSession)
                                .with(user(principal))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"Strong@123\"}"))
                                .andExpect(status().isOk());
                when(googleOidcService.generatePkceCodeVerifier()).thenReturn("link-verifier-wrong");
                when(googleOidcService.computeS256CodeChallenge("link-verifier-wrong"))
                                .thenReturn("link-challenge-wrong");
                when(googleOidcService.buildAuthorizationUrl("link-state-wrong", "link-nonce-wrong",
                                "link-challenge-wrong", "http://localhost:8080/oauth2/link/google/callback",
                                "select_account"))
                                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=link-state-wrong");
                mockMvc.perform(get("/oauth2/link/google/start")
                                .session(linkSession)
                                .with(user(principal)))
                                .andExpect(status().is3xxRedirection());

                GoogleOidcService.VerifiedGoogleIdentity wrongIdentity = new GoogleOidcService.VerifiedGoogleIdentity(
                                "google-sub-other", "other@gmail.com", "Other User", true);
                when(googleOidcService.exchangeCodeAndVerifyIdToken("code-wrong",
                                "http://localhost:8080/oauth2/link/google/callback", "link-nonce-wrong",
                                "link-verifier-wrong"))
                                .thenReturn(wrongIdentity);
                doThrow(com.boxcinema.exception.AuthException.conflict("GOOGLE_EMAIL_CONFLICT",
                                "This Google email is already associated with another BOX Cinema account."))
                                .when(userService).linkGoogleAccount(1L, "google-sub-other", "other@gmail.com");

                mockMvc.perform(get("/oauth2/link/google/callback")
                                .session(linkSession)
                                .with(user(principal))
                                .param("code", "code-wrong")
                                .param("state", "link-state-wrong"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/profile?linkStatus=error&linkError=GOOGLE_EMAIL_CONFLICT"))
                                .andExpect(authenticated().withUsername("alex@boxcinema.com"))
                                .andExpect(flash().attribute("errorMessage", containsString("already associated")));

                // Security check: Forged userId query param / different authenticated user
                // cannot hijack LINK callback
                when(googleOidcService.generateCryptographicToken()).thenReturn("tx-link-hijack", "link-state-hijack",
                                "link-nonce-hijack");
                mockMvc.perform(post("/api/account/google-link/reauth")
                                .session(linkSession)
                                .with(user(principal))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"Strong@123\"}"))
                                .andExpect(status().isOk());
                when(googleOidcService.generatePkceCodeVerifier()).thenReturn("link-verifier-hijack");
                when(googleOidcService.computeS256CodeChallenge("link-verifier-hijack"))
                                .thenReturn("link-challenge-hijack");
                when(googleOidcService.buildAuthorizationUrl("link-state-hijack", "link-nonce-hijack",
                                "link-challenge-hijack", "http://localhost:8080/oauth2/link/google/callback",
                                "select_account"))
                                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=link-state-hijack");
                mockMvc.perform(get("/oauth2/link/google/start")
                                .session(linkSession)
                                .with(user(principal)))
                                .andExpect(status().is3xxRedirection());

                User otherActive = new User("Other Active", "other@boxcinema.com", "0909998887", "$2a$10$hash",
                                "CUSTOMER");
                otherActive.setUserId(2L);
                otherActive.setAccountStatus("ACTIVE");
                when(userRepository.findById(2L)).thenReturn(Optional.of(otherActive));
                UserSummaryDto otherDto = new UserSummaryDto(2L, "Other Active", "other@boxcinema.com", "0909998887",
                                "CUSTOMER");
                when(googleOidcService.exchangeCodeAndVerifyIdToken("code-hijack",
                                "http://localhost:8080/oauth2/link/google/callback", "link-nonce-hijack",
                                "link-verifier-hijack"))
                                .thenReturn(new GoogleOidcService.VerifiedGoogleIdentity("google-sub-alex2",
                                                "alex@boxcinema.com", "Alex", true));

                mockMvc.perform(get("/oauth2/link/google/callback")
                                .session(linkSession)
                                .with(user(new BoxCinemaUserDetails(otherDto)))
                                .param("code", "code-hijack")
                                .param("state", "link-state-hijack")
                                .param("userId", "1"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/profile?linkStatus=error&linkError=OAUTH_STATE_INVALID"))
                                .andExpect(flash().attribute("errorMessage", containsString("OAuth state")));
                verify(userService, never()).linkGoogleAccount(eq(2L), anyString(), anyString());

                // Test I: Role authorization stays enforced (/admin, /manager, /staff reject
                // CUSTOMER with 403)
                mockMvc.perform(get("/admin").with(user(principal))).andExpect(status().isForbidden());
                mockMvc.perform(get("/manager").with(user(principal))).andExpect(status().isForbidden());
                mockMvc.perform(get("/staff").with(user(principal))).andExpect(status().isForbidden());
        }

        @Test
        void testSpringSecurityLogoutInvalidatesSessionAndProtectedRoutesEnforceCsrf() throws Exception {
                UserSummaryDto userDto = new UserSummaryDto(1L, "Alex Nguyen", "alex@boxcinema.com", "0901234567",
                                "CUSTOMER");
                when(userService.authenticate("alex@boxcinema.com", "Strong@123")).thenReturn(userDto);

                MvcResult loginResult = mockMvc.perform(post("/login")
                                .with(csrf())
                                .param("username", "alex@boxcinema.com")
                                .param("password", "Strong@123"))
                                .andExpect(status().is3xxRedirection())
                                .andReturn();

                MockHttpSession session = (MockHttpSession) loginResult.getRequest().getSession(false);

                MvcResult logoutResult = mockMvc.perform(post("/logout")
                                .session(session)
                                .with(csrf()))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/login?logout=true"))
                                .andExpect(unauthenticated())
                                .andReturn();

                MockHttpSession logoutSession = (MockHttpSession) logoutResult.getRequest().getSession(false);
                mockMvc.perform(get("/login?logout=true").session(logoutSession))
                                .andExpect(status().isOk())
                                .andExpect(model().attribute("successMessage", "You have been signed out safely."));

                mockMvc.perform(post("/register")
                                .param("fullName", "No Csrf"))
                                .andExpect(status().isForbidden());
        }

        @Test
        void testSharedLoginCallbackEndpointDispatchesLinkAndSetPasswordFlowsSafely() throws Exception {
                UserSummaryDto userDto = new UserSummaryDto(1L, "Alex Nguyen", "alex@boxcinema.com", "0901234567",
                                "CUSTOMER");
                BoxCinemaUserDetails principal = new BoxCinemaUserDetails(userDto);
                String sharedCallbackUri = "http://localhost:8080/login/oauth2/code/google";

                when(googleOidcService.isConfigured()).thenReturn(true);
                when(googleOidcService.resolveLinkRedirectUri(anyString())).thenReturn(sharedCallbackUri);
                when(googleOidcService.generateCryptographicToken())
                                .thenReturn("tx-shared-1", "link-state-shared-1", "link-nonce-shared-1");
                when(googleOidcService.generatePkceCodeVerifier()).thenReturn("link-verifier-shared-1");
                when(googleOidcService.computeS256CodeChallenge("link-verifier-shared-1"))
                                .thenReturn("link-challenge-shared-1");
                when(googleOidcService.buildAuthorizationUrl("link-state-shared-1", "link-nonce-shared-1",
                                "link-challenge-shared-1", sharedCallbackUri, "select_account"))
                                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=link-state-shared-1");

                MvcResult reauthRes = mockMvc.perform(post("/api/account/google-link/reauth")
                                .with(user(principal))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"Strong@123\"}"))
                                .andExpect(status().isOk())
                                .andReturn();
                MockHttpSession session = (MockHttpSession) reauthRes.getRequest().getSession(false);

                mockMvc.perform(get("/oauth2/link/google/start")
                                .session(session)
                                .with(user(principal)))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl(
                                                "https://accounts.google.com/o/oauth2/v2/auth?state=link-state-shared-1"));
                assertEquals(sharedCallbackUri, session.getAttribute("BOX_LINK_OAUTH_REDIRECT_URI"));

                when(googleOidcService.exchangeCodeAndVerifyIdToken("code-shared-ok", sharedCallbackUri,
                                "link-nonce-shared-1", "link-verifier-shared-1"))
                                .thenReturn(new GoogleOidcService.VerifiedGoogleIdentity("sub-shared-1",
                                                "alex@boxcinema.com", "Alex Nguyen", true));

                mockMvc.perform(get("/login/oauth2/code/google")
                                .session(session)
                                .with(user(principal))
                                .param("code", "code-shared-ok")
                                .param("state", "link-state-shared-1"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/profile?linkStatus=success"))
                                .andExpect(authenticated().withUsername("alex@boxcinema.com"));
                verify(userService, times(1)).linkGoogleAccount(1L, "sub-shared-1", "alex@boxcinema.com");

                // Cancel on shared callback preserves profile error redirect instead of
                // treating it as login cancel
                when(googleOidcService.generateCryptographicToken())
                                .thenReturn("tx-shared-2", "link-state-cancel", "link-nonce-cancel");
                when(googleOidcService.generatePkceCodeVerifier()).thenReturn("link-verifier-cancel");
                when(googleOidcService.computeS256CodeChallenge("link-verifier-cancel"))
                                .thenReturn("link-challenge-cancel");
                when(googleOidcService.buildAuthorizationUrl("link-state-cancel", "link-nonce-cancel",
                                "link-challenge-cancel", sharedCallbackUri, "select_account"))
                                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=link-state-cancel");

                mockMvc.perform(post("/api/account/google-link/reauth")
                                .session(session)
                                .with(user(principal))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"Strong@123\"}"))
                                .andExpect(status().isOk());
                mockMvc.perform(get("/oauth2/link/google/start")
                                .session(session)
                                .with(user(principal)))
                                .andExpect(status().is3xxRedirection());
                mockMvc.perform(get("/login/oauth2/code/google")
                                .session(session)
                                .with(user(principal))
                                .param("error", "access_denied")
                                .param("state", "link-state-cancel"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/profile?linkStatus=error&linkError=OAUTH_CANCELLED"));

                // Google-only Set-Password re-auth also dispatches via shared
                // /login/oauth2/code/google callback
                when(userService.getAuthMethods(1L)).thenReturn(new AuthMethodsDto(
                                1L, "Alex Nguyen", "alex@boxcinema.com", "0901234567", "CUSTOMER", "ACTIVE", false,
                                true, false));
                when(googleOidcService.resolveSetPasswordRedirectUri(anyString())).thenReturn(sharedCallbackUri);
                when(googleOidcService.generateCryptographicToken())
                                .thenReturn("setpw-state-shared", "setpw-nonce-shared");
                when(googleOidcService.generatePkceCodeVerifier()).thenReturn("setpw-verifier-shared");
                when(googleOidcService.computeS256CodeChallenge("setpw-verifier-shared"))
                                .thenReturn("setpw-challenge-shared");
                when(googleOidcService.buildAuthorizationUrl("setpw-state-shared", "setpw-nonce-shared",
                                "setpw-challenge-shared", sharedCallbackUri))
                                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=setpw-state-shared");

                mockMvc.perform(get("/oauth2/set-password/google/start")
                                .session(session)
                                .with(user(principal)))
                                .andExpect(status().is3xxRedirection());
                when(googleOidcService.exchangeCodeAndVerifyIdToken("setpw-code-shared", sharedCallbackUri,
                                "setpw-nonce-shared", "setpw-verifier-shared"))
                                .thenReturn(new GoogleOidcService.VerifiedGoogleIdentity("sub-shared-1",
                                                "alex@boxcinema.com", "Alex Nguyen", true));

                mockMvc.perform(get("/login/oauth2/code/google")
                                .session(session)
                                .with(user(principal))
                                .param("code", "setpw-code-shared")
                                .param("state", "setpw-state-shared"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/profile?setPasswordReady=true"));
                verify(userService, times(1)).verifyGoogleSubOwnership(1L, "sub-shared-1");
        }

        @Test
        void testSpecV1LinkEndpointsRecentReauthCrossEmailNonceAndUnlink() throws Exception {
                // 1. Unauthenticated /api/account/google/link/start -> 401
                // ACCOUNT_NOT_AUTHENTICATED
                mockMvc.perform(post("/api/account/google/link/start").with(csrf()))
                                .andExpect(status().isUnauthorized())
                                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_AUTHENTICATED"));

                // 2. Unauthenticated /oauth2/authorization/google-link -> 302 /login (must not
                // be permitAll)
                mockMvc.perform(get("/oauth2/authorization/google-link"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrlPattern("**/login"));

                UserSummaryDto userDto = new UserSummaryDto(1L, "Alex Nguyen", "alex@boxcinema.com", "0901234567",
                                "CUSTOMER");
                BoxCinemaUserDetails principal = new BoxCinemaUserDetails(userDto);
                when(googleOidcService.isConfigured()).thenReturn(true);

                // 3. Stale session without recent reauth (> 5m or no reauth) -> 403
                // RECENT_AUTH_REQUIRED
                MockHttpSession session = new MockHttpSession();
                mockMvc.perform(post("/api/account/google/link/start")
                                .session(session)
                                .with(user(principal))
                                .with(csrf()))
                                .andExpect(status().isForbidden())
                                .andExpect(jsonPath("$.errorCode").value("RECENT_AUTH_REQUIRED"))
                                .andExpect(jsonPath("$.requiresReauth").value(true));

                // 4. Fresh recent password login (< 5m) allows POST
                // /api/account/google/link/start WITHOUT re-prompting password
                session.setAttribute("BOX_REAUTH_USER_ID", 1L);
                session.setAttribute("BOX_REAUTH_VERIFIED_AT_MS", System.currentTimeMillis());
                when(googleOidcService.generateCryptographicToken()).thenReturn("tx-spec-1", "spec-state-1",
                                "spec-nonce-1");
                when(googleOidcService.generatePkceCodeVerifier()).thenReturn("spec-verifier-1");
                when(googleOidcService.computeS256CodeChallenge("spec-verifier-1")).thenReturn("spec-challenge-1");
                when(googleOidcService.resolveLinkRedirectUri(anyString()))
                                .thenReturn("http://localhost:8080/oauth2/link/google/callback");
                when(googleOidcService.buildAuthorizationUrl("spec-state-1", "spec-nonce-1", "spec-challenge-1",
                                "http://localhost:8080/oauth2/link/google/callback", "select_account"))
                                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=spec-state-1&prompt=select_account");

                mockMvc.perform(post("/api/account/google/link/start")
                                .session(session)
                                .with(user(principal))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.success").value(true))
                                .andExpect(jsonPath("$.authorizationUrl").value("/oauth2/authorization/google-link"));

                // 5. GET /oauth2/authorization/google-link redirects to Google chooser
                mockMvc.perform(get("/oauth2/authorization/google-link")
                                .session(session)
                                .with(user(principal)))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl(
                                                "https://accounts.google.com/o/oauth2/v2/auth?state=spec-state-1&prompt=select_account"));

                // 6. Cross-email callback on /login/oauth2/code/google-link links external
                // Gmail cleanly
                when(googleOidcService.exchangeCodeAndVerifyIdToken("spec-code-1",
                                "http://localhost:8080/oauth2/link/google/callback", "spec-nonce-1", "spec-verifier-1"))
                                .thenReturn(new GoogleOidcService.VerifiedGoogleIdentity("sub-cross-work",
                                                "alex.work@gmail.com", "Alex Work", true));

                mockMvc.perform(get("/login/oauth2/code/google-link")
                                .session(session)
                                .with(user(principal))
                                .param("code", "spec-code-1")
                                .param("state", "spec-state-1"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/profile?linkStatus=success"))
                                .andExpect(authenticated().withUsername("alex@boxcinema.com"));
                verify(userService, times(1)).linkGoogleAccount(1L, "sub-cross-work", "alex.work@gmail.com");

                // 7. Replaying /oauth2/authorization/google-link after single-use TX consumed
                // -> LINK_TRANSACTION_ALREADY_USED
                mockMvc.perform(get("/oauth2/authorization/google-link")
                                .session(session)
                                .with(user(principal)))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl(
                                                "/profile?linkStatus=error&linkError=LINK_TRANSACTION_ALREADY_USED"));

                // 8. OIDC nonce invalid on callback -> redirects with OAUTH_NONCE_INVALID
                when(googleOidcService.generateCryptographicToken()).thenReturn("tx-spec-nonce", "spec-state-nonce",
                                "spec-nonce-bad");
                when(googleOidcService.generatePkceCodeVerifier()).thenReturn("spec-verifier-nonce");
                when(googleOidcService.computeS256CodeChallenge("spec-verifier-nonce"))
                                .thenReturn("spec-challenge-nonce");
                when(googleOidcService.buildAuthorizationUrl("spec-state-nonce", "spec-nonce-bad",
                                "spec-challenge-nonce", "http://localhost:8080/oauth2/link/google/callback",
                                "select_account"))
                                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=spec-state-nonce");
                mockMvc.perform(post("/api/account/google/link/start")
                                .session(session)
                                .with(user(principal))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"password\":\"Strong@123\"}"))
                                .andExpect(status().isOk());
                mockMvc.perform(get("/oauth2/authorization/google-link")
                                .session(session)
                                .with(user(principal)))
                                .andExpect(status().is3xxRedirection());
                when(googleOidcService.exchangeCodeAndVerifyIdToken("bad-nonce-code",
                                "http://localhost:8080/oauth2/link/google/callback", "spec-nonce-bad",
                                "spec-verifier-nonce"))
                                .thenThrow(com.boxcinema.exception.AuthException.badRequest("OAUTH_NONCE_INVALID",
                                                "Google token nonce validation failed."));
                mockMvc.perform(get("/login/oauth2/code/google-link")
                                .session(session)
                                .with(user(principal))
                                .param("code", "bad-nonce-code")
                                .param("state", "spec-state-nonce"))
                                .andExpect(status().is3xxRedirection())
                                .andExpect(redirectedUrl("/profile?linkStatus=error&linkError=OAUTH_NONCE_INVALID"));

                // 9. /api/account/google/unlink rejects Google-only user with
                // CANNOT_UNLINK_LAST_AUTH_METHOD
                when(userService.getAuthMethods(1L)).thenReturn(new AuthMethodsDto(
                                1L, "Alex Nguyen", "alex@boxcinema.com", "0901234567", "CUSTOMER", "ACTIVE", false,
                                true, false));
                mockMvc.perform(post("/api/account/google/unlink")
                                .with(user(principal))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.errorCode").value("CANNOT_UNLINK_LAST_AUTH_METHOD"));

        // 10. Normal Google Login routes unlinked account lacking OTP evidence to /auth/google/verify-link
        // and preserves ACCOUNT_LINK_REQUIRED fallback behavior when thrown.
        when(googleOidcService.generateCryptographicToken()).thenReturn("login-state-req", "login-nonce-req");
        when(googleOidcService.generatePkceCodeVerifier()).thenReturn("login-verifier-req");
        when(googleOidcService.computeS256CodeChallenge("login-verifier-req")).thenReturn("login-challenge-req");
        when(googleOidcService.resolveRedirectUri(anyString())).thenReturn("http://localhost:8080/login/oauth2/code/google");
        when(googleOidcService.buildAuthorizationUrl("login-state-req", "login-nonce-req", "login-challenge-req", "http://localhost:8080/login/oauth2/code/google"))
                .thenReturn("https://accounts.google.com/o/oauth2/v2/auth?state=login-state-req");
        MvcResult loginStart = mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        MockHttpSession loginSession = (MockHttpSession) loginStart.getRequest().getSession(false);
        when(googleOidcService.exchangeCodeAndVerifyIdToken("code-unlinked-existing", "http://localhost:8080/login/oauth2/code/google", "login-nonce-req", "login-verifier-req"))
                .thenReturn(new GoogleOidcService.VerifiedGoogleIdentity("sub-unlinked", "alex@boxcinema.com", "Alex", true));
        when(userService.authenticateSocial("GOOGLE", "sub-unlinked", "alex@boxcinema.com", "Alex", false))
                .thenThrow(new IllegalStateException("GOOGLE_EMAIL_OTP_REQUIRED:alex@boxcinema.com"));
        when(emailVerificationService.requestGoogleLinkEmailOtp("alex@boxcinema.com", false))
                .thenReturn(new EmailVerificationService.ResendResult("alex@boxcinema.com", "chg-otp-1", true, 60));
        mockMvc.perform(get("/login/oauth2/code/google")
                        .session(loginSession)
                        .param("code", "code-unlinked-existing")
                        .param("state", "login-state-req"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/auth/google/verify-link"))
                .andExpect(request().sessionAttribute("BOX_PENDING_GOOGLE_MODE", "OTP_REQUIRED"))
                .andExpect(request().sessionAttribute("BOX_PENDING_GOOGLE_OTP_CHALLENGE_ID", "chg-otp-1"));

        when(userService.verifyOtpAndLinkPendingGoogle("sub-unlinked", "alex@boxcinema.com", "chg-otp-1", "123456"))
                .thenReturn(userDto);
        mockMvc.perform(post("/auth/google/verify-link/otp")
                        .with(csrf())
                        .session(loginSession)
                        .param("otp", "123456"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername("alex@boxcinema.com"));

                // 11. Account Settings /profile HTML uses Connect Google / Not connected
                // without matching Gmail copy
                mockMvc.perform(get("/profile").with(user(principal)))
                                .andExpect(status().isOk())
                                .andExpect(content().string(containsString("Connect Google")))
                                .andExpect(content().string(containsString("Not connected")))
                                .andExpect(content()
                                                .string(org.hamcrest.Matchers.not(containsString("matching Gmail"))))
                                .andExpect(content()
                                                .string(org.hamcrest.Matchers.not(containsString("Connected Gmail:"))));
        }
}
