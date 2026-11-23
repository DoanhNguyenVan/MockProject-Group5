package com.boxcinema.admin.service;

import com.boxcinema.admin.dto.AdminShowtimeRequestDto;
import com.boxcinema.common.entity.Cinema;
import com.boxcinema.common.entity.Movie;
import com.boxcinema.common.entity.Room;
import com.boxcinema.common.entity.Showtime;

import java.util.List;

public interface AdminShowtimeService {

    List<Showtime> getShowtimes(Long cinemaId, String status);

    List<Cinema> getAllCinemas();

    List<Room> getAllActiveRooms();

    List<Movie> getAllMovies();

    Showtime getShowtimeById(Long id);

    Cinema getCinemaById(Long id);

    List<Room> getRoomsByCinema(Long cinemaId);

    Showtime createShowtime(AdminShowtimeRequestDto request);

    Showtime updateShowtime(Long id, AdminShowtimeRequestDto request);

    void deleteShowtime(Long id);

    Showtime createManagerShowtime(Long managerCinemaId, AdminShowtimeRequestDto request);

    Showtime updateManagerShowtime(Long managerCinemaId, Long showtimeId, AdminShowtimeRequestDto request);

    void deleteManagerShowtime(Long managerCinemaId, Long showtimeId);
}
