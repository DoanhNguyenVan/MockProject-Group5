package com.boxcinema.staff.controller;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import com.boxcinema.staff.dto.response.StaffCheckinResponseDto;
import com.boxcinema.staff.service.StaffCheckinService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StaffCheckinApiControllerTest {

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsUnauthenticatedRequestWith401() throws Exception {
        StaffCheckinService service = mock(StaffCheckinService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StaffCheckinApiController(service)).build();

        mvc.perform(post("/api/staff/bookings/check-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrPayload\":\"BOX1.Token_0123456789ABCDEF\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void returnsOkConflictBadRequestNotFoundAndForbiddenAppropriately() throws Exception {
        StaffCheckinService service = mock(StaffCheckinService.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StaffCheckinApiController(service)).build();

        UserSummaryDto staff = new UserSummaryDto(15L, "Gate Staff", "staff@boxcinema.vn", "0901000002", "STAFF");
        BoxCinemaUserDetails details = new BoxCinemaUserDetails(staff);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities())
        );

        StaffCheckinResponseDto ok = new StaffCheckinResponseDto();
        ok.setSuccess(true);
        ok.setCode("SUCCESS");
        ok.setCheckInStatus("USED");
        when(service.checkinByQrToken("BOX1.Valid", 15L)).thenReturn(ok);

        StaffCheckinResponseDto dup = new StaffCheckinResponseDto();
        dup.setSuccess(false);
        dup.setCode("BOOKING_ALREADY_CHECKED_IN");
        dup.setMessage("Ticket has already been used / checked in.");
        when(service.checkinByQrToken("BOX1.Used", 15L)).thenReturn(dup);

        StaffCheckinResponseDto invalid = new StaffCheckinResponseDto();
        invalid.setSuccess(false);
        invalid.setCode("INVALID_QR_TOKEN");
        when(service.checkinByQrToken("BOX1.Missing", 15L)).thenReturn(invalid);

        StaffCheckinResponseDto refunded = new StaffCheckinResponseDto();
        refunded.setSuccess(false);
        refunded.setCode("BOOKING_REFUNDED");
        when(service.checkinByQrToken("BOX1.Refunded", 15L)).thenReturn(refunded);

        when(service.checkinByQrToken("BOX1.Denied", 15L))
                .thenThrow(new AccessDeniedException("WRONG_CINEMA_BRANCH|Wrong branch."));

        mvc.perform(post("/api/staff/bookings/check-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrPayload\":\"BOX1.Valid\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.checkInStatus").value("USED"));

        mvc.perform(post("/api/staff/bookings/check-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrPayload\":\"BOX1.Used\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_ALREADY_CHECKED_IN"));

        mvc.perform(post("/api/staff/bookings/check-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrPayload\":\"BOX1.Missing\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_QR_TOKEN"));

        mvc.perform(post("/api/staff/bookings/check-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrPayload\":\"BOX1.Refunded\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BOOKING_REFUNDED"));

        mvc.perform(post("/api/staff/bookings/check-in")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"qrPayload\":\"BOX1.Denied\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WRONG_CINEMA_BRANCH"));

        when(service.recordTicketPrint(10L, 15L)).thenReturn(java.util.Map.of("success", true, "printCount", 1L));
        mvc.perform(post("/api/staff/bookings/10/print-log"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.printCount").value(1));
    }
}
