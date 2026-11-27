package com.boxcinema.publicsite.controller;

import com.boxcinema.publicsite.dto.response.PublicTicketDto;
import com.boxcinema.publicsite.service.PublicTicketService;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.Optional;

@Controller
public class PublicTicketViewController {

    private final PublicTicketService publicTicketService;

    public PublicTicketViewController(PublicTicketService publicTicketService) {
        this.publicTicketService = publicTicketService;
    }

    @GetMapping({"/t/{token}", "/t/{token:.+}"})
    public String viewPublicTicket(@PathVariable("token") String token, Model model) {
        Optional<PublicTicketDto> ticketOpt = publicTicketService.getPublicTicketByToken(token);
        boolean isStaff = hasStaffAuthority();
        model.addAttribute("isStaff", isStaff);

        if (ticketOpt.isPresent()) {
            model.addAttribute("ticket", ticketOpt.get());
            model.addAttribute("found", true);
            model.addAttribute("ticketToken", token != null ? token.trim() : "");
        } else {
            PublicTicketDto notFound = new PublicTicketDto();
            notFound.setValid(false);
            notFound.setConfirmable(false);
            notFound.setTicketStatus("INVALID");
            notFound.setMessage("Ticket information not found or access token is invalid.");
            model.addAttribute("ticket", notFound);
            model.addAttribute("found", false);
            model.addAttribute("ticketToken", "");
        }
        return "public/ticket-view";
    }

    @GetMapping({"/scan", "/ticket-scanner"})
    public String publicScanner() {
        return "public/scanner";
    }

    private boolean hasStaffAuthority() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return false;
        }
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority() != null ? a.getAuthority().toUpperCase() : "")
                .anyMatch(role -> "ROLE_STAFF".equals(role)
                        || "ROLE_MANAGER".equals(role)
                        || "ROLE_ADMIN".equals(role));
    }
}
