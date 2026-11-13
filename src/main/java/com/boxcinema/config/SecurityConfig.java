package com.boxcinema.config;

import com.boxcinema.entity.User;
import com.boxcinema.repository.UserRepository;
import com.boxcinema.security.BoxCinemaAuthenticationProvider;
import com.boxcinema.security.BoxCinemaUserDetails;
import com.boxcinema.security.SecuritySessionHelper;
import com.boxcinema.security.UnverifiedEmailAuthenticationException;
import com.boxcinema.service.AuthAuditService;
import com.boxcinema.service.AuthRateLimitService;
import com.boxcinema.service.PasswordHashService;
import com.boxcinema.service.SessionManagementService;
import com.boxcinema.service.UserService;
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
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
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
            ObjectProvider<UserDetailsService> userDetailsServiceProvider,
            ObjectProvider<SessionManagementService> sessionManagementServiceProvider,
            ObjectProvider<UserRepository> userRepositoryProvider,
            ObjectProvider<AuthAuditService> authAuditServiceProvider,
            ObjectProvider<AuthRateLimitService> authRateLimitServiceProvider,
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

        http
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        "/api/quick-booking/**"
                ))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .exceptionHandling(ex -> ex
                        .defaultAuthenticationEntryPointFor(
                                (request, response, authException) -> {
                                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                                    response.setContentType("application/json;charset=UTF-8");
                                    response.getWriter().write("{\"success\":false,\"errorCode\":\"ACCOUNT_NOT_AUTHENTICATED\",\"message\":\"Authentication required.\"}");
                                },
                                new AntPathRequestMatcher("/api/**")
                        )
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                AnyRequestMatcher.INSTANCE
                        )
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/oauth2/authorization/google-link").authenticated()
                        .requestMatchers(
                                "/", "/home", "/about", "/contact", "/showtimes/**", "/movies/**",
                                "/login", "/register", "/signup", "/verify-email", "/verify-email/**", "/forgot-password",
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
                                "/css/**", "/js/**", "/assets/**", "/images/**", "/favicon.ico", "/error"
                        ).permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/manager/**").hasAnyRole("MANAGER", "ADMIN")
                        .requestMatchers("/staff/**").hasAnyRole("STAFF", "MANAGER", "ADMIN")
                        .requestMatchers(
                                "/profile/**",
                                "/auth/account/**",
                                "/oauth2/link/google/**",
                                "/oauth2/set-password/google/**",
                                "/api/account/**",
                                "/api/auth/google/**",
                                "/api/auth/me",
                                "/api/auth/logout",
                                "/api/users/**",
                                "/booking/**",
                                "/my-tickets/**",
                                "/api/booking/**"
                        ).authenticated()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(authenticationSuccessHandler(sessionManagementService))
                        .failureHandler(authenticationFailureHandler())
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID")
                        .logoutSuccessHandler(logoutSuccessHandler(sessionManagementService, authAuditService))
                        .permitAll()
                )
                .addFilterAfter(
                        sessionValidationFilter(sessionManagementService, userRepository),
                        AnonymousAuthenticationFilter.class
                );

        // Remember Me is intentionally disabled per Baseline v1.0 Session Architecture.

        return http.build();
    }

    private OncePerRequestFilter sessionValidationFilter(
            SessionManagementService sessionManagementService,
            UserRepository userRepository) {
        return new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                String path = request.getRequestURI();
                return path != null && (path.startsWith("/css/")
                        || path.startsWith("/js/")
                        || path.startsWith("/assets/")
                        || path.startsWith("/images/")
                        || "/favicon.ico".equals(path)
                        || "/error".equals(path));
            }

            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain filterChain) throws ServletException, IOException {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof BoxCinemaUserDetails details) {
                    Long userId = details.getUserSummary() != null ? details.getUserSummary().getUserId() : null;
                    HttpSession session = request.getSession(false);

                    boolean sessionRevoked = sessionManagementService != null
                            && userId != null
                            && session != null
                            && !sessionManagementService.isSessionValid(userId, session);

                    boolean userInactiveInDb = false;
                    boolean userRoleChangedInDb = false;
                    if (!sessionRevoked && userRepository != null && userId != null) {
                        Optional<User> dbUserOpt = userRepository.findById(userId);
                        userInactiveInDb = dbUserOpt.isEmpty()
                                || !"ACTIVE".equalsIgnoreCase(dbUserOpt.get().getAccountStatus());
                        if (!userInactiveInDb) {
                            String sessionRole = details.getUserSummary() != null ? details.getUserSummary().getRole() : null;
                            String dbRole = dbUserOpt.get().getRole();
                            userRoleChangedInDb = sessionRole != null && dbRole != null
                                    && !sessionRole.trim().equalsIgnoreCase(dbRole.trim());
                        }
                    }

                    if (sessionRevoked || userInactiveInDb || userRoleChangedInDb) {
                        if (sessionManagementService != null && userId != null && session != null) {
                            sessionManagementService.unregisterSession(userId, session);
                        }
                        if (session != null) {
                            session.invalidate();
                        }
                        SecurityContextHolder.clearContext();
                        expireCookie(response, "JSESSIONID");

                        String uri = request.getRequestURI();
                        if (uri != null && uri.startsWith("/api/")) {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType("application/json;charset=UTF-8");
                            response.getWriter().write("{\"success\":false,\"message\":\"Your session has expired or been revoked. Please sign in again.\"}");
                            return;
                        }
                        SecuritySessionHelper.saveFlashAttributes(
                                request,
                                response,
                                Map.of("errorMessage", "Your session has expired or your account status changed. Please sign in again.")
                        );
                        response.sendRedirect("/login?sessionExpired=true");
                        return;
                    }
                }
                filterChain.doFilter(request, response);
            }
        };
    }

    private void expireCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    @Bean
    public AuthenticationSuccessHandler authenticationSuccessHandler() {
        return authenticationSuccessHandler(null);
    }

    private AuthenticationSuccessHandler authenticationSuccessHandler(SessionManagementService sessionManagementService) {
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
                            session
                    );
                }

                String role = details.getUserSummary().getRole();
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
            }
            delegate.onAuthenticationSuccess(request, response, authentication);
        };
    }

    @Bean
    public AuthenticationFailureHandler authenticationFailureHandler() {
        return (request, response, exception) -> {
            if (exception instanceof UnverifiedEmailAuthenticationException unverified) {
                SecuritySessionHelper.saveFlashAttributes(
                        request,
                        response,
                        Map.of("errorMessage", unverified.getMessage())
                );
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
                            "enteredEmail", enteredEmail != null ? enteredEmail.trim() : ""
                    )
            );
            response.sendRedirect("/login?error=true");
        };
    }

    @Bean
    public LogoutSuccessHandler logoutSuccessHandler() {
        return logoutSuccessHandler(null, null);
    }

    private LogoutSuccessHandler logoutSuccessHandler(
            SessionManagementService sessionManagementService,
            AuthAuditService authAuditService) {
        return (request, response, authentication) -> {
            if (authentication != null && authentication.getPrincipal() instanceof BoxCinemaUserDetails details
                    && details.getUserSummary() != null) {
                if (sessionManagementService != null) {
                    sessionManagementService.unregisterSession(details.getUserSummary().getUserId(), request.getSession(false));
                }
                if (authAuditService != null) {
                    authAuditService.logEvent(
                            "SESSION_LOGOUT",
                            details.getUserSummary().getUserId(),
                            details.getUserSummary().getEmail(),
                            "SUCCESS",
                            "User signed out"
                    );
                }
            }
            SecuritySessionHelper.saveFlashAttributes(
                    request,
                    response,
                    Map.of("successMessage", "You have been signed out safely.")
            );
            response.sendRedirect("/login?logout=true");
        };
    }
}
