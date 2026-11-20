package com.boxcinema.customer.dto.request;

import jakarta.validation.constraints.NotNull;

public class CreatePaymentRequest {

    @NotNull(message = "Booking ID is required")
    private Long bookingId;

    public CreatePaymentRequest() {
    }

    public CreatePaymentRequest(Long bookingId) {
        this.bookingId = bookingId;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public void setBookingId(Long bookingId) {
        this.bookingId = bookingId;
    }
}
