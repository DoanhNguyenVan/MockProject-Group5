
package com.boxcinema.admin.service.impl;

import com.boxcinema.common.entity.Genre;
import com.boxcinema.common.entity.Movie;
import com.boxcinema.common.repository.GenreRepository;
import com.boxcinema.common.repository.MovieRepository;
import com.boxcinema.common.repository.ShowtimeRepository;
import com.boxcinema.admin.service.AdminMovieManagementService;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@Transactional
public class AdminMovieManagementServiceImpl
        implements AdminMovieManagementService {

    private static final Set<String> VALID_MOVIE_STATUSES = Set.of("NOW_SHOWING", "COMING_SOON", "ENDED", "INACTIVE");

    private final MovieRepository movieRepository;
    private final GenreRepository genreRepository;
    private final ShowtimeRepository showtimeRepository;

    public AdminMovieManagementServiceImpl(
            MovieRepository movieRepository,
            GenreRepository genreRepository) {
        this(movieRepository, genreRepository, null);
    }

    @Autowired
    public AdminMovieManagementServiceImpl(
            MovieRepository movieRepository,
            GenreRepository genreRepository,
            @Autowired(required = false) ShowtimeRepository showtimeRepository) {
        this.movieRepository = movieRepository;
        this.genreRepository = genreRepository;
        this.showtimeRepository = showtimeRepository;
    }

    // Get all movies
    @Override
    @Transactional(readOnly = true)
    public List<Movie> getAllMovies() {
        return movieRepository.findAllWithGenres();
    }

    // Get all genres for movie form
    @Override
    @Transactional(readOnly = true)
    public List<Genre> getAllGenres() {
        return genreRepository.findAll()
                .stream()
                .sorted((a, b) ->
                        a.getGenreName().compareToIgnoreCase(b.getGenreName()))
                .toList();
    }

    // Get movie by ID
    @Override
    @Transactional(readOnly = true)
    public Movie getMovieById(Long movieId) {

        if (movieId == null) {
            throw new IllegalArgumentException("Movie ID is required.");
        }

        return movieRepository.findByIdWithGenres(movieId)
                .orElseThrow(() ->
                        new IllegalArgumentException("Movie not found."));
    }

    // Create movie
    @Override
    public Movie createMovie(Movie movie, Set<Long> genreIds) {

        validateMovie(movie);

        if (movieRepository.findByTitle(movie.getTitle().trim()).isPresent()) {
            throw new IllegalArgumentException(
                    "A movie with this title already exists.");
        }

        movie.setTitle(movie.getTitle().trim());
        movie.setGenres(findGenres(genreIds));

        return movieRepository.save(movie);
    }

    // Update movie
    @Override
    public Movie updateMovie(
            Long movieId,
            Movie updatedMovie,
            Set<Long> genreIds) {

        Movie existingMovie = getMovieById(movieId);

        validateMovie(updatedMovie);

        String newTitle = updatedMovie.getTitle().trim();

        movieRepository.findByTitle(newTitle)
                .filter(found -> !found.getMovieId().equals(movieId))
                .ifPresent(found -> {
                    throw new IllegalArgumentException(
                            "A movie with this title already exists.");
                });

        existingMovie.setTitle(newTitle);
        existingMovie.setSynopsis(updatedMovie.getSynopsis());
        existingMovie.setDurationMinutes(
                updatedMovie.getDurationMinutes());
        existingMovie.setAgeRating(updatedMovie.getAgeRating());
        existingMovie.setLanguage(updatedMovie.getLanguage());
        existingMovie.setReleaseDate(updatedMovie.getReleaseDate());
        existingMovie.setEndDate(updatedMovie.getEndDate());
        String nextStatus = updatedMovie.getStatus().trim().toUpperCase(Locale.ROOT);
        if (("ENDED".equals(nextStatus) || "INACTIVE".equals(nextStatus))
                && showtimeRepository != null
                && showtimeRepository.countUpcomingActiveShowtimesByMovie(movieId, LocalDateTime.now()) > 0) {
            throw new IllegalStateException("Cannot set movie status to " + nextStatus + " while upcoming active showtimes exist.");
        }
        existingMovie.setStatus(nextStatus);
        existingMovie.setPosterUrl(updatedMovie.getPosterUrl());
        existingMovie.setBannerUrl(updatedMovie.getBannerUrl());
        existingMovie.setTrailerUrl(updatedMovie.getTrailerUrl());

        existingMovie.setGenres(findGenres(genreIds));

        return movieRepository.save(existingMovie);
    }

    // Delete movie
    @Override
    public void deleteMovie(Long movieId) {

        Movie movie = getMovieById(movieId);
        if (showtimeRepository != null
                && showtimeRepository.countUpcomingActiveShowtimesByMovie(movieId, LocalDateTime.now()) > 0) {
            throw new IllegalStateException("Cannot delete this movie while upcoming active showtimes exist.");
        }

        movieRepository.delete(movie);
    }

    // Find selected genres
    private Set<Genre> findGenres(Set<Long> genreIds) {

        if (genreIds == null || genreIds.isEmpty()) {
            return new HashSet<>();
        }

        Set<Genre> genres = new HashSet<>(
                genreRepository.findAllById(genreIds));

        if (genres.size() != genreIds.size()) {
            throw new IllegalArgumentException(
                    "One or more selected genres do not exist.");
        }

        return genres;
    }

    // Validate movie information
    private void validateMovie(Movie movie) {

        if (movie == null) {
            throw new IllegalArgumentException(
                    "Movie information is required.");
        }

        if (movie.getTitle() == null || movie.getTitle().isBlank()) {
            throw new IllegalArgumentException(
                    "Movie title is required.");
        }

        if (movie.getTitle().trim().length() > 200) {
            throw new IllegalArgumentException(
                    "Movie title cannot exceed 200 characters.");
        }

        if (movie.getDurationMinutes() == null
                || movie.getDurationMinutes() <= 0) {
            throw new IllegalArgumentException(
                    "Duration must be greater than zero.");
        }

        if (movie.getAgeRating() == null
                || movie.getAgeRating().isBlank()) {
            throw new IllegalArgumentException(
                    "Age rating is required.");
        }

        if (movie.getLanguage() == null
                || movie.getLanguage().isBlank()) {
            throw new IllegalArgumentException(
                    "Language is required.");
        }

        if (movie.getReleaseDate() == null) {
            throw new IllegalArgumentException(
                    "Release date is required.");
        }

        if (movie.getEndDate() != null
                && movie.getEndDate().isBefore(movie.getReleaseDate())) {
            throw new IllegalArgumentException(
                    "End date cannot be before release date.");
        }

        if (movie.getStatus() == null || movie.getStatus().isBlank()) {
            throw new IllegalArgumentException(
                    "Movie status is required.");
        }
        String normalizedStatus = movie.getStatus().trim().toUpperCase(Locale.ROOT);
        if (!VALID_MOVIE_STATUSES.contains(normalizedStatus)) {
            throw new IllegalArgumentException(
                    "Invalid movie status. Allowed values: NOW_SHOWING, COMING_SOON, ENDED, INACTIVE.");
        }
        movie.setStatus(normalizedStatus);
    }
}
