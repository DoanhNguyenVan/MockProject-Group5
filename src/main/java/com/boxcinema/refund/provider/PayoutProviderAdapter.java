package com.boxcinema.refund.provider;

import com.boxcinema.common.entity.Payment;
import com.boxcinema.common.entity.RefundRequest;

import java.util.List;

/**
 * Provider abstraction for refund disbursement (original payment reversal check,
 * transfer fee assessment, credit estimation, payout submission, and status reconciliation).
 */
public interface PayoutProviderAdapter {

    String getProviderName();

    /**
     * Evaluates whether the original payment channel supports direct automated reversal.
     * PayOS Collection VietQR does NOT support direct pay-in reversal -> returns false.
     */
    boolean supportsOriginalPaymentReversal(Payment originalPayment);

    /**
     * Human-readable audit rationale explaining why fallback to BANK_PAYOUT was chosen.
     */
    String getOriginalPaymentFallbackReason(Payment originalPayment);

    /**
     * Assesses transfer fee behavior before payout submission.
     * Must return guaranteedFullReceipt=true ONLY when 100% receipt of approvedAmountVnd is confirmed.
     */
    PayoutFeeAssessment assessTransferFee(RefundRequest request, long approvedAmountVnd, boolean liveMode);

    /**
     * Calls provider credit/fee estimation endpoint (PayOS POST /v1/payouts/estimate-credit).
     * Never calls external network in MOCK mode unless a test transport is explicitly installed.
     */
    Long estimateCredit(
            String referenceId,
            long amountVnd,
            String description,
            String toBin,
            String toAccountNumberPlain,
            List<String> category
    );

    PayoutExecutionResult submitPayout(PayoutExecutionCommand command);

    PayoutReconciliationResult fetchPayoutStatus(String providerPayoutId, String referenceId);
}
