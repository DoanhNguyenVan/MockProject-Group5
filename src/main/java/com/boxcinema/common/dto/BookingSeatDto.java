package com.boxcinema.common.dto;

import java.math.BigDecimal;

public class BookingSeatDto {
    private Long bookingSeatId;
    private Long showtimeSeatId;
    private String seatLabel;
    private BigDecimal price;
    private String status;

    public BookingSeatDto() {}

    public BookingSeatDto(Long bookingSeatId, Long showtimeSeatId, String seatLabel, BigDecimal price, String status) {
        this.bookingSeatId = bookingSeatId;
        this.showtimeSeatId = showtimeSeatId;
        this.seatLabel = seatLabel;
        this.price = price;
        this.status = status;
    }

    public Long getBookingSeatId() { return bookingSeatId; }
    public void setBookingSeatId(Long bookingSeatId) { this.bookingSeatId = bookingSeatId; }
    public Long getShowtimeSeatId() { return showtimeSeatId; }
    public void setShowtimeSeatId(Long showtimeSeatId) { this.showtimeSeatId = showtimeSeatId; }
    public String getSeatLabel() { return seatLabel; }
    public void setSeatLabel(String seatLabel) { this.seatLabel = seatLabel; }
    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
