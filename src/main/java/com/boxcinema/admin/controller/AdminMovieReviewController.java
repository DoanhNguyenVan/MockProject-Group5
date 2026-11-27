package com.boxcinema.admin.controller;

import com.boxcinema.admin.dto.AdminMovieReviewDto;
import com.boxcinema.admin.dto.AdminMovieReviewStatsDto;
import com.boxcinema.admin.service.AdminMovieReviewService;
import com.boxcinema.common.constant.SessionConstants;
import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.util.SecurityContextUtils;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping({"/admin/reviews", "/admin/comments"})
@PreAuthorize("hasRole('ADMIN')")
public class AdminMovieReviewController {

    private final AdminMovieReviewService reviewService;

    public AdminMovieReviewController(AdminMovieReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public String listReviews(
            @RequestParam(name = "movieId", required = false) Long movieId,
            @RequestParam(name = "rating", required = false) Integer rating,
            @RequestParam(name = "status", required = false, defaultValue = "") String status,
            @RequestParam(name = "verified", required = false) Boolean verified,
            @RequestParam(name = "search", required = false, defaultValue = "") String search,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            Model model,
            HttpSession session
    ) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<AdminMovieReviewDto> reviewsPage = reviewService.getReviews(movieId, rating, status, verified, search, pageable);
        AdminMovieReviewStatsDto stats = reviewService.getStats();

        model.addAttribute("reviews", reviewsPage);
        model.addAttribute("stats", stats);
        model.addAttribute("topRatedMovies", reviewService.getTopRatedMovies(5));
        model.addAttribute("movieOptions", reviewService.getMovieFilterOptions());
        model.addAttribute("selectedMovieId", movieId);
        model.addAttribute("selectedRating", rating);
        model.addAttribute("selectedStatus", status != null ? status : "");
        model.addAttribute("selectedVerified", verified != null ? String.valueOf(verified) : "");
        model.addAttribute("searchQuery", search != null ? search : "");
        model.addAttribute("activeSection", "reviews");
        model.addAttribute("activePage", "admin");

        if (session != null) {
            UserSummaryDto currentUser = (UserSummaryDto) session.getAttribute(SessionConstants.CURRENT_USER);
            if (currentUser != null) {
                model.addAttribute("currentUser", currentUser);
            }
        }

        return "admin/reviews/index";
    }

    @PostMapping("/{id}/status")
    public String updateStatus(
            @PathVariable Long id,
            @RequestParam("targetStatus") String targetStatus,
            Authentication authentication,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long adminUserId = resolveAdminUserId(authentication, session);
        try {
            AdminMovieReviewDto updated = reviewService.updateStatus(id, targetStatus, adminUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Comment #" + id + " marked as " + updated.getStatus() + ".");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to update comment status: " + ex.getMessage());
        }
        return "redirect:/admin/reviews";
    }

    @PostMapping("/{id}/reply")
    public String replyToReview(
            @PathVariable Long id,
            @RequestParam(name = "adminReply", required = false, defaultValue = "") String adminReply,
            Authentication authentication,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long adminUserId = resolveAdminUserId(authentication, session);
        try {
            AdminMovieReviewDto updated = reviewService.replyToReview(id, adminReply, adminUserId);
            String msg = updated.getAdminReply() != null && !updated.getAdminReply().isBlank()
                    ? "Official reply saved for comment #" + id + "."
                    : "Official reply cleared for comment #" + id + ".";
            redirectAttributes.addFlashAttribute("successMessage", msg);
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to save reply: " + ex.getMessage());
        }
        return "redirect:/admin/reviews";
    }

    @PostMapping("/{id}/delete")
    public String deleteReview(
            @PathVariable Long id,
            Authentication authentication,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long adminUserId = resolveAdminUserId(authentication, session);
        try {
            reviewService.deleteReview(id, adminUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Comment #" + id + " permanently deleted.");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Unable to delete comment: " + ex.getMessage());
        }
        return "redirect:/admin/reviews";
    }

    @PostMapping("/bulk")
    public String bulkModerate(
            @RequestParam(name = "reviewIds", required = false) List<Long> reviewIds,
            @RequestParam("action") String action,
            Authentication authentication,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long adminUserId = resolveAdminUserId(authentication, session);
        try {
            int affected = reviewService.bulkModerate(reviewIds, action, adminUserId);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Updated " + affected + " comment(s) (" + action.toUpperCase() + ").");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", "Bulk moderation failed: " + ex.getMessage());
        }
        return "redirect:/admin/reviews";
    }

    private Long resolveAdminUserId(Authentication authentication, HttpSession session) {
        try {
            if (authentication != null) {
                Long uid = SecurityContextUtils.extractUserId(authentication);
                if (uid != null) return uid;
            }
        } catch (Exception ignored) {
        }
        if (session != null) {
            UserSummaryDto currentUser = (UserSummaryDto) session.getAttribute(SessionConstants.CURRENT_USER);
            if (currentUser != null && currentUser.getUserId() != null) {
                return currentUser.getUserId();
            }
        }
        return null;
    }
}
