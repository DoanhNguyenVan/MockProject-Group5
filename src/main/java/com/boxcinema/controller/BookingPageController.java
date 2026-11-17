package com.boxcinema.controller;

import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.security.BoxCinemaUserDetails;
import com.boxcinema.service.MovieService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** View only; seat holds and payments belong to the booking API. */
@Controller
public class BookingPageController {
    private final MovieService movieService;

    public BookingPageController(MovieService movieService) {
        this.movieService = movieService;
    }

    @GetMapping("/booking/buy-tickets")
    public String buyTickets(@RequestParam(required = false) Long movieId,
                             Model model,
                             HttpSession session,
                             Authentication authentication) {
        model.addAttribute("activePage", "showtimes");
        model.addAttribute("bookingMovie", movieId == null ? null : movieService.getMovieById(movieId).orElse(null));
        model.addAttribute("bookingMovieMissing", movieId != null && model.getAttribute("bookingMovie") == null);

        UserSummaryDto userSummary = null;
        if (session != null && session.getAttribute("currentUser") instanceof UserSummaryDto summary) {
            userSummary = summary;
        } else if (authentication != null && authentication.getPrincipal() instanceof BoxCinemaUserDetails details) {
            userSummary = details.getUserSummary();
            if (session != null) {
                session.setAttribute("currentUser", userSummary);
            }
        }
        if (userSummary != null) {
            model.addAttribute("currentUser", userSummary);
        }

        return "customer/buy-tickets";
    }
}
