package com.boxcinema.publicsite.controller;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import com.boxcinema.publicsite.dto.response.PublicTicketDto;
import com.boxcinema.publicsite.service.PublicTicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
    void viewEnforcesAuthAndShowsStaffToolsOnlyToStaff() throws Exception {
        PublicTicketService service = mock(PublicTicketService.class);
        PublicTicketViewController controller = new PublicTicketViewController(service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        PublicTicketDto ownerDto = new PublicTicketDto();
        ownerDto.setValid(true);
        ownerDto.setConfirmable(false);
        ownerDto.setViewerIsStaff(false);
        ownerDto.setCanDisplayQrImage(true);
        ownerDto.setTicketStatus("VALID");
        ownerDto.setBookingCode("BOX-20261127-2001");
        ownerDto.setMovieTitle("Dune: Part Two");

        PublicTicketDto staffDto = new PublicTicketDto();
        staffDto.setValid(true);
        staffDto.setConfirmable(true);
        staffDto.setViewerIsStaff(true);
        staffDto.setCanDisplayQrImage(false);
        staffDto.setTicketStatus("VALID");
        staffDto.setBookingCode("BOX-20261127-2001");
        staffDto.setMovieTitle("Dune: Part Two");

        String token = "BOX1.ValidViewToken_0123456789ABCDEF";
        when(service.getTicketByTokenForViewer(token, null, ""))
                .thenThrow(new AccessDeniedException("Authentication required."));
        when(service.getTicketByTokenForViewer(token, 10L, "CUSTOMER")).thenReturn(Optional.of(ownerDto));
        when(service.getTicketByTokenForViewer(token, 15L, "STAFF")).thenReturn(Optional.of(staffDto));

        // Anonymous rejected with 401 and found=false
        mvc.perform(get("/t/" + token))
                .andExpect(status().isUnauthorized())
                .andExpect(model().attribute("accessDenied", true))
                .andExpect(model().attribute("found", false));

        // Authenticated CUSTOMER owner sees ticket with isStaff=false
        UserSummaryDto cust = new UserSummaryDto(10L, "Buyer", "cust@boxcinema.vn", "0901000010", "CUSTOMER");
        BoxCinemaUserDetails custDetails = new BoxCinemaUserDetails(cust);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(custDetails, null, custDetails.getAuthorities())
        );
        mvc.perform(get("/t/" + token))
                .andExpect(status().isOk())
                .andExpect(model().attribute("isStaff", false))
                .andExpect(model().attribute("found", true));

        // Authenticated STAFF receives isStaff=true
        UserSummaryDto staff = new UserSummaryDto(15L, "Staff", "staff@boxcinema.vn", "0901000015", "STAFF");
        BoxCinemaUserDetails staffDetails = new BoxCinemaUserDetails(staff);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(staffDetails, null, staffDetails.getAuthorities())
        );
        mvc.perform(get("/t/" + token))
                .andExpect(status().isOk())
                .andExpect(model().attribute("isStaff", true))
                .andExpect(model().attribute("ticketToken", token));
    }

    @Test
    void apiReturns401ForUnauthenticated403ForDenied404ForInvalidAnd200ForAuthorized() throws Exception {
        PublicTicketService service = mock(PublicTicketService.class);
        PublicTicketApiController apiController = new PublicTicketApiController(service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(apiController).build();

        when(service.getTicketByTokenForViewer("bad-token", null, ""))
                .thenThrow(new AccessDeniedException("Authentication required."));
        mvc.perform(get("/api/public/tickets/bad-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.valid").value(false));

        UserSummaryDto staff = new UserSummaryDto(15L, "Staff", "staff@boxcinema.vn", "0901000015", "STAFF");
        BoxCinemaUserDetails staffDetails = new BoxCinemaUserDetails(staff);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(staffDetails, null, staffDetails.getAuthorities())
        );

        when(service.getTicketByTokenForViewer("bad-token", 15L, "STAFF")).thenReturn(Optional.empty());
        mvc.perform(get("/api/public/tickets/bad-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(content().string(not(containsString("stackTrace"))));

        when(service.getTicketByTokenForViewer("BOX1.WrongBranch_0123456789ABCDEF", 15L, "STAFF"))
                .thenThrow(new AccessDeniedException("WRONG_CINEMA_BRANCH|Wrong branch."));
        mvc.perform(get("/api/public/tickets/BOX1.WrongBranch_0123456789ABCDEF"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("WRONG_CINEMA_BRANCH"));

        PublicTicketDto valid = new PublicTicketDto();
        valid.setValid(true);
        valid.setConfirmable(true);
        valid.setTicketStatus("VALID");
        valid.setMovieTitle("Oppenheimer");
        when(service.getTicketByTokenForViewer("BOX1.ApiToken_0123456789ABCDEF", 15L, "STAFF"))
                .thenReturn(Optional.of(valid));

        mvc.perform(get("/api/public/tickets/BOX1.ApiToken_0123456789ABCDEF"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.ticketStatus").value("VALID"))
                .andExpect(jsonPath("$.movieTitle").value("Oppenheimer"));
    }
}
