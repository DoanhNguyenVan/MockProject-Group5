package com.boxcinema.customer.controller;

import com.boxcinema.common.entity.Promotion;
import com.boxcinema.common.mapper.PromotionMapper;
import com.boxcinema.customer.service.PromotionService;
import com.boxcinema.common.util.SecurityContextUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Voucher endpoints called by the booking JS on the payment step.
 */
@RestController
@RequestMapping("/api/vouchers")
public class VoucherApiController {

    private final PromotionService promotionService;

    public VoucherApiController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    @GetMapping("/available")
    public ResponseEntity<List<Map<String, Object>>> getAvailable(
            @RequestParam(defaultValue = "0") BigDecimal amount) {
        List<Promotion> promos = promotionService.getAvailablePromotions(amount);
        return ResponseEntity.ok(PromotionMapper.toVoucherMapList(promos));
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, Object> body) {
        String code = body.containsKey("voucherCode") ? String.valueOf(body.get("voucherCode")) : "";
        BigDecimal amount = BigDecimal.ZERO;
        try {
            amount = new BigDecimal(String.valueOf(body.getOrDefault("orderAmount", "0")));
        } catch (NumberFormatException ignored) {
        }

        Long userId = SecurityContextUtils.resolveCurrentUserId();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            BigDecimal discount = promotionService.validateAndCalculateDiscount(code, amount, userId);
            Promotion promo = promotionService.findByVoucherCode(code);
            result.put("success", true);
            result.put("voucherCode", code.toUpperCase());
            result.put("promotionName", promo != null ? promo.getPromotionName() : "");
            result.put("discountAmount", discount);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.badRequest().body(result);
        }
    }
}
