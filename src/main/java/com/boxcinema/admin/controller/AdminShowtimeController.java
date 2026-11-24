package com.boxcinema.admin.controller;

import com.boxcinema.admin.dto.AdminShowtimeRequestDto;
import com.boxcinema.admin.service.AdminShowtimeService;
import com.boxcinema.common.entity.Showtime;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

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
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Model model) {

        List<Showtime> allShowtimes = showtimeService.getShowtimes(cinemaId, status);
        int totalElements = allShowtimes != null ? allShowtimes.size() : 0;
        int pageSize = Math.max(1, size);
        int totalPages = totalElements == 0 ? 1 : (int) Math.ceil((double) totalElements / pageSize);
        int currentPage = Math.max(0, Math.min(page, Math.max(0, totalPages - 1)));

        int fromIndex = Math.min(currentPage * pageSize, totalElements);
        int toIndex = Math.min(fromIndex + pageSize, totalElements);
        List<Showtime> pagedShowtimes = (allShowtimes != null && totalElements > 0)
                ? allShowtimes.subList(fromIndex, toIndex)
                : java.util.Collections.emptyList();

        model.addAttribute("showtimes", pagedShowtimes);
        model.addAttribute("totalShowtimes", totalElements);
        model.addAttribute("currentPage", currentPage);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("pageSize", pageSize);
        model.addAttribute("hasNext", currentPage < totalPages - 1);
        model.addAttribute("hasPrevious", currentPage > 0);
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
