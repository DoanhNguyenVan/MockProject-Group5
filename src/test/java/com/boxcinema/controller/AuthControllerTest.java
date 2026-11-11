package com.boxcinema.controller;

import com.boxcinema.config.SecurityConfig;
import com.boxcinema.dto.RegisterRequestDto;
import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
public class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Test
    void testLoginPageRenders() throws Exception {
        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attribute("activePage", "login"));
    }

    @Test
    void testProcessLoginSuccessViaSpringSecurity() throws Exception {
        UserSummaryDto userDto = new UserSummaryDto(1L, "Alex Nguyen", "alex@boxcinema.com", "0901234567", "CUSTOMER");
        when(userService.authenticate("alex@boxcinema.com", "Password123!")).thenReturn(userDto);

        mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "alex@boxcinema.com")
                        .param("password", "Password123!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername("alex@boxcinema.com").withRoles("CUSTOMER"))
                .andExpect(request().sessionAttribute("currentUser", userDto));
    }

    @Test
    void testProcessLoginFailureRedirectsWithFlashError() throws Exception {
        when(userService.authenticate(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Invalid email or password."));

        MvcResult result = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "alex@boxcinema.com")
                        .param("password", "WrongPass"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=true"))
                .andExpect(unauthenticated())
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(get("/login?error=true").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attribute("errorMessage", "Invalid email or password."))
                .andExpect(model().attribute("enteredEmail", "alex@boxcinema.com"));
    }

    @Test
    void testProcessLoginUnverifiedGmailRedirectsToVerifyOtp() throws Exception {
        when(userService.authenticate("unverified@gmail.com", "Password123!"))
                .thenThrow(new IllegalStateException("UNVERIFIED_EMAIL:unverified@gmail.com"));

        MvcResult result = mockMvc.perform(post("/login")
                        .with(csrf())
                        .param("username", "unverified@gmail.com")
                        .param("password", "Password123!"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/verify-email?email=unverified%40gmail.com"))
                .andExpect(unauthenticated())
                .andReturn();

        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(get("/verify-email?email=unverified@gmail.com").session(session))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/verify-email"))
                .andExpect(model().attributeExists("errorMessage"));
    }

    @Test
    void testProcessRegisterRedirectsToVerifyEmailOtpPageAndRequiresCsrf() throws Exception {
        mockMvc.perform(post("/register")
                        .param("fullName", "Minh Tran")
                        .param("email", "minh@gmail.com"))
                .andExpect(status().isForbidden());

        UserSummaryDto created = new UserSummaryDto(2L, "Minh Tran", "minh@gmail.com", "0909888777", "CUSTOMER");
        when(userService.registerCustomer(any(RegisterRequestDto.class))).thenReturn(created);

        mockMvc.perform(post("/register")
                        .with(csrf())
                        .param("fullName", "Minh Tran")
                        .param("email", "minh@gmail.com")
                        .param("phone", "0909888777")
                        .param("password", "Password123!")
                        .param("confirmPassword", "Password123!")
                        .param("termsAccepted", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/verify-email?email=minh%40gmail.com"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    @Test
    void testVerifyEmailOtpPageAndSubmission() throws Exception {
        when(userService.getPendingOtpPreview("minh@gmail.com")).thenReturn(Optional.of("654321"));

        mockMvc.perform(get("/verify-email").param("email", "minh@gmail.com"))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/verify-email"))
                .andExpect(model().attribute("verifyEmail", "minh@gmail.com"))
                .andExpect(model().attribute("demoOtpCode", "654321"));

        UserSummaryDto verified = new UserSummaryDto(2L, "Minh Tran", "minh@gmail.com", "0909888777", "CUSTOMER");
        when(userService.verifyEmailOtp("minh@gmail.com", "654321")).thenReturn(verified);

        mockMvc.perform(post("/verify-email")
                        .with(csrf())
                        .param("email", "minh@gmail.com")
                        .param("otpCode", "654321"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?verified=true"))
                .andExpect(flash().attributeExists("successMessage"));
    }

    @Test
    void testSocialLoginGoogleAndFacebookEstablishesSecurityContext() throws Exception {
        UserSummaryDto googleUser = new UserSummaryDto(3L, "Alex Google", "alex.google@gmail.com", "GG123456789", "CUSTOMER");
        when(userService.authenticateSocial("GOOGLE", "gg_123", "alex.google@gmail.com", "Alex Google"))
                .thenReturn(googleUser);

        mockMvc.perform(post("/auth/social")
                        .with(csrf())
                        .param("provider", "GOOGLE")
                        .param("providerId", "gg_123")
                        .param("email", "alex.google@gmail.com")
                        .param("fullName", "Alex Google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername("alex.google@gmail.com").withRoles("CUSTOMER"))
                .andExpect(request().sessionAttribute("currentUser", googleUser));

        UserSummaryDto fbUser = new UserSummaryDto(4L, "Nam Facebook", "nam.fb@facebook.com", "FB123456789", "CUSTOMER");
        when(userService.authenticateSocial("FACEBOOK", "fb_456", "nam.fb@facebook.com", "Nam Facebook"))
                .thenReturn(fbUser);

        mockMvc.perform(post("/auth/social")
                        .with(csrf())
                        .param("provider", "FACEBOOK")
                        .param("providerId", "fb_456")
                        .param("email", "nam.fb@facebook.com")
                        .param("fullName", "Nam Facebook"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername("nam.fb@facebook.com").withRoles("CUSTOMER"))
                .andExpect(request().sessionAttribute("currentUser", fbUser));
    }

    @Test
    void testForgotPasswordAndSpringSecurityLogout() throws Exception {
        mockMvc.perform(post("/forgot-password")
                        .with(csrf())
                        .param("email", "alex@boxcinema.com")
                        .param("newPassword", "NewSecret123")
                        .param("confirmPassword", "NewSecret123"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?reset=true"));

        MvcResult logoutResult = mockMvc.perform(post("/logout")
                        .with(csrf())
                        .with(user("alex@boxcinema.com").roles("CUSTOMER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout=true"))
                .andExpect(unauthenticated())
                .andReturn();

        MockHttpSession logoutSession = (MockHttpSession) logoutResult.getRequest().getSession(false);
        mockMvc.perform(get("/login?logout=true").session(logoutSession))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/login"))
                .andExpect(model().attribute("successMessage", "You have been signed out safely."));
    }

    @Test
    void testRoleAndAuthRouteProtection() throws Exception {
        mockMvc.perform(get("/booking/seats"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrlPattern("**/login"));

        mockMvc.perform(get("/admin/dashboard")
                        .with(user("alex@boxcinema.com").roles("CUSTOMER")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/staff/pos")
                        .with(user("alex@boxcinema.com").roles("CUSTOMER")))
                .andExpect(status().isForbidden());
    }
}
