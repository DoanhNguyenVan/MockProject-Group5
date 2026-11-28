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
        if (amount != null && amount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Order amount cannot be negative.");
        }
        List<Promotion> promos = promotionService.getAvailablePromotions(amount);
        return ResponseEntity.ok(PromotionMapper.toVoucherMapList(promos));
    }

    @GetMapping("/my")
    public ResponseEntity<?> getMyVouchers() {
        Long userId = SecurityContextUtils.resolveCurrentUserId();
        if (userId == null) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.UNAUTHORIZED)
                    .body(Map.of("success", false, "errorCode", "ACCOUNT_NOT_AUTHENTICATED", "message", "Please sign in to view your vouchers."));
        }
        String email = null;
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof com.boxcinema.common.security.BoxCinemaUserDetails details
                && details.getUserSummary() != null) {
            email = details.getUserSummary().getEmail();
        }
        List<Promotion> promos = promotionService.getMyAvailablePromotions(userId, email);
        return ResponseEntity.ok(PromotionMapper.toVoucherMapList(promos));
    }

    @PostMapping("/validate")
    public ResponseEntity<Map<String, Object>> validate(@RequestBody Map<String, Object> body) {
        String code = body != null && body.containsKey("voucherCode") ? String.valueOf(body.get("voucherCode")) : "";
        BigDecimal amount = BigDecimal.ZERO;
        try {
            if (body != null && body.get("orderAmount") != null) {
                amount = new BigDecimal(String.valueOf(body.get("orderAmount")));
            }
        } catch (NumberFormatException ex) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid order amount."));
        }
        if (amount.compareTo(BigDecimal.ZERO) < 0) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Order amount cannot be negative."));
        }

        Long userId = SecurityContextUtils.resolveCurrentUserId();
        String email = null;
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof com.boxcinema.common.security.BoxCinemaUserDetails details
                && details.getUserSummary() != null) {
            email = details.getUserSummary().getEmail();
        }
        if ((email == null || email.isBlank()) && body != null) {
            email = body.containsKey("email") && body.get("email") != null
                    ? String.valueOf(body.get("email")).trim()
                    : (body.containsKey("guestEmail") && body.get("guestEmail") != null
                    ? String.valueOf(body.get("guestEmail")).trim() : null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            BigDecimal discount = promotionService.validateAndCalculateDiscount(code, amount, userId, email);
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
