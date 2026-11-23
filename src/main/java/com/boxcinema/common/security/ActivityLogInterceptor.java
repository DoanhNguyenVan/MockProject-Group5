package com.boxcinema.common.security;

import com.boxcinema.admin.service.UserActivityLogService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ActivityLogInterceptor implements HandlerInterceptor {

    private final ObjectProvider<UserActivityLogService> activityLogServiceProvider;

    @Autowired
    public ActivityLogInterceptor(ObjectProvider<UserActivityLogService> activityLogServiceProvider) {
        this.activityLogServiceProvider = activityLogServiceProvider;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserActivityLogService activityLogService = activityLogServiceProvider.getIfAvailable();
        if (activityLogService == null) {
            return;
        }

        if (response.getStatus() >= 400) {
            return; // Don't log failed requests
        }

        String uri = request.getRequestURI();
        if (uri == null || shouldSkip(uri)) {
            return;
        }

        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method)) {
            return; // Only log page navigation (GET requests)
        }

        Long userId = null;
        String email = null;
        String role = "GUEST";

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            if (auth.getPrincipal() instanceof BoxCinemaUserDetails userDetails && userDetails.getUserSummary() != null) {
                userId = userDetails.getUserSummary().getUserId();
                email = userDetails.getUsername();
                role = userDetails.getUserSummary().getRole() != null ? userDetails.getUserSummary().getRole() : "CUSTOMER";
            } else {
                email = auth.getName();
                role = auth.getAuthorities().stream()
                        .map(Object::toString)
                        .filter(r -> r.startsWith("ROLE_"))
                        .map(r -> r.substring(5))
                        .findFirst()
                        .orElse("USER");
            }
        }

        String ip = resolveClientIp(request);
        String location = resolveLocation(ip);
        String device = resolveDevice(request.getHeader("User-Agent"));
        String pageTitle = resolvePageTitle(uri);

        activityLogService.logActivityAsync(
                userId,
                email,
                role,
                "PAGE_VIEW",
                uri,
                pageTitle,
                ip,
                location,
                device
        );
    }

    private boolean shouldSkip(String uri) {
        String lower = uri.toLowerCase();
        return lower.startsWith("/assets/")
                || lower.startsWith("/css/")
                || lower.startsWith("/js/")
                || lower.startsWith("/img/")
                || lower.startsWith("/images/")
                || lower.startsWith("/webjars/")
                || lower.startsWith("/api/")
                || lower.startsWith("/favicon.ico")
                || lower.startsWith("/error")
                || lower.endsWith(".css")
                || lower.endsWith(".js")
                || lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".svg")
                || lower.endsWith(".webp")
                || lower.endsWith(".woff")
                || lower.endsWith(".woff2")
                || lower.endsWith(".ttf")
                || lower.endsWith(".map");
    }

    public static String resolveClientIp(HttpServletRequest request) {
        String[] headers = {
                "CF-Connecting-IP",
                "X-Forwarded-For",
                "X-Real-IP",
                "HTTP_X_FORWARDED_FOR"
        };
        for (String h : headers) {
            String ip = request.getHeader(h);
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                return ip.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "127.0.0.1";
    }

    public static String resolveLocation(String ip) {
        if (ip == null || ip.isBlank() || ip.equals("127.0.0.1") || ip.equals("0:0:0:0:0:0:0:1") || ip.startsWith("192.168.") || ip.startsWith("10.")) {
            return "Vietnam (Local Dev)";
        }
        if (ip.startsWith("118.69.") || ip.startsWith("171.244.")) {
            return "Ho Chi Minh City, VN";
        }
        if (ip.startsWith("14.161.") || ip.startsWith("113.160.")) {
            return "Hanoi, VN";
        }
        return "Vietnam (Gateway)";
    }

    public static String resolveDevice(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "Unknown Device";
        }
        String ua = userAgent.toLowerCase();
        String os = "Unknown OS";
        if (ua.contains("windows")) os = "Windows";
        else if (ua.contains("mac os") || ua.contains("macintosh")) os = "macOS";
        else if (ua.contains("iphone") || ua.contains("ipad")) os = "iOS";
        else if (ua.contains("android")) os = "Android";
        else if (ua.contains("linux")) os = "Linux";

        String browser = "Browser";
        if (ua.contains("edg/")) browser = "Edge";
        else if (ua.contains("chrome") && !ua.contains("edg/")) browser = "Chrome";
        else if (ua.contains("firefox")) browser = "Firefox";
        else if (ua.contains("safari") && !ua.contains("chrome")) browser = "Safari";

        return browser + " on " + os;
    }

    private String resolvePageTitle(String uri) {
        if (uri.startsWith("/admin/workspace")) return "Admin Workspace";
        if (uri.startsWith("/admin/showtimes")) return "Showtime Management";
        if (uri.startsWith("/admin/activity-logs")) return "Audit & Activity Logs";
        if (uri.startsWith("/admin/movies")) return "Movie Management";
        if (uri.startsWith("/admin/genres")) return "Genre Management";
        if (uri.startsWith("/manager/showtimes")) return "Manager Showtimes";
        if (uri.startsWith("/movies")) return "Movies Catalog";
        if (uri.startsWith("/showtimes")) return "Showtimes Schedule";
        if (uri.startsWith("/booking/buy-tickets")) return "Seat Booking";
        if (uri.startsWith("/booking/history")) return "Booking History";
        if (uri.startsWith("/login")) return "Sign In";
        if (uri.startsWith("/register")) return "Register Account";
        if (uri.startsWith("/about")) return "About Us";
        if (uri.startsWith("/contact")) return "Contact Support";
        if (uri.equals("/")) return "Home Page";
        return uri;
    }
}
