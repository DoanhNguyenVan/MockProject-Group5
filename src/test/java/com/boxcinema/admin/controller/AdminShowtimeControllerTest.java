package com.boxcinema.admin.controller;

import com.boxcinema.admin.service.AdminShowtimeService;
import com.boxcinema.common.config.SecurityConfig;
import com.boxcinema.common.dto.UserSummaryDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminShowtimeController.class)
@Import(SecurityConfig.class)
class AdminShowtimeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminShowtimeService showtimeService;

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void listShowtimes_rendersViewWithModel() throws Exception {
        when(showtimeService.getShowtimes(null, null)).thenReturn(Collections.emptyList());
        when(showtimeService.getAllCinemas()).thenReturn(Collections.emptyList());
        when(showtimeService.getAllActiveRooms()).thenReturn(Collections.emptyList());
        when(showtimeService.getAllMovies()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/admin/showtimes")
                        .sessionAttr("currentUser", new UserSummaryDto(1L, "Admin", "admin@boxcinema.vn", "0900000000", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/showtimes/list"))
                .andExpect(model().attributeExists("showtimes", "cinemas", "rooms", "movies"));
    }

    @Test
    void deleteShowtime_redirectsWithFlashMessage() throws Exception {
        mockMvc.perform(post("/admin/showtimes/delete/1")
                        .with(user("admin").roles("ADMIN"))
                        .with(csrf())
                        .param("cinemaId", "1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/showtimes?cinemaId=1"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(showtimeService, times(1)).deleteShowtime(1L);
    }
}
