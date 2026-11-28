
package com.boxcinema.admin.controller;

import com.boxcinema.admin.dto.MovieFormDto;
import com.boxcinema.common.entity.Movie;
import com.boxcinema.admin.service.AdminMovieManagementService;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;

@Controller
@RequestMapping("/admin/movies")
@PreAuthorize("hasRole('ADMIN')")
public class AdminMovieController {

    private final AdminMovieManagementService movieService;

    public AdminMovieController(AdminMovieManagementService movieService) {
        this.movieService = movieService;
    }

    @InitBinder("movie")
    public void initMovieBinder(WebDataBinder binder) {
        binder.setAllowedFields("title", "synopsis", "durationMinutes", "ageRating",
                "language", "releaseDate", "endDate", "status", "posterUrl", "bannerUrl", "trailerUrl");
    }

    @GetMapping
    public String listMovies(Model model) {

        model.addAttribute("movies", movieService.getAllMovies());
        model.addAttribute("genres", movieService.getAllGenres());

        return "admin/movies/list";
    }

    @GetMapping("/create")
    public String createForm(Model model) {

        Movie movie = new Movie();
        movie.setStatus("NOW_SHOWING");

        model.addAttribute("movie", movie);
        model.addAttribute("genres", movieService.getAllGenres());
        model.addAttribute("formTitle", "Add New Movie");

        return "admin/movies/form";
    }

    @PostMapping("/create")
    public String createMovie(
            @ModelAttribute("movie") MovieFormDto form,
            @RequestParam(required = false) Set<Long> genreIds,
            Model model,
            RedirectAttributes redirectAttributes) {

        try {
            movieService.createMovie(toMovieEntity(form), genreIds);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Movie created successfully!"
            );

            return "redirect:/admin/movies";

        } catch (IllegalArgumentException | IllegalStateException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage()
            );

            return "redirect:/admin/movies";
        }
    }

    @GetMapping("/edit/{id}")
    public String editForm(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes) {

        try {

            Movie movie = movieService.getMovieById(id);

            model.addAttribute("movie", movie);
            model.addAttribute("genres", movieService.getAllGenres());
            model.addAttribute("formTitle", "Edit Movie");

            return "admin/movies/form";

        } catch (IllegalArgumentException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage()
            );

            return "redirect:/admin/movies";
        }
    }

    @PostMapping("/edit/{id}")
    public String updateMovie(
            @PathVariable Long id,
            @ModelAttribute("movie") MovieFormDto form,
            @RequestParam(required = false) Set<Long> genreIds,
            Model model,
            RedirectAttributes redirectAttributes) {

        try {
            movieService.updateMovie(id, toMovieEntity(form), genreIds);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Movie updated successfully!"
            );

            return "redirect:/admin/movies";

        } catch (IllegalArgumentException | IllegalStateException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage()
            );

            return "redirect:/admin/movies";
        }
    }

    private Movie toMovieEntity(MovieFormDto form) {
        Movie movie = new Movie();
        if (form != null) {
            movie.setTitle(form.getTitle());
            movie.setSynopsis(form.getSynopsis());
            movie.setDurationMinutes(form.getDurationMinutes());
            movie.setAgeRating(form.getAgeRating());
            movie.setLanguage(form.getLanguage());
            movie.setReleaseDate(form.getReleaseDate());
            movie.setEndDate(form.getEndDate());
            movie.setStatus(form.getStatus());
            movie.setPosterUrl(form.getPosterUrl());
            movie.setBannerUrl(form.getBannerUrl());
            movie.setTrailerUrl(form.getTrailerUrl());
        }
        return movie;
    }

    @PostMapping("/delete/{id}")
    public String deleteMovie(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {

        try {

            movieService.deleteMovie(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Movie deleted successfully!"
            );

        } catch (DataIntegrityViolationException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    "Cannot delete this movie because it is linked to other records."
            );

        } catch (IllegalArgumentException | IllegalStateException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage()
            );
        }

        return "redirect:/admin/movies";
    }
}
