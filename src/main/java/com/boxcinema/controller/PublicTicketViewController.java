package com.boxcinema.controller;

import com.boxcinema.dto.PublicTicketDto;
import com.boxcinema.service.PublicTicketService;
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
        if (ticketOpt.isPresent()) {
            model.addAttribute("ticket", ticketOpt.get());
            model.addAttribute("found", true);
        } else {
            PublicTicketDto notFound = new PublicTicketDto();
            notFound.setValid(false);
            notFound.setMessage("Ticket information not found or access token is invalid.");
            model.addAttribute("ticket", notFound);
            model.addAttribute("found", false);
        }
        return "public/ticket-view";
    }

    @GetMapping({"/scan", "/ticket-scanner"})
    public String publicScanner() {
        return "public/scanner";
    }
}
