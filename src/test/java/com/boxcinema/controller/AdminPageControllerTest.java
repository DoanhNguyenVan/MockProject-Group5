package com.boxcinema.controller;

import com.boxcinema.config.SecurityConfig;
import com.boxcinema.dto.UserSummaryDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminPageController.class)
@Import(SecurityConfig.class)
class AdminPageControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest
    @ValueSource(strings = {"dashboard", "movies", "genres", "cinemas", "rooms", "seats", "showtimes", "pricing", "promotions", "combos", "staff", "reports"})
    void rendersEveryAdminScreenWithSharedLayoutAndCsrf(String section) throws Exception {
        var result = mockMvc.perform(get("/admin/" + section).with(user("admin").roles("ADMIN"))
                        .sessionAttr("currentUser", new UserSummaryDto(100L, "Preview Admin", "preview@example.com", "0900000000", "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/workspace"))
                .andExpect(model().attribute("adminSection", section))
                .andExpect(content().string(containsString("data-admin-section=\"" + section + "\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(containsString("Sample workspace.")))
                .andExpect(content().string(containsString("Main navigation")))
                .andReturn();
        // Optional rendered fixtures for local browser checks; never served by production.
        if (Boolean.getBoolean("admin.preview.export")) {
            Path directory = Path.of("target", "admin-preview");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(section + ".html"), result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin", "/admin/"})
    void loginDestinationRendersOverview(String path) throws Exception {
        mockMvc.perform(get(path).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(model().attribute("adminSection", "dashboard"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CUSTOMER", "STAFF", "MANAGER"})
    void nonAdminsCannotOpenAdminScreens(String role) throws Exception {
        mockMvc.perform(get("/admin").with(user("user").roles(role))).andExpect(status().isForbidden());
        mockMvc.perform(get("/admin/staff").with(user("user").roles(role))).andExpect(status().isForbidden());
    }

    @Test
    void guestIsSentToLogin() throws Exception {
        mockMvc.perform(get("/admin")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrlPattern("**/login"));
    }

    @Test
    void unknownSectionIsNotRendered() throws Exception {
        mockMvc.perform(get("/admin/missing").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
    }
}
