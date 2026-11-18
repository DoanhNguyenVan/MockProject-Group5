package com.boxcinema.dto;

import java.util.ArrayList;
import java.util.List;

public class BookingCreateRequestDto {
    private Long showtimeId;
    private List<Long> seatIds = new ArrayList<>();
    private String holdSessionId;
    private String guestName;
    private String guestEmail;
    private String guestPhone;
    private List<BookingFoodItemRequestDto> foodItems = new ArrayList<>();
    private List<BookingComboRequestDto> combos = new ArrayList<>();
    private String voucherCode;

    public BookingCreateRequestDto() {}

    public Long getShowtimeId() { return showtimeId; }
    public void setShowtimeId(Long showtimeId) { this.showtimeId = showtimeId; }
    public List<Long> getSeatIds() { return seatIds; }
    public void setSeatIds(List<Long> seatIds) { this.seatIds = seatIds; }
    public String getHoldSessionId() { return holdSessionId; }
    public void setHoldSessionId(String holdSessionId) { this.holdSessionId = holdSessionId; }
    public String getGuestName() { return guestName; }
    public void setGuestName(String guestName) { this.guestName = guestName; }
    public String getGuestEmail() { return guestEmail; }
    public void setGuestEmail(String guestEmail) { this.guestEmail = guestEmail; }
    public String getGuestPhone() { return guestPhone; }
    public void setGuestPhone(String guestPhone) { this.guestPhone = guestPhone; }
    public List<BookingFoodItemRequestDto> getFoodItems() { return foodItems; }
    public void setFoodItems(List<BookingFoodItemRequestDto> foodItems) { this.foodItems = foodItems; }
    public List<BookingComboRequestDto> getCombos() { return combos; }
    public void setCombos(List<BookingComboRequestDto> combos) { this.combos = combos; }
    public String getVoucherCode() { return voucherCode; }
    public void setVoucherCode(String voucherCode) { this.voucherCode = voucherCode; }
}
