package com.boxcinema.refund.dto;

public class RefundAppealSubmitRequestDto {
    private String purchaseEmail;
    private String appealReason;
    private String evidenceUrl;

    public String getPurchaseEmail() {
        return purchaseEmail;
    }

    public void setPurchaseEmail(String purchaseEmail) {
        this.purchaseEmail = purchaseEmail;
    }

    public String getAppealReason() {
        return appealReason;
    }

    public void setAppealReason(String appealReason) {
        this.appealReason = appealReason;
    }

    public String getEvidenceUrl() {
        return evidenceUrl;
    }

    public void setEvidenceUrl(String evidenceUrl) {
        this.evidenceUrl = evidenceUrl;
    }
}
