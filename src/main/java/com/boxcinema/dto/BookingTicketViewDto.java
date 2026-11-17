package com.boxcinema.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class BookingTicketViewDto {
    private Long bookingId;
    private String bookingCode;
    private String movieTitle;
    private String moviePosterUrl;
    private String cinemaName;
    private String roomName;
    private String showtimeStart;
    private String guestName;
    private String guestEmail;
    private BigDecimal totalAmount;
    private String paymentStatus;
    private String bookingStatus;
    private String checkinStatus; // "PENDING", "CHECKED_IN"
    private LocalDateTime checkedInAt;
    private String bookingQrToken; // Raw decrypted token for QR rendering
    private String bookingQrHash;
    private String bookingQrStatus;
    private String foodFulfillmentStatus; // "NONE", "QUEUED", "PREPARING", "READY", "PICKED_UP"
    private List<SeatTicketItemDto> tickets = new ArrayList<>();
    private List<BookingFoodItemDto> foodItems = new ArrayList<>();

    public BookingTicketViewDto() {}

    public static class SeatTicketItemDto {
        private Long ticketId;
        private String ticketCode;
        private String seatLabel;
        private BigDecimal price;
        private String ticketStatus;

        public SeatTicketItemDto() {}

        public SeatTicketItemDto(Long ticketId, String ticketCode, String seatLabel, BigDecimal price, String ticketStatus) {
            this.ticketId = ticketId;
            this.ticketCode = ticketCode;
            this.seatLabel = seatLabel;
            this.price = price;
            this.ticketStatus = ticketStatus;
        }

        public Long getTicketId() { return ticketId; }
        public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
        public String getTicketCode() { return ticketCode; }
        public void setTicketCode(String ticketCode) { this.ticketCode = ticketCode; }
        public String getSeatLabel() { return seatLabel; }
        public void setSeatLabel(String seatLabel) { this.seatLabel = seatLabel; }
        public BigDecimal getPrice() { return price; }
        public void setPrice(BigDecimal price) { this.price = price; }
        public String getTicketStatus() { return ticketStatus; }
        public void setTicketStatus(String ticketStatus) { this.ticketStatus = ticketStatus; }
    }

    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }
    public String getBookingCode() { return bookingCode; }
    public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }
    public String getMovieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }
    public String getMoviePosterUrl() { return moviePosterUrl; }
    public void setMoviePosterUrl(String moviePosterUrl) { this.moviePosterUrl = moviePosterUrl; }
    public String getCinemaName() { return cinemaName; }
    public void setCinemaName(String cinemaName) { this.cinemaName = cinemaName; }
    public String getRoomName() { return roomName; }
    public void setRoomName(String roomName) { this.roomName = roomName; }
    public String getShowtimeStart() { return showtimeStart; }
    public void setShowtimeStart(String showtimeStart) { this.showtimeStart = showtimeStart; }
    public String getGuestName() { return guestName; }
    public void setGuestName(String guestName) { this.guestName = guestName; }
    public String getGuestEmail() { return guestEmail; }
    public void setGuestEmail(String guestEmail) { this.guestEmail = guestEmail; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getPaymentStatus() { return paymentStatus; }
    public void setPaymentStatus(String paymentStatus) { this.paymentStatus = paymentStatus; }
    public String getBookingStatus() { return bookingStatus; }
    public void setBookingStatus(String bookingStatus) { this.bookingStatus = bookingStatus; }
    public String getCheckinStatus() { return checkinStatus; }
    public void setCheckinStatus(String checkinStatus) { this.checkinStatus = checkinStatus; }
    public LocalDateTime getCheckedInAt() { return checkedInAt; }
    public void setCheckedInAt(LocalDateTime checkedInAt) { this.checkedInAt = checkedInAt; }
    public String getBookingQrToken() { return bookingQrToken; }
    public void setBookingQrToken(String bookingQrToken) { this.bookingQrToken = bookingQrToken; }
    public String getBookingQrHash() { return bookingQrHash; }
    public void setBookingQrHash(String bookingQrHash) { this.bookingQrHash = bookingQrHash; }
    public String getBookingQrStatus() { return bookingQrStatus; }
    public void setBookingQrStatus(String bookingQrStatus) { this.bookingQrStatus = bookingQrStatus; }
    public String getFoodFulfillmentStatus() { return foodFulfillmentStatus; }
    public void setFoodFulfillmentStatus(String foodFulfillmentStatus) { this.foodFulfillmentStatus = foodFulfillmentStatus; }
    public List<SeatTicketItemDto> getTickets() { return tickets; }
    public void setTickets(List<SeatTicketItemDto> tickets) { this.tickets = tickets; }
    public List<BookingFoodItemDto> getFoodItems() { return foodItems; }
    public void setFoodItems(List<BookingFoodItemDto> foodItems) { this.foodItems = foodItems; }
}
