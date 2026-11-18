package com.boxcinema.controller;

import com.boxcinema.entity.Promotion;
import com.boxcinema.security.BoxCinemaUserDetails;
import com.boxcinema.service.PromotionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Voucher endpoints called by the booking JS on the payment step.
 * Keeps the same REST-style pattern used throughout the project.
 */
@RestController
@RequestMapping("/api/vouchers")
public class VoucherApiController {

    private final PromotionService promotionService;

    public VoucherApiController(PromotionService promotionService) {
        this.promotionService = promotionService;
    }

    private Long resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()
                && auth.getPrincipal() instanceof BoxCinemaUserDetails details
                && details.getUserSummary() != null) {
            return details.getUserSummary().getUserId();
        }
        return null;
    }

    /**
     * GET /api/vouchers/available?amount=350000
     * Returns the list of vouchers the customer can apply for the given subtotal.
     */
    @GetMapping("/available")
    public ResponseEntity<List<Map<String, Object>>> getAvailable(
            @RequestParam(defaultValue = "0") BigDecimal amount) {
        List<Promotion> promos = promotionService.getAvailablePromotions(amount);
        List<Map<String, Object>> result = promos.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("promotionId", p.getPromotionId());
            m.put("promotionName", p.getPromotionName());
            m.put("voucherCode", p.getVoucherCode());
            m.put("discountType", p.getDiscountType());
            m.put("discountValue", p.getDiscountValue());
            m.put("maxDiscount", p.getMaxDiscount());
            m.put("minOrderAmount", p.getMinOrderAmount());
            m.put("endAt", p.getEndAt() != null ? p.getEndAt().toString() : null);
            return m;
        }).collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/vouchers/validate
     * Body: { "voucherCode": "BOXMIDWEEK", "orderAmount": 350000 }
     * Returns: { "success": true, "discountAmount": 50000, "voucherCode": "BOXMIDWEEK", "promotionName": "..." }
     */
    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, Object> body) {
        String code = body.containsKey("voucherCode") ? String.valueOf(body.get("voucherCode")) : "";
        BigDecimal amount = BigDecimal.ZERO;
        try {
            amount = new BigDecimal(String.valueOf(body.getOrDefault("orderAmount", "0")));
        } catch (NumberFormatException ignored) {}

        Long userId = resolveCurrentUserId();
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
