package com.boxcinema.controller;

import com.boxcinema.dto.CinemaDto;
import com.boxcinema.dto.MovieOptionDto;
import com.boxcinema.dto.ShowtimeOptionDto;
import com.boxcinema.service.CinemaService;
import com.boxcinema.service.MovieService;
import com.boxcinema.service.ShowtimeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/quick-booking")
public class QuickBookingApiController {

    private final MovieService movieService;
    private final CinemaService cinemaService;
    private final ShowtimeService showtimeService;

    public QuickBookingApiController(
            MovieService movieService,
            CinemaService cinemaService,
            ShowtimeService showtimeService
    ) {
        this.movieService = movieService;
        this.cinemaService = cinemaService;
        this.showtimeService = showtimeService;
    }

    @GetMapping("/movies")
    public ResponseEntity<List<MovieOptionDto>> getMovies() {
        return ResponseEntity.ok(movieService.getNowShowingMovieOptions());
    }

    @GetMapping("/cinemas")
    public ResponseEntity<List<CinemaDto>> getCinemas(@RequestParam(required = false) Long movieId) {
        if (movieId != null) {
            return ResponseEntity.ok(cinemaService.getCinemasShowingMovie(movieId));
        }
        return ResponseEntity.ok(cinemaService.getActiveCinemaDtos());
    }

    @GetMapping("/dates")
    public ResponseEntity<List<Map<String, String>>> getDates(
            @RequestParam(required = false) Long movieId,
            @RequestParam(required = false) Long cinemaId
    ) {
        List<LocalDate> dates = showtimeService.getAvailableDates(movieId, cinemaId);
        List<Map<String, String>> result = new ArrayList<>();
        LocalDate today = LocalDate.now();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("EEE, dd/MM", Locale.ENGLISH);

        for (LocalDate d : dates) {
            Map<String, String> item = new HashMap<>();
            item.put("value", d.toString());
            if (d.equals(today)) {
                item.put("label", "Today (" + d.format(DateTimeFormatter.ofPattern("dd/MM")) + ")");
            } else if (d.equals(today.plusDays(1))) {
                item.put("label", "Tomorrow (" + d.format(DateTimeFormatter.ofPattern("dd/MM")) + ")");
            } else {
                item.put("label", d.format(formatter));
            }
            result.add(item);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/showtimes")
    public ResponseEntity<List<ShowtimeOptionDto>> getShowtimes(
            @RequestParam Long movieId,
            @RequestParam Long cinemaId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(showtimeService.getShowtimeOptions(movieId, cinemaId, date));
    }
}

