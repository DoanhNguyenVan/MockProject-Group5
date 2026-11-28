package com.boxcinema.refund.service;

import com.boxcinema.common.entity.PayOsPayoutTransaction;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.PayOsPayoutTransactionRepository;
import com.boxcinema.refund.dto.BatchPayoutPreviewDto;
import com.boxcinema.refund.dto.LiveExecuteRequestDto;
import com.boxcinema.refund.dto.PayoutAccountBalanceDto;
import com.boxcinema.refund.dto.RefundRequestDetailDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the Admin-only, one-payout-at-a-time, manually confirmed live payout workflow.
 *
 * <p>This service NEVER executes a payout on its own: {@link #buildConfirmation} is read-only (provider GET
 * balance + local fee estimation) and {@link #execute} only runs when a human Admin submits the typed phrase,
 * the explicit acknowledgement, the exact amount and a single-use confirmation token.
 */
@Service
public class LivePayoutWorkflowService {

    private final RefundService refundService;
    private final PayOsPayoutService payOsPayoutService;
    private final LivePayoutGate gate;
    private final PayOsPayoutTransactionRepository payoutRepository;

    @Autowired
    public LivePayoutWorkflowService(RefundService refundService,
                                     PayOsPayoutService payOsPayoutService,
                                     LivePayoutGate gate,
                                     PayOsPayoutTransactionRepository payoutRepository) {
        this.refundService = refundService;
        this.payOsPayoutService = payOsPayoutService;
        this.gate = gate;
        this.payoutRepository = payoutRepository;
    }

    // --------------------------------------------------------------- control

    public Map<String, Object> getControlStatus() {
        Map<String, Object> out = new LinkedHashMap<>(payOsPayoutService.getPayoutPreflightStatus());
        out.putAll(gate.snapshot());
        boolean envOk = payOsPayoutService.isLivePayoutEnabled() && "LIVE".equals(payOsPayoutService.getMode());
        out.put("environmentPermitsLive", envOk);
        out.put("effectiveMode", envOk && gate.isAdminLiveEnabled() ? "LIVE" : "DISABLED");
        out.put("requiredConfirmationPhrase", LivePayoutGate.CONFIRM_PHRASE);
        out.put("batchPayoutAllowedInLive", false);
        return out;
    }

    public Map<String, Object> setMode(boolean enable, String note, Long adminUserId, String ip) {
        gate.setAdminLiveEnabled(enable, adminUserId, note, ip,
                payOsPayoutService.isLivePayoutEnabled(), "LIVE".equals(payOsPayoutService.getMode()));
        return getControlStatus();
    }

    public Map<String, Object> setMaxAmount(long amountVnd, String note, Long adminUserId, String ip) {
        gate.setMaxTestAmountVnd(amountVnd, adminUserId, note, ip);
        return getControlStatus();
    }

    // ---------------------------------------------------------- confirmation

    /** Read-only confirmation screen data. A single-use token is issued ONLY when no blocker exists. */
    public Map<String, Object> buildConfirmation(Long refundRequestId, Long adminUserId, String ip) {
        Evaluation ev = evaluate(refundRequestId, adminUserId);
        Map<String, Object> view = ev.view();
        if (ev.blockers().isEmpty()) {
            LivePayoutGate.Confirmation c = gate.issueConfirmation(
                    refundRequestId, ev.amountVnd(), adminUserId, ev.referenceId(), ip);
            view.put("confirmationToken", c.token());
            view.put("confirmationExpiresAt", c.expiresAt().toString());
        }
        return view;
    }

    // ------------------------------------------------------------- execution

    public Map<String, Object> execute(Long refundRequestId, LiveExecuteRequestDto req, Long adminUserId, String ip) {
        gate.acquireSingleFlight();
        try {
            if (req == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "LIVE_CONFIRMATION_REQUIRED", "Confirmation body required.");
            }
            if (!LivePayoutGate.CONFIRM_PHRASE.equals(req.getConfirmPhrase())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "LIVE_PHRASE_MISMATCH",
                        "Type the exact phrase '" + LivePayoutGate.CONFIRM_PHRASE + "' to confirm a real-money transfer.");
            }
            if (!Boolean.TRUE.equals(req.getAcknowledgeRealMoney())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "LIVE_ACK_REQUIRED",
                        "You must tick the explicit acknowledgement that this operation transfers real money.");
            }
            if (req.getConfirmAmountVnd() == null || req.getConfirmAmountVnd() <= 0) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "LIVE_AMOUNT_CONFIRM_REQUIRED",
                        "Re-enter the exact approved amount in VND.");
            }
            // Atomic single-use token: a duplicate click / replay fails here before any state change.
            gate.consumeConfirmation(req.getConfirmationToken(), refundRequestId, req.getConfirmAmountVnd(), adminUserId);

            Evaluation ev = evaluate(refundRequestId, adminUserId);
            if (ev.amountVnd() != req.getConfirmAmountVnd()) {
                throw new BusinessException(HttpStatus.CONFLICT, "LIVE_AMOUNT_MISMATCH",
                        "Entered amount does not equal the approved refund amount.");
            }
            if (!ev.blockers().isEmpty()) {
                throw new BusinessException(HttpStatus.CONFLICT, "LIVE_PAYOUT_BLOCKED",
                        "Live payout blocked: " + String.join("; ", ev.blockers()));
            }

            Map<String, Object> pre = new LinkedHashMap<>();
            pre.put("amountVnd", ev.amountVnd());
            pre.put("referenceId", ev.referenceId());
            pre.put("note", req.getNote());
            // Durable audit BEFORE any money can move: if this fails, nothing is dispatched.
            gate.audit(refundRequestId, "LIVE_PAYOUT_EXECUTE_REQUESTED", adminUserId, ip, pre);

            RefundRequestDetailDto detail;
            gate.grantDispatchAuthorization(refundRequestId, ev.amountVnd(), adminUserId);
            try {
                // No automatic retry anywhere: a timeout leaves the transaction UNKNOWN for reconciliation.
                detail = refundService.approveSinglePayout(refundRequestId, req.getNote(), null, null, adminUserId);
            } catch (RuntimeException ex) {
                Map<String, Object> fail = new LinkedHashMap<>();
                fail.put("referenceId", ev.referenceId());
                fail.put("error", ex.getClass().getSimpleName());
                gate.audit(refundRequestId, "LIVE_PAYOUT_EXECUTE_FAILED", adminUserId, ip, fail);
                throw ex;
            } finally {
                gate.revokeDispatchAuthorization(refundRequestId);
            }

            Map<String, Object> post = new LinkedHashMap<>();
            post.put("referenceId", ev.referenceId());
            gate.audit(refundRequestId, "LIVE_PAYOUT_EXECUTE_RETURNED", adminUserId, ip, post);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("refundRequestId", refundRequestId);
            out.put("payoutReference", ev.referenceId());
            out.put("refund", detail);
            out.put("message", "Dispatch call returned. The refund is NOT paid until the provider reports verified SUCCESS. "
                    + "If the status is PROCESSING or UNKNOWN, use Reconcile on the ORIGINAL reference; never create a second payout.");
            return out;
        } finally {
            gate.releaseSingleFlight();
        }
    }

    // ------------------------------------------------------------ evaluation

    record Evaluation(Map<String, Object> view, List<String> blockers, long amountVnd, String referenceId) {}

    Evaluation evaluate(Long refundRequestId, Long adminUserId) {
        BatchPayoutPreviewDto preview = refundService.previewBatchPayout(List.of(refundRequestId), adminUserId);
        BatchPayoutPreviewDto.BatchPreviewItemDto item = !preview.getEligibleItems().isEmpty()
                ? preview.getEligibleItems().get(0)
                : (!preview.getIneligibleItems().isEmpty() ? preview.getIneligibleItems().get(0) : null);

        List<String> blockers = new ArrayList<>();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("refundRequestId", refundRequestId);
        view.put("requiredPhrase", LivePayoutGate.CONFIRM_PHRASE);
        view.put("warning", "WARNING: this operation transfers REAL MONEY from the PayOS payout wallet to a real bank account. "
                + "It cannot be undone by BOX Cinema.");

        String mode = payOsPayoutService.getMode();
        boolean envFlag = payOsPayoutService.isLivePayoutEnabled();
        view.put("payoutMode", mode);
        view.put("environmentFlagEnabled", envFlag);
        view.put("adminLiveMode", gate.modeLabel());
        view.put("maxTestAmountVnd", gate.getMaxTestAmountVnd());

        if (!"LIVE".equals(mode)) {
            blockers.add("PAYOS_PAYOUT_MODE is " + mode + " (must be LIVE)");
        }
        if (!envFlag) {
            blockers.add("Environment flag LIVE_PAYOUT_ENABLED is false");
        }
        if (!gate.isAdminLiveEnabled()) {
            blockers.add("Admin live control is DISABLED");
        }
        if (gate.getMaxTestAmountVnd() == null) {
            blockers.add("Maximum test amount has not been configured by the Admin");
        }

        long amount = 0L;
        String reference = null;
        if (item == null) {
            blockers.add("Refund request not found");
        } else {
            amount = item.getAmountVnd();
            view.put("requestCode", item.getRequestCode());
            view.put("approvedAmountVnd", amount);
            view.put("destinationBankName", item.getToBankName());
            view.put("destinationBin", item.getToBin());
            view.put("destinationAccountMasked", item.getToAccountNumberMasked());
            view.put("accountHolderName", item.getAccountHolderName());
            // PayOS Payout v1 exposes no destination-name lookup for single payouts; never claim verification we do not have.
            view.put("accountHolderNameVerifiedByProvider", false);
            view.put("estimatedFeeVnd", item.getEstimatedFeeVnd());
            view.put("feeClassification", item.getFeeClassification());
            view.put("guaranteedFullReceipt", item.isGuaranteedFullReceipt());
            view.put("expectedRecipientAmountVnd", item.getNetRecipientVnd());
            view.put("transferDescription", item.getTransferDescription());
            view.put("refundMethod", item.getRefundMethod());
            view.put("requestStatus", item.getRequestStatus());
            view.put("workflowStatus", item.getWorkflowStatus());

            if (!item.isEligible()) {
                for (String r : item.getIneligibleReasons()) {
                    blockers.add("Not eligible: " + r);
                }
                if (item.getIneligibleReasons().isEmpty()) {
                    blockers.add("Refund is not eligible for payout");
                }
            }
            if (amount <= 0) {
                blockers.add("Approved amount is not a positive VND value");
            }
            Long max = gate.getMaxTestAmountVnd();
            if (max != null && amount > max) {
                blockers.add("Amount " + amount + " exceeds the Admin-configured maximum test amount " + max);
            }
            if (isBlank(item.getToBin()) || isBlank(item.getToAccountNumberMasked())) {
                blockers.add("Destination bank/account is missing");
            }
            if (isBlank(item.getAccountHolderName())) {
                blockers.add("Account-holder name is missing");
            }
            if (item.isFeeBlocked() || !item.isGuaranteedFullReceipt()
                    || "UNKNOWN".equals(item.getFeeClassification())
                    || "UNVERIFIED".equals(item.getFeeClassification())
                    || "RECIPIENT_DEDUCTED".equals(item.getFeeClassification())) {
                blockers.add("Fee behavior is " + item.getFeeClassification()
                        + " (UNKNOWN/UNVERIFIED/recipient-deducted fee is blocked; customer must receive the full amount)");
            } else if (item.getNetRecipientVnd() != amount) {
                blockers.add("Expected recipient amount differs from the approved amount");
            }

            List<PayOsPayoutTransaction> prior = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(refundRequestId);
            int next = prior.isEmpty() ? 1 : prior.get(0).getAttemptCount() + 1;
            reference = "RFD-" + refundRequestId + "-A" + next;
            view.put("payoutReference", reference);
            for (PayOsPayoutTransaction t : prior) {
                if (t.getActiveRequestLock() != null) {
                    blockers.add("A payout (" + t.getReferenceId() + ", status " + t.getStatus()
                            + ") is already active or UNKNOWN: reconcile that original reference; do not create another");
                    break;
                }
            }

            // Source wallet (read-only provider GET). Insufficient or unverifiable balance blocks.
            PayoutAccountBalanceDto bal = null;
            try {
                bal = payOsPayoutService.fetchPayoutAccountBalance();
            } catch (RuntimeException ex) {
                blockers.add("Source wallet balance lookup failed (" + ex.getClass().getSimpleName() + ")");
            }
            if (bal != null) {
                view.put("sourceWalletVerified", bal.isLiveVerified());
                view.put("sourceWalletAccountMasked", bal.getAccountNumberMasked());
                view.put("sourceWalletCurrency", bal.getCurrency());
                view.put("sourceWalletBalance", bal.getBalance());
                BigDecimal balance = parseAmount(bal.getBalance());
                long totalDebit = item.getTotalDebitVnd() > 0 ? item.getTotalDebitVnd() : amount + item.getEstimatedFeeVnd();
                view.put("requiredSourceDebitVnd", totalDebit);
                if (!bal.isLiveVerified() || balance == null) {
                    blockers.add("Source wallet balance is not provider-verified");
                } else if (balance.compareTo(BigDecimal.valueOf(totalDebit)) < 0) {
                    blockers.add("Insufficient source wallet balance for amount plus fee");
                }
            }
        }
        view.put("blockers", blockers);
        view.put("readyToExecute", blockers.isEmpty());
        return new Evaluation(view, blockers, amount, reference);
    }

    static BigDecimal parseAmount(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[^0-9.]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned).setScale(0, RoundingMode.DOWN);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
