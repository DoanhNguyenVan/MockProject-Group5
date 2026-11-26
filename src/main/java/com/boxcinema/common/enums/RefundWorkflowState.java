package com.boxcinema.common.enums;

import java.util.Locale;

/**
 * Canonical Refund Request Workflow State Machine.
 */
public enum RefundWorkflowState {
    REQUESTED,
    UNDER_REVIEW,
    APPROVED,
    REJECTED,
    PAYOUT_PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    UNKNOWN,
    PAYOUT_BLOCKED,
    CANCELLED;

    public static RefundWorkflowState fromLegacyStatuses(
            String requestStatus,
            String payoutStatus,
            boolean payoutUnknownOutcome) {
        String rs = requestStatus != null ? requestStatus.trim().toUpperCase(Locale.ROOT) : "";
        String ps = payoutStatus != null ? payoutStatus.trim().toUpperCase(Locale.ROOT) : "";

        if ("PAYOUT_BLOCKED".equals(rs) || "PAYOUT_BLOCKED".equals(ps)) {
            return PAYOUT_BLOCKED;
        }
        if ("CANCELLED".equals(rs) || "CANCELLED".equals(ps)) {
            return CANCELLED;
        }
        if ("REJECTED".equals(rs) || "PAYOUT_REJECTED".equals(rs) || "INELIGIBLE".equals(rs) || "REJECTED".equals(ps)) {
            return REJECTED;
        }
        if ("COMPLETED".equals(rs) || "PAYOUT_SUCCEEDED".equals(rs) || "SUCCEEDED".equals(ps) || "SUCCESS".equals(rs)) {
            return SUCCESS;
        }
        if ("PAYOUT_FAILED".equals(rs) || "FAILED".equals(ps) || "FAILED".equals(rs)) {
            return FAILED;
        }
        if ("UNKNOWN".equals(rs) || "UNKNOWN".equals(ps)) {
            return UNKNOWN;
        }
        if ("PAYOUT_PROCESSING".equals(rs) || "PROCESSING".equals(ps) || "PROCESSING".equals(rs)) {
            return payoutUnknownOutcome ? UNKNOWN : PROCESSING;
        }
        if ("AWAITING_PAYOUT_APPROVAL".equals(rs)
                || "PAYOUT_APPROVED".equals(rs)
                || "PAYOUT_PENDING".equals(rs)
                || "AWAITING_APPROVAL".equals(ps)
                || "PENDING".equals(ps)) {
            return PAYOUT_PENDING;
        }
        if ("APPROVED".equals(rs) || "PARTIALLY_APPROVED".equals(rs)) {
            return (ps.isEmpty() || "NOT_REQUIRED".equals(ps)) ? APPROVED : PAYOUT_PENDING;
        }
        if ("UNDER_VERIFICATION".equals(rs)
                || "MORE_INFO_REQUIRED".equals(rs)
                || "VERIFIED".equals(rs)
                || "ESCALATED_TO_ADMIN".equals(rs)
                || "UNDER_REVIEW".equals(rs)) {
            return UNDER_REVIEW;
        }
        return REQUESTED;
    }
}
