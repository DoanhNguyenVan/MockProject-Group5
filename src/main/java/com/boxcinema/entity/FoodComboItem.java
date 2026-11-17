package com.boxcinema.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "food_combo_item")
public class FoodComboItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "combo_id", nullable = false)
    private FoodCombo combo;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "food_item_id", nullable = false)
    private FoodItem foodItem;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "variant_id")
    private FoodVariant variant;

    @Column(nullable = false)
    private int quantity = 1;

    public FoodComboItem() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public FoodCombo getCombo() { return combo; }
    public void setCombo(FoodCombo combo) { this.combo = combo; }

    public FoodItem getFoodItem() { return foodItem; }
    public void setFoodItem(FoodItem foodItem) { this.foodItem = foodItem; }

    public FoodVariant getVariant() { return variant; }
    public void setVariant(FoodVariant variant) { this.variant = variant; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
}
