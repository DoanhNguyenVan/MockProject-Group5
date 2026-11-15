package com.boxcinema.controller;

import com.boxcinema.dto.AdminAccountDto;
import com.boxcinema.security.BoxCinemaUserDetails;
import com.boxcinema.service.AdminAccountService;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/accounts")
public class AdminAccountController {
    private final AdminAccountService accountService;

    public AdminAccountController(AdminAccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "") String q,
                       @RequestParam(defaultValue = "") String role,
                       @RequestParam(defaultValue = "") String status,
                       @RequestParam(defaultValue = "0") int page,
                       Model model, Authentication authentication) {
        Page<AdminAccountDto> accounts;
        try {
            accounts = accountService.findAccounts(q, role, status, page);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        model.addAttribute("activePage", "admin");
        model.addAttribute("accounts", accounts);
        model.addAttribute("q", q);
        model.addAttribute("selectedRole", role);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("isFirst", accounts.isFirst());
        model.addAttribute("isLast", accounts.isLast());
        model.addAttribute("currentAdminId", currentAdminId(authentication));
        return "admin/accounts";
    }

    @PostMapping("/{id}/lock")
    public String lock(@PathVariable Long id, Authentication authentication, RedirectAttributes flash) {
        return updateStatus(id, true, authentication, flash);
    }

    @PostMapping("/{id}/unlock")
    public String unlock(@PathVariable Long id, Authentication authentication, RedirectAttributes flash) {
        return updateStatus(id, false, authentication, flash);
    }

    private String updateStatus(Long id, boolean lock, Authentication authentication, RedirectAttributes flash) {
        try {
            accountService.changeLockStatus(id, lock, currentAdminId(authentication));
            flash.addFlashAttribute("successMessage", lock ? "Account locked successfully." : "Account unlocked successfully.");
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/accounts";
    }

    private Long currentAdminId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof BoxCinemaUserDetails user)
                || user.getUserSummary() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator identity is unavailable.");
        }
        return user.getUserSummary().getUserId();
    }
}
