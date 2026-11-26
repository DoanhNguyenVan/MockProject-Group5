package com.boxcinema.refund.provider;

/**
 * Provider-agnostic HTTP/execution outcome from submitting a payout.
 */
public record PayoutExecutionResult(
        int httpStatus,
        String body,
        String signatureHeader,
        boolean networkTimeout,
        String errorMessage
) {}
