package com.boxcinema.controller;

import com.boxcinema.dto.AuthMethodsDto;
import com.boxcinema.dto.EmailVerificationResendDto;
import com.boxcinema.dto.EmailVerificationVerifyDto;
import com.boxcinema.dto.GoogleUnlinkRequestDto;
import com.boxcinema.dto.LoginRequestDto;
import com.boxcinema.dto.ReauthRequestDto;
import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.entity.Movie;
import com.boxcinema.entity.OtpPurpose;
import com.boxcinema.entity.PasswordReset;
import com.boxcinema.entity.User;
import com.boxcinema.exception.AuthException;
import com.boxcinema.repository.PasswordResetRepository;
import com.boxcinema.repository.UserRepository;
import com.boxcinema.security.BoxCinemaUserDetails;
import com.boxcinema.security.SecuritySessionHelper;
import com.boxcinema.service.AuthAuditService;
import com.boxcinema.service.AuthRateLimitService;
import com.boxcinema.service.EmailVerificationService;
import com.boxcinema.service.GoogleOidcService;
import com.boxcinema.service.LoginAttemptService;
import com.boxcinema.service.MovieService;
import com.boxcinema.service.OtpChallengeTxHelper;
import com.boxcinema.service.PasswordPolicyService;
import com.boxcinema.service.SessionManagementService;
import com.boxcinema.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
public class AuthController {

    private static final String SESSION_OAUTH_STATE = "BOX_OAUTH_STATE";
    private static final String SESSION_OAUTH_NONCE = "BOX_OAUTH_NONCE";
    private static final String SESSION_OAUTH_CODE_VERIFIER = "BOX_OAUTH_CODE_VERIFIER";
    private static final String SESSION_OAUTH_FLOW = "BOX_OAUTH_FLOW";
    private static final String SESSION_OAUTH_EXPIRES_AT_MS = "BOX_OAUTH_EXPIRES_AT_MS";

    private static final String SESSION_LINK_OAUTH_STATE = "BOX_LINK_OAUTH_STATE";
    private static final String SESSION_LINK_OAUTH_NONCE = "BOX_LINK_OAUTH_NONCE";
    private static final String SESSION_LINK_OAUTH_VERIFIER = "BOX_LINK_OAUTH_VERIFIER";
    private static final String SESSION_LINK_OAUTH_USER_ID = "BOX_LINK_OAUTH_USER_ID";
    private static final String SESSION_LINK_OAUTH_EXPIRES_AT_MS = "BOX_LINK_OAUTH_EXPIRES_AT_MS";
    private static final String SESSION_LINK_OAUTH_REDIRECT_URI = "BOX_LINK_OAUTH_REDIRECT_URI";

    private static final String SESSION_LINK_TX_TOKEN = "BOX_LINK_TX_TOKEN";
    private static final String SESSION_LINK_TX_USER_ID = "BOX_LINK_TX_USER_ID";
    private static final String SESSION_LINK_TX_EXPIRES_AT_MS = "BOX_LINK_TX_EXPIRES_AT_MS";
    private static final String SESSION_LINK_TX_USED = "BOX_LINK_TX_USED";

    private static final String SESSION_SETPW_OAUTH_STATE = "BOX_SETPW_OAUTH_STATE";
    private static final String SESSION_SETPW_OAUTH_NONCE = "BOX_SETPW_OAUTH_NONCE";
    private static final String SESSION_SETPW_OAUTH_VERIFIER = "BOX_SETPW_OAUTH_VERIFIER";
    private static final String SESSION_SETPW_OAUTH_USER_ID = "BOX_SETPW_OAUTH_USER_ID";
    private static final String SESSION_SETPW_OAUTH_EXPIRES_AT_MS = "BOX_SETPW_OAUTH_EXPIRES_AT_MS";
    private static final String SESSION_SETPW_OAUTH_REDIRECT_URI = "BOX_SETPW_OAUTH_REDIRECT_URI";
    private static final String SESSION_SETPW_REAUTH_USER_ID = "BOX_SET_PW_REAUTH_USER_ID";
    private static final String SESSION_SETPW_REAUTH_EXPIRES_AT_MS = "BOX_SET_PW_REAUTH_EXPIRES_AT_MS";

    private static final String SESSION_REAUTH_USER_ID = "BOX_REAUTH_USER_ID";
    private static final String SESSION_REAUTH_AT_MS = "BOX_REAUTH_VERIFIED_AT_MS";
    private static final String SESSION_PENDING_GOOGLE_SUB = "BOX_PENDING_GOOGLE_SUB";
    private static final String SESSION_PENDING_GOOGLE_EMAIL = "BOX_PENDING_GOOGLE_EMAIL";
    private static final String SESSION_PENDING_GOOGLE_NAME = "BOX_PENDING_GOOGLE_NAME";
    private static final String SESSION_PENDING_GOOGLE_VERIFIED = "BOX_PENDING_GOOGLE_VERIFIED";
    private static final String SESSION_PENDING_GOOGLE_EXPIRES_AT_MS = "BOX_PENDING_GOOGLE_EXPIRES_AT_MS";
    private static final String SESSION_PENDING_GOOGLE_MODE = "BOX_PENDING_GOOGLE_MODE";
    private static final String SESSION_PENDING_GOOGLE_TARGET_EMAIL = "BOX_PENDING_GOOGLE_TARGET_EMAIL";
    private static final String SESSION_PENDING_GOOGLE_OTP_CHALLENGE_ID = "BOX_PENDING_GOOGLE_OTP_CHALLENGE_ID";
    private static final long REAUTH_VALIDITY_MS = 5 * 60 * 1000L;
    private static final long OAUTH_STATE_TTL_MS = 5 * 60 * 1000L;
    private static final long PENDING_GOOGLE_TTL_MS = 10 * 60 * 1000L;
    private static final String GENERIC_REGISTER_ACCEPTED_MESSAGE =
            "If registration can proceed, a 6-digit verification code has been sent to your email.";

    private final UserService userService;
    private final EmailVerificationService emailVerificationService;
    private final GoogleOidcService googleOidcService;
    private final AuthRateLimitService authRateLimitService;
    private final SessionManagementService sessionManagementService;
    private final AuthAuditService authAuditService;
    private final MovieService movieService;
    private final SecurityContextRepository securityContextRepository;
    private final UserRepository userRepository;
    private final PasswordResetRepository passwordResetRepository;

    @Autowired
    public AuthController(
            UserService userService,
            ObjectProvider<EmailVerificationService> emailVerificationServiceProvider,
            ObjectProvider<GoogleOidcService> googleOidcServiceProvider,
            ObjectProvider<AuthRateLimitService> authRateLimitServiceProvider,
            ObjectProvider<SessionManagementService> sessionManagementServiceProvider,
            ObjectProvider<AuthAuditService> authAuditServiceProvider,
            ObjectProvider<MovieService> movieServiceProvider,
            ObjectProvider<SecurityContextRepository> securityContextRepositoryProvider,
            ObjectProvider<UserRepository> userRepositoryProvider,
            ObjectProvider<PasswordResetRepository> passwordResetRepositoryProvider) {
        this.userService = userService;
        this.emailVerificationService = emailVerificationServiceProvider != null ? emailVerificationServiceProvider.getIfAvailable() : null;
        this.googleOidcService = googleOidcServiceProvider != null ? googleOidcServiceProvider.getIfAvailable() : null;
        this.authRateLimitService = authRateLimitServiceProvider != null ? authRateLimitServiceProvider.getIfAvailable() : null;
        this.sessionManagementService = sessionManagementServiceProvider != null ? sessionManagementServiceProvider.getIfAvailable() : null;
        this.authAuditService = authAuditServiceProvider != null ? authAuditServiceProvider.getIfAvailable(AuthAuditService::new) : new AuthAuditService();
        this.movieService = movieServiceProvider != null ? movieServiceProvider.getIfAvailable() : null;
        this.securityContextRepository = securityContextRepositoryProvider != null ? securityContextRepositoryProvider.getIfAvailable() : null;
        this.userRepository = userRepositoryProvider != null ? userRepositoryProvider.getIfAvailable() : null;
        this.passwordResetRepository = passwordResetRepositoryProvider != null ? passwordResetRepositoryProvider.getIfAvailable() : null;
    }

    public AuthController(UserService userService, ObjectMapper objectMapper) {
        this.userService = userService;
        this.emailVerificationService = null;
        this.googleOidcService = null;
        this.authRateLimitService = null;
        this.sessionManagementService = null;
        this.authAuditService = new AuthAuditService();
        this.movieService = null;
        this.securityContextRepository = null;
        this.userRepository = null;
        this.passwordResetRepository = null;
    }

    // ==================== AUTH PAGES & REGISTRATION (AUTH-01) ====================

    @GetMapping("/login")
    public String loginPage(Model model) {
        model.addAttribute("activePage", "login");
        model.addAttribute("googleOAuthConfigured", isGoogleOAuthConfigured());
        populateAuthShowcasePosters(model);
        return "auth/login";
    }

    @GetMapping({"/register", "/signup"})
    public String registerPage(Model model) {
        model.addAttribute("activePage", "register");
        model.addAttribute("googleOAuthConfigured", isGoogleOAuthConfigured());
        if (!model.containsAttribute("formData")) {
            model.addAttribute("formData", new RegisterRequestDto());
        }
        populateAuthShowcasePosters(model);
        return "auth/register";
    }

    @GetMapping("/forgot-password")
    public String forgotPasswordRedirect() {
        return "redirect:/login?forgot=true";
    }

