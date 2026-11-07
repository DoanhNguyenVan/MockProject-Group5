package com.boxcinema.repository;

import com.boxcinema.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MovieRepository extends JpaRepository<Movie, Long> {

    @Query("SELECT DISTINCT m FROM Movie m LEFT JOIN FETCH m.genres WHERE m.movieId = :id")
    Optional<Movie> findByIdWithGenres(@Param("id") Long id);

    @Query("SELECT DISTINCT m FROM Movie m LEFT JOIN FETCH m.genres " +
           "WHERE m.status = 'NOW_SHOWING' AND m.bannerUrl IS NOT NULL AND TRIM(m.bannerUrl) <> '' " +
           "ORDER BY m.releaseDate DESC, m.movieId ASC")
    List<Movie> findSpotlightMovies();

    @Query("SELECT DISTINCT m FROM Movie m LEFT JOIN FETCH m.genres " +
           "WHERE m.status = :status " +
           "ORDER BY m.releaseDate DESC, m.movieId ASC")
    List<Movie> findByStatusWithGenres(@Param("status") String status);

    @Query("SELECT DISTINCT m FROM Movie m LEFT JOIN FETCH m.genres " +
           "ORDER BY CASE WHEN m.status = 'NOW_SHOWING' THEN 1 WHEN m.status = 'COMING_SOON' THEN 2 ELSE 3 END, " +
           "m.releaseDate DESC, m.movieId ASC")
    List<Movie> findAllWithGenres();

    Optional<Movie> findByTitle(String title);
}
