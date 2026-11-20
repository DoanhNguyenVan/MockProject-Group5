package com.boxcinema.common.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class BookingResponseDto {
    private Long bookingId;
    private String bookingCode;
    private Long showtimeId;
    private String movieTitle;
    private String moviePosterUrl;
    private String cinemaName;
    private String roomName;
    private String showtimeStart;
    private String guestName;
    private String guestEmail;
    private String guestPhone;
    private BigDecimal ticketSubtotal;
    private BigDecimal foodSubtotal;
    private BigDecimal discountAmount;
    private String voucherCode;
    private BigDecimal serviceFee;
    private BigDecimal totalAmount;
    private String bookingStatus;
    private LocalDateTime paymentDeadline;
    private LocalDateTime confirmedAt;
    private List<BookingSeatDto> seats = new ArrayList<>();
    private List<BookingFoodItemDto> foodItems = new ArrayList<>();

    public BookingResponseDto() {}

    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }
    public String getBookingCode() { return bookingCode; }
    public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }
    public Long getShowtimeId() { return showtimeId; }
    public void setShowtimeId(Long showtimeId) { this.showtimeId = showtimeId; }
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
    public String getGuestPhone() { return guestPhone; }
    public void setGuestPhone(String guestPhone) { this.guestPhone = guestPhone; }
    public BigDecimal getTicketSubtotal() { return ticketSubtotal; }
    public void setTicketSubtotal(BigDecimal ticketSubtotal) { this.ticketSubtotal = ticketSubtotal; }
    public BigDecimal getFoodSubtotal() { return foodSubtotal; }
    public void setFoodSubtotal(BigDecimal foodSubtotal) { this.foodSubtotal = foodSubtotal; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public String getVoucherCode() { return voucherCode; }
    public void setVoucherCode(String voucherCode) { this.voucherCode = voucherCode; }
    public BigDecimal getServiceFee() { return serviceFee; }
    public void setServiceFee(BigDecimal serviceFee) { this.serviceFee = serviceFee; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public String getBookingStatus() { return bookingStatus; }
    public void setBookingStatus(String bookingStatus) { this.bookingStatus = bookingStatus; }
    public LocalDateTime getPaymentDeadline() { return paymentDeadline; }
    public void setPaymentDeadline(LocalDateTime paymentDeadline) { this.paymentDeadline = paymentDeadline; }
    public LocalDateTime getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(LocalDateTime confirmedAt) { this.confirmedAt = confirmedAt; }
    public List<BookingSeatDto> getSeats() { return seats; }
    public void setSeats(List<BookingSeatDto> seats) { this.seats = seats; }
    public List<BookingFoodItemDto> getFoodItems() { return foodItems; }
    public void setFoodItems(List<BookingFoodItemDto> foodItems) { this.foodItems = foodItems; }
}
