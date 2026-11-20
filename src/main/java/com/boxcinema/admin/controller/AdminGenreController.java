
package com.boxcinema.admin.controller;

import com.boxcinema.common.entity.Genre;
import com.boxcinema.admin.service.GenreManagementService;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/genres")
public class AdminGenreController {

    private final GenreManagementService genreService;

    public AdminGenreController(
            GenreManagementService genreService) {
        this.genreService = genreService;
    }

    @GetMapping
    public String listGenres(Model model) {
        model.addAttribute(
                "genres",
                genreService.getAllGenres()
        );

        return "admin/genres/list";
    }

    @GetMapping("/create")
    public String createGenreForm(Model model) {
        model.addAttribute("genre", new Genre());
        model.addAttribute("formTitle", "Add New Genre");

        return "admin/genres/form";
    }

    @PostMapping("/create")
    public String createGenre(
            @RequestParam String genreName,
            @RequestParam(required = false) String description,
            RedirectAttributes redirectAttributes) {

        try {
            genreService.createGenre(genreName, description);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Genre created successfully!"
            );

            return "redirect:/admin/genres";

        } catch (IllegalArgumentException ex) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );

            return "redirect:/admin/genres/create";
        }
    }

    @GetMapping("/edit/{id}")
    public String editGenreForm(
            @PathVariable Long id,
            Model model,
            RedirectAttributes redirectAttributes) {

        try {
            Genre genre = genreService.getGenreById(id);

            model.addAttribute("genre", genre);
            model.addAttribute("formTitle", "Edit Genre");

            return "admin/genres/form";

        } catch (IllegalArgumentException ex) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );

            return "redirect:/admin/genres";
        }
    }

    @PostMapping("/edit/{id}")
    public String updateGenre(
            @PathVariable Long id,
            @RequestParam String genreName,
            @RequestParam(required = false) String description,
            RedirectAttributes redirectAttributes) {

        try {
            genreService.updateGenre(id, genreName, description);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Genre updated successfully!"
            );

        } catch (IllegalArgumentException ex) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
        }

        return "redirect:/admin/genres";
    }

    @PostMapping("/delete/{id}")
    public String deleteGenre(
            @PathVariable Long id,
            RedirectAttributes redirectAttributes) {

        try {
            genreService.deleteGenre(id);

            redirectAttributes.addFlashAttribute(
                    "successMessage",
                    "Genre deleted successfully!"
            );

        } catch (IllegalArgumentException ex) {

            redirectAttributes.addFlashAttribute(
                    "errorMessage",
                    ex.getMessage()
            );
        }

        return "redirect:/admin/genres";
    }
}
