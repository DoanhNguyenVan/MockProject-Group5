package com.boxcinema.refund.service;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.EmailOutboxRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Enqueues durable, deduplicated EmailOutbox records for all 6 refund stages.
 */
@Service
public class RefundEmailService {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final EmailOutboxRepository emailOutboxRepository;

    public RefundEmailService(EmailOutboxRepository emailOutboxRepository) {
        this.emailOutboxRepository = emailOutboxRepository;
    }

    public void enqueueReceivedEmail(RefundRequest req, List<RefundRequestItem> items) {
        String dedupKey = "REFUND_RECEIVED:" + req.getId();
        String subject = "BOX Cinema - Refund Request Received (#" + req.getRequestCode() + ")";
        StringBuilder itemRows = new StringBuilder();
        for (RefundRequestItem item : items) {
            itemRows.append("<li>")
                    .append(HtmlUtils.htmlEscape(item.getItemLabelSnapshot()))
                    .append(" (Qty: ").append(item.getRequestedQuantity()).append(")</li>");
        }
        String body = wrapCard(
                "Refund Request Received",
                "#2563eb",
                "<p>Dear " + esc(req.getCustomerName(), "Valued Customer") + ",</p>"
                        + "<p>We have <strong>received</strong> your refund request <strong>#" + esc(req.getRequestCode(), "") + "</strong> "
                        + "for order <strong>" + esc(req.getSubmittedOrderCode(), "") + "</strong>.</p>"
                        + "<div style='background:#fef3c7;border:1px solid #f59e0b;padding:12px;border-radius:8px;margin:12px 0;color:#92400e;'>"
                        + "<strong>Important Notice:</strong> Your request is currently <strong>pending review and verification</strong>. "
                        + "It has <strong>NOT yet been approved or refunded</strong>."
                        + "</div>"
                        + "<p><strong>Requested Items:</strong></p><ul>" + itemRows + "</ul>"
                        + "<p><strong>Registered Payout Account:</strong> "
                        + esc(req.getToBankName(), "") + " (" + esc(req.getToBin(), "") + ") - "
                        + esc(req.getToAccountNumberMasked(), "N/A") + "</p>"
                        + "<p>You can track updates at our Contact &amp; Refund Support page using code <strong>"
                        + esc(req.getRequestCode(), "") + "</strong> and your purchase email.</p>"
        );
        saveOutbox(req, "REFUND_RECEIVED", subject, body, dedupKey);
    }

    public void enqueueMoreInfoEmail(RefundRequest req, long commId, String question) {
        String dedupKey = "REFUND_MORE_INFO:" + req.getId() + ":" + commId;
        String subject = "BOX Cinema - Additional Information Needed (#" + req.getRequestCode() + ")";
        String body = wrapCard(
                "Additional Information Requested",
                "#d97706",
                "<p>Dear " + esc(req.getCustomerName(), "Valued Customer") + ",</p>"
                        + "<p>Our cinema team needs additional details to verify refund request <strong>#" + esc(req.getRequestCode(), "") + "</strong>:</p>"
                        + "<blockquote style='background:#f8fafc;border-left:4px solid #d97706;padding:12px;margin:12px 0;'>"
                        + esc(question, "") + "</blockquote>"
                        + "<p>Please visit BOX Cinema's <strong>/contact</strong> page under <em>Track Request Status &amp; Communication</em>, "
                        + "enter request code <strong>" + esc(req.getRequestCode(), "") + "</strong> and your purchase email to reply.</p>"
        );
        saveOutbox(req, "REFUND_MORE_INFO", subject, body, dedupKey);
    }

    public void enqueueBankReverifyEmail(RefundRequest req, long commId, String reason) {
        String dedupKey = "REFUND_BANK_REVERIFY:" + req.getId() + ":" + commId;
        String subject = "BOX Cinema - Bank Account Re-verification Needed (#" + req.getRequestCode() + ")";
        String body = wrapCard(
                "Bank Account Update Required",
                "#d97706",
                "<p>Dear " + esc(req.getCustomerName(), "Valued Customer") + ",</p>"
                        + "<p>Our Finance team has placed payout for refund request <strong>#" + esc(req.getRequestCode(), "")
                        + "</strong> on hold so you can verify or update your recipient bank account ("
                        + esc(req.getToBankName(), "") + " " + esc(req.getToAccountNumberMasked(), "") + "):</p>"
                        + "<blockquote style='background:#f8fafc;border-left:4px solid #d97706;padding:12px;margin:12px 0;'>"
                        + esc(reason, "Please verify your recipient bank account details.") + "</blockquote>"
                        + "<p>Please visit BOX Cinema's <strong>/contact</strong> page under <em>Track Request Status &amp; Communication</em>, "
                        + "enter request code <strong>" + esc(req.getRequestCode(), "") + "</strong> and your purchase email to update your bank details.</p>"
        );
        saveOutbox(req, "REFUND_MORE_INFO", subject, body, dedupKey);
    }

