package com.boxcinema.refund;

import com.boxcinema.common.constant.SessionConstants;
import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.refund.service.PayOsPayoutService;
import com.boxcinema.refund.service.RefundRateLimitService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Stage 4 Independent QA & Verification Test Suite:
 * - Verifies Thymeleaf renders HTTP 200 with zero SpEL/template syntax errors and all Stage 4 DOM hooks
 *   across Customer (/contact, /booking/history, /booking/history/{id} & /booking/detail/{id}),
 *   Manager (/manager & /manager/dashboard), and Admin (/admin/refunds & /admin/incidents).
 * - Enforces RBAC (CUSTOMER/MANAGER/ADMIN) on Manager and Admin workspaces.
 * - Verifies every fetch(...) endpoint + HTTP method wired in contact-refund.js and refund-ops.js
 *   resolves to a real Spring MVC controller mapping in RequestMappingHandlerMapping.
 * - Confirms PayOS preflight safety invariants (LIVE_PAYOUT_ENABLED=false, PAYOS_PAYOUT_MODE=MOCK).
 */
@SpringBootTest
@AutoConfigureMockMvc
class Stage4FrontendTemplatesAndWiringTest {

    private static final String TAG = "STG4_" + System.currentTimeMillis();

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CinemaRepository cinemaRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingSeatRepository bookingSeatRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private BookingQrRepository bookingQrRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private RefundRateLimitService refundRateLimitService;
    @Autowired private PayOsPayoutService payOsPayoutService;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @MockBean private JavaMailSender mailSender;

    private Cinema cinema1;
    private User customer1;
    private User manager1;
    private User admin1;

    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        refundRateLimitService.resetForTesting();

