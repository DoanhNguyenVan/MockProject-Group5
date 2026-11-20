package com.boxcinema.common.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "food_inventory")
public class FoodInventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "food_item_id", nullable = false, unique = true)
    private FoodItem foodItem;

    @Column(name = "on_hand_quantity", nullable = false)
    private int onHandQuantity = 100;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity = 0;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    public FoodInventory() {}

    public FoodInventory(FoodItem foodItem, int onHandQuantity, int reservedQuantity) {
        this.foodItem = foodItem;
        this.onHandQuantity = onHandQuantity;
        this.reservedQuantity = reservedQuantity;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public int getAvailableQuantity() {
        return Math.max(0, onHandQuantity - reservedQuantity);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public FoodItem getFoodItem() { return foodItem; }
    public void setFoodItem(FoodItem foodItem) { this.foodItem = foodItem; }

    public int getOnHandQuantity() { return onHandQuantity; }
    public void setOnHandQuantity(int onHandQuantity) { this.onHandQuantity = onHandQuantity; }

    public int getReservedQuantity() { return reservedQuantity; }
    public void setReservedQuantity(int reservedQuantity) { this.reservedQuantity = reservedQuantity; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
