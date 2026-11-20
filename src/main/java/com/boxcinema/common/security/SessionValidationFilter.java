package com.boxcinema.common.security;

import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.UserRepository;
import com.boxcinema.common.service.SessionManagementService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

/**
 * Filter that validates active HTTP sessions against the in-memory session registry and user account status/role in DB.
 */
public class SessionValidationFilter extends OncePerRequestFilter {

    private final SessionManagementService sessionManagementService;
    private final UserRepository userRepository;

    public SessionValidationFilter(SessionManagementService sessionManagementService,
                                   UserRepository userRepository) {
        this.sessionManagementService = sessionManagementService;
        this.userRepository = userRepository;
    }

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
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof BoxCinemaUserDetails details) {
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
                    response.getWriter().write(
                            "{\"success\":false,\"message\":\"Your session has expired or been revoked. Please sign in again.\"}");
                    return;
                }
                SecuritySessionHelper.saveFlashAttributes(
                        request,
                        response,
                        Map.of("errorMessage",
                                "Your session has expired or your account status changed. Please sign in again."));
                response.sendRedirect("/login?sessionExpired=true");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private void expireCookie(HttpServletResponse response, String name) {
        Cookie cookie = new Cookie(name, "");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
}
