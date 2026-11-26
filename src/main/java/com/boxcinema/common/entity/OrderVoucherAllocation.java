package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Child row distributing the single order voucher's actual_discount across order items
 * using integer VND Largest Remainder Method with deterministic ticket_id tie-breaker.
 */
@Entity
@Table(
        name = "order_voucher_allocations",
        uniqueConstraints = @UniqueConstraint(name = "uk_ova_redemption_item", columnNames = {"redemption_id", "item_ref_key"})
)
public class OrderVoucherAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "redemption_id", nullable = false)
    private OrderVoucherRedemption redemption;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /** TICKET, SEAT, FOOD_ITEM, COMBO_COMPONENT */
    @Column(name = "item_kind", nullable = false, length = 30)
    private String itemKind;

    @Column(name = "item_ref_key", nullable = false, length = 80)
    private String itemRefKey;

    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(name = "booking_seat_id")
    private Long bookingSeatId;

    @Column(name = "booking_food_item_id")
    private Long bookingFoodItemId;

    @Column(name = "booking_food_item_component_id")
    private Long bookingFoodItemComponentId;

    @Column(name = "gross_amount", nullable = false)
    private Long grossAmount = 0L;

    @Column(name = "eligible_amount", nullable = false)
    private Long eligibleAmount = 0L;

    @Column(name = "allocated_discount", nullable = false)
    private Long allocatedDiscount = 0L;

    @Column(name = "net_paid_amount", nullable = false)
    private Long netPaidAmount = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public OrderVoucherAllocation() {}

    @PrePersist
    public void onCreate() {
        if (this.createdAt == null) this.createdAt = LocalDateTime.now();
        if (this.grossAmount == null) this.grossAmount = 0L;
        if (this.eligibleAmount == null) this.eligibleAmount = 0L;
        if (this.allocatedDiscount == null) this.allocatedDiscount = 0L;
        if (this.netPaidAmount == null) this.netPaidAmount = 0L;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public OrderVoucherRedemption getRedemption() { return redemption; }
    public void setRedemption(OrderVoucherRedemption redemption) { this.redemption = redemption; }

    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }

    public String getItemKind() { return itemKind; }
    public void setItemKind(String itemKind) { this.itemKind = itemKind; }

    public String getItemRefKey() { return itemRefKey; }
    public void setItemRefKey(String itemRefKey) { this.itemRefKey = itemRefKey; }

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }

    public Long getBookingSeatId() { return bookingSeatId; }
    public void setBookingSeatId(Long bookingSeatId) { this.bookingSeatId = bookingSeatId; }

    public Long getBookingFoodItemId() { return bookingFoodItemId; }
    public void setBookingFoodItemId(Long bookingFoodItemId) { this.bookingFoodItemId = bookingFoodItemId; }

    public Long getBookingFoodItemComponentId() { return bookingFoodItemComponentId; }
    public void setBookingFoodItemComponentId(Long bookingFoodItemComponentId) { this.bookingFoodItemComponentId = bookingFoodItemComponentId; }

    public Long getGrossAmount() { return grossAmount; }
    public void setGrossAmount(Long grossAmount) { this.grossAmount = grossAmount != null ? grossAmount : 0L; }

    public Long getEligibleAmount() { return eligibleAmount; }
    public void setEligibleAmount(Long eligibleAmount) { this.eligibleAmount = eligibleAmount != null ? eligibleAmount : 0L; }

    public Long getAllocatedDiscount() { return allocatedDiscount; }
    public void setAllocatedDiscount(Long allocatedDiscount) { this.allocatedDiscount = allocatedDiscount != null ? allocatedDiscount : 0L; }

    public Long getNetPaidAmount() { return netPaidAmount; }
    public void setNetPaidAmount(Long netPaidAmount) { this.netPaidAmount = netPaidAmount != null ? netPaidAmount : 0L; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
