package com.boxcinema.refund.dto;

public class RefundBankUpdateRequestDto {
    private String purchaseEmail;
    private Long savedBankAccountId;
    private String toBin;
    private String toAccountNumber;
    private String confirmAccountNumber;
    private String bankAccountName;
    private String bankNote;

    public String getPurchaseEmail() { return purchaseEmail; }
    public void setPurchaseEmail(String purchaseEmail) { this.purchaseEmail = purchaseEmail; }

    public Long getSavedBankAccountId() { return savedBankAccountId; }
    public void setSavedBankAccountId(Long savedBankAccountId) { this.savedBankAccountId = savedBankAccountId; }

    public String getToBin() { return toBin; }
    public void setToBin(String toBin) { this.toBin = toBin; }

    public String getToAccountNumber() { return toAccountNumber; }
    public void setToAccountNumber(String toAccountNumber) { this.toAccountNumber = toAccountNumber; }

    public String getConfirmAccountNumber() { return confirmAccountNumber; }
    public void setConfirmAccountNumber(String confirmAccountNumber) { this.confirmAccountNumber = confirmAccountNumber; }

    public String getBankAccountName() { return bankAccountName; }
    public void setBankAccountName(String bankAccountName) { this.bankAccountName = bankAccountName; }

    public String getBankNote() { return bankNote; }
    public void setBankNote(String bankNote) { this.bankNote = bankNote; }

    @Override
    public String toString() {
        return "RefundBankUpdateRequestDto{toBin='" + toBin + "', savedBankAccountId=" + savedBankAccountId + "}";
    }
}
