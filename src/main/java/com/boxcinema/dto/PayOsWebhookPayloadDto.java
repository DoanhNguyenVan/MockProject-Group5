package com.boxcinema.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PayOsWebhookPayloadDto {
    private String code;
    private String desc;
    private PayOsWebhookDataDto data;
    private JsonNode rawData;
    private String signature;

    public PayOsWebhookPayloadDto() {}

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getDesc() { return desc; }
    public void setDesc(String desc) { this.desc = desc; }

    public PayOsWebhookDataDto getData() { return data; }
    public void setData(PayOsWebhookDataDto data) { this.data = data; }

    @JsonProperty("data")
    public void handleDataNode(JsonNode node) {
        this.rawData = node;
        try {
            this.data = new ObjectMapper().treeToValue(node, PayOsWebhookDataDto.class);
        } catch (Exception ignored) {}
    }

    public JsonNode getRawData() { return rawData; }
    public void setRawData(JsonNode rawData) { this.rawData = rawData; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PayOsWebhookDataDto {
        private Long orderCode;
        private Long amount;
        private String description;
        private String accountNumber;
        private String reference;
        private String transactionDateTime;
        private String currency;
        private String paymentLinkId;
        private String code;
        private String desc;
        private String counterAccountBankId;
        private String counterAccountName;
        private String counterAccountNumber;
        private String virtualAccountName;
        private String virtualAccountNumber;

        public PayOsWebhookDataDto() {}

        public Long getOrderCode() { return orderCode; }
        public void setOrderCode(Long orderCode) { this.orderCode = orderCode; }
        public Long getAmount() { return amount; }
        public void setAmount(Long amount) { this.amount = amount; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }
        public String getReference() { return reference; }
        public void setReference(String reference) { this.reference = reference; }
        public String getTransactionDateTime() { return transactionDateTime; }
        public void setTransactionDateTime(String transactionDateTime) { this.transactionDateTime = transactionDateTime; }
        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }
        public String getPaymentLinkId() { return paymentLinkId; }
        public void setPaymentLinkId(String paymentLinkId) { this.paymentLinkId = paymentLinkId; }
        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getDesc() { return desc; }
        public void setDesc(String desc) { this.desc = desc; }
        public String getCounterAccountBankId() { return counterAccountBankId; }
        public void setCounterAccountBankId(String counterAccountBankId) { this.counterAccountBankId = counterAccountBankId; }
        public String getCounterAccountName() { return counterAccountName; }
        public void setCounterAccountName(String counterAccountName) { this.counterAccountName = counterAccountName; }
        public String getCounterAccountNumber() { return counterAccountNumber; }
        public void setCounterAccountNumber(String counterAccountNumber) { this.counterAccountNumber = counterAccountNumber; }
        public String getVirtualAccountName() { return virtualAccountName; }
        public void setVirtualAccountName(String virtualAccountName) { this.virtualAccountName = virtualAccountName; }
        public String getVirtualAccountNumber() { return virtualAccountNumber; }
        public void setVirtualAccountNumber(String virtualAccountNumber) { this.virtualAccountNumber = virtualAccountNumber; }

        private final Map<String, Object> additionalProperties = new HashMap<>();

        @JsonAnySetter
        public void setAdditionalProperty(String name, Object value) {
            this.additionalProperties.put(name, value);
        }

        @JsonAnyGetter
        public Map<String, Object> getAdditionalProperties() {
            return this.additionalProperties;
        }
    }
}
