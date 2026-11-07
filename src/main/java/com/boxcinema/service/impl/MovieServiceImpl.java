package com.boxcinema.service.impl;

import com.boxcinema.dto.MovieOptionDto;
import com.boxcinema.entity.Movie;
import com.boxcinema.repository.MovieRepository;
import com.boxcinema.service.MovieService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class MovieServiceImpl implements MovieService {

    private final MovieRepository movieRepository;

    public MovieServiceImpl(MovieRepository movieRepository) {
        this.movieRepository = movieRepository;
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
                        // NOW_SHOWING: Mới chiếu / mới ra mắt lên đầu
                        if (m1.getReleaseDate() != null && m2.getReleaseDate() != null) {
                            int cmp = m2.getReleaseDate().compareTo(m1.getReleaseDate());
                            if (cmp != 0) return cmp;
                        }
                    } else if (s1 == 1) {
                        // COMING_SOON: Sắp khởi chiếu sớm nhất lên đầu
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
                .map(m -> new MovieOptionDto(m.getMovieId(), m.getTitle(), m.getAgeRating(), m.getDurationMinutes()))
                .collect(Collectors.toList());
    }
}
