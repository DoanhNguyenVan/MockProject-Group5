package com.boxcinema.refund.controller;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/manager")
@PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
public class ManagerPageController {

    @GetMapping({"", "/", "/refunds", "/dashboard"})
    public String refundsDashboard(Model model) {
        model.addAttribute("activePage", "manager");
        model.addAttribute("managerTab", "refunds");
        return "manager/dashboard";
    }

    @GetMapping("/incidents")
    public String incidentsDashboard(Model model) {
        model.addAttribute("activePage", "manager");
        model.addAttribute("managerTab", "incidents");
        return "manager/dashboard";
    }

    @GetMapping("/staff-errors")
    public String staffErrorsDashboard(Model model) {
        model.addAttribute("activePage", "manager");
        model.addAttribute("managerTab", "staff-errors");
        return "manager/dashboard";
    }
}
