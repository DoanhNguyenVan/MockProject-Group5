package com.boxcinema.refund.provider;

import java.util.List;

/**
 * Provider-agnostic execution command for submitting a single payout transfer.
 * Plaintext account number is passed transiently in memory only and never logged.
 */
public record PayoutExecutionCommand(
        Long refundRequestId,
        String referenceId,
        String idempotencyKey,
        long amountVnd,
        String description,
        String toBin,
        String toAccountNumberPlain,
        String toAccountHolderName,
        List<String> category,
        String batchCode,
        String recipientSnapshotHash
) {}
