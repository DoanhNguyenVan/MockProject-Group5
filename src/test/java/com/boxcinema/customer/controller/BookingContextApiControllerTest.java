package com.boxcinema.customer.controller;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.Cinema;
import com.boxcinema.common.entity.Movie;
import com.boxcinema.common.entity.Room;
import com.boxcinema.common.entity.Showtime;
import com.boxcinema.common.repository.ShowtimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookingContextApiControllerTest {
    private ShowtimeRepository repository;
    private JdbcTemplate jdbcTemplate;
    private MockMvc mvc;
    private Showtime showtime;

    @BeforeEach
    void setup() {
        repository = mock(ShowtimeRepository.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        mvc = MockMvcBuilders.standaloneSetup(new BookingContextApiController(repository, jdbcTemplate)).build();
        Movie movie = new Movie();
        movie.setMovieId(16L);
        movie.setTitle("Alien: Romulus");
        movie.setAgeRating("T18");
        movie.setPosterUrl("/assets/alien.jpg");
        movie.setLanguage("English");
        Cinema cinema = new Cinema("BOX Landmark", "Address", "Ho Chi Minh City", "0900000000", "info@example.test");
        cinema.setCinemaId(3L);
        cinema.setStatus("ACTIVE");
        Room room = new Room(cinema, "Screen 2", "IMAX");
        room.setRoomId(11L);
        room.setStatus("ACTIVE");
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
        verify(repository).findById(42L);
        verifyNoMoreInteractions(repository);
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
        showtime.setStartAt(LocalDateTime.now().plusDays(1));
        showtime.setStatus("CANCELLED");
        mvc.perform(get("/api/quick-booking/showtimes/42")).andExpect(status().isConflict());
    }

    @Test
    @SuppressWarnings("unchecked")
    void holdsAvailableSeatsWithExpiryAndRejectsSweetboxIncompletePairOrExceedingMax() throws Exception {
        Map<String, Object> seatA1 = new LinkedHashMap<>(Map.of(
                "seatId", 101L, "seatTypeId", "1", "row", "A", "number", 1,
                "seatStatus", "ACTIVE", "typeName", "STANDARD", "surcharge", 0.0, "shapeType", "standard"
        ));
        seatA1.put("pairId", null);

        Map<String, Object> seatF1 = new LinkedHashMap<>(Map.of(
                "seatId", 102L, "seatTypeId", "3", "row", "F", "number", 1,
                "seatStatus", "ACTIVE", "typeName", "SWEETBOX", "surcharge", 50000.0, "shapeType", "sweetbox"
        ));
        seatF1.put("pairId", "F1-F2");

        Map<String, Object> seatF2 = new LinkedHashMap<>(Map.of(
                "seatId", 103L, "seatTypeId", "3", "row", "F", "number", 2,
                "seatStatus", "ACTIVE", "typeName", "SWEETBOX", "surcharge", 50000.0, "shapeType", "sweetbox"
        ));
        seatF2.put("pairId", "F1-F2");

        when(jdbcTemplate.query(contains("FROM seats s"), any(RowMapper.class), eq(11L)))
                .thenReturn(List.of(seatA1, seatF1, seatF2));
        when(jdbcTemplate.query(contains("FROM showtime_seats WHERE showtime_id = ? AND seat_id = ?"), any(RowMapper.class), eq(42L), any()))
                .thenReturn(Collections.emptyList());

        UserSummaryDto loggedInUser = new UserSummaryDto(1L, "Alex", "alex@boxcinema.com", "0901234567", "CUSTOMER");

        // 0. Unauthenticated hold attempt is rejected with 401
        mvc.perform(post("/api/booking/showtimes/42/hold")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[\"A1\"]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_NOT_AUTHENTICATED"));

        // 1. Holding A1 succeeds when authenticated and returns holdRemainingSeconds
        mvc.perform(post("/api/booking/showtimes/42/hold")
                        .sessionAttr("currentUser", loggedInUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[\"A1\"],\"durationSeconds\":600}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.heldSeatIds[0]").value("A1"))
                .andExpect(jsonPath("$.holdDurationSeconds").value(600));

        // 2. Selecting only half of a Sweetbox pair (F1 without F2) is rejected
        mvc.perform(post("/api/booking/showtimes/42/hold")
                        .sessionAttr("currentUser", loggedInUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[\"F1\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("SWEETBOX_PAIR_REQUIRED"));

        // 3. Selecting more than 8 seats is rejected
        mvc.perform(post("/api/booking/showtimes/42/hold")
                        .sessionAttr("currentUser", loggedInUser)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[\"A1\",\"A2\",\"A3\",\"A4\",\"A5\",\"A6\",\"A7\",\"A8\",\"A9\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("MAX_SEATS_EXCEEDED"));

        // 4. Releasing held seats succeeds
        mvc.perform(delete("/api/booking/showtimes/42/hold")
                        .sessionAttr("currentUser", loggedInUser))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsHoldWhenSeatIsAlreadyHeldByAnotherCustomer() throws Exception {
        UserSummaryDto currentUser = new UserSummaryDto(1L, "Alex", "alex@boxcinema.com", "0901234567", "CUSTOMER");
        Map<String, Object> seatD3 = new LinkedHashMap<>(Map.of(
                "seatId", 201L, "seatTypeId", "2", "row", "D", "number", 3,
                "seatStatus", "ACTIVE", "typeName", "VIP", "surcharge", 30000.0, "shapeType", "vip"
        ));
        seatD3.put("pairId", null);

        when(jdbcTemplate.query(contains("FROM seats s"), any(RowMapper.class), eq(11L)))
                .thenReturn(List.of(seatD3));
        Map<String, Object> otherCustomerHold = new LinkedHashMap<>();
        otherCustomerHold.put("status", "HELD");
        otherCustomerHold.put("userId", 999L);
        otherCustomerHold.put("sessionId", "other-user-session");
        otherCustomerHold.put("remSec", 420L);

        when(jdbcTemplate.query(contains("FROM showtime_seats WHERE showtime_id = ? AND seat_id = ?"), any(RowMapper.class), eq(42L), eq(201L)))
                .thenReturn(List.of(otherCustomerHold));

        mvc.perform(post("/api/booking/showtimes/42/hold")
                        .sessionAttr("currentUser", currentUser)
                        .header("X-Hold-Session", "my-current-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seatIds\":[\"D1\"]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SEAT_ALREADY_HELD_OR_BOOKED"))
                .andExpect(jsonPath("$.conflictingSeats[0]").value("D1"));
    }
}
