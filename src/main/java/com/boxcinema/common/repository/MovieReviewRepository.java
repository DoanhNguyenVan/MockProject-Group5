package com.boxcinema.common.repository;

import com.boxcinema.common.entity.MovieReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MovieReviewRepository extends JpaRepository<MovieReview, Long> {

    @Query("SELECT r FROM MovieReview r " +
           "JOIN FETCH r.user u " +
           "WHERE r.movie.movieId = :movieId " +
           "AND r.status = 'VISIBLE' " +
           "ORDER BY r.createdAt DESC")
    List<MovieReview> findVisibleByMovieIdOrderByCreatedAtDesc(@Param("movieId") Long movieId);

    Optional<MovieReview> findByMovie_MovieIdAndUser_UserId(Long movieId, Long userId);

    Optional<MovieReview> findByReviewIdAndMovie_MovieId(Long reviewId, Long movieId);
}
