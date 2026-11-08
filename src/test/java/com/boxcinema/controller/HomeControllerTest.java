package com.boxcinema.controller;

import com.boxcinema.entity.Cinema;
import com.boxcinema.entity.Movie;
import com.boxcinema.service.CinemaService;
import com.boxcinema.service.MovieService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(HomeController.class)
public class HomeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MovieService movieService;

    @MockBean
    private CinemaService cinemaService;

    @MockBean
    private com.boxcinema.repository.GenreRepository genreRepository;

    private Movie sampleMovie;
    private Cinema sampleCinema;

    @BeforeEach
    void setUp() {
        sampleMovie = new Movie();
        sampleMovie.setMovieId(1L);
        sampleMovie.setTitle("Crimson Horizon: Eclipse Protocol");
        sampleMovie.setDurationMinutes(148);
        sampleMovie.setAgeRating("T16");
        sampleMovie.setLanguage("English");
        sampleMovie.setReleaseDate(LocalDate.now());
        sampleMovie.setStatus("NOW_SHOWING");

        sampleCinema = new Cinema("BOX Flagship District 1", "123 Nguyen Hue", "HCMC", "02838123456", "d1@boxcinema.com");
        sampleCinema.setCinemaId(1L);
    }

    @Test
    void testHomePageEndpointReturnsCorrectViewAndAttributes() throws Exception {
        when(movieService.getSpotlightMovies()).thenReturn(List.of(sampleMovie));
        when(movieService.getNowShowingMovies()).thenReturn(List.of(sampleMovie));
        when(movieService.getComingSoonMovies()).thenReturn(Collections.emptyList());
        when(cinemaService.getAllActiveCinemas()).thenReturn(List.of(sampleCinema));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/home"))
                .andExpect(model().attributeExists("spotlightMovies"))
                .andExpect(model().attributeExists("nowShowingMovies"))
                .andExpect(model().attributeExists("comingSoonMovies"))
                .andExpect(model().attributeExists("cinemas"))
                .andExpect(model().attribute("activePage", "home"));

        mockMvc.perform(get("/home"))
                .andExpect(status().isOk())
                .andExpect(view().name("customer/home"));
    }
}

