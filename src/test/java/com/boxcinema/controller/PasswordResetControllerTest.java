package com.boxcinema.controller;

import com.boxcinema.dto.*;
import com.boxcinema.service.PasswordResetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PasswordResetController.class)
@AutoConfigureMockMvc(addFilters = false)
public class PasswordResetControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PasswordResetService passwordResetService;

    @Test
    void testRequestPasswordResetSuccess() throws Exception {
        when(passwordResetService.requestPasswordReset("alex@boxcinema.com"))
                .thenReturn(PasswordResetResponseDto.success("A 6-digit verification code has been sent."));

        PasswordResetRequestDto request = new PasswordResetRequestDto("alex@boxcinema.com");

        mockMvc.perform(post("/api/auth/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("A 6-digit verification code has been sent."));
    }

    @Test
    void testVerifyOtpSuccessReturnsToken() throws Exception {
        when(passwordResetService.verifyOtp("alex@boxcinema.com", "123456"))
                .thenReturn(PasswordResetResponseDto.success("Code verified successfully.", "sample-256-bit-token"));

        VerifyOtpRequestDto request = new VerifyOtpRequestDto("alex@boxcinema.com", "123456");

        mockMvc.perform(post("/api/auth/password-reset/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.resetToken").value("sample-256-bit-token"));
    }

    @Test
    void testVerifyOtpFailureReturnsBadRequest() throws Exception {
        when(passwordResetService.verifyOtp(anyString(), anyString()))
                .thenThrow(new IllegalArgumentException("Invalid verification code. Remaining attempts: 4"));

        VerifyOtpRequestDto request = new VerifyOtpRequestDto("alex@boxcinema.com", "000000");

        mockMvc.perform(post("/api/auth/password-reset/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid verification code. Remaining attempts: 4"));
    }

    @Test
    void testResendOtpSuccess() throws Exception {
        when(passwordResetService.resendOtp("alex@boxcinema.com"))
                .thenReturn(PasswordResetResponseDto.success("A fresh 6-digit verification code has been dispatched."));

        ResendOtpRequestDto request = new ResendOtpRequestDto("alex@boxcinema.com");

        mockMvc.perform(post("/api/auth/password-reset/resend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void testCompletePasswordResetSuccess() throws Exception {
        when(passwordResetService.completePasswordReset("sample-token", "NewStrongP@ss123", "NewStrongP@ss123"))
                .thenReturn(PasswordResetResponseDto.success("Your password has been reset successfully."));

        CompletePasswordResetDto request = new CompletePasswordResetDto("sample-token", "NewStrongP@ss123", "NewStrongP@ss123");

        mockMvc.perform(post("/api/auth/password-reset/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }
}

