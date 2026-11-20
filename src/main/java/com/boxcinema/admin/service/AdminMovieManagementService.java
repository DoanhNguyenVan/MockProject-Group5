
package com.boxcinema.admin.service;

import com.boxcinema.common.entity.Genre;
import com.boxcinema.common.entity.Movie;

import java.util.List;
import java.util.Set;

public interface AdminMovieManagementService {

    List<Movie> getAllMovies();

    List<Genre> getAllGenres();

    Movie getMovieById(Long movieId);

    Movie createMovie(Movie movie, Set<Long> genreIds);

    Movie updateMovie(Long movieId, Movie movie, Set<Long> genreIds);

    void deleteMovie(Long movieId);
}
