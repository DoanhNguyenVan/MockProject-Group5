
package com.boxcinema.controller;

import com.boxcinema.entity.Genre;
import com.boxcinema.entity.Movie;
import com.boxcinema.service.AdminMovieManagementService;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.HashSet;
import java.util.Set;

@Controller
@RequestMapping("/admin/movies")
public class AdminMovieController {

    private final AdminMovieManagementService movieService;

    public AdminMovieController(AdminMovieManagementService movieService) {
        this.movieService = movieService;
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
            @ModelAttribute("movie") Movie movie,
            @RequestParam(required = false) Set<Long> genreIds,
            Model model,
            RedirectAttributes redirectAttributes) {

        try {

            movieService.createMovie(movie, genreIds);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Movie created successfully!"
            );

            return "redirect:/admin/movies";

        } catch (IllegalArgumentException e) {

            model.addAttribute("genres", movieService.getAllGenres());
            model.addAttribute("formTitle", "Add New Movie");
            model.addAttribute("errorMessage", e.getMessage());

            setSelectedGenres(movie, genreIds);

            return "admin/movies/form";
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
            @ModelAttribute("movie") Movie movie,
            @RequestParam(required = false) Set<Long> genreIds,
            Model model,
            RedirectAttributes redirectAttributes) {

        try {

            movieService.updateMovie(id, movie, genreIds);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Movie updated successfully!"
            );

            return "redirect:/admin/movies";

        } catch (IllegalArgumentException e) {

            movie.setMovieId(id);

            model.addAttribute("genres", movieService.getAllGenres());
            model.addAttribute("formTitle", "Edit Movie");
            model.addAttribute("errorMessage", e.getMessage());

            setSelectedGenres(movie, genreIds);

            return "admin/movies/form";
        }
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

        } catch (IllegalArgumentException e) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage()
            );
        }

        return "redirect:/admin/movies";
    }

    private void setSelectedGenres(Movie movie, Set<Long> genreIds) {

        Set<Genre> selectedGenres = new HashSet<>();

        if (genreIds != null) {

            for (Genre genre : movieService.getAllGenres()) {

                if (genreIds.contains(genre.getGenreId())) {
                    selectedGenres.add(genre);
                }
            }
        }

        movie.setGenres(selectedGenres);
    }
}
