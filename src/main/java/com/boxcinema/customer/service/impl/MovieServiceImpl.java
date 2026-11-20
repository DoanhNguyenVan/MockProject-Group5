package com.boxcinema.customer.service.impl;

import com.boxcinema.common.dto.MovieOptionDto;
import com.boxcinema.common.entity.Genre;
import com.boxcinema.common.entity.Movie;
import com.boxcinema.common.mapper.CatalogMapper;
import com.boxcinema.common.repository.GenreRepository;
import com.boxcinema.common.repository.MovieRepository;
import com.boxcinema.common.service.MovieService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class MovieServiceImpl implements MovieService {

    private final MovieRepository movieRepository;
    private final GenreRepository genreRepository;

    public MovieServiceImpl(MovieRepository movieRepository) {
        this(movieRepository, null);
    }

    @Autowired
    public MovieServiceImpl(MovieRepository movieRepository,
                            @Autowired(required = false) GenreRepository genreRepository) {
        this.movieRepository = movieRepository;
        this.genreRepository = genreRepository;
    }

    @Override
    public List<Movie> getSpotlightMovies() {
        return movieRepository.findSpotlightMovies().stream()
                .limit(10)
                .collect(Collectors.toList());
    }

    @Override
    public List<Movie> getNowShowingMovies() {
        return movieRepository.findByStatusWithGenres("NOW_SHOWING").stream()
                .sorted(Comparator.comparing(Movie::getReleaseDate, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(Movie::getMovieId))
                .collect(Collectors.toList());
    }

    @Override
    public List<Movie> getComingSoonMovies() {
        return movieRepository.findByStatusWithGenres("COMING_SOON").stream()
                .sorted(Comparator.comparing(Movie::getReleaseDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Movie::getMovieId))
                .collect(Collectors.toList());
    }

    @Override
    public List<Movie> getAllActiveMovies() {
        return movieRepository.findAllWithGenres().stream()
                .sorted((m1, m2) -> {
                    int s1 = "NOW_SHOWING".equalsIgnoreCase(m1.getStatus()) ? 0 : ("COMING_SOON".equalsIgnoreCase(m1.getStatus()) ? 1 : 2);
                    int s2 = "NOW_SHOWING".equalsIgnoreCase(m2.getStatus()) ? 0 : ("COMING_SOON".equalsIgnoreCase(m2.getStatus()) ? 1 : 2);
                    if (s1 != s2) return Integer.compare(s1, s2);

                    if (s1 == 0) {
                        // NOW_SHOWING: Sort by newest release date first
                        if (m1.getReleaseDate() != null && m2.getReleaseDate() != null) {
                            int cmp = m2.getReleaseDate().compareTo(m1.getReleaseDate());
                            if (cmp != 0) return cmp;
                        }
                    } else if (s1 == 1) {
                        // COMING_SOON: Sort by earliest release date first
                        if (m1.getReleaseDate() != null && m2.getReleaseDate() != null) {
                            int cmp = m1.getReleaseDate().compareTo(m2.getReleaseDate());
                            if (cmp != 0) return cmp;
                        }
                    }
                    return Long.compare(m1.getMovieId(), m2.getMovieId());
                })
                .collect(Collectors.toList());
    }

    @Override
    public Optional<Movie> getMovieById(Long id) {
        return movieRepository.findByIdWithGenres(id);
    }

    @Override
    public List<MovieOptionDto> getNowShowingMovieOptions() {
        return getNowShowingMovies().stream()
                .map(CatalogMapper::toMovieOptionDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<Genre> getAllGenres() {
        return genreRepository != null ? genreRepository.findAll() : Collections.emptyList();
    }
}
