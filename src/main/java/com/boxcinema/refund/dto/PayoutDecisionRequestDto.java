package com.boxcinema.refund.dto;

public class PayoutDecisionRequestDto {
    private String reason;
    private String note;
    private Boolean limitOverrideApproved;
    private String limitOverrideReason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Boolean getLimitOverrideApproved() { return limitOverrideApproved; }
    public void setLimitOverrideApproved(Boolean limitOverrideApproved) { this.limitOverrideApproved = limitOverrideApproved; }
    public String getLimitOverrideReason() { return limitOverrideReason; }
    public void setLimitOverrideReason(String limitOverrideReason) { this.limitOverrideReason = limitOverrideReason; }
}
