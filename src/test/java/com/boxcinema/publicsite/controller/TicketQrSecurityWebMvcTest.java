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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
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
    void anonymousAccessToQrTicketOrScannerRedirectsToLoginOrReturns401() throws Exception {
        String token = "BOX1.WebMvcValidToken_0123456789ABCDEF";

        mockMvc.perform(get("/t/" + token))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        mockMvc.perform(get("/scan"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        mockMvc.perform(get("/api/public/tickets/" + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customerOwnerSeesQrWithoutStaffControlsAndNonOwnerGets403() throws Exception {
        String token = "BOX1.WebMvcValidToken_0123456789ABCDEF";
        PublicTicketDto ownerDto = validTicketDto();
        ownerDto.setViewerIsStaff(false);
        ownerDto.setCanDisplayQrImage(true);
        ownerDto.setConfirmable(false);

        UserSummaryDto owner = new UserSummaryDto(10L, "Owner Customer", "owner@box.vn", "0901000010", "CUSTOMER");
        UserSummaryDto intruder = new UserSummaryDto(99L, "Other Customer", "other@box.vn", "0901000099", "CUSTOMER");

        when(publicTicketService.getTicketByTokenForViewer(token, 10L, "CUSTOMER")).thenReturn(Optional.of(ownerDto));
        when(publicTicketService.getTicketByTokenForViewer(token, 99L, "CUSTOMER"))
                .thenThrow(new AccessDeniedException("You do not have permission to view this ticket."));

        // Owner sees ticket details + QR img, no staff buttons, no /scan link
        mockMvc.perform(get("/t/" + token).with(user(new BoxCinemaUserDetails(owner))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Interstellar")))
                .andExpect(content().string(containsString("/api/customer/bookings/10/ticket/qr")))
                .andExpect(content().string(not(containsString("btnConfirmTicketUsed"))))
                .andExpect(content().string(not(containsString("/scan"))));

        // Non-owner receives 403 Forbidden with zero ticket info
        mockMvc.perform(get("/t/" + token).with(user(new BoxCinemaUserDetails(intruder))))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("403 Access Denied")))
                .andExpect(content().string(not(containsString("Interstellar"))));

        // Customer cannot open /scan camera scanner
        mockMvc.perform(get("/scan").with(user(new BoxCinemaUserDetails(owner))))
                .andExpect(status().isForbidden());
    }

    @Test
    void staffSeesDetailsAndConfirmButtonWithoutDuplicateQrImageAndWrongBranchGets403() throws Exception {
        String token = "BOX1.WebMvcValidToken_0123456789ABCDEF";
        PublicTicketDto staffDto = validTicketDto();
        staffDto.setViewerIsStaff(true);
        staffDto.setCanDisplayQrImage(false);
        staffDto.setConfirmable(true);

        UserSummaryDto staff = new UserSummaryDto(15L, "Gate Staff", "staff@boxcinema.vn", "0901000002", "STAFF");
        UserSummaryDto otherBranch = new UserSummaryDto(16L, "Other Staff", "other.staff@boxcinema.vn", "0901000003", "STAFF");

        when(publicTicketService.getTicketByTokenForViewer(token, 15L, "STAFF")).thenReturn(Optional.of(staffDto));
        when(publicTicketService.getTicketByTokenForViewer(token, 16L, "STAFF"))
                .thenThrow(new AccessDeniedException("WRONG_CINEMA_BRANCH|This ticket belongs to Times City."));

        mockMvc.perform(get("/t/" + token).with(user(new BoxCinemaUserDetails(staff))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("btnConfirmTicketUsed")))
                .andExpect(content().string(containsString("CONFIRM TICKET USED")))
                .andExpect(content().string(not(containsString("/api/customer/bookings/10/ticket/qr"))));

        mockMvc.perform(get("/t/" + token).with(user(new BoxCinemaUserDetails(otherBranch))))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("403 Wrong Cinema Branch")))
                .andExpect(content().string(not(containsString("Interstellar"))));
    }

    @Test
    void usedTicketPageDisplaysWarningAndHidesConfirmButtonEvenForStaff() throws Exception {
        String token = "BOX1.WebMvcUsedToken_0123456789ABCDEF";
        PublicTicketDto used = validTicketDto();
        used.setValid(false);
        used.setConfirmable(false);
        used.setViewerIsStaff(true);
        used.setCanDisplayQrImage(false);
        used.setTicketStatus("USED");
        used.setCheckedInAt("18:30 - 27/11/2026");
        used.setStatusReason("This ticket was already checked in at 18:30 - 27/11/2026.");
        when(publicTicketService.getTicketByTokenForViewer(token, 15L, "STAFF")).thenReturn(Optional.of(used));

        UserSummaryDto staff = new UserSummaryDto(15L, "Gate Staff", "staff@boxcinema.vn", "0901000002", "STAFF");
        mockMvc.perform(get("/t/" + token).with(user(new BoxCinemaUserDetails(staff))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("USED")))
                .andExpect(content().string(containsString("This ticket was already checked in")))
                .andExpect(content().string(not(containsString("btnConfirmTicketUsed"))));
    }

    @Test
    void staffCheckinEndpointEnforcesRoleAuthorizationAndCsrf() throws Exception {
        String payload = "{\"qrPayload\":\"BOX1.WebMvcValidToken_0123456789ABCDEF\"}";

        // Unauthenticated -> 401
        mockMvc.perform(post("/api/staff/bookings/check-in")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());

        // Customer -> 403
        mockMvc.perform(post("/api/staff/bookings/check-in")
                        .with(csrf())
                        .with(user("customer@box.vn").roles("USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        UserSummaryDto staff = new UserSummaryDto(15L, "Gate Staff", "staff@boxcinema.vn", "0901000002", "STAFF");

        // Missing CSRF token -> 403
        mockMvc.perform(post("/api/staff/bookings/check-in")
                        .with(user(new BoxCinemaUserDetails(staff)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        // Staff with valid CSRF -> 200 OK
        StaffCheckinResponseDto ok = new StaffCheckinResponseDto();
        ok.setSuccess(true);
        ok.setCode("SUCCESS");
        ok.setCheckInStatus("USED");
        when(staffCheckinService.checkinByQrToken(eq("BOX1.WebMvcValidToken_0123456789ABCDEF"), eq(15L)))
                .thenReturn(ok);

        mockMvc.perform(post("/api/staff/bookings/check-in")
                        .with(csrf())
                        .with(user(new BoxCinemaUserDetails(staff)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.checkInStatus").value("USED"));
    }

    @Test
    void scannerPageAllowedOnlyForAuthenticatedStaffManagerAdmin() throws Exception {
        UserSummaryDto staff = new UserSummaryDto(15L, "Gate Staff", "staff@boxcinema.vn", "0901000002", "STAFF");
        mockMvc.perform(get("/scan").with(user(new BoxCinemaUserDetails(staff))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("E-Ticket QR Scanner")))
                .andExpect(content().string(not(containsString("ngrok"))));
    }

    private static PublicTicketDto validTicketDto() {
        PublicTicketDto dto = new PublicTicketDto();
        dto.setBookingId(10L);
        dto.setShowtimeId(20L);
        dto.setCinemaId(1L);
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
