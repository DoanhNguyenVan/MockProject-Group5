package com.boxcinema.controller;

import com.boxcinema.entity.Movie;
import com.boxcinema.service.MovieService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MovieDetailsControllerTest {
    @Test
    void loadsTheRequestedMovieWithItsSynopsisAndTrailer() throws Exception {
        MovieService service = mock(MovieService.class);
        Movie movie = new Movie();
        movie.setMovieId(7L);
        movie.setTitle("Alien: Romulus");
        movie.setSynopsis("A crew explores an abandoned space station.");
        movie.setTrailerUrl("https://youtu.be/Abc123_-xyz");
        when(service.getMovieById(7L)).thenReturn(Optional.of(movie));
        MockMvcBuilders.standaloneSetup(new MovieDetailsController(service)).build()
                .perform(get("/movies/7"))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/movie-details"))
                .andExpect(model().attribute("movie", movie))
                .andExpect(model().attribute("activePage", "home"));
    }

    @Test
    void returnsNotFoundForMissingMovie() throws Exception {
        MovieService service = mock(MovieService.class);
        when(service.getMovieById(999L)).thenReturn(Optional.empty());
        MockMvcBuilders.standaloneSetup(new MovieDetailsController(service)).build()
                .perform(get("/movies/999"))
                .andExpect(status().isNotFound())
                .andExpect(view().name("customer/movie-details"));
    }
}
