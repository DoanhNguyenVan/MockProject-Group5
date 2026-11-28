package com.boxcinema.staff.dto.response;

import java.time.LocalDateTime;

/**
 * Clean DTO for GET /api/staff/check-ins avoiding lazy Hibernate proxy serialization errors.
 */
public class StaffCheckinHistoryDto {
    private Long id;
    private LocalDateTime checkedInAt;
    private String result;
    private String status;
    private int seatsCount;
    private BookingRefDto booking;

    public static class BookingRefDto {
        private Long bookingId;
        private String bookingCode;

        public BookingRefDto() {}

        public BookingRefDto(Long bookingId, String bookingCode) {
            this.bookingId = bookingId;
            this.bookingCode = bookingCode;
        }

        public Long getBookingId() { return bookingId; }
        public void setBookingId(Long bookingId) { this.bookingId = bookingId; }
        public String getBookingCode() { return bookingCode; }
        public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public LocalDateTime getCheckedInAt() { return checkedInAt; }
    public void setCheckedInAt(LocalDateTime checkedInAt) { this.checkedInAt = checkedInAt; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getSeatsCount() { return seatsCount; }
    public void setSeatsCount(int seatsCount) { this.seatsCount = seatsCount; }

    public BookingRefDto getBooking() { return booking; }
    public void setBooking(BookingRefDto booking) { this.booking = booking; }
}
