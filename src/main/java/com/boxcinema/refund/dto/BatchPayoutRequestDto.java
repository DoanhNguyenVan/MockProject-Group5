package com.boxcinema.refund.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.ArrayList;
import java.util.List;

public class BatchPayoutRequestDto {
    private List<Long> refundRequestIds = new ArrayList<>();
    @JsonAlias("expectedTotalCash")
    private Long expectedTotalVnd;
    private Boolean strictTotalMatch;
    private String note;

    public List<Long> getRefundRequestIds() { return refundRequestIds; }
    public void setRefundRequestIds(List<Long> refundRequestIds) { this.refundRequestIds = refundRequestIds; }
    public Long getExpectedTotalVnd() { return expectedTotalVnd; }
    public void setExpectedTotalVnd(Long expectedTotalVnd) { this.expectedTotalVnd = expectedTotalVnd; }
    public Boolean getStrictTotalMatch() { return strictTotalMatch; }
    public void setStrictTotalMatch(Boolean strictTotalMatch) { this.strictTotalMatch = strictTotalMatch; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
