package com.boxcinema.publicsite.controller;

import com.boxcinema.publicsite.dto.response.PublicTicketDto;
import com.boxcinema.publicsite.service.PublicTicketService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/public")
public class PublicTicketApiController {

    private final PublicTicketService publicTicketService;

    public PublicTicketApiController(PublicTicketService publicTicketService) {
        this.publicTicketService = publicTicketService;
    }

    @GetMapping({"/tickets/{token}", "/tickets/{token:.+}"})
    public ResponseEntity<PublicTicketDto> getPublicTicket(@PathVariable("token") String token) {
        Optional<PublicTicketDto> ticketOpt = publicTicketService.getPublicTicketByToken(token);
        if (ticketOpt.isEmpty()) {
            PublicTicketDto notFound = new PublicTicketDto();
            notFound.setValid(false);
            notFound.setMessage("Ticket information not found or token is invalid.");
            return ResponseEntity.status(404).body(notFound);
        }
        return ResponseEntity.ok(ticketOpt.get());
    }
}
