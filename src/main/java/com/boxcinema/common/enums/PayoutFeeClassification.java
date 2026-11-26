package com.boxcinema.common.enums;

import java.util.Locale;

/**
 * Classification of payout provider transfer fee handling.
 * Only SENDER_PAYS and NO_FEE satisfy the 100% customer refund receipt guarantee.
 * RECIPIENT_DEDUCTED, UNKNOWN, and UNVERIFIED MUST block automated payout submission.
 */
public enum PayoutFeeClassification {
    SENDER_PAYS,
    RECIPIENT_DEDUCTED,
    NO_FEE,
    UNKNOWN,
    UNVERIFIED;

    public boolean allowsGuaranteedFullReceipt() {
        return this == SENDER_PAYS || this == NO_FEE;
    }

    public static PayoutFeeClassification fromStringOrUnverified(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNVERIFIED;
        }
        String norm = raw.trim().toUpperCase(Locale.ROOT);
        for (PayoutFeeClassification value : values()) {
            if (value.name().equals(norm)) {
                return value;
            }
        }
        return UNKNOWN;
    }
}
