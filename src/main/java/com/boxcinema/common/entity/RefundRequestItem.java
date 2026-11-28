package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "refund_request_items")
public class RefundRequestItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_request_id", nullable = false)
    private RefundRequest refundRequest;

    @Column(name = "item_kind", nullable = false, length = 30)
    private String itemKind; // TICKET, FOOD_ITEM, COMBO_COMPONENT

    @Column(name = "ticket_id")
    private Long ticketId;

    @Column(name = "booking_seat_id")
    private Long bookingSeatId;

    @Column(name = "booking_food_item_id")
    private Long bookingFoodItemId;

    @Column(name = "booking_food_item_component_id")
    private Long bookingFoodItemComponentId;

    @Column(name = "item_label_snapshot", nullable = false, length = 200)
    private String itemLabelSnapshot;

    @Column(name = "purchased_quantity", nullable = false)
    private int purchasedQuantity = 1;

    @Column(name = "requested_quantity", nullable = false)
    private int requestedQuantity = 1;

    @Column(name = "approved_quantity", nullable = false)
    private int approvedQuantity = 0;

    @Column(name = "gross_unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal grossUnitPrice = BigDecimal.ZERO;

    @Column(name = "discount_allocated_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAllocatedAmount = BigDecimal.ZERO;

    @Column(name = "net_paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal netPaidAmount = BigDecimal.ZERO;

    @Column(name = "approved_cash_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal approvedCashAmount = BigDecimal.ZERO;

    @Column(name = "approved_voucher_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal approvedVoucherAmount = BigDecimal.ZERO;

    @Column(name = "item_status", nullable = false, length = 30)
    private String itemStatus = "PENDING"; // PENDING, MORE_INFO_REQUIRED, APPROVED, REJECTED

    @Column(name = "decision_reason", columnDefinition = "TEXT")
    private String decisionReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_by")
    private User decidedBy;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @Column(name = "active_lock_key", unique = true, length = 100)
    private String activeLockKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) this.createdAt = now;
        if (this.updatedAt == null) this.updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public RefundRequest getRefundRequest() { return refundRequest; }
    public void setRefundRequest(RefundRequest refundRequest) { this.refundRequest = refundRequest; }

    public String getItemKind() { return itemKind; }
    public void setItemKind(String itemKind) { this.itemKind = itemKind; }

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }

    public Long getBookingSeatId() { return bookingSeatId; }
    public void setBookingSeatId(Long bookingSeatId) { this.bookingSeatId = bookingSeatId; }

    public Long getBookingFoodItemId() { return bookingFoodItemId; }
    public void setBookingFoodItemId(Long bookingFoodItemId) { this.bookingFoodItemId = bookingFoodItemId; }

    public Long getBookingFoodItemComponentId() { return bookingFoodItemComponentId; }
    public void setBookingFoodItemComponentId(Long bookingFoodItemComponentId) { this.bookingFoodItemComponentId = bookingFoodItemComponentId; }

    public String getItemLabelSnapshot() { return itemLabelSnapshot; }
    public void setItemLabelSnapshot(String itemLabelSnapshot) { this.itemLabelSnapshot = itemLabelSnapshot; }

    public int getPurchasedQuantity() { return purchasedQuantity; }
    public void setPurchasedQuantity(int purchasedQuantity) { this.purchasedQuantity = purchasedQuantity; }

    public int getRequestedQuantity() { return requestedQuantity; }
    public void setRequestedQuantity(int requestedQuantity) { this.requestedQuantity = requestedQuantity; }

    public int getApprovedQuantity() { return approvedQuantity; }
    public void setApprovedQuantity(int approvedQuantity) { this.approvedQuantity = approvedQuantity; }

    public BigDecimal getGrossUnitPrice() { return grossUnitPrice; }
    public void setGrossUnitPrice(BigDecimal grossUnitPrice) { this.grossUnitPrice = grossUnitPrice; }

    public BigDecimal getDiscountAllocatedAmount() { return discountAllocatedAmount; }
    public void setDiscountAllocatedAmount(BigDecimal discountAllocatedAmount) { this.discountAllocatedAmount = discountAllocatedAmount; }

    public BigDecimal getNetPaidAmount() { return netPaidAmount; }
    public void setNetPaidAmount(BigDecimal netPaidAmount) { this.netPaidAmount = netPaidAmount; }

    public BigDecimal getApprovedCashAmount() { return approvedCashAmount; }
    public void setApprovedCashAmount(BigDecimal approvedCashAmount) { this.approvedCashAmount = approvedCashAmount; }

    public BigDecimal getApprovedVoucherAmount() { return approvedVoucherAmount; }
    public void setApprovedVoucherAmount(BigDecimal approvedVoucherAmount) { this.approvedVoucherAmount = approvedVoucherAmount; }

    public String getItemStatus() { return itemStatus; }
    public void setItemStatus(String itemStatus) { this.itemStatus = itemStatus; }

    public String getDecisionReason() { return decisionReason; }
    public void setDecisionReason(String decisionReason) { this.decisionReason = decisionReason; }

    public User getDecidedBy() { return decidedBy; }
    public void setDecidedBy(User decidedBy) { this.decidedBy = decidedBy; }

    public LocalDateTime getDecidedAt() { return decidedAt; }
    public void setDecidedAt(LocalDateTime decidedAt) { this.decidedAt = decidedAt; }

    public String getActiveLockKey() { return activeLockKey; }
    public void setActiveLockKey(String activeLockKey) { this.activeLockKey = activeLockKey; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
