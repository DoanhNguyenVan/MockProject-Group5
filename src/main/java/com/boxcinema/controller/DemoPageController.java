package com.boxcinema.controller;

import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.entity.Inquiry;
import com.boxcinema.repository.CinemaRepository;
import com.boxcinema.repository.MovieRepository;
import com.boxcinema.repository.RoomRepository;
import com.boxcinema.repository.ShowtimeRepository;
import com.boxcinema.service.InquiryService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Controller serving Module 01 Core Pages:
 * About (/about) and Contact (/contact).
 * Authentication pages (/login, /register, /logout, /auth/*) are handled by AuthController.
 */
@Controller
public class DemoPageController {

    private final CinemaRepository cinemaRepository;
    private final MovieRepository movieRepository;
    private final RoomRepository roomRepository;
    private final ShowtimeRepository showtimeRepository;
    private final InquiryService inquiryService;

    public DemoPageController(CinemaRepository cinemaRepository,
                              MovieRepository movieRepository,
                              RoomRepository roomRepository,
                              ShowtimeRepository showtimeRepository,
                              InquiryService inquiryService) {
        this.cinemaRepository = cinemaRepository;
        this.movieRepository = movieRepository;
        this.roomRepository = roomRepository;
        this.showtimeRepository = showtimeRepository;
        this.inquiryService = inquiryService;
    }

    // Home mapping is handled by HomeController with real dynamic database entities.

    @GetMapping("/about")
    public String aboutPage(Model model) {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime endOfDay = today.atTime(LocalTime.MAX);

        long activeCinemas = cinemaRepository.findByStatusOrderByCinemaNameAsc("ACTIVE").size();
        long nowShowing = movieRepository.findByStatusWithGenres("NOW_SHOWING").size();
        long comingSoon = movieRepository.findByStatusWithGenres("COMING_SOON").size();
        long totalScreens = roomRepository.count();
        long todayShowtimes = showtimeRepository.countShowtimesBetween(startOfDay, endOfDay);
        long totalShowtimes = showtimeRepository.count();

        model.addAttribute("activePage", "about");
        model.addAttribute("activeCinemasCount", activeCinemas);
        model.addAttribute("nowShowingCount", nowShowing);
        model.addAttribute("comingSoonCount", comingSoon);
        model.addAttribute("totalScreensCount", totalScreens);
        model.addAttribute("todayShowtimesCount", todayShowtimes);
        model.addAttribute("totalShowtimesCount", totalShowtimes);

        return "customer/about";
    }

    @GetMapping("/contact")
    public String contactPage(Model model, HttpSession session) {
        model.addAttribute("activePage", "contact");
        UserSummaryDto currentUser = (UserSummaryDto) session.getAttribute("currentUser");
        if (currentUser != null) {
            model.addAttribute("currentUser", currentUser);
        }
        return "customer/contact";
    }

    @PostMapping("/contact")
    public String submitContactForm(
            @RequestParam("fullName") String fullName,
            @RequestParam("email") String email,
            @RequestParam(value = "phone", required = false) String phone,
            @RequestParam("topic") String topic,
            @RequestParam("message") String message,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        UserSummaryDto currentUser = (UserSummaryDto) session.getAttribute("currentUser");
        Long userId = (currentUser != null) ? currentUser.getUserId() : null;

        try {
            Inquiry saved = inquiryService.createInquiry(fullName, email, phone, topic, message, userId);
            redirectAttributes.addFlashAttribute(
                    "contactSuccessMessage",
                    "Thank you! Your inquiry has been successfully submitted with ticket #" + saved.getInquiryId()
                            + ". A confirmation email has been sent to " + saved.getEmail()
                            + ". Our Guest Relations team will get back to you shortly.");
            redirectAttributes.addFlashAttribute("ticketCode", "BOX-REQ-" + saved.getInquiryId());
            return "redirect:/contact?success=true";
        } catch (IllegalArgumentException ex) {
            redirectAttributes.addFlashAttribute("contactErrorMessage", ex.getMessage());
            redirectAttributes.addFlashAttribute("enteredFullName", fullName);
            redirectAttributes.addFlashAttribute("enteredEmail", email);
            redirectAttributes.addFlashAttribute("enteredPhone", phone);
            redirectAttributes.addFlashAttribute("enteredTopic", topic);
            redirectAttributes.addFlashAttribute("enteredMessage", message);
            return "redirect:/contact";
        }
    }
}
