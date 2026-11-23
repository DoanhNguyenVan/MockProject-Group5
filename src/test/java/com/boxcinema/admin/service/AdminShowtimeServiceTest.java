package com.boxcinema.admin.service;

import com.boxcinema.admin.dto.AdminShowtimeRequestDto;
import com.boxcinema.admin.service.impl.AdminShowtimeServiceImpl;
import com.boxcinema.common.entity.Cinema;
import com.boxcinema.common.entity.Movie;
import com.boxcinema.common.entity.Room;
import com.boxcinema.common.entity.Showtime;
import com.boxcinema.common.repository.BookingRepository;
import com.boxcinema.common.repository.CinemaRepository;
import com.boxcinema.common.repository.MovieRepository;
import com.boxcinema.common.repository.RoomRepository;
import com.boxcinema.common.repository.ShowtimeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminShowtimeServiceTest {

    @Mock
    private ShowtimeRepository showtimeRepository;

    @Mock
    private MovieRepository movieRepository;

    @Mock
    private CinemaRepository cinemaRepository;

    @Mock
    private RoomRepository roomRepository;

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private AdminShowtimeServiceImpl adminShowtimeService;

    private Cinema cinemaHanoi;
    private Cinema cinemaHcm;
    private Room room1;
    private Movie movie;

    @BeforeEach
    void setUp() {
        cinemaHanoi = new Cinema("BOX Cinema Hà Nội", "123 Phố Huế", "Hà Nội", "0241111111", "hanoi@boxcinema.vn");
        cinemaHanoi.setCinemaId(1L);

        cinemaHcm = new Cinema("BOX Cinema TP. Hồ Chí Minh", "456 Nguyễn Huệ", "TP. Hồ Chí Minh", "0282222222", "hcm@boxcinema.vn");
        cinemaHcm.setCinemaId(2L);

        room1 = new Room(cinemaHanoi, "Screen 01", "IMAX");
        room1.setRoomId(10L);

        movie = new Movie();
        movie.setMovieId(100L);
        movie.setTitle("Inception");
        movie.setDurationMinutes(148);
    }

    @Test
    void createShowtime_success() {
        AdminShowtimeRequestDto request = new AdminShowtimeRequestDto();
        request.setMovieId(100L);
        request.setCinemaId(1L);
        request.setRoomId(10L);
        request.setStartAt(LocalDateTime.of(2026, 11, 23, 18, 0));
        request.setEndAt(LocalDateTime.of(2026, 11, 23, 20, 30));
        request.setBasePrice(new BigDecimal("120000"));
        request.setStatus("ACTIVE");

        when(movieRepository.findById(100L)).thenReturn(Optional.of(movie));
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room1));
        when(showtimeRepository.countOverlappingShowtimes(eq(10L), isNull(), any(), any())).thenReturn(0L);
        when(showtimeRepository.save(any(Showtime.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Showtime created = adminShowtimeService.createShowtime(request);

        assertNotNull(created);
        assertEquals(movie, created.getMovie());
        assertEquals(room1, created.getRoom());
        assertEquals(new BigDecimal("120000"), created.getBasePrice());
        verify(showtimeRepository, times(1)).save(any(Showtime.class));
    }

    @Test
    void createShowtime_throwsWhenOverlapping() {
        AdminShowtimeRequestDto request = new AdminShowtimeRequestDto();
        request.setMovieId(100L);
        request.setCinemaId(1L);
        request.setRoomId(10L);
        request.setStartAt(LocalDateTime.of(2026, 11, 23, 18, 0));
        request.setEndAt(LocalDateTime.of(2026, 11, 23, 20, 30));

        when(movieRepository.findById(100L)).thenReturn(Optional.of(movie));
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room1));
        when(showtimeRepository.countOverlappingShowtimes(eq(10L), isNull(), any(), any())).thenReturn(1L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                adminShowtimeService.createShowtime(request)
        );

        assertTrue(ex.getMessage().contains("already has another showtime scheduled"));
        verify(showtimeRepository, never()).save(any());
    }

    @Test
    void createShowtime_throwsWhenRoomNotInCinema() {
        AdminShowtimeRequestDto request = new AdminShowtimeRequestDto();
        request.setMovieId(100L);
        request.setCinemaId(2L); // HCM selected, but room1 is in Hanoi
        request.setRoomId(10L);
        request.setStartAt(LocalDateTime.of(2026, 11, 23, 18, 0));
        request.setEndAt(LocalDateTime.of(2026, 11, 23, 20, 30));

        when(movieRepository.findById(100L)).thenReturn(Optional.of(movie));
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room1));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                adminShowtimeService.createShowtime(request)
        );

        assertTrue(ex.getMessage().contains("does not belong to this cinema"));
    }

    @Test
    void deleteShowtime_blocksWhenBookingsExist() {
        Showtime st = new Showtime(movie, room1, LocalDateTime.now(), LocalDateTime.now().plusHours(2), new BigDecimal("75000"));
        st.setShowtimeId(50L);

        when(showtimeRepository.findById(50L)).thenReturn(Optional.of(st));
        when(bookingRepository.countByShowtime_ShowtimeId(50L)).thenReturn(3L);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () ->
                adminShowtimeService.deleteShowtime(50L)
        );

        assertTrue(ex.getMessage().contains("Cannot delete showtime #50 because it already has 3 booking(s)"));
        verify(showtimeRepository, never()).delete(any());
    }

    @Test
    void deleteShowtime_successWhenNoBookings() {
        Showtime st = new Showtime(movie, room1, LocalDateTime.now(), LocalDateTime.now().plusHours(2), new BigDecimal("75000"));
        st.setShowtimeId(50L);

        when(showtimeRepository.findById(50L)).thenReturn(Optional.of(st));
        when(bookingRepository.countByShowtime_ShowtimeId(50L)).thenReturn(0L);

        adminShowtimeService.deleteShowtime(50L);

        verify(showtimeRepository, times(1)).delete(st);
    }

    @Test
    void createManagerShowtime_enforcesManagerCinemaId() {
        AdminShowtimeRequestDto request = new AdminShowtimeRequestDto();
        request.setMovieId(100L);
        request.setCinemaId(999L); // Malicious input trying another cinema
        request.setRoomId(10L);
        request.setStartAt(LocalDateTime.of(2026, 11, 23, 18, 0));
        request.setEndAt(LocalDateTime.of(2026, 11, 23, 20, 30));

        when(movieRepository.findById(100L)).thenReturn(Optional.of(movie));
        when(roomRepository.findById(10L)).thenReturn(Optional.of(room1));
        when(showtimeRepository.countOverlappingShowtimes(eq(10L), isNull(), any(), any())).thenReturn(0L);
        when(showtimeRepository.save(any(Showtime.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Showtime created = adminShowtimeService.createManagerShowtime(1L, request);

        assertNotNull(created);
        assertEquals(1L, request.getCinemaId()); // Overwritten to manager's cinema
    }

    @Test
    void updateManagerShowtime_throwsWhenShowtimeBelongsToAnotherCinema() {
        Room roomHcm = new Room(cinemaHcm, "Screen 01", "Standard");
        roomHcm.setRoomId(20L);

        Showtime hcmShowtime = new Showtime(movie, roomHcm, LocalDateTime.now(), LocalDateTime.now().plusHours(2), new BigDecimal("75000"));
        hcmShowtime.setShowtimeId(88L);

        when(showtimeRepository.findById(88L)).thenReturn(Optional.of(hcmShowtime));

        AdminShowtimeRequestDto request = new AdminShowtimeRequestDto();
        request.setMovieId(100L);
        request.setRoomId(10L);
        request.setStartAt(LocalDateTime.now().plusHours(1));
        request.setEndAt(LocalDateTime.now().plusHours(3));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                adminShowtimeService.updateManagerShowtime(1L, 88L, request) // Hanoi manager trying to edit HCM showtime
        );

        assertTrue(ex.getMessage().contains("You are not authorized to update showtimes for other cinemas"));
    }

    @Test
    void deleteManagerShowtime_throwsWhenShowtimeBelongsToAnotherCinema() {
        Room roomHcm = new Room(cinemaHcm, "Screen 01", "Standard");
        roomHcm.setRoomId(20L);

        Showtime hcmShowtime = new Showtime(movie, roomHcm, LocalDateTime.now(), LocalDateTime.now().plusHours(2), new BigDecimal("75000"));
        hcmShowtime.setShowtimeId(88L);

        when(showtimeRepository.findById(88L)).thenReturn(Optional.of(hcmShowtime));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                adminShowtimeService.deleteManagerShowtime(1L, 88L) // Hanoi manager trying to delete HCM showtime
        );

        assertTrue(ex.getMessage().contains("You are not authorized to delete showtimes for other cinemas"));
    }
}
