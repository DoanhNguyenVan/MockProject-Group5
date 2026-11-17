package com.boxcinema.controller;

import com.boxcinema.dto.PublicTicketDto;
import com.boxcinema.service.PublicTicketService;
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
            notFound.setMessage("Không tìm thấy thông tin vé hoặc mã vé không hợp lệ.");
            return ResponseEntity.status(404).body(notFound);
        }
        return ResponseEntity.ok(ticketOpt.get());
    }
}
