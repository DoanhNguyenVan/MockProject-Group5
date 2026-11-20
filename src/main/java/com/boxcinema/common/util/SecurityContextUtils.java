package com.boxcinema.common.util;

import com.boxcinema.common.constant.SessionConstants;
import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

/**
 * Utility methods for extracting the authenticated user identity without duplicating logic across Controllers.
 */
public final class SecurityContextUtils {

    private SecurityContextUtils() {
    }

    public static Long resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return extractUserId(auth);
    }

    public static Long resolveCurrentUserId(HttpServletRequest request) {
        Long userId = resolveCurrentUserId();
        if (userId != null) {
            return userId;
        }
        if (request != null) {
            HttpSession session = request.getSession(false);
            if (session != null) {
                Object currentUser = session.getAttribute(SessionConstants.CURRENT_USER);
                if (currentUser instanceof UserSummaryDto dto && dto.getUserId() != null) {
                    return dto.getUserId();
                }
            }
        }
        return null;
    }

    public static Long requireAdminUserId(Authentication authentication) {
        Long userId = extractUserId(authentication);
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator identity is unavailable.");
        }
        return userId;
    }

    public static Long extractUserId(Authentication authentication) {
        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof BoxCinemaUserDetails details
                && details.getUserSummary() != null) {
            return details.getUserSummary().getUserId();
        }
        return null;
    }
}
