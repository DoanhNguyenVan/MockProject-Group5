package com.boxcinema.refund.dto;

import java.time.LocalDateTime;

public class PayoutAccountBalanceDto {
    private String mode;
    private boolean liveVerified;
    private int httpStatus;
    private String responseCode;
    private String responseDesc;
    private String accountNumberMasked;
    private String accountName;
    private String currency;
    private String balance;
    private String message;
    private LocalDateTime checkedAt;

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public boolean isLiveVerified() { return liveVerified; }
    public void setLiveVerified(boolean liveVerified) { this.liveVerified = liveVerified; }
    public int getHttpStatus() { return httpStatus; }
    public void setHttpStatus(int httpStatus) { this.httpStatus = httpStatus; }
    public String getResponseCode() { return responseCode; }
    public void setResponseCode(String responseCode) { this.responseCode = responseCode; }
    public String getResponseDesc() { return responseDesc; }
    public void setResponseDesc(String responseDesc) { this.responseDesc = responseDesc; }
    public String getAccountNumberMasked() { return accountNumberMasked; }
    public void setAccountNumberMasked(String accountNumberMasked) { this.accountNumberMasked = accountNumberMasked; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getBalance() { return balance; }
    public void setBalance(String balance) { this.balance = balance; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public LocalDateTime getCheckedAt() { return checkedAt; }
    public void setCheckedAt(LocalDateTime checkedAt) { this.checkedAt = checkedAt; }
}
