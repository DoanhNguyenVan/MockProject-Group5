package com.boxcinema.manager.controller;

import com.boxcinema.admin.dto.AdminShowtimeRequestDto;
import com.boxcinema.admin.service.AdminShowtimeService;
import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.Cinema;
import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.UserRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/manager")
@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
public class ManagerShowtimeController {

    private final AdminShowtimeService showtimeService;
    private final UserRepository userRepository;

    public ManagerShowtimeController(AdminShowtimeService showtimeService, UserRepository userRepository) {
        this.showtimeService = showtimeService;
        this.userRepository = userRepository;
    }

    @GetMapping({"", "/", "/showtimes"})
    public String listShowtimes(
            @RequestParam(required = false) String status,
            Authentication authentication,
            HttpSession session,
            Model model) {

        // If an Administrator navigates here, redirect them to the full Admin showtime management page
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            return "redirect:/admin/showtimes";
        }

        Long cinemaId = resolveManagerCinemaId(authentication, session);
        if (cinemaId == null) {
            model.addAttribute("errorMessage", "Your account is not assigned to any cinema. Please contact an Administrator.");
            return "manager/showtimes/list";
        }

        Cinema cinema = showtimeService.getCinemaById(cinemaId);

        model.addAttribute("cinema", cinema);
        model.addAttribute("showtimes", showtimeService.getShowtimes(cinemaId, status));
        model.addAttribute("rooms", showtimeService.getRoomsByCinema(cinemaId));
        model.addAttribute("movies", showtimeService.getAllMovies());
        model.addAttribute("selectedStatus", status != null ? status : "ALL");

        return "manager/showtimes/list";
    }

    @PostMapping("/showtimes/create")
    public String createShowtime(
            @ModelAttribute AdminShowtimeRequestDto request,
            Authentication authentication,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Long cinemaId = resolveManagerCinemaId(authentication, session);
        if (cinemaId == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Your account is not assigned to any cinema.");
            return "redirect:/manager/showtimes";
        }

        try {
            showtimeService.createManagerShowtime(cinemaId, request);
            redirectAttributes.addFlashAttribute("successMessage", "Showtime created successfully!");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/manager/showtimes";
    }

    @PostMapping("/showtimes/edit/{id}")
    public String updateShowtime(
            @PathVariable Long id,
            @ModelAttribute AdminShowtimeRequestDto request,
            Authentication authentication,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Long cinemaId = resolveManagerCinemaId(authentication, session);
        if (cinemaId == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Your account is not assigned to any cinema.");
            return "redirect:/manager/showtimes";
        }

        try {
            showtimeService.updateManagerShowtime(cinemaId, id, request);
            redirectAttributes.addFlashAttribute("successMessage", "Showtime updated successfully!");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/manager/showtimes";
    }

    @PostMapping("/showtimes/delete/{id}")
    public String deleteShowtime(
            @PathVariable Long id,
            Authentication authentication,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        Long cinemaId = resolveManagerCinemaId(authentication, session);
        if (cinemaId == null) {
            redirectAttributes.addFlashAttribute("errorMessage", "Your account is not assigned to any cinema.");
            return "redirect:/manager/showtimes";
        }

        try {
            showtimeService.deleteManagerShowtime(cinemaId, id);
            redirectAttributes.addFlashAttribute("successMessage", "Showtime deleted successfully!");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        return "redirect:/manager/showtimes";
    }

    private Long resolveManagerCinemaId(Authentication authentication, HttpSession session) {
        if (session != null) {
            UserSummaryDto currentUser = (UserSummaryDto) session.getAttribute("currentUser");
            if (currentUser != null && currentUser.getCinemaId() != null) {
                return currentUser.getCinemaId();
            }
        }

        if (authentication != null && authentication.getName() != null) {
            User user = userRepository.findByEmail(authentication.getName()).orElse(null);
            if (user != null && user.getCinemaId() != null) {
                if (session != null) {
                    UserSummaryDto currentUser = (UserSummaryDto) session.getAttribute("currentUser");
                    if (currentUser != null) {
                        currentUser.setCinemaId(user.getCinemaId());
                    }
                }
                return user.getCinemaId();
            }
        }

        return null;
    }
}
