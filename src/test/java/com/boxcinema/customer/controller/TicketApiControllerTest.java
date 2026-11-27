package com.boxcinema.customer.controller;

import com.boxcinema.common.dto.BookingTicketViewDto;
import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import com.boxcinema.customer.service.TicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TicketApiControllerTest {

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void enforcesOwnershipAndGuestSessionForTicketAndQrEndpoints() throws Exception {
        TicketService service = mock(TicketService.class);
        TicketApiController controller = new TicketApiController(service);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(service.getBookingQrImage(10L, null, Set.of()))
                .thenThrow(new AccessDeniedException("Please sign in."));
        when(service.getTicketView(10L, null, Set.of()))
                .thenThrow(new AccessDeniedException("Please sign in."));

        // Unauthenticated without guest session -> 401
        mvc.perform(get("/api/customer/bookings/10/ticket/qr"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/10/ticket"))
                .andExpect(status().isUnauthorized());

        // Non-owner authenticated customer -> 403
        UserSummaryDto other = new UserSummaryDto(99L, "Other", "other@box.vn", "0909", "CUSTOMER");
        BoxCinemaUserDetails otherDetails = new BoxCinemaUserDetails(other);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(otherDetails, null, otherDetails.getAuthorities())
        );
        when(service.getBookingQrImage(10L, 99L, Set.of()))
                .thenThrow(new AccessDeniedException("Forbidden."));
        mvc.perform(get("/api/bookings/10/ticket/qr"))
                .andExpect(status().isForbidden());

        // Verified owner -> 200 OK
        UserSummaryDto owner = new UserSummaryDto(10L, "Owner", "owner@box.vn", "0901", "CUSTOMER");
        BoxCinemaUserDetails ownerDetails = new BoxCinemaUserDetails(owner);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ownerDetails, null, ownerDetails.getAuthorities())
        );
        BookingTicketViewDto dto = new BookingTicketViewDto();
        dto.setBookingId(10L);
        dto.setBookingCode("BOX-20261127-1010");
        when(service.getTicketView(10L, 10L, Set.of())).thenReturn(dto);
        when(service.getBookingQrImage(10L, 10L, Set.of())).thenReturn(new byte[]{1, 2, 3});

        mvc.perform(get("/api/customer/bookings/10/ticket"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingCode").value("BOX-20261127-1010"));
        mvc.perform(get("/api/customer/bookings/10/ticket/qr"))
                .andExpect(status().isOk());

        // Guest browser session matching bookingId -> 200 OK
        SecurityContextHolder.clearContext();
        MockHttpSession guestSession = new MockHttpSession();
        guestSession.setAttribute("BOX_GUEST_BOOKING_IDS", Set.of(55L));
        when(service.getBookingQrImage(eq(55L), eq(null), eq(Set.of(55L)))).thenReturn(new byte[]{9, 8, 7});
        mvc.perform(get("/api/bookings/55/ticket/qr").session(guestSession))
                .andExpect(status().isOk());
    }
}
