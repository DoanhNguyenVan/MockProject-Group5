package com.boxcinema.admin.controller;

import com.boxcinema.admin.dto.AdminAccountDto;
import com.boxcinema.admin.service.AdminAccountService;
import com.boxcinema.common.util.SecurityContextUtils;
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
        var cinemas = accountService.getAllCinemas();
        model.addAttribute("activePage", "admin");
        model.addAttribute("accounts", accounts);
        model.addAttribute("cinemas", cinemas);
        model.addAttribute("cinemaNameMap", accountService.toCinemaNameMap(cinemas));
        model.addAttribute("accountMetrics", accountService.getAccountSummaryMetrics());
        model.addAttribute("q", q);
        model.addAttribute("selectedRole", role);
        model.addAttribute("selectedStatus", status);
        model.addAttribute("isFirst", accounts.isFirst());
        model.addAttribute("isLast", accounts.isLast());
        model.addAttribute("currentAdminId", SecurityContextUtils.requireAdminUserId(authentication));
        return "admin/accounts";
    }

    @PostMapping("/create")
    public String create(@RequestParam String fullName,
                         @RequestParam String email,
                         @RequestParam String phone,
                         @RequestParam(defaultValue = "STAFF") String role,
                         @RequestParam(required = false) Long cinemaId,
                         @RequestParam(required = false) String password,
                         Authentication authentication,
                         RedirectAttributes flash) {
        try {
            accountService.createAccount(fullName, email, phone, role, cinemaId, password,
                    SecurityContextUtils.requireAdminUserId(authentication));
            flash.addFlashAttribute("successMessage", "Account created successfully.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/accounts";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id,
                         @RequestParam String fullName,
                         @RequestParam String phone,
                         @RequestParam String role,
                         @RequestParam(required = false) Long cinemaId,
                         Authentication authentication,
                         RedirectAttributes flash) {
        try {
            accountService.updateAccount(id, fullName, phone, role, cinemaId,
                    SecurityContextUtils.requireAdminUserId(authentication));
            flash.addFlashAttribute("successMessage", "Account and role updated successfully.");
        } catch (IllegalArgumentException | IllegalStateException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/accounts";
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
            accountService.changeLockStatus(id, lock, SecurityContextUtils.requireAdminUserId(authentication));
            flash.addFlashAttribute("successMessage", lock ? "Account locked successfully." : "Account unlocked successfully.");
        } catch (IllegalStateException e) {
            flash.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/accounts";
    }
}