    public void enqueueRejectedEmail(RefundRequest req, List<RefundRequestItem> items) {
        String dedupKey = "REFUND_REJECTED:" + req.getId();
        String subject = "BOX Cinema - Refund Request Update (#" + req.getRequestCode() + ")";
        StringBuilder reasons = new StringBuilder();
        for (RefundRequestItem item : items) {
            reasons.append("<li><strong>").append(esc(item.getItemLabelSnapshot(), "")).append(":</strong> ")
                    .append(esc(item.getDecisionReason(), req.getDecisionNote() != null ? req.getDecisionNote() : "Does not meet refund policy"))
                    .append("</li>");
        }
        String body = wrapCard(
                "Refund Request Not Approved",
                "#dc2626",
                "<p>Dear " + esc(req.getCustomerName(), "Valued Customer") + ",</p>"
                        + "<p>After careful verification, your refund request <strong>#" + esc(req.getRequestCode(), "") + "</strong> could not be approved.</p>"
                        + "<p><strong>Review Details:</strong></p><ul>" + reasons + "</ul>"
                        + (req.getDecisionNote() != null && !req.getDecisionNote().isBlank()
                            ? "<p><strong>Manager Note:</strong> " + esc(req.getDecisionNote(), "") + "</p>" : "")
                        + "<p>If you need further assistance, please contact BOX Cinema Support at 1900 6868 or reply via our Contact page.</p>"
        );
        saveOutbox(req, "REFUND_REJECTED", subject, body, dedupKey);
    }

    public void enqueueApprovedEmail(RefundRequest req) {
        String dedupKey = "REFUND_APPROVED:" + req.getId();
        String subject = "BOX Cinema - Refund Request Approved (#" + req.getRequestCode() + ")";
        boolean hasCashPayout = req.getApprovedCashTotal() != null
                && req.getApprovedCashTotal().compareTo(BigDecimal.ZERO) > 0;
        String payoutBlock = hasCashPayout
                ? "<div style='background:#eff6ff;border:1px solid #3b82f6;padding:12px;border-radius:8px;margin:12px 0;color:#1e40af;'>"
                        + "<strong>Transfer Status:</strong> Your refund request has passed business verification and is now queued for final Admin PayOS Payout disbursement to "
                        + esc(req.getToBankName(), "") + " (" + esc(req.getToAccountNumberMasked(), "") + "). "
                        + "<strong>Funds have NOT yet arrived in your bank account</strong> — you will receive a separate confirmation email once the bank transfer succeeds."
                        + "</div>"
                : "<div style='background:#f5f3ff;border:1px solid #8b5cf6;padding:12px;border-radius:8px;margin:12px 0;color:#5b21b6;'>"
                        + "<strong>Recovery Method:</strong> Your refund is issued solely as a recovery voucher — no bank transfer is required, and your new voucher code is being delivered in a separate email."
                        + "</div>";
        String body = wrapCard(
                "Refund Request Approved",
                "#059669",
                "<p>Dear " + esc(req.getCustomerName(), "Valued Customer") + ",</p>"
                        + "<p>Your refund request <strong>#" + esc(req.getRequestCode(), "") + "</strong> has been <strong>APPROVED</strong>.</p>"
                        + "<ul>"
                        + "<li><strong>Approved Cash Refund:</strong> " + formatVnd(req.getApprovedCashTotal()) + "</li>"
                        + "<li><strong>Voucher Value Recovery:</strong> " + formatVnd(req.getApprovedVoucherTotal()) + "</li>"
                        + "</ul>"
                        + payoutBlock
        );
        saveOutbox(req, "REFUND_APPROVED", subject, body, dedupKey);
    }

    public void enqueuePayoutSucceededEmail(RefundRequest req, PayOsPayoutTransaction tx) {
        String dedupKey = "REFUND_PAYOUT_SUCCEEDED:" + req.getId();
        String subject = "BOX Cinema - Bank Transfer Completed (#" + req.getRequestCode() + ")";
        String txTime = tx.getSucceededAt() != null ? tx.getSucceededAt().format(DT_FMT) : "Now";
        String body = wrapCard(
                "Refund Payout Succeeded",
                "#10b981",
                "<p>Dear " + esc(req.getCustomerName(), "Valued Customer") + ",</p>"
                        + "<p>Your refund bank transfer for request <strong>#" + esc(req.getRequestCode(), "") + "</strong> has <strong>SUCCEEDED</strong>.</p>"
                        + "<ul>"
                        + "<li><strong>Transferred Amount:</strong> " + formatVnd(BigDecimal.valueOf(tx.getAmount())) + "</li>"
                        + "<li><strong>Destination Bank:</strong> " + esc(req.getToBankName(), "") + " (BIN " + esc(tx.getToBin(), "") + ")</li>"
                        + "<li><strong>Recipient Account:</strong> " + esc(tx.getToAccountNumberMasked(), "") + "</li>"
                        + "<li><strong>Reference ID:</strong> " + esc(tx.getReferenceId(), "") + "</li>"
                        + (tx.getPayosPayoutId() != null ? "<li><strong>PayOS Payout ID:</strong> " + esc(tx.getPayosPayoutId(), "") + "</li>" : "")
                        + "<li><strong>Completed At:</strong> " + esc(txTime, "") + "</li>"
                        + "</ul>"
        );
        saveOutbox(req, "REFUND_PAYOUT_SUCCEEDED", subject, body, dedupKey);
    }

