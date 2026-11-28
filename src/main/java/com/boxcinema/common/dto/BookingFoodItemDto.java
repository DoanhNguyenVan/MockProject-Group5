package com.boxcinema.common.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class BookingFoodItemDto {
    private Long id;
    private String itemType;
    private Long foodItemId;
    private Long variantId;
    private Long comboId;
    private String productName;
    private String variantName;
    private int quantity;
    private BigDecimal unitPrice;
    private BigDecimal subtotal;
    private BigDecimal discountAllocated;
    private BigDecimal netPaidAmount;
    private int deliveredQuantity;
    private int refundedQuantity;
    private int replacementAcceptedQuantity;
    private String deliveryStatus;
    private List<ComboComponentDto> components = new ArrayList<>();

    public static class ComboComponentDto {
        private Long id;
        private String componentName;
        private String variantName;
        private int totalQuantity;
        private int deliveredQuantity;
        private int refundedQuantity;
        private int replacementAcceptedQuantity;
        private String deliveryStatus;
        private BigDecimal allocatedSubtotal;
        private BigDecimal discountAllocated;
        private BigDecimal netPaidAmount;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public String getComponentName() { return componentName; }
        public void setComponentName(String componentName) { this.componentName = componentName; }
        public String getVariantName() { return variantName; }
        public void setVariantName(String variantName) { this.variantName = variantName; }
        public int getTotalQuantity() { return totalQuantity; }
        public void setTotalQuantity(int totalQuantity) { this.totalQuantity = totalQuantity; }
        public int getDeliveredQuantity() { return deliveredQuantity; }
        public void setDeliveredQuantity(int deliveredQuantity) { this.deliveredQuantity = deliveredQuantity; }
        public int getRefundedQuantity() { return refundedQuantity; }
        public void setRefundedQuantity(int refundedQuantity) { this.refundedQuantity = refundedQuantity; }
        public int getReplacementAcceptedQuantity() { return replacementAcceptedQuantity; }
        public void setReplacementAcceptedQuantity(int replacementAcceptedQuantity) { this.replacementAcceptedQuantity = replacementAcceptedQuantity; }
        public String getDeliveryStatus() { return deliveryStatus; }
        public void setDeliveryStatus(String deliveryStatus) { this.deliveryStatus = deliveryStatus; }
        public BigDecimal getAllocatedSubtotal() { return allocatedSubtotal; }
        public void setAllocatedSubtotal(BigDecimal allocatedSubtotal) { this.allocatedSubtotal = allocatedSubtotal; }
        public BigDecimal getDiscountAllocated() { return discountAllocated; }
        public void setDiscountAllocated(BigDecimal discountAllocated) { this.discountAllocated = discountAllocated; }
        public BigDecimal getNetPaidAmount() { return netPaidAmount; }
        public void setNetPaidAmount(BigDecimal netPaidAmount) { this.netPaidAmount = netPaidAmount; }
    }

    public BookingFoodItemDto() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public Long getFoodItemId() { return foodItemId; }
    public void setFoodItemId(Long foodItemId) { this.foodItemId = foodItemId; }
    public Long getVariantId() { return variantId; }
    public void setVariantId(Long variantId) { this.variantId = variantId; }
    public Long getComboId() { return comboId; }
    public void setComboId(Long comboId) { this.comboId = comboId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public String getVariantName() { return variantName; }
    public void setVariantName(String variantName) { this.variantName = variantName; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
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
    public List<ComboComponentDto> getComponents() { return components; }
    public void setComponents(List<ComboComponentDto> components) { this.components = components; }
}
