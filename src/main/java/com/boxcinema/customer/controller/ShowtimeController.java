package com.boxcinema.customer.controller;

import com.boxcinema.customer.dto.response.DateTabDto;
import com.boxcinema.customer.dto.response.MovieShowtimeGroupDto;
import com.boxcinema.common.entity.Cinema;
import com.boxcinema.common.entity.Genre;
import com.boxcinema.common.service.CinemaService;
import com.boxcinema.customer.service.ShowtimeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class ShowtimeController {

    private final CinemaService cinemaService;
    private final ShowtimeService showtimeService;

    public ShowtimeController(CinemaService cinemaService,
                              ShowtimeService showtimeService) {
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
        List<Genre> genres = showtimeService.getAllGenres();
        List<String> roomFormats = showtimeService.getAllActiveRoomFormats();
        Map<String, List<Cinema>> cinemasByCity = allCinemas.stream()
                .collect(Collectors.groupingBy(Cinema::getCity, LinkedHashMap::new, Collectors.toList()));

        model.addAttribute("activePage", "showtimes");
        model.addAttribute("allCinemas", allCinemas);
        model.addAttribute("cinemasByCity", cinemasByCity);
        model.addAttribute("selectedCinema", selectedCinema);
        model.addAttribute("selectedCinemaId", finalCinemaId);
        model.addAttribute("selectedDate", date.toString());
        model.addAttribute("dateTabs", dateTabs);
        model.addAttribute("movieGroups", movieGroups);
        model.addAttribute("genres", genres);
        model.addAttribute("roomFormats", roomFormats);

        return "customer/showtimes";
    }
}
