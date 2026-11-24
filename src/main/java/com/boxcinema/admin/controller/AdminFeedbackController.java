package com.boxcinema.admin.controller;

import com.boxcinema.admin.dto.AdminFeedbackDto;
import com.boxcinema.admin.dto.AdminFeedbackStatsDto;
import com.boxcinema.admin.service.AdminFeedbackService;
import com.boxcinema.common.constant.SessionConstants;
import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.util.SecurityContextUtils;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping({"/admin/feedbacks", "/admin/inquiries"})
@PreAuthorize("hasRole('ADMIN')")
public class AdminFeedbackController {

    private final AdminFeedbackService feedbackService;

    public AdminFeedbackController(AdminFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @GetMapping
    public String listFeedbacks(
            @RequestParam(name = "status", required = false, defaultValue = "") String status,
            @RequestParam(name = "topic", required = false, defaultValue = "") String topic,
            @RequestParam(name = "search", required = false, defaultValue = "") String search,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "10") int size,
            Model model,
            HttpSession session
    ) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        Page<AdminFeedbackDto> feedbackPage = feedbackService.getFeedbacks(status, topic, search, pageable);
        AdminFeedbackStatsDto stats = feedbackService.getStatistics();

        model.addAttribute("feedbacks", feedbackPage);
        model.addAttribute("stats", stats);
        model.addAttribute("selectedStatus", status != null ? status : "");
        model.addAttribute("selectedTopic", topic != null ? topic : "");
        model.addAttribute("searchQuery", search != null ? search : "");
        model.addAttribute("activeSection", "feedbacks");
        model.addAttribute("activeNav", "feedbacks");
        model.addAttribute("activePage", "admin");

        UserSummaryDto currentUser = (UserSummaryDto) session.getAttribute(SessionConstants.CURRENT_USER);
        if (currentUser != null) {
            model.addAttribute("currentUser", currentUser);
        }

        return "admin/feedbacks/index";
    }

    @GetMapping("/api/{id}")
    @ResponseBody
    public ResponseEntity<AdminFeedbackDto> getFeedbackApi(@PathVariable Long id) {
        try {
            AdminFeedbackDto dto = feedbackService.getFeedbackById(id);
            return ResponseEntity.ok(dto);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{id}/reply")
    public String replyFeedback(
            @PathVariable Long id,
            @RequestParam("adminResponse") String adminResponse,
            @RequestParam(name = "newStatus", defaultValue = "RESOLVED") String newStatus,
            @RequestParam(name = "sendEmail", defaultValue = "false") boolean sendEmail,
            Authentication authentication,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long adminUserId = resolveAdminUserId(authentication, session);
        try {
            feedbackService.replyFeedback(id, adminResponse, newStatus, adminUserId, sendEmail);
            String msg = "Response successfully recorded and ticket marked as " + newStatus + (sendEmail ? " (Notification email sent to customer)." : ".");
            redirectAttributes.addFlashAttribute("successMessage", msg);
            redirectAttributes.addFlashAttribute("feedbackSuccessMessage", msg);
        } catch (Exception ex) {
            String err = "Failed to update feedback: " + ex.getMessage();
            redirectAttributes.addFlashAttribute("errorMessage", err);
            redirectAttributes.addFlashAttribute("feedbackErrorMessage", err);
        }
        return "redirect:/admin/feedbacks";
    }

    @PostMapping("/{id}/status")
    public String updateStatus(
            @PathVariable Long id,
            @RequestParam("newStatus") String newStatus,
            Authentication authentication,
            HttpSession session,
            RedirectAttributes redirectAttributes
    ) {
        Long adminUserId = resolveAdminUserId(authentication, session);
        try {
            feedbackService.updateStatus(id, newStatus, adminUserId);
            String msg = "Ticket status changed to " + newStatus + ".";
            redirectAttributes.addFlashAttribute("successMessage", msg);
            redirectAttributes.addFlashAttribute("feedbackSuccessMessage", msg);
        } catch (Exception ex) {
            String err = "Failed to change status: " + ex.getMessage();
            redirectAttributes.addFlashAttribute("errorMessage", err);
            redirectAttributes.addFlashAttribute("feedbackErrorMessage", err);
        }
        return "redirect:/admin/feedbacks";
    }

    @PostMapping("/{id}/delete")
    public String deleteFeedback(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes
    ) {
        try {
            feedbackService.deleteFeedback(id);
            String msg = "Customer feedback ticket #" + id + " has been permanently deleted.";
            redirectAttributes.addFlashAttribute("successMessage", msg);
            redirectAttributes.addFlashAttribute("feedbackSuccessMessage", msg);
        } catch (Exception ex) {
            String err = "Failed to delete feedback: " + ex.getMessage();
            redirectAttributes.addFlashAttribute("errorMessage", err);
            redirectAttributes.addFlashAttribute("feedbackErrorMessage", err);
        }
        return "redirect:/admin/feedbacks";
    }

    private Long resolveAdminUserId(Authentication authentication, HttpSession session) {
        try {
            if (authentication != null) {
                Long uid = SecurityContextUtils.extractUserId(authentication);
                if (uid != null) return uid;
            }
        } catch (Exception ignored) {}

        if (session != null) {
            UserSummaryDto currentUser = (UserSummaryDto) session.getAttribute(SessionConstants.CURRENT_USER);
            if (currentUser != null && currentUser.getUserId() != null) {
                return currentUser.getUserId();
            }
        }
        return 1L; // Fallback to System Admin
    }
}
