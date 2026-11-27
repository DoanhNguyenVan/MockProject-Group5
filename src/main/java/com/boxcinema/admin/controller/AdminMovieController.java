
package com.boxcinema.admin.controller;

import com.boxcinema.admin.service.AdminMovieManagementService;
import com.boxcinema.admin.service.MovieMediaUploadService;
import com.boxcinema.common.entity.Movie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Controller
@RequestMapping("/admin/movies")
public class AdminMovieController {

    private final AdminMovieManagementService movieService;

    @Autowired(required = false)
    private MovieMediaUploadService movieMediaUploadService;

    public AdminMovieController(AdminMovieManagementService movieService) {
        this.movieService = movieService;
    }

    @PostMapping("/upload-media")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> uploadMovieMedia(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "mediaType", defaultValue = "poster") String mediaType
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (movieMediaUploadService == null) {
            body.put("success", false);
            body.put("message", "Movie media upload service is unavailable.");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
        try {
            String url = movieMediaUploadService.uploadMovieMedia(file, mediaType);
            body.put("success", true);
            body.put("url", url);
            body.put("mediaType", mediaType != null ? mediaType.trim().toLowerCase() : "poster");
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException ex) {
            body.put("success", false);
            body.put("message", ex.getMessage());
            return ResponseEntity.badRequest().body(body);
        } catch (Exception ex) {
            body.put("success", false);
            body.put("message", "Unable to upload movie image right now. Please try again.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
        }
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

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    e.getMessage()
            );

            return "redirect:/admin/movies";
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
}
