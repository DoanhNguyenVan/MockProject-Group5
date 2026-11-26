package com.boxcinema.refund;

import com.boxcinema.common.dto.UserSummaryDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.enums.RefundAppealStatus;
import com.boxcinema.common.enums.RefundWorkflowState;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.security.BoxCinemaUserDetails;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.refund.dto.*;
import com.boxcinema.refund.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive Stage 2 Independent QA Suite verifying:
 * 1. Incidents (manager_admin policy, cinema isolation, Admin global access, Customer 403,
 *    linked-refund delete protection 409 + DB ON DELETE RESTRICT, zero auto-refund, missing-incident Manager routing).
 * 2. Two-Stage Approval Limits & Atomic Quota Reservations (500k/2M/5M/10M exact vs +1 VND boundaries,
 *    Manager 409 escalation, Admin override with mandatory reason + audit without mutating global config,
 *    Admin config update history, payout-time revalidation without double-counting, post-approval config tightening,
 *    Asia/Ho_Chi_Minh midnight rollover preserving Manager approval date & protecting Day D on Day D+1 overflow,
 *    SUCCESS -> COMMITTED, PROCESSING/UNKNOWN -> RESERVED, FAILED/CANCELLED/REJECTED -> RELEASED,
 *    and multi-threaded concurrent approval race).
 * 3. Separate Customer Refund Appeals (REJECTED -> APPEAL_SUBMITTED -> APPEAL_UNDER_REVIEW -> APPEAL_ACCEPTED / APPEAL_REJECTED,
 *    cross-customer 403, cross-cinema Manager 403, Admin global access, duplicate open appeal 409 APPEAL_ALREADY_OPEN, audit trail).
 * 4. Evidence Upload & Download Security (double extensions, path traversal, MIME spoofing, invalid magic bytes,
 *    SVG/HTML/polyglot/EICAR quarantine, >5MB & >5 files limits, authenticated download role/branch/owner checks,
 *    QUARANTINED 403 block, and EVIDENCE_DOWNLOADED audit log).
 * 5. Backend Rate Limiting (5/customer/10m & 20/IP/h on refund creation, 15/customer/10m & 30/IP/h on upload,
 *    5/customer/10m & 15/IP/h on appeals -> HTTP 429 + Retry-After header + clock advance window reset).
 */
@SpringBootTest
@AutoConfigureMockMvc
class Stage2GovernanceLimitsAppealsSecurityTest {

    private static final ZoneId HCM_ZONE = RefundLimitService.VN_ZONE;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private CinemaRepository cinemaRepository;
    @Autowired private RoomRepository roomRepository;
    @Autowired private MovieRepository movieRepository;
    @Autowired private ShowtimeRepository showtimeRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private BookingSeatRepository bookingSeatRepository;
    @Autowired private TicketRepository ticketRepository;
    @Autowired private PaymentRepository paymentRepository;
    @Autowired private IncidentReportRepository incidentRepository;
    @Autowired private RefundRequestRepository refundRequestRepository;
    @Autowired private RefundRequestItemRepository refundRequestItemRepository;
    @Autowired private RefundLimitConfigRepository limitConfigRepository;
    @Autowired private RefundLimitConfigHistoryRepository limitConfigHistoryRepository;
    @Autowired private RefundLimitReservationRepository reservationRepository;
    @Autowired private RefundLimitOverrideRepository overrideRepository;
    @Autowired private RefundAppealRepository appealRepository;
    @Autowired private RefundEvidenceFileRepository evidenceFileRepository;
    @Autowired private RefundAuditLogRepository auditLogRepository;

    @Autowired private RefundService refundService;
    @Autowired private RefundLimitService refundLimitService;
    @Autowired private RefundAppealService refundAppealService;
    @Autowired private IncidentService incidentService;
    @Autowired private EvidenceStorageService evidenceStorageService;
    @Autowired private EvidenceMalwareScanner malwareScanner;
    @Autowired private RefundRateLimitService refundRateLimitService;
    @Autowired private RefundDeadlineService refundDeadlineService;
    @Autowired private PayOsPayoutService payOsPayoutService;

    @MockBean private JavaMailSender mailSender;

    private Cinema cinema1;
    private Cinema cinema2;
    private Room room1;
    private Movie movie;

    private User customer1;
    private User customer2;
    private User staff1;
    private User staff2;
    private User manager1;
    private User manager1B;
    private User manager1C;
    private User manager2;
    private User admin1;
    private User admin2;

    private final List<Long> createdBookingIds = new ArrayList<>();
    private final List<Long> createdShowtimeIds = new ArrayList<>();
    private final List<Long> createdIncidentIds = new ArrayList<>();
    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> createdCinemaIds = new ArrayList<>();
    private final List<String> createdStorageNames = new ArrayList<>();

