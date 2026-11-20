package com.boxcinema.common.mapper;

import com.boxcinema.common.dto.CinemaDto;
import com.boxcinema.common.dto.MovieOptionDto;
import com.boxcinema.customer.dto.response.ShowtimeOptionDto;
import com.boxcinema.common.entity.Cinema;
import com.boxcinema.common.entity.Movie;
import com.boxcinema.common.entity.Showtime;

/**
 * Pure mapper utility for Catalog entities (Cinema, Movie, Showtime) to DTOs.
 */
public final class CatalogMapper {

    private CatalogMapper() {
    }

    public static CinemaDto toCinemaDto(Cinema cinema) {
        if (cinema == null) {
            return null;
        }
        return new CinemaDto(cinema.getCinemaId(), cinema.getCinemaName(), cinema.getCity());
    }

    public static MovieOptionDto toMovieOptionDto(Movie movie) {
        if (movie == null) {
            return null;
        }
        return new MovieOptionDto(
                movie.getMovieId(),
                movie.getTitle(),
                movie.getAgeRating(),
                movie.getDurationMinutes()
        );
    }

    public static ShowtimeOptionDto toShowtimeOptionDto(Showtime showtime) {
        if (showtime == null) {
            return null;
        }
        return new ShowtimeOptionDto(
                showtime.getShowtimeId(),
                showtime.getFormattedStartTime(),
                showtime.getRoom() != null ? showtime.getRoom().getRoomName() : null,
                showtime.getRoom() != null ? showtime.getRoom().getRoomType() : null,
                showtime.getBasePrice()
        );
    }
}
