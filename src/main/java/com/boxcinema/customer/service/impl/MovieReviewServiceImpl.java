package com.boxcinema.customer.service.impl;

import com.boxcinema.common.entity.Movie;
import com.boxcinema.common.entity.MovieReview;
import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.BookingRepository;
import com.boxcinema.common.repository.MovieRepository;
import com.boxcinema.common.repository.MovieReviewRepository;
import com.boxcinema.common.repository.UserRepository;
import com.boxcinema.customer.dto.request.MovieReviewSubmitRequestDto;
import com.boxcinema.customer.dto.response.MovieReviewItemDto;
import com.boxcinema.customer.dto.response.MovieReviewSummaryDto;
import com.boxcinema.customer.service.MovieReviewService;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Transactional
public class MovieReviewServiceImpl implements MovieReviewService {

    private static final int MAX_COMMENT_LENGTH = 1000;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm", Locale.ENGLISH);

    private final MovieReviewRepository movieReviewRepository;
    private final MovieRepository movieRepository;
    private final UserRepository userRepository;
    private final BookingRepository bookingRepository;

    public MovieReviewServiceImpl(
            MovieReviewRepository movieReviewRepository,
            MovieRepository movieRepository,
            UserRepository userRepository,
            BookingRepository bookingRepository
    ) {
        this.movieReviewRepository = movieReviewRepository;
        this.movieRepository = movieRepository;
        this.userRepository = userRepository;
        this.bookingRepository = bookingRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public MovieReviewSummaryDto getReviewsForMovie(Long movieId, Long currentUserId, boolean isAdmin, String sort) {
        if (movieId == null || !movieRepository.existsById(movieId)) {
            throw new NoSuchElementException("Movie not found: " + movieId);
        }
        return buildSummary(movieId, currentUserId, isAdmin, sort);
    }

    @Override
    public MovieReviewSummaryDto submitReview(Long movieId, Long currentUserId, boolean isAdmin, MovieReviewSubmitRequestDto request) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Please sign in to review this movie.");
        }
        if (request == null || request.getRating() == null || request.getRating() < 1 || request.getRating() > 5) {
            throw new IllegalArgumentException("Rating must be between 1 and 5 stars.");
        }
        String comment = request.getComment() != null ? request.getComment().trim() : "";
        if (comment.length() > MAX_COMMENT_LENGTH) {
            throw new IllegalArgumentException("Review comment must not exceed " + MAX_COMMENT_LENGTH + " characters.");
        }

