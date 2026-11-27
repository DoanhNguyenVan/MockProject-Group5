package com.boxcinema.common.config;

import com.boxcinema.common.constant.SessionConstants;
import com.boxcinema.common.repository.UserRepository;
import com.boxcinema.admin.service.UserActivityLogService;
import com.boxcinema.common.security.ActivityLogInterceptor;
import com.boxcinema.common.security.BoxCinemaAuthenticationProvider;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import com.boxcinema.common.security.SecuritySessionHelper;
import com.boxcinema.common.security.SessionValidationFilter;
import com.boxcinema.common.security.UnverifiedEmailAuthenticationException;
import com.boxcinema.auth.service.AuthAuditService;
import com.boxcinema.auth.service.AuthRateLimitService;
import com.boxcinema.common.service.PasswordHashService;
import com.boxcinema.common.service.SessionManagementService;
import com.boxcinema.auth.service.UserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder(ObjectProvider<PasswordHashService> passwordHashServiceProvider) {
        return passwordHashServiceProvider.getIfAvailable(PasswordHashService::new);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:8080,http://127.0.0.1:8080}") String allowedOriginsRaw) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !"*".equals(s))
                .toList();
        config.setAllowedOrigins(origins.isEmpty() ? List.of("http://localhost:8080") : origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN", "X-Requested-With"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectProvider<UserService> userServiceProvider,
            ObjectProvider<SessionManagementService> sessionManagementServiceProvider,
            ObjectProvider<UserRepository> userRepositoryProvider,
            ObjectProvider<AuthAuditService> authAuditServiceProvider,
            ObjectProvider<AuthRateLimitService> authRateLimitServiceProvider,
            ObjectProvider<UserActivityLogService> activityLogServiceProvider,
            CorsConfigurationSource corsConfigurationSource,
            SecurityContextRepository securityContextRepository) throws Exception {

        UserService userService = userServiceProvider.getIfAvailable();
        if (userService != null) {
            BoxCinemaAuthenticationProvider authProvider = new BoxCinemaAuthenticationProvider(userService);
            authProvider.setAuthRateLimitService(authRateLimitServiceProvider.getIfAvailable());
            http.authenticationManager(new ProviderManager(authProvider));
        }

        SessionManagementService sessionManagementService = sessionManagementServiceProvider.getIfAvailable();
        UserRepository userRepository = userRepositoryProvider.getIfAvailable();
        AuthAuditService authAuditService = authAuditServiceProvider.getIfAvailable();

        AuthorizationManager<RequestAuthorizationContext> notStaff = (authentication, context) -> {
            Authentication authObj = authentication.get();
            boolean isStaff = authObj != null && authObj.isAuthenticated()
                    && authObj.getAuthorities().stream()
                            .anyMatch(a -> "ROLE_STAFF".equalsIgnoreCase(a.getAuthority()));
            return new AuthorizationDecision(!isStaff);
        };
        AuthorizationManager<RequestAuthorizationContext> authenticatedNotStaff = (authentication, context) -> {
            Authentication authObj = authentication.get();
            boolean isAuth = authObj != null && authObj.isAuthenticated()
                    && !(authObj instanceof AnonymousAuthenticationToken);
            boolean isStaff = isAuth && authObj.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_STAFF".equalsIgnoreCase(a.getAuthority()));
            return new AuthorizationDecision(isAuth && !isStaff);
        };

        http
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/api/quick-booking/**",
                        "/api/booking/showtimes/**",
                        "/admin/api/**",
                        "/api/payments/**",
                        "/api/bookings/**",
                        "/api/customer/bookings/**",
                        "/api/food/**",
                        "/api/vouchers/**",
                        "/api/auth/**",
                        "/api/chat/**",
                        "/api/account/**"))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                (request, response, authException) -> {
                                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                    response.setContentType("application/json;charset=UTF-8");
                                    response.getWriter().write(
                                            "{\"success\":false,\"errorCode\":\"ACCOUNT_NOT_AUTHENTICATED\",\"message\":\"Authentication required.\"}");
                                },
                                new AntPathRequestMatcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                AnyRequestMatcher.INSTANCE))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/oauth2/authorization/google-link").authenticated()
                        .requestMatchers(
                                "/", "/home", "/about", "/contact",
                                "/showtimes", "/showtimes/**",
                                "/movies", "/movies/**",
                                "/booking/buy-tickets",
                                "/booking/ticket/**",
                                "/api/customer/bookings/**")
                        .access(notStaff)
                        .requestMatchers(HttpMethod.GET, "/api/movies/*/reviews")
                        .access(notStaff)
                        .requestMatchers("/api/movies/*/reviews", "/api/movies/*/reviews/**")
                        .access(authenticatedNotStaff)
                        .requestMatchers(
                                "/login", "/register", "/signup", "/verify-email", "/verify-email/**",
                                "/forgot-password",
                                "/oauth2/authorization/**",
                                "/auth/oauth2/**",
                                "/login/oauth2/code/**",
                                "/auth/google/complete-profile",
                                "/auth/google/complete-profile/**",
                                "/auth/google/verify-link",
                                "/auth/google/verify-link/**",
                                "/api/auth/register", "/api/auth/register/**",
                                "/api/auth/login",
                                "/api/auth/csrf",
                                "/api/auth/forgot-password", "/api/auth/forgot-password/**",
                                "/api/auth/password-reset/**",
                                "/api/quick-booking/**",
                                "/api/booking/showtimes/**",
                                "/api/chat/**",
                                "/api/food/**",
                                "/api/vouchers/**",
                                "/api/payments/**",
                                "/api/bookings/**",
                                "/uploads/avatars/**",
                                "/uploads/movies/**",
                                "/css/**", "/js/**", "/assets/**", "/images/**", "/favicon.ico", "/error")
                        .permitAll()
                        .requestMatchers("/scan", "/ticket-scanner").hasAnyRole("STAFF", "MANAGER", "ADMIN")
                        .requestMatchers("/t/**", "/api/public/**").authenticated()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/manager/**").hasAnyRole("MANAGER", "ADMIN")
                        .requestMatchers("/staff/**", "/api/staff/**").hasAnyRole("STAFF", "MANAGER", "ADMIN")
                        .requestMatchers("/booking/**", "/my-tickets/**")
                        .access(authenticatedNotStaff)
                        .requestMatchers(
                                "/profile",
                                "/profile/**",
                                "/account-settings",
                                "/account-settings/**",
                                "/auth/account/**",
                                "/oauth2/link/google/**",
                                "/oauth2/set-password/google/**",
                                "/api/account/**",
                                "/api/auth/google/**",
                                "/api/auth/me",
                                "/api/auth/logout",
                                "/api/users/**",
                                "/api/booking/**")
                        .authenticated()
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(authenticationSuccessHandler(sessionManagementService, activityLogServiceProvider))
                        .failureHandler(authenticationFailureHandler())
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(logoutSuccessHandler(sessionManagementService, authAuditService, activityLogServiceProvider))
                        .permitAll())
                .addFilterAfter(
                        new SessionValidationFilter(sessionManagementService, userRepository),
                        AnonymousAuthenticationFilter.class);

        // Remember Me is intentionally disabled per Baseline v1.0 Session Architecture.

        return http.build();
    }

    @Bean
    public AuthenticationSuccessHandler authenticationSuccessHandler() {
        return authenticationSuccessHandler(null, null);
    }

    private AuthenticationSuccessHandler authenticationSuccessHandler(
            SessionManagementService sessionManagementService,
            ObjectProvider<UserActivityLogService> activityLogServiceProvider) {
        SavedRequestAwareAuthenticationSuccessHandler delegate = new SavedRequestAwareAuthenticationSuccessHandler();
        delegate.setDefaultTargetUrl("/");

        return (request, response, authentication) -> {
            if (authentication != null && authentication.getPrincipal() instanceof BoxCinemaUserDetails details) {
                HttpSession session = request.getSession(true);
                session.setAttribute("currentUser", details.getUserSummary());
                session.setAttribute("BOX_REAUTH_USER_ID", details.getUserSummary().getUserId());
                session.setAttribute("BOX_REAUTH_VERIFIED_AT_MS", System.currentTimeMillis());
                if (sessionManagementService != null && details.getUserSummary() != null) {
                    sessionManagementService.registerSession(
                            details.getUserSummary().getUserId(),
                            details.getUserSummary().getRole(),
                            session);
                }

                // Asynchronously record LOGIN activity log
                try {
                    UserActivityLogService logService = activityLogServiceProvider != null ? activityLogServiceProvider.getIfAvailable() : null;
                    if (logService != null && details.getUserSummary() != null) {
                        String ip = ActivityLogInterceptor.resolveClientIp(request);
                        String location = ActivityLogInterceptor.resolveLocation(ip);
                        String device = ActivityLogInterceptor.resolveDevice(request.getHeader("User-Agent"));
                        logService.logActivityAsync(
                                details.getUserSummary().getUserId(),
                                details.getUserSummary().getEmail(),
                                details.getUserSummary().getRole(),
                                "LOGIN",
                                "/login",
                                "Sign In Successful",
                                ip,
                                location,
                                device
                        );
                    }
                } catch (Exception ignored) {}

                String role = details.getUserSummary().getRole();
                String savedRedirect = resolveSavedRedirectForRole(request, response, role);
                if (savedRedirect != null) {
                    response.sendRedirect(savedRedirect);
                    return;
                }

                if ("ADMIN".equalsIgnoreCase(role)) {
                    response.sendRedirect("/admin");
                    return;
                } else if ("MANAGER".equalsIgnoreCase(role)) {
                    response.sendRedirect("/manager");
                    return;
                } else if ("STAFF".equalsIgnoreCase(role)) {
                    response.sendRedirect("/staff");
                    return;
                }

                // Check for saved redirect after customer login (e.g. from seat selection)
                String redirect = (String) session.getAttribute("REDIRECT_AFTER_LOGIN");
                if (redirect != null && !redirect.isBlank() && redirect.startsWith("/")) {
                    session.removeAttribute("REDIRECT_AFTER_LOGIN");
                    response.sendRedirect(redirect);
                    return;
                }
            }
            delegate.onAuthenticationSuccess(request, response, authentication);
        };
    }

    private String resolveSavedRedirectForRole(HttpServletRequest request, HttpServletResponse response, String role) {
        HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        if (savedRequest == null || savedRequest.getRedirectUrl() == null || savedRequest.getRedirectUrl().isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(savedRequest.getRedirectUrl());
            String path = uri.getRawPath();
            if (path == null || !path.startsWith("/") || path.startsWith("//")) {
                return null;
            }
            String roleUpper = role != null ? role.trim().toUpperCase() : "";
            boolean isAdmin = "ADMIN".equals(roleUpper);
            boolean isManager = "MANAGER".equals(roleUpper);
            boolean isStaff = "STAFF".equals(roleUpper);
            boolean isGatePersonnel = isAdmin || isManager || isStaff;

            boolean allowedTarget = path.startsWith("/t/")
                    || ((path.equals("/scan") || path.equals("/ticket-scanner") || path.startsWith("/staff")) && isGatePersonnel)
                    || (path.startsWith("/manager") && (isAdmin || isManager))
                    || (path.startsWith("/admin") && isAdmin)
                    || (path.startsWith("/booking/") && !isStaff);
            if (!allowedTarget) {
                return null;
            }
            requestCache.removeRequest(request, response);
            String query = uri.getRawQuery();
            return query != null && !query.isBlank() ? path + "?" + query : path;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    @Bean
    public AuthenticationFailureHandler authenticationFailureHandler() {
        return (request, response, exception) -> {
            if (exception instanceof UnverifiedEmailAuthenticationException unverified) {
                SecuritySessionHelper.saveFlashAttributes(
                        request,
                        response,
                        Map.of("errorMessage", unverified.getMessage()));
                String encodedEmail = URLEncoder.encode(unverified.getEmail(), StandardCharsets.UTF_8);
                response.sendRedirect("/verify-email?email=" + encodedEmail);
                return;
            }

            String enteredEmail = request.getParameter("username");
            String message = (exception != null && exception.getMessage() != null && !exception.getMessage().isBlank())
                    ? exception.getMessage()
                    : "Invalid email or password.";
            SecuritySessionHelper.saveFlashAttributes(
                    request,
                    response,
                    Map.of(
                            "errorMessage", message,
                            "enteredEmail", enteredEmail != null ? enteredEmail.trim() : ""));
            response.sendRedirect("/login?error=true");
        };
    }

    @Bean
    public LogoutSuccessHandler logoutSuccessHandler() {
        return logoutSuccessHandler(null, null, null);
    }

    private LogoutSuccessHandler logoutSuccessHandler(
            SessionManagementService sessionManagementService,
            AuthAuditService authAuditService,
            ObjectProvider<UserActivityLogService> activityLogServiceProvider) {
        return (request, response, authentication) -> {
            if (authentication != null && authentication.getPrincipal() instanceof BoxCinemaUserDetails details
                    && details.getUserSummary() != null) {
                if (sessionManagementService != null) {
                    sessionManagementService.unregisterSession(details.getUserSummary().getUserId(),
                            request.getSession(false));
                }
                if (authAuditService != null) {
                    authAuditService.logEvent(
                            "SESSION_LOGOUT",
                            details.getUserSummary().getUserId(),
                            details.getUserSummary().getEmail(),
                            "SUCCESS",
                            "User signed out");
                }

                // Asynchronously record LOGOUT activity log
                try {
                    UserActivityLogService logService = activityLogServiceProvider != null ? activityLogServiceProvider.getIfAvailable() : null;
                    if (logService != null) {
                        String ip = ActivityLogInterceptor.resolveClientIp(request);
                        String location = ActivityLogInterceptor.resolveLocation(ip);
                        String device = ActivityLogInterceptor.resolveDevice(request.getHeader("User-Agent"));
                        logService.logActivityAsync(
                                details.getUserSummary().getUserId(),
                                details.getUserSummary().getEmail(),
                                details.getUserSummary().getRole(),
                                "LOGOUT",
                                "/logout",
                                "User Sign Out",
                                ip,
                                location,
                                device
                        );
                    }
                } catch (Exception ignored) {}
            }
            SecuritySessionHelper.saveFlashAttributes(
                    request,
                    response,
                    Map.of("successMessage", "You have been signed out safely."));
            response.sendRedirect("/login?logout=true");
        };
    }
}
