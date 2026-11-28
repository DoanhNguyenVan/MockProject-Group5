package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "tickets")
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "ticket_id")
    private Long ticketId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    @Column(name = "booking_seat_id", unique = true)
    private Long bookingSeatId;

    @Column(name = "showtime_id")
    private Long showtimeId;

    @Column(name = "seat_id")
    private Long seatId;

    @Column(name = "ticket_code", nullable = false, unique = true, length = 50)
    private String ticketCode;

    @Column(name = "qr_token_hash", nullable = false, unique = true, length = 255)
    private String qrTokenHash;

    @Column(name = "price", nullable = false, precision = 12, scale = 2)
    private BigDecimal price = BigDecimal.ZERO;

    @Column(name = "gross_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossPrice = BigDecimal.ZERO;

    @Column(name = "discount_allocated", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAllocated = BigDecimal.ZERO;

    @Column(name = "net_paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal netPaidAmount = BigDecimal.ZERO;

    @Column(name = "ticket_status", nullable = false, length = 20)
    private String ticketStatus = "VALID";

    @Column(name = "refund_status", nullable = false, length = 30)
    private String refundStatus = "NONE";

    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt = LocalDateTime.now();

    @Column(name = "checked_in_at")
    private LocalDateTime checkedInAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "checked_in_by")
    private User checkedInBy;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    public Ticket() {}

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }

    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }

    public Long getBookingSeatId() { return bookingSeatId; }
    public void setBookingSeatId(Long bookingSeatId) { this.bookingSeatId = bookingSeatId; }

    public Long getShowtimeId() { return showtimeId; }
    public void setShowtimeId(Long showtimeId) { this.showtimeId = showtimeId; }

    public Long getSeatId() { return seatId; }
    public void setSeatId(Long seatId) { this.seatId = seatId; }

    public String getTicketCode() { return ticketCode; }
    public void setTicketCode(String ticketCode) { this.ticketCode = ticketCode; }

    public String getQrTokenHash() { return qrTokenHash; }
    public void setQrTokenHash(String qrTokenHash) { this.qrTokenHash = qrTokenHash; }

    public BigDecimal getPrice() { return price; }
    public void setPrice(BigDecimal price) { this.price = price; }

    public BigDecimal getGrossPrice() { return grossPrice; }
    public void setGrossPrice(BigDecimal grossPrice) { this.grossPrice = grossPrice; }

    public BigDecimal getDiscountAllocated() { return discountAllocated; }
    public void setDiscountAllocated(BigDecimal discountAllocated) { this.discountAllocated = discountAllocated; }

    public BigDecimal getNetPaidAmount() { return netPaidAmount; }
    public void setNetPaidAmount(BigDecimal netPaidAmount) { this.netPaidAmount = netPaidAmount; }

    public String getTicketStatus() { return ticketStatus; }
    public void setTicketStatus(String ticketStatus) { this.ticketStatus = ticketStatus; }

    public String getRefundStatus() { return refundStatus; }
    public void setRefundStatus(String refundStatus) { this.refundStatus = refundStatus; }

    public LocalDateTime getIssuedAt() { return issuedAt; }
    public void setIssuedAt(LocalDateTime issuedAt) { this.issuedAt = issuedAt; }

    public LocalDateTime getCheckedInAt() { return checkedInAt; }
    public void setCheckedInAt(LocalDateTime checkedInAt) { this.checkedInAt = checkedInAt; }

    public User getCheckedInBy() { return checkedInBy; }
    public void setCheckedInBy(User checkedInBy) { this.checkedInBy = checkedInBy; }

    public LocalDateTime getUsedAt() { return usedAt; }
    public void setUsedAt(LocalDateTime usedAt) { this.usedAt = usedAt; }
}
