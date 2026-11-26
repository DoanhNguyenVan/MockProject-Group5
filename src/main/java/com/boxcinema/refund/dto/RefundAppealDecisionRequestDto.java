package com.boxcinema.refund.dto;

public class RefundAppealDecisionRequestDto {
    private String decision;
    private String decisionNote;

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getDecisionNote() {
        return decisionNote;
    }

    public void setDecisionNote(String decisionNote) {
        this.decisionNote = decisionNote;
    }
}
