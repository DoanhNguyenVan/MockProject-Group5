package com.boxcinema.admin.service.impl;

import com.boxcinema.admin.dto.AdminMovieReviewDto;
import com.boxcinema.admin.dto.AdminMovieReviewStatsDto;
import com.boxcinema.admin.dto.AdminTopRatedMovieDto;
import com.boxcinema.admin.service.UserActivityLogService;
import com.boxcinema.common.entity.Movie;
import com.boxcinema.common.entity.MovieReview;
import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.MovieRepository;
import com.boxcinema.common.repository.MovieReviewRepository;
import com.boxcinema.common.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AdminMovieReviewServiceImplTest {

    private final MovieReviewRepository reviewRepo = mock(MovieReviewRepository.class);
    private final MovieRepository movieRepo = mock(MovieRepository.class);
    private final UserRepository userRepo = mock(UserRepository.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<UserActivityLogService> logProvider = mock(ObjectProvider.class);

    private AdminMovieReviewServiceImpl createService() {
        UserActivityLogService logService = mock(UserActivityLogService.class);
        when(logProvider.getIfAvailable()).thenReturn(logService);
        return new AdminMovieReviewServiceImpl(reviewRepo, movieRepo, userRepo, logProvider);
    }

    @Test
    void computesStatsAndFilteredPageCleanly() {
        AdminMovieReviewServiceImpl service = createService();

        when(reviewRepo.count()).thenReturn(10L);
        when(reviewRepo.countByStatusIgnoreCase("VISIBLE")).thenReturn(8L);
        when(reviewRepo.countByStatusIgnoreCase("HIDDEN")).thenReturn(2L);
        when(reviewRepo.countByVerifiedViewerTrue()).thenReturn(6L);
        when(reviewRepo.findAverageVisibleRating()).thenReturn(4.46);

        AdminMovieReviewStatsDto stats = service.getStats();
        assertEquals(10L, stats.getTotalReviews());
        assertEquals(8L, stats.getVisibleReviews());
        assertEquals(2L, stats.getHiddenReviews());
        assertEquals(6L, stats.getVerifiedViewers());
        assertEquals(4.5, stats.getAverageRating());

        MovieReview review = sampleReview(101L, "VISIBLE");
        when(reviewRepo.findAdminFilteredReviews(eq(7L), eq(5), eq("VISIBLE"), eq(true), eq("imax"), any()))
                .thenReturn(new PageImpl<>(List.of(review)));

        Page<AdminMovieReviewDto> page = service.getReviews(7L, 5, "visible", true, " imax ", PageRequest.of(0, 10));
        assertEquals(1, page.getTotalElements());
        assertEquals("Interstellar", page.getContent().get(0).getMovieTitle());
        assertEquals("Alice", page.getContent().get(0).getReviewerName());
    }

    @Test
    void updatesStatusAndOfficialReplyWithValidation() {
        AdminMovieReviewServiceImpl service = createService();
        MovieReview review = sampleReview(201L, "VISIBLE");
        User admin = new User();
        admin.setUserId(1L);
        admin.setFullName("System Admin");
        admin.setEmail("admin@boxcinema.vn");

        when(reviewRepo.findById(201L)).thenReturn(Optional.of(review));
        when(userRepo.findById(1L)).thenReturn(Optional.of(admin));
        when(reviewRepo.saveAndFlush(any(MovieReview.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminMovieReviewDto hidden = service.updateStatus(201L, "hidden", 1L);
        assertEquals("HIDDEN", hidden.getStatus());

        assertThrows(IllegalArgumentException.class, () -> service.updateStatus(201L, "ARCHIVED", 1L));

        AdminMovieReviewDto replied = service.replyToReview(201L, "  Thank you for visiting!  ", 1L);
        assertEquals("Thank you for visiting!", replied.getAdminReply());
        assertNotNull(replied.getAdminRepliedAt());

        AdminMovieReviewDto cleared = service.replyToReview(201L, "   ", 1L);
        assertNull(cleared.getAdminReply());
        assertNull(cleared.getAdminRepliedAt());

        assertThrows(IllegalArgumentException.class, () -> service.replyToReview(201L, "a".repeat(1001), 1L));
    }

    @Test
    void performsBulkHideRestoreAndDeleteSafely() {
        AdminMovieReviewServiceImpl service = createService();
        MovieReview r1 = sampleReview(1L, "VISIBLE");
        MovieReview r2 = sampleReview(2L, "VISIBLE");

        when(reviewRepo.findByReviewIdIn(List.of(1L, 2L))).thenReturn(List.of(r1, r2));

        int hiddenCount = service.bulkModerate(List.of(1L, 2L), "HIDE", 1L);
        assertEquals(2, hiddenCount);
        assertEquals("HIDDEN", r1.getStatus());
        assertEquals("HIDDEN", r2.getStatus());

        int restoredCount = service.bulkModerate(List.of(1L, 2L), "RESTORE", 1L);
        assertEquals(2, restoredCount);
        assertEquals("VISIBLE", r1.getStatus());
        assertEquals("VISIBLE", r2.getStatus());

        int deletedCount = service.bulkModerate(List.of(1L, 2L), "DELETE", 1L);
        assertEquals(2, deletedCount);
        verify(reviewRepo).deleteAllInBatch(List.of(r1, r2));

        assertThrows(IllegalArgumentException.class, () -> service.bulkModerate(List.of(), "HIDE", 1L));
    }

    @Test
    void mapsTopRatedMoviesWithRankRoundingAndBannerFallback() {
        AdminMovieReviewServiceImpl service = createService();
        List<Object[]> rows = List.of(
                new Object[]{7L, "Dune: Part Two", "/assets/img/posters/dune.jpg", "/assets/img/banners/dune.jpg", 4.86, 148L},
                new Object[]{8L, "Oppenheimer", "/assets/img/posters/oppenheimer.jpg", " ", 4.74, 92L}
        );

        when(reviewRepo.findTopRatedMovies(PageRequest.of(0, 5))).thenReturn(rows);

        List<AdminTopRatedMovieDto> top = service.getTopRatedMovies(5);
        assertEquals(2, top.size());
        assertEquals(1, top.get(0).getRank());
        assertEquals(7L, top.get(0).getMovieId());
        assertEquals(4.9, top.get(0).getAverageRating());
        assertEquals("/assets/img/banners/dune.jpg", top.get(0).getBannerUrl());
        assertEquals(148L, top.get(0).getReviewCount());

        assertEquals(2, top.get(1).getRank());
        assertEquals(4.7, top.get(1).getAverageRating());
        assertEquals("/assets/img/posters/oppenheimer.jpg", top.get(1).getBannerUrl());
    }

    private static MovieReview sampleReview(Long id, String status) {
        Movie movie = new Movie();
        movie.setMovieId(7L);
        movie.setTitle("Interstellar");

        User user = new User();
        user.setUserId(15L);
        user.setFullName("Alice");
        user.setEmail("alice@example.com");

        MovieReview r = new MovieReview();
        r.setReviewId(id);
        r.setMovie(movie);
        r.setUser(user);
        r.setRating(5);
        r.setComment("Great IMAX experience");
        r.setVerifiedViewer(true);
        r.setStatus(status);
        r.setCreatedAt(LocalDateTime.now());
        r.setUpdatedAt(r.getCreatedAt());
        return r;
    }
}
