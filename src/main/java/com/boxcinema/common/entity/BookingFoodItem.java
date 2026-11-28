package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "booking_food_item")
public class BookingFoodItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "item_type", nullable = false, length = 20)
    private String itemType; // "FOOD" or "COMBO"

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_item_id")
    private FoodItem foodItem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private FoodVariant variant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "combo_id")
    private FoodCombo combo;

    @Column(name = "product_name_snapshot", nullable = false, length = 150)
    private String productNameSnapshot;

    @Column(name = "variant_name_snapshot", length = 100)
    private String variantNameSnapshot;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_snapshot", nullable = false, precision = 12, scale = 2)
    private BigDecimal unitPriceSnapshot;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal subtotal;

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

    public BookingFoodItem() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }

    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }

    public FoodItem getFoodItem() { return foodItem; }
    public void setFoodItem(FoodItem foodItem) { this.foodItem = foodItem; }

    public FoodVariant getVariant() { return variant; }
    public void setVariant(FoodVariant variant) { this.variant = variant; }

    public FoodCombo getCombo() { return combo; }
    public void setCombo(FoodCombo combo) { this.combo = combo; }

    public String getProductNameSnapshot() { return productNameSnapshot; }
    public void setProductNameSnapshot(String productNameSnapshot) { this.productNameSnapshot = productNameSnapshot; }

    public String getVariantNameSnapshot() { return variantNameSnapshot; }
    public void setVariantNameSnapshot(String variantNameSnapshot) { this.variantNameSnapshot = variantNameSnapshot; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public BigDecimal getUnitPriceSnapshot() { return unitPriceSnapshot; }
    public void setUnitPriceSnapshot(BigDecimal unitPriceSnapshot) { this.unitPriceSnapshot = unitPriceSnapshot; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

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
}
