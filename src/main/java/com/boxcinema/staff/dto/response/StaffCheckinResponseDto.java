package com.boxcinema.staff.dto.response;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class StaffCheckinResponseDto {
    private boolean success;
    private String code;
    private String message;
    private Long bookingId;
    private String bookingCode;
    private String movieTitle;
    private String cinemaName;
    private String roomName;
    private String showtimeStart;
    private int totalTickets;
    private int checkedInTickets;
    private List<String> seats = new ArrayList<>();
    private String staffName;
    private LocalDateTime checkedInAt;
    private String checkInStatus;
    private String result;

    public StaffCheckinResponseDto() {}

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }

    public String getBookingCode() { return bookingCode; }
    public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }

    public String getMovieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }

    public String getCinemaName() { return cinemaName; }
    public void setCinemaName(String cinemaName) { this.cinemaName = cinemaName; }

    public String getRoomName() { return roomName; }
    public void setRoomName(String roomName) { this.roomName = roomName; }

    public String getShowtimeStart() { return showtimeStart; }
    public void setShowtimeStart(String showtimeStart) { this.showtimeStart = showtimeStart; }

    public int getTotalTickets() { return totalTickets; }
    public void setTotalTickets(int totalTickets) { this.totalTickets = totalTickets; }

    public int getTicketCount() { return totalTickets; }
    public void setTicketCount(int ticketCount) { this.totalTickets = ticketCount; }

    public int getCheckedInTickets() { return checkedInTickets; }
    public void setCheckedInTickets(int checkedInTickets) { this.checkedInTickets = checkedInTickets; }

    public List<String> getSeats() { return seats; }
    public void setSeats(List<String> seats) { this.seats = seats; }

    public List<String> getSeatLabels() { return seats; }
    public void setSeatLabels(List<String> seatLabels) { this.seats = seatLabels; }

    public String getStaffName() { return staffName; }
    public void setStaffName(String staffName) { this.staffName = staffName; }

    public LocalDateTime getCheckedInAt() { return checkedInAt; }
    public void setCheckedInAt(LocalDateTime checkedInAt) { this.checkedInAt = checkedInAt; }

    public String getCheckInStatus() { return checkInStatus; }
    public void setCheckInStatus(String checkInStatus) { this.checkInStatus = checkInStatus; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
}
