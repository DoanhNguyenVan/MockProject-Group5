package com.boxcinema.controller;

import com.boxcinema.repository.CinemaRepository;
import com.boxcinema.repository.MovieRepository;
import com.boxcinema.repository.RoomRepository;
import com.boxcinema.repository.ShowtimeRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * Controller serving Module 01 Core Pages Live Demo:
 * Home (/), About (/about), Contact (/contact), Login (/login), and Sign Up (/register).
 */
@Controller
public class DemoPageController {

    private final CinemaRepository cinemaRepository;
    private final MovieRepository movieRepository;
    private final RoomRepository roomRepository;
    private final ShowtimeRepository showtimeRepository;

    public DemoPageController(CinemaRepository cinemaRepository,
                              MovieRepository movieRepository,
                              RoomRepository roomRepository,
                              ShowtimeRepository showtimeRepository) {
        this.cinemaRepository = cinemaRepository;
        this.movieRepository = movieRepository;
        this.roomRepository = roomRepository;
        this.showtimeRepository = showtimeRepository;
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
    public String contactPage(Model model) {
        model.addAttribute("activePage", "contact");
        return "customer/contact";
    }

    @GetMapping("/login")
    public String loginPage(Model model) {
        model.addAttribute("activePage", "login");
        return "auth/login";
    }

    @GetMapping({"/register", "/signup"})
    public String registerPage(Model model) {
        model.addAttribute("activePage", "register");
        return "auth/register";
    }
}
