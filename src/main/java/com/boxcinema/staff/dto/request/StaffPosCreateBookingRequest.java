package com.boxcinema.staff.dto.request;

import com.boxcinema.customer.dto.request.BookingComboRequestDto;
import com.boxcinema.customer.dto.request.BookingFoodItemRequestDto;

import java.util.List;

public class StaffPosCreateBookingRequest {
    private Long showtimeId;
    private List<Long> seatIds;
    private String guestName;
    private String guestPhone;
    private String guestEmail;
    private String paymentMethod; // "CASH" | "CARD" | "PAYOS_QR"
    private String voucherCode;
    private List<BookingFoodItemRequestDto> foodItems;
    private List<BookingComboRequestDto> combos;

    public StaffPosCreateBookingRequest() {}

    public Long getShowtimeId() { return showtimeId; }
    public void setShowtimeId(Long showtimeId) { this.showtimeId = showtimeId; }

    public List<Long> getSeatIds() { return seatIds; }
    public void setSeatIds(List<Long> seatIds) { this.seatIds = seatIds; }

    public String getGuestName() { return guestName; }
    public void setGuestName(String guestName) { this.guestName = guestName; }

    public String getGuestPhone() { return guestPhone; }
    public void setGuestPhone(String guestPhone) { this.guestPhone = guestPhone; }

    public String getGuestEmail() { return guestEmail; }
    public void setGuestEmail(String guestEmail) { this.guestEmail = guestEmail; }

    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }

    public String getVoucherCode() { return voucherCode; }
    public void setVoucherCode(String voucherCode) { this.voucherCode = voucherCode; }

    public List<BookingFoodItemRequestDto> getFoodItems() { return foodItems; }
    public void setFoodItems(List<BookingFoodItemRequestDto> foodItems) { this.foodItems = foodItems; }

    public List<BookingComboRequestDto> getCombos() { return combos; }
    public void setCombos(List<BookingComboRequestDto> combos) { this.combos = combos; }
}
