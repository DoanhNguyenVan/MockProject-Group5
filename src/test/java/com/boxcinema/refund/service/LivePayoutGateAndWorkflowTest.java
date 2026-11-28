package com.boxcinema.refund.service;

import com.boxcinema.common.entity.PayOsPayoutTransaction;
import com.boxcinema.common.entity.RefundAuditLog;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.PayOsPayoutTransactionRepository;
import com.boxcinema.common.repository.RefundAuditLogRepository;
import com.boxcinema.refund.dto.BatchPayoutPreviewDto;
import com.boxcinema.refund.dto.LiveExecuteRequestDto;
import com.boxcinema.refund.dto.PayoutAccountBalanceDto;
import com.boxcinema.refund.dto.RefundRequestDetailDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * MOCK-ONLY unit tests for the Admin live payout gate and workflow. No database, no network, no PayOS call.
 * These results prove application logic only; they are NOT provider verification.
 */
class LivePayoutGateAndWorkflowTest {

    private static final Long ADMIN = 7001L;
    private static final Long REFUND = 55L;

    private RefundAuditLogRepository auditRepo;
    private LivePayoutGate gate;
    private RefundService refundService;
    private PayOsPayoutService payoutService;
    private PayOsPayoutTransactionRepository txRepo;
    private LivePayoutWorkflowService workflow;
    private MutableClock clock;
    private final List<RefundAuditLog> audits = new ArrayList<>();

    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-28T00:00:00Z");
        void advance(Duration d) { now = now.plus(d); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    @BeforeEach
    void setUp() {
        auditRepo = mock(RefundAuditLogRepository.class);
        when(auditRepo.save(any(RefundAuditLog.class))).thenAnswer(inv -> {
            audits.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        gate = new LivePayoutGate(auditRepo);
        clock = new MutableClock();
        gate.setClock(clock);
        refundService = mock(RefundService.class);
        payoutService = mock(PayOsPayoutService.class);
        txRepo = mock(PayOsPayoutTransactionRepository.class);
        when(payoutService.getMode()).thenReturn("LIVE");
        when(payoutService.isLivePayoutEnabled()).thenReturn(true);
        when(payoutService.getPayoutPreflightStatus()).thenReturn(new java.util.LinkedHashMap<>());
        when(txRepo.findByRefundRequest_IdOrderByAttemptCountDesc(anyLong())).thenReturn(List.of());
        workflow = new LivePayoutWorkflowService(refundService, payoutService, gate, txRepo);
    }

    // ------------------------------------------------------------ gate state

    @Test
    void gateStartsDisabledWithNoMaxAndNoAuthorization() {
        assertFalse(gate.isAdminLiveEnabled());
        assertEquals("DISABLED", gate.modeLabel());
        assertNull(gate.getMaxTestAmountVnd());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> gate.consumeDispatchAuthorizationOrThrow(REFUND, 10_000));
        assertEquals("LIVE_ADMIN_DISABLED", ex.getErrorCode());
    }

    @Test
    void enablingRequiresEnvironmentFlagAndLiveModeAndWritesNoStateOrAuditOtherwise() {
        BusinessException a = assertThrows(BusinessException.class,
                () -> gate.setAdminLiveEnabled(true, ADMIN, "x", "127.0.0.1", false, true));
        assertEquals("LIVE_ENV_FLAG_OFF", a.getErrorCode());
        BusinessException b = assertThrows(BusinessException.class,
                () -> gate.setAdminLiveEnabled(true, ADMIN, "x", "127.0.0.1", true, false));
        assertEquals("LIVE_MODE_NOT_CONFIGURED", b.getErrorCode());
        assertFalse(gate.isAdminLiveEnabled());
        assertTrue(audits.isEmpty());
    }

    @Test
    void modeChangeIsAuditedAndAuditFailureKeepsGateDisabled() {
        gate.setAdminLiveEnabled(true, ADMIN, "first live test", "10.0.0.1", true, true);
        assertTrue(gate.isAdminLiveEnabled());
        assertEquals(1, audits.size());
        assertEquals("LIVE_PAYOUT_MODE_ENABLED", audits.get(0).getEventType());
        assertEquals(ADMIN, audits.get(0).getActorUserId());
        gate.setAdminLiveEnabled(false, ADMIN, null, "10.0.0.1", true, true);
        assertEquals("LIVE_PAYOUT_MODE_DISABLED", audits.get(1).getEventType());

        when(auditRepo.save(any(RefundAuditLog.class))).thenThrow(new IllegalStateException("db down"));
        assertThrows(IllegalStateException.class,
                () -> gate.setAdminLiveEnabled(true, ADMIN, null, null, true, true));
        assertFalse(gate.isAdminLiveEnabled(), "gate must not flip when the audit record cannot be written");
    }

    @Test
    void auditRecordsNeverContainSecrets() {
        gate.setAdminLiveEnabled(true, ADMIN, "note", "10.0.0.1", true, true);
        gate.setMaxTestAmountVnd(10_000, ADMIN, "limit", "10.0.0.1");
        for (RefundAuditLog l : audits) {
            String d = String.valueOf(l.getDetailsJson()).toLowerCase();
            assertFalse(d.contains("api-key") || d.contains("checksum") || d.contains("secret") || d.contains("x-signature"));
        }
    }

    @Test
    void maxTestAmountMustBePositiveAndIsAdminSupplied() {
        assertThrows(BusinessException.class, () -> gate.setMaxTestAmountVnd(0, ADMIN, null, null));
        assertThrows(BusinessException.class, () -> gate.setMaxTestAmountVnd(-5, ADMIN, null, null));
        gate.setMaxTestAmountVnd(25_000, ADMIN, "n", null);
        assertEquals(25_000L, gate.getMaxTestAmountVnd());
        assertEquals("LIVE_PAYOUT_MAX_AMOUNT_SET", audits.get(audits.size() - 1).getEventType());
    }

    @Test
    void dispatchAuthorizationIsSingleUseBoundToRefundAndAmountAndExpires() {
        gate.setAdminLiveEnabled(true, ADMIN, null, null, true, true);
        // no max yet
        gate.grantDispatchAuthorization(REFUND, 10_000, ADMIN);
        assertEquals("LIVE_MAX_AMOUNT_NOT_SET", assertThrows(BusinessException.class,
                () -> gate.consumeDispatchAuthorizationOrThrow(REFUND, 10_000)).getErrorCode());

        gate.setMaxTestAmountVnd(10_000, ADMIN, null, null);
        gate.grantDispatchAuthorization(REFUND, 10_000, ADMIN);
        assertEquals("LIVE_AMOUNT_EXCEEDS_MAX", assertThrows(BusinessException.class,
                () -> gate.consumeDispatchAuthorizationOrThrow(REFUND, 10_001)).getErrorCode());

        gate.grantDispatchAuthorization(REFUND, 10_000, ADMIN);
        gate.consumeDispatchAuthorizationOrThrow(REFUND, 10_000);
        assertEquals("LIVE_ADMIN_AUTHORIZATION_REQUIRED", assertThrows(BusinessException.class,
                () -> gate.consumeDispatchAuthorizationOrThrow(REFUND, 10_000)).getErrorCode(), "single use");

        gate.grantDispatchAuthorization(REFUND, 10_000, ADMIN);
        assertEquals("LIVE_ADMIN_AUTHORIZATION_REQUIRED", assertThrows(BusinessException.class,
                () -> gate.consumeDispatchAuthorizationOrThrow(999L, 10_000)).getErrorCode(), "other refund");
        gate.grantDispatchAuthorization(REFUND, 9_000, ADMIN);
        assertEquals("LIVE_ADMIN_AUTHORIZATION_REQUIRED", assertThrows(BusinessException.class,
                () -> gate.consumeDispatchAuthorizationOrThrow(REFUND, 10_000)).getErrorCode(), "other amount");

        gate.grantDispatchAuthorization(REFUND, 10_000, ADMIN);
        clock.advance(Duration.ofMinutes(3));
        assertEquals("LIVE_ADMIN_AUTHORIZATION_REQUIRED", assertThrows(BusinessException.class,
                () -> gate.consumeDispatchAuthorizationOrThrow(REFUND, 10_000)).getErrorCode(), "expired");
    }

    @Test
    void disablingClearsOutstandingAuthorizationsAndConfirmations() {
        gate.setAdminLiveEnabled(true, ADMIN, null, null, true, true);
        gate.setMaxTestAmountVnd(10_000, ADMIN, null, null);
        gate.grantDispatchAuthorization(REFUND, 10_000, ADMIN);
        LivePayoutGate.Confirmation c = gate.issueConfirmation(REFUND, 10_000, ADMIN, "RFD-55-A1", null);
        gate.setAdminLiveEnabled(false, ADMIN, null, null, true, true);
        assertFalse(gate.hasAnyDispatchAuthorization(REFUND));
        assertThrows(BusinessException.class, () -> gate.consumeConfirmation(c.token(), REFUND, 10_000, ADMIN));
    }

    @Test
    void confirmationTokenIsSingleUseBoundAndExpiring() {
        LivePayoutGate.Confirmation c = gate.issueConfirmation(REFUND, 10_000, ADMIN, "RFD-55-A1", "1.1.1.1");
        assertEquals("LIVE_PAYOUT_CONFIRMATION_ISSUED", audits.get(audits.size() - 1).getEventType());
        gate.consumeConfirmation(c.token(), REFUND, 10_000, ADMIN);
        assertEquals("LIVE_CONFIRMATION_INVALID", assertThrows(BusinessException.class,
                () -> gate.consumeConfirmation(c.token(), REFUND, 10_000, ADMIN)).getErrorCode(), "replay");

        LivePayoutGate.Confirmation c2 = gate.issueConfirmation(REFUND, 10_000, ADMIN, "RFD-55-A1", null);
        assertThrows(BusinessException.class, () -> gate.consumeConfirmation(c2.token(), REFUND, 10_001, ADMIN));
        LivePayoutGate.Confirmation c3 = gate.issueConfirmation(REFUND, 10_000, ADMIN, "RFD-55-A1", null);
        assertThrows(BusinessException.class, () -> gate.consumeConfirmation(c3.token(), REFUND, 10_000, 9999L), "other admin");
        LivePayoutGate.Confirmation c4 = gate.issueConfirmation(REFUND, 10_000, ADMIN, "RFD-55-A1", null);
        clock.advance(Duration.ofMinutes(6));
        assertThrows(BusinessException.class, () -> gate.consumeConfirmation(c4.token(), REFUND, 10_000, ADMIN), "expired");

        LivePayoutGate.Confirmation old = gate.issueConfirmation(REFUND, 10_000, ADMIN, "RFD-55-A1", null);
        LivePayoutGate.Confirmation fresh = gate.issueConfirmation(REFUND, 10_000, ADMIN, "RFD-55-A1", null);
        assertThrows(BusinessException.class, () -> gate.consumeConfirmation(old.token(), REFUND, 10_000, ADMIN), "superseded");
        gate.consumeConfirmation(fresh.token(), REFUND, 10_000, ADMIN);
    }

    @Test
    void onlyOneLivePayoutMayBeInFlight() throws Exception {
        gate.acquireSingleFlight();
        AtomicReference<String> code = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                gate.acquireSingleFlight();
            } catch (BusinessException ex) {
                code.set(ex.getErrorCode());
            }
        });
        t.start();
        t.join();
        assertEquals("LIVE_PAYOUT_IN_PROGRESS", code.get());
        gate.releaseSingleFlight();
        gate.acquireSingleFlight();
        gate.releaseSingleFlight();
    }

    // --------------------------------------------------------------- workflow

    private void enableGate(long max) {
        gate.setAdminLiveEnabled(true, ADMIN, null, null, true, true);
        gate.setMaxTestAmountVnd(max, ADMIN, null, null);
    }

    private BatchPayoutPreviewDto.BatchPreviewItemDto item(long amount) {
        BatchPayoutPreviewDto.BatchPreviewItemDto i = new BatchPayoutPreviewDto.BatchPreviewItemDto();
        i.setRefundRequestId(REFUND);
        i.setRequestCode("RF-55");
        i.setToBankName("Vietcombank");
        i.setToBin("970436");
        i.setToAccountNumberMasked("******7890");
        i.setAccountHolderName("NGUYEN VAN A");
        i.setTransferDescription("BOX REFUND 55");
        i.setAmountVnd(amount);
        i.setFeeClassification("SENDER_PAYS");
        i.setEstimatedFeeVnd(0);
        i.setNetRecipientVnd(amount);
        i.setTotalDebitVnd(amount);
        i.setGuaranteedFullReceipt(true);
        i.setFeeBlocked(false);
        i.setEligible(true);
        return i;
    }

    private void stubPreview(BatchPayoutPreviewDto.BatchPreviewItemDto it) {
        BatchPayoutPreviewDto p = new BatchPayoutPreviewDto();
        (it.isEligible() ? p.getEligibleItems() : p.getIneligibleItems()).add(it);
        when(refundService.previewBatchPayout(any(), any())).thenReturn(p);
    }

    private void stubBalance(String balance, boolean verified) {
        PayoutAccountBalanceDto b = new PayoutAccountBalanceDto();
        b.setLiveVerified(verified);
        b.setBalance(balance);
        b.setCurrency("VND");
        b.setAccountNumberMasked("****1234");
        when(payoutService.fetchPayoutAccountBalance()).thenReturn(b);
    }

    private LiveExecuteRequestDto request(String token, String phrase, Long amount, boolean ack) {
        LiveExecuteRequestDto r = new LiveExecuteRequestDto();
        r.setConfirmationToken(token);
        r.setConfirmPhrase(phrase);
        r.setConfirmAmountVnd(amount);
        r.setAcknowledgeRealMoney(ack);
        return r;
    }

    @Test
    void confirmationScreenContainsAllRequiredFieldsAndNeverFullAccountNumber() {
        enableGate(50_000);
        stubPreview(item(10_000));
        stubBalance("1,000,000", true);
        Map<String, Object> v = workflow.buildConfirmation(REFUND, ADMIN, "1.1.1.1");
        assertEquals(Boolean.TRUE, v.get("readyToExecute"));
        assertNotNull(v.get("confirmationToken"));
        for (String k : List.of("refundRequestId", "approvedAmountVnd", "destinationBankName", "destinationAccountMasked",
                "accountHolderName", "accountHolderNameVerifiedByProvider", "estimatedFeeVnd", "expectedRecipientAmountVnd",
                "sourceWalletBalance", "payoutReference", "warning", "requiredPhrase")) {
            assertTrue(v.containsKey(k), "missing " + k);
        }
        assertEquals("RFD-55-A1", v.get("payoutReference"));
        assertEquals(Boolean.FALSE, v.get("accountHolderNameVerifiedByProvider"));
        assertTrue(String.valueOf(v.get("warning")).contains("REAL MONEY"));
        assertEquals("******7890", v.get("destinationAccountMasked"));
    }

    @Test
    void confirmationIsBlockedAndIssuesNoTokenWhenGateDisabledOrNoMax() {
        stubPreview(item(10_000));
        stubBalance("1000000", true);
        Map<String, Object> v = workflow.buildConfirmation(REFUND, ADMIN, null);
        assertEquals(Boolean.FALSE, v.get("readyToExecute"));
        assertFalse(v.containsKey("confirmationToken"));
        String b = String.valueOf(v.get("blockers"));
        assertTrue(b.contains("Admin live control is DISABLED"));
        assertTrue(b.contains("Maximum test amount"));
    }

    @Test
    void insufficientOrUnverifiedBalanceBlocks() {
        enableGate(50_000);
        stubPreview(item(10_000));
        stubBalance("9,999", true);
        assertTrue(String.valueOf(workflow.buildConfirmation(REFUND, ADMIN, null).get("blockers")).contains("Insufficient source wallet"));
        stubBalance("1000000", false);
        assertTrue(String.valueOf(workflow.buildConfirmation(REFUND, ADMIN, null).get("blockers")).contains("not provider-verified"));
        when(payoutService.fetchPayoutAccountBalance()).thenThrow(new IllegalStateException("x"));
        assertEquals(Boolean.FALSE, workflow.buildConfirmation(REFUND, ADMIN, null).get("readyToExecute"));
    }

    @Test
    void unknownOrUnverifiedFeeBehaviorBlocksAndCustomerMustReceiveFullAmount() {
        enableGate(50_000);
        stubBalance("1000000", true);
        for (String cls : List.of("UNKNOWN", "UNVERIFIED", "RECIPIENT_DEDUCTED")) {
            BatchPayoutPreviewDto.BatchPreviewItemDto it = item(10_000);
            it.setFeeClassification(cls);
            it.setGuaranteedFullReceipt(false);
            it.setFeeBlocked(true);
            stubPreview(it);
            Map<String, Object> v = workflow.buildConfirmation(REFUND, ADMIN, null);
            assertEquals(Boolean.FALSE, v.get("readyToExecute"), cls);
            assertFalse(v.containsKey("confirmationToken"), cls);
        }
        BatchPayoutPreviewDto.BatchPreviewItemDto short1 = item(10_000);
        short1.setNetRecipientVnd(9_500);
        stubPreview(short1);
        assertTrue(String.valueOf(workflow.buildConfirmation(REFUND, ADMIN, null).get("blockers")).contains("differs"));
    }

    @Test
    void amountAboveAdminMaximumBlocks() {
        enableGate(5_000);
        stubPreview(item(10_000));
        stubBalance("1000000", true);
        assertTrue(String.valueOf(workflow.buildConfirmation(REFUND, ADMIN, null).get("blockers")).contains("exceeds"));
    }

    @Test
    void existingActiveOrUnknownPayoutBlocksNewPayoutAndNeverCreatesSecondOne() {
        enableGate(50_000);
        stubPreview(item(10_000));
        stubBalance("1000000", true);
        PayOsPayoutTransaction t = new PayOsPayoutTransaction();
        t.setReferenceId("RFD-55-A1");
        t.setStatus("PROCESSING");
        t.setAttemptCount(1);
        t.setActiveRequestLock(REFUND);
        when(txRepo.findByRefundRequest_IdOrderByAttemptCountDesc(REFUND)).thenReturn(List.of(t));
        Map<String, Object> v = workflow.buildConfirmation(REFUND, ADMIN, null);
        assertEquals("RFD-55-A2", v.get("payoutReference"));
        assertEquals(Boolean.FALSE, v.get("readyToExecute"));
        assertTrue(String.valueOf(v.get("blockers")).contains("reconcile that original reference"));
        verify(refundService, never()).approveSinglePayout(any(), any(), any(), any(), any());
    }

    @Test
    void executeRejectsWrongPhraseMissingAckAndWrongAmountWithoutDispatchOrTokenBurn() {
        enableGate(50_000);
        stubPreview(item(10_000));
        stubBalance("1000000", true);
        String token = (String) workflow.buildConfirmation(REFUND, ADMIN, null).get("confirmationToken");

        assertEquals("LIVE_PHRASE_MISMATCH", assertThrows(BusinessException.class,
                () -> workflow.execute(REFUND, request(token, "confirm live payout", 10_000L, true), ADMIN, null)).getErrorCode());
        assertEquals("LIVE_ACK_REQUIRED", assertThrows(BusinessException.class,
                () -> workflow.execute(REFUND, request(token, LivePayoutGate.CONFIRM_PHRASE, 10_000L, false), ADMIN, null)).getErrorCode());
        assertEquals("LIVE_AMOUNT_CONFIRM_REQUIRED", assertThrows(BusinessException.class,
                () -> workflow.execute(REFUND, request(token, LivePayoutGate.CONFIRM_PHRASE, null, true), ADMIN, null)).getErrorCode());
        assertEquals("LIVE_CONFIRMATION_INVALID", assertThrows(BusinessException.class,
                () -> workflow.execute(REFUND, request(token, LivePayoutGate.CONFIRM_PHRASE, 9_999L, true), ADMIN, null)).getErrorCode());
        verify(refundService, never()).approveSinglePayout(any(), any(), any(), any(), any());
    }

    @Test
    void executeDispatchesExactlyOnceHoldsAuthorizationOnlyDuringCallAndRejectsDuplicateClick() {
        enableGate(50_000);
        stubPreview(item(10_000));
        stubBalance("1000000", true);
        String token = (String) workflow.buildConfirmation(REFUND, ADMIN, null).get("confirmationToken");

        AtomicBoolean authorizedDuringCall = new AtomicBoolean(false);
        when(refundService.approveSinglePayout(eq(REFUND), any(), any(), any(), eq(ADMIN))).thenAnswer(inv -> {
            authorizedDuringCall.set(gate.hasValidDispatchAuthorization(REFUND, 10_000));
            return new RefundRequestDetailDto();
        });

        LiveExecuteRequestDto req = request(token, LivePayoutGate.CONFIRM_PHRASE, 10_000L, true);
        Map<String, Object> out = workflow.execute(REFUND, req, ADMIN, "9.9.9.9");
        assertTrue(authorizedDuringCall.get());
        assertFalse(gate.hasAnyDispatchAuthorization(REFUND), "authorization must be revoked after the call");
        assertEquals("RFD-55-A1", out.get("payoutReference"));
        assertTrue(String.valueOf(out.get("message")).contains("NOT paid until"));

        BusinessException dup = assertThrows(BusinessException.class, () -> workflow.execute(REFUND, req, ADMIN, "9.9.9.9"));
        assertEquals("LIVE_CONFIRMATION_INVALID", dup.getErrorCode());
        verify(refundService, times(1)).approveSinglePayout(any(), any(), any(), any(), any());

        List<String> events = audits.stream().map(RefundAuditLog::getEventType).toList();
        assertTrue(events.contains("LIVE_PAYOUT_EXECUTE_REQUESTED"));
        assertTrue(events.contains("LIVE_PAYOUT_EXECUTE_RETURNED"));
    }

    @Test
    void executeReEvaluatesBalanceAndBlocksWithoutDispatchEvenWithValidToken() {
        enableGate(50_000);
        stubPreview(item(10_000));
        stubBalance("1000000", true);
        String token = (String) workflow.buildConfirmation(REFUND, ADMIN, null).get("confirmationToken");
        stubBalance("100", true); // wallet drained between review and confirm
        BusinessException ex = assertThrows(BusinessException.class,
                () -> workflow.execute(REFUND, request(token, LivePayoutGate.CONFIRM_PHRASE, 10_000L, true), ADMIN, null));
        assertEquals("LIVE_PAYOUT_BLOCKED", ex.getErrorCode());
        verify(refundService, never()).approveSinglePayout(any(), any(), any(), any(), any());
    }

    @Test
    void dispatchFailureRevokesAuthorizationAuditsFailureAndDoesNotRetry() {
        enableGate(50_000);
        stubPreview(item(10_000));
        stubBalance("1000000", true);
        String token = (String) workflow.buildConfirmation(REFUND, ADMIN, null).get("confirmationToken");
        when(refundService.approveSinglePayout(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        assertThrows(IllegalStateException.class,
                () -> workflow.execute(REFUND, request(token, LivePayoutGate.CONFIRM_PHRASE, 10_000L, true), ADMIN, null));
        assertFalse(gate.hasAnyDispatchAuthorization(REFUND));
        verify(refundService, times(1)).approveSinglePayout(any(), any(), any(), any(), any());
        ArgumentCaptor<RefundAuditLog> cap = ArgumentCaptor.forClass(RefundAuditLog.class);
        verify(auditRepo, atLeastOnce()).save(cap.capture());
        assertTrue(cap.getAllValues().stream().anyMatch(a -> "LIVE_PAYOUT_EXECUTE_FAILED".equals(a.getEventType())));
    }

    @Test
    void executeFailsClosedIfAuditCannotBeWrittenBeforeDispatch() {
        enableGate(50_000);
        stubPreview(item(10_000));
        stubBalance("1000000", true);
        String token = (String) workflow.buildConfirmation(REFUND, ADMIN, null).get("confirmationToken");
        when(auditRepo.save(any(RefundAuditLog.class))).thenThrow(new IllegalStateException("db down"));
        assertThrows(IllegalStateException.class,
                () -> workflow.execute(REFUND, request(token, LivePayoutGate.CONFIRM_PHRASE, 10_000L, true), ADMIN, null));
        verify(refundService, never()).approveSinglePayout(any(), any(), any(), any(), any());
    }

    @Test
    void parseAmountHandlesFormattedBalances() {
        assertEquals(1_000_000L, LivePayoutWorkflowService.parseAmount("1,000,000").longValue());
        assertEquals(12345L, LivePayoutWorkflowService.parseAmount("12345.60").longValue());
        assertNull(LivePayoutWorkflowService.parseAmount(null));
        assertNull(LivePayoutWorkflowService.parseAmount("n/a"));
    }
}
