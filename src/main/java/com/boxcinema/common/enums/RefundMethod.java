package com.boxcinema.common.enums;

/**
 * Supported refund disbursement methods.
 * ORIGINAL_PAYMENT_METHOD is evaluated first; falls back to BANK_PAYOUT when original channel
 * (e.g. PayOS VietQR pay-in transfer) does not support direct reversal.
 */
public enum RefundMethod {
    ORIGINAL_PAYMENT_METHOD,
    BANK_PAYOUT
}
