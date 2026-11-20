package com.boxcinema.customer.service;

import com.boxcinema.common.entity.Promotion;
import com.boxcinema.common.repository.PromotionRepository;
import com.boxcinema.customer.service.impl.PromotionServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PromotionServiceTest {

    private PromotionRepository promotionRepository;
    private PromotionService promotionService;

    @BeforeEach
    void setUp() {
        promotionRepository = mock(PromotionRepository.class);
        promotionService = new PromotionServiceImpl(promotionRepository);
    }

    @Test
    void testValidateAndCalculateDiscount_FixedAmount_Success() {
        Promotion promo = new Promotion();
        promo.setPromotionId(1L);
        promo.setVoucherCode("BOX20K");
        promo.setStatus("ACTIVE");
        promo.setDiscountType("FIXED");
        promo.setDiscountValue(BigDecimal.valueOf(20000));
        promo.setMinOrderAmount(BigDecimal.valueOf(100000));
        promo.setStartAt(LocalDateTime.now().minusDays(1));
        promo.setEndAt(LocalDateTime.now().plusDays(1));

        when(promotionRepository.findByVoucherCodeIgnoreCase("BOX20K")).thenReturn(Optional.of(promo));

        BigDecimal discount = promotionService.validateAndCalculateDiscount("BOX20K", BigDecimal.valueOf(150000), 1L);
        assertEquals(0, BigDecimal.valueOf(20000).compareTo(discount));
    }

    @Test
    void testValidateAndCalculateDiscount_PercentWithCap_Success() {
        Promotion promo = new Promotion();
        promo.setPromotionId(2L);
        promo.setVoucherCode("BOXMIDWEEK");
        promo.setStatus("ACTIVE");
        promo.setDiscountType("PERCENT");
        promo.setDiscountValue(BigDecimal.valueOf(15)); // 15%
        promo.setMaxDiscount(BigDecimal.valueOf(50000)); // Cap at 50,000 VND
        promo.setMinOrderAmount(BigDecimal.valueOf(100000));

        when(promotionRepository.findByVoucherCodeIgnoreCase("BOXMIDWEEK")).thenReturn(Optional.of(promo));

        // 15% of 400,000 = 60,000 -> should cap at 50,000
        BigDecimal discount = promotionService.validateAndCalculateDiscount("BOXMIDWEEK", BigDecimal.valueOf(400000), 1L);
        assertEquals(0, BigDecimal.valueOf(50000).compareTo(discount));
    }

    @Test
    void testValidateAndCalculateDiscount_MinOrderNotMet_ThrowsException() {
        Promotion promo = new Promotion();
        promo.setPromotionId(1L);
        promo.setVoucherCode("BOX20K");
        promo.setStatus("ACTIVE");
        promo.setDiscountType("FIXED");
        promo.setDiscountValue(BigDecimal.valueOf(20000));
        promo.setMinOrderAmount(BigDecimal.valueOf(200000));

        when(promotionRepository.findByVoucherCodeIgnoreCase("BOX20K")).thenReturn(Optional.of(promo));

        assertThrows(IllegalArgumentException.class, () ->
                promotionService.validateAndCalculateDiscount("BOX20K", BigDecimal.valueOf(150000), 1L)
        );
    }

    @Test
    void testValidateAndCalculateDiscount_Expired_ThrowsException() {
        Promotion promo = new Promotion();
        promo.setPromotionId(1L);
        promo.setVoucherCode("EXPIRED");
        promo.setStatus("ACTIVE");
        promo.setEndAt(LocalDateTime.now().minusDays(1)); // Expired yesterday

        when(promotionRepository.findByVoucherCodeIgnoreCase("EXPIRED")).thenReturn(Optional.of(promo));

        assertThrows(IllegalArgumentException.class, () ->
                promotionService.validateAndCalculateDiscount("EXPIRED", BigDecimal.valueOf(150000), 1L)
        );
    }
}
