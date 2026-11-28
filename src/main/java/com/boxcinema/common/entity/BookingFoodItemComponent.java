package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "booking_food_item_component")
public class BookingFoodItemComponent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_food_item_id", nullable = false)
    private BookingFoodItem bookingFoodItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_item_id")
    private FoodItem foodItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private FoodVariant variant;

    @Column(name = "food_combo_item_id")
    private Long foodComboItemId;

    @Column(name = "component_name_snapshot", nullable = false, length = 150)
    private String componentNameSnapshot;

    @Column(name = "variant_name_snapshot", length = 100)
    private String variantNameSnapshot;

    @Column(name = "quantity_per_combo", nullable = false)
    private int quantityPerCombo = 1;

    @Column(name = "total_quantity", nullable = false)
    private int totalQuantity;

    @Column(name = "catalog_unit_price_snapshot", nullable = false, precision = 12, scale = 2)
    private BigDecimal catalogUnitPriceSnapshot = BigDecimal.ZERO;

    @Column(name = "allocated_unit_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal allocatedUnitPrice = BigDecimal.ZERO;

    @Column(name = "allocated_subtotal", nullable = false, precision = 12, scale = 2)
    private BigDecimal allocatedSubtotal = BigDecimal.ZERO;

    @Column(name = "discount_allocated", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAllocated = BigDecimal.ZERO;

    @Column(name = "net_paid_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal netPaidAmount = BigDecimal.ZERO;

    @Column(name = "delivered_quantity", nullable = false)
    private int deliveredQuantity = 0;

    @Column(name = "refunded_quantity", nullable = false)
    private int refundedQuantity = 0;

    @Column(name = "replacement_accepted_quantity", nullable = false)
    private int replacementAcceptedQuantity = 0;

    @Column(name = "delivery_status", nullable = false, length = 30)
    private String deliveryStatus = "PENDING";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @PrePersist
    public void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public BookingFoodItem getBookingFoodItem() { return bookingFoodItem; }
    public void setBookingFoodItem(BookingFoodItem bookingFoodItem) { this.bookingFoodItem = bookingFoodItem; }

    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }

    public FoodItem getFoodItem() { return foodItem; }
    public void setFoodItem(FoodItem foodItem) { this.foodItem = foodItem; }

    public FoodVariant getVariant() { return variant; }
    public void setVariant(FoodVariant variant) { this.variant = variant; }

    public Long getFoodComboItemId() { return foodComboItemId; }
    public void setFoodComboItemId(Long foodComboItemId) { this.foodComboItemId = foodComboItemId; }

    public String getComponentNameSnapshot() { return componentNameSnapshot; }
    public void setComponentNameSnapshot(String componentNameSnapshot) { this.componentNameSnapshot = componentNameSnapshot; }

    public String getVariantNameSnapshot() { return variantNameSnapshot; }
    public void setVariantNameSnapshot(String variantNameSnapshot) { this.variantNameSnapshot = variantNameSnapshot; }

    public int getQuantityPerCombo() { return quantityPerCombo; }
    public void setQuantityPerCombo(int quantityPerCombo) { this.quantityPerCombo = quantityPerCombo; }

    public int getTotalQuantity() { return totalQuantity; }
    public void setTotalQuantity(int totalQuantity) { this.totalQuantity = totalQuantity; }

    public BigDecimal getCatalogUnitPriceSnapshot() { return catalogUnitPriceSnapshot; }
    public void setCatalogUnitPriceSnapshot(BigDecimal catalogUnitPriceSnapshot) { this.catalogUnitPriceSnapshot = catalogUnitPriceSnapshot; }

    public BigDecimal getAllocatedUnitPrice() { return allocatedUnitPrice; }
    public void setAllocatedUnitPrice(BigDecimal allocatedUnitPrice) { this.allocatedUnitPrice = allocatedUnitPrice; }

    public BigDecimal getAllocatedSubtotal() { return allocatedSubtotal; }
    public void setAllocatedSubtotal(BigDecimal allocatedSubtotal) { this.allocatedSubtotal = allocatedSubtotal; }

    public BigDecimal getDiscountAllocated() { return discountAllocated; }
    public void setDiscountAllocated(BigDecimal discountAllocated) { this.discountAllocated = discountAllocated; }

    public BigDecimal getNetPaidAmount() { return netPaidAmount; }
    public void setNetPaidAmount(BigDecimal netPaidAmount) { this.netPaidAmount = netPaidAmount; }

    public int getDeliveredQuantity() { return deliveredQuantity; }
    public void setDeliveredQuantity(int deliveredQuantity) { this.deliveredQuantity = deliveredQuantity; }

    public int getRefundedQuantity() { return refundedQuantity; }
    public void setRefundedQuantity(int refundedQuantity) { this.refundedQuantity = refundedQuantity; }

    public int getReplacementAcceptedQuantity() { return replacementAcceptedQuantity; }
    public void setReplacementAcceptedQuantity(int replacementAcceptedQuantity) { this.replacementAcceptedQuantity = replacementAcceptedQuantity; }

    public String getDeliveryStatus() { return deliveryStatus; }
    public void setDeliveryStatus(String deliveryStatus) { this.deliveryStatus = deliveryStatus; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
