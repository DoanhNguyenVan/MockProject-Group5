package com.boxcinema.common.repository;

import com.boxcinema.common.entity.MovieReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
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

    @EntityGraph(attributePaths = {"movie", "user", "repliedByAdmin"})
    @Query(
            value = "SELECT r FROM MovieReview r " +
                    "JOIN r.movie m " +
                    "JOIN r.user u " +
                    "WHERE (:movieId IS NULL OR m.movieId = :movieId) " +
                    "AND (:rating IS NULL OR r.rating = :rating) " +
                    "AND (:status IS NULL OR :status = '' OR UPPER(r.status) = UPPER(:status)) " +
                    "AND (:verified IS NULL OR r.verifiedViewer = :verified) " +
                    "AND (:keyword IS NULL OR :keyword = '' " +
                    "  OR LOWER(COALESCE(r.comment, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "  OR LOWER(COALESCE(r.adminReply, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "  OR LOWER(COALESCE(u.fullName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "  OR LOWER(COALESCE(u.email, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "  OR LOWER(COALESCE(m.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
                    "ORDER BY r.createdAt DESC",
            countQuery = "SELECT COUNT(r) FROM MovieReview r " +
                    "JOIN r.movie m " +
                    "JOIN r.user u " +
                    "WHERE (:movieId IS NULL OR m.movieId = :movieId) " +
                    "AND (:rating IS NULL OR r.rating = :rating) " +
                    "AND (:status IS NULL OR :status = '' OR UPPER(r.status) = UPPER(:status)) " +
                    "AND (:verified IS NULL OR r.verifiedViewer = :verified) " +
                    "AND (:keyword IS NULL OR :keyword = '' " +
                    "  OR LOWER(COALESCE(r.comment, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "  OR LOWER(COALESCE(r.adminReply, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "  OR LOWER(COALESCE(u.fullName, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "  OR LOWER(COALESCE(u.email, '')) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
                    "  OR LOWER(COALESCE(m.title, '')) LIKE LOWER(CONCAT('%', :keyword, '%')))"
    )
    Page<MovieReview> findAdminFilteredReviews(
            @Param("movieId") Long movieId,
            @Param("rating") Integer rating,
            @Param("status") String status,
            @Param("verified") Boolean verified,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    long countByStatusIgnoreCase(String status);

    long countByVerifiedViewerTrue();

    @Query("SELECT COALESCE(AVG(r.rating), 0.0) FROM MovieReview r WHERE UPPER(r.status) = 'VISIBLE'")
    Double findAverageVisibleRating();

    @Query("SELECT m.movieId, m.title, m.posterUrl, m.bannerUrl, AVG(r.rating), COUNT(r) " +
           "FROM MovieReview r " +
           "JOIN r.movie m " +
           "WHERE UPPER(r.status) = 'VISIBLE' " +
           "GROUP BY m.movieId, m.title, m.posterUrl, m.bannerUrl " +
           "ORDER BY AVG(r.rating) DESC, COUNT(r) DESC, m.movieId ASC")
    List<Object[]> findTopRatedMovies(Pageable pageable);

    List<MovieReview> findByReviewIdIn(Collection<Long> reviewIds);
}
