package com.boxcinema.common.mapper;

import com.boxcinema.common.entity.Promotion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mapper for Promotion / Voucher entities to API response payloads.
 */
public final class PromotionMapper {

    private PromotionMapper() {
    }

    public static Map<String, Object> toVoucherMap(Promotion promotion) {
        if (promotion == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("promotionId", promotion.getPromotionId());
        m.put("promotionName", promotion.getPromotionName());
        m.put("voucherCode", promotion.getVoucherCode());
        m.put("discountType", promotion.getDiscountType());
        m.put("discountValue", promotion.getDiscountValue());
        m.put("maxDiscount", promotion.getMaxDiscount());
        m.put("minOrderAmount", promotion.getMinOrderAmount());
        m.put("endAt", promotion.getEndAt() != null ? promotion.getEndAt().toString() : null);
        return m;
    }

    public static List<Map<String, Object>> toVoucherMapList(List<Promotion> promotions) {
        if (promotions == null) {
            return List.of();
        }
        return promotions.stream()
                .map(PromotionMapper::toVoucherMap)
                .collect(Collectors.toList());
    }
}
