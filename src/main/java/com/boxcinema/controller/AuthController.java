package com.boxcinema.controller;

import com.boxcinema.dto.LoginRequestDto;
import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.entity.Movie;
import com.boxcinema.entity.User;
import com.boxcinema.repository.UserRepository;
import com.boxcinema.security.SecuritySessionHelper;
import com.boxcinema.service.MovieService;
import com.boxcinema.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Controller
public class AuthController {

    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final MovieService movieService;
    private final SecurityContextRepository securityContextRepository;
    private final UserRepository userRepository;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${app.oauth2.google.client-id:}")
    private String googleClientId;

    @Value("${app.oauth2.google.client-secret:}")
    private String googleClientSecret;

    @Value("${app.oauth2.google.redirect-uri:}")
    private String googleRedirectUri;

    @Value("${app.oauth2.facebook.client-id:}")
    private String facebookClientId;

    @Value("${app.oauth2.facebook.client-secret:}")
    private String facebookClientSecret;

    @Value("${app.oauth2.facebook.redirect-uri:}")
    private String facebookRedirectUri;

    @Autowired
    public AuthController(UserService userService,
                          ObjectMapper objectMapper,
                          @Autowired(required = false) MovieService movieService,
                          @Autowired(required = false) SecurityContextRepository securityContextRepository,
                          @Autowired(required = false) UserRepository userRepository) {
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.movieService = movieService;
        this.securityContextRepository = securityContextRepository;
        this.userRepository = userRepository;
    }

    public AuthController(UserService userService, ObjectMapper objectMapper) {
        this(userService, objectMapper, null, null, null);
    }

    @GetMapping("/login")
    public String loginPage(Model model) {
        model.addAttribute("activePage", "login");
        model.addAttribute("googleOAuthConfigured", isConfigured(googleClientId));
        model.addAttribute("facebookOAuthConfigured", isConfigured(facebookClientId));
        populateAuthShowcasePosters(model);
        return "auth/login";
    }

    @GetMapping({"/register", "/signup"})
    public String registerPage(Model model) {
        model.addAttribute("activePage", "register");
        model.addAttribute("googleOAuthConfigured", isConfigured(googleClientId));
        model.addAttribute("facebookOAuthConfigured", isConfigured(facebookClientId));
        if (!model.containsAttribute("formData")) {
            model.addAttribute("formData", new RegisterRequestDto());
        }
        populateAuthShowcasePosters(model);
        return "auth/register";
    }

    /**
     * Phase 1: Direct registration (No OTP).
     * Account is created in ACTIVE status and redirects to login with success flash.
     */
    @PostMapping({"/register", "/signup"})
    public String processRegister(
            @ModelAttribute RegisterRequestDto registerRequest,
            Model model,
            RedirectAttributes redirectAttributes) {

        try {
            UserSummaryDto newUser = userService.registerCustomer(registerRequest);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Registration successful! Please sign in with your email and password.");
            redirectAttributes.addFlashAttribute("enteredEmail", newUser.getEmail());
            return "redirect:/login?registered=true";
        } catch (IllegalArgumentException ex) {
            model.addAttribute("activePage", "register");
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("formData", registerRequest);
            model.addAttribute("googleOAuthConfigured", isConfigured(googleClientId));
            model.addAttribute("facebookOAuthConfigured", isConfigured(facebookClientId));
            populateAuthShowcasePosters(model);
            return "auth/register";
        }
    }

    /**
     * REST API for Registration (JSON payload).
     */
    @PostMapping("/api/auth/register")
    @ResponseBody
    public ResponseEntity<?> apiRegister(@RequestBody RegisterRequestDto registerRequest) {
        try {
            UserSummaryDto user = userService.registerCustomer(registerRequest);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Registration successful. Please sign in.",
                    "user", user
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        }
    }

    /**
     * REST API for Login (JSON payload).
     */
    @PostMapping("/api/auth/login")
    @ResponseBody
    public ResponseEntity<?> apiLogin(
            @RequestBody LoginRequestDto loginRequest,
            HttpServletRequest request,
            HttpServletResponse response) {
        try {
            UserSummaryDto user = userService.authenticate(loginRequest.getEmail(), loginRequest.getPassword());
            SecuritySessionHelper.authenticateUserSession(user, request, response, securityContextRepository);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Login successful.",
                    "user", user
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        }
    }

