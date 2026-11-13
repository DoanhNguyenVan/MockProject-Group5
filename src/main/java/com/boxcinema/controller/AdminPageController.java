package com.boxcinema.controller;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/** View-only admin workspace. Management mutations await the administration API. */
@Controller
public class AdminPageController {
    private static final Set<String> SECTIONS = Set.of(
            "dashboard", "movies", "genres", "cinemas", "rooms", "seats", "showtimes",
            "pricing", "promotions", "combos", "staff", "reports");

    @GetMapping({"/admin", "/admin/"})
    public String dashboard(Model model) {
        return workspace("dashboard", model);
    }

    @GetMapping("/admin/{section}")
    public String workspace(@PathVariable String section, Model model) {
        if (!SECTIONS.contains(section)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        model.addAttribute("activePage", "admin");
        model.addAttribute("adminSection", section);
        return "admin/workspace";
    }
}
