package com.boxcinema.refund.provider;

import com.boxcinema.common.enums.PayoutFeeClassification;

/**
 * Immutable evaluation of transfer fee behavior for a payout.
 * Enforces the BOX Cinema policy: customers MUST receive 100% of approvedRefundVnd.
 */
public record PayoutFeeAssessment(
        String providerName,
        PayoutFeeClassification classification,
        long approvedRefundVnd,
        long estimatedFeeVnd,
        long netRecipientVnd,
        long totalDebitVnd,
        boolean guaranteedFullReceipt,
        boolean blocked,
        String reason
) {
    public static PayoutFeeAssessment senderPays(String providerName, long approvedRefundVnd, long estimatedFeeVnd, String reason) {
        long safeFee = Math.max(0L, estimatedFeeVnd);
        return new PayoutFeeAssessment(
                normalizeProvider(providerName),
                PayoutFeeClassification.SENDER_PAYS,
                approvedRefundVnd,
                safeFee,
                approvedRefundVnd,
                approvedRefundVnd + safeFee,
                true,
                false,
                reason != null ? reason : "Sender bears all transfer fees; recipient receives 100% of approved refund."
        );
    }

    public static PayoutFeeAssessment noFee(String providerName, long approvedRefundVnd, String reason) {
        return new PayoutFeeAssessment(
                normalizeProvider(providerName),
                PayoutFeeClassification.NO_FEE,
                approvedRefundVnd,
                0L,
                approvedRefundVnd,
                approvedRefundVnd,
                true,
                false,
                reason != null ? reason : "Zero transfer fee; recipient receives 100% of approved refund."
        );
    }

    public static PayoutFeeAssessment recipientDeducted(String providerName, long approvedRefundVnd, long estimatedFeeVnd, String reason) {
        long safeFee = Math.max(0L, estimatedFeeVnd);
        long net = Math.max(0L, approvedRefundVnd - safeFee);
        return new PayoutFeeAssessment(
                normalizeProvider(providerName),
                PayoutFeeClassification.RECIPIENT_DEDUCTED,
                approvedRefundVnd,
                safeFee,
                net,
                approvedRefundVnd,
                false,
                true,
                reason != null ? reason : "Provider deducts transfer fee from recipient; violates 100% customer refund guarantee."
        );
    }

    public static PayoutFeeAssessment blocked(String providerName, PayoutFeeClassification classification, long approvedRefundVnd, String reason) {
        PayoutFeeClassification effective = (classification == null || classification.allowsGuaranteedFullReceipt())
                ? PayoutFeeClassification.UNKNOWN
                : classification;
        return new PayoutFeeAssessment(
                normalizeProvider(providerName),
                effective,
                approvedRefundVnd,
                0L,
                0L,
                approvedRefundVnd,
                false,
                true,
                (reason != null && !reason.isBlank()) ? reason : ("Payout blocked because fee policy is " + effective.name() + ".")
        );
    }

    /**
     * Defensively validates any raw assessment returned by a provider/supplier.
     * Catches null/missing info, negative fees, non-full recipient net amounts, or inconsistent math.
     */
    public static PayoutFeeAssessment validateDefensively(String defaultProviderName, long expectedApprovedVnd, PayoutFeeAssessment raw) {
        String provider = normalizeProvider(raw != null && raw.providerName() != null ? raw.providerName() : defaultProviderName);
        if (expectedApprovedVnd <= 0L) {
            return blocked(provider, PayoutFeeClassification.UNKNOWN, Math.max(0L, expectedApprovedVnd),
                    "Invalid approved refund amount for fee assessment.");
        }
        if (raw == null) {
            return blocked(provider, PayoutFeeClassification.UNKNOWN, expectedApprovedVnd,
                    "Missing fee assessment response from payout provider.");
        }
        if (raw.classification() == null) {
            return blocked(provider, PayoutFeeClassification.UNKNOWN, expectedApprovedVnd,
                    "Missing fee classification from payout provider.");
        }
        if (raw.approvedRefundVnd() != expectedApprovedVnd) {
            return blocked(provider, PayoutFeeClassification.UNKNOWN, expectedApprovedVnd,
                    "Inconsistent fee assessment: approvedRefundVnd (" + raw.approvedRefundVnd()
                            + ") does not match expected refund amount (" + expectedApprovedVnd + ").");
        }
        if (raw.estimatedFeeVnd() < 0L) {
            return blocked(provider, PayoutFeeClassification.UNKNOWN, expectedApprovedVnd,
                    "Inconsistent fee assessment: estimatedFeeVnd cannot be negative.");
        }

        PayoutFeeClassification cls = raw.classification();
        if (cls == PayoutFeeClassification.RECIPIENT_DEDUCTED) {
            long net = raw.netRecipientVnd() >= 0L ? raw.netRecipientVnd() : Math.max(0L, expectedApprovedVnd - raw.estimatedFeeVnd());
            String rsn = (raw.reason() != null && !raw.reason().isBlank())
                    ? raw.reason()
                    : "Transfer fee is deducted from recipient; blocked to protect 100% customer refund guarantee.";
            return new PayoutFeeAssessment(
                    provider,
                    PayoutFeeClassification.RECIPIENT_DEDUCTED,
                    expectedApprovedVnd,
                    raw.estimatedFeeVnd(),
                    net,
                    Math.max(expectedApprovedVnd, raw.totalDebitVnd()),
                    false,
                    true,
                    rsn
            );
        }

        if (cls == PayoutFeeClassification.UNKNOWN || cls == PayoutFeeClassification.UNVERIFIED) {
            String rsn = (raw.reason() != null && !raw.reason().isBlank())
                    ? raw.reason()
                    : ("Transfer fee bearer is " + cls.name() + "; payout blocked until 100% customer receipt is verified.");
            return new PayoutFeeAssessment(
                    provider,
                    cls,
                    expectedApprovedVnd,
                    raw.estimatedFeeVnd(),
                    raw.netRecipientVnd(),
                    Math.max(expectedApprovedVnd, raw.totalDebitVnd()),
                    false,
                    true,
                    rsn
            );
        }

        // SENDER_PAYS or NO_FEE: strictly verify 100% receipt and debit consistency
        if (cls == PayoutFeeClassification.NO_FEE && raw.estimatedFeeVnd() != 0L) {
            return blocked(provider, PayoutFeeClassification.UNKNOWN, expectedApprovedVnd,
                    "Inconsistent fee assessment: NO_FEE classification reported non-zero estimatedFeeVnd=" + raw.estimatedFeeVnd() + ".");
        }
        if (raw.netRecipientVnd() != expectedApprovedVnd) {
            return blocked(provider, PayoutFeeClassification.UNKNOWN, expectedApprovedVnd,
                    "Inconsistent fee assessment: netRecipientVnd (" + raw.netRecipientVnd()
                            + ") != approvedRefundVnd (" + expectedApprovedVnd + ").");
        }
        if (raw.totalDebitVnd() != expectedApprovedVnd + raw.estimatedFeeVnd()) {
            return blocked(provider, PayoutFeeClassification.UNKNOWN, expectedApprovedVnd,
                    "Inconsistent fee assessment: totalDebitVnd (" + raw.totalDebitVnd()
                            + ") != approvedRefundVnd + estimatedFeeVnd (" + (expectedApprovedVnd + raw.estimatedFeeVnd()) + ").");
        }
        if (!raw.guaranteedFullReceipt() || raw.blocked()) {
            return blocked(provider, PayoutFeeClassification.UNKNOWN, expectedApprovedVnd,
                    raw.reason() != null && !raw.reason().isBlank()
                            ? raw.reason()
                            : "Provider did not confirm guaranteed full customer receipt.");
        }

        return new PayoutFeeAssessment(
                provider,
                cls,
                expectedApprovedVnd,
                raw.estimatedFeeVnd(),
                expectedApprovedVnd,
                raw.totalDebitVnd(),
                true,
                false,
                raw.reason() != null ? raw.reason() : "Guaranteed 100% customer refund receipt verified."
        );
    }

    private static String normalizeProvider(String name) {
        return (name == null || name.isBlank()) ? "PAYOS" : name.trim().toUpperCase();
    }
}
