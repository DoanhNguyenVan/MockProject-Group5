package com.boxcinema.dto;

public class StaffCheckinRequestDto {
    private String qrPayload;
    private String qrToken;

    public StaffCheckinRequestDto() {}

    public StaffCheckinRequestDto(String qrPayload) {
        this.qrPayload = qrPayload;
        this.qrToken = qrPayload;
    }

    public String getQrPayload() {
        return qrPayload != null ? qrPayload : qrToken;
    }

    public void setQrPayload(String qrPayload) {
        this.qrPayload = qrPayload;
        if (this.qrToken == null) {
            this.qrToken = qrPayload;
        }
    }

    public String getQrToken() {
        return qrToken != null ? qrToken : qrPayload;
    }

    public void setQrToken(String qrToken) {
        this.qrToken = qrToken;
        if (this.qrPayload == null) {
            this.qrPayload = qrToken;
        }
    }
}
