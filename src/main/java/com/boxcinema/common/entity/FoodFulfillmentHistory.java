package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Immutable append-only delivery history record. Never updated or deleted.
 */
@Entity
@Table(name = "food_fulfillment_history")
public class FoodFulfillmentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_fulfillment_id", nullable = false)
    private FoodFulfillment foodFulfillment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @Column(name = "booking_food_item_id")
    private Long bookingFoodItemId;

    @Column(name = "booking_food_item_component_id")
    private Long bookingFoodItemComponentId;

    @Column(name = "action_type", nullable = false, length = 40)
    private String actionType;

    @Column(name = "previous_status", length = 30)
    private String previousStatus;

    @Column(name = "new_status", nullable = false, length = 30)
    private String newStatus;

    @Column(name = "quantity_affected", nullable = false)
    private int quantityAffected = 0;

    @Column(name = "delivered_item_name_snapshot", length = 150)
    private String deliveredItemNameSnapshot;

    @Column(name = "wrong_item_description", length = 255)
    private String wrongItemDescription;

    @Column(name = "replacement_description", length = 255)
    private String replacementDescription;

    @Column(name = "customer_accepted_replacement")
    private Boolean customerAcceptedReplacement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id")
    private User staff;

    @Column(columnDefinition = "TEXT")
    private String note;

    @Column(name = "evidence_url", length = 500)
    private String evidenceUrl;

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

    public FoodFulfillment getFoodFulfillment() { return foodFulfillment; }
    public void setFoodFulfillment(FoodFulfillment foodFulfillment) { this.foodFulfillment = foodFulfillment; }

    public Booking getBooking() { return booking; }
    public void setBooking(Booking booking) { this.booking = booking; }

    public Long getBookingFoodItemId() { return bookingFoodItemId; }
    public void setBookingFoodItemId(Long bookingFoodItemId) { this.bookingFoodItemId = bookingFoodItemId; }

    public Long getBookingFoodItemComponentId() { return bookingFoodItemComponentId; }
    public void setBookingFoodItemComponentId(Long bookingFoodItemComponentId) { this.bookingFoodItemComponentId = bookingFoodItemComponentId; }

    public String getActionType() { return actionType; }
    public void setActionType(String actionType) { this.actionType = actionType; }

    public String getPreviousStatus() { return previousStatus; }
    public void setPreviousStatus(String previousStatus) { this.previousStatus = previousStatus; }

    public String getNewStatus() { return newStatus; }
    public void setNewStatus(String newStatus) { this.newStatus = newStatus; }

    public int getQuantityAffected() { return quantityAffected; }
    public void setQuantityAffected(int quantityAffected) { this.quantityAffected = quantityAffected; }

    public String getDeliveredItemNameSnapshot() { return deliveredItemNameSnapshot; }
    public void setDeliveredItemNameSnapshot(String deliveredItemNameSnapshot) { this.deliveredItemNameSnapshot = deliveredItemNameSnapshot; }

    public String getWrongItemDescription() { return wrongItemDescription; }
    public void setWrongItemDescription(String wrongItemDescription) { this.wrongItemDescription = wrongItemDescription; }

    public String getReplacementDescription() { return replacementDescription; }
    public void setReplacementDescription(String replacementDescription) { this.replacementDescription = replacementDescription; }

    public Boolean getCustomerAcceptedReplacement() { return customerAcceptedReplacement; }
    public void setCustomerAcceptedReplacement(Boolean customerAcceptedReplacement) { this.customerAcceptedReplacement = customerAcceptedReplacement; }

    public User getStaff() { return staff; }
    public void setStaff(User staff) { this.staff = staff; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public String getEvidenceUrl() { return evidenceUrl; }
    public void setEvidenceUrl(String evidenceUrl) { this.evidenceUrl = evidenceUrl; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
