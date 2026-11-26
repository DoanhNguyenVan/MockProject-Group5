package com.boxcinema.refund.dto;

public class RefundLimitUpdateRequestDto {
    private String configKey;
    private Long limitAmountVnd;
    private String reason;

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public Long getLimitAmountVnd() {
        return limitAmountVnd;
    }

    public void setLimitAmountVnd(Long limitAmountVnd) {
        this.limitAmountVnd = limitAmountVnd;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
