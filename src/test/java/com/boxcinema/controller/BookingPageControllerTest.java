package com.boxcinema.controller;

import org.junit.jupiter.api.Test;
import com.boxcinema.entity.Movie;
import com.boxcinema.service.MovieService;
import java.util.Optional;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

class BookingPageControllerTest {
    @Test
    void servesBookingViewWithoutDatabaseOrBookingMutations() throws Exception {
        MockMvcBuilders.standaloneSetup(new BookingPageController(mock(MovieService.class))).build()
                .perform(get("/booking/buy-tickets").param("showtimeId", "42"))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/buy-tickets"))
                .andExpect(model().attribute("activePage", "showtimes"));
    }

    @Test
    void usesTheClickedMoviesPosterAndMetadata() throws Exception {
        MovieService movies = mock(MovieService.class);
        Movie alien = new Movie();
        alien.setMovieId(7L);
        alien.setTitle("Alien: Romulus");
        alien.setPosterUrl("/assets/alien.jpg");
        when(movies.getMovieById(7L)).thenReturn(Optional.of(alien));
        MockMvcBuilders.standaloneSetup(new BookingPageController(movies)).build()
                .perform(get("/booking/buy-tickets").param("movieId", "7"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("bookingMovie", alien))
                .andExpect(model().attribute("bookingMovieMissing", false));
    }

    @Test
    void marksUnknownMoviesWithoutSubstitutingAnotherFilm() throws Exception {
        MovieService movies = mock(MovieService.class);
        when(movies.getMovieById(99L)).thenReturn(Optional.empty());
        MockMvcBuilders.standaloneSetup(new BookingPageController(movies)).build()
                .perform(get("/booking/buy-tickets").param("movieId", "99"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("bookingMovieMissing", true));
    }
}
