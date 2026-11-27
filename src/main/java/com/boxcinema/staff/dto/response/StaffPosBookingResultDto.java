package com.boxcinema.staff.dto.response;

import com.boxcinema.publicsite.dto.response.PublicFoodItemDto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class StaffPosBookingResultDto {
    private Long bookingId;
    private String bookingCode;
    private String bookingStatus;   // "CONFIRMED" | "PENDING_PAYMENT"
    private String paymentMethod;   // "CASH" | "CARD" | "PAYOS_QR"
    private BigDecimal totalAmount;
    private String guestName;
    private String guestPhone;
    private String guestEmail;
    private String movieTitle;
    private String cinemaName;
    private String roomName;
    private String pickupLocation;
    private String qrToken;
    private String showtimeStart;
    private List<String> seatLabels = new ArrayList<>();
    private List<PublicFoodItemDto> foodItems = new ArrayList<>();
    private Long paymentId;
    private Long orderCode;
    private String payOsQrPayload;
    private String checkoutUrl;
    private String accountNumber;
    private String accountName;
    private String bankName;
    private boolean emailSent;

    public StaffPosBookingResultDto() {}

    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }

    public String getBookingCode() { return bookingCode; }
    public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }

    public String getBookingStatus() { return bookingStatus; }
    public void setBookingStatus(String bookingStatus) { this.bookingStatus = bookingStatus; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }

    public String getGuestName() { return guestName; }
    public void setGuestName(String guestName) { this.guestName = guestName; }

    public String getGuestPhone() { return guestPhone; }
    public void setGuestPhone(String guestPhone) { this.guestPhone = guestPhone; }

    public String getGuestEmail() { return guestEmail; }
    public void setGuestEmail(String guestEmail) { this.guestEmail = guestEmail; }

    public String getMovieTitle() { return movieTitle; }
    public void setMovieTitle(String movieTitle) { this.movieTitle = movieTitle; }

    public String getShowtimeStart() { return showtimeStart; }
    public void setShowtimeStart(String showtimeStart) { this.showtimeStart = showtimeStart; }

    public List<String> getSeatLabels() { return seatLabels; }
    public void setSeatLabels(List<String> seatLabels) { this.seatLabels = seatLabels; }

    public Long getPaymentId() { return paymentId; }
    public void setPaymentId(Long paymentId) { this.paymentId = paymentId; }

    public Long getOrderCode() { return orderCode; }
    public void setOrderCode(Long orderCode) { this.orderCode = orderCode; }

    public String getPayOsQrPayload() { return payOsQrPayload; }
    public void setPayOsQrPayload(String payOsQrPayload) { this.payOsQrPayload = payOsQrPayload; }

    public String getCheckoutUrl() { return checkoutUrl; }
    public void setCheckoutUrl(String checkoutUrl) { this.checkoutUrl = checkoutUrl; }

    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }

    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }

    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }

    public boolean isEmailSent() { return emailSent; }
    public void setEmailSent(boolean emailSent) { this.emailSent = emailSent; }

    public String getCinemaName() { return cinemaName; }
    public void setCinemaName(String cinemaName) { this.cinemaName = cinemaName; }

    public String getRoomName() { return roomName; }
    public void setRoomName(String roomName) { this.roomName = roomName; }

    public String getPickupLocation() { return pickupLocation; }
    public void setPickupLocation(String pickupLocation) { this.pickupLocation = pickupLocation; }

    public String getQrToken() { return qrToken; }
    public void setQrToken(String qrToken) { this.qrToken = qrToken; }

    public List<PublicFoodItemDto> getFoodItems() { return foodItems; }
    public void setFoodItems(List<PublicFoodItemDto> foodItems) { this.foodItems = foodItems; }
}
