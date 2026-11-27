package com.boxcinema.publicsite.controller;

import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.publicsite.dto.response.PublicTicketDto;
import com.boxcinema.publicsite.service.PublicTicketService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
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
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long viewerUserId = SecurityContextUtils.extractUserId(auth);
        String viewerRole = SecurityContextUtils.extractRole(auth);

        try {
            Optional<PublicTicketDto> ticketOpt =
                    publicTicketService.getTicketByTokenForViewer(token, viewerUserId, viewerRole);
            if (ticketOpt.isEmpty()) {
                PublicTicketDto notFound = new PublicTicketDto();
                notFound.setValid(false);
                notFound.setMessage("Ticket information not found or token is invalid.");
                return ResponseEntity.status(404).body(notFound);
            }
            return ResponseEntity.ok(ticketOpt.get());
        } catch (AccessDeniedException ex) {
            String rawMsg = ex.getMessage() != null ? ex.getMessage() : "Access denied.";
            boolean isWrongBranch = rawMsg.startsWith("WRONG_CINEMA_BRANCH|");
            PublicTicketDto denied = new PublicTicketDto();
            denied.setValid(false);
            denied.setConfirmable(false);
            denied.setErrorCode(viewerUserId == null
                    ? "UNAUTHENTICATED"
                    : (isWrongBranch ? "WRONG_CINEMA_BRANCH" : "ACCESS_DENIED"));
            denied.setMessage(isWrongBranch ? rawMsg.substring("WRONG_CINEMA_BRANCH|".length()) : rawMsg);
            return ResponseEntity.status(viewerUserId == null ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN)
                    .body(denied);
        }
    }
}
