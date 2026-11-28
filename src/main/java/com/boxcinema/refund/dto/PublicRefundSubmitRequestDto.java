package com.boxcinema.refund.dto;

import java.util.ArrayList;
import java.util.List;

public class PublicRefundSubmitRequestDto {
    private String orderCode;
    private String purchaseEmail;
    private String customerName;
    private String customerPhone;
    private String reasonCategory;
    private String description;
    private String evidenceUrl;
    private String toBin;
    private String toAccountNumber;
    private String confirmAccountNumber;
    private String bankAccountName;
    private String bankNote;
    private Long savedBankAccountId;
    private Boolean saveBankAccount;
    private List<RequestedItemDto> items = new ArrayList<>();

    public static class RequestedItemDto {
        private String itemKind; // TICKET, FOOD_ITEM, COMBO_COMPONENT
        private Long targetId;
        private int quantity = 1;

        public String getItemKind() { return itemKind; }
        public void setItemKind(String itemKind) { this.itemKind = itemKind; }
        public Long getTargetId() { return targetId; }
        public void setTargetId(Long targetId) { this.targetId = targetId; }
        public int getQuantity() { return quantity; }
        public void setQuantity(int quantity) { this.quantity = quantity; }
    }

    public String getOrderCode() { return orderCode; }
    public void setOrderCode(String orderCode) { this.orderCode = orderCode; }
    public String getPurchaseEmail() { return purchaseEmail; }
    public void setPurchaseEmail(String purchaseEmail) { this.purchaseEmail = purchaseEmail; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }
    public String getReasonCategory() { return reasonCategory; }
    public void setReasonCategory(String reasonCategory) { this.reasonCategory = reasonCategory; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getEvidenceUrl() { return evidenceUrl; }
    public void setEvidenceUrl(String evidenceUrl) { this.evidenceUrl = evidenceUrl; }
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
    public Long getSavedBankAccountId() { return savedBankAccountId; }
    public void setSavedBankAccountId(Long savedBankAccountId) { this.savedBankAccountId = savedBankAccountId; }
    public Boolean getSaveBankAccount() { return saveBankAccount; }
    public void setSaveBankAccount(Boolean saveBankAccount) { this.saveBankAccount = saveBankAccount; }
    public List<RequestedItemDto> getItems() { return items; }
    public void setItems(List<RequestedItemDto> items) { this.items = items; }

    @Override
    public String toString() {
        return "PublicRefundSubmitRequestDto{orderCode='" + orderCode + "', reasonCategory='" + reasonCategory + "'}";
    }
}
