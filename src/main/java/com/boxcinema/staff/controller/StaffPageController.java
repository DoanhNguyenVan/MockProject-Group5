package com.boxcinema.staff.controller;

import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.staff.service.StaffPosService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
@RequestMapping("/staff")
@PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
public class StaffPageController {

    private final StaffPosService staffPosService;

    public StaffPageController(StaffPosService staffPosService) {
        this.staffPosService = staffPosService;
    }

    @GetMapping({"", "/"})
    public String staffRoot() {
        return "redirect:/staff/pos";
    }

    @GetMapping("/pos")
    public String posPage(Model model) {
        Long staffUserId = SecurityContextUtils.resolveCurrentUserId();
        model.addAttribute("activePage", "staff-pos");
        model.addAttribute("assignedCinemaName", staffPosService.getAssignedCinemaName(staffUserId));
        return "staff/pos";
    }

    @GetMapping("/food")
    public String foodKitchenPage(Model model) {
        model.addAttribute("activePage", "staff-food");
        return "staff/food";
    }
}
