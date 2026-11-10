package com.boxcinema.controller;

import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.service.UserService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Value;
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

@Controller
public class AuthController {

    private final UserService userService;
    private final ObjectMapper objectMapper;
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

    public AuthController(UserService userService, ObjectMapper objectMapper) {
        this.userService = userService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/login")
    public String loginPage(Model model) {
        model.addAttribute("activePage", "login");
        model.addAttribute("googleOAuthConfigured", isConfigured(googleClientId));
        model.addAttribute("facebookOAuthConfigured", isConfigured(facebookClientId));
        return "auth/login";
    }

    @PostMapping("/login")
    public String processLogin(
            @RequestParam(value = "username", required = false) String username,
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "password", required = false) String password,
            @RequestParam(value = "remember-me", required = false) String rememberMe,
            HttpSession session,
            Model model,
            RedirectAttributes redirectAttributes) {

        String loginEmail = (username != null && !username.isBlank()) ? username : email;

        try {
            UserSummaryDto user = userService.authenticate(loginEmail, password);
            session.setAttribute("currentUser", user);
            if (rememberMe != null) {
                session.setMaxInactiveInterval(7 * 24 * 60 * 60);
            }
            return "redirect:/";
        } catch (IllegalStateException unverifiedEx) {
            String targetEmail = loginEmail != null ? loginEmail.trim().toLowerCase() : "";
            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Please verify your Gmail address before signing in. A new 6-digit OTP code has been generated.");
            return "redirect:/verify-email?email=" + URLEncoder.encode(targetEmail, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("activePage", "login");
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("enteredEmail", loginEmail != null ? loginEmail.trim() : "");
            model.addAttribute("googleOAuthConfigured", isConfigured(googleClientId));
            model.addAttribute("facebookOAuthConfigured", isConfigured(facebookClientId));
            return "auth/login";
        }
    }

    @GetMapping({"/register", "/signup"})
    public String registerPage(Model model) {
        model.addAttribute("activePage", "register");
        model.addAttribute("googleOAuthConfigured", isConfigured(googleClientId));
        model.addAttribute("facebookOAuthConfigured", isConfigured(facebookClientId));
        if (!model.containsAttribute("formData")) {
            model.addAttribute("formData", new RegisterRequestDto());
        }
        return "auth/register";
    }

    @PostMapping({"/register", "/signup"})
    public String processRegister(
            @ModelAttribute RegisterRequestDto registerRequest,
            Model model,
            RedirectAttributes redirectAttributes) {

        try {
            UserSummaryDto newUser = userService.registerCustomer(registerRequest);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "A 6-digit verification code has been sent to " + newUser.getEmail() + ". Please verify your Gmail to activate your account.");
            return "redirect:/verify-email?email=" + URLEncoder.encode(newUser.getEmail(), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            model.addAttribute("activePage", "register");
            model.addAttribute("errorMessage", ex.getMessage());
            model.addAttribute("formData", registerRequest);
            model.addAttribute("googleOAuthConfigured", isConfigured(googleClientId));
            model.addAttribute("facebookOAuthConfigured", isConfigured(facebookClientId));
            return "auth/register";
        }
    }

    @GetMapping("/verify-email")
    public String verifyEmailPage(
            @RequestParam(value = "email", required = false) String email,
            Model model) {

        String cleanEmail = (email != null) ? email.trim().toLowerCase() : "";
        model.addAttribute("activePage", "register");
        model.addAttribute("verifyEmail", cleanEmail);
        userService.getPendingOtpPreview(cleanEmail)
                .ifPresent(otp -> model.addAttribute("demoOtpCode", otp));
        return "auth/verify-email";
    }

    @PostMapping("/verify-email")
    public String processVerifyEmail(
            @RequestParam("email") String email,
            @RequestParam("otpCode") String otpCode,
            Model model,
            RedirectAttributes redirectAttributes) {

        String cleanEmail = (email != null) ? email.trim().toLowerCase() : "";
        try {
            UserSummaryDto verifiedUser = userService.verifyEmailOtp(cleanEmail, otpCode);
            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Gmail verified successfully! Welcome to BOX Cinema, " + verifiedUser.getFullName() + ". Please sign in.");
            redirectAttributes.addFlashAttribute("enteredEmail", verifiedUser.getEmail());
            return "redirect:/login?verified=true";
        } catch (IllegalArgumentException ex) {
            model.addAttribute("activePage", "register");
            model.addAttribute("verifyEmail", cleanEmail);
            model.addAttribute("errorMessage", ex.getMessage());
            userService.getPendingOtpPreview(cleanEmail)
                    .ifPresent(otp -> model.addAttribute("demoOtpCode", otp));
            return "auth/verify-email";
        }
    }

    @PostMapping("/verify-email/resend")
    public String resendVerificationOtp(
            @RequestParam("email") String email,
            RedirectAttributes redirectAttributes) {

        String cleanEmail = (email != null) ? email.trim().toLowerCase() : "";
        try {
            boolean sentViaSmtp = userService.resendVerificationOtp(cleanEmail);
            String msg = sentViaSmtp
                    ? "A new 6-digit verification code has been sent to your Gmail (" + cleanEmail + ")."
                    : "A new 6-digit verification code has been generated for " + cleanEmail + ".";
            redirectAttributes.addFlashAttribute("successMessage", msg);
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }
        return "redirect:/verify-email?email=" + URLEncoder.encode(cleanEmail, StandardCharsets.UTF_8);
    }

    @PostMapping("/auth/social")
    public String processSocialLogin(
            @RequestParam("provider") String provider,
            @RequestParam(value = "providerId", required = false) String providerId,
            @RequestParam("email") String email,
            @RequestParam(value = "fullName", required = false) String fullName,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        try {
            UserSummaryDto user = userService.authenticateSocial(provider, providerId, email, fullName);
            session.setAttribute("currentUser", user);
            return "redirect:/";
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
            return "redirect:/login";
        }
    }

    @GetMapping("/auth/oauth2/{provider}")
    public String redirectToOAuth2Provider(
            @PathVariable("provider") String provider,
            HttpServletRequest request) {

        String cleanProvider = provider.trim().toLowerCase();
        String baseUrl = getBaseUrl(request);

        if ("google".equals(cleanProvider) && isConfigured(googleClientId)) {
            String resolvedRedirect = isConfigured(googleRedirectUri)
                    ? googleRedirectUri.trim()
                    : baseUrl + "/login/oauth2/code/google";
            String redirectUri = URLEncoder.encode(resolvedRedirect, StandardCharsets.UTF_8);
            String scope = URLEncoder.encode("openid email profile", StandardCharsets.UTF_8);
            return "redirect:https://accounts.google.com/o/oauth2/v2/auth?client_id="
                    + URLEncoder.encode(googleClientId.trim(), StandardCharsets.UTF_8)
                    + "&redirect_uri=" + redirectUri
                    + "&response_type=code&scope=" + scope
                    + "&prompt=select_account";
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
            @RequestParam(value = "error", required = false) String error,
            HttpServletRequest request,
            HttpSession session,
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

                UserSummaryDto user = userService.authenticateSocial("GOOGLE", sub, email, name);
                session.setAttribute("currentUser", user);
                return "redirect:/";
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
                session.setAttribute("currentUser", user);
                return "redirect:/";
            }
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Social authentication error: " + ex.getMessage());
            return "redirect:/login";
        }

        return "redirect:/login";
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

    @RequestMapping(value = "/logout", method = {RequestMethod.GET, RequestMethod.POST})
    public String logout(HttpSession session, RedirectAttributes redirectAttributes) {
        session.invalidate();
        redirectAttributes.addFlashAttribute("successMessage", "You have been signed out safely.");
        return "redirect:/login?logout=true";
    }

    private boolean isConfigured(String val) {
        return val != null && !val.isBlank() && !val.startsWith("YOUR_");
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
