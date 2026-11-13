package com.boxcinema.controller;

import com.boxcinema.service.MovieService;
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
    public String buyTickets(@RequestParam(required = false) Long movieId, Model model) {
        model.addAttribute("activePage", "showtimes");
        model.addAttribute("bookingMovie", movieId == null ? null : movieService.getMovieById(movieId).orElse(null));
        model.addAttribute("bookingMovieMissing", movieId != null && model.getAttribute("bookingMovie") == null);
        return "customer/buy-tickets";
    }
}
