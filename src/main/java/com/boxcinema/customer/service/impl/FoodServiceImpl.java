package com.boxcinema.customer.service.impl;

import com.boxcinema.common.dto.*;
import com.boxcinema.customer.dto.request.*;
import com.boxcinema.customer.dto.response.*;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.customer.service.FoodService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class FoodServiceImpl implements FoodService {

    private final FoodCategoryRepository categoryRepository;
    private final FoodItemRepository itemRepository;
    private final FoodVariantRepository variantRepository;
    private final FoodComboRepository comboRepository;
    private final FoodComboItemRepository comboItemRepository;
    private final FoodInventoryRepository inventoryRepository;

    public FoodServiceImpl(
            FoodCategoryRepository categoryRepository,
            FoodItemRepository itemRepository,
            FoodVariantRepository variantRepository,
            FoodComboRepository comboRepository,
            FoodComboItemRepository comboItemRepository,
            FoodInventoryRepository inventoryRepository
    ) {
        this.categoryRepository = categoryRepository;
        this.itemRepository = itemRepository;
        this.variantRepository = variantRepository;
        this.comboRepository = comboRepository;
        this.comboItemRepository = comboItemRepository;
        this.inventoryRepository = inventoryRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FoodCategoryDto> getActiveCategories() {
        return categoryRepository.findByStatusOrderByIdAsc("ACTIVE").stream()
                .map(c -> new FoodCategoryDto(c.getId(), c.getName(), c.getDescription(), c.getStatus()))
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<FoodItemDto> getActiveFoodItems(Long categoryId) {
        List<FoodItem> items;
        if (categoryId != null && categoryId > 0) {
            items = itemRepository.findByCategory_IdAndStatusAndIsAvailableTrueOrderByIdAsc(categoryId, "ACTIVE");
        } else {
            items = itemRepository.findAllActiveWithVariants();
        }

        List<FoodItemDto> result = new ArrayList<>();
        for (FoodItem item : items) {
            result.add(mapToItemDto(item));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public FoodItemDto getFoodItemById(Long id) {
        FoodItem item = itemRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Food item not found with id: " + id));
        return mapToItemDto(item);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FoodComboDto> getActiveCombos() {
        return comboRepository.findAllActiveWithItems().stream()
                .map(this::mapToComboDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public FoodComboDto getComboById(Long id) {
        FoodCombo combo = comboRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Food combo not found with id: " + id));
        return mapToComboDto(combo);
    }

    @Override
    public void checkAndReserveFoodStock(Long foodItemId, int quantity) {
        if (quantity <= 0) return;
        FoodInventory inventory = inventoryRepository.findByFoodItemIdForUpdate(foodItemId)
                .orElseGet(() -> {
                    FoodItem item = itemRepository.findById(foodItemId).orElseThrow();
                    return inventoryRepository.save(new FoodInventory(item, 200, 0));
                });

        if (inventory.getAvailableQuantity() < quantity) {
            throw new IllegalStateException("Product '" + inventory.getFoodItem().getName() + "' is out of stock or insufficient quantity.");
        }
        inventory.setReservedQuantity(inventory.getReservedQuantity() + quantity);
        inventoryRepository.save(inventory);
    }

    @Override
    public void checkAndReserveComboStock(Long comboId, int comboQuantity) {
        if (comboQuantity <= 0) return;
        List<FoodComboItem> items = comboItemRepository.findByCombo_Id(comboId);
        for (FoodComboItem ci : items) {
            int needed = ci.getQuantity() * comboQuantity;
            checkAndReserveFoodStock(ci.getFoodItem().getId(), needed);
        }
    }

    @Override
    public void commitFoodStock(Long foodItemId, int quantity) {
        if (quantity <= 0) return;
        inventoryRepository.findByFoodItemIdForUpdate(foodItemId).ifPresent(inv -> {
            inv.setReservedQuantity(Math.max(0, inv.getReservedQuantity() - quantity));
            inv.setOnHandQuantity(Math.max(0, inv.getOnHandQuantity() - quantity));
            inventoryRepository.save(inv);
        });
    }

    @Override
    public void commitComboStock(Long comboId, int comboQuantity) {
        if (comboQuantity <= 0) return;
        List<FoodComboItem> items = comboItemRepository.findByCombo_Id(comboId);
        for (FoodComboItem ci : items) {
            commitFoodStock(ci.getFoodItem().getId(), ci.getQuantity() * comboQuantity);
        }
    }

    @Override
    public void releaseFoodStock(Long foodItemId, int quantity) {
        if (quantity <= 0) return;
        inventoryRepository.findByFoodItemIdForUpdate(foodItemId).ifPresent(inv -> {
            inv.setReservedQuantity(Math.max(0, inv.getReservedQuantity() - quantity));
            inventoryRepository.save(inv);
        });
    }

    @Override
    public void releaseComboStock(Long comboId, int comboQuantity) {
        if (comboQuantity <= 0) return;
        List<FoodComboItem> items = comboItemRepository.findByCombo_Id(comboId);
        for (FoodComboItem ci : items) {
            releaseFoodStock(ci.getFoodItem().getId(), ci.getQuantity() * comboQuantity);
        }
    }

    private FoodItemDto mapToItemDto(FoodItem item) {
        FoodItemDto dto = new FoodItemDto();
        dto.setId(item.getId());
        dto.setCategoryId(item.getCategory() != null ? item.getCategory().getId() : null);
        dto.setCategoryName(item.getCategory() != null ? item.getCategory().getName() : "");
        dto.setName(item.getName());
        dto.setDescription(item.getDescription());
        dto.setPrice(item.getPrice());
        dto.setImageUrl(item.getImageUrl());
        dto.setAvailable(item.isAvailable());

        inventoryRepository.findByFoodItem_Id(item.getId()).ifPresent(inv -> {
            dto.setAvailableQuantity(inv.getAvailableQuantity());
        });

        if (item.getVariants() != null) {
            for (FoodVariant v : item.getVariants()) {
                if ("ACTIVE".equalsIgnoreCase(v.getStatus())) {
                    dto.getVariants().add(new FoodVariantDto(v.getId(), item.getId(), v.getName(), v.getPrice(), v.getStatus()));
                }
            }
        }
        return dto;
    }

    private FoodComboDto mapToComboDto(FoodCombo combo) {
        FoodComboDto dto = new FoodComboDto();
        dto.setId(combo.getId());
        dto.setName(combo.getName());
        dto.setDescription(combo.getDescription());
        dto.setPrice(combo.getPrice());
        dto.setImageUrl(combo.getImageUrl());
        dto.setAvailable(combo.isAvailable());

        if (combo.getComboItems() != null) {
            for (FoodComboItem ci : combo.getComboItems()) {
                FoodComboItemDto ciDto = new FoodComboItemDto();
                ciDto.setId(ci.getId());
                ciDto.setFoodItemId(ci.getFoodItem().getId());
                ciDto.setFoodItemName(ci.getFoodItem().getName());
                if (ci.getVariant() != null) {
                    ciDto.setVariantId(ci.getVariant().getId());
                    ciDto.setVariantName(ci.getVariant().getName());
                }
                ciDto.setQuantity(ci.getQuantity());
                dto.getItems().add(ciDto);
            }
        }
        return dto;
    }
}
