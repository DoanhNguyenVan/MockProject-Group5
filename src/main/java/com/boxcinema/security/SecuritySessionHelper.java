package com.boxcinema.security;

import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.service.SessionManagementService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.servlet.FlashMap;
import org.springframework.web.servlet.FlashMapManager;
import org.springframework.web.servlet.support.SessionFlashMapManager;

import java.util.Map;

/**
 * Shared helper to keep Spring Security Context, HttpSession ("currentUser"),
 * session ID rotation (session fixation protection), and FlashMap redirects synchronized.
 */
public final class SecuritySessionHelper {

    private static final SecurityContextRepository DEFAULT_CONTEXT_REPOSITORY = new HttpSessionSecurityContextRepository();
    private static final FlashMapManager FLASH_MAP_MANAGER = new SessionFlashMapManager();

    private SecuritySessionHelper() {
    }

    public static void authenticateUserSession(
            UserSummaryDto user,
            HttpServletRequest request,
            HttpServletResponse response,
            SecurityContextRepository securityContextRepository) {
        authenticateUserSession(user, request, response, securityContextRepository, null);
    }

    public static void authenticateUserSession(
            UserSummaryDto user,
            HttpServletRequest request,
            HttpServletResponse response,
            SecurityContextRepository securityContextRepository,
            SessionManagementService sessionManagementService) {

        HttpSession session = request.getSession(true);
        try {
            request.changeSessionId();
        } catch (IllegalStateException ignored) {
            // Mock or container request state without active mutable session ID
        }

        BoxCinemaUserDetails principal = new BoxCinemaUserDetails(user);
        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                principal.getAuthorities()
        );

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);

        SecurityContextRepository repo = (securityContextRepository != null)
                ? securityContextRepository
                : DEFAULT_CONTEXT_REPOSITORY;
        repo.saveContext(context, request, response);

        session = request.getSession(true);
        session.setAttribute("currentUser", user);
        if (sessionManagementService != null) {
            sessionManagementService.registerSession(user.getUserId(), user.getRole(), session);
        }
    }

    public static void saveFlashAttributes(
            HttpServletRequest request,
            HttpServletResponse response,
            Map<String, ?> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return;
        }
        FlashMap flashMap = new FlashMap();
        flashMap.putAll(attributes);
        FLASH_MAP_MANAGER.saveOutputFlashMap(flashMap, request, response);
    }
}