    /**
     * REST API for retrieving current authenticated user.
     */
    @GetMapping("/api/auth/me")
    @ResponseBody
    public ResponseEntity<?> apiMe(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        UserSummaryDto user = (session != null) ? (UserSummaryDto) session.getAttribute("currentUser") : null;
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

    /**
     * REST API for Logout.
     */
    @PostMapping("/api/auth/logout")
    @ResponseBody
    public ResponseEntity<?> apiLogout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Logged out successfully."
        ));
    }

    @PostMapping("/auth/social")
    public String processSocialLogin(
            @RequestParam("provider") String provider,
            @RequestParam(value = "providerId", required = false) String providerId,
            @RequestParam("email") String email,
            @RequestParam(value = "fullName", required = false) String fullName,
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes) {

        try {
            UserSummaryDto user = userService.authenticateSocial(provider, providerId, email, fullName);
            SecuritySessionHelper.authenticateUserSession(user, request, response, securityContextRepository);
            return "redirect:/";
        } catch (IllegalStateException stateEx) {
            String msg = stateEx.getMessage();
            if (msg != null && msg.startsWith("ACCOUNT_EXISTS_NEEDS_LINK:")) {
                redirectAttributes.addFlashAttribute(
                        "errorMessage",
                        "An account with this email already exists. Please sign in with your email and password, then link Google in Account Settings."
                );
                return "redirect:/login";
            } else if (msg != null && msg.startsWith("GOOGLE_NEEDS_PROFILE:")) {
                String[] parts = msg.split(":::");
                HttpSession session = request.getSession(true);
                session.setAttribute("pendingGoogleSub", parts.length > 1 ? parts[1] : providerId);
                session.setAttribute("pendingGoogleEmail", parts.length > 2 ? parts[2] : email);
                session.setAttribute("pendingGoogleName", parts.length > 3 ? parts[3] : fullName);
                return "redirect:/auth/google/complete-profile";
            }
            redirectAttributes.addFlashAttribute("errorMessage", stateEx.getMessage());
            return "redirect:/login";
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/login";
        }
    }

    @GetMapping("/auth/oauth2/{provider}")
    public String redirectToOAuth2Provider(
            @PathVariable("provider") String provider,
            @RequestParam(value = "action", required = false) String action,
            HttpServletRequest request) {

        String cleanProvider = provider.trim().toLowerCase();
        String baseUrl = getBaseUrl(request);

        if ("google".equals(cleanProvider) && isConfigured(googleClientId)) {
            String resolvedRedirect = isConfigured(googleRedirectUri)
                    ? googleRedirectUri.trim()
                    : baseUrl + "/login/oauth2/code/google";
            String redirectUri = URLEncoder.encode(resolvedRedirect, StandardCharsets.UTF_8);
            String scope = URLEncoder.encode("openid email profile", StandardCharsets.UTF_8);

            String authUrl = "redirect:https://accounts.google.com/o/oauth2/v2/auth?client_id="
                    + URLEncoder.encode(googleClientId.trim(), StandardCharsets.UTF_8)
                    + "&redirect_uri=" + redirectUri
                    + "&response_type=code&scope=" + scope
                    + "&prompt=select_account";

            if (action != null && !action.isBlank()) {
                authUrl += "&state=" + URLEncoder.encode(action.trim(), StandardCharsets.UTF_8);
            }
            return authUrl;
        }

        if ("facebook".equals(cleanProvider) && isConfigured(facebookClientId)) {
            String resolvedRedirect = isConfigured(facebookRedirectUri)
                    ? facebookRedirectUri.trim()
                    : baseUrl + "/login/oauth2/code/facebook";
            String redirectUri = URLEncoder.encode(resolvedRedirect, StandardCharsets.UTF_8);
            String scope = URLEncoder.encode("public_profile,email", StandardCharsets.UTF_8);
            return "redirect:https://www.facebook.com/v19.0/dialog/oauth?client_id="
                    + URLEncoder.encode(facebookClientId.trim(), StandardCharsets.UTF_8)
                    + "&redirect_uri=" + redirectUri
                    + "&response_type=code&scope=" + scope;
        }

        return "redirect:/login?social=" + URLEncoder.encode(cleanProvider, StandardCharsets.UTF_8);
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

        if (error != null || code == null || code.isBlank()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Social sign-in was cancelled or failed.");
            return "redirect:/login";
        }

        String cleanProvider = provider.trim().toLowerCase();
        String baseUrl = getBaseUrl(request);

        try {
            if ("google".equals(cleanProvider) && isConfigured(googleClientId) && isConfigured(googleClientSecret)) {
                String redirectUri = isConfigured(googleRedirectUri)
                        ? googleRedirectUri.trim()
                        : baseUrl + request.getRequestURI();
                String tokenBody = "code=" + URLEncoder.encode(code, StandardCharsets.UTF_8)
                        + "&client_id=" + URLEncoder.encode(googleClientId.trim(), StandardCharsets.UTF_8)
                        + "&client_secret=" + URLEncoder.encode(googleClientSecret.trim(), StandardCharsets.UTF_8)
                        + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                        + "&grant_type=authorization_code";

                HttpRequest tokenReq = HttpRequest.newBuilder()
                        .uri(URI.create("https://oauth2.googleapis.com/token"))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(tokenBody))
                        .build();

                HttpResponse<String> tokenRes = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString());
                JsonNode tokenJson = objectMapper.readTree(tokenRes.body());
                String accessToken = tokenJson.path("access_token").asText("");
                if (accessToken.isBlank()) {
                    throw new IllegalArgumentException("Failed to obtain access token from Google.");
                }

                HttpRequest userReq = HttpRequest.newBuilder()
                        .uri(URI.create("https://www.googleapis.com/oauth2/v3/userinfo"))
                        .header("Authorization", "Bearer " + accessToken)
                        .GET()
                        .build();

                HttpResponse<String> userRes = httpClient.send(userReq, HttpResponse.BodyHandlers.ofString());
                JsonNode userJson = objectMapper.readTree(userRes.body());
                String sub = userJson.path("sub").asText("");
                String email = userJson.path("email").asText("");
                String name = userJson.path("name").asText("");

                // Phase 4: Handle Google Account Linking from Profile
                if (state != null && state.startsWith("link")) {
                    HttpSession session = request.getSession(false);
                    UserSummaryDto currentUser = (session != null) ? (UserSummaryDto) session.getAttribute("currentUser") : null;
                    if (currentUser == null) {
                        redirectAttributes.addFlashAttribute("errorMessage", "You must be signed in to link a Google account.");
                        return "redirect:/login";
                    }
                    try {
                        userService.linkGoogleAccount(currentUser.getUserId(), sub, email);
                        redirectAttributes.addFlashAttribute("successMessage", "Google account connected successfully!");
                        return "redirect:/profile";
                    } catch (IllegalArgumentException linkEx) {
                        redirectAttributes.addFlashAttribute("errorMessage", linkEx.getMessage());
                        return "redirect:/profile";
                    }
                }

                // Phase 3: Google Login / Registration
                try {
                    UserSummaryDto user = userService.authenticateSocial("GOOGLE", sub, email, name);
                    SecuritySessionHelper.authenticateUserSession(user, request, response, securityContextRepository);
                    return "redirect:/";
                } catch (IllegalStateException stateEx) {
                    String msg = stateEx.getMessage();
                    if (msg != null && msg.startsWith("ACCOUNT_EXISTS_NEEDS_LINK:")) {
                        redirectAttributes.addFlashAttribute(
                                "errorMessage",
                                "An account with email (" + email + ") already exists. Please sign in with your email and password, then link your Google account in Account Settings."
                        );
                        return "redirect:/login";
                    } else if (msg != null && msg.startsWith("GOOGLE_NEEDS_PROFILE:")) {
                        String[] parts = msg.split(":::");
                        HttpSession session = request.getSession(true);
                        session.setAttribute("pendingGoogleSub", parts.length > 1 ? parts[1] : sub);
                        session.setAttribute("pendingGoogleEmail", parts.length > 2 ? parts[2] : email);
                        session.setAttribute("pendingGoogleName", parts.length > 3 ? parts[3] : name);
                        return "redirect:/auth/google/complete-profile";
                    }
                    throw stateEx;
                }
            }

            if ("facebook".equals(cleanProvider) && isConfigured(facebookClientId) && isConfigured(facebookClientSecret)) {
                String redirectUri = baseUrl + "/auth/oauth2/callback/facebook";
                String tokenUrl = "https://graph.facebook.com/v19.0/oauth/access_token?client_id="
                        + URLEncoder.encode(facebookClientId, StandardCharsets.UTF_8)
                        + "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                        + "&client_secret=" + URLEncoder.encode(facebookClientSecret, StandardCharsets.UTF_8)
                        + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8);

                HttpRequest tokenReq = HttpRequest.newBuilder().uri(URI.create(tokenUrl)).GET().build();
                HttpResponse<String> tokenRes = httpClient.send(tokenReq, HttpResponse.BodyHandlers.ofString());
                JsonNode tokenJson = objectMapper.readTree(tokenRes.body());
                String accessToken = tokenJson.path("access_token").asText("");
                if (accessToken.isBlank()) {
                    throw new IllegalArgumentException("Failed to obtain access token from Facebook.");
                }

                String profileUrl = "https://graph.facebook.com/me?fields=id,name,email&access_token="
                        + URLEncoder.encode(accessToken, StandardCharsets.UTF_8);
                HttpRequest userReq = HttpRequest.newBuilder().uri(URI.create(profileUrl)).GET().build();
                HttpResponse<String> userRes = httpClient.send(userReq, HttpResponse.BodyHandlers.ofString());
                JsonNode userJson = objectMapper.readTree(userRes.body());
                String fbId = userJson.path("id").asText("");
                String name = userJson.path("name").asText("Facebook User");
                String email = userJson.path("email").asText("");
                if (email.isBlank() && !fbId.isBlank()) {
                    email = "fb_" + fbId + "@facebook.boxcinema.com";
                }

                UserSummaryDto user = userService.authenticateSocial("FACEBOOK", fbId, email, name);
                SecuritySessionHelper.authenticateUserSession(user, request, response, securityContextRepository);
                return "redirect:/";
            }
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Social authentication error: " + ex.getMessage());
            return "redirect:/login";
        }

        return "redirect:/login";
    }

    /**
     * Phase 3: Complete profile form for first-time Google user.
     */
    @GetMapping("/auth/google/complete-profile")
    public String completeProfilePage(HttpSession session, Model model, RedirectAttributes redirectAttributes) {
        String sub = (String) session.getAttribute("pendingGoogleSub");
        String email = (String) session.getAttribute("pendingGoogleEmail");
        String name = (String) session.getAttribute("pendingGoogleName");
        if (sub == null || email == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "No pending Google sign-in session found.");
            return "redirect:/login";
        }
        model.addAttribute("googleEmail", email);
        model.addAttribute("googleName", name);
        model.addAttribute("activePage", "register");
        populateAuthShowcasePosters(model);
        return "auth/complete-profile";
    }

    @PostMapping("/auth/google/complete-profile")
    public String processCompleteProfile(
            @RequestParam("phone") String phone,
            HttpSession session,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model,
            RedirectAttributes redirectAttributes) {

        String sub = (String) session.getAttribute("pendingGoogleSub");
        String email = (String) session.getAttribute("pendingGoogleEmail");
        String name = (String) session.getAttribute("pendingGoogleName");
        if (sub == null || email == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Session expired. Please sign in with Google again.");
            return "redirect:/login";
        }

        try {
            UserSummaryDto newUser = userService.registerGoogleCustomer(sub, email, name, phone);
            session.removeAttribute("pendingGoogleSub");
            session.removeAttribute("pendingGoogleEmail");
            session.removeAttribute("pendingGoogleName");

            SecuritySessionHelper.authenticateUserSession(newUser, request, response, securityContextRepository);
            redirectAttributes.addFlashAttribute("successMessage", "Welcome to BOX Cinema, " + newUser.getFullName() + "! Your account has been created.");
            return "redirect:/";
        } catch (IllegalArgumentException ex) {
            model.addAttribute("googleEmail", email);
            model.addAttribute("googleName", name);
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("enteredPhone", phone);
            model.addAttribute("activePage", "register");
            populateAuthShowcasePosters(model);
            return "auth/complete-profile";
        }
    }

    /**
     * Phase 4: Account Settings & Linked Accounts.
     */
    @GetMapping("/profile")
    public String profilePage(HttpServletRequest request, Model model) {
        HttpSession session = request.getSession(false);
        UserSummaryDto currentUser = (session != null) ? (UserSummaryDto) session.getAttribute("currentUser") : null;
        if (currentUser == null) {
            return "redirect:/login";
        }

        if (userRepository != null) {
            Optional<User> userOpt = userRepository.findById(currentUser.getUserId());
            if (userOpt.isPresent()) {
                User user = userOpt.get();
                model.addAttribute("user", user);
                model.addAttribute("hasPassword", user.getPasswordHash() != null && !user.getPasswordHash().isBlank());
                model.addAttribute("isGoogleLinked", user.getGoogleId() != null && !user.getGoogleId().isBlank());
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

    @GetMapping("/auth/account/link/google")
    public String linkGoogleStart(HttpServletRequest request) {
        return redirectToOAuth2Provider("google", "link", request);
    }

    @PostMapping("/auth/account/unlink/google")
    public String unlinkGoogle(HttpServletRequest request, RedirectAttributes redirectAttributes) {
        HttpSession session = request.getSession(false);
        UserSummaryDto currentUser = (session != null) ? (UserSummaryDto) session.getAttribute("currentUser") : null;
        if (currentUser == null) {
            return "redirect:/login";
        }

        try {
            userService.unlinkGoogleAccount(currentUser.getUserId());
            redirectAttributes.addFlashAttribute("successMessage", "Google account unlinked successfully.");
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/profile";
    }

    @PostMapping("/forgot-password")
    public String processForgotPassword(
            @RequestParam("email") String email,
            @RequestParam("newPassword") String newPassword,
            @RequestParam("confirmPassword") String confirmPassword,
            RedirectAttributes redirectAttributes) {

        try {
            userService.resetPassword(email, newPassword, confirmPassword);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Password reset successfully! Please sign in with your new password.");
            redirectAttributes.addFlashAttribute("enteredEmail", email.trim());
            return "redirect:/login?reset=true";
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/login";
        }
    }

    private boolean isConfigured(String val) {
        return val != null && !val.isBlank() && !val.startsWith("YOUR_");
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
