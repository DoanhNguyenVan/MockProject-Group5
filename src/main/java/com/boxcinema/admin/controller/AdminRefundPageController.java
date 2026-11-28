package com.boxcinema.admin.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRefundPageController {

    @GetMapping("/refunds")
    public String refundsPage(Model model) {
        model.addAttribute("activePage", "admin");
        model.addAttribute("adminSection", "refunds");
        return "admin/refunds";
    }

    @GetMapping("/incidents")
    public String incidentsPage(Model model) {
        model.addAttribute("activePage", "admin");
        model.addAttribute("adminSection", "incidents");
        return "admin/incidents";
    }
}
