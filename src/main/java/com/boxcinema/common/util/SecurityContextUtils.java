package com.boxcinema.common.util;

import com.boxcinema.common.constant.SessionConstants;
import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Utility methods for extracting the authenticated user identity without duplicating logic across Controllers.
 */
public final class SecurityContextUtils {

    private static final String GUEST_BOOKING_IDS_ATTR = "BOX_GUEST_BOOKING_IDS";

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
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.getPrincipal() instanceof BoxCinemaUserDetails details
                && details.getUserSummary() != null) {
            return details.getUserSummary().getUserId();
        }
        return null;
    }

    public static String extractRole(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return "";
        }
        if (authentication.getPrincipal() instanceof BoxCinemaUserDetails details
                && details.getUserSummary() != null
                && details.getUserSummary().getRole() != null) {
            return details.getUserSummary().getRole().trim().toUpperCase();
        }
        return authentication.getAuthorities().stream()
                .map(a -> a.getAuthority() != null ? a.getAuthority().replace("ROLE_", "").toUpperCase() : "")
                .findFirst()
                .orElse("");
    }

    public static boolean hasStaffAuthority(Authentication authentication) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(a -> a.getAuthority() != null ? a.getAuthority().toUpperCase() : "")
                .anyMatch(role -> "ROLE_STAFF".equals(role)
                        || "ROLE_MANAGER".equals(role)
                        || "ROLE_ADMIN".equals(role));
    }

    public static Set<Long> extractGuestSessionBookingIds(HttpServletRequest request) {
        if (request == null) {
            return Collections.emptySet();
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            return Collections.emptySet();
        }
        Object attr = session.getAttribute(GUEST_BOOKING_IDS_ATTR);
        if (attr instanceof Collection<?> col && !col.isEmpty()) {
            Set<Long> ids = new LinkedHashSet<>();
            for (Object item : col) {
                if (item instanceof Number num) {
                    ids.add(num.longValue());
                }
            }
            return Collections.unmodifiableSet(ids);
        }
        return Collections.emptySet();
    }

    public static void recordGuestBookingInSession(HttpServletRequest request, Long bookingId) {
        if (request == null || bookingId == null) {
            return;
        }
        HttpSession session = request.getSession(true);
        Set<Long> ids = new LinkedHashSet<>(extractGuestSessionBookingIds(request));
        ids.add(bookingId);
        session.setAttribute(GUEST_BOOKING_IDS_ATTR, ids);
    }
}
