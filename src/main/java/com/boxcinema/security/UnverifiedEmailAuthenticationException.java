package com.boxcinema.security;

import org.springframework.security.core.AuthenticationException;

/**
 * Thrown during Spring Security authentication when the account email exists and password matches,
 * but the account is still awaiting 6-digit Gmail OTP verification.
 */
public class UnverifiedEmailAuthenticationException extends AuthenticationException {

    private final String email;

    public UnverifiedEmailAuthenticationException(String email, String message) {
        super(message);
        this.email = email;
    }

    public String getEmail() {
        return email;
    }
}

