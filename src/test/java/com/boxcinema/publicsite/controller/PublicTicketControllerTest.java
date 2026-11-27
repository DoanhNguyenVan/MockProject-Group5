package com.boxcinema.publicsite.controller;

import com.boxcinema.publicsite.dto.response.PublicTicketDto;
import com.boxcinema.publicsite.service.PublicTicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicTicketControllerTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void viewShowsConfirmButtonForAuthenticatedStaffAndHidesForGuestOrCustomer() throws Exception {
        PublicTicketService service = mock(PublicTicketService.class);
        PublicTicketViewController controller = new PublicTicketViewController(service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        PublicTicketDto dto = new PublicTicketDto();
        dto.setValid(true);
        dto.setConfirmable(true);
        dto.setTicketStatus("VALID");
        dto.setBookingCode("BOX-20261127-2001");
        dto.setMovieTitle("Dune: Part Two");

        String token = "BOX1.ValidViewToken_0123456789ABCDEF";
        when(service.getPublicTicketByToken(token)).thenReturn(Optional.of(dto));

        // Guest (anonymous) sees ticket without staff confirmation button
        mvc.perform(get("/t/" + token))
                .andExpect(status().isOk())
                .andExpect(view().name("public/ticket-view"))
                .andExpect(model().attribute("isStaff", false))
                .andExpect(model().attribute("found", true));

        // Authenticated CUSTOMER must not receive isStaff=true
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("cust@boxcinema.vn", "pw",
                        List.of(new SimpleGrantedAuthority("ROLE_USER")))
        );
        mvc.perform(get("/t/" + token))
                .andExpect(status().isOk())
                .andExpect(model().attribute("isStaff", false));

        // Authenticated STAFF receives isStaff=true so CONFIRM TICKET USED renders
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("staff@boxcinema.vn", "pw",
                        List.of(new SimpleGrantedAuthority("ROLE_STAFF")))
        );
        mvc.perform(get("/t/" + token))
                .andExpect(status().isOk())
                .andExpect(model().attribute("isStaff", true))
                .andExpect(model().attribute("ticketToken", token));
    }

    @Test
    void apiReturnsNotFoundForInvalidTokenAndReturnsStatusForValidToken() throws Exception {
        PublicTicketService service = mock(PublicTicketService.class);
        PublicTicketApiController apiController = new PublicTicketApiController(service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(apiController).build();

        when(service.getPublicTicketByToken("bad-token")).thenReturn(Optional.empty());
        mvc.perform(get("/api/public/tickets/bad-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(content().string(not(containsString("stackTrace"))));

        PublicTicketDto valid = new PublicTicketDto();
        valid.setValid(true);
        valid.setConfirmable(true);
        valid.setTicketStatus("VALID");
        valid.setMovieTitle("Oppenheimer");
        when(service.getPublicTicketByToken("BOX1.ApiToken_0123456789ABCDEF")).thenReturn(Optional.of(valid));

        mvc.perform(get("/api/public/tickets/BOX1.ApiToken_0123456789ABCDEF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.ticketStatus").value("VALID"))
                .andExpect(jsonPath("$.movieTitle").value("Oppenheimer"));
    }
}
