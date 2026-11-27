package com.boxcinema.customer.service.impl;

import com.boxcinema.common.entity.Movie;
import com.boxcinema.common.entity.MovieReview;
import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.BookingRepository;
import com.boxcinema.common.repository.MovieRepository;
import com.boxcinema.common.repository.MovieReviewRepository;
import com.boxcinema.common.repository.UserRepository;
import com.boxcinema.customer.dto.request.MovieReviewSubmitRequestDto;
import com.boxcinema.customer.dto.response.MovieReviewSummaryDto;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MovieReviewServiceImplTest {

    private final MovieReviewRepository reviewRepo = mock(MovieReviewRepository.class);
    private final MovieRepository movieRepo = mock(MovieRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    private final BookingRepository bookingRepo = mock(BookingRepository.class);

    private final MovieReviewServiceImpl service =
            new MovieReviewServiceImpl(reviewRepo, movieRepo, userRepo, bookingRepo);

    @Test
    void calculatesAverageRatingAndStarCounts() {
        Movie movie = new Movie();
        movie.setMovieId(7L);

        User u1 = new User();
        u1.setUserId(1L);
        u1.setFullName("Alice");

        User u2 = new User();
        u2.setUserId(2L);
        u2.setFullName("Bob");

        MovieReview r1 = new MovieReview();
        r1.setReviewId(10L);
        r1.setMovie(movie);
        r1.setUser(u1);
        r1.setRating(5);
        r1.setComment("Masterpiece");
        r1.setVerifiedViewer(true);
        r1.setCreatedAt(LocalDateTime.now());
        r1.setUpdatedAt(r1.getCreatedAt());

        MovieReview r2 = new MovieReview();
        r2.setReviewId(11L);
        r2.setMovie(movie);
        r2.setUser(u2);
        r2.setRating(4);
        r2.setComment("Very good");
        r2.setVerifiedViewer(false);
        r2.setCreatedAt(LocalDateTime.now().minusHours(1));
        r2.setUpdatedAt(r2.getCreatedAt());

        when(movieRepo.existsById(7L)).thenReturn(true);
        when(reviewRepo.findVisibleByMovieIdOrderByCreatedAtDesc(7L)).thenReturn(List.of(r1, r2));
        when(bookingRepo.existsConfirmedBookingForMovie(1L, 7L)).thenReturn(true);

        MovieReviewSummaryDto summary = service.getReviewsForMovie(7L, 1L, false, "newest");

        assertEquals(4.5, summary.getAverageRating());
        assertEquals(2, summary.getTotalReviews());
        assertEquals(1, summary.getRatingCounts().get(5));
        assertEquals(1, summary.getRatingCounts().get(4));
        assertEquals(0, summary.getRatingCounts().get(1));
        assertTrue(summary.isVerifiedViewer());
        assertNotNull(summary.getMyReview());
        assertTrue(summary.getMyReview().isOwner());
    }

    @Test
    void rejectsOutOfRangeRating() {
        assertThrows(IllegalArgumentException.class,
                () -> service.submitReview(7L, 1L, false, new MovieReviewSubmitRequestDto(6, "Too high")));
        assertThrows(IllegalArgumentException.class,
                () -> service.submitReview(7L, 1L, false, new MovieReviewSubmitRequestDto(0, "Too low")));
    }

    @Test
    void blocksStaffFromSubmittingReview() {
        Movie movie = new Movie();
        movie.setMovieId(7L);
        User staff = new User();
        staff.setUserId(99L);
        staff.setRole("STAFF");

        when(movieRepo.findById(7L)).thenReturn(Optional.of(movie));
        when(userRepo.findById(99L)).thenReturn(Optional.of(staff));

        assertThrows(AccessDeniedException.class,
                () -> service.submitReview(7L, 99L, false, new MovieReviewSubmitRequestDto(5, "Great")));
    }

    @Test
    void createsReviewAndMarksVerifiedViewer() {
        Movie movie = new Movie();
        movie.setMovieId(7L);
        User customer = new User();
        customer.setUserId(15L);
        customer.setFullName("Doanh Nguyen");
        customer.setRole("CUSTOMER");

        when(movieRepo.findById(7L)).thenReturn(Optional.of(movie));
        when(movieRepo.existsById(7L)).thenReturn(true);
        when(userRepo.findById(15L)).thenReturn(Optional.of(customer));
        when(reviewRepo.findByMovie_MovieIdAndUser_UserId(7L, 15L)).thenReturn(Optional.empty());
        when(bookingRepo.existsConfirmedBookingForMovie(15L, 7L)).thenReturn(true);
        when(reviewRepo.saveAndFlush(any(MovieReview.class))).thenAnswer(inv -> {
            MovieReview saved = inv.getArgument(0);
            saved.setReviewId(55L);
            return saved;
        });
        when(reviewRepo.findVisibleByMovieIdOrderByCreatedAtDesc(7L)).thenReturn(List.of());

        MovieReviewSummaryDto res = service.submitReview(7L, 15L, false,
                new MovieReviewSubmitRequestDto(5, "Loved it!"));

        assertTrue(res.isVerifiedViewer());
        verify(reviewRepo).saveAndFlush(argThat(r ->
                r.getRating() == 5 && "Loved it!".equals(r.getComment()) && r.isVerifiedViewer()));
    }

    @Test
    void rejectsEditingOrRepostingExistingReview() {
        Movie movie = new Movie();
        movie.setMovieId(7L);
        User customer = new User();
        customer.setUserId(15L);
        customer.setRole("CUSTOMER");

        MovieReview existing = new MovieReview();
        existing.setReviewId(55L);

        when(movieRepo.findById(7L)).thenReturn(Optional.of(movie));
        when(userRepo.findById(15L)).thenReturn(Optional.of(customer));
        when(reviewRepo.findByMovie_MovieIdAndUser_UserId(7L, 15L)).thenReturn(Optional.of(existing));

        assertThrows(IllegalStateException.class,
                () -> service.submitReview(7L, 15L, false, new MovieReviewSubmitRequestDto(4, "Edited")));
        verify(reviewRepo, never()).saveAndFlush(any());
    }

    @Test
    void preventsNonOwnerNonAdminFromDeletingReview() {
        User owner = new User();
        owner.setUserId(10L);
        MovieReview review = new MovieReview();
        review.setReviewId(55L);
        review.setUser(owner);

        when(reviewRepo.findByReviewIdAndMovie_MovieId(55L, 7L)).thenReturn(Optional.of(review));

        assertThrows(AccessDeniedException.class,
                () -> service.deleteReview(7L, 55L, 20L, false));
    }
}
