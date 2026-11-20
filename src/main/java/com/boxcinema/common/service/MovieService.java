package com.boxcinema.common.service;

import com.boxcinema.common.dto.MovieOptionDto;
import com.boxcinema.common.entity.Genre;
import com.boxcinema.common.entity.Movie;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public interface MovieService {
    List<Movie> getSpotlightMovies();
    List<Movie> getNowShowingMovies();
    List<Movie> getComingSoonMovies();
    List<Movie> getAllActiveMovies();
    Optional<Movie> getMovieById(Long id);
    List<MovieOptionDto> getNowShowingMovieOptions();
    default List<Genre> getAllGenres() {
        return Collections.emptyList();
    }
}

