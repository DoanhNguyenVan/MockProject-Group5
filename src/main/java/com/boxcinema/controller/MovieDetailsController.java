package com.boxcinema.controller;

import com.boxcinema.service.MovieService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class MovieDetailsController {
    private final MovieService movieService;

    public MovieDetailsController(MovieService movieService) {
        this.movieService = movieService;
    }

    @GetMapping("/movies/{movieId}")
    public String details(@PathVariable Long movieId, Model model, HttpServletResponse response) {
        var movie = movieService.getMovieById(movieId).orElse(null);
        model.addAttribute("movie", movie);
        model.addAttribute("activePage", "home");
        if (movie == null) response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        return "customer/movie-details";
    }
}
