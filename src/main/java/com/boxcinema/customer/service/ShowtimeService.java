package com.boxcinema.customer.service;

import com.boxcinema.customer.dto.response.DateTabDto;
import com.boxcinema.customer.dto.response.MovieShowtimeGroupDto;
import com.boxcinema.customer.dto.response.ShowtimeOptionDto;
import com.boxcinema.common.entity.Genre;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

public interface ShowtimeService {
    List<ShowtimeOptionDto> getShowtimeOptions(Long movieId, Long cinemaId, LocalDate date);
    List<LocalDate> getAvailableDates(Long movieId, Long cinemaId);
    java.util.Map<Long, String> getMovieCinemaIdsMap();
    java.util.Map<Long, String> getMovieFormatsMap();
    List<String> getAllActiveRoomFormats();
    List<MovieShowtimeGroupDto> getMovieShowtimeGroups(Long cinemaId, LocalDate date);
    List<DateTabDto> getDateTabs(LocalDate selectedDate);
    long countTotalRooms();
    long countTodayShowtimes();
    long countTotalShowtimes();
    default List<Genre> getAllGenres() {
        return Collections.emptyList();
    }
}