    public void enqueuePayoutRejectedEmail(RefundRequest req, String reason) {
        String dedupKey = "REFUND_PAYOUT_REJECTED:" + req.getId();
        String subject = "BOX Cinema - Payout Approval Update (#" + req.getRequestCode() + ")";
        String body = wrapCard(
                "Payout Transfer Rejected",
                "#dc2626",
                "<p>Dear " + esc(req.getCustomerName(), "Valued Customer") + ",</p>"
                        + "<p>Your cash refund request <strong>#" + esc(req.getRequestCode(), "") + "</strong> could not be approved for bank transfer during financial review.</p>"
                        + "<p><strong>Finance Admin Note:</strong> " + esc(reason, "Does not meet payout verification requirements") + "</p>"
                        + "<p>If you believe this is an error or need to update your bank details, please contact BOX Cinema Support at 1900 6868.</p>"
        );
        saveOutbox(req, "REFUND_PAYOUT_REJECTED", subject, body, dedupKey);
    }

    public void enqueueVoucherIssuedEmail(RefundRequest req, VoucherRecovery vr) {
        String dedupKey = "REFUND_VOUCHER_ISSUED:" + req.getId();
        String subject = "BOX Cinema - Replacement Voucher Issued (#" + req.getRequestCode() + ")";
        String exp = vr.getExpiresAt() != null ? vr.getExpiresAt().format(DT_FMT) : "30 days";
        String body = wrapCard(
                "Recovery Voucher Issued",
                "#7c3aed",
                "<p>Dear " + esc(req.getCustomerName(), "Valued Customer") + ",</p>"
                        + "<p>As part of approved refund request <strong>#" + esc(req.getRequestCode(), "") + "</strong>, "
                        + "we have issued a new single-use voucher covering the promotional discount portion of your refunded item(s):</p>"
                        + "<div style='background:#f5f3ff;border:2px dashed #7c3aed;padding:16px;text-align:center;border-radius:8px;margin:14px 0;'>"
                        + "<div style='font-size:12px;color:#5b21b6;text-transform:uppercase;'>New Single-Use Voucher Code</div>"
                        + "<div style='font-size:22px;font-weight:bold;color:#4c1d95;letter-spacing:2px;margin:6px 0;'>"
                        + esc(vr.getNewVoucherCode(), "") + "</div>"
                        + "<div style='font-size:14px;color:#334155;'>Value: <strong>" + formatVnd(vr.getRecoveryAmount())
                        + "</strong> &bull; Valid until: <strong>" + esc(exp, "") + "</strong></div>"
                        + "</div>"
                        + "<p>This voucher is single-use and bound to your purchase email (" + esc(req.getPurchaseEmail(), "") + ").</p>"
        );
        saveOutbox(req, "REFUND_VOUCHER_ISSUED", subject, body, dedupKey);
    }

    private void saveOutbox(RefundRequest req, String emailType, String subject, String bodyHtml, String dedupKey) {
        if (emailOutboxRepository.existsByDedupKey(dedupKey)) {
            return;
        }
        EmailOutbox outbox = new EmailOutbox();
        outbox.setBooking(req.getBooking());
        outbox.setRefundRequestId(req.getId());
        outbox.setRecipientEmail(req.getPurchaseEmail());
        outbox.setEmailType(emailType);
        outbox.setSubject(subject);
        outbox.setBodyHtml(bodyHtml);
        outbox.setDedupKey(dedupKey);
        outbox.setStatus("PENDING");
        emailOutboxRepository.save(outbox);
    }

    private String wrapCard(String title, String accentHex, String innerHtml) {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'/></head>"
                + "<body style='font-family:Arial,sans-serif;background:#f8fafc;padding:20px;margin:0;'>"
                + "<div style='max-width:600px;margin:0 auto;background:#ffffff;border-radius:12px;overflow:hidden;border:1px solid #e2e8f0;'>"
                + "<div style='background:" + accentHex + ";color:#ffffff;padding:18px 24px;'>"
                + "<h2 style='margin:0;font-size:20px;'>BOX CINEMA &bull; " + esc(title, "") + "</h2>"
                + "</div>"
                + "<div style='padding:24px;color:#0f172a;line-height:1.55;font-size:14px;'>" + innerHtml + "</div>"
                + "<div style='background:#f1f5f9;padding:12px 24px;color:#64748b;font-size:12px;'>"
                + "BOX Cinema Customer Care &bull; 1900 6868 &bull; support@boxcinema.vn"
                + "</div></div></body></html>";
    }

    private static String esc(String val, String fallback) {
        String s = (val == null || val.isBlank()) ? fallback : val;
        return HtmlUtils.htmlEscape(s != null ? s : "");
    }

    private static String formatVnd(BigDecimal amount) {
        long val = amount != null ? amount.longValue() : 0L;
        return NumberFormat.getInstance(Locale.US).format(val) + " VND";
    }
}
