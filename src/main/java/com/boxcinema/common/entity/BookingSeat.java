package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "booking_seats")
public class BookingSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "booking_seat_id")
    private Long bookingSeatId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "showtime_seat_id", nullable = false)
    private Long showtimeSeatId;

    @Column(name = "unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPrice;

    @Column(name = "seat_label_snapshot", nullable = false, length = 20)
    private String seatLabelSnapshot;

    @Column(nullable = false, length = 20)
    private String status = "HOLDING";

    @Column(name = "active_seat_key")
    private Long activeSeatKey;

    public BookingSeat() {}

    public BookingSeat(Booking booking, Long showtimeSeatId, BigDecimal unitPrice, String seatLabelSnapshot, String status) {
        this.booking = booking;
        this.showtimeSeatId = showtimeSeatId;
        this.unitPrice = unitPrice;
        this.seatLabelSnapshot = seatLabelSnapshot;
        this.status = status;
    }

    public Long getBookingSeatId() { return bookingSeatId; }
    public void setBookingSeatId(Long bookingSeatId) { this.bookingSeatId = bookingSeatId; }

    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }

    public Long getShowtimeSeatId() { return showtimeSeatId; }
    public void setShowtimeSeatId(Long showtimeSeatId) { this.showtimeSeatId = showtimeSeatId; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }

    public String getSeatLabelSnapshot() { return seatLabelSnapshot; }
    public void setSeatLabelSnapshot(String seatLabelSnapshot) { this.seatLabelSnapshot = seatLabelSnapshot; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getActiveSeatKey() { return activeSeatKey; }
    public void setActiveSeatKey(Long activeSeatKey) { this.activeSeatKey = activeSeatKey; }
}
