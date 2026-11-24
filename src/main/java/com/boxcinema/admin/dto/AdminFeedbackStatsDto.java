package com.boxcinema.admin.dto;

public class AdminFeedbackStatsDto {
    private long total;
    private long pending;
    private long inProgress;
    private long resolved;
    private long closed;

    public AdminFeedbackStatsDto() {}

    public AdminFeedbackStatsDto(long total, long pending, long inProgress, long resolved, long closed) {
        this.total = total;
        this.pending = pending;
        this.inProgress = inProgress;
        this.resolved = resolved;
        this.closed = closed;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPending() {
        return pending;
    }

    public void setPending(long pending) {
        this.pending = pending;
    }

    public long getInProgress() {
        return inProgress;
    }

    public void setInProgress(long inProgress) {
        this.inProgress = inProgress;
    }

    public long getResolved() {
        return resolved;
    }

    public void setResolved(long resolved) {
        this.resolved = resolved;
    }

    public long getClosed() {
        return closed;
    }

    public void setClosed(long closed) {
        this.closed = closed;
    }
}
