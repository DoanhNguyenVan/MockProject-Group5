package com.boxcinema.publicsite.controller;

import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.publicsite.dto.response.PublicTicketDto;
import com.boxcinema.publicsite.service.PublicTicketService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
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
    public String viewPublicTicket(
            @PathVariable("token") String token,
            Model model,
            HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Robots-Tag", "noindex, nofollow");

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long viewerUserId = SecurityContextUtils.extractUserId(auth);
        String viewerRole = SecurityContextUtils.extractRole(auth);
        boolean isStaff = SecurityContextUtils.hasStaffAuthority(auth);
        model.addAttribute("isStaff", isStaff);

        try {
            Optional<PublicTicketDto> ticketOpt =
                    publicTicketService.getTicketByTokenForViewer(token, viewerUserId, viewerRole);
            if (ticketOpt.isPresent()) {
                model.addAttribute("ticket", ticketOpt.get());
                model.addAttribute("found", true);
                model.addAttribute("accessDenied", false);
                model.addAttribute("ticketToken", token != null ? token.trim() : "");
            } else {
                PublicTicketDto notFound = new PublicTicketDto();
                notFound.setValid(false);
                notFound.setConfirmable(false);
                notFound.setTicketStatus("INVALID");
                notFound.setMessage("Ticket information not found or access token is invalid.");
                model.addAttribute("ticket", notFound);
                model.addAttribute("found", false);
                model.addAttribute("accessDenied", false);
                model.addAttribute("ticketToken", "");
            }
        } catch (AccessDeniedException ex) {
            response.setStatus(viewerUserId == null ? HttpServletResponse.SC_UNAUTHORIZED : HttpServletResponse.SC_FORBIDDEN);
            String rawMsg = ex.getMessage() != null ? ex.getMessage() : "You do not have permission to view this ticket.";
            boolean isWrongBranch = rawMsg.startsWith("WRONG_CINEMA_BRANCH|");
            String cleanMsg = isWrongBranch ? rawMsg.substring("WRONG_CINEMA_BRANCH|".length()) : rawMsg;

            PublicTicketDto denied = new PublicTicketDto();
            denied.setValid(false);
            denied.setConfirmable(false);
            denied.setTicketStatus(isWrongBranch ? "WRONG_CINEMA_BRANCH" : "ACCESS_DENIED");
            denied.setErrorCode(isWrongBranch ? "WRONG_CINEMA_BRANCH" : "ACCESS_DENIED");
            denied.setMessage(cleanMsg);

            model.addAttribute("ticket", denied);
            model.addAttribute("found", false);
            model.addAttribute("accessDenied", true);
            model.addAttribute("ticketToken", "");
        }
        return "public/ticket-view";
    }

    @GetMapping({"/scan", "/ticket-scanner"})
    public String publicScanner(Model model) {
        model.addAttribute("activePage", "staff-scan");
        return "public/scanner";
    }
}
