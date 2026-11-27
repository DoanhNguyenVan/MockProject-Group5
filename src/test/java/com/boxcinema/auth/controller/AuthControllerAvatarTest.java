package com.boxcinema.auth.controller;

import com.boxcinema.auth.service.UserService;
import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.customer.service.ProfileAvatarService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthControllerAvatarTest {

    @Test
    void uploadAvatarSyncsSessionAndRedirectsToProfile() throws Exception {
        UserService userService = mock(UserService.class);
        ProfileAvatarService avatarService = mock(ProfileAvatarService.class);
        AuthController controller = new AuthController(userService, new ObjectMapper());
        ReflectionTestUtils.setField(controller, "profileAvatarService", avatarService);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MockHttpSession session = new MockHttpSession();
        UserSummaryDto userDto = new UserSummaryDto(19L, "Doanh Nguyen", "doanh@example.com", "0901234567", "CUSTOMER");
        session.setAttribute("currentUser", userDto);

        when(avatarService.uploadAvatar(eq(19L), any()))
                .thenReturn("https://res.cloudinary.com/demo/image/upload/v1/box-cinema/avatars/user_19.webp");

        MockMultipartFile file = new MockMultipartFile("avatarFile", "me.png", "image/png", new byte[]{1, 2, 3});
        mvc.perform(multipart("/profile/avatar").file(file).session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/profile"))
                .andExpect(flash().attributeExists("successMessage"));

        assertEquals("https://res.cloudinary.com/demo/image/upload/v1/box-cinema/avatars/user_19.webp",
                userDto.getAvatarUrl());
    }

    @Test
    void removeAvatarClearsSessionAndRedirectsToProfile() throws Exception {
        UserService userService = mock(UserService.class);
        ProfileAvatarService avatarService = mock(ProfileAvatarService.class);
        AuthController controller = new AuthController(userService, new ObjectMapper());
        ReflectionTestUtils.setField(controller, "profileAvatarService", avatarService);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        MockHttpSession session = new MockHttpSession();
        UserSummaryDto userDto = new UserSummaryDto(19L, "Doanh Nguyen", "doanh@example.com", "0901234567", "CUSTOMER");
        userDto.setAvatarUrl("https://res.cloudinary.com/demo/image/upload/v1/box-cinema/avatars/user_19.webp");
        session.setAttribute("currentUser", userDto);

        mvc.perform(post("/profile/avatar/remove").session(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/profile"))
                .andExpect(flash().attributeExists("successMessage"));

        verify(avatarService).removeAvatar(19L);
        assertNull(userDto.getAvatarUrl());
    }
}
