package com.boxcinema.publicsite.controller;

import com.boxcinema.common.config.SecurityConfig;
import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import com.boxcinema.publicsite.dto.response.PublicFoodItemDto;
import com.boxcinema.publicsite.dto.response.PublicTicketDto;
import com.boxcinema.publicsite.service.PublicTicketService;
import com.boxcinema.staff.controller.StaffCheckinApiController;
import com.boxcinema.staff.dto.response.StaffCheckinResponseDto;
import com.boxcinema.staff.service.StaffCheckinService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {PublicTicketViewController.class, PublicTicketApiController.class, StaffCheckinApiController.class})
@Import(SecurityConfig.class)
class TicketQrSecurityWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PublicTicketService publicTicketService;

    @MockBean
    private StaffCheckinService staffCheckinService;

    @MockBean
    private UserDetailsService userDetailsService;

    @Test
    void publicTicketPageRendersFullTicketWithoutConfirmButtonForCustomerAndWithConfirmButtonForStaff() throws Exception {
        String token = "BOX1.WebMvcValidToken_0123456789ABCDEF";
        PublicTicketDto dto = validTicketDto();
        when(publicTicketService.getPublicTicketByToken(token)).thenReturn(Optional.of(dto));

        // Anonymous scan renders ticket details cleanly without confirmation button
        mockMvc.perform(get("/t/" + token))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Interstellar")))
                .andExpect(content().string(containsString("BOX Cinema Royal City")))
                .andExpect(content().string(containsString("72A Nguyen Trai, Hanoi")))
                .andExpect(content().string(containsString("IMAX 1")))
                .andExpect(content().string(containsString("F7")))
                .andExpect(content().string(containsString("TKT-20261127-101")))
                .andExpect(content().string(not(containsString("btnConfirmTicketUsed"))));

        // Customer role cannot see CONFIRM TICKET USED
        mockMvc.perform(get("/t/" + token).with(user("customer@box.vn").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("btnConfirmTicketUsed"))));

        // Staff role sees CONFIRM TICKET USED button
        UserSummaryDto staff = new UserSummaryDto(15L, "Gate Staff", "staff@boxcinema.vn", "0901000002", "STAFF");
        mockMvc.perform(get("/t/" + token).with(user(new BoxCinemaUserDetails(staff))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("btnConfirmTicketUsed")))
                .andExpect(content().string(containsString("CONFIRM TICKET USED")));
    }

    @Test
    void usedTicketPageDisplaysWarningAndHidesConfirmButtonEvenForStaff() throws Exception {
        String token = "BOX1.WebMvcUsedToken_0123456789ABCDEF";
        PublicTicketDto used = validTicketDto();
        used.setValid(false);
        used.setConfirmable(false);
        used.setTicketStatus("USED");
        used.setCheckedInAt("18:30 - 27/11/2026");
        used.setStatusReason("This ticket was already checked in at 18:30 - 27/11/2026.");
        when(publicTicketService.getPublicTicketByToken(token)).thenReturn(Optional.of(used));

        UserSummaryDto staff = new UserSummaryDto(15L, "Gate Staff", "staff@boxcinema.vn", "0901000002", "STAFF");
        mockMvc.perform(get("/t/" + token).with(user(new BoxCinemaUserDetails(staff))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("USED")))
                .andExpect(content().string(containsString("This ticket was already checked in")))
                .andExpect(content().string(not(containsString("btnConfirmTicketUsed"))));
    }

    @Test
    void staffCheckinEndpointEnforcesRoleAuthorization() throws Exception {
        String payload = "{\"qrPayload\":\"BOX1.WebMvcValidToken_0123456789ABCDEF\"}";

        // Unauthenticated -> 401
        mockMvc.perform(post("/api/staff/bookings/check-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());

        // Customer -> 403
        mockMvc.perform(post("/api/staff/bookings/check-in")
                        .with(user("customer@box.vn").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        // Staff -> 200 OK
        UserSummaryDto staff = new UserSummaryDto(15L, "Gate Staff", "staff@boxcinema.vn", "0901000002", "STAFF");
        StaffCheckinResponseDto ok = new StaffCheckinResponseDto();
        ok.setSuccess(true);
        ok.setCode("SUCCESS");
        ok.setCheckInStatus("USED");
        when(staffCheckinService.checkinByQrToken(eq("BOX1.WebMvcValidToken_0123456789ABCDEF"), eq(15L)))
                .thenReturn(ok);

        mockMvc.perform(post("/api/staff/bookings/check-in")
                        .with(user(new BoxCinemaUserDetails(staff)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.checkInStatus").value("USED"));
    }

    @Test
    void publicScannerPageRendersCleanlyWithoutNgrok() throws Exception {
        mockMvc.perform(get("/scan"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("E-Ticket QR Scanner")))
                .andExpect(content().string(not(containsString("ngrok"))));
    }

    private static PublicTicketDto validTicketDto() {
        PublicTicketDto dto = new PublicTicketDto();
        dto.setValid(true);
        dto.setConfirmable(true);
        dto.setTicketStatus("VALID");
        dto.setPaymentStatus("COMPLETED");
        dto.setBookingStatus("CONFIRMED");
        dto.setBookingCode("BOX-20261127-1010");
        dto.setMovieTitle("Interstellar");
        dto.setMoviePosterUrl("https://res.cloudinary.com/demo/poster.webp");
        dto.setCinemaName("BOX Cinema Royal City");
        dto.setCinemaAddress("72A Nguyen Trai, Hanoi");
        dto.setRoomName("IMAX 1");
        dto.setShowDate("27/11/2026");
        dto.setShowTime("19:30");
        dto.setSeats(List.of("F7"));
        dto.setTicketCodes(List.of("TKT-20261127-101"));
        dto.setTicketCount(1);
        PublicFoodItemDto food = new PublicFoodItemDto();
        food.setProductName("Caramel Popcorn");
        food.setVariantName("Large");
        food.setQuantity(1);
        dto.setFoodItems(List.of(food));
        dto.setFoodFulfillmentStatus("Queued for Preparation");
        return dto;
    }
}
