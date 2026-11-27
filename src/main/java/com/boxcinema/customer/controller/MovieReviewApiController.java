package com.boxcinema.customer.controller;

import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.customer.dto.request.MovieReviewSubmitRequestDto;
import com.boxcinema.customer.service.MovieReviewService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/movies/{movieId}/reviews")
public class MovieReviewApiController {

    private final MovieReviewService movieReviewService;

    public MovieReviewApiController(MovieReviewService movieReviewService) {
        this.movieReviewService = movieReviewService;
    }

    @GetMapping
    public ResponseEntity<?> getMovieReviews(
            @PathVariable Long movieId,
            @RequestParam(required = false, defaultValue = "newest") String sort,
            HttpServletRequest request
    ) {
        try {
            Long userId = SecurityContextUtils.resolveCurrentUserId(request);
            return ResponseEntity.ok(movieReviewService.getReviewsForMovie(movieId, userId, isAdmin(), sort));
        } catch (NoSuchElementException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        }
    }

    @PostMapping
    public ResponseEntity<?> submitReview(
            @PathVariable Long movieId,
            @RequestBody MovieReviewSubmitRequestDto body,
            HttpServletRequest request
    ) {
        try {
            Long userId = SecurityContextUtils.resolveCurrentUserId(request);
            return ResponseEntity.ok(movieReviewService.submitReview(movieId, userId, isAdmin(), body));
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        } catch (NoSuchElementException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        }
    }

    @DeleteMapping("/{reviewId}")
    public ResponseEntity<?> deleteReview(
            @PathVariable Long movieId,
            @PathVariable Long reviewId,
            HttpServletRequest request
    ) {
        try {
            Long userId = SecurityContextUtils.resolveCurrentUserId(request);
            return ResponseEntity.ok(movieReviewService.deleteReview(movieId, reviewId, userId, isAdmin()));
        } catch (AccessDeniedException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        } catch (NoSuchElementException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "message", ex.getMessage()
            ));
        }
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equalsIgnoreCase(a.getAuthority()));
    }
}