        Movie movie = movieRepository.findById(movieId)
                .orElseThrow(() -> new NoSuchElementException("Movie not found: " + movieId));
        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new AccessDeniedException("User account not found."));

        if ("STAFF".equalsIgnoreCase(user.getRole())) {
            throw new AccessDeniedException("Staff counter accounts cannot post customer movie reviews.");
        }

        if (movieReviewRepository.findByMovie_MovieIdAndUser_UserId(movieId, currentUserId).isPresent()) {
            throw new IllegalStateException("You have already reviewed this movie. Reviews cannot be edited once posted.");
        }

        LocalDateTime now = LocalDateTime.now();
        MovieReview review = new MovieReview();
        review.setMovie(movie);
        review.setUser(user);
        review.setStatus("VISIBLE");
        review.setCreatedAt(now);
        review.setUpdatedAt(now);
        review.setRating(request.getRating());
        review.setComment(comment.isEmpty() ? null : comment);
        review.setVerifiedViewer(bookingRepository.existsConfirmedBookingForMovie(currentUserId, movieId));

        movieReviewRepository.saveAndFlush(review);
        return buildSummary(movieId, currentUserId, isAdmin, "newest");
    }

    @Override
    public MovieReviewSummaryDto deleteReview(Long movieId, Long reviewId, Long currentUserId, boolean isAdmin) {
        if (currentUserId == null) {
            throw new AccessDeniedException("Please sign in to delete a review.");
        }
        MovieReview review = movieReviewRepository.findByReviewIdAndMovie_MovieId(reviewId, movieId)
                .orElseThrow(() -> new NoSuchElementException("Review not found."));

        boolean isOwner = review.getUser() != null && currentUserId.equals(review.getUser().getUserId());
        if (!isOwner && !isAdmin) {
            throw new AccessDeniedException("You can only delete your own review.");
        }

        movieReviewRepository.delete(review);
        movieReviewRepository.flush();
        return buildSummary(movieId, currentUserId, isAdmin, "newest");
    }

    private MovieReviewSummaryDto buildSummary(Long movieId, Long currentUserId, boolean isAdmin, String sort) {
        List<MovieReview> rawList = movieReviewRepository.findVisibleByMovieIdOrderByCreatedAtDesc(movieId);

        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (int star = 5; star >= 1; star--) {
            counts.put(star, 0);
        }

        int total = rawList.size();
        int sum = 0;
        for (MovieReview r : rawList) {
            int rating = r.getRating() != null ? Math.max(1, Math.min(5, r.getRating())) : 5;
            sum += rating;
            counts.put(rating, counts.getOrDefault(rating, 0) + 1);
        }

        double average = total > 0 ? Math.round((sum / (double) total) * 10.0) / 10.0 : 0.0;

        String normalizedSort = sort != null ? sort.trim().toLowerCase() : "newest";
        List<MovieReview> sorted = new ArrayList<>(rawList);
        if ("highest".equals(normalizedSort)) {
            sorted.sort(Comparator.comparing(MovieReview::getRating, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(MovieReview::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        } else if ("lowest".equals(normalizedSort)) {
            sorted.sort(Comparator.comparing(MovieReview::getRating, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(MovieReview::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        }

        MovieReviewItemDto myReviewDto = null;
        List<MovieReviewItemDto> itemDtos = new ArrayList<>(sorted.size());
        for (MovieReview r : sorted) {
            MovieReviewItemDto dto = mapToItemDto(r, currentUserId, isAdmin);
            itemDtos.add(dto);
            if (dto.isOwner()) {
                myReviewDto = dto;
            }
        }

        boolean authenticated = currentUserId != null;
        boolean verifiedViewer = authenticated && bookingRepository.existsConfirmedBookingForMovie(currentUserId, movieId);

        MovieReviewSummaryDto summary = new MovieReviewSummaryDto();
        summary.setMovieId(movieId);
        summary.setAverageRating(average);
        summary.setTotalReviews(total);
        summary.setRatingCounts(counts);
        summary.setAuthenticated(authenticated);
        summary.setCanReview(authenticated);
        summary.setVerifiedViewer(verifiedViewer);
        summary.setMyReview(myReviewDto);
        summary.setReviews(itemDtos);
        return summary;
    }

    private MovieReviewItemDto mapToItemDto(MovieReview r, Long currentUserId, boolean isAdmin) {
        User author = r.getUser();
        Long authorId = author != null ? author.getUserId() : null;
        String authorName = (author != null && author.getFullName() != null && !author.getFullName().isBlank())
                ? author.getFullName().trim() : "Cinema Guest";
        String initial = authorName.substring(0, 1).toUpperCase(Locale.ENGLISH);

        boolean owner = currentUserId != null && currentUserId.equals(authorId);
        boolean edited = r.getCreatedAt() != null && r.getUpdatedAt() != null
                && r.getUpdatedAt().isAfter(r.getCreatedAt().plusSeconds(2));

        MovieReviewItemDto dto = new MovieReviewItemDto();
        dto.setReviewId(r.getReviewId());
        dto.setUserId(authorId);
        dto.setAuthorName(authorName);
        dto.setAuthorInitial(initial);
        dto.setAuthorAvatarUrl(author != null ? author.getAvatarUrl() : null);
        dto.setRating(r.getRating() != null ? r.getRating() : 5);
        dto.setComment(r.getComment());
        dto.setVerifiedViewer(r.isVerifiedViewer());
        dto.setOwner(owner);
        dto.setCanDelete(owner || isAdmin);
        dto.setEdited(edited);
        dto.setCreatedAt(r.getCreatedAt());
        dto.setFormattedDate(r.getCreatedAt() != null ? r.getCreatedAt().format(DATE_FORMATTER) : "");
        return dto;
    }
}
