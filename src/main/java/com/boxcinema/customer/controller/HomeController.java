package com.boxcinema.customer.controller;

import com.boxcinema.common.entity.Cinema;
import com.boxcinema.common.entity.Genre;
import com.boxcinema.common.entity.Movie;
import com.boxcinema.common.service.CinemaService;
import com.boxcinema.common.service.MovieService;
import com.boxcinema.customer.service.ShowtimeService;
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

    public HomeController(MovieService movieService,
                          CinemaService cinemaService,
                          ShowtimeService showtimeService) {
        this.movieService = movieService;
        this.cinemaService = cinemaService;
        this.showtimeService = showtimeService;
    }

    @GetMapping({"/", "/home"})
    public String homePage(Model model) {
        List<Movie> spotlightMovies = movieService.getSpotlightMovies();
        List<Movie> nowShowingMovies = movieService.getNowShowingMovies();
        List<Movie> comingSoonMovies = movieService.getComingSoonMovies();
        List<Movie> allMovies = movieService.getAllActiveMovies();
        List<Cinema> cinemas = cinemaService.getAllActiveCinemas();
        List<Genre> genres = movieService.getAllGenres();

        Map<Long, String> movieCinemaIdsMap = showtimeService.getMovieCinemaIdsMap();
        Map<Long, String> movieFormatsMap = showtimeService.getMovieFormatsMap();
        List<String> roomFormats = showtimeService.getAllActiveRoomFormats();
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
        model.addAttribute("movieFormatsMap", movieFormatsMap);
        model.addAttribute("roomFormats", roomFormats);
        model.addAttribute("genres", genres);

        return "customer/home";
    }
}