    @PostMapping({"/register", "/signup"})
    public String processRegister(
            @ModelAttribute RegisterRequestDto registerRequest,
            HttpServletRequest request,
            Model model,
            RedirectAttributes redirectAttributes) {
        try {
            if (authRateLimitService != null) {
                String clientIp = resolveClientIp(request);
                authRateLimitService.checkRegisterRateLimit(clientIp);
                authRateLimitService.checkOtpRateLimit(clientIp);
            }
            if (emailVerificationService == null) {
                throw new IllegalStateException("Email verification service is unavailable.");
            }
            EmailVerificationService.RegistrationResult result = emailVerificationService.registerWithEmailOtp(registerRequest);
            if (result.emailSent()) {
                redirectAttributes.addFlashAttribute("successMessage", "Verification code sent — valid for 5 minutes.");
                redirectAttributes.addFlashAttribute(
                        "successDetail",
                        "Enter the 6-digit code from your Gmail to activate your account."
                );
            } else {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "Account created, but email delivery failed or SMTP is not configured. Please click 'Resend Verification Code' below."
                );
            }
            redirectAttributes.addFlashAttribute("cooldownSeconds", result.cooldownSeconds());
            return "redirect:/verify-email?email=" + urlEncode(result.user().getEmail())
                    + "&challengeId=" + urlEncode(result.challengeId());
        } catch (OtpChallengeTxHelper.PendingVerificationException pendingEx) {
            redirectAttributes.addFlashAttribute("errorMessage", pendingEx.getMessage());
            String redirect = "redirect:/verify-email?email=" + urlEncode(pendingEx.getEmail());
            if (pendingEx.getChallengeId() != null) {
                redirect += "&challengeId=" + urlEncode(pendingEx.getChallengeId());
            }
            return redirect;
        } catch (AuthRateLimitService.RateLimitExceededException | IllegalArgumentException | IllegalStateException ex) {
            model.addAttribute("activePage", "register");
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("formData", registerRequest);
            model.addAttribute("googleOAuthConfigured", isGoogleOAuthConfigured());
            populateAuthShowcasePosters(model);
            return "auth/register";
        }
    }

    @GetMapping("/verify-email")
    public String verifyEmailPage(
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "challengeId", required = false) String challengeId,
            Model model) {
        String cleanEmail = email != null ? email.trim().toLowerCase() : "";
        String resolvedChallengeId = challengeId != null ? challengeId.trim() : "";

        if (resolvedChallengeId.isBlank() && !cleanEmail.isBlank() && userRepository != null && passwordResetRepository != null) {
            resolvedChallengeId = userRepository.findByEmail(cleanEmail)
                    .flatMap(u -> passwordResetRepository.findByUserIdAndPurpose(u.getUserId(), OtpPurpose.EMAIL_VERIFICATION))
                    .map(PasswordReset::getChallengeId)
                    .orElse("");
        }

        model.addAttribute("activePage", "register");
        model.addAttribute("email", cleanEmail);
        model.addAttribute("verifyEmail", cleanEmail);
        model.addAttribute("challengeId", resolvedChallengeId);
        populateAuthShowcasePosters(model);
        return "auth/verify-email";
    }

    @PostMapping("/verify-email")
    public String processVerifyEmail(
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "challengeId", required = false) String challengeId,
            @RequestParam(value = "otpCode", required = false) String otpCode,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        String cleanEmail = email != null ? email.trim().toLowerCase() : "";
        try {
            if (authRateLimitService != null) {
                authRateLimitService.checkOtpRateLimit(resolveClientIp(request));
            }
            if (emailVerificationService == null) {
                throw new IllegalStateException("Email verification service is unavailable.");
            }
            UserSummaryDto verified = emailVerificationService.verifyRegistrationOtp(cleanEmail, challengeId, otpCode);
            redirectAttributes.addFlashAttribute("successMessage", "Email verified successfully! Please sign in with your password.");
            redirectAttributes.addFlashAttribute("enteredEmail", verified.getEmail());
            return "redirect:/login?verified=true";
        } catch (AuthRateLimitService.RateLimitExceededException | IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            StringBuilder redirect = new StringBuilder("redirect:/verify-email?email=").append(urlEncode(cleanEmail));
            if (challengeId != null && !challengeId.isBlank()) {
                redirect.append("&challengeId=").append(urlEncode(challengeId.trim()));
            }
            return redirect.toString();
        }
    }

    @PostMapping("/verify-email/resend")
    public String processResendVerificationOtp(
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "challengeId", required = false) String challengeId,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        String cleanEmail = email != null ? email.trim().toLowerCase() : "";
        String activeChallengeId = challengeId != null ? challengeId.trim() : "";
        try {
            if (authRateLimitService != null) {
                authRateLimitService.checkOtpRateLimit(resolveClientIp(request));
            }
            if (emailVerificationService == null) {
                throw new IllegalStateException("Email verification service is unavailable.");
            }
            EmailVerificationService.ResendResult result = emailVerificationService.resendVerificationOtp(cleanEmail, activeChallengeId);
            activeChallengeId = result.challengeId();
            cleanEmail = result.email();
            redirectAttributes.addFlashAttribute("successMessage", "New verification code sent — valid for 5 minutes.");
            redirectAttributes.addFlashAttribute(
                    "successDetail",
                    "Enter the latest 6-digit code sent to your Gmail."
            );
            redirectAttributes.addFlashAttribute("cooldownSeconds", result.cooldownSeconds());
        } catch (OtpChallengeTxHelper.OtpCooldownException cooldownEx) {
            redirectAttributes.addFlashAttribute("errorMessage", cooldownEx.getMessage());
            redirectAttributes.addFlashAttribute("cooldownSeconds", cooldownEx.getCooldownSeconds());
        } catch (AuthRateLimitService.RateLimitExceededException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            redirectAttributes.addFlashAttribute("cooldownSeconds", (int) ex.getRetryAfterSeconds());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        StringBuilder redirect = new StringBuilder("redirect:/verify-email?email=").append(urlEncode(cleanEmail));
        if (!activeChallengeId.isBlank()) {
            redirect.append("&challengeId=").append(urlEncode(activeChallengeId));
        }
        return redirect.toString();
    }

    // ==================== REST AUTH ENDPOINTS ====================

    @GetMapping("/api/auth/csrf")
    @ResponseBody
    public ResponseEntity<?> apiCsrf(HttpServletRequest request) {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token == null) {
            return ResponseEntity.ok(Map.of("headerName", "X-CSRF-TOKEN", "parameterName", "_csrf", "token", ""));
        }
        return ResponseEntity.ok(Map.of(
                "headerName", token.getHeaderName(),
                "parameterName", token.getParameterName(),
                "token", token.getToken()
        ));
    }

    @PostMapping("/api/auth/register")
    @ResponseBody
    public ResponseEntity<?> apiRegister(
            @RequestBody(required = false) RegisterRequestDto registerRequest,
            HttpServletRequest request) {
        try {
            String clientIp = resolveClientIp(request);
            if (authRateLimitService != null) {
                authRateLimitService.checkRegisterRateLimit(clientIp);
                authRateLimitService.checkOtpRateLimit(clientIp);
                if (registerRequest != null && registerRequest.getEmail() != null && !registerRequest.getEmail().isBlank()) {
                    authRateLimitService.checkRegisterEmailRateLimit(registerRequest.getEmail());
                }
            }
            if (emailVerificationService == null) {
                throw new IllegalStateException("Email verification service is unavailable.");
            }
            emailVerificationService.registerWithEmailOtp(registerRequest);
            return acceptedGenericRegisterResponse();
        } catch (OtpChallengeTxHelper.PendingVerificationException ex) {
            return acceptedGenericRegisterResponse();
        } catch (AuthRateLimitService.RateLimitExceededException ex) {
            return rateLimitedResponse(ex.getMessage(), ex.getRetryAfterSeconds());
        } catch (IllegalArgumentException ex) {
            String msg = ex.getMessage() != null ? ex.getMessage() : "";
            if (msg.contains("already registered") || msg.contains("awaiting verification")) {
                return acceptedGenericRegisterResponse();
            }
            return ResponseEntity.badRequest().body(errorBody("VALIDATION_ERROR", msg));
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(errorBody("VALIDATION_ERROR", ex.getMessage()));
        }
    }

    @PostMapping({"/api/auth/register/verify", "/api/auth/register/verify-otp"})
    @ResponseBody
    public ResponseEntity<?> apiVerifyRegistrationOtp(
            @RequestBody(required = false) EmailVerificationVerifyDto verifyDto,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            if (authRateLimitService != null) {
                authRateLimitService.checkOtpRateLimit(resolveClientIp(request));
            }
            if (emailVerificationService == null) {
                throw new IllegalStateException("Email verification service is unavailable.");
            }
            UserSummaryDto user = emailVerificationService.verifyRegistrationOtp(
                    verifyDto != null ? verifyDto.getEmail() : null,
                    verifyDto != null ? verifyDto.getChallengeId() : null,
                    verifyDto != null ? verifyDto.getOtp() : null
            );
            SecuritySessionHelper.authenticateUserSession(
                    user,
                    request,
                    response,
                    securityContextRepository,
                    sessionManagementService
            );
            markReauthVerified(request.getSession(false), user.getUserId());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Email verified successfully. You are now signed in.",
                    "data", user,
                    "user", user
            ));
        } catch (AuthRateLimitService.RateLimitExceededException ex) {
            return rateLimitedResponse(ex.getMessage(), ex.getRetryAfterSeconds());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(errorBody("VALIDATION_ERROR", ex.getMessage()));
        }
    }

    @PostMapping({"/api/auth/register/resend", "/api/auth/register/resend-otp"})
    @ResponseBody
    public ResponseEntity<?> apiResendRegistrationOtp(
            @RequestBody(required = false) EmailVerificationResendDto resendDto,
            HttpServletRequest request) {
        try {
            if (authRateLimitService != null) {
                authRateLimitService.checkOtpRateLimit(resolveClientIp(request));
            }
            if (emailVerificationService == null) {
                throw new IllegalStateException("Email verification service is unavailable.");
            }
            EmailVerificationService.ResendResult res = emailVerificationService.resendVerificationOtp(
                    resendDto != null ? resendDto.getEmail() : null,
                    resendDto != null ? resendDto.getChallengeId() : null
            );
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "A new 6-digit verification code has been sent.",
                    "cooldownSeconds", res.cooldownSeconds()
            ));
        } catch (OtpChallengeTxHelper.OtpCooldownException ex) {
            return rateLimitedResponse(ex.getMessage(), ex.getCooldownSeconds());
        } catch (AuthRateLimitService.RateLimitExceededException ex) {
            return rateLimitedResponse(ex.getMessage(), ex.getRetryAfterSeconds());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(errorBody("VALIDATION_ERROR", ex.getMessage()));
        }
    }

    @PostMapping("/api/auth/login")
    @ResponseBody
    public ResponseEntity<?> apiLogin(
            @RequestBody(required = false) LoginRequestDto loginRequest,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            if (authRateLimitService != null) {
                authRateLimitService.checkLoginRateLimit(resolveClientIp(request));
            }
            if (loginRequest == null) {
                throw AuthException.badRequest("VALIDATION_ERROR", "Email and password are required.");
            }
            UserSummaryDto user = userService.authenticate(
                    loginRequest.getEmail(),
                    loginRequest.getPassword()
            );
            SecuritySessionHelper.authenticateUserSession(
                    user,
                    request,
                    response,
                    securityContextRepository,
                    sessionManagementService
            );
            markReauthVerified(request.getSession(false), user.getUserId());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Login successful.",
                    "data", user,
                    "user", user
            ));
        } catch (AuthRateLimitService.RateLimitExceededException ex) {
            return rateLimitedResponse(ex.getMessage(), ex.getRetryAfterSeconds());
        } catch (LoginAttemptService.LoginLockoutException ex) {
            return rateLimitedResponse(ex.getMessage(), ex.getRetryAfterSeconds());
        } catch (IllegalStateException stateEx) {
            String msg = stateEx.getMessage() != null ? stateEx.getMessage() : "";
            if (msg.startsWith("PENDING_VERIFICATION:")) {
                String unverifiedEmail = msg.substring("PENDING_VERIFICATION:".length());
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                        "success", false,
                        "errorCode", "ACCOUNT_REQUIRES_VERIFICATION",
                        "code", "EMAIL_NOT_VERIFIED",
                        "requiresVerification", true,
                        "email", unverifiedEmail,
                        "message", "Please verify your email with the 6-digit OTP before signing in."
                ));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody("VALIDATION_ERROR", msg));
        } catch (AuthException ex) {
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getHttpStatus());
            if (ex.getRetryAfterSeconds() != null) {
                builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
            }
            return builder.body(errorBody(ex.getErrorCode(), ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("AUTH_INVALID_CREDENTIALS", ex.getMessage()));
        }
    }

    @GetMapping("/api/auth/me")
    @ResponseBody
    public ResponseEntity<?> apiMe(HttpServletRequest request) {
        UserSummaryDto user = resolveAuthenticatedUser(request);
        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "message", "Not authenticated."
            ));
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "user", user
        ));
    }

    @PostMapping("/api/auth/logout")
    @ResponseBody
    public ResponseEntity<?> apiLogout(HttpServletRequest request, HttpServletResponse response) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        HttpSession session = request.getSession(false);
        if (sessionManagementService != null && currentUser != null) {
            sessionManagementService.unregisterSession(currentUser.getUserId(), session);
        }
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        expireCookie(response, "JSESSIONID");
        expireCookie(response, "remember-me");
        if (currentUser != null) {
            authAuditService.logEvent("SESSION_LOGOUT", currentUser.getUserId(), currentUser.getEmail(), "SUCCESS", "REST API logout");
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Logged out successfully."
        ));
    }

    // ==================== GOOGLE OAUTH2 / OIDC (AUTH-03, AUTH-04) ====================

    @GetMapping("/oauth2/authorization/google")
    public String startGoogleOAuthLogin(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        return startGoogleLoginFlow(request, redirectAttributes);
    }

    @GetMapping("/auth/oauth2/{provider}")
    public String redirectToOAuth2Provider(
            @PathVariable("provider") String provider,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        String cleanProvider = provider != null ? provider.trim().toLowerCase() : "";
        if (!"google".equals(cleanProvider)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Only Google sign-in is supported.");
            return "redirect:/login?social=unsupported";
        }
        return startGoogleLoginFlow(request, redirectAttributes);
    }

    private String startGoogleLoginFlow(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        if (!isGoogleOAuthConfigured()) {
            redirectAttributes.addFlashAttribute(
                    "infoMessage",
                    "Google Sign-In requires Google OAuth2 client credentials in application-local.properties."
            );
            return "redirect:/login?social=google_unconfigured";
        }

        String state = googleOidcService.generateCryptographicToken();
        String nonce = googleOidcService.generateCryptographicToken();
        String codeVerifier = googleOidcService.generatePkceCodeVerifier();
        String codeChallenge = googleOidcService.computeS256CodeChallenge(codeVerifier);
        String redirectUri = googleOidcService.resolveRedirectUri(getBaseUrl(request) + "/login/oauth2/code/google");

        prepareOAuthSessionState(request.getSession(true), state, nonce, codeVerifier, "LOGIN");

        return "redirect:" + googleOidcService.buildAuthorizationUrl(state, nonce, codeChallenge, redirectUri);
    }

    @GetMapping({"/login/oauth2/code/{provider}", "/auth/oauth2/callback/{provider}"})
    public String handleOAuth2Callback(
            @PathVariable("provider") String provider,
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes) {

        String cleanProvider = provider != null ? provider.trim().toLowerCase() : "";
        if (!"google".equals(cleanProvider)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Only Google authentication is supported.");
            return "redirect:/login";
        }

        HttpSession session = request.getSession(false);
        // Route isolated Link / Set-Password callbacks cleanly when sharing the registered /login/oauth2/code/google URI.
        if (session != null && state != null && !state.isBlank()) {
            String loginState = (String) session.getAttribute(SESSION_OAUTH_STATE);
            boolean matchesLoginState = loginState != null && constantTimeEquals(loginState, state);
            if (!matchesLoginState) {
                String linkState = (String) session.getAttribute(SESSION_LINK_OAUTH_STATE);
                if (linkState != null && constantTimeEquals(linkState, state)) {
                    return handleGoogleLinkCallback(code, state, error, request, redirectAttributes);
                }
                String setPwState = (String) session.getAttribute(SESSION_SETPW_OAUTH_STATE);
                if (setPwState != null && constantTimeEquals(setPwState, state)) {
                    return handleSetPasswordGoogleCallback(code, state, error, request, redirectAttributes);
                }
            }
        }

        String expectedState = session != null ? (String) session.getAttribute(SESSION_OAUTH_STATE) : null;
        String expectedNonce = session != null ? (String) session.getAttribute(SESSION_OAUTH_NONCE) : null;
        String expectedVerifier = session != null ? (String) session.getAttribute(SESSION_OAUTH_CODE_VERIFIER) : null;
        String flow = session != null ? (String) session.getAttribute(SESSION_OAUTH_FLOW) : null;
        Object expectedExpiresObj = session != null ? session.getAttribute(SESSION_OAUTH_EXPIRES_AT_MS) : null;

        if (session != null) {
            session.removeAttribute(SESSION_OAUTH_STATE);
            session.removeAttribute(SESSION_OAUTH_NONCE);
            session.removeAttribute(SESSION_OAUTH_CODE_VERIFIER);
            session.removeAttribute(SESSION_OAUTH_FLOW);
            session.removeAttribute(SESSION_OAUTH_EXPIRES_AT_MS);
        }

        if (error != null || code == null || code.isBlank()) {
            authAuditService.logGoogleEvent("GOOGLE_LOGIN_FAILURE", null, null, "CANCELLED", error != null ? error : "Missing code");
            redirectAttributes.addFlashAttribute("errorMessage", "Google sign-in was cancelled or failed.");
            return "redirect:/login";
        }

        if (!"LOGIN".equals(flow) || expectedState == null || expectedNonce == null
                || state == null || !constantTimeEquals(expectedState, state)) {
            authAuditService.logGoogleEvent("GOOGLE_LOGIN_FAILURE", null, null, "REJECTED", "OAuth state mismatch");
            redirectAttributes.addFlashAttribute("errorMessage", "Security verification (OAuth state) failed or expired. Please try again.");
            return "redirect:/login";
        }

        if (!(expectedExpiresObj instanceof Long expiresAtMs) || System.currentTimeMillis() > expiresAtMs) {
            authAuditService.logGoogleEvent("GOOGLE_LOGIN_FAILURE", null, null, "REJECTED", "OAuth state expired");
            redirectAttributes.addFlashAttribute("errorMessage", "Google authorization request expired. Please try again.");
            return "redirect:/login";
        }

        if (!isGoogleOAuthConfigured()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Google OAuth2 is not configured.");
            return "redirect:/login";
        }

        try {
            String redirectUri = googleOidcService.resolveRedirectUri(getBaseUrl(request) + request.getRequestURI());
            GoogleOidcService.VerifiedGoogleIdentity identity = googleOidcService.exchangeCodeAndVerifyIdToken(
                    code,
                    redirectUri,
                    expectedNonce,
                    expectedVerifier
            );
            if (!identity.emailVerified()) {
                throw AuthException.badRequest("GOOGLE_EMAIL_UNVERIFIED", "Your Google account email must be verified with Google.");
            }

            boolean trustedGoogleEmail = GoogleOidcService.isTrustedGoogleEmailIdentity(
                    identity.email(),
                    identity.hostedDomain()
            );

            try {
                UserSummaryDto user = userService.authenticateSocial(
                        "GOOGLE",
                        identity.googleSub(),
                        identity.email(),
                        identity.fullName(),
                        trustedGoogleEmail
                );
                clearPendingGoogleSession(request.getSession(false));
                SecuritySessionHelper.authenticateUserSession(
                        user,
                        request,
                        response,
                        securityContextRepository,
                        sessionManagementService
                );
                clearReauth(request.getSession(false));
                return resolveRoleRedirect(user.getRole());
            } catch (IllegalStateException stateEx) {
                String msg = stateEx.getMessage() != null ? stateEx.getMessage() : "";
                if (msg.startsWith("GOOGLE_ONBOARDING_REQUIRED:")) {
                    storePendingGoogleIdentity(request, identity, "ONBOARDING", null);
                    return "redirect:/auth/google/complete-profile";
                }
                if (msg.startsWith("GOOGLE_EMAIL_OTP_REQUIRED:")) {
                    String targetEmail = extractSuffixAfterColon(msg, identity.email());
                    HttpSession pendingSession = storePendingGoogleIdentity(request, identity, "OTP_REQUIRED", targetEmail);
                    issueInitialGoogleLinkOtp(request, pendingSession, targetEmail, redirectAttributes);
                    return "redirect:/auth/google/verify-link";
                }
                if (msg.startsWith("BOX_PASSWORD_AUTH_REQUIRED:")) {
                    String targetEmail = extractSuffixAfterColon(msg, identity.email());
                    storePendingGoogleIdentity(request, identity, "PASSWORD_REQUIRED", targetEmail);
                    return "redirect:/auth/google/verify-link";
                }
                if (msg.startsWith("ACCOUNT_LINK_AUTH_REQUIRED:")) {
                    String targetEmail = extractSuffixAfterColon(msg, identity.email());
                    storePendingGoogleIdentity(request, identity, "LINK_CHOICE", targetEmail);
                    return "redirect:/auth/google/verify-link";
                }
                if (msg.startsWith("ACCOUNT_LINK_REQUIRED:") || msg.startsWith("ACCOUNT_EXISTS_NEEDS_LINK:")) {
                    redirectAttributes.addFlashAttribute(
                            "errorMessage",
                            "An account with email (" + identity.email() + ") already exists. Please sign in with your email and password, then click Connect Google in Account Settings."
                    );
                    redirectAttributes.addFlashAttribute("errorCode", "ACCOUNT_LINK_REQUIRED");
                    return "redirect:/login?error=ACCOUNT_LINK_REQUIRED";
                }
                throw stateEx;
            }
        } catch (AuthException ex) {
            authAuditService.logGoogleEvent("GOOGLE_LOGIN_FAILURE", null, null, "FAILED", ex.getErrorCode() + ": " + ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            redirectAttributes.addFlashAttribute("errorCode", ex.getErrorCode());
            return "redirect:/login?error=" + urlEncode(ex.getErrorCode());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            authAuditService.logGoogleEvent("GOOGLE_LOGIN_FAILURE", null, null, "FAILED", ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/login";
        }
    }

    @GetMapping("/auth/google/complete-profile")
    public String completeProfilePage(HttpServletRequest request, Model model, RedirectAttributes redirectAttributes) {
        HttpSession session = request.getSession(false);
        if (!hasValidPendingGoogleSession(session)) {
            clearPendingGoogleSession(session);
            redirectAttributes.addFlashAttribute("errorMessage", "Your Google verification session expired or is invalid. Please click 'Continue with Google' again.");
            return "redirect:/login";
        }
        populateCompleteProfileModel(session, model, null, null, null, false);
        return "auth/complete-profile";
    }

    @PostMapping("/auth/google/complete-profile")
    public String processCompleteProfile(
            @RequestParam(value = "fullName", required = false) String fullName,
            @RequestParam(value = "phone", required = false) String phone,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            RedirectAttributes redirectAttributes) {
        HttpSession session = request.getSession(false);
        if (!hasValidPendingGoogleSession(session)) {
            clearPendingGoogleSession(session);
            redirectAttributes.addFlashAttribute("errorMessage", "Your Google verification session expired or is invalid. Please click 'Continue with Google' again.");
            return "redirect:/login";
        }

        String pendingSub = (String) session.getAttribute(SESSION_PENDING_GOOGLE_SUB);
        String pendingEmail = (String) session.getAttribute(SESSION_PENDING_GOOGLE_EMAIL);
        String pendingName = (String) session.getAttribute(SESSION_PENDING_GOOGLE_NAME);
        String chosenName = (fullName != null && !fullName.isBlank()) ? fullName.trim() : pendingName;

        try {
            UserSummaryDto user = userService.registerGoogleCustomer(pendingSub, pendingEmail, chosenName, phone);
            clearPendingGoogleSession(session);
            SecuritySessionHelper.authenticateUserSession(
                    user,
                    request,
                    response,
                    securityContextRepository,
                    sessionManagementService
            );
            redirectAttributes.addFlashAttribute("successMessage", "Welcome to BOX Cinema, " + user.getFullName() + "!");
            return resolveRoleRedirect(user.getRole());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            populateCompleteProfileModel(session, model, chosenName, phone != null ? phone.trim() : "", null, false);
            model.addAttribute("errorMessage", ex.getMessage());
            return "auth/complete-profile";
        }
    }

    @PostMapping("/auth/google/complete-profile/link-existing")
    public String processCompleteProfileLinkExisting(
            @RequestParam(value = "boxEmail", required = false) String boxEmail,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "otp", required = false) String otp,
            @RequestParam(value = "challengeId", required = false) String challengeId,
            @RequestParam(value = "authMethod", defaultValue = "password") String authMethod,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            RedirectAttributes redirectAttributes) {
        HttpSession session = request.getSession(false);
        if (!hasValidPendingGoogleSession(session)) {
            clearPendingGoogleSession(session);
            redirectAttributes.addFlashAttribute("errorMessage", "Your Google verification session expired or is invalid. Please click 'Continue with Google' again.");
            return "redirect:/login";
        }

        String pendingSub = (String) session.getAttribute(SESSION_PENDING_GOOGLE_SUB);
        String cleanBoxEmail = boxEmail != null ? boxEmail.trim() : "";
        String sessionChallengeId = (String) session.getAttribute(SESSION_PENDING_GOOGLE_OTP_CHALLENGE_ID);
        String effectiveChallengeId = (challengeId != null && !challengeId.isBlank()) ? challengeId.trim() : sessionChallengeId;

        try {
            UserSummaryDto user = "otp".equalsIgnoreCase(authMethod)
                    ? userService.verifyOtpAndLinkPendingGoogle(pendingSub, cleanBoxEmail, effectiveChallengeId, otp)
                    : userService.verifyPasswordAndLinkPendingGoogle(pendingSub, cleanBoxEmail, password);
            clearPendingGoogleSession(session);
            SecuritySessionHelper.authenticateUserSession(
                    user,
                    request,
                    response,
                    securityContextRepository,
                    sessionManagementService
            );
            clearReauth(request.getSession(false));
            redirectAttributes.addFlashAttribute("successMessage", "Your Google account has been linked to " + user.getEmail() + ".");
            return resolveRoleRedirect(user.getRole());
        } catch (Exception ex) {
            String message = resolvePendingLinkErrorMessage(ex);
            populateCompleteProfileModel(session, model, null, null, cleanBoxEmail, true);
            model.addAttribute("linkAuthMethod", "otp".equalsIgnoreCase(authMethod) ? "otp" : "password");
            model.addAttribute("linkErrorMessage", message);
            model.addAttribute("activeChallengeId", effectiveChallengeId);
            return "auth/complete-profile";
        }
    }

    @GetMapping("/auth/google/verify-link")
    public String verifyLinkPage(HttpServletRequest request, Model model, RedirectAttributes redirectAttributes) {
        HttpSession session = request.getSession(false);
        if (!hasValidPendingGoogleSession(session)) {
            clearPendingGoogleSession(session);
            redirectAttributes.addFlashAttribute("errorMessage", "Your Google verification session expired or is invalid. Please click 'Continue with Google' again.");
            return "redirect:/login";
        }
        populateVerifyLinkModel(session, model);
        return "auth/verify-link";
    }

    @PostMapping("/auth/google/verify-link/password")
    public String processVerifyLinkPassword(
            @RequestParam(value = "password", required = false) String password,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            RedirectAttributes redirectAttributes) {
        HttpSession session = request.getSession(false);
        if (!hasValidPendingGoogleSession(session)) {
            clearPendingGoogleSession(session);
            redirectAttributes.addFlashAttribute("errorMessage", "Your Google verification session expired or is invalid. Please click 'Continue with Google' again.");
            return "redirect:/login";
        }
        String pendingSub = (String) session.getAttribute(SESSION_PENDING_GOOGLE_SUB);
        String targetEmail = resolvePendingTargetEmail(session);

        try {
            UserSummaryDto user = userService.verifyPasswordAndLinkPendingGoogle(pendingSub, targetEmail, password);
            clearPendingGoogleSession(session);
            SecuritySessionHelper.authenticateUserSession(
                    user,
                    request,
                    response,
                    securityContextRepository,
                    sessionManagementService
            );
            clearReauth(request.getSession(false));
            redirectAttributes.addFlashAttribute("successMessage", "Google Sign-In has been verified and linked to your account.");
            return resolveRoleRedirect(user.getRole());
        } catch (Exception ex) {
            populateVerifyLinkModel(session, model);
            model.addAttribute("errorMessage", resolvePendingLinkErrorMessage(ex));
            model.addAttribute("activeMethod", "password");
            return "auth/verify-link";
        }
    }

    @PostMapping("/auth/google/verify-link/send-otp")
    public Object sendVerifyLinkOtp(
            @RequestParam(value = "boxEmail", required = false) String boxEmail,
            @RequestParam(value = "resend", defaultValue = "false") boolean resend,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        boolean wantsJson = isAjaxOrJsonRequest(request);
        HttpSession session = request.getSession(false);
        if (!hasValidPendingGoogleSession(session)) {
            clearPendingGoogleSession(session);
            if (wantsJson) {
                return ResponseEntity.status(401).body(Map.of(
                        "success", false,
                        "message", "Your Google verification session expired. Please restart Continue with Google."
                ));
            }
            redirectAttributes.addFlashAttribute("errorMessage", "Your Google verification session expired. Please click 'Continue with Google' again.");
            return "redirect:/login";
        }

        String mode = (String) session.getAttribute(SESSION_PENDING_GOOGLE_MODE);
        if ("PASSWORD_REQUIRED".equals(mode)) {
            String msg = "Staff, Manager, and Admin accounts must verify using Email & Password.";
            if (wantsJson) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", msg));
            }
            redirectAttributes.addFlashAttribute("errorMessage", msg);
            return "redirect:/auth/google/verify-link";
        }

        String targetEmail = "ONBOARDING".equals(mode)
                ? (boxEmail != null ? boxEmail.trim() : "")
                : resolvePendingTargetEmail(session);

        try {
            if (emailVerificationService == null) {
                throw new IllegalStateException("Email verification service is unavailable.");
            }
            if (authRateLimitService != null) {
                authRateLimitService.checkOtpRateLimit(resolveClientIp(request));
            }
            boolean isResend = resend && session.getAttribute(SESSION_PENDING_GOOGLE_OTP_CHALLENGE_ID) != null;
            EmailVerificationService.ResendResult otpRes = emailVerificationService.requestGoogleLinkEmailOtp(targetEmail, isResend);
            session.setAttribute(SESSION_PENDING_GOOGLE_TARGET_EMAIL, otpRes.email());
            session.setAttribute(SESSION_PENDING_GOOGLE_OTP_CHALLENGE_ID, otpRes.challengeId());

            if (wantsJson) {
                return ResponseEntity.ok(Map.of(
                        "success", otpRes.emailSent(),
                        "email", otpRes.email(),
                        "challengeId", otpRes.challengeId(),
                        "cooldownSeconds", otpRes.cooldownSeconds(),
                        "message", otpRes.emailSent()
                                ? "A 6-digit verification code was sent to " + otpRes.email() + "."
                                : "Email delivery failed. Please check SMTP settings or verify with your BOX Cinema password."
                ));
            }
            if (otpRes.emailSent()) {
                redirectAttributes.addFlashAttribute("successMessage", "A 6-digit verification code was sent to " + otpRes.email() + ".");
            } else {
                redirectAttributes.addFlashAttribute("errorMessage", "Email delivery is currently unavailable. Please verify with your password or retry.");
            }
            redirectAttributes.addFlashAttribute("cooldownSeconds", otpRes.cooldownSeconds());
            return "ONBOARDING".equals(mode) ? "redirect:/auth/google/complete-profile" : "redirect:/auth/google/verify-link";
        } catch (Exception ex) {
            if (wantsJson) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", ex.getMessage()));
            }
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "ONBOARDING".equals(mode) ? "redirect:/auth/google/complete-profile" : "redirect:/auth/google/verify-link";
        }
    }

    @PostMapping("/auth/google/verify-link/otp")
    public String processVerifyLinkOtp(
            @RequestParam(value = "otp", required = false) String otp,
            @RequestParam(value = "challengeId", required = false) String challengeId,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            RedirectAttributes redirectAttributes) {
        HttpSession session = request.getSession(false);
        if (!hasValidPendingGoogleSession(session)) {
            clearPendingGoogleSession(session);
            redirectAttributes.addFlashAttribute("errorMessage", "Your Google verification session expired or is invalid. Please click 'Continue with Google' again.");
            return "redirect:/login";
        }

        String mode = (String) session.getAttribute(SESSION_PENDING_GOOGLE_MODE);
        if ("PASSWORD_REQUIRED".equals(mode)) {
            populateVerifyLinkModel(session, model);
            model.addAttribute("errorMessage", "Staff, Manager, and Admin accounts must verify with Email & Password.");
            return "auth/verify-link";
        }

        String pendingSub = (String) session.getAttribute(SESSION_PENDING_GOOGLE_SUB);
        String targetEmail = resolvePendingTargetEmail(session);
        String sessionChallengeId = (String) session.getAttribute(SESSION_PENDING_GOOGLE_OTP_CHALLENGE_ID);
        String effectiveChallengeId = (challengeId != null && !challengeId.isBlank()) ? challengeId.trim() : sessionChallengeId;

        try {
            UserSummaryDto user = userService.verifyOtpAndLinkPendingGoogle(pendingSub, targetEmail, effectiveChallengeId, otp);
            clearPendingGoogleSession(session);
            SecuritySessionHelper.authenticateUserSession(
                    user,
                    request,
                    response,
                    securityContextRepository,
                    sessionManagementService
            );
            clearReauth(request.getSession(false));
            redirectAttributes.addFlashAttribute("successMessage", "Email verified and Google Sign-In linked to your account!");
            return resolveRoleRedirect(user.getRole());
        } catch (Exception ex) {
            populateVerifyLinkModel(session, model);
            model.addAttribute("errorMessage", resolvePendingLinkErrorMessage(ex));
            model.addAttribute("activeMethod", "otp");
            return "auth/verify-link";
        }
    }

    // ==================== ACCOUNT MANAGEMENT & GOOGLE LINK/UNLINK (AUTH-05, AUTH-06, AUTH-08, 4A) ====================

    @GetMapping("/profile")
    public String profilePage(
            @RequestParam(value = "linkStatus", required = false) String linkStatus,
            @RequestParam(value = "linkError", required = false) String linkError,
            @RequestParam(value = "setPasswordReady", required = false) String setPasswordReadyParam,
            @RequestParam(value = "setPasswordError", required = false) String setPasswordError,
            HttpServletRequest request,
            Model model) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            return "redirect:/login";
        }

        HttpSession session = request.getSession(false);
        model.addAttribute("googleOAuthConfigured", isGoogleOAuthConfigured());
        model.addAttribute("hasRecentReauth", hasRecentReauth(session, currentUser.getUserId(), REAUTH_VALIDITY_MS));
        model.addAttribute("setPasswordReady", hasValidSetPasswordReauth(session, currentUser.getUserId()));

        if ("success".equalsIgnoreCase(linkStatus) && !model.containsAttribute("successMessage")) {
            model.addAttribute("successMessage", "Google account connected successfully!");
        }
        if ("error".equalsIgnoreCase(linkStatus) && linkError != null && !model.containsAttribute("errorMessage")) {
            model.addAttribute("errorMessage", mapFriendlyErrorCode(linkError));
        }
        if (setPasswordError != null && !setPasswordError.isBlank() && !model.containsAttribute("errorMessage")) {
            model.addAttribute("errorMessage", mapFriendlyErrorCode(setPasswordError));
        }
        if ("true".equalsIgnoreCase(setPasswordReadyParam) && hasValidSetPasswordReauth(session, currentUser.getUserId())
                && !model.containsAttribute("infoMessage")) {
            model.addAttribute("infoMessage", "Google identity verified. Enter your new password (min 6 characters, including 1 uppercase letter, 1 number, and 1 special character) below.");
        }

        if (userRepository != null) {
            Optional<User> userOpt = userRepository.findById(currentUser.getUserId());
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                boolean hasPassword = user.getPasswordHash() != null && !user.getPasswordHash().isBlank();
                boolean isGoogleLinked = user.getGoogleId() != null && !user.getGoogleId().isBlank();
                model.addAttribute("user", user);
                model.addAttribute("hasPassword", hasPassword);
                model.addAttribute("isGoogleLinked", isGoogleLinked);
                model.addAttribute("activePage", "profile");
                return "customer/profile";
            }
        }

        model.addAttribute("user", currentUser);
        model.addAttribute("hasPassword", true);
        model.addAttribute("isGoogleLinked", false);
        model.addAttribute("activePage", "profile");
        return "customer/profile";
    }

    @PostMapping("/api/account/google/link/start")
    @ResponseBody
    public ResponseEntity<?> apiStartGoogleLink(
            @RequestBody(required = false) ReauthRequestDto body,
            HttpServletRequest request) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("ACCOUNT_NOT_AUTHENTICATED", "Authentication required."));
        }
        try {
            if (authRateLimitService != null) {
                authRateLimitService.checkGoogleLinkIpRateLimit(resolveClientIp(request));
                authRateLimitService.checkGoogleLinkUserRateLimit(currentUser.getUserId());
            }
            validateGoogleLinkAccountEligibility(currentUser.getUserId());
            if (!isGoogleOAuthConfigured()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(errorBody("SERVICE_UNAVAILABLE", "Google OAuth2 is not configured."));
            }
            verifyReauthOrPasswordForLink(request, currentUser.getUserId(), body != null ? body.getPassword() : null);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "authorizationUrl", "/oauth2/authorization/google-link"
            ));
        } catch (AuthRateLimitService.RateLimitExceededException ex) {
            return rateLimitedResponse(ex.getMessage(), ex.getRetryAfterSeconds());
        } catch (ReauthRequiredException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "errorCode", "RECENT_AUTH_REQUIRED",
                    "requiresReauth", true,
                    "message", ex.getMessage()
            ));
        } catch (AuthException ex) {
            return ResponseEntity.status(ex.getHttpStatus()).body(errorBody(ex.getErrorCode(), ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("AUTH_INVALID_CREDENTIALS", ex.getMessage()));
        }
    }

    @PostMapping(value = {"/api/account/google-link/reauth", "/auth/account/reauth"}, consumes = "application/json")
    @ResponseBody
    public ResponseEntity<?> apiReauthenticate(
            @RequestBody(required = false) ReauthRequestDto body,
            HttpServletRequest request) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("ACCOUNT_NOT_AUTHENTICATED", "Authentication required."));
        }
        try {
            userService.reauthenticatePassword(currentUser.getUserId(), body != null ? body.getPassword() : null);
            HttpSession session = request.getSession(true);
            markReauthVerified(session, currentUser.getUserId());
            mintLinkTransaction(session, currentUser.getUserId());
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "linkStartUrl", "/oauth2/link/google/start",
                    "authorizationUrl", "/oauth2/authorization/google-link",
                    "message", "Identity confirmed."
            ));
        } catch (AuthException ex) {
            return ResponseEntity.status(ex.getHttpStatus()).body(errorBody(ex.getErrorCode(), ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("AUTH_INVALID_CREDENTIALS", ex.getMessage()));
        }
    }

    @PostMapping("/auth/account/link/google")
    public String linkGoogleWithReauth(
            @RequestParam(value = "password", required = false) String password,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            return "redirect:/login";
        }

        HttpSession session = request.getSession(true);
        try {
            verifyReauthOrPasswordForLink(request, currentUser.getUserId(), password);
            return startIsolatedGoogleLinkRedirect(session, currentUser.getUserId(), request, redirectAttributes);
        } catch (AuthRateLimitService.RateLimitExceededException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/profile?linkStatus=error&linkError=RATE_LIMITED";
        } catch (ReauthRequiredException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please enter your current BOX Cinema password to connect Google.");
            return "redirect:/profile?linkStatus=error&linkError=RECENT_AUTH_REQUIRED";
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/profile?linkStatus=error&linkError=AUTH_INVALID_CREDENTIALS";
        }
    }

    @GetMapping({"/oauth2/authorization/google-link", "/oauth2/link/google/start", "/auth/account/link/google"})
    public String linkGoogleStart(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            return "redirect:/login";
        }
        HttpSession session = request.getSession(false);
        if (!hasValidLinkTransaction(session, currentUser.getUserId())) {
            String errCode = resolveMissingLinkTxErrorCode(session);
            redirectAttributes.addFlashAttribute("errorMessage", mapFriendlyErrorCode(errCode));
            return "redirect:/profile?linkStatus=error&linkError=" + errCode;
        }
        try {
            return startIsolatedGoogleLinkRedirect(session, currentUser.getUserId(), request, redirectAttributes);
        } catch (AuthRateLimitService.RateLimitExceededException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/profile?linkStatus=error&linkError=RATE_LIMITED";
        }
    }

    @GetMapping({"/oauth2/link/google/callback", "/login/oauth2/code/google-link"})
    public String handleGoogleLinkCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {

        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Please sign in to complete Google account linking.");
            return "redirect:/login";
        }

        HttpSession session = request.getSession(false);
        String expectedState = session != null ? (String) session.getAttribute(SESSION_LINK_OAUTH_STATE) : null;
        String expectedNonce = session != null ? (String) session.getAttribute(SESSION_LINK_OAUTH_NONCE) : null;
        String expectedVerifier = session != null ? (String) session.getAttribute(SESSION_LINK_OAUTH_VERIFIER) : null;
        Long expectedUserId = session != null ? (Long) session.getAttribute(SESSION_LINK_OAUTH_USER_ID) : null;
        Object expectedExpiresObj = session != null ? session.getAttribute(SESSION_LINK_OAUTH_EXPIRES_AT_MS) : null;
        String storedLinkRedirectUri = session != null ? (String) session.getAttribute(SESSION_LINK_OAUTH_REDIRECT_URI) : null;

        // Immediately consume Link OAuth params (single-use)
        clearLinkOAuthSessionState(session);

        if (error != null || code == null || code.isBlank()) {
            consumeLinkTransaction(session);
            authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", currentUser.getUserId(), currentUser.getEmail(),
                    "CANCELLED", error != null ? error : "Missing code");
            redirectAttributes.addFlashAttribute("errorMessage", "Google authorization was cancelled or failed.");
            return "redirect:/profile?linkStatus=error&linkError=OAUTH_CANCELLED";
        }

        if (expectedUserId == null || !expectedUserId.equals(currentUser.getUserId())
                || expectedState == null || expectedNonce == null || state == null
                || !constantTimeEquals(expectedState, state)) {
            consumeLinkTransaction(session);
            authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", currentUser.getUserId(), currentUser.getEmail(),
                    "REJECTED", "OAUTH_STATE_INVALID");
            redirectAttributes.addFlashAttribute("errorMessage", "Security verification (OAuth state) failed. Please retry linking.");
            return "redirect:/profile?linkStatus=error&linkError=OAUTH_STATE_INVALID";
        }

        if (!(expectedExpiresObj instanceof Long expiresAtMs) || System.currentTimeMillis() > expiresAtMs) {
            consumeLinkTransaction(session);
            authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", currentUser.getUserId(), currentUser.getEmail(),
                    "REJECTED", "LINK_TRANSACTION_EXPIRED");
            redirectAttributes.addFlashAttribute("errorMessage", "Google link authorization expired. Please retry.");
            return "redirect:/profile?linkStatus=error&linkError=LINK_TRANSACTION_EXPIRED";
        }

        if (!hasValidLinkTransaction(session, currentUser.getUserId())) {
            String errCode = resolveMissingLinkTxErrorCode(session);
            authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", currentUser.getUserId(), currentUser.getEmail(),
                    "REJECTED", errCode);
            redirectAttributes.addFlashAttribute("errorMessage", mapFriendlyErrorCode(errCode));
            return "redirect:/profile?linkStatus=error&linkError=" + errCode;
        }

        // Consume single-use Link Transaction before invoking token exchange/linking
        consumeLinkTransaction(session);

        if (!isGoogleOAuthConfigured()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Google OAuth2 is not configured.");
            return "redirect:/profile?linkStatus=error&linkError=SERVICE_UNAVAILABLE";
        }

        try {
            String linkRedirectUri = (storedLinkRedirectUri != null && !storedLinkRedirectUri.isBlank())
                    ? storedLinkRedirectUri
                    : googleOidcService.resolveLinkRedirectUri(
                            getBaseUrl(request) + "/oauth2/link/google/callback"
                    );
            GoogleOidcService.VerifiedGoogleIdentity identity = googleOidcService.exchangeCodeAndVerifyIdToken(
                    code,
                    linkRedirectUri,
                    expectedNonce,
                    expectedVerifier
            );
            if (!identity.emailVerified()) {
                throw AuthException.badRequest("GOOGLE_EMAIL_UNVERIFIED", "Your Google account email must be verified with Google.");
            }

            userService.linkGoogleAccount(currentUser.getUserId(), identity.googleSub(), identity.email());
            // Keep existing BOX Cinema SecurityContext intact; only update profile summary flags
            syncSessionGoogleLinked(request, currentUser, true);
            clearReauth(session);

            redirectAttributes.addFlashAttribute("successMessage", "Google account connected successfully!");
            return "redirect:/profile?linkStatus=success";
        } catch (AuthException ex) {
            authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", currentUser.getUserId(), currentUser.getEmail(),
                    "FAILED", ex.getErrorCode() + ": " + ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/profile?linkStatus=error&linkError=" + urlEncode(ex.getErrorCode());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            authAuditService.logGoogleEvent("GOOGLE_LINK_FAILURE", currentUser.getUserId(), currentUser.getEmail(),
                    "FAILED", ex.getMessage());
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/profile?linkStatus=error&linkError=OAUTH_LINK_FAILED";
        }
    }

    @RequestMapping(value = "/api/auth/google/link", method = {RequestMethod.GET, RequestMethod.POST})
    @ResponseBody
    public ResponseEntity<?> apiLinkGoogleAuthorizationUrl(
            @RequestBody(required = false) ReauthRequestDto body,
            HttpServletRequest request) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("ACCOUNT_NOT_AUTHENTICATED", "Authentication required."));
        }

        String password = body != null ? body.getPassword() : null;
        try {
            verifyReauthOrPasswordForLink(request, currentUser.getUserId(), password);
            if (authRateLimitService != null) {
                authRateLimitService.checkGoogleLinkIpRateLimit(resolveClientIp(request));
                authRateLimitService.checkGoogleLinkUserRateLimit(currentUser.getUserId());
            }
            validateGoogleLinkAccountEligibility(currentUser.getUserId());
            if (!isGoogleOAuthConfigured()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(errorBody("SERVICE_UNAVAILABLE", "Google OAuth2 is not configured."));
            }

            String state = googleOidcService.generateCryptographicToken();
            String nonce = googleOidcService.generateCryptographicToken();
            String codeVerifier = googleOidcService.generatePkceCodeVerifier();
            String codeChallenge = googleOidcService.computeS256CodeChallenge(codeVerifier);
            String redirectUri = googleOidcService.resolveLinkRedirectUri(getBaseUrl(request) + "/oauth2/link/google/callback");

            prepareLinkOAuthSessionState(request.getSession(true), state, nonce, codeVerifier, currentUser.getUserId(), redirectUri);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "authorizationUrl", googleOidcService.buildAuthorizationUrl(state, nonce, codeChallenge, redirectUri, "select_account")
            ));
        } catch (AuthRateLimitService.RateLimitExceededException ex) {
            return rateLimitedResponse(ex.getMessage(), ex.getRetryAfterSeconds());
        } catch (ReauthRequiredException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "errorCode", "RECENT_AUTH_REQUIRED",
                    "requiresReauth", true,
                    "message", ex.getMessage()
            ));
        } catch (AuthException ex) {
            return ResponseEntity.status(ex.getHttpStatus()).body(errorBody(ex.getErrorCode(), ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("AUTH_INVALID_CREDENTIALS", ex.getMessage()));
        }
    }

    @PostMapping("/auth/account/unlink/google")
    public String unlinkGoogle(
            @RequestParam(value = "password", required = false) String password,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            return "redirect:/login";
        }

        HttpSession session = request.getSession(false);
        try {
            ensureCanUnlinkGoogle(currentUser.getUserId());
            verifyReauthForUnlink(session, currentUser.getUserId(), password);
            userService.unlinkGoogleAccount(currentUser.getUserId());
            syncSessionGoogleLinked(request, currentUser, false);
            clearReauth(session);
            consumeLinkTransaction(session);
            redirectAttributes.addFlashAttribute("successMessage", "Google account disconnected successfully.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/profile";
    }

    @DeleteMapping("/api/auth/google/link")
    @ResponseBody
    public ResponseEntity<?> apiDeleteGoogleLink(
            @RequestBody(required = false) GoogleUnlinkRequestDto body,
            HttpServletRequest request) {
        return apiUnlinkGoogle(body, request);
    }

    @PostMapping({"/api/account/google-link/unlink", "/api/account/google/unlink"})
    @ResponseBody
    public ResponseEntity<?> apiUnlinkGoogle(
            @RequestBody(required = false) GoogleUnlinkRequestDto body,
            HttpServletRequest request) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("ACCOUNT_NOT_AUTHENTICATED", "Authentication required."));
        }
        HttpSession session = request.getSession(false);
        String password = body != null ? body.getPassword() : null;
        try {
            ensureCanUnlinkGoogle(currentUser.getUserId());
            verifyReauthForUnlink(session, currentUser.getUserId(), password);
            userService.unlinkGoogleAccount(currentUser.getUserId());
            syncSessionGoogleLinked(request, currentUser, false);
            clearReauth(session);
            consumeLinkTransaction(session);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "googleLinked", false,
                    "googleConnected", false,
                    "canUnlinkGoogle", false,
                    "message", "Google account disconnected successfully."
            ));
        } catch (ReauthRequiredException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "errorCode", "RECENT_AUTH_REQUIRED",
                    "requiresReauth", true,
                    "message", ex.getMessage()
            ));
        } catch (AuthException ex) {
            return ResponseEntity.status(ex.getHttpStatus()).body(errorBody(ex.getErrorCode(), ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(errorBody("VALIDATION_ERROR", ex.getMessage()));
        }
    }

    @GetMapping("/oauth2/set-password/google/start")
    public String startSetPasswordGoogleReauth(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            return "redirect:/login";
        }
        AuthMethodsDto methods = userService.getAuthMethods(currentUser.getUserId());
        if (methods.isHasPassword() || !methods.isGoogleLinked()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Set Password is only available for Google-only accounts.");
            return "redirect:/profile?setPasswordError=VALIDATION_ERROR";
        }
        if (!isGoogleOAuthConfigured()) {
            redirectAttributes.addFlashAttribute("infoMessage", "Google OAuth2 client credentials are not configured.");
            return "redirect:/profile?setPasswordError=SERVICE_UNAVAILABLE";
        }

        String state = googleOidcService.generateCryptographicToken();
        String nonce = googleOidcService.generateCryptographicToken();
        String codeVerifier = googleOidcService.generatePkceCodeVerifier();
        String codeChallenge = googleOidcService.computeS256CodeChallenge(codeVerifier);
        String redirectUri = googleOidcService.resolveSetPasswordRedirectUri(
                getBaseUrl(request) + "/oauth2/set-password/google/callback"
        );

        HttpSession session = request.getSession(true);
        session.setAttribute(SESSION_SETPW_OAUTH_STATE, state);
        session.setAttribute(SESSION_SETPW_OAUTH_NONCE, nonce);
        session.setAttribute(SESSION_SETPW_OAUTH_VERIFIER, codeVerifier);
        session.setAttribute(SESSION_SETPW_OAUTH_USER_ID, currentUser.getUserId());
        session.setAttribute(SESSION_SETPW_OAUTH_EXPIRES_AT_MS, System.currentTimeMillis() + OAUTH_STATE_TTL_MS);
        session.setAttribute(SESSION_SETPW_OAUTH_REDIRECT_URI, redirectUri);

        return "redirect:" + googleOidcService.buildAuthorizationUrl(state, nonce, codeChallenge, redirectUri);
    }

    @GetMapping("/oauth2/set-password/google/callback")
    public String handleSetPasswordGoogleCallback(
            @RequestParam(value = "code", required = false) String code,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "error", required = false) String error,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            return "redirect:/login";
        }

        HttpSession session = request.getSession(false);
        String expectedState = session != null ? (String) session.getAttribute(SESSION_SETPW_OAUTH_STATE) : null;
        String expectedNonce = session != null ? (String) session.getAttribute(SESSION_SETPW_OAUTH_NONCE) : null;
        String expectedVerifier = session != null ? (String) session.getAttribute(SESSION_SETPW_OAUTH_VERIFIER) : null;
        Long expectedUserId = session != null ? (Long) session.getAttribute(SESSION_SETPW_OAUTH_USER_ID) : null;
        Object expiresObj = session != null ? session.getAttribute(SESSION_SETPW_OAUTH_EXPIRES_AT_MS) : null;
        String storedSetPwRedirectUri = session != null ? (String) session.getAttribute(SESSION_SETPW_OAUTH_REDIRECT_URI) : null;

        clearSetPasswordOAuthSessionState(session);

        if (error != null || code == null || code.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Google verification was cancelled.");
            return "redirect:/profile?setPasswordError=OAUTH_CANCELLED";
        }
        if (expectedUserId == null || !expectedUserId.equals(currentUser.getUserId())
                || expectedState == null || expectedNonce == null || state == null
                || !constantTimeEquals(expectedState, state)) {
            redirectAttributes.addFlashAttribute("errorMessage", "Google verification state is invalid or expired.");
            return "redirect:/profile?setPasswordError=OAUTH_STATE_INVALID";
        }
        if (!(expiresObj instanceof Long expiresAtMs) || System.currentTimeMillis() > expiresAtMs) {
            redirectAttributes.addFlashAttribute("errorMessage", "Google verification request expired. Please try again.");
            return "redirect:/profile?setPasswordError=LINK_TRANSACTION_EXPIRED";
        }
        if (!isGoogleOAuthConfigured()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Google OAuth2 is not configured.");
            return "redirect:/profile?setPasswordError=SERVICE_UNAVAILABLE";
        }

        try {
            String redirectUri = (storedSetPwRedirectUri != null && !storedSetPwRedirectUri.isBlank())
                    ? storedSetPwRedirectUri
                    : googleOidcService.resolveSetPasswordRedirectUri(
                            getBaseUrl(request) + "/oauth2/set-password/google/callback"
                    );
            GoogleOidcService.VerifiedGoogleIdentity identity = googleOidcService.exchangeCodeAndVerifyIdToken(
                    code,
                    redirectUri,
                    expectedNonce,
                    expectedVerifier
            );
            if (!identity.emailVerified()) {
                throw AuthException.badRequest("GOOGLE_EMAIL_UNVERIFIED", "Your Google email must be verified.");
            }
            userService.verifyGoogleSubOwnership(currentUser.getUserId(), identity.googleSub());

            HttpSession activeSession = request.getSession(true);
            activeSession.setAttribute(SESSION_SETPW_REAUTH_USER_ID, currentUser.getUserId());
            activeSession.setAttribute(SESSION_SETPW_REAUTH_EXPIRES_AT_MS, System.currentTimeMillis() + REAUTH_VALIDITY_MS);

            redirectAttributes.addFlashAttribute("infoMessage",
                    "Google identity confirmed! Enter your new password (min 6 characters, including 1 uppercase letter, 1 number, and 1 special character).");
            return "redirect:/profile?setPasswordReady=true";
        } catch (AuthException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/profile?setPasswordError=" + urlEncode(ex.getErrorCode());
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/profile?setPasswordError=VALIDATION_ERROR";
        }
    }

    @PostMapping("/auth/account/set-password")
    public String setPasswordForm(
            @RequestParam(value = "newPassword", required = false) String newPassword,
            @RequestParam(value = "confirmPassword", required = false) String confirmPassword,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            return "redirect:/login";
        }
        HttpSession session = request.getSession(false);
        if (!hasValidSetPasswordReauth(session, currentUser.getUserId())) {
            redirectAttributes.addFlashAttribute("errorMessage",
                    "Please verify your Google account first before creating a BOX Cinema password.");
            return "redirect:/profile?setPasswordError=SET_PASSWORD_REAUTH_REQUIRED";
        }
        try {
            userService.setPasswordForGoogleUser(currentUser.getUserId(), newPassword, confirmPassword);
            clearSetPasswordReauth(session);
            syncSessionHasPassword(request, currentUser, true);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Password created successfully! You can now sign in with Email & Password or Google.");
        } catch (IllegalArgumentException | IllegalStateException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/profile";
    }

    @PostMapping({"/api/account/set-password", "/api/auth/set-password"})
    @ResponseBody
    public ResponseEntity<?> apiSetPassword(
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("VALIDATION_ERROR", "Authentication required."));
        }
        HttpSession session = request.getSession(false);
        if (!hasValidSetPasswordReauth(session, currentUser.getUserId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "errorCode", "SET_PASSWORD_REAUTH_REQUIRED",
                    "requiresGoogleReauth", true,
                    "reauthStartUrl", "/oauth2/set-password/google/start",
                    "message", "Google re-authentication is required before setting a password."
            ));
        }
        String newPassword = body != null ? body.get("newPassword") : null;
        String confirmPassword = body != null ? body.get("confirmPassword") : null;
        try {
            userService.setPasswordForGoogleUser(currentUser.getUserId(), newPassword, confirmPassword);
            clearSetPasswordReauth(session);
            syncSessionHasPassword(request, currentUser, true);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "hasPassword", true,
                    "message", "Password created successfully."
            ));
        } catch (AuthException ex) {
            return ResponseEntity.status(ex.getHttpStatus()).body(errorBody(ex.getErrorCode(), ex.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            return ResponseEntity.badRequest().body(errorBody("VALIDATION_ERROR", ex.getMessage()));
        }
    }

    @GetMapping({"/api/account/auth-methods", "/api/users/me/auth-methods"})
    @ResponseBody
    public ResponseEntity<?> apiGetMyAuthMethods(HttpServletRequest request) {
        UserSummaryDto currentUser = resolveAuthenticatedUser(request);
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorBody("ACCOUNT_NOT_AUTHENTICATED", "Authentication required."));
        }
        try {
            AuthMethodsDto methods = userService.getAuthMethods(currentUser.getUserId());
            methods.setSetPasswordVerified(hasValidSetPasswordReauth(request.getSession(false), currentUser.getUserId()));
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", methods,
                    "authMethods", methods
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(errorBody("VALIDATION_ERROR", ex.getMessage()));
        }
    }

    // ==================== PRIVATE HELPERS ====================

    private static class ReauthRequiredException extends IllegalArgumentException {
        ReauthRequiredException(String message) {
            super(message);
        }
    }

    private String startIsolatedGoogleLinkRedirect(
            HttpSession session,
            Long userId,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {
        if (authRateLimitService != null) {
            authRateLimitService.checkGoogleLinkIpRateLimit(resolveClientIp(request));
            authRateLimitService.checkGoogleLinkUserRateLimit(userId);
        }
        try {
            validateGoogleLinkAccountEligibility(userId);
        } catch (AuthException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/profile?linkStatus=error&linkError=" + urlEncode(ex.getErrorCode());
        }
        if (!isGoogleOAuthConfigured()) {
            redirectAttributes.addFlashAttribute("infoMessage", "Google OAuth2 client credentials are not configured.");
            return "redirect:/profile?linkStatus=error&linkError=SERVICE_UNAVAILABLE";
        }
        String state = googleOidcService.generateCryptographicToken();
        String nonce = googleOidcService.generateCryptographicToken();
        String codeVerifier = googleOidcService.generatePkceCodeVerifier();
        String codeChallenge = googleOidcService.computeS256CodeChallenge(codeVerifier);
        String redirectUri = googleOidcService.resolveLinkRedirectUri(getBaseUrl(request) + "/oauth2/link/google/callback");

        prepareLinkOAuthSessionState(session, state, nonce, codeVerifier, userId, redirectUri);
        authAuditService.logGoogleEvent("GOOGLE_LINK_STARTED", userId, null, "INITIATED", "Redirecting to Google account chooser");

        return "redirect:" + googleOidcService.buildAuthorizationUrl(state, nonce, codeChallenge, redirectUri, "select_account");
    }

    private void prepareOAuthSessionState(HttpSession session, String state, String nonce, String codeVerifier, String flow) {
        if (session == null) {
            return;
        }
        session.setAttribute(SESSION_OAUTH_STATE, state);
        session.setAttribute(SESSION_OAUTH_NONCE, nonce);
        session.setAttribute(SESSION_OAUTH_CODE_VERIFIER, codeVerifier);
        session.setAttribute(SESSION_OAUTH_FLOW, flow);
        session.setAttribute(SESSION_OAUTH_EXPIRES_AT_MS, System.currentTimeMillis() + OAUTH_STATE_TTL_MS);
    }

    private void prepareLinkOAuthSessionState(
            HttpSession session,
            String state,
            String nonce,
            String codeVerifier,
            Long userId,
            String redirectUri) {
        if (session == null) {
            return;
        }
        session.setAttribute(SESSION_LINK_OAUTH_STATE, state);
        session.setAttribute(SESSION_LINK_OAUTH_NONCE, nonce);
        session.setAttribute(SESSION_LINK_OAUTH_VERIFIER, codeVerifier);
        session.setAttribute(SESSION_LINK_OAUTH_USER_ID, userId);
        session.setAttribute(SESSION_LINK_OAUTH_EXPIRES_AT_MS, System.currentTimeMillis() + OAUTH_STATE_TTL_MS);
        session.setAttribute(SESSION_LINK_OAUTH_REDIRECT_URI, redirectUri);
    }

    private void clearLinkOAuthSessionState(HttpSession session) {
        if (session == null) {
            return;
        }
        session.removeAttribute(SESSION_LINK_OAUTH_STATE);
        session.removeAttribute(SESSION_LINK_OAUTH_NONCE);
        session.removeAttribute(SESSION_LINK_OAUTH_VERIFIER);
        session.removeAttribute(SESSION_LINK_OAUTH_USER_ID);
        session.removeAttribute(SESSION_LINK_OAUTH_EXPIRES_AT_MS);
        session.removeAttribute(SESSION_LINK_OAUTH_REDIRECT_URI);
    }

    private void mintLinkTransaction(HttpSession session, Long userId) {
        if (session == null || userId == null) {
            return;
        }
        String txToken = googleOidcService != null
                ? googleOidcService.generateCryptographicToken()
                : java.util.UUID.randomUUID().toString();
        session.setAttribute(SESSION_LINK_TX_TOKEN, txToken);
        session.setAttribute(SESSION_LINK_TX_USER_ID, userId);
        session.setAttribute(SESSION_LINK_TX_EXPIRES_AT_MS, System.currentTimeMillis() + REAUTH_VALIDITY_MS);
        session.setAttribute(SESSION_LINK_TX_USED, Boolean.FALSE);
    }

    private boolean hasValidLinkTransaction(HttpSession session, Long userId) {
        if (session == null || userId == null) {
            return false;
        }
        Object tokenObj = session.getAttribute(SESSION_LINK_TX_TOKEN);
        Object uidObj = session.getAttribute(SESSION_LINK_TX_USER_ID);
        Object expObj = session.getAttribute(SESSION_LINK_TX_EXPIRES_AT_MS);
        Object usedObj = session.getAttribute(SESSION_LINK_TX_USED);
        if (!(tokenObj instanceof String token) || token.isBlank()
                || !(uidObj instanceof Long uid) || !uid.equals(userId)
                || !(expObj instanceof Long expMs)
                || Boolean.TRUE.equals(usedObj)) {
            return false;
        }
        return System.currentTimeMillis() <= expMs;
    }

    private void consumeLinkTransaction(HttpSession session) {
        if (session == null) {
            return;
        }
        session.setAttribute(SESSION_LINK_TX_USED, Boolean.TRUE);
        session.removeAttribute(SESSION_LINK_TX_TOKEN);
        session.removeAttribute(SESSION_LINK_TX_USER_ID);
        session.removeAttribute(SESSION_LINK_TX_EXPIRES_AT_MS);
    }

    private void clearSetPasswordOAuthSessionState(HttpSession session) {
        if (session == null) {
            return;
        }
        session.removeAttribute(SESSION_SETPW_OAUTH_STATE);
        session.removeAttribute(SESSION_SETPW_OAUTH_NONCE);
        session.removeAttribute(SESSION_SETPW_OAUTH_VERIFIER);
        session.removeAttribute(SESSION_SETPW_OAUTH_USER_ID);
        session.removeAttribute(SESSION_SETPW_OAUTH_EXPIRES_AT_MS);
        session.removeAttribute(SESSION_SETPW_OAUTH_REDIRECT_URI);
    }

    private boolean hasValidSetPasswordReauth(HttpSession session, Long userId) {
        if (session == null || userId == null) {
            return false;
        }
        Object uidObj = session.getAttribute(SESSION_SETPW_REAUTH_USER_ID);
        Object expObj = session.getAttribute(SESSION_SETPW_REAUTH_EXPIRES_AT_MS);
        if (!(uidObj instanceof Long uid) || !uid.equals(userId) || !(expObj instanceof Long expMs)) {
            return false;
        }
        return System.currentTimeMillis() <= expMs;
    }

    private void clearSetPasswordReauth(HttpSession session) {
        if (session == null) {
            return;
        }
        session.removeAttribute(SESSION_SETPW_REAUTH_USER_ID);
        session.removeAttribute(SESSION_SETPW_REAUTH_EXPIRES_AT_MS);
    }

    private ResponseEntity<Map<String, Object>> acceptedGenericRegisterResponse() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "success", true,
                "message", GENERIC_REGISTER_ACCEPTED_MESSAGE
        ));
    }

    private ResponseEntity<Map<String, Object>> rateLimitedResponse(String message, long retryAfterSeconds) {
        long clamped = Math.max(1L, retryAfterSeconds);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(clamped))
                .body(Map.of(
                        "success", false,
                        "errorCode", "RATE_LIMITED",
                        "message", message != null ? message : "Too many requests. Please wait before trying again.",
                        "cooldownSeconds", clamped
                ));
    }

    private Map<String, Object> errorBody(String errorCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("errorCode", errorCode != null ? errorCode : "VALIDATION_ERROR");
        body.put("message", message != null ? message : "Request could not be processed.");
        return body;
    }

    private String mapFriendlyErrorCode(String code) {
        if (code == null) {
            return "Request could not be processed.";
        }
        return switch (code) {
            case "ACCOUNT_NOT_AUTHENTICATED" -> "Please sign in to your BOX Cinema account first.";
            case "RECENT_AUTH_REQUIRED" -> "Please confirm your current BOX Cinema password before continuing.";
            case "ACCOUNT_NOT_ACTIVE" -> "Only active accounts can link Google Sign-In.";
            case "LINK_TRANSACTION_EXPIRED" -> "Link authorization expired. Please try again.";
            case "LINK_TRANSACTION_ALREADY_USED" -> "This Google link transaction has already been used. Please start a fresh request.";
            case "OAUTH_STATE_INVALID" -> "Security verification (OAuth state) failed. Please retry.";
            case "OAUTH_NONCE_INVALID" -> "Security verification (OIDC nonce) failed. Please retry.";
            case "GOOGLE_EMAIL_UNVERIFIED" -> "Your Google account email must be verified with Google.";
            case "GOOGLE_EMAIL_CONFLICT" -> "This Google account email is already used as the primary email on another BOX Cinema account.";
            case "GOOGLE_EMAIL_MISMATCH" -> "Selected Google account could not be linked.";
            case "GOOGLE_IDENTITY_ALREADY_LINKED" -> "This Google account is already linked to another BOX Cinema account.";
            case "USER_ALREADY_HAS_DIFFERENT_GOOGLE" -> "Your account is already linked to a different Google identity.";
            case "ACCOUNT_PENDING_VERIFICATION" -> "Your BOX Cinema account email is not verified yet. Please verify your email OTP before signing in with Google.";
            case "ACCOUNT_LOCKED" -> "Your BOX Cinema account is currently locked. Please contact support.";
            case "ACCOUNT_SUSPENDED" -> "Your BOX Cinema account is suspended. Please contact support.";
            case "BOX_PASSWORD_AUTH_REQUIRED" -> "Please confirm your BOX Cinema password once to link Google Sign-In.";
            case "ACCOUNT_LINK_AUTH_REQUIRED" -> "Please verify your existing BOX Cinema account once to link Google Sign-In.";
            case "ACCOUNT_LINK_REQUIRED" -> "An account with this email already exists. Sign in with Email & Password, then click Connect Google in Account Settings.";
            case "CANNOT_UNLINK_LAST_AUTH_METHOD" -> "Please set a BOX Cinema password first before disconnecting Google.";
            case "SET_PASSWORD_REAUTH_REQUIRED" -> "Please verify your Google account again before creating a password.";
            case "GOOGLE_SUB_MISMATCH" -> "Selected Google account does not match your linked Google identity.";
            default -> "Unable to complete Google operation (" + code + ").";
        };
    }

    private HttpSession storePendingGoogleIdentity(
            HttpServletRequest request,
            GoogleOidcService.VerifiedGoogleIdentity identity,
            String mode,
            String targetEmail) {
        HttpSession pendingSession = request.getSession(true);
        pendingSession.setAttribute(SESSION_PENDING_GOOGLE_SUB, identity.googleSub());
        pendingSession.setAttribute(SESSION_PENDING_GOOGLE_EMAIL, identity.email());
        pendingSession.setAttribute(SESSION_PENDING_GOOGLE_NAME, identity.fullName());
        pendingSession.setAttribute(SESSION_PENDING_GOOGLE_VERIFIED, Boolean.TRUE);
        pendingSession.setAttribute(SESSION_PENDING_GOOGLE_EXPIRES_AT_MS, System.currentTimeMillis() + PENDING_GOOGLE_TTL_MS);
        pendingSession.setAttribute(SESSION_PENDING_GOOGLE_MODE, mode != null ? mode : "ONBOARDING");
        if (targetEmail != null && !targetEmail.isBlank()) {
            pendingSession.setAttribute(SESSION_PENDING_GOOGLE_TARGET_EMAIL, targetEmail.trim().toLowerCase(Locale.ROOT));
        } else {
            pendingSession.removeAttribute(SESSION_PENDING_GOOGLE_TARGET_EMAIL);
        }
        pendingSession.removeAttribute(SESSION_PENDING_GOOGLE_OTP_CHALLENGE_ID);
        return pendingSession;
    }

    private void issueInitialGoogleLinkOtp(
            HttpServletRequest request,
            HttpSession pendingSession,
            String targetEmail,
            RedirectAttributes redirectAttributes) {
        if (emailVerificationService == null || pendingSession == null) {
            return;
        }
        try {
            if (authRateLimitService != null) {
                authRateLimitService.checkOtpRateLimit(resolveClientIp(request));
            }
            EmailVerificationService.ResendResult otpRes = emailVerificationService.requestGoogleLinkEmailOtp(targetEmail, false);
            pendingSession.setAttribute(SESSION_PENDING_GOOGLE_OTP_CHALLENGE_ID, otpRes.challengeId());
            if (otpRes.emailSent()) {
                redirectAttributes.addFlashAttribute("successMessage",
                        "We sent a 6-digit verification code to " + otpRes.email()
                                + " to verify your email ownership once before linking Google Sign-In.");
            } else {
                redirectAttributes.addFlashAttribute("errorMessage",
                        "Email delivery is unavailable right now. Please click 'Resend Code' or verify using your BOX Cinema password.");
            }
            redirectAttributes.addFlashAttribute("cooldownSeconds", otpRes.cooldownSeconds());
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
    }

    private void populateCompleteProfileModel(
            HttpSession session,
            Model model,
            String enteredFullName,
            String enteredPhone,
            String enteredBoxEmail,
            boolean openLinkTab) {
        String pendingName = (String) session.getAttribute(SESSION_PENDING_GOOGLE_NAME);
        String pendingEmail = (String) session.getAttribute(SESSION_PENDING_GOOGLE_EMAIL);
        String targetEmail = (String) session.getAttribute(SESSION_PENDING_GOOGLE_TARGET_EMAIL);
        String challengeId = (String) session.getAttribute(SESSION_PENDING_GOOGLE_OTP_CHALLENGE_ID);

        model.addAttribute("activePage", "register");
        model.addAttribute("pendingGoogleName", pendingName != null ? pendingName : "");
        model.addAttribute("pendingGoogleEmail", pendingEmail != null ? pendingEmail : "");
        if (enteredFullName != null) {
            model.addAttribute("enteredFullName", enteredFullName);
        } else if (!model.containsAttribute("enteredFullName")) {
            model.addAttribute("enteredFullName", pendingName != null ? pendingName : "");
        }
        if (enteredPhone != null) {
            model.addAttribute("enteredPhone", enteredPhone);
        }
        String resolvedBoxEmail = (enteredBoxEmail != null && !enteredBoxEmail.isBlank())
                ? enteredBoxEmail
                : (targetEmail != null ? targetEmail : "");
        model.addAttribute("enteredBoxEmail", resolvedBoxEmail);
        model.addAttribute("activeChallengeId", challengeId != null ? challengeId : "");
        model.addAttribute("openLinkTab", openLinkTab || (challengeId != null && !challengeId.isBlank()));
        populateAuthShowcasePosters(model);
    }

    private void populateVerifyLinkModel(HttpSession session, Model model) {
        String mode = (String) session.getAttribute(SESSION_PENDING_GOOGLE_MODE);
        if (mode == null || mode.isBlank()) {
            mode = "LINK_CHOICE";
        }
        String pendingName = (String) session.getAttribute(SESSION_PENDING_GOOGLE_NAME);
        String pendingEmail = (String) session.getAttribute(SESSION_PENDING_GOOGLE_EMAIL);
        String targetEmail = resolvePendingTargetEmail(session);
        String challengeId = (String) session.getAttribute(SESSION_PENDING_GOOGLE_OTP_CHALLENGE_ID);
        boolean allowOtp = !"PASSWORD_REQUIRED".equals(mode);

        model.addAttribute("activePage", "login");
        model.addAttribute("verifyMode", mode);
        model.addAttribute("pendingGoogleName", pendingName != null ? pendingName : "");
        model.addAttribute("pendingGoogleEmail", pendingEmail != null ? pendingEmail : "");
        model.addAttribute("targetEmail", targetEmail != null ? targetEmail : "");
        model.addAttribute("activeChallengeId", challengeId != null ? challengeId : "");
        model.addAttribute("allowOtp", allowOtp);
        model.addAttribute("allowPassword", true);
        if (!model.containsAttribute("activeMethod")) {
            model.addAttribute("activeMethod", ("OTP_REQUIRED".equals(mode) && allowOtp) ? "otp" : "password");
        }
        populateAuthShowcasePosters(model);
    }

    private String resolvePendingTargetEmail(HttpSession session) {
        if (session == null) {
            return "";
        }
        Object target = session.getAttribute(SESSION_PENDING_GOOGLE_TARGET_EMAIL);
        if (target instanceof String s && !s.isBlank()) {
            return s;
        }
        Object googleEmail = session.getAttribute(SESSION_PENDING_GOOGLE_EMAIL);
        return googleEmail instanceof String s ? s : "";
    }

    private String resolvePendingLinkErrorMessage(Exception ex) {
        if (ex == null || ex.getMessage() == null || ex.getMessage().isBlank()) {
            return "Verification failed. Please check your credentials and try again.";
        }
        String msg = ex.getMessage();
        if (msg.startsWith("PENDING_VERIFICATION:")) {
            return "Your BOX Cinema account email has not completed initial activation OTP verification yet. Please check your email and verify your account first.";
        }
        return msg;
    }

    private String extractSuffixAfterColon(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        int idx = value.indexOf(':');
        if (idx >= 0 && idx + 1 < value.length()) {
            String suffix = value.substring(idx + 1).trim();
            if (!suffix.isBlank()) {
                return suffix;
            }
        }
        return fallback;
    }

    private boolean isAjaxOrJsonRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        String xrw = request.getHeader("X-Requested-With");
        String accept = request.getHeader("Accept");
        return "XMLHttpRequest".equalsIgnoreCase(xrw)
                || (accept != null && accept.toLowerCase(Locale.ROOT).contains("application/json"));
    }

    private boolean hasValidPendingGoogleSession(HttpSession session) {
        if (session == null) {
            return false;
        }
        Object subObj = session.getAttribute(SESSION_PENDING_GOOGLE_SUB);
        Object emailObj = session.getAttribute(SESSION_PENDING_GOOGLE_EMAIL);
        Object verifiedObj = session.getAttribute(SESSION_PENDING_GOOGLE_VERIFIED);
        Object expObj = session.getAttribute(SESSION_PENDING_GOOGLE_EXPIRES_AT_MS);
        if (!(subObj instanceof String sub) || sub.isBlank()
                || !(emailObj instanceof String email) || email.isBlank()
                || !Boolean.TRUE.equals(verifiedObj)
                || !(expObj instanceof Long expMs)) {
            return false;
        }
        return System.currentTimeMillis() <= expMs;
    }

    private void clearPendingGoogleSession(HttpSession session) {
        if (session == null) {
            return;
        }
        session.removeAttribute(SESSION_PENDING_GOOGLE_SUB);
        session.removeAttribute(SESSION_PENDING_GOOGLE_EMAIL);
        session.removeAttribute(SESSION_PENDING_GOOGLE_NAME);
        session.removeAttribute(SESSION_PENDING_GOOGLE_VERIFIED);
        session.removeAttribute(SESSION_PENDING_GOOGLE_EXPIRES_AT_MS);
        session.removeAttribute(SESSION_PENDING_GOOGLE_MODE);
        session.removeAttribute(SESSION_PENDING_GOOGLE_TARGET_EMAIL);
        session.removeAttribute(SESSION_PENDING_GOOGLE_OTP_CHALLENGE_ID);
    }

    private void syncSessionHasPassword(HttpServletRequest request, UserSummaryDto currentUser, boolean hasPassword) {
        if (currentUser != null) {
            currentUser.setHasPassword(hasPassword);
        }
        HttpSession session = request != null ? request.getSession(false) : null;
        if (session != null && session.getAttribute("currentUser") instanceof UserSummaryDto sessionUser) {
            sessionUser.setHasPassword(hasPassword);
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof BoxCinemaUserDetails details && details.getUserSummary() != null) {
            details.getUserSummary().setHasPassword(hasPassword);
        }
    }

    private void syncSessionGoogleLinked(HttpServletRequest request, UserSummaryDto currentUser, boolean linked) {
        if (currentUser != null) {
            currentUser.setGoogleLinked(linked);
        }
        HttpSession session = request != null ? request.getSession(false) : null;
        if (session != null && session.getAttribute("currentUser") instanceof UserSummaryDto sessionUser) {
            sessionUser.setGoogleLinked(linked);
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof BoxCinemaUserDetails details && details.getUserSummary() != null) {
            details.getUserSummary().setGoogleLinked(linked);
        }
    }

    private void validateGoogleLinkAccountEligibility(Long userId) {
        if (userRepository == null || userId == null) {
            return;
        }
        Optional<User> dbUserOpt = userRepository.findById(userId);
        if (dbUserOpt.isEmpty() || !"ACTIVE".equalsIgnoreCase(dbUserOpt.get().getAccountStatus())) {
            throw AuthException.forbidden("ACCOUNT_NOT_ACTIVE", "Only active accounts can link Google sign-in.");
        }
        User dbUser = dbUserOpt.get();
        if (dbUser.getPasswordHash() == null || dbUser.getPasswordHash().isBlank()) {
            throw AuthException.forbidden("RECENT_AUTH_REQUIRED", "Please set a BOX Cinema password first before connecting Google.");
        }
        if (dbUser.getGoogleId() != null && !dbUser.getGoogleId().isBlank()) {
            throw AuthException.conflict("USER_ALREADY_HAS_DIFFERENT_GOOGLE", "A Google account is already linked to your profile.");
        }
    }

    private void verifyReauthOrPasswordForLink(HttpServletRequest request, Long userId, String password) {
        if (password != null && !password.isBlank()) {
            userService.reauthenticatePassword(userId, password);
            HttpSession activeSession = request.getSession(true);
            markReauthVerified(activeSession, userId);
            mintLinkTransaction(activeSession, userId);
            return;
        }
        HttpSession existingSession = request.getSession(false);
        if (hasValidLinkTransaction(existingSession, userId)) {
            return;
        }
        if (hasRecentReauth(existingSession, userId, REAUTH_VALIDITY_MS)) {
            mintLinkTransaction(existingSession, userId);
            return;
        }
        throw new ReauthRequiredException("Please confirm your current BOX Cinema password before connecting Google.");
    }

    private String resolveMissingLinkTxErrorCode(HttpSession session) {
        if (session == null) {
            return "RECENT_AUTH_REQUIRED";
        }
        if (Boolean.TRUE.equals(session.getAttribute(SESSION_LINK_TX_USED))) {
            return "LINK_TRANSACTION_ALREADY_USED";
        }
        Object expObj = session.getAttribute(SESSION_LINK_TX_EXPIRES_AT_MS);
        if (expObj instanceof Long expMs && System.currentTimeMillis() > expMs) {
            return "LINK_TRANSACTION_EXPIRED";
        }
        return "RECENT_AUTH_REQUIRED";
    }

    private void ensureCanUnlinkGoogle(Long userId) {
        if (userService == null || userId == null) {
            return;
        }
        AuthMethodsDto methods = userService.getAuthMethods(userId);
        if (methods != null && !methods.isHasPassword()) {
            throw AuthException.conflict(
                    "CANNOT_UNLINK_LAST_AUTH_METHOD",
                    "Please set a BOX Cinema password first before disconnecting Google."
            );
        }
    }

    private void verifyReauthForUnlink(HttpSession session, Long userId, String password) {
        if (password != null && !password.isBlank()) {
            userService.reauthenticatePassword(userId, password);
            return;
        }
        if (!hasRecentReauth(session, userId, REAUTH_VALIDITY_MS)) {
            throw new ReauthRequiredException("Please confirm your current BOX Cinema password before unlinking Google.");
        }
    }

    private void markReauthVerified(HttpSession session, Long userId) {
        if (session == null || userId == null) {
            return;
        }
        session.setAttribute(SESSION_REAUTH_USER_ID, userId);
        session.setAttribute(SESSION_REAUTH_AT_MS, System.currentTimeMillis());
    }

    private boolean hasRecentReauth(HttpSession session, Long userId, long maxAgeMs) {
        if (session == null || userId == null) {
            return false;
        }
        Object uidObj = session.getAttribute(SESSION_REAUTH_USER_ID);
        Object tsObj = session.getAttribute(SESSION_REAUTH_AT_MS);
        if (!(uidObj instanceof Long uid) || !uid.equals(userId) || !(tsObj instanceof Long tsMs)) {
            return false;
        }
        return System.currentTimeMillis() - tsMs <= maxAgeMs;
    }

    private void clearReauth(HttpSession session) {
        if (session != null) {
            session.removeAttribute(SESSION_REAUTH_USER_ID);
            session.removeAttribute(SESSION_REAUTH_AT_MS);
        }
    }

    private UserSummaryDto resolveAuthenticatedUser(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof BoxCinemaUserDetails details) {
            return details.getUserSummary();
        }
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("currentUser") instanceof UserSummaryDto dto) {
            return dto;
        }
        return null;
    }

    private String resolveRoleRedirect(String role) {
        if ("ADMIN".equalsIgnoreCase(role)) {
            return "redirect:/admin";
        }
        if ("MANAGER".equalsIgnoreCase(role)) {
            return "redirect:/manager";
        }
        if ("STAFF".equalsIgnoreCase(role)) {
            return "redirect:/staff";
        }
        return "redirect:/";
    }

    private boolean isGoogleOAuthConfigured() {
        return googleOidcService != null && googleOidcService.isConfigured();
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private void expireCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private String resolveClientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    private String urlEncode(String val) {
        return URLEncoder.encode(val != null ? val : "", StandardCharsets.UTF_8);
    }

    private void populateAuthShowcasePosters(Model model) {
        if (movieService == null) {
            model.addAttribute("authPosters", Collections.emptyList());
            return;
        }
        List<String> posters = movieService.getAllActiveMovies().stream()
                .filter(m -> "NOW_SHOWING".equalsIgnoreCase(m.getStatus())
                        || "COMING_SOON".equalsIgnoreCase(m.getStatus()))
                .map(Movie::getPosterUrl)
                .filter(url -> url != null && !url.isBlank())
                .distinct()
                .limit(12)
                .collect(Collectors.toList());
        model.addAttribute("authPosters", posters);
    }

    private String getBaseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String host = request.getServerName();
        int port = request.getServerPort();
        if (("http".equalsIgnoreCase(scheme) && port == 80) || ("https".equalsIgnoreCase(scheme) && port == 443)) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + port;
    }
}
