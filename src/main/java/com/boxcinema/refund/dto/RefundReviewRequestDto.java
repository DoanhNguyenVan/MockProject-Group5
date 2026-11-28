package com.boxcinema.refund.dto;

import java.util.ArrayList;
import java.util.List;

public class RefundReviewRequestDto {
    private String action; // DECIDE_ITEMS, REQUEST_MORE_INFO
    private String note;
    private Boolean deadlineExceptionApproved;
    private String deadlineExceptionReason;
    private Boolean limitOverrideApproved;
    private String limitOverrideReason;
    private List<ItemDecisionDto> itemDecisions = new ArrayList<>();

    public static class ItemDecisionDto {
        private Long itemId;
        private String decision; // APPROVED, REJECTED
        private Integer approvedQuantity;
        private String reason;

        public Long getItemId() { return itemId; }
        public void setItemId(Long itemId) { this.itemId = itemId; }
        public String getDecision() { return decision; }
        public void setDecision(String decision) { this.decision = decision; }
        public Integer getApprovedQuantity() { return approvedQuantity; }
        public void setApprovedQuantity(Integer approvedQuantity) { this.approvedQuantity = approvedQuantity; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Boolean getDeadlineExceptionApproved() { return deadlineExceptionApproved; }
    public void setDeadlineExceptionApproved(Boolean deadlineExceptionApproved) { this.deadlineExceptionApproved = deadlineExceptionApproved; }
    public String getDeadlineExceptionReason() { return deadlineExceptionReason; }
    public void setDeadlineExceptionReason(String deadlineExceptionReason) { this.deadlineExceptionReason = deadlineExceptionReason; }
    public Boolean getLimitOverrideApproved() { return limitOverrideApproved; }
    public void setLimitOverrideApproved(Boolean limitOverrideApproved) { this.limitOverrideApproved = limitOverrideApproved; }
    public String getLimitOverrideReason() { return limitOverrideReason; }
    public void setLimitOverrideReason(String limitOverrideReason) { this.limitOverrideReason = limitOverrideReason; }
    public List<ItemDecisionDto> getItemDecisions() { return itemDecisions; }
    public void setItemDecisions(List<ItemDecisionDto> itemDecisions) { this.itemDecisions = itemDecisions; }
}