    @BeforeEach
    void setUp() {
        reset(mailSender);
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));

        refundRateLimitService.setClock(Clock.systemUTC());
        refundRateLimitService.resetForTesting();
        refundLimitService.setClock(Clock.system(HCM_ZONE));
        refundDeadlineService.setClock(Clock.system(HCM_ZONE));
        payOsPayoutService.setCustomTransportExecutor(null);
        malwareScanner.setCustomQuarantinePredicateForTesting(null);
        restoreDefaultLimitConfigs();

        List<Cinema> cinemas = cinemaRepository.findAll();
        cinema1 = cinemas.get(0);
        room1 = roomRepository.findAll().stream()
                .filter(r -> r.getCinema() != null && r.getCinema().getCinemaId().equals(cinema1.getCinemaId()))
                .findFirst()
                .orElseGet(() -> roomRepository.findAll().get(0));
        cinema1 = room1.getCinema();

        Cinema other = cinemas.stream()
                .filter(c -> !c.getCinemaId().equals(cinema1.getCinemaId()))
                .findFirst()
                .orElse(null);
        if (other != null) {
            cinema2 = other;
        } else {
            cinema2 = cinemaRepository.save(new Cinema(
                    "Stage2 Cinema 2 " + System.nanoTime(), "789 Branch Rd", "Da Nang", "0905556667",
                    "stg2_c2_" + System.nanoTime() + "@box.test"));
            createdCinemaIds.add(cinema2.getCinemaId());
        }
        movie = movieRepository.findAll().get(0);

        String u = UUID.randomUUID().toString().substring(0, 8);
        customer1 = createUser("c1_" + u + "@box.test", "CUSTOMER", null);
        customer2 = createUser("c2_" + u + "@box.test", "CUSTOMER", null);
        staff1 = createUser("st1_" + u + "@box.test", "STAFF", cinema1.getCinemaId());
        staff2 = createUser("st2_" + u + "@box.test", "STAFF", cinema2.getCinemaId());
        manager1 = createUser("m1_" + u + "@box.test", "MANAGER", cinema1.getCinemaId());
        manager1B = createUser("m1b_" + u + "@box.test", "MANAGER", cinema1.getCinemaId());
        manager1C = createUser("m1c_" + u + "@box.test", "MANAGER", cinema1.getCinemaId());
        manager2 = createUser("m2_" + u + "@box.test", "MANAGER", cinema2.getCinemaId());
        admin1 = createUser("adm1_" + u + "@box.test", "ADMIN", null);
        admin2 = createUser("adm2_" + u + "@box.test", "ADMIN", null);

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        refundRateLimitService.setClock(Clock.systemUTC());
        refundRateLimitService.resetForTesting();
        refundLimitService.setClock(Clock.system(HCM_ZONE));
        refundDeadlineService.setClock(Clock.system(HCM_ZONE));
        payOsPayoutService.setCustomTransportExecutor(null);
        malwareScanner.setCustomQuarantinePredicateForTesting(null);
        restoreDefaultLimitConfigs();

        for (String sName : createdStorageNames) {
            try {
                Files.deleteIfExists(Paths.get("uploads/refund-evidence").toAbsolutePath().normalize().resolve(sName));
                Files.deleteIfExists(Paths.get("uploads/refund-evidence-quarantine").toAbsolutePath().normalize().resolve(sName));
            } catch (Exception ignored) {
            }
            jdbcTemplate.update("DELETE FROM refund_evidence_files WHERE storage_name = ?", sName);
        }
        createdStorageNames.clear();

        for (Long bid : createdBookingIds) {
            List<Long> rrIds = jdbcTemplate.queryForList("SELECT id FROM refund_requests WHERE booking_id = ?", Long.class, bid);
            jdbcTemplate.update("DELETE FROM email_outbox WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM voucher_recoveries WHERE booking_id = ?", bid);
            for (Long rrId : rrIds) {
                jdbcTemplate.update("DELETE FROM refund_evidence_files WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM payos_payout_transactions WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_limit_overrides WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_limit_reservations WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_appeal_history WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_appeals WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE refund_request_id = ? OR (entity_type = 'REFUND_REQUEST' AND entity_id = ?)", rrId, rrId);
                jdbcTemplate.update("DELETE FROM refund_approval_history WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_communications WHERE refund_request_id = ?", rrId);
                jdbcTemplate.update("DELETE FROM refund_request_items WHERE refund_request_id = ?", rrId);
            }
            jdbcTemplate.update("DELETE FROM refund_requests WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM staff_error_logs WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM incident_verifications WHERE incident_report_id IN (SELECT id FROM incident_reports WHERE booking_id = ?)", bid);
            jdbcTemplate.update("DELETE FROM incident_reports WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM payments WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM tickets WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_qr WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM booking_seats WHERE booking_id = ?", bid);
            jdbcTemplate.update("DELETE FROM bookings WHERE booking_id = ?", bid);
        }
        createdBookingIds.clear();

        for (Long incId : createdIncidentIds) {
            jdbcTemplate.update("DELETE FROM refund_evidence_files WHERE incident_report_id = ?", incId);
            jdbcTemplate.update("DELETE FROM refund_limit_reservations WHERE incident_report_id = ?", incId);
            jdbcTemplate.update("DELETE FROM refund_limit_buckets WHERE bucket_key = ?", "INC:" + incId);
            jdbcTemplate.update("DELETE FROM incident_verifications WHERE incident_report_id = ?", incId);
            jdbcTemplate.update("DELETE FROM staff_error_logs WHERE incident_report_id = ?", incId);
            jdbcTemplate.update("DELETE FROM incident_reports WHERE id = ?", incId);
        }
        createdIncidentIds.clear();

        for (Long stId : createdShowtimeIds) {
            jdbcTemplate.update("DELETE FROM incident_verifications WHERE incident_report_id IN (SELECT id FROM incident_reports WHERE showtime_id = ?)", stId);
            jdbcTemplate.update("DELETE FROM incident_reports WHERE showtime_id = ?", stId);
            jdbcTemplate.update("DELETE FROM showtime_seats WHERE showtime_id = ?", stId);
            jdbcTemplate.update("DELETE FROM showtimes WHERE showtime_id = ?", stId);
        }
        createdShowtimeIds.clear();

        jdbcTemplate.update("DELETE FROM refund_limit_buckets WHERE bucket_type IN ('CINEMA_DAILY', 'MANAGER_DAILY') AND reserved_amount_vnd = 0 AND committed_amount_vnd = 0");
        jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE entity_type IN ('REFUND_EVIDENCE_FILE', 'REFUND_LIMIT_CONFIG', 'INCIDENT_REPORT', 'REFUND_APPEAL', 'REFUND_LIMIT_RESERVATION')");

        for (Long uid : createdUserIds) {
            jdbcTemplate.update("DELETE FROM refund_limit_config_history WHERE changed_by = ?", uid);
            jdbcTemplate.update("DELETE FROM refund_limit_overrides WHERE admin_user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM refund_evidence_files WHERE uploaded_by_user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM refund_audit_logs WHERE actor_user_id = ?", uid);
            jdbcTemplate.update("DELETE FROM users WHERE user_id = ?", uid);
        }
        createdUserIds.clear();

        for (Long cid : createdCinemaIds) {
            jdbcTemplate.update("DELETE FROM refund_limit_buckets WHERE bucket_type = 'CINEMA_DAILY' AND scope_id = ?", cid);
            jdbcTemplate.update("DELETE FROM cinemas WHERE cinema_id = ?", cid);
        }
        createdCinemaIds.clear();
    }

    // =========================================================================
    // 1. INCIDENT GOVERNANCE (manager_admin, cinema scope, no auto-refund, delete guard)
    // =========================================================================

    @Test
    void incidentGovernance_ManagerCinemaScoped_AdminSystemWide_CustomerForbidden_DeleteLinkedBlocked_ZeroAutoRefund() throws Exception {
        Showtime st1 = createShowtime(cinema1, LocalDateTime.now(HCM_ZONE).plusHours(4), LocalDateTime.now(HCM_ZONE).plusHours(6));
        Booking b1 = createPaidBooking(st1, cinema1, customer1, 120_000L);

        Map<String, Object> createBody = Map.of(
                "cinemaId", cinema1.getCinemaId(),
                "showtimeId", st1.getShowtimeId(),
                "bookingCode", b1.getBookingCode(),
                "incidentGroup", "CINEMA_INCIDENT",
                "incidentType", "AUDIO_FAILURE",
                "severity", "HIGH",
                "title", "Dolby processor muted",
                "description", "Center speaker channel failed during screening",
                "customerNotifiedAt", LocalDateTime.now(HCM_ZONE).withNano(0).toString()
        );

        // 1. Customer is strictly forbidden (403) across manager, admin, and staff incident endpoints
        mockMvc.perform(post("/api/manager/incidents").with(csrf()).with(authOf(customer1))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/incidents").with(csrf()).with(authOf(customer1))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/staff/incidents").with(csrf()).with(authOf(customer1))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isForbidden());

        // 2. Cross-cinema Manager (manager2 -> cinema2) CANNOT create incident for cinema1 -> 403
        mockMvc.perform(post("/api/manager/incidents").with(csrf()).with(authOf(manager2))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BRANCH_ACCESS_DENIED"));

        // 3. Assigned Manager (manager1 -> cinema1) creates incident -> 201 Created, ZERO auto-refunds triggered!
        MvcResult mgrCreate = mockMvc.perform(post("/api/manager/incidents").with(csrf()).with(authOf(manager1))
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cinemaId").value(cinema1.getCinemaId()))
                .andReturn();
        long inc1Id = objectMapper.readTree(mgrCreate.getResponse().getContentAsString()).get("id").asLong();
        createdIncidentIds.add(inc1Id);

        Integer autoRefundCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refund_requests WHERE booking_id = ?", Integer.class, b1.getBookingId());
        assertEquals(0, autoRefundCount, "Incident creation must NEVER auto-create or auto-trigger refunds");

        // 4. Manager1 updates incident details & customerNotifiedAt; Manager2 is blocked (403) from reading/updating/deleting inc1
        LocalDateTime updatedNotified = LocalDateTime.now(HCM_ZONE).plusMinutes(15).withNano(0);
        mockMvc.perform(put("/api/manager/incidents/" + inc1Id).with(csrf()).with(authOf(manager2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "Unauthorized cross-branch edit"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/manager/incidents/" + inc1Id).with(csrf()).with(authOf(manager1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "Updated Dolby processor outage",
                                "severity", "CRITICAL",
                                "status", "VERIFIED",
                                "customerNotifiedAt", updatedNotified.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.severity").value("CRITICAL"))
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.updatedBy").value(manager1.getUserId()));

        // 5. Admin has system-wide incident CRUD across cinema2 as well
        MvcResult admCreate = mockMvc.perform(post("/api/admin/incidents").with(csrf()).with(authOf(admin1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cinemaId", cinema2.getCinemaId(),
                                "incidentGroup", "CINEMA_INCIDENT",
                                "incidentType", "HVAC_OUTAGE",
                                "severity", "MEDIUM",
                                "title", "Cinema 2 AC outage",
                                "description", "Admin logged cross-branch incident"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cinemaId").value(cinema2.getCinemaId()))
                .andReturn();
        long inc2Id = objectMapper.readTree(admCreate.getResponse().getContentAsString()).get("id").asLong();
        createdIncidentIds.add(inc2Id);

        // Unlinked incident CAN be deleted by Admin (204), while Manager1 cannot delete Cinema 2's incident (403)
        mockMvc.perform(delete("/api/manager/incidents/" + inc2Id).with(csrf()).with(authOf(manager1)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/incidents/" + inc2Id).with(csrf()).with(authOf(admin1)))
                .andExpect(status().isNoContent());
        createdIncidentIds.remove(inc2Id);
        assertTrue(incidentRepository.findById(inc2Id).isEmpty());

        // 6. Link a customer RefundRequest to inc1 -> physical deletion is BLOCKED at service (409) AND DB FK (ON DELETE RESTRICT)
        Ticket t1 = ticketRepository.findByBooking_BookingId(b1.getBookingId()).get(0);
        RefundRequestDetailDto linkedRefund = submitCustomerRefund(b1, t1.getTicketId(), customer1, "10.50.1.1");
        assertNotNull(refundRequestRepository.findById(linkedRefund.getId()).orElseThrow().getIncidentReport());

        mockMvc.perform(delete("/api/manager/incidents/" + inc1Id).with(csrf()).with(authOf(manager1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INCIDENT_LINKED_TO_REFUNDS"));
        mockMvc.perform(delete("/api/admin/incidents/" + inc1Id).with(csrf()).with(authOf(admin1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INCIDENT_LINKED_TO_REFUNDS"));

        assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update("DELETE FROM incident_reports WHERE id = ?", inc1Id),
                "DB fk_rr_incident ON DELETE RESTRICT must block physical deletion of linked incident_reports row");

        // 7. Missing incident routes customer refund to PENDING_REVIEW (REQUESTED) for Manager investigation — never auto-rejected
        Booking noIncBooking = createPaidBooking(st1, cinema1, customer2, 100_000L);
        jdbcTemplate.update("UPDATE incident_reports SET showtime_id = NULL WHERE id = ?", inc1Id);
        Ticket noIncTicket = ticketRepository.findByBooking_BookingId(noIncBooking.getBookingId()).get(0);
        RefundRequestDetailDto unlinkedSub = submitCustomerRefund(noIncBooking, noIncTicket.getTicketId(), customer2, "10.50.1.2");
        assertEquals("PENDING_REVIEW", unlinkedSub.getRequestStatus());
        assertEquals("REQUESTED", unlinkedSub.getWorkflowStatus());
    }

    // =========================================================================
    // 2. TWO-STAGE LIMITS, EXACT BOUNDARIES, RESERVATIONS & MIDNIGHT ROLLOVER
    // =========================================================================

    @Test
    void approvalLimits_ExactVsExceededThresholds_ManagerEscalation_AdminOverride_And_ConfigHistory() throws Exception {
        LocalDate baseDate = LocalDate.of(2026, 9, 5);
        setLimitClock(baseDate.atTime(10, 0));

        Showtime st = createShowtime(cinema1, LocalDateTime.now(HCM_ZONE).plusHours(5), LocalDateTime.now(HCM_ZONE).plusHours(7));

        // --- A. Per-Request Limit: 500,000 VND ALLOWED vs 500,001 VND BLOCKED ---
        RefundRequestDetailDto req500k = createAndSubmitRefund(st, customer1, 500_000L, "10.60.1.1");
        RefundRequestDetailDto approved500k = approveBy(req500k.getId(), manager1, null, null);
        assertEquals("AWAITING_PAYOUT_APPROVAL", approved500k.getRequestStatus());

        RefundRequestDetailDto req500kPlus1 = createAndSubmitRefund(st, customer1, 500_001L, "10.60.1.2");
        BusinessException perReqEx = assertThrows(BusinessException.class, () ->
                approveBy(req500kPlus1.getId(), manager1, null, null));
        assertEquals(HttpStatus.CONFLICT, perReqEx.getStatus());
        assertEquals("LIMIT_EXCEEDED_ESCALATION_REQUIRED", perReqEx.getErrorCode());
        assertTrue(perReqEx.getMessage().contains("MANAGER_PER_REQUEST"));

        // Manager cannot set limitOverrideApproved=true -> 403 MANAGER_CANNOT_OVERRIDE_LIMIT
        BusinessException mgrOverrideEx = assertThrows(BusinessException.class, () ->
                approveBy(req500kPlus1.getId(), manager1, true, "Manager override attempt"));
        assertEquals(HttpStatus.FORBIDDEN, mgrOverrideEx.getStatus());
        assertEquals("MANAGER_CANNOT_OVERRIDE_LIMIT", mgrOverrideEx.getErrorCode());

        // Manager escalates to Admin -> ESCALATED_TO_ADMIN
        RefundReviewRequestDto escalateDto = new RefundReviewRequestDto();
        escalateDto.setAction("ESCALATE_TO_ADMIN");
        escalateDto.setNote("Amount 500,001 VND exceeds 500k manager per-request cap");
        RefundRequestDetailDto escalated = runAs(manager1, () ->
                refundService.reviewRefundRequest(req500kPlus1.getId(), escalateDto, manager1.getUserId()));
        assertEquals("ESCALATED_TO_ADMIN", escalated.getRequestStatus());

        // Admin without override flag -> 409 LIMIT_OVERRIDE_REQUIRED; blank reason -> 400 LIMIT_OVERRIDE_REASON_REQUIRED
        BusinessException admMissingFlag = assertThrows(BusinessException.class, () ->
                approveBy(req500kPlus1.getId(), admin1, false, null));
        assertEquals("LIMIT_OVERRIDE_REQUIRED", admMissingFlag.getErrorCode());

        BusinessException admBlankReason = assertThrows(BusinessException.class, () ->
                approveBy(req500kPlus1.getId(), admin1, true, "   "));
        assertEquals(HttpStatus.BAD_REQUEST, admBlankReason.getStatus());
        assertEquals("LIMIT_OVERRIDE_REASON_REQUIRED", admBlankReason.getErrorCode());

        // Admin approves with explicit override + mandatory reason -> records refund_limit_overrides + audit, global config unchanged!
        String overrideReason = "VIP IMAX screening cancellation approved by Head Office";
        RefundRequestDetailDto admOverridden = approveBy(req500kPlus1.getId(), admin1, true, overrideReason);
        assertEquals("AWAITING_PAYOUT_APPROVAL", admOverridden.getRequestStatus());
        assertTrue(admOverridden.isLimitOverrideApplied());
        assertEquals(admin1.getUserId(), admOverridden.getLimitOverrideBy());
        assertEquals(overrideReason, admOverridden.getLimitOverrideReason());
        assertEquals(500_000L, refundLimitService.getLimitAmountVnd(RefundLimitConfig.KEY_MANAGER_PER_REQUEST),
                "Admin per-request override must NOT alter global MANAGER_PER_REQUEST config");
        assertEquals(1, overrideRepository.findByRefundRequestIdOrderByCreatedAtDesc(req500kPlus1.getId()).size());
        assertTrue(auditLogRepository.findByRefundRequestIdOrderByCreatedAtDesc(req500kPlus1.getId()).stream()
                .anyMatch(a -> "REFUND_LIMIT_OVERRIDE_APPLIED".equals(a.getEventType())));

        // Cancel admOverridden so Cinema daily usage returns to 500,000 for clean cumulative boundary checks below
        cancelRefundAsAdmin(req500kPlus1.getId(), "Resetting cinema bucket for exact 2M/5M threshold test");

        // --- B. Manager Daily Limit: 2,000,000 VND exact ALLOWED vs 2,000,001 VND BLOCKED ---
        // manager1 already has 500,000 RESERVED; add 3 x 500,000 = 2,000,000 exact -> ALLOWED
        for (int i = 0; i < 3; i++) {
            RefundRequestDetailDto r = createAndSubmitRefund(st, customer2, 500_000L, "10.60.2." + (i + 1));
            assertEquals("AWAITING_PAYOUT_APPROVAL", approveBy(r.getId(), manager1, null, null).getRequestStatus());
        }
        RefundLimitSummaryDto mgrSummaryAt2M = refundLimitService.getQuotaSummary(cinema1.getCinemaId(), manager1.getUserId(), null, baseDate);
        assertEquals(2_000_000L, mgrSummaryAt2M.getManagerReservedVnd());
        assertEquals(0L, mgrSummaryAt2M.getManagerAvailableVnd());

        // +1 VND (1 VND request -> 2,000,001 VND) for manager1 -> 409 LIMIT_EXCEEDED_ESCALATION_REQUIRED (MANAGER_DAILY)
        RefundRequestDetailDto mgrOverBy1 = createAndSubmitRefund(st, customer2, 1L, "10.60.2.99");
        BusinessException mgrDailyEx = assertThrows(BusinessException.class, () ->
                approveBy(mgrOverBy1.getId(), manager1, null, null));
        assertEquals("LIMIT_EXCEEDED_ESCALATION_REQUIRED", mgrDailyEx.getErrorCode());
        assertTrue(mgrDailyEx.getMessage().contains("MANAGER_DAILY(2000001>2000000)"));

        // --- C. Cinema Daily Limit: 5,000,000 VND exact ALLOWED vs 5,000,001 VND BLOCKED ---
        // Cinema1 currently has 2,000,000 from manager1. Add 2,000,000 via manager1B + 1,000,000 via manager1C = 5,000,000 exact!
        for (int i = 0; i < 4; i++) {
            RefundRequestDetailDto r = createAndSubmitRefund(st, customer1, 500_000L, "10.60.3." + (i + 1));
            refundRateLimitService.resetForTesting();
            assertEquals("AWAITING_PAYOUT_APPROVAL", approveBy(r.getId(), manager1B, null, null).getRequestStatus());
        }
        for (int i = 0; i < 2; i++) {
            RefundRequestDetailDto r = createAndSubmitRefund(st, customer1, 500_000L, "10.60.4." + (i + 1));
            refundRateLimitService.resetForTesting();
            assertEquals("AWAITING_PAYOUT_APPROVAL", approveBy(r.getId(), manager1C, null, null).getRequestStatus());
        }
        RefundLimitSummaryDto cinSummaryAt5M = refundLimitService.getQuotaSummary(cinema1.getCinemaId(), manager1C.getUserId(), null, baseDate);
        assertEquals(5_000_000L, cinSummaryAt5M.getCinemaReservedVnd());
        assertEquals(0L, cinSummaryAt5M.getCinemaAvailableVnd());
        assertEquals(1_000_000L, cinSummaryAt5M.getManagerAvailableVnd(), "manager1C still has 1M personal room");

        // Now manager1C tries to approve 1 VND (mgrOverBy1) -> Cinema1 hits 5,000,001 -> BLOCKED by CINEMA_DAILY!
        BusinessException cinDailyEx = assertThrows(BusinessException.class, () ->
                approveBy(mgrOverBy1.getId(), manager1C, null, null));
        assertEquals("LIMIT_EXCEEDED_ESCALATION_REQUIRED", cinDailyEx.getErrorCode());
        assertTrue(cinDailyEx.getMessage().contains("CINEMA_DAILY(5000001>5000000)"));

        // --- D. Incident Cumulative Limit: 10,000,000 VND exact ALLOWED vs 10,000,001 VND BLOCKED + Config Update History ---
        // Advance to next day so Cinema daily & Manager daily are fresh, create verified incident, seed 9,500,000 committed on incident
        LocalDate incDate = LocalDate.of(2026, 9, 6);
        setLimitClock(incDate.atTime(11, 0));

        IncidentCreateRequestDto incReq = new IncidentCreateRequestDto();
        incReq.setCinemaId(cinema1.getCinemaId());
        incReq.setShowtimeId(st.getShowtimeId());
        incReq.setIncidentGroup("CINEMA_INCIDENT");
        incReq.setIncidentType("PROJECTOR_LAMP");
        incReq.setSeverity("HIGH");
        incReq.setTitle("Lamp blowout");
        incReq.setDescription("Verified projector outage");
        var incCreated = runAs(staff1, () -> incidentService.createIncident(incReq, staff1.getUserId()));
        createdIncidentIds.add(incCreated.getId());
        runAs(manager1, () -> incidentService.verifyByManager(incCreated.getId(), "VERIFIED", "Verified outage", manager1.getUserId()));

        // Seed 9,500,000 VND linked to this incident via Admin override on incDate, then advance 1 day so CINEMA_DAILY resets while INCIDENT_CUMULATIVE retains 9.5M
        RefundRequestDetailDto incSeed = createAndSubmitRefund(st, customer1, 9_500_000L, "10.60.5.1");
        refundRateLimitService.resetForTesting();
        approveBy(incSeed.getId(), admin1, true, "Bulk incident seed 9.5M");

        setLimitClock(incDate.plusDays(1).atTime(11, 0));

        // Manager1 approves 500,000 VND -> incident cumulative total = 10,000,000 exact -> ALLOWED!
        RefundRequestDetailDto incExact10M = createAndSubmitRefund(st, customer2, 500_000L, "10.60.5.2");
        assertEquals("AWAITING_PAYOUT_APPROVAL", approveBy(incExact10M.getId(), manager1, null, null).getRequestStatus());

        // Next 1 VND on same incident -> 10,000,001 VND -> BLOCKED with INCIDENT_CUMULATIVE(10000001>10000000)!
        RefundRequestDetailDto incOver10M = createAndSubmitRefund(st, customer2, 1L, "10.60.5.3");
        BusinessException incCapEx = assertThrows(BusinessException.class, () ->
                approveBy(incOver10M.getId(), manager1, null, null));
        assertEquals("LIMIT_EXCEEDED_ESCALATION_REQUIRED", incCapEx.getErrorCode());
        assertTrue(incCapEx.getMessage().contains("INCIDENT_CUMULATIVE(10000001>10000000)"));

        // Admin updates global config via PUT /api/admin/refunds/limits -> logs refund_limit_config_history + audit
        mockMvc.perform(put("/api/admin/refunds/limits").with(csrf()).with(authOf(admin1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "configKey", "MANAGER_PER_REQUEST",
                                "limitAmountVnd", 600_000L,
                                "reason", "Holiday surge policy adjustment"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limitAmountVnd").value(600_000));

        List<RefundLimitConfigHistory> cfgHist = limitConfigHistoryRepository.findByConfigKeyOrderByCreatedAtDesc("MANAGER_PER_REQUEST");
        assertFalse(cfgHist.isEmpty());
        assertEquals(500_000L, cfgHist.get(0).getPreviousAmountVnd());
        assertEquals(600_000L, cfgHist.get(0).getNewAmountVnd());
        assertEquals("Holiday surge policy adjustment", cfgHist.get(0).getReason());
    }

    @Test
    void reservationLifecycle_NoDoubleCount_PostApprovalTightening_MidnightRollover_States_And_Concurrency() throws Exception {
        LocalDate dayD = LocalDate.of(2026, 9, 10);
        setLimitClock(dayD.atTime(23, 50));
        Showtime st = createShowtime(cinema1, LocalDateTime.now(HCM_ZONE).plusHours(6), LocalDateTime.now(HCM_ZONE).plusHours(8));

        // 1. Approve R1 (400k) and R2 (400k) on Day D (23:50 ICT) -> RESERVED
        RefundRequestDetailDto r1 = createAndSubmitRefund(st, customer1, 400_000L, "10.70.1.1");
        RefundRequestDetailDto r2 = createAndSubmitRefund(st, customer2, 400_000L, "10.70.1.2");
        approveBy(r1.getId(), manager1, null, null);
        approveBy(r2.getId(), manager1, null, null);

        RefundLimitReservation res1 = reservationRepository.findByActiveLockKey("REQ:" + r1.getId()).orElseThrow();
        assertEquals("RESERVED", res1.getStatus());
        assertEquals(dayD, res1.getQuotaDate());

        // 2. Post-approval config tightening BEFORE payout blocks payout without double-counting or losing reservation!
        runAs(admin2, () -> refundLimitService.updateLimitConfig("CINEMA_DAILY", 350_000L, "Temporary freeze tightening", admin2.getUserId()));
        mockMvc.perform(post("/api/admin/refunds/" + r1.getId() + "/payout/approve").with(csrf()).with(authOf(admin2))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Attempt during tightened limit\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LIMIT_EXCEEDED_BEFORE_PAYOUT"));
        assertEquals("RESERVED", reservationRepository.findByActiveLockKey("REQ:" + r1.getId()).orElseThrow().getStatus());

        // Restore CINEMA_DAILY to 800,000 exact (matching R1 400k + R2 400k -> proves ZERO double-counting of R1!)
        runAs(admin2, () -> refundLimitService.updateLimitConfig("CINEMA_DAILY", 800_000L, "Exact 800k cap check", admin2.getUserId()));

        // 3. Roll clock across midnight to Day D+1 (2026-09-11 00:05 ICT), tighten CINEMA_DAILY to 500,000:
        //    - R1 (400k) rolls over cleanly from Day D -> Day D+1 and reaches SUCCESS -> COMMITTED!
        //    - Manager's approval date stays anchored to Day D (reservedAt = 2026-09-10)!
        //    - R2 (400k) attempts rollover onto Day D+1 (400k + 400k = 800k > 500k) -> BLOCKED (409 CINEMA_DAILY_LIMIT_EXCEEDED_ON_ROLLOVER),
        //      and R2's Day D reservation is NOT prematurely released!
        LocalDate dayDPlus1 = dayD.plusDays(1);
        setLimitClock(dayDPlus1.atTime(0, 5));
        runAs(admin2, () -> refundLimitService.updateLimitConfig("CINEMA_DAILY", 500_000L, "Day D+1 500k cap", admin2.getUserId()));

        mockMvc.perform(post("/api/admin/refunds/" + r1.getId() + "/payout/approve").with(csrf()).with(authOf(admin2))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Rollover R1 payout\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("COMPLETED"));

        RefundLimitReservation r1AfterSuccess = reservationRepository.findByRefundRequestIdOrderByIdAsc(r1.getId()).get(0);
        assertEquals("COMMITTED", r1AfterSuccess.getStatus());
        assertEquals(dayDPlus1, r1AfterSuccess.getQuotaDate(), "Cinema quotaDate must roll over to actual payout date Day D+1");
        assertEquals(dayD, r1AfterSuccess.getReservedAt().toLocalDate(), "Manager approval anchor date must remain Day D");

        // R2 rollover fails because Day D+1 already has 400k committed (400k + 400k > 500k) -> R2 stays RESERVED on Day D!
        mockMvc.perform(post("/api/admin/refunds/" + r2.getId() + "/payout/approve").with(csrf()).with(authOf(admin2))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Rollover R2 overflow\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CINEMA_DAILY_LIMIT_EXCEEDED_ON_ROLLOVER"));

        RefundLimitReservation r2StillOnDayD = reservationRepository.findByActiveLockKey("REQ:" + r2.getId()).orElseThrow();
        assertEquals("RESERVED", r2StillOnDayD.getStatus());
        assertEquals(dayD, r2StillOnDayD.getQuotaDate(), "Old Day D reservation must stay intact when Day D+1 rollover fails");

        // Restore default 5M cinema cap and test PROCESSING/UNKNOWN_OUTCOME retains RESERVED, then FAILED -> RELEASED!
        restoreDefaultLimitConfigs();
        payOsPayoutService.setCustomTransportExecutor(new PayOsPayoutService.TransportExecutor() {
            @Override
            public PayOsPayoutService.HttpOutcome postPayout(String url, HttpHeaders headers, Map<String, Object> bodyMap) {
                return new PayOsPayoutService.HttpOutcome(0, null, null, true, "Simulated network timeout");
            }

            @Override
            public PayOsPayoutService.HttpOutcome getPayout(String url, HttpHeaders headers) {
                return new PayOsPayoutService.HttpOutcome(0, null, null, true, "Simulated network timeout");
            }
        });

        mockMvc.perform(post("/api/admin/refunds/" + r2.getId() + "/payout/approve").with(csrf()).with(authOf(admin2))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"Dispatch with timeout\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestStatus").value("PAYOUT_PROCESSING"));

        assertEquals("RESERVED", reservationRepository.findByActiveLockKey("REQ:" + r2.getId()).orElseThrow().getStatus(),
                "PROCESSING / UNKNOWN_OUTCOME payout must retain RESERVED quota");

        PayOsPayoutTransaction timeoutTx = runAs(admin2, () ->
                refundService.getRefundDetailForStaffOrManager(r2.getId(), admin2.getUserId()))
                .getPayoutTransactions().stream()
                .map(p -> jdbcTemplate.queryForObject("SELECT id FROM payos_payout_transactions WHERE id = ?", Long.class, p.getId()))
                .findFirst().map(txId -> {
                    PayOsPayoutTransaction t = new PayOsPayoutTransaction();
                    t.setId(txId);
                    return t;
                }).orElseThrow();

        mockMvc.perform(post("/api/admin/payouts/" + timeoutTx.getId() + "/confirm-failed").with(csrf()).with(authOf(admin2))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Confirmed not debited at bank\"}"))
                .andExpect(status().isOk());

        RefundLimitReservation r2Released = reservationRepository.findByRefundRequestIdOrderByIdAsc(r2.getId()).get(0);
        assertEquals("RELEASED", r2Released.getStatus());
        assertNull(r2Released.getActiveLockKey(), "Definitive FAILED payout must release reservation and clear activeLockKey");

        // 4. Multi-threaded concurrency race: 8 threads racing 400,000 VND approvals for manager1B on Day D+2 (2,000,000 cap -> max 5 win!)
        payOsPayoutService.setCustomTransportExecutor(null);
        LocalDate raceDay = LocalDate.of(2026, 9, 12);
        setLimitClock(raceDay.atTime(14, 0));

        int threads = 8;
        List<Long> raceRefundIds = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            refundRateLimitService.resetForTesting();
            raceRefundIds.add(createAndSubmitRefund(st, customer1, 400_000L, "10.70.9." + (i + 1)).getId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger(0);
        AtomicInteger limitBlocks = new AtomicInteger(0);
        List<Throwable> unexpected = Collections.synchronizedList(new ArrayList<>());

        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final Long rfId = raceRefundIds.get(i);
                futures.add(pool.submit(() -> {
                    authenticateThreadAs(manager1B);
                    ready.countDown();
                    try {
                        start.await();
                        approveBy(rfId, manager1B, null, null);
                        wins.incrementAndGet();
                    } catch (BusinessException bex) {
                        if ("LIMIT_EXCEEDED_ESCALATION_REQUIRED".equals(bex.getErrorCode())) {
                            limitBlocks.incrementAndGet();
                        } else {
                            unexpected.add(bex);
                        }
                    } catch (Throwable t) {
                        unexpected.add(t);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            for (Future<?> f : futures) {
                f.get(25, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            SecurityContextHolder.clearContext();
        }

        assertTrue(unexpected.isEmpty(), "Unexpected errors in concurrent limit race: " + unexpected);
        assertEquals(5, wins.get(), "Exactly 5 x 400k = 2,000,000 VND approvals may succeed under concurrent race");
        assertEquals(3, limitBlocks.get(), "Remaining 3 concurrent threads must be blocked by atomic InnoDB bucket lock");

        Long activeMgrSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount_vnd), 0) FROM refund_limit_reservations WHERE manager_user_id = ? AND status IN ('RESERVED','COMMITTED')",
                Long.class, manager1B.getUserId());
        assertEquals(2_000_000L, activeMgrSum);
    }

    // =========================================================================
    // 3. SEPARATE CUSTOMER REFUND APPEALS WORKFLOW & RBAC
    // =========================================================================

    @Test
    void customerRefundAppeals_StateMachine_OwnershipAndBranchIsolation_DuplicateLock_And_AuditTrail() throws Exception {
        Showtime st = createShowtime(cinema1, LocalDateTime.now(HCM_ZONE).plusHours(5), LocalDateTime.now(HCM_ZONE).plusHours(7));
        RefundRequestDetailDto r1 = createAndSubmitRefund(st, customer1, 120_000L, "10.80.1.1");
        long totalRefundRowsBefore = refundRequestRepository.count();

        // 1. Appealing a non-rejected (PENDING_REVIEW) refund is rejected with 409 APPEAL_ONLY_ALLOWED_WHEN_REJECTED
        mockMvc.perform(post("/api/refunds/public/" + r1.getId() + "/appeals").with(csrf()).with(authOf(customer1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("appealReason", "Premature appeal"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("APPEAL_ONLY_ALLOWED_WHEN_REJECTED"));

        // 2. Manager1 rejects r1 -> REJECTED
        rejectAllItems(r1.getId(), manager1, "Insufficient proof initially");
        assertEquals("REJECTED", refundRequestRepository.findById(r1.getId()).orElseThrow().getWorkflowStatus());

        // 3. Cross-customer (customer2 without matching email/userId) CANNOT submit or read appeals on r1 -> 403
        mockMvc.perform(post("/api/refunds/public/" + r1.getId() + "/appeals").with(csrf()).with(authOf(customer2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("appealReason", "Hijack appeal attempt"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("APPEAL_ACCESS_DENIED"));

        // 4. Owning customer1 submits appeal -> 201 APPEAL_SUBMITTED (zero new RefundRequest rows created!)
        MvcResult subRes = mockMvc.perform(post("/api/refunds/public/" + r1.getId() + "/appeals").with(csrf()).with(authOf(customer1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purchaseEmail", customer1.getEmail(),
                                "appealReason", "Attached hospital discharge slip proving emergency"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(RefundAppealStatus.APPEAL_SUBMITTED.name()))
                .andExpect(jsonPath("$.activeLockOpen").value(true))
                .andReturn();
        long appeal1Id = objectMapper.readTree(subRes.getResponse().getContentAsString()).get("id").asLong();
        assertEquals(totalRefundRowsBefore, refundRequestRepository.count(), "Appeal must NOT create a duplicate RefundRequest");

        // 5. Duplicate open appeal on same RefundRequest -> 409 APPEAL_ALREADY_OPEN
        mockMvc.perform(post("/api/refunds/public/" + r1.getId() + "/appeals").with(csrf()).with(authOf(customer1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("appealReason", "Second open appeal"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("APPEAL_ALREADY_OPEN"));

        // 6. Cross-cinema manager2 (cinema2) is blocked (403) from viewing, starting review, or deciding appeal1
        mockMvc.perform(get("/api/manager/refunds/appeals/" + appeal1Id).with(authOf(manager2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CROSS_CINEMA_APPEAL_FORBIDDEN"));
        mockMvc.perform(post("/api/manager/refunds/appeals/" + appeal1Id + "/start-review").with(csrf()).with(authOf(manager2)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/manager/refunds/appeals/" + appeal1Id + "/decide").with(csrf()).with(authOf(manager2))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("decision", "ACCEPT", "decisionNote", "Cross branch"))))
                .andExpect(status().isForbidden());

        // 7. Assigned manager1 transitions APPEAL_SUBMITTED -> APPEAL_UNDER_REVIEW -> APPEAL_ACCEPTED
        mockMvc.perform(post("/api/manager/refunds/appeals/" + appeal1Id + "/start-review").with(csrf()).with(authOf(manager1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(RefundAppealStatus.APPEAL_UNDER_REVIEW.name()));

        // Blank decisionNote rejected -> 400 APPEAL_DECISION_NOTE_REQUIRED
        mockMvc.perform(post("/api/manager/refunds/appeals/" + appeal1Id + "/decide").with(csrf()).with(authOf(manager1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("decision", "ACCEPT", "decisionNote", "  "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("APPEAL_DECISION_NOTE_REQUIRED"));

        MvcResult acceptRes = mockMvc.perform(post("/api/manager/refunds/appeals/" + appeal1Id + "/decide").with(csrf()).with(authOf(manager1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decision", "ACCEPT",
                                "decisionNote", "Verified new medical proof — reopening for screening"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(RefundAppealStatus.APPEAL_ACCEPTED.name()))
                .andExpect(jsonPath("$.activeLockOpen").value(false))
                .andReturn();

        JsonNode acceptedNode = objectMapper.readTree(acceptRes.getResponse().getContentAsString());
        assertEquals(3, acceptedNode.get("history").size(), "History must record SUBMITTED -> UNDER_REVIEW -> ACCEPTED");

        RefundRequest reopenedR1 = refundRequestRepository.findById(r1.getId()).orElseThrow();
        assertEquals(RefundWorkflowState.UNDER_REVIEW.name(), reopenedR1.getWorkflowStatus(),
                "Accepted appeal must return original RefundRequest to UNDER_REVIEW");
        assertEquals("UNDER_VERIFICATION", reopenedR1.getRequestStatus());
        assertEquals("NOT_REQUIRED", reopenedR1.getPayoutStatus(), "Accepted appeal must NEVER auto-approve or dispatch payout");
        assertEquals(0, BigDecimal.ZERO.compareTo(reopenedR1.getApprovedCashTotal()));

        // 8. Admin decides APPEAL_REJECTED across cinemas -> original RefundRequest stays REJECTED
        rejectAllItems(r1.getId(), manager1, "Rejected again on secondary check");
        MvcResult subRes2 = mockMvc.perform(post("/api/refunds/public/" + r1.getId() + "/appeals").with(csrf()).with(authOf(customer1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("appealReason", "Second appeal after new rejection"))))
                .andExpect(status().isCreated())
                .andReturn();
        long appeal2Id = objectMapper.readTree(subRes2.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(post("/api/admin/refunds/appeals/" + appeal2Id + "/decide").with(csrf()).with(authOf(admin1))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "decision", "REJECT",
                                "decisionNote", "Final review upholds rejection"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(RefundAppealStatus.APPEAL_REJECTED.name()))
                .andExpect(jsonPath("$.activeLockOpen").value(false));

        RefundRequest stillRejected = refundRequestRepository.findById(r1.getId()).orElseThrow();
        assertEquals(RefundWorkflowState.REJECTED.name(), stillRejected.getWorkflowStatus());
        assertEquals("REJECTED", stillRejected.getRequestStatus());

        List<RefundAuditLog> appealAudits = auditLogRepository.findByRefundRequestIdOrderByCreatedAtDesc(r1.getId());
        assertTrue(appealAudits.stream().anyMatch(a -> "REFUND_APPEAL_SUBMITTED".equals(a.getEventType())));
        assertTrue(appealAudits.stream().anyMatch(a -> "REFUND_APPEAL_ACCEPTED".equals(a.getEventType())));
        assertTrue(appealAudits.stream().anyMatch(a -> "REFUND_APPEAL_REJECTED".equals(a.getEventType())));
    }

    // =========================================================================
    // 4. EVIDENCE UPLOAD, MALWARE QUARANTINE & AUTHENTICATED DOWNLOAD SECURITY
    // =========================================================================

    @Test
    void evidenceSecurity_RejectsDoubleExtSpoofPolyglotOversizeBatch_And_EnforcesRoleScopedDownloadAndQuarantineBlock() throws Exception {
        byte[] cleanPng = buildValidPngBytes(null);

        // 1. Double extensions (a.php.jpg, payload.html.png) -> 400 Bad Request
        MockMultipartFile dblPhp = new MockMultipartFile("files", "a.php.jpg", "image/jpeg", buildValidJpegBytes());
        mockMvc.perform(multipart("/api/refunds/evidence/upload").file(dblPhp).with(csrf()).with(authOf(customer1)))
                .andExpect(status().isBadRequest());

        MockMultipartFile dblHtml = new MockMultipartFile("files", "payload.html.png", "image/png", cleanPng);
        mockMvc.perform(multipart("/api/refunds/evidence/upload").file(dblHtml).with(csrf()).with(authOf(customer1)))
                .andExpect(status().isBadRequest());

        // 2. Path traversal filenames -> 400 Bad Request
        MockMultipartFile traversal = new MockMultipartFile("files", "../escape.png", "image/png", cleanPng);
        mockMvc.perform(multipart("/api/refunds/evidence/upload").file(traversal).with(csrf()).with(authOf(customer1)))
                .andExpect(status().isBadRequest());

        // 3. Disallowed SVG / HTML / executable types & MIME spoofing / magic-byte mismatch -> 400 Bad Request
        MockMultipartFile svgFile = new MockMultipartFile(
                "files", "vector.svg", "image/svg+xml", "<svg onload=alert(1)></svg>".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/refunds/evidence/upload").file(svgFile).with(csrf()).with(authOf(customer1)))
                .andExpect(status().isBadRequest());

        MockMultipartFile mimeSpoof = new MockMultipartFile("files", "spoof.png", "image/png", buildValidJpegBytes());
        mockMvc.perform(multipart("/api/refunds/evidence/upload").file(mimeSpoof).with(csrf()).with(authOf(customer1)))
                .andExpect(status().isBadRequest());

        MockMultipartFile badMagic = new MockMultipartFile(
                "files", "fake.jpg", "image/jpeg", "NOT_AN_IMAGE_HEADER_1234567890".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/refunds/evidence/upload").file(badMagic).with(csrf()).with(authOf(customer1)))
                .andExpect(status().isBadRequest());

        // 4. Oversized file (> 5 MB) -> 400 Bad Request
        byte[] over5Mb = new byte[5 * 1024 * 1024 + 2048];
        System.arraycopy(cleanPng, 0, over5Mb, 0, cleanPng.length);
        MockMultipartFile hugeFile = new MockMultipartFile("files", "huge.png", "image/png", over5Mb);
        mockMvc.perform(multipart("/api/refunds/evidence/upload").file(hugeFile).with(csrf()).with(authOf(customer1)))
                .andExpect(status().isBadRequest());

        // 5. > 5 files in single upload request -> 400 EVIDENCE_FILE_COUNT_EXCEEDED
        var multiReq = multipart("/api/refunds/evidence/upload");
        for (int i = 0; i < 6; i++) {
            multiReq.file(new MockMultipartFile("files", "img" + i + ".png", "image/png", cleanPng));
        }
        mockMvc.perform(multiReq.with(csrf()).with(authOf(customer1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("EVIDENCE_FILE_COUNT_EXCEEDED"));

        // 6. Polyglot PNG containing embedded PHP/script payload -> 400 EVIDENCE_QUARANTINED, never served even to ADMIN (403)!
        refundRateLimitService.resetForTesting();
        byte[] polyglotPng = buildValidPngBytes("<?php system($_GET['x']); ?><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile polyglotFile = new MockMultipartFile("files", "polyglot.png", "image/png", polyglotPng);
        MvcResult qRes = mockMvc.perform(multipart("/api/refunds/evidence/upload").file(polyglotFile).with(csrf()).with(authOf(customer1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("EVIDENCE_QUARANTINED"))
                .andExpect(jsonPath("$.scanStatus").value("QUARANTINED"))
                .andReturn();
        String qStorageName = objectMapper.readTree(qRes.getResponse().getContentAsString()).get("storageName").asText();
        createdStorageNames.add(qStorageName);

        RefundEvidenceFile qMeta = evidenceFileRepository.findByStorageName(qStorageName).orElseThrow();
        assertEquals("QUARANTINED", qMeta.getScanStatus());
        assertFalse(Files.exists(Paths.get("uploads/refund-evidence").toAbsolutePath().normalize().resolve(qStorageName)),
                "Quarantined file must NOT exist in active upload directory");

        // Even ADMIN attempting to download a QUARANTINED file gets 403 EVIDENCE_QUARANTINED!
        mockMvc.perform(get("/api/refunds/evidence/" + qStorageName).with(authOf(admin1)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("EVIDENCE_QUARANTINED"));

        // 7. Clean authentic PNG linked to cinema1 refund -> test full download RBAC matrix + audit log
        Showtime st = createShowtime(cinema1, LocalDateTime.now(HCM_ZONE).plusHours(5), LocalDateTime.now(HCM_ZONE).plusHours(7));
        RefundRequestDetailDto r1 = createAndSubmitRefund(st, customer1, 100_000L, "10.90.1.1");

        MockMultipartFile validUpload = new MockMultipartFile("files", "seat_issue.png", "image/png", cleanPng);
        MvcResult cleanUpRes = mockMvc.perform(multipart("/api/refunds/evidence/upload")
                        .file(validUpload)
                        .param("refundRequestId", String.valueOf(r1.getId()))
                        .with(csrf()).with(authOf(customer1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanStatus").value("CLEAN"))
                .andReturn();
        JsonNode cleanNode = objectMapper.readTree(cleanUpRes.getResponse().getContentAsString());
        String storageName = cleanNode.get("storageName").asText();
        String authUrl = cleanNode.get("url").asText();
        String pubCompatUrl = cleanNode.get("publicUrl").asText();
        createdStorageNames.add(storageName);

        // 7a. Unauthenticated download -> 401 Unauthorized on BOTH /api/refunds/evidence/** and /api/refunds/public/evidence/**
        mockMvc.perform(get(authUrl)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(pubCompatUrl)).andExpect(status().isUnauthorized());

        // 7b. Path traversal on download endpoint -> 400
        mockMvc.perform(get("/api/refunds/evidence/..%2Fsecret.png").with(authOf(admin1)))
                .andExpect(result -> assertTrue(result.getResponse().getStatus() == 400 || result.getResponse().getStatus() == 404));

        // 7c. Unrelated customer2 -> 403 EVIDENCE_ACCESS_DENIED
        mockMvc.perform(get(authUrl).with(authOf(customer2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("EVIDENCE_ACCESS_DENIED"));

        // 7d. Cross-cinema manager2 & staff2 -> 403 BRANCH_ACCESS_DENIED
        mockMvc.perform(get(authUrl).with(authOf(manager2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BRANCH_ACCESS_DENIED"));
        mockMvc.perform(get(authUrl).with(authOf(staff2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("BRANCH_ACCESS_DENIED"));

        // 7e. Owning customer1, assigned manager1, assigned staff1, and global admin1 -> 200 OK with security headers + audit
        for (User allowed : List.of(customer1, manager1, staff1, admin1)) {
            mockMvc.perform(get(authUrl).with(authOf(allowed)))
                    .andExpect(status().isOk())
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("Content-Security-Policy", containsString("sandbox")));
        }

        List<RefundAuditLog> dlAudits = auditLogRepository.findByRefundRequestIdOrderByCreatedAtDesc(r1.getId());
        long dlCount = dlAudits.stream().filter(a -> "EVIDENCE_DOWNLOADED".equals(a.getEventType())).count();
        assertEquals(4L, dlCount, "Each authorized download must write an EVIDENCE_DOWNLOADED audit entry");
    }

    // =========================================================================
    // 5. BACKEND RATE LIMITING (429 Too Many Requests + Retry-After + Clock Reset)
    // =========================================================================

    @Test
    void backendRateLimiting_EnforcesCustomerAndIpWindows_Returns429WithRetryAfter_And_ResetsOnClockAdvance() throws Exception {
        Instant t0 = Instant.parse("2026-10-01T08:00:00Z");
        refundRateLimitService.resetForTesting();
        refundRateLimitService.setClock(Clock.fixed(t0, ZoneOffset.UTC));

        Showtime st = createShowtime(cinema1, LocalDateTime.now(HCM_ZONE).plusHours(5), LocalDateTime.now(HCM_ZONE).plusHours(7));

        // --- A. Refund Creation: 5 requests / customer / 10 minutes ---
        for (int i = 0; i < 5; i++) {
            Booking b = createPaidBooking(st, cinema1, customer1, 80_000L);
            Ticket t = ticketRepository.findByBooking_BookingId(b.getBookingId()).get(0);
            mockMvc.perform(post("/api/refunds/public").with(csrf()).with(authOf(customer1))
                            .with(remoteIp("10.100.1." + (i + 1)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(buildRefundSubmitJson(b, t.getTicketId(), customer1.getEmail())))
                    .andExpect(status().isCreated());
        }

        // 6th refund request by customer1 within 10m -> 429 Too Many Requests + Retry-After header!
        Booking b6 = createPaidBooking(st, cinema1, customer1, 80_000L);
        Ticket t6 = ticketRepository.findByBooking_BookingId(b6.getBookingId()).get(0);
        MvcResult rlCustRes = mockMvc.perform(post("/api/refunds/public").with(csrf()).with(authOf(customer1))
                        .with(remoteIp("10.100.1.99"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundSubmitJson(b6, t6.getTicketId(), customer1.getEmail())))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.limitScope").value("REFUND_CREATE_CUSTOMER"))
                .andReturn();
        long retryAfterCust = Long.parseLong(Objects.requireNonNull(rlCustRes.getResponse().getHeader(HttpHeaders.RETRY_AFTER)));
        assertTrue(retryAfterCust >= 1 && retryAfterCust <= 600);

        // Advance clock 10 minutes + 1 second -> customer1's 10m window clears and 6th request succeeds!
        refundRateLimitService.setClock(Clock.fixed(t0.plusSeconds(601), ZoneOffset.UTC));
        mockMvc.perform(post("/api/refunds/public").with(csrf()).with(authOf(customer1))
                        .with(remoteIp("10.100.1.99"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundSubmitJson(b6, t6.getTicketId(), customer1.getEmail())))
                .andExpect(status().isCreated());

        // --- B. Refund Creation IP Limit: 20 requests / IP / 1 hour across distinct guest emails ---
        String sharedIp = "203.0.113.55";
        for (int i = 0; i < 20; i++) {
            String guestEmail = "ipguest_" + i + "_" + System.nanoTime() + "@box.test";
            Booking gb = createPaidGuestBooking(st, cinema1, guestEmail, 75_000L);
            Ticket gt = ticketRepository.findByBooking_BookingId(gb.getBookingId()).get(0);
            mockMvc.perform(post("/api/refunds/public").with(csrf())
                            .with(remoteIp(sharedIp))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(buildRefundSubmitJson(gb, gt.getTicketId(), guestEmail)))
                    .andExpect(status().isCreated());
        }

        // 21st request from sharedIp within 1h -> 429 + Retry-After (REFUND_CREATE_IP)
        String guest21 = "ipguest_21_" + System.nanoTime() + "@box.test";
        Booking gb21 = createPaidGuestBooking(st, cinema1, guest21, 75_000L);
        Ticket gt21 = ticketRepository.findByBooking_BookingId(gb21.getBookingId()).get(0);
        MvcResult rlIpRes = mockMvc.perform(post("/api/refunds/public").with(csrf())
                        .with(remoteIp(sharedIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildRefundSubmitJson(gb21, gt21.getTicketId(), guest21)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.limitScope").value("REFUND_CREATE_IP"))
                .andReturn();
        assertTrue(Long.parseLong(Objects.requireNonNull(rlIpRes.getResponse().getHeader(HttpHeaders.RETRY_AFTER))) > 0);

        // --- C. Evidence Upload Rate Limit: 15/customer/10m & 30/IP/h -> 429 + Retry-After ---
        refundRateLimitService.resetForTesting();
        refundRateLimitService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        byte[] cleanPng = buildValidPngBytes(null);
        for (int i = 0; i < 15; i++) {
            MockMultipartFile f = new MockMultipartFile("files", "up" + i + ".png", "image/png", cleanPng);
            MvcResult okUp = mockMvc.perform(multipart("/api/refunds/evidence/upload").file(f)
                            .with(csrf()).with(authOf(customer2)).with(remoteIp("10.100.2." + (i + 1))))
                    .andExpect(status().isOk())
                    .andReturn();
            createdStorageNames.add(objectMapper.readTree(okUp.getResponse().getContentAsString()).get("storageName").asText());
        }
        MockMultipartFile f16 = new MockMultipartFile("files", "up16.png", "image/png", cleanPng);
        mockMvc.perform(multipart("/api/refunds/evidence/upload").file(f16)
                        .with(csrf()).with(authOf(customer2)).with(remoteIp("10.100.2.99")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.limitScope").value("EVIDENCE_UPLOAD_CUSTOMER"));

        // 30 uploads / IP / 1h across distinct authenticated users -> 31st from same IP hits EVIDENCE_UPLOAD_IP
        refundRateLimitService.resetForTesting();
        String uploadSharedIp = "203.0.113.77";
        List<User> uploadUsers = List.of(customer1, customer2, staff1);
        for (User u : uploadUsers) {
            for (int j = 0; j < 10; j++) {
                MockMultipartFile f = new MockMultipartFile("files", "ipup" + j + ".png", "image/png", cleanPng);
                MvcResult okUp = mockMvc.perform(multipart("/api/refunds/evidence/upload").file(f)
                                .with(csrf()).with(authOf(u)).with(remoteIp(uploadSharedIp)))
                        .andExpect(status().isOk())
                        .andReturn();
                createdStorageNames.add(objectMapper.readTree(okUp.getResponse().getContentAsString()).get("storageName").asText());
            }
        }
        MockMultipartFile f31 = new MockMultipartFile("files", "ipup31.png", "image/png", cleanPng);
        mockMvc.perform(multipart("/api/refunds/evidence/upload").file(f31)
                        .with(csrf()).with(authOf(staff2)).with(remoteIp(uploadSharedIp)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.limitScope").value("EVIDENCE_UPLOAD_IP"));

        // --- D. Appeal Submission Rate Limit: 5/customer/10m & 15/IP/h -> 429 + Retry-After ---
        refundRateLimitService.resetForTesting();
        refundRateLimitService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        RefundRequestDetailDto rejTarget = createAndSubmitRefund(st, customer2, 90_000L, "10.100.3.1");
        rejectAllItems(rejTarget.getId(), manager1, "Initial rejection for rate limit test");

        for (int i = 0; i < 5; i++) {
            MvcResult apSub = mockMvc.perform(post("/api/refunds/public/" + rejTarget.getId() + "/appeals")
                            .with(csrf()).with(authOf(customer2)).with(remoteIp("10.100.4." + (i + 1)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("appealReason", "Appeal #" + (i + 1)))))
                    .andExpect(status().isCreated())
                    .andReturn();
            long apId = objectMapper.readTree(apSub.getResponse().getContentAsString()).get("id").asLong();
            RefundAppealDecisionRequestDto dec = new RefundAppealDecisionRequestDto();
            dec.setDecision("REJECT");
            dec.setDecisionNote("Rejected iteration " + (i + 1));
            runAs(manager1, () -> refundAppealService.decideAppeal(apId, dec, manager1.getUserId()));
        }

        // 6th appeal within 10m -> 429 Too Many Requests + Retry-After (REFUND_APPEAL_CUSTOMER)!
        mockMvc.perform(post("/api/refunds/public/" + rejTarget.getId() + "/appeals")
                        .with(csrf()).with(authOf(customer2)).with(remoteIp("10.100.4.99"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("appealReason", "6th appeal over limit"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.limitScope").value("REFUND_APPEAL_CUSTOMER"));

        // 15 appeals / IP / 1h across distinct guest refund requests -> 16th from same IP hits REFUND_APPEAL_IP
        refundRateLimitService.resetForTesting();
        String appealSharedIp = "203.0.113.88";
        List<Long> guestRejectedIds = new ArrayList<>();
        List<String> guestEmails = new ArrayList<>();
        for (int g = 0; g < 4; g++) {
            String gEmail = "apip_" + g + "_" + System.nanoTime() + "@box.test";
            Booking agb = createPaidGuestBooking(st, cinema1, gEmail, 65_000L);
            Ticket agt = ticketRepository.findByBooking_BookingId(agb.getBookingId()).get(0);
            RefundRequestDetailDto gr = submitCustomerRefund(agb, agt.getTicketId(), null, "10.100.6." + (g + 1));
            rejectAllItems(gr.getId(), manager1, "Rejected guest refund #" + g);
            guestRejectedIds.add(gr.getId());
            guestEmails.add(gEmail);
        }
        refundRateLimitService.resetForTesting();
        refundRateLimitService.setClock(Clock.fixed(t0, ZoneOffset.UTC));
        int sentOnSharedIp = 0;
        for (int g = 0; g < 4; g++) {
            int countForThisGuest = (g < 3) ? 4 : 3; // 4 + 4 + 4 + 3 = 15 appeals on appealSharedIp (each <= 5/customer)
            for (int k = 0; k < countForThisGuest; k++) {
                MvcResult apSub = mockMvc.perform(post("/api/refunds/public/" + guestRejectedIds.get(g) + "/appeals")
                                .with(csrf()).with(remoteIp(appealSharedIp))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "purchaseEmail", guestEmails.get(g),
                                        "appealReason", "IP appeal #" + (++sentOnSharedIp)))))
                        .andExpect(status().isCreated())
                        .andReturn();
                long apId = objectMapper.readTree(apSub.getResponse().getContentAsString()).get("id").asLong();
                RefundAppealDecisionRequestDto dec = new RefundAppealDecisionRequestDto();
                dec.setDecision("REJECT");
                dec.setDecisionNote("Close appeal #" + sentOnSharedIp);
                runAs(manager1, () -> refundAppealService.decideAppeal(apId, dec, manager1.getUserId()));
            }
        }
        // 16th appeal from appealSharedIp (guest #3 only has 3 customer appeals so customer quota is NOT full; IP quota 15 IS full!)
        mockMvc.perform(post("/api/refunds/public/" + guestRejectedIds.get(3) + "/appeals")
                        .with(csrf()).with(remoteIp(appealSharedIp))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "purchaseEmail", guestEmails.get(3),
                                "appealReason", "16th IP appeal over 15/IP/h limit"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.limitScope").value("REFUND_APPEAL_IP"));
    }

    // =========================================================================
    // Helper Builders
    // =========================================================================

    private void restoreDefaultLimitConfigs() {
        jdbcTemplate.update("UPDATE refund_limit_configs SET limit_amount_vnd = 500000, updated_by = NULL, is_active = 1 WHERE config_key = 'MANAGER_PER_REQUEST'");
        jdbcTemplate.update("UPDATE refund_limit_configs SET limit_amount_vnd = 2000000, updated_by = NULL, is_active = 1 WHERE config_key = 'MANAGER_DAILY'");
        jdbcTemplate.update("UPDATE refund_limit_configs SET limit_amount_vnd = 5000000, updated_by = NULL, is_active = 1 WHERE config_key = 'CINEMA_DAILY'");
        jdbcTemplate.update("UPDATE refund_limit_configs SET limit_amount_vnd = 10000000, updated_by = NULL, is_active = 1 WHERE config_key = 'INCIDENT_CUMULATIVE'");
    }

    private void setLimitClock(LocalDateTime hcmDateTime) {
        Instant inst = hcmDateTime.atZone(HCM_ZONE).toInstant();
        refundLimitService.setClock(Clock.fixed(inst, HCM_ZONE));
    }

    private void authenticateThreadAs(User u) {
        BoxCinemaUserDetails details = new BoxCinemaUserDetails(u);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(details, "", details.getAuthorities()));
    }

    private <T> T runAs(User actor, java.util.function.Supplier<T> action) {
        authenticateThreadAs(actor);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private RequestPostProcessor authOf(User u) {
        BoxCinemaUserDetails details = new BoxCinemaUserDetails(u);
        UserSummaryDto summary = new UserSummaryDto(u.getUserId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole());
        RequestPostProcessor userProc = user(details);
        return request -> {
            HttpServletRequestWrapperHelper.bindSessionSummary(request, summary);
            return userProc.postProcessRequest(request);
        };
    }

    private static final class HttpServletRequestWrapperHelper {
        static void bindSessionSummary(org.springframework.mock.web.MockHttpServletRequest request, UserSummaryDto summary) {
            Objects.requireNonNull(request.getSession(true)).setAttribute("currentUser", summary);
        }
    }

    private RequestPostProcessor remoteIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private User createUser(String email, String role, Long cinemaId) {
        User u = new User("Stage2 " + role, email,
                "09" + String.format("%08d", Math.abs(email.hashCode()) % 100_000_000), "hash", role);
        u.setCinemaId(cinemaId);
        u.setAccountStatus("ACTIVE");
        u = userRepository.save(u);
        createdUserIds.add(u.getUserId());
        return u;
    }

    private Showtime createShowtime(Cinema c, LocalDateTime startAt, LocalDateTime endAt) {
        Room targetRoom = roomRepository.findAll().stream()
                .filter(r -> r.getCinema() != null && r.getCinema().getCinemaId().equals(c.getCinemaId()))
                .findFirst()
                .orElse(room1);
        Showtime st = new Showtime(movie, targetRoom, startAt, endAt, BigDecimal.valueOf(100_000));
        st.setStatus("ACTIVE");
        st = showtimeRepository.save(st);
        createdShowtimeIds.add(st.getShowtimeId());
        return st;
    }

    private Booking createPaidBooking(Showtime st, Cinema c, User owner, long amountVnd) {
        BigDecimal amt = BigDecimal.valueOf(amountVnd).setScale(2, RoundingMode.HALF_UP);
        Booking b = new Booking();
        b.setBookingCode("BOX-S2-" + (100_000 + ThreadLocalRandom.current().nextInt(900_000)));
        b.setShowtime(st);
        b.setCinema(c);
        b.setUser(owner);
        b.setOrderType("TICKET_ONLY");
        b.setGuestName(owner != null ? owner.getFullName() : "Guest S2");
        b.setGuestEmail(owner != null ? owner.getEmail() : ("guest_" + System.nanoTime() + "@box.test"));
        b.setGuestPhone("0901234567");
        b.setTicketSubtotal(amt);
        b.setFoodSubtotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        b.setSubtotal(amt);
        b.setDiscountAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        b.setServiceFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        b.setTotalAmount(amt);
        b.setBookingStatus("CONFIRMED");
        b.setConfirmedAt(LocalDateTime.now(HCM_ZONE));
        b = bookingRepository.save(b);
        createdBookingIds.add(b.getBookingId());

        BookingSeat bs = bookingSeatRepository.save(new BookingSeat(b, 1L, amt, "A1", "BOOKED"));
        Ticket t = new Ticket();
        t.setBooking(b);
        t.setBookingSeatId(bs.getBookingSeatId());
        t.setShowtimeId(st != null ? st.getShowtimeId() : null);
        t.setTicketCode("TCK-" + b.getBookingCode().replace("BOX-", "") + "-" + UUID.randomUUID().toString().substring(0, 4));
        t.setQrTokenHash(CryptoUtils.sha256(t.getTicketCode()));
        t.setPrice(amt);
        t.setGrossPrice(amt);
        t.setDiscountAllocated(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        t.setNetPaidAmount(amt);
        t.setTicketStatus("VALID");
        ticketRepository.save(t);

        Payment p = new Payment();
        p.setBooking(b);
        p.setOrderCode(Math.abs(UUID.randomUUID().getMostSignificantBits()) % 8_000_000_000L + 100_000L);
        p.setIdempotencyKey("IDEMP-S2-" + UUID.randomUUID());
        p.setPaymentProvider("PAYOS");
        p.setPaymentMethod("PAYOS_VIETQR");
        p.setAmount(amt);
        p.setCurrency("VND");
        p.setPaymentStatus("PAID");
        p.setPaidAt(LocalDateTime.now(HCM_ZONE).minusMinutes(5));
        paymentRepository.save(p);
        return b;
    }

    private Booking createPaidGuestBooking(Showtime st, Cinema c, String guestEmail, long amountVnd) {
        Booking b = createPaidBooking(st, c, null, amountVnd);
        b.setGuestEmail(guestEmail);
        return bookingRepository.save(b);
    }

    private RefundRequestDetailDto createAndSubmitRefund(Showtime st, User owner, long amountVnd, String ip) {
        Booking b = createPaidBooking(st, cinema1, owner, amountVnd);
        Ticket t = ticketRepository.findByBooking_BookingId(b.getBookingId()).get(0);
        return submitCustomerRefund(b, t.getTicketId(), owner, ip);
    }

    private RefundRequestDetailDto submitCustomerRefund(Booking b, Long ticketId, User owner, String ip) {
        PublicRefundSubmitRequestDto req = new PublicRefundSubmitRequestDto();
        req.setOrderCode(b.getBookingCode());
        req.setPurchaseEmail(owner != null ? owner.getEmail() : b.getGuestEmail());
        req.setReasonCategory("CINEMA_INCIDENT");
        req.setDescription("Stage 2 governance test refund for " + b.getBookingCode());
        req.setToBin("970422");
        req.setToAccountNumber("0987654321");
        req.setConfirmAccountNumber("0987654321");
        req.setBankAccountName("NGUYEN STAGE TWO");

        PublicRefundSubmitRequestDto.RequestedItemDto item = new PublicRefundSubmitRequestDto.RequestedItemDto();
        item.setItemKind("TICKET");
        item.setTargetId(ticketId);
        item.setQuantity(1);
        req.setItems(List.of(item));

        return refundService.submitPublicRefundRequest(req, owner != null ? owner.getUserId() : null, ip);
    }

    private String buildRefundSubmitJson(Booking b, Long ticketId, String email) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "orderCode", b.getBookingCode(),
                "purchaseEmail", email,
                "reasonCategory", "CUSTOMER_REQUEST",
                "description", "Rate limit verification request",
                "toBin", "970422",
                "toAccountNumber", "0912345678",
                "confirmAccountNumber", "0912345678",
                "bankAccountName", "NGUYEN RATE LIMIT",
                "items", List.of(Map.of("itemKind", "TICKET", "targetId", ticketId, "quantity", 1))
        ));
    }

    private RefundRequestDetailDto approveBy(Long refundId, User actor, Boolean overrideApproved, String overrideReason) {
        return runAs(actor, () -> {
            RefundRequestDetailDto detail = refundService.getRefundDetailForStaffOrManager(refundId, actor.getUserId());
            RefundReviewRequestDto dto = new RefundReviewRequestDto();
            dto.setAction("DECIDE_ITEMS");
            dto.setNote("Stage 2 review by " + actor.getRole());
            dto.setLimitOverrideApproved(overrideApproved);
            dto.setLimitOverrideReason(overrideReason);

            List<RefundReviewRequestDto.ItemDecisionDto> decs = new ArrayList<>();
            for (RefundRequestDetailDto.RefundItemDto item : detail.getItems()) {
                RefundReviewRequestDto.ItemDecisionDto d = new RefundReviewRequestDto.ItemDecisionDto();
                d.setItemId(item.getId());
                d.setDecision("APPROVED");
                d.setApprovedQuantity(item.getRequestedQuantity());
                d.setReason("Eligible");
                decs.add(d);
            }
            dto.setItemDecisions(decs);
            return refundService.reviewRefundRequest(refundId, dto, actor.getUserId());
        });
    }

    private RefundRequestDetailDto rejectAllItems(Long refundId, User actor, String reason) {
        return runAs(actor, () -> {
            RefundRequestDetailDto detail = refundService.getRefundDetailForStaffOrManager(refundId, actor.getUserId());
            RefundReviewRequestDto dto = new RefundReviewRequestDto();
            dto.setAction("DECIDE_ITEMS");
            dto.setNote(reason);

            List<RefundReviewRequestDto.ItemDecisionDto> decs = new ArrayList<>();
            for (RefundRequestDetailDto.RefundItemDto item : detail.getItems()) {
                RefundReviewRequestDto.ItemDecisionDto d = new RefundReviewRequestDto.ItemDecisionDto();
                d.setItemId(item.getId());
                d.setDecision("REJECTED");
                d.setApprovedQuantity(0);
                d.setReason(reason);
                decs.add(d);
            }
            dto.setItemDecisions(decs);
            return refundService.reviewRefundRequest(refundId, dto, actor.getUserId());
        });
    }

    private void cancelRefundAsAdmin(Long refundId, String reason) {
        runAs(admin2, () -> {
            RefundReviewRequestDto cancelDto = new RefundReviewRequestDto();
            cancelDto.setAction("CANCEL");
            cancelDto.setNote(reason);
            return refundService.reviewRefundRequest(refundId, cancelDto, admin2.getUserId());
        });
    }

    private static byte[] buildValidPngBytes(byte[] extraPayloadBeforeIend) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] sigAndIhdr = new byte[]{
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R',
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89
        };
        byte[] iend = new byte[]{
                0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        };
        out.writeBytes(sigAndIhdr);
        if (extraPayloadBeforeIend != null) {
            out.writeBytes(extraPayloadBeforeIend);
        }
        out.writeBytes(iend);
        return out.toByteArray();
    }

    private static byte[] buildValidJpegBytes() {
        return new byte[]{
                (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xD9
        };
    }
}
