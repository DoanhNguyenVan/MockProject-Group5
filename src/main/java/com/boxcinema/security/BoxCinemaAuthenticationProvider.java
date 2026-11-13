package com.boxcinema.security;

import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.service.AuthRateLimitService;
import com.boxcinema.service.LoginAttemptService;
import com.boxcinema.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Custom Spring Security AuthenticationProvider integrating BOX Cinema's
 * IP & (email, IP) brute-force protection, BCrypt password matching, and unverified Gmail OTP flow.
 */
public class BoxCinemaAuthenticationProvider implements AuthenticationProvider {

    private final UserService userService;
    private AuthRateLimitService authRateLimitService;

    public BoxCinemaAuthenticationProvider(UserService userService) {
        this.userService = userService;
    }

    public void setAuthRateLimitService(AuthRateLimitService authRateLimitService) {
        this.authRateLimitService = authRateLimitService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getName();
        String rawPassword = authentication.getCredentials() != null
                ? authentication.getCredentials().toString()
                : "";
        String clientIp = resolveClientIp();

        try {
            if (authRateLimitService != null) {
                authRateLimitService.checkLoginRateLimit(clientIp);
            }
            UserSummaryDto user = userService.authenticate(email, rawPassword);
            BoxCinemaUserDetails principal = new BoxCinemaUserDetails(user);
            return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        } catch (AuthRateLimitService.RateLimitExceededException rateEx) {
            throw new BadCredentialsException(rateEx.getMessage(), rateEx);
        } catch (LoginAttemptService.LoginLockoutException lockEx) {
            throw new BadCredentialsException(lockEx.getMessage(), lockEx);
        } catch (IllegalStateException stateEx) {
            String msg = stateEx.getMessage() != null ? stateEx.getMessage() : "";
            if (msg.startsWith("PENDING_VERIFICATION:")) {
                String unverifiedEmail = msg.substring("PENDING_VERIFICATION:".length());
                throw new UnverifiedEmailAuthenticationException(
                        unverifiedEmail.isBlank() ? (email != null ? email.trim().toLowerCase() : "") : unverifiedEmail,
                        "Please verify your email with the 6-digit OTP code before signing in.");
            }
            throw new BadCredentialsException(msg.isBlank() ? "Authentication failed." : msg, stateEx);
        } catch (IllegalArgumentException ex) {
            throw new BadCredentialsException(ex.getMessage(), ex);
        }
    }

    private String resolveClientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            if (request != null && request.getRemoteAddr() != null && !request.getRemoteAddr().isBlank()) {
                return request.getRemoteAddr();
            }
        }
        return "local";
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
