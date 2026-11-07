package com.boxcinema.service;

import com.boxcinema.dto.MovieOptionDto;
import com.boxcinema.entity.Movie;

import java.util.List;
import java.util.Optional;

public interface MovieService {
    List<Movie> getSpotlightMovies();
    List<Movie> getNowShowingMovies();
    List<Movie> getComingSoonMovies();
    List<Movie> getAllActiveMovies();
    Optional<Movie> getMovieById(Long id);
    List<MovieOptionDto> getNowShowingMovieOptions();
}

