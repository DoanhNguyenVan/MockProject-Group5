package com.boxcinema.controller;

import com.boxcinema.entity.Showtime;
import com.boxcinema.repository.ShowtimeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Read-only screening metadata for the frontend booking flow. Does not reserve seats. */
@RestController
public class BookingContextApiController {
    private final ShowtimeRepository showtimes;

    public BookingContextApiController(ShowtimeRepository showtimes) {
        this.showtimes = showtimes;
    }

    @GetMapping("/api/quick-booking/showtimes/{showtimeId}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> context(@PathVariable Long showtimeId) {
        Showtime showtime = showtimes.findById(showtimeId).orElse(null);
        if (showtime == null) {
            return error(HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND", "This screening could not be found. Please choose another showtime.");
        }
        if (!"ACTIVE".equals(showtime.getStatus()) || !"ACTIVE".equals(showtime.getRoom().getStatus())
                || !"ACTIVE".equals(showtime.getRoom().getCinema().getStatus())
                || !showtime.getStartAt().isAfter(LocalDateTime.now())) {
            return error(HttpStatus.CONFLICT, "SHOWTIME_UNAVAILABLE", "This screening has started or is no longer available. Please choose another showtime.");
        }
        var movie = showtime.getMovie();
        var room = showtime.getRoom();
        var cinema = room.getCinema();
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(new ScreeningContext(
                showtime.getShowtimeId().toString(), movie.getMovieId().toString(), movie.getTitle(),
                movie.getAgeRating(), movie.getPosterUrl(), room.getRoomType() + " / " + movie.getLanguage(),
                cinema.getCinemaId().toString(), cinema.getCinemaName(), room.getRoomName(),
                showtime.getStartAt().format(DateTimeFormatter.ofPattern("dd MMM yyyy / HH:mm", Locale.ENGLISH)),
                showtime.getBasePrice()));
    }

    private ResponseEntity<?> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("success", false, "errorCode", code, "message", message));
    }

    public record ScreeningContext(String showtimeId, String movieId, String movieTitle, String ageRating,
            String posterUrl, String format, String cinemaId, String cinemaName, String roomName,
            String screeningLabel, BigDecimal basePrice) {}
}