        cinema1 = cinemaRepository.findAll().get(0);
        customer1 = createUser("stg4_cust_" + TAG + "@box.test", "CUSTOMER", null);
        manager1 = createUser("stg4_mgr_" + TAG + "@box.test", "MANAGER", cinema1.getCinemaId());
        admin1 = createUser("stg4_adm_" + TAG + "@box.test", "ADMIN", null);
    }

    @AfterEach
    void tearDown() {
        for (Long bid : createdBookingIds) {
            jdbcTemplate.update("DELETE FROM email_outbox WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM payments WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM tickets WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_qr WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_seats WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM bookings WHERE booking_id = ?", bid);
        }
        createdBookingIds.clear();
        for (Long uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", uid);
        }
        createdUserIds.clear();
    }

    @Test
    void safetyPreflight_remainsMockAndLivePayoutDisabled() {
        Map<String, Object> preflight = payOsPayoutService.getPayoutPreflightStatus();
        assertEquals(Boolean.FALSE, preflight.get("livePayoutEnabled"), "LIVE_PAYOUT_ENABLED must be false");
        assertEquals("MOCK", String.valueOf(preflight.get("payoutMode")).toUpperCase(Locale.ROOT), "PAYOS_PAYOUT_MODE must be MOCK");
    }

    @Test
    void customerTemplates_render200WithRefundPrefillLinksAndContactDomHooks() throws Exception {
        Booking confirmedBooking = createPaidBooking(customer1, "CONFIRMED", true);
        Booking conflictBooking = createPaidBooking(customer1, "PAID_SEAT_CONFLICT", false);

        // 1. /contact (anonymous & authenticated CUSTOMER)
        String contactHtml = mockMvc.perform(get("/contact")
                        .param("tab", "refund")
                        .param("orderCode", confirmedBooking.getBookingCode())
                        .param("email", customer1.getEmail()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<String> requiredContactHooks = List.of(
                "id=\"refundSupportRoot\"",
                "id=\"tab-refund-btn\"",
                "id=\"tab-lookup-btn\"",
                "id=\"refundOrderCode\"",
                "id=\"refundPurchaseEmail\"",
                "id=\"btnLookupOrder\"",
                "id=\"refundOrderDetailsBox\"",
                "id=\"refundOrderMeta\"",
                "id=\"refundSelectedTotalBadge\"",
                "id=\"refundItemList\"",
                "id=\"publicRefundForm\"",
                "id=\"refundEvidenceFile\"",
                "id=\"refundEvidenceUrl\"",
                "id=\"refundStatusLookupForm\"",
                "id=\"lookupRefundCode\"",
                "id=\"lookupRefundEmail\"",
                "id=\"refundLookupResult\"",
                "/js/modules/contact-refund.js"
        );
        for (String hook : requiredContactHooks) {
            assertTrue(contactHtml.contains(hook), "/contact missing required Stage 4 DOM hook: " + hook);
        }

        // 2. /booking/history (authenticated CUSTOMER)
        String historyHtml = mockMvc.perform(get("/booking/history")
                        .with(authUser(customer1))
                        .sessionAttr(SessionConstants.CURRENT_USER, summaryDto(customer1)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(confirmedBooking.getBookingCode())))
                .andExpect(content().string(containsString("Request Refund / Issue Support")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(historyHtml.contains("/contact?tab=refund") && historyHtml.contains("orderCode=" + confirmedBooking.getBookingCode()),
                "/booking/history must render /contact?tab=refund&orderCode=... prefill link");

        // 3. /booking/history/{id} AND /booking/detail/{id} for CONFIRMED (with QR) and PAID_SEAT_CONFLICT (without QR)
        for (String detailRoute : List.of(
                "/booking/history/" + confirmedBooking.getBookingId(),
                "/booking/detail/" + confirmedBooking.getBookingId(),
                "/booking/detail/" + conflictBooking.getBookingId())) {
            String detailHtml = mockMvc.perform(get(detailRoute)
                            .with(authUser(customer1))
                            .sessionAttr(SessionConstants.CURRENT_USER, summaryDto(customer1)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("id=\"btnBookingDetailRequestRefund\"")))
                    .andExpect(content().string(containsString("Request Refund / Issue Support")))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            assertTrue(detailHtml.contains("/contact?tab=refund") && detailHtml.contains("orderCode="),
                    detailRoute + " must render /contact?tab=refund&orderCode=... prefill link");
        }
    }

    @Test
    void managerAndAdminTemplates_enforceRbacAndRenderAllStage4DomHooks() throws Exception {
        // 1. Manager workspace RBAC & DOM hooks (/manager and /manager/dashboard)
        mockMvc.perform(get("/manager/dashboard").with(authUser(customer1)))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/manager").with(authUser(admin1))
                        .sessionAttr(SessionConstants.CURRENT_USER, summaryDto(admin1)))
                .andExpect(status().isOk());

        String managerHtml = mockMvc.perform(get("/manager/dashboard")
                        .with(authUser(manager1))
                        .sessionAttr(SessionConstants.CURRENT_USER, summaryDto(manager1)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<String> requiredManagerHooks = List.of(
                "id=\"refundOpsRoot\"",
                "data-role-scope=\"manager\"",
                "id=\"opsPayoutPreflightBadge\"",
                "LIVE_PAYOUT_ENABLED: FALSE",
                "id=\"opsLimitSummaryBanner\"",
                "id=\"opsLimitSummaryContent\"",
                "id=\"btnOpsReloadLimits\"",
                "id=\"mgr-refunds\"",
                "id=\"mgr-appeals\"",
                "id=\"mgr-incidents\"",
                "id=\"mgr-staff-errors\"",
                "value=\"PAYOUT_BLOCKED\"",
                "value=\"UNKNOWN\"",
                "value=\"APPEAL_SUBMITTED\"",
                "value=\"APPEAL_UNDER_REVIEW\"",
                "value=\"APPEAL_ACCEPTED\"",
                "value=\"APPEAL_REJECTED\"",
                "id=\"opsAppealTableBody\"",
                "id=\"opsAppealDetailPanel\"",
                "id=\"opsIncidentFormCard\"",
                "id=\"incFormCustomerNotifiedAt\"",
                "/js/modules/refund-ops.js"
        );
        for (String hook : requiredManagerHooks) {
            assertTrue(managerHtml.contains(hook), "/manager/dashboard missing Stage 4 DOM hook: " + hook);
        }

        // 2. Admin /admin/refunds & /admin/incidents RBAC (CUSTOMER 403, MANAGER 403, ADMIN 200)
        for (String adminPath : List.of("/admin/refunds", "/admin/incidents")) {
            mockMvc.perform(get(adminPath).with(authUser(customer1))).andExpect(status().isForbidden());
            mockMvc.perform(get(adminPath).with(authUser(manager1))).andExpect(status().isForbidden());
        }

        String adminRefundsHtml = mockMvc.perform(get("/admin/refunds")
                        .with(authUser(admin1))
                        .sessionAttr(SessionConstants.CURRENT_USER, summaryDto(admin1)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<String> requiredAdminRefundHooks = List.of(
                "id=\"refundOpsRoot\"",
                "data-role-scope=\"admin\"",
                "id=\"opsPayoutPreflightBadge\"",
                "id=\"admin-tab-payout-queue\"",
                "id=\"admin-tab-all-refunds\"",
                "id=\"admin-tab-payout-history\"",
                "id=\"admin-tab-appeals\"",
                "id=\"admin-tab-limits\"",
                "id=\"limitConfigKeySelect\"",
                "value=\"MANAGER_PER_REQUEST\"",
                "value=\"MANAGER_DAILY\"",
                "value=\"CINEMA_DAILY\"",
                "value=\"INCIDENT_CUMULATIVE\"",
                "id=\"limitAmountVndInput\"",
                "id=\"limitChangeReasonInput\"",
                "id=\"btnSaveLimitConfig\"",
                "id=\"opsLimitConfigTableBody\"",
                "id=\"payoutConfirmModal\"",
                "id=\"confirmPreflightBanner\"",
                "id=\"confirmFeeBlockedBanner\"",
                "id=\"confirmLimitOverrideApproved\"",
                "id=\"confirmLimitOverrideReason\"",
                "id=\"btnConfirmExecute\""
        );
        for (String hook : requiredAdminRefundHooks) {
            assertTrue(adminRefundsHtml.contains(hook), "/admin/refunds missing Stage 4 DOM hook: " + hook);
        }

        String adminIncidentsHtml = mockMvc.perform(get("/admin/incidents")
                        .with(authUser(admin1))
                        .sessionAttr(SessionConstants.CURRENT_USER, summaryDto(admin1)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        for (String hook : List.of(
                "id=\"opsIncidentFormCard\"",
                "id=\"incFormCinemaId\"",
                "id=\"incFormCustomerNotifiedAt\"",
                "id=\"opsIncidentTableBody\"",
                "id=\"opsIncidentDetailPanel\"")) {
            assertTrue(adminIncidentsHtml.contains(hook), "/admin/incidents missing Stage 4 DOM hook: " + hook);
        }
    }

    @Test
    void allFrontendFetchCalls_matchActualSpringMvcControllerMappings() throws Exception {
        String contactJs = Files.readString(Path.of("src/main/resources/static/js/modules/contact-refund.js"));
        String refundOpsJs = Files.readString(Path.of("src/main/resources/static/js/modules/refund-ops.js"));

        List<EndpointSpec> extracted = new ArrayList<>();
        extracted.addAll(extractFetchEndpoints(contactJs, false));
        extracted.addAll(extractFetchEndpoints(refundOpsJs, true));

        // Also explicitly include the dynamic variable `fetch(url, { method })` in refund-ops.js saveIncidentForm()
        extracted.add(new EndpointSpec("POST", "/api/manager/incidents", "refund-ops.js:saveIncidentForm"));
        extracted.add(new EndpointSpec("PUT", "/api/manager/incidents/1", "refund-ops.js:saveIncidentForm"));
        extracted.add(new EndpointSpec("POST", "/api/admin/incidents", "refund-ops.js:saveIncidentForm"));
        extracted.add(new EndpointSpec("PUT", "/api/admin/incidents/1", "refund-ops.js:saveIncidentForm"));
        // And authorized evidence download URL generated in refund-ops.js normalizeEvidenceHref()
        extracted.add(new EndpointSpec("GET", "/api/refunds/evidence/sample.png", "refund-ops.js:normalizeEvidenceHref"));

        assertTrue(extracted.size() >= 35, "Expected at least 35 distinct frontend API call specs, got " + extracted.size());

        List<String> unmapped = new ArrayList<>();
        for (EndpointSpec spec : extracted) {
            MockHttpServletRequest req = new MockHttpServletRequest(spec.method(), spec.path());
            HandlerExecutionChain chain;
            try {
                chain = handlerMapping.getHandler(req);
            } catch (Exception ex) {
                unmapped.add(spec.method() + " " + spec.path() + " (" + spec.source() + ") -> " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                continue;
            }
            if (chain == null) {
                unmapped.add(spec.method() + " " + spec.path() + " (" + spec.source() + ") -> NO HANDLER MAPPED");
            }
        }

        assertTrue(unmapped.isEmpty(), "All frontend fetch(...) calls must match real controller mappings, but found mismatches:\n" + String.join("\n", unmapped));
    }

    private record EndpointSpec(String method, String path, String source) {}

    private List<EndpointSpec> extractFetchEndpoints(String jsSource, boolean expandApiBase) {
        List<EndpointSpec> specs = new ArrayList<>();
        Pattern fetchPattern = Pattern.compile("fetch\\(\\s*(['\"`])([^'\"`]+)\\1(\\s*,\\s*\\{[\\s\\S]*?\\})?\\s*\\)");
        Matcher m = fetchPattern.matcher(jsSource);
        Pattern methodPattern = Pattern.compile("method\\s*:\\s*['\"]([A-Z]+)['\"]");

        while (m.find()) {
            String rawUrl = m.group(2).trim();
            String optsBlock = m.group(3) != null ? m.group(3) : "";
            String method = "GET";
            Matcher mm = methodPattern.matcher(optsBlock);
            if (mm.find()) {
                method = mm.group(1).toUpperCase(Locale.ROOT);
            }

            List<String> baseExpandedUrls;
            if (rawUrl.contains("${apiBase}")) {
                if (expandApiBase) {
                    // Note: retry-payout is Admin-only; check if rawUrl is ${apiBase}
                    baseExpandedUrls = List.of(
                            rawUrl.replace("${apiBase}", "/api/manager"),
                            rawUrl.replace("${apiBase}", "/api/admin")
                    );
                } else {
                    baseExpandedUrls = List.of(rawUrl.replace("${apiBase}", "/api/admin"));
                }
            } else {
                baseExpandedUrls = List.of(rawUrl);
            }

            for (String expanded : baseExpandedUrls) {
                // Replace remaining JS template interpolations (${...}) with concrete path/query values
                String concrete = expanded
                        .replace("${qs ? '?' + qs : ''}", "")
                        .replaceAll("\\$\\{[^}]+\\}", "1");
                int qIdx = concrete.indexOf('?');
                String cleanPath = qIdx >= 0 ? concrete.substring(0, qIdx) : concrete;
                if (cleanPath.startsWith("/api/")) {
                    specs.add(new EndpointSpec(method, cleanPath, rawUrl));
                }
            }
        }
        return specs;
    }

    private RequestPostProcessor authUser(User u) {
        return user(new BoxCinemaUserDetails(u));
    }

    private UserSummaryDto summaryDto(User u) {
        return new UserSummaryDto(u.getUserId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole());
    }

    private User createUser(String email, String role, Long cinemaId) {
        User u = new User("Stage4 " + role, email, "09" + String.format("%08d", Math.abs(email.hashCode()) % 100000000), "hash", role);
        u.setCinemaId(cinemaId);
        u.setAccountStatus("ACTIVE");
        u = userRepository.save(u);
        createdUserIds.add(u.getUserId());
        return u;
    }

    private Booking createPaidBooking(User owner, String bookingStatus, boolean withValidQrUrl) {
        Booking b = new Booking();
        b.setBookingCode("BOX-20261127-" + (1000 + new Random().nextInt(8999)));
        b.setCinema(cinema1);
        b.setUser(owner);
        b.setOrderType("TICKET_ONLY");
        b.setGuestName(owner.getFullName());
        b.setGuestEmail(owner.getEmail());
        b.setGuestPhone(owner.getPhone());
        BigDecimal price = BigDecimal.valueOf(100_000);
        b.setTicketSubtotal(price);
        b.setFoodSubtotal(BigDecimal.ZERO);
        b.setSubtotal(price);
        b.setDiscountAmount(BigDecimal.ZERO);
        b.setServiceFee(BigDecimal.ZERO);
        b.setTotalAmount(price);
        b.setBookingStatus(bookingStatus);
        b.setBookedAt(LocalDateTime.now());
        if ("CONFIRMED".equals(bookingStatus)) {
            b.setConfirmedAt(LocalDateTime.now());
        }
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());

        BookingSeat bs = bookingSeatRepository.save(new BookingSeat(b, 1L, price, "C5", "BOOKED"));

        Ticket t = new Ticket();
        t.setBooking(b);
        t.setBookingSeatId(bs.getBookingSeatId());
        t.setSeatId(1L);
        t.setTicketCode("TCK-" + b.getBookingCode().replace("BOX-", "") + "-C5");
        t.setQrTokenHash(CryptoUtils.sha256(t.getTicketCode()));
        t.setPrice(price);
        t.setGrossPrice(price);
        t.setDiscountAllocated(BigDecimal.ZERO);
        t.setNetPaidAmount(price);
        t.setTicketStatus("VALID");
        ticketRepository.save(t);

        if (withValidQrUrl) {
            BookingQr qr = new BookingQr();
            qr.setBooking(b);
            qr.setTokenHash(CryptoUtils.sha256("BOX1.STG4." + UUID.randomUUID()));
            qr.setTokenEncrypted("enc");
            qr.setTicketUrl("http://localhost:8080/t/BOX1.STG4." + b.getBookingId());
            qr.setStatus("ACTIVE");
            bookingQrRepository.save(qr);
        }

        Payment p = new Payment();
        p.setBooking(b);
        p.setOrderCode(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 900000000L + 1000000L);
        p.setIdempotencyKey("STG4-IDEM-" + UUID.randomUUID());
        p.setPaymentProvider("PAYOS");
        p.setPaymentMethod("PAYOS_QR");
        p.setAmount(price);
        p.setCurrency("VND");
        p.setPaymentStatus("PAID");
        p.setPaidAt(LocalDateTime.now());
        paymentRepository.save(p);

        return b;
    }
}
