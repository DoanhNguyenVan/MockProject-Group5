package com.boxcinema.repository;

import com.boxcinema.entity.Cinema;
import com.boxcinema.entity.Showtime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ShowtimeRepository extends JpaRepository<Showtime, Long> {

    @Query("SELECT DISTINCT s.room.cinema FROM Showtime s " +
           "WHERE s.movie.movieId = :movieId " +
           "AND s.startAt >= :fromTime " +
           "AND s.status = 'ACTIVE' " +
           "ORDER BY s.room.cinema.cinemaName ASC")
    List<Cinema> findCinemasByMovie(@Param("movieId") Long movieId, @Param("fromTime") LocalDateTime fromTime);

    @Query("SELECT s FROM Showtime s " +
           "JOIN FETCH s.room r " +
           "JOIN FETCH r.cinema c " +
           "WHERE s.movie.movieId = :movieId " +
           "AND c.cinemaId = :cinemaId " +
           "AND s.startAt BETWEEN :start AND :end " +
           "AND s.status = 'ACTIVE' " +
           "ORDER BY s.startAt ASC")
    List<Showtime> findShowtimesByMovieCinemaAndDate(
            @Param("movieId") Long movieId,
            @Param("cinemaId") Long cinemaId,
            @Param("start") LocalDateTime start,
            @Param("end") LocalDateTime end
    );

    @Query("SELECT s FROM Showtime s " +
           "WHERE s.movie.movieId = :movieId " +
           "AND s.startAt >= :fromTime " +
           "AND s.status = 'ACTIVE' " +
           "ORDER BY s.startAt ASC")
    List<Showtime> findUpcomingShowtimesByMovie(@Param("movieId") Long movieId, @Param("fromTime") LocalDateTime fromTime);
}

