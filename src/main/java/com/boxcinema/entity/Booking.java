package com.boxcinema.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bookings")
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "booking_code", nullable = false, unique = true, length = 40)
    private String bookingCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "showtime_id", nullable = false)
    private Showtime showtime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sold_by_staff_id")
    private User soldByStaff;

    @Column(name = "booking_channel", nullable = false, length = 20)
    private String bookingChannel = "ONLINE";

    @Column(name = "guest_name", length = 100)
    private String guestName;

    @Column(name = "guest_email", length = 255)
    private String guestEmail;

    @Column(name = "guest_phone", length = 20)
    private String guestPhone;

    @Column(name = "ticket_subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal ticketSubtotal = BigDecimal.ZERO;

    @Column(name = "food_subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal foodSubtotal = BigDecimal.ZERO;

    @Column(name = "subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "service_fee", nullable = false, precision = 12, scale = 2)
    private BigDecimal serviceFee = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "booking_status", nullable = false, length = 20)
    private String bookingStatus = "DRAFT";

    @Column(name = "payment_deadline")
    private LocalDateTime paymentDeadline;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "booked_at", nullable = false, updatable = false)
    private LocalDateTime bookedAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingSeat> bookingSeats = new ArrayList<>();

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingFoodItem> bookingFoodItems = new ArrayList<>();

    public Booking() {}

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void recalculateTotals() {
        BigDecimal tSub = BigDecimal.ZERO;
        if (bookingSeats != null) {
            for (BookingSeat bs : bookingSeats) {
                if (bs.getUnitPrice() != null) {
                    tSub = tSub.add(bs.getUnitPrice());
                }
            }
        }
        this.ticketSubtotal = tSub;

        BigDecimal fSub = BigDecimal.ZERO;
        if (bookingFoodItems != null) {
            for (BookingFoodItem bfi : bookingFoodItems) {
                if (bfi.getSubtotal() != null) {
                    fSub = fSub.add(bfi.getSubtotal());
                }
            }
        }
        this.foodSubtotal = fSub;
        this.subtotal = ticketSubtotal.add(foodSubtotal);

        BigDecimal disc = discountAmount != null ? discountAmount : BigDecimal.ZERO;
        BigDecimal fee = serviceFee != null ? serviceFee : BigDecimal.ZERO;

        BigDecimal grand = subtotal.subtract(disc).add(fee);
        if (grand.compareTo(BigDecimal.ZERO) < 0) {
            grand = BigDecimal.ZERO;
        }
        this.totalAmount = grand;
    }

    // Getters and Setters
    public Long getBookingId() { return bookingId; }
    public void setBookingId(Long bookingId) { this.bookingId = bookingId; }

    public String getBookingCode() { return bookingCode; }
    public void setBookingCode(String bookingCode) { this.bookingCode = bookingCode; }

    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }

    public Showtime getShowtime() { return showtime; }
    public void setShowtime(Showtime showtime) { this.showtime = showtime; }

    public User getSoldByStaff() { return soldByStaff; }
    public void setSoldByStaff(User soldByStaff) { this.soldByStaff = soldByStaff; }

    public String getBookingChannel() { return bookingChannel; }
    public void setBookingChannel(String bookingChannel) { this.bookingChannel = bookingChannel; }

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

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    public BigDecimal getDiscountAmount() { return discountAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }

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

    public LocalDateTime getBookedAt() { return bookedAt; }
    public void setBookedAt(LocalDateTime bookedAt) { this.bookedAt = bookedAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(LocalDateTime cancelledAt) { this.cancelledAt = cancelledAt; }

    public List<BookingSeat> getBookingSeats() { return bookingSeats; }
    public void setBookingSeats(List<BookingSeat> bookingSeats) { this.bookingSeats = bookingSeats; }

    public List<BookingFoodItem> getBookingFoodItems() { return bookingFoodItems; }
    public void setBookingFoodItems(List<BookingFoodItem> bookingFoodItems) { this.bookingFoodItems = bookingFoodItems; }
}
