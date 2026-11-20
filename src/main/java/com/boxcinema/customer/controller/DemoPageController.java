package com.boxcinema.customer.controller;

import com.boxcinema.common.constant.SessionConstants;
import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.Inquiry;
import com.boxcinema.common.service.CinemaService;
import com.boxcinema.customer.service.InquiryService;
import com.boxcinema.common.service.MovieService;
import com.boxcinema.customer.service.ShowtimeService;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Controller serving Module 01 Core Pages:
 * About (/about) and Contact (/contact).
 */
@Controller
public class DemoPageController {

    private final CinemaService cinemaService;
    private final MovieService movieService;
    private final ShowtimeService showtimeService;
    private final InquiryService inquiryService;

    public DemoPageController(CinemaService cinemaService,
                              MovieService movieService,
                              ShowtimeService showtimeService,
                              InquiryService inquiryService) {
        this.cinemaService = cinemaService;
        this.movieService = movieService;
        this.showtimeService = showtimeService;
        this.inquiryService = inquiryService;
    }

    @GetMapping("/about")
    public String aboutPage(Model model) {
        long activeCinemas = cinemaService.getAllActiveCinemas().size();
        long nowShowing = movieService.getNowShowingMovies().size();
        long comingSoon = movieService.getComingSoonMovies().size();
        long totalScreens = showtimeService.countTotalRooms();
        long todayShowtimes = showtimeService.countTodayShowtimes();
        long totalShowtimes = showtimeService.countTotalShowtimes();

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
        UserSummaryDto currentUser = (UserSummaryDto) session.getAttribute(SessionConstants.CURRENT_USER);
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

        UserSummaryDto currentUser = (UserSummaryDto) session.getAttribute(SessionConstants.CURRENT_USER);
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
