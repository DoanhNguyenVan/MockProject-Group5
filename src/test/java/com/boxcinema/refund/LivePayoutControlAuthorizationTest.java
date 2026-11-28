package com.boxcinema.refund;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.repository.RefundAuditLogRepository;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import com.boxcinema.refund.service.LivePayoutGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Authorization / default-state tests for the Admin live payout control. Runs against box_cinema_test with
 * PAYOS_PAYOUT_MODE=MOCK and LIVE_PAYOUT_ENABLED=false: zero provider calls, zero real money.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LivePayoutControlAuthorizationTest {

    private static final long ADMIN_ID = 990001L;

    @Autowired private MockMvc mockMvc;
    @Autowired private LivePayoutGate gate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RefundAuditLogRepository auditRepo;
    @Autowired private com.boxcinema.common.repository.UserRepository userRepository;
    @MockBean private JavaMailSender mailSender;

    private final java.util.Map<String, com.boxcinema.common.entity.User> users = new java.util.HashMap<>();

    private RequestPostProcessor principal(long ignoredId, String role) {
        com.boxcinema.common.entity.User u = users.computeIfAbsent(role, r -> {
            String email = "lp_" + r.toLowerCase() + "_" + System.nanoTime() + "@box.test";
            com.boxcinema.common.entity.User nu = new com.boxcinema.common.entity.User("LivePay " + r, email,
                    "09" + String.format("%08d", Math.abs(email.hashCode()) % 100_000_000), "hash", r);
            nu.setAccountStatus("ACTIVE");
            return userRepository.save(nu);
        });
        UserSummaryDto s = new UserSummaryDto(u.getUserId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole());
        BoxCinemaUserDetails d = new BoxCinemaUserDetails(u);
        RequestPostProcessor up = SecurityMockMvcRequestPostProcessors.user(d);
        return req -> {
            Objects.requireNonNull(req.getSession(true)).setAttribute("currentUser", s);
            return up.postProcessRequest(req);
        };
    }

    @AfterEach
    void cleanup() {
        ReflectionTestUtils.setField(gate, "adminLiveEnabled", false);
        ReflectionTestUtils.setField(gate, "maxTestAmountVnd", null);
        for (com.boxcinema.common.entity.User u : users.values()) {
            jdbc.update("DELETE FROM refund_audit_logs WHERE actor_user_id = ?", u.getUserId());
            userRepository.deleteById(u.getUserId());
        }
        users.clear();
    }


    @Test
    void defaultStateIsDisabledAndAdminSeesStatusWithoutSecrets() throws Exception {
        String body = mockMvc.perform(get("/api/admin/payouts/live-control").with(principal(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.effectiveMode").value("DISABLED"))
                .andExpect(jsonPath("$.adminLiveMode").value("DISABLED"))
                .andExpect(jsonPath("$.maxTestAmountConfigured").value(false))
                .andExpect(jsonPath("$.requiredConfirmationPhrase").value("CONFIRM LIVE PAYOUT"))
                .andReturn().getResponse().getContentAsString().toLowerCase();
        assertFalse(body.contains("checksum-key") || body.contains("api-key") || body.contains("\"apikey\""));
    }

    @Test
    void managerStaffCustomerAndAnonymousCannotReachAnyLiveControlEndpoint() throws Exception {
        for (String role : new String[]{"MANAGER", "STAFF", "CUSTOMER"}) {
            RequestPostProcessor p = principal(880000L, role);
            mockMvc.perform(get("/api/admin/payouts/live-control").with(p)).andExpect(status().isForbidden());
            mockMvc.perform(put("/api/admin/payouts/live-control/mode").with(p).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")).andExpect(status().isForbidden());
            mockMvc.perform(put("/api/admin/payouts/live-control/max-amount").with(p).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"maxAmountVnd\":10000}")).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/admin/refunds/1/payout/live-confirmation").with(p)).andExpect(status().isForbidden());
            mockMvc.perform(post("/api/admin/refunds/1/payout/live-execute").with(p).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden());
        }
        assertFalse(gate.isAdminLiveEnabled());
        int code = mockMvc.perform(get("/api/admin/payouts/live-control")).andReturn().getResponse().getStatus();
        assertTrue(code == 401 || code == 302 || code == 403, "anonymous must be rejected, was " + code);
    }

    @Test
    void adminCannotEnableLiveWhenEnvironmentFlagIsOffAndNoStateOrAuditChanges() throws Exception {
        long before = auditRepo.count();
        mockMvc.perform(put("/api/admin/payouts/live-control/mode").with(principal(ADMIN_ID, "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"note\":\"t\"}"))
                .andExpect(status().isConflict());
        assertFalse(gate.isAdminLiveEnabled());
        assertEquals(before, auditRepo.count());
    }

    @Test
    void adminMaxAmountIsAuditedAndLiveExecuteIsBlockedWhileDisabled() throws Exception {
        mockMvc.perform(put("/api/admin/payouts/live-control/max-amount").with(principal(ADMIN_ID, "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxAmountVnd\":12000,\"note\":\"test cap\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxTestAmountVnd").value(12000));
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM refund_audit_logs WHERE event_type='LIVE_PAYOUT_MAX_AMOUNT_SET' AND actor_user_id = ?",
                Integer.class, users.get("ADMIN").getUserId());
        assertEquals(1, n);

        mockMvc.perform(put("/api/admin/payouts/live-control/max-amount").with(principal(ADMIN_ID, "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"maxAmountVnd\":-1}"))
                .andExpect(status().isBadRequest());

        // Execute without a server-issued token / phrase must never dispatch.
        mockMvc.perform(post("/api/admin/refunds/999999/payout/live-execute").with(principal(ADMIN_ID, "ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmPhrase\":\"CONFIRM LIVE PAYOUT\",\"confirmAmountVnd\":12000,\"acknowledgeRealMoney\":true,\"confirmationToken\":\"forged\"}"))
                .andExpect(status().is4xxClientError());
    }
}
