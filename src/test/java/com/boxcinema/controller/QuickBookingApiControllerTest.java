package com.boxcinema.controller;

import com.boxcinema.config.SecurityConfig;
import com.boxcinema.dto.CinemaDto;
import com.boxcinema.dto.MovieOptionDto;
import com.boxcinema.dto.ShowtimeOptionDto;
import com.boxcinema.service.CinemaService;
import com.boxcinema.service.MovieService;
import com.boxcinema.service.ShowtimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(QuickBookingApiController.class)
@Import(SecurityConfig.class)
public class QuickBookingApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MovieService movieService;

    @MockBean
    private CinemaService cinemaService;

    @MockBean
    private ShowtimeService showtimeService;

    @Test
    void testGetMoviesApi() throws Exception {
        MovieOptionDto dto = new MovieOptionDto(1L, "Crimson Horizon", "T16", 148);
        when(movieService.getNowShowingMovieOptions()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/quick-booking/movies"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].movieId").value(1L))
                .andExpect(jsonPath("$[0].title").value("Crimson Horizon"));
    }

    @Test
    void testGetCinemasApi() throws Exception {
        CinemaDto dto = new CinemaDto(1L, "BOX Flagship District 1", "HCMC");
        when(cinemaService.getActiveCinemaDtos()).thenReturn(List.of(dto));

        mockMvc.perform(get("/api/quick-booking/cinemas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].cinemaId").value(1L))
                .andExpect(jsonPath("$[0].cinemaName").value("BOX Flagship District 1"));
    }

    @Test
    void testGetShowtimesApi() throws Exception {
        ShowtimeOptionDto slot = new ShowtimeOptionDto(10L, "18:30", "Screen 1", "IMAX Laser", BigDecimal.valueOf(120000));
        LocalDate date = LocalDate.now();
        when(showtimeService.getShowtimeOptions(1L, 1L, date)).thenReturn(List.of(slot));

        mockMvc.perform(get("/api/quick-booking/showtimes")
                        .param("movieId", "1")
                        .param("cinemaId", "1")
                        .param("date", date.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].showtimeId").value(10L))
                .andExpect(jsonPath("$[0].timeDisplay").value("18:30"))
                .andExpect(jsonPath("$[0].roomType").value("IMAX Laser"));
    }
}

