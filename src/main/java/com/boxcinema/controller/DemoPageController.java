package com.boxcinema.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller serving Module 01 Core Pages:
 * About (/about) and Contact (/contact).
 * Authentication pages (/login, /register, /logout, /auth/*) are handled by AuthController.
 */
@Controller
public class DemoPageController {

    // Home mapping is handled by HomeController with real dynamic database entities.

    @GetMapping("/about")
    public String aboutPage(Model model) {
        model.addAttribute("activePage", "about");
        return "customer/about";
    }

    @GetMapping("/contact")
    public String contactPage(Model model) {
        model.addAttribute("activePage", "contact");
        return "customer/contact";
    }
}
