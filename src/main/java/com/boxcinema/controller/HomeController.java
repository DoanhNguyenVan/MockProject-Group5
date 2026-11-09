package com.boxcinema.controller;

import com.boxcinema.entity.Cinema;
import com.boxcinema.entity.Genre;
import com.boxcinema.entity.Movie;
import com.boxcinema.repository.GenreRepository;
import com.boxcinema.service.CinemaService;
import com.boxcinema.service.MovieService;
import com.boxcinema.service.ShowtimeService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class HomeController {

    private final MovieService movieService;
    private final CinemaService cinemaService;
    private final ShowtimeService showtimeService;
    private final GenreRepository genreRepository;

    public HomeController(MovieService movieService,
                          CinemaService cinemaService,
                          ShowtimeService showtimeService,
                          GenreRepository genreRepository) {
        this.movieService = movieService;
        this.cinemaService = cinemaService;
        this.showtimeService = showtimeService;
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

        Map<Long, String> movieCinemaIdsMap = showtimeService.getMovieCinemaIdsMap();
        Map<String, List<Cinema>> cinemasByCity = cinemas.stream()
                .collect(Collectors.groupingBy(Cinema::getCity, LinkedHashMap::new, Collectors.toList()));

        model.addAttribute("activePage", "home");
        model.addAttribute("spotlightMovies", spotlightMovies);
        model.addAttribute("nowShowingMovies", nowShowingMovies);
        model.addAttribute("comingSoonMovies", comingSoonMovies);
        model.addAttribute("allMovies", allMovies);
        model.addAttribute("cinemas", cinemas);
        model.addAttribute("cinemasByCity", cinemasByCity);
        model.addAttribute("movieCinemaIdsMap", movieCinemaIdsMap);
        model.addAttribute("genres", genres);

        return "customer/home";
    }
}
