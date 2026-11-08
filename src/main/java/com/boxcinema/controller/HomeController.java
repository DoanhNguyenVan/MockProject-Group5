package com.boxcinema.controller;

import com.boxcinema.entity.Cinema;
import com.boxcinema.entity.Genre;
import com.boxcinema.entity.Movie;
import com.boxcinema.repository.GenreRepository;
import com.boxcinema.service.CinemaService;
import com.boxcinema.service.MovieService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
public class HomeController {

    private final MovieService movieService;
    private final CinemaService cinemaService;
    private final GenreRepository genreRepository;

    public HomeController(MovieService movieService, CinemaService cinemaService, GenreRepository genreRepository) {
        this.movieService = movieService;
        this.cinemaService = cinemaService;
        this.genreRepository = genreRepository;
    }

    @GetMapping({"/", "/home"})
    public String homePage(Model model) {
        List<Movie> spotlightMovies = movieService.getSpotlightMovies();
        List<Movie> nowShowingMovies = movieService.getNowShowingMovies();
        List<Movie> comingSoonMovies = movieService.getComingSoonMovies();
        List<Movie> allMovies = movieService.getAllActiveMovies();
        List<Cinema> cinemas = cinemaService.getAllActiveCinemas();
        List<Genre> genres = genreRepository.findAll();

        model.addAttribute("activePage", "home");
        model.addAttribute("spotlightMovies", spotlightMovies);
        model.addAttribute("nowShowingMovies", nowShowingMovies);
        model.addAttribute("comingSoonMovies", comingSoonMovies);
        model.addAttribute("allMovies", allMovies);
        model.addAttribute("cinemas", cinemas);
        model.addAttribute("genres", genres);

        return "customer/home";
    }
}
