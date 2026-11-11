package com.boxcinema.security;

import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.service.UserService;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

/**
 * Custom Spring Security AuthenticationProvider integrating BOX Cinema's
 * brute-force protection, BCrypt password matching, and unverified Gmail OTP flow.
 */
public class BoxCinemaAuthenticationProvider implements AuthenticationProvider {

    private final UserService userService;

    public BoxCinemaAuthenticationProvider(UserService userService) {
        this.userService = userService;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getName();
        String rawPassword = authentication.getCredentials() != null
                ? authentication.getCredentials().toString()
                : "";

        try {
            UserSummaryDto user = userService.authenticate(email, rawPassword);
            BoxCinemaUserDetails principal = new BoxCinemaUserDetails(user);
            return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        } catch (IllegalStateException unverifiedEx) {
            String cleanEmail = (email != null) ? email.trim().toLowerCase() : "";
            throw new UnverifiedEmailAuthenticationException(
                    cleanEmail,
                    "Please verify your Gmail address before signing in. A new 6-digit OTP code has been generated."
            );
        } catch (IllegalArgumentException ex) {
            throw new BadCredentialsException(ex.getMessage(), ex);
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}

