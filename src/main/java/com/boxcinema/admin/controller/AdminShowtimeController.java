package com.boxcinema.admin.controller;

import com.boxcinema.admin.dto.AdminShowtimeRequestDto;
import com.boxcinema.admin.service.AdminShowtimeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/showtimes")
public class AdminShowtimeController {

    private final AdminShowtimeService showtimeService;

    public AdminShowtimeController(AdminShowtimeService showtimeService) {
        this.showtimeService = showtimeService;
    }

    @GetMapping
    public String listShowtimes(
            @RequestParam(required = false) Long cinemaId,
            @RequestParam(required = false) String status,
            Model model) {

        model.addAttribute("showtimes", showtimeService.getShowtimes(cinemaId, status));
        model.addAttribute("cinemas", showtimeService.getAllCinemas());
        model.addAttribute("rooms", showtimeService.getAllActiveRooms());
        model.addAttribute("movies", showtimeService.getAllMovies());
        model.addAttribute("selectedCinemaId", cinemaId);
        model.addAttribute("selectedStatus", status != null ? status : "ALL");

        return "admin/showtimes/list";
    }

    @PostMapping("/create")
    public String createShowtime(
            @ModelAttribute AdminShowtimeRequestDto request,
            RedirectAttributes redirectAttributes) {

        try {
            showtimeService.createShowtime(request);
            redirectAttributes.addFlashAttribute("successMessage", "Showtime created successfully!");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        if (request.getCinemaId() != null) {
            return "redirect:/admin/showtimes?cinemaId=" + request.getCinemaId();
        }
        return "redirect:/admin/showtimes";
    }

    @PostMapping("/edit/{id}")
    public String updateShowtime(
            @PathVariable Long id,
            @ModelAttribute AdminShowtimeRequestDto request,
            RedirectAttributes redirectAttributes) {

        try {
            showtimeService.updateShowtime(id, request);
            redirectAttributes.addFlashAttribute("successMessage", "Showtime updated successfully!");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        if (request.getCinemaId() != null) {
            return "redirect:/admin/showtimes?cinemaId=" + request.getCinemaId();
        }
        return "redirect:/admin/showtimes";
    }

    @PostMapping("/delete/{id}")
    public String deleteShowtime(
            @PathVariable Long id,
            @RequestParam(required = false) Long cinemaId,
            RedirectAttributes redirectAttributes) {

        try {
            showtimeService.deleteShowtime(id);
            redirectAttributes.addFlashAttribute("successMessage", "Showtime deleted successfully!");
        } catch (Exception ex) {
            redirectAttributes.addFlashAttribute("errorMessage", ex.getMessage());
        }

        if (cinemaId != null) {
            return "redirect:/admin/showtimes?cinemaId=" + cinemaId;
        }
        return "redirect:/admin/showtimes";
    }
}
