package com.boxcinema.refund.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

public class CustomerBankAccountRequestDto {
    private String bankBin;
    private String accountNumber;
    private String confirmAccountNumber;
    private String accountHolderName;

    @JsonAlias("bankNote")
    private String note;

    @JsonProperty("isDefault")
    private Boolean isDefault;

    public String getBankBin() { return bankBin; }
    public void setBankBin(String bankBin) { this.bankBin = bankBin; }

    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }

    public String getConfirmAccountNumber() { return confirmAccountNumber; }
    public void setConfirmAccountNumber(String confirmAccountNumber) { this.confirmAccountNumber = confirmAccountNumber; }

    public String getAccountHolderName() { return accountHolderName; }
    public void setAccountHolderName(String accountHolderName) { this.accountHolderName = accountHolderName; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public Boolean getIsDefault() { return isDefault; }
    public void setIsDefault(Boolean isDefault) { this.isDefault = isDefault; }

    @Override
    public String toString() {
        return "CustomerBankAccountRequestDto{bankBin='" + bankBin + "', isDefault=" + isDefault + "}";
    }
}
