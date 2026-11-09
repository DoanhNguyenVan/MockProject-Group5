package com.boxcinema.service;

import com.boxcinema.dto.DateTabDto;
import com.boxcinema.dto.MovieShowtimeGroupDto;
import com.boxcinema.dto.ShowtimeOptionDto;
import com.boxcinema.entity.Showtime;

import java.time.LocalDate;
import java.util.List;

public interface ShowtimeService {
    List<ShowtimeOptionDto> getShowtimeOptions(Long movieId, Long cinemaId, LocalDate date);
    List<LocalDate> getAvailableDates(Long movieId, Long cinemaId);
    java.util.Map<Long, String> getMovieCinemaIdsMap();
    List<MovieShowtimeGroupDto> getMovieShowtimeGroups(Long cinemaId, LocalDate date);
    List<DateTabDto> getDateTabs(LocalDate selectedDate);
}

