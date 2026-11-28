package com.boxcinema.refund.dto;

/** Request body for {@code POST /api/admin/refunds/{id}/payout/live-execute}. */
public class LiveExecuteRequestDto {
    private String confirmationToken;
    private String confirmPhrase;
    private Long confirmAmountVnd;
    private Boolean acknowledgeRealMoney;
    private String note;

    public String getConfirmationToken() { return confirmationToken; }
    public void setConfirmationToken(String confirmationToken) { this.confirmationToken = confirmationToken; }
    public String getConfirmPhrase() { return confirmPhrase; }
    public void setConfirmPhrase(String confirmPhrase) { this.confirmPhrase = confirmPhrase; }
    public Long getConfirmAmountVnd() { return confirmAmountVnd; }
    public void setConfirmAmountVnd(Long confirmAmountVnd) { this.confirmAmountVnd = confirmAmountVnd; }
    public Boolean getAcknowledgeRealMoney() { return acknowledgeRealMoney; }
    public void setAcknowledgeRealMoney(Boolean acknowledgeRealMoney) { this.acknowledgeRealMoney = acknowledgeRealMoney; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
