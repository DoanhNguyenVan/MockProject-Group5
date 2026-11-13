package com.boxcinema.controller;

import com.boxcinema.entity.Cinema;
import com.boxcinema.entity.Movie;
import com.boxcinema.entity.Room;
import com.boxcinema.entity.Showtime;
import com.boxcinema.repository.ShowtimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingContextApiControllerTest {
    private ShowtimeRepository repository;
    private MockMvc mvc;
    private Showtime showtime;

    @BeforeEach
    void setup() {
        repository = mock(ShowtimeRepository.class);
        mvc = MockMvcBuilders.standaloneSetup(new BookingContextApiController(repository)).build();
        Movie movie = new Movie(); movie.setMovieId(16L); movie.setTitle("Alien: Romulus");
        movie.setAgeRating("T18"); movie.setPosterUrl("/assets/alien.jpg"); movie.setLanguage("English");
        Cinema cinema = new Cinema("BOX Landmark", "Address", "Ho Chi Minh City", "0900000000", "info@example.test");
        cinema.setCinemaId(3L); cinema.setStatus("ACTIVE");
        Room room = new Room(cinema, "Screen 2", "IMAX");
        showtime = new Showtime(movie, room, LocalDateTime.now().plusDays(1), LocalDateTime.now().plusDays(1).plusHours(2), new BigDecimal("140000"));
        showtime.setShowtimeId(42L);
        when(repository.findById(42L)).thenReturn(Optional.of(showtime));
    }

    @Test
    void returnsTheSelectedScreeningWithoutWritingBookings() throws Exception {
        mvc.perform(get("/api/quick-booking/showtimes/42"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.showtimeId").value("42"))
                .andExpect(jsonPath("$.movieId").value("16"))
                .andExpect(jsonPath("$.movieTitle").value("Alien: Romulus"))
                .andExpect(jsonPath("$.cinemaName").value("BOX Landmark"))
                .andExpect(jsonPath("$.roomName").value("Screen 2"))
                .andExpect(jsonPath("$.posterUrl").value("/assets/alien.jpg"))
                .andExpect(jsonPath("$.basePrice").value(140000))
                .andExpect(jsonPath("$.seats").doesNotExist());
        verify(repository).findById(42L); verifyNoMoreInteractions(repository);
    }

    @Test
    void unknownScreeningsReturnAnExplicitError() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        mvc.perform(get("/api/quick-booking/showtimes/99"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.errorCode").value("SHOWTIME_NOT_FOUND"));
    }

    @Test
    void startedOrInactiveScreeningsCannotBeUsedForBooking() throws Exception {
        showtime.setStartAt(LocalDateTime.now().minusMinutes(1));
        mvc.perform(get("/api/quick-booking/showtimes/42")).andExpect(status().isConflict());
        showtime.setStartAt(LocalDateTime.now().plusDays(1)); showtime.setStatus("CANCELLED");
        mvc.perform(get("/api/quick-booking/showtimes/42")).andExpect(status().isConflict());
    }
}
