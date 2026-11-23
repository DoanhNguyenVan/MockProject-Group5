package com.boxcinema.manager.controller;

import com.boxcinema.admin.dto.AdminShowtimeRequestDto;
import com.boxcinema.admin.service.AdminShowtimeService;
import com.boxcinema.common.config.SecurityConfig;
import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.Cinema;
import com.boxcinema.common.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ManagerShowtimeController.class)
@Import(SecurityConfig.class)
class ManagerShowtimeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AdminShowtimeService showtimeService;

    @MockBean
    private UserRepository userRepository;

    private UserSummaryDto managerUser;
    private Cinema cinemaHanoi;

    @BeforeEach
    void setUp() {
        managerUser = new UserSummaryDto(2L, "Hanoi Manager", "manager.hanoi@boxcinema.vn", "0911111111", "MANAGER");
        managerUser.setCinemaId(1L);

        cinemaHanoi = new Cinema("BOX Cinema Hà Nội", "123 Phố Huế", "Hà Nội", "0241111111", "hanoi@boxcinema.vn");
        cinemaHanoi.setCinemaId(1L);
    }

    @Test
    void listShowtimes_asManager_rendersManagerView() throws Exception {
        when(showtimeService.getCinemaById(1L)).thenReturn(cinemaHanoi);
        when(showtimeService.getShowtimes(1L, null)).thenReturn(Collections.emptyList());
        when(showtimeService.getRoomsByCinema(1L)).thenReturn(Collections.emptyList());
        when(showtimeService.getAllMovies()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/manager/showtimes")
                        .with(user("manager.hanoi@boxcinema.vn").roles("MANAGER"))
                        .sessionAttr("currentUser", managerUser))
                .andExpect(status().isOk())
                .andExpect(view().name("manager/showtimes/list"))
                .andExpect(model().attributeExists("cinema", "showtimes", "rooms", "movies"));
    }

    @Test
    void listShowtimes_asAdmin_redirectsToAdminShowtimes() throws Exception {
        mockMvc.perform(get("/manager/showtimes")
                        .with(user("admin@boxcinema.vn").roles("ADMIN")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/showtimes"));
    }

    @Test
    void listShowtimes_asCustomer_isForbidden() throws Exception {
        mockMvc.perform(get("/manager/showtimes")
                        .with(user("customer@gmail.com").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void listShowtimes_asGuest_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/manager/showtimes"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void createShowtime_asManager_callsServiceAndRedirects() throws Exception {
        mockMvc.perform(post("/manager/showtimes/create")
                        .with(user("manager.hanoi@boxcinema.vn").roles("MANAGER"))
                        .with(csrf())
                        .sessionAttr("currentUser", managerUser)
                        .param("movieId", "100")
                        .param("roomId", "10")
                        .param("startAt", "2026-11-23T18:00")
                        .param("endAt", "2026-11-23T20:30")
                        .param("basePrice", "75000"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/showtimes"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(showtimeService, times(1)).createManagerShowtime(eq(1L), any(AdminShowtimeRequestDto.class));
    }

    @Test
    void deleteShowtime_asManager_callsServiceAndRedirects() throws Exception {
        mockMvc.perform(post("/manager/showtimes/delete/10")
                        .with(user("manager.hanoi@boxcinema.vn").roles("MANAGER"))
                        .with(csrf())
                        .sessionAttr("currentUser", managerUser))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/manager/showtimes"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(showtimeService, times(1)).deleteManagerShowtime(1L, 10L);
    }
}
