package com.boxcinema.refund.provider;

/**
 * Provider-agnostic reconciliation query outcome for an existing payout transaction.
 */
public record PayoutReconciliationResult(
        int httpStatus,
        String body,
        String signatureHeader,
        boolean networkTimeout,
        String errorMessage
) {}
