package com.boxcinema.staff.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/staff")
@PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
public class StaffPageController {

    @GetMapping({"", "/"})
    public String staffRoot() {
        return "redirect:/staff/checkin";
    }

    @GetMapping("/checkin")
    public String checkinPage(Model model) {
        model.addAttribute("activePage", "staff-checkin");
        return "staff/checkin";
    }

    @GetMapping("/food")
    public String foodKitchenPage(Model model) {
        model.addAttribute("activePage", "staff-food");
        return "staff/food";
    }

    @GetMapping("/incidents")
    public String incidentsPage(Model model) {
        model.addAttribute("activePage", "staff-incidents");
        return "staff/incidents";
    }
}
