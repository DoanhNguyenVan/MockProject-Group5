package com.boxcinema.config;

import com.boxcinema.security.BoxCinemaAuthenticationProvider;
import com.boxcinema.security.BoxCinemaUserDetails;
import com.boxcinema.security.SecuritySessionHelper;
import com.boxcinema.security.UnverifiedEmailAuthenticationException;
import com.boxcinema.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
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
            SecurityContextRepository securityContextRepository) throws Exception {

        UserService userService = userServiceProvider.getIfAvailable();
        if (userService != null) {
            http.authenticationManager(new ProviderManager(new BoxCinemaAuthenticationProvider(userService)));
        }

        http
                .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/auth/**", "/api/quick-booking/**"))
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/", "/home", "/about", "/contact", "/showtimes/**", "/movies/**",
                                "/login", "/register", "/signup", "/verify-email/**", "/forgot-password",
                                "/auth/**", "/login/oauth2/**",
                                "/api/auth/**",
                                "/api/quick-booking/**",
                                "/css/**", "/js/**", "/assets/**", "/images/**", "/favicon.ico", "/error"
                        ).permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/manager/**").hasAnyRole("MANAGER", "ADMIN")
                        .requestMatchers("/staff/**").hasAnyRole("STAFF", "MANAGER", "ADMIN")
                        .requestMatchers("/booking/**", "/my-tickets/**", "/profile/**", "/api/booking/**").authenticated()
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .successHandler(authenticationSuccessHandler())
                        .failureHandler(authenticationFailureHandler())
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID", "remember-me")
                        .logoutSuccessHandler(logoutSuccessHandler())
                        .permitAll()
                );

        UserDetailsService userDetailsService = userDetailsServiceProvider.getIfAvailable();
        if (userDetailsService != null) {
            http.rememberMe(rememberMe -> rememberMe
                    .userDetailsService(userDetailsService)
                    .key("box-cinema-remember-me-secret-key")
                    .rememberMeParameter("remember-me")
                    .tokenValiditySeconds(7 * 24 * 60 * 60)
            );
        }

        return http.build();
    }

    @Bean
    public AuthenticationSuccessHandler authenticationSuccessHandler() {
        SavedRequestAwareAuthenticationSuccessHandler delegate = new SavedRequestAwareAuthenticationSuccessHandler();
        delegate.setDefaultTargetUrl("/");

        return (request, response, authentication) -> {
            if (authentication != null && authentication.getPrincipal() instanceof BoxCinemaUserDetails details) {
                HttpSession session = request.getSession(true);
                session.setAttribute("currentUser", details.getUserSummary());
                if (request.getParameter("remember-me") != null) {
                    session.setMaxInactiveInterval(7 * 24 * 60 * 60);
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
        return (request, response, authentication) -> {
            SecuritySessionHelper.saveFlashAttributes(
                    request,
                    response,
                    Map.of("successMessage", "You have been signed out safely.")
            );
            response.sendRedirect("/login?logout=true");
        };
    }
}
