package com.boxcinema.service;

import com.boxcinema.dto.PublicTicketDto;

import java.util.Optional;

public interface PublicTicketService {
    Optional<PublicTicketDto> getPublicTicketByToken(String token);
}
