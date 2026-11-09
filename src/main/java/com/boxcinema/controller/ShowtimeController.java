package com.boxcinema.controller;

import com.boxcinema.dto.DateTabDto;
import com.boxcinema.dto.MovieShowtimeGroupDto;
import com.boxcinema.entity.Cinema;
import com.boxcinema.service.CinemaService;
import com.boxcinema.service.ShowtimeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.List;

@Controller
public class ShowtimeController {

    private final CinemaService cinemaService;
    private final ShowtimeService showtimeService;

    public ShowtimeController(CinemaService cinemaService, ShowtimeService showtimeService) {
        this.cinemaService = cinemaService;
        this.showtimeService = showtimeService;
    }

    @GetMapping("/showtimes")
    public String showtimesPage(
            @RequestParam(required = false) Long cinemaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            Model model
    ) {
        List<Cinema> allCinemas = cinemaService.getAllActiveCinemas();

        Cinema selectedCinema = null;
        if (cinemaId != null) {
            for (Cinema c : allCinemas) {
                if (c.getCinemaId().equals(cinemaId)) {
                    selectedCinema = c;
                    break;
                }
            }
        }
        if (selectedCinema == null && !allCinemas.isEmpty()) {
            selectedCinema = allCinemas.get(0);
        }
        Long finalCinemaId = selectedCinema != null ? selectedCinema.getCinemaId() : cinemaId;

        if (date == null) {
            date = LocalDate.now();
        }

        List<DateTabDto> dateTabs = showtimeService.getDateTabs(date);
        List<MovieShowtimeGroupDto> movieGroups = showtimeService.getMovieShowtimeGroups(finalCinemaId, date);

        model.addAttribute("activePage", "showtimes");
        model.addAttribute("allCinemas", allCinemas);
        model.addAttribute("selectedCinema", selectedCinema);
        model.addAttribute("selectedCinemaId", finalCinemaId);
        model.addAttribute("selectedDate", date.toString());
        model.addAttribute("dateTabs", dateTabs);
        model.addAttribute("movieGroups", movieGroups);

        return "customer/showtimes";
    }
}
