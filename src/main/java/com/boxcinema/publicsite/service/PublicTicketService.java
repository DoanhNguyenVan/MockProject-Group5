package com.boxcinema.publicsite.service;

import com.boxcinema.publicsite.dto.response.PublicTicketDto;

import java.util.Optional;

public interface PublicTicketService {
    Optional<PublicTicketDto> getTicketByTokenForViewer(String token, Long viewerUserId, String viewerRole);
}
