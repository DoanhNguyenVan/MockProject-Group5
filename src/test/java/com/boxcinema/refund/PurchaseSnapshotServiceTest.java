package com.boxcinema.refund;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.BookingFoodItemComponentRepository;
import com.boxcinema.common.repository.FoodComboItemRepository;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class PurchaseSnapshotServiceTest {

    private FoodComboItemRepository comboItemRepository;
    private BookingFoodItemComponentRepository componentRepository;
    private PurchaseSnapshotService snapshotService;

    @BeforeEach
    void setUp() {
        comboItemRepository = mock(FoodComboItemRepository.class);
        componentRepository = mock(BookingFoodItemComponentRepository.class);
        when(componentRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        snapshotService = new PurchaseSnapshotService(comboItemRepository, componentRepository);
    }

    @Test
    void referenceExample_TicketPopcornCoca10PercentVoucher_PopcornRefundYields45kCashAnd5kVoucher() {
        // Ticket 100k, Popcorn 50k, Coca 30k -> gross 180k -> 10% voucher = 18k discount -> net paid 162k
        Booking booking = new Booking();
        booking.setBookingId(1L);
        booking.setDiscountAmount(BigDecimal.valueOf(18000));
        booking.setServiceFee(BigDecimal.ZERO);

        BookingSeat seat = new BookingSeat(booking, 10L, BigDecimal.valueOf(100000), "A1", "BOOKED");
        seat.setBookingSeatId(101L);
        booking.getBookingSeats().add(seat);

        BookingFoodItem comboLine = new BookingFoodItem();
        comboLine.setId(201L);
        comboLine.setBooking(booking);
        comboLine.setItemType("COMBO");
        comboLine.setQuantity(1);
        comboLine.setUnitPriceSnapshot(BigDecimal.valueOf(80000));
        comboLine.setSubtotal(BigDecimal.valueOf(80000));
        FoodCombo combo = new FoodCombo();
        combo.setId(50L);
        comboLine.setCombo(combo);
        booking.getBookingFoodItems().add(comboLine);

        FoodItem popcorn = new FoodItem();
        popcorn.setId(1L);
        popcorn.setName("Butter Popcorn");
        popcorn.setPrice(BigDecimal.valueOf(50000));
        FoodComboItem ci1 = new FoodComboItem();
        ci1.setId(1L);
        ci1.setFoodItem(popcorn);
        ci1.setQuantity(1);

        FoodItem coca = new FoodItem();
        coca.setId(2L);
        coca.setName("Coca-Cola");
        coca.setPrice(BigDecimal.valueOf(30000));
        FoodComboItem ci2 = new FoodComboItem();
        ci2.setId(2L);
        ci2.setFoodItem(coca);
        ci2.setQuantity(1);

        when(comboItemRepository.findByCombo_Id(50L)).thenReturn(new java.util.ArrayList<>(List.of(ci1, ci2)));
        when(componentRepository.findByBooking_BookingId(1L)).thenReturn(List.of());

        Ticket ticket = new Ticket();
        ticket.setBookingSeatId(101L);
        ticket.setPrice(BigDecimal.valueOf(100000));

        List<BookingFoodItemComponent> builtComps = snapshotService.buildComboComponents(booking, comboLine);
        assertEquals(2, builtComps.size());
        builtComps.get(0).setId(301L);
        builtComps.get(1).setId(302L);
        when(componentRepository.findByBooking_BookingId(1L)).thenReturn(builtComps);

        snapshotService.allocateOrderSnapshot(booking, BigDecimal.valueOf(162000), List.of(ticket));

        // Ticket: 100,000 - 10,000 = 90,000
        assertEquals(0, BigDecimal.valueOf(10000).compareTo(ticket.getDiscountAllocated()));
        assertEquals(0, BigDecimal.valueOf(90000).compareTo(ticket.getNetPaidAmount()));

        // Popcorn component: 50,000 - 5,000 = 45,000
        BookingFoodItemComponent popcornComp = builtComps.get(0);
        assertEquals(0, BigDecimal.valueOf(5000).compareTo(popcornComp.getDiscountAllocated()));
        assertEquals(0, BigDecimal.valueOf(45000).compareTo(popcornComp.getNetPaidAmount()));

        // Coca component: 30,000 - 3,000 = 27,000
        BookingFoodItemComponent cocaComp = builtComps.get(1);
        assertEquals(0, BigDecimal.valueOf(3000).compareTo(cocaComp.getDiscountAllocated()));
        assertEquals(0, BigDecimal.valueOf(27000).compareTo(cocaComp.getNetPaidAmount()));

        // Combo parent rollup equals exact sum of components
        assertEquals(0, BigDecimal.valueOf(8000).compareTo(comboLine.getDiscountAllocated()));
        assertEquals(0, BigDecimal.valueOf(72000).compareTo(comboLine.getNetPaidAmount()));
    }

    @Test
    void largestRemainderAllocation_IndivisibleAmounts_PreservesExactSumWithoutLosingOrCreatingMoney() {
        long[] weights = new long[]{33333L, 33333L, 33333L};
        long[] alloc = snapshotService.allocateProportional(10000L, weights);

        long sum = alloc[0] + alloc[1] + alloc[2];
        assertEquals(10000L, sum);
        // Tie break goes to lowest indices: [3334, 3333, 3333]
        assertEquals(3334L, alloc[0]);
        assertEquals(3333L, alloc[1]);
        assertEquals(3333L, alloc[2]);
        for (int i = 0; i < 3; i++) {
            assertTrue(alloc[i] >= 0 && alloc[i] <= weights[i]);
        }
    }

    @Test
    void partialQuantityRefund_NeverExceedsLineNetPaidOrDiscountAllocated() {
        long[] first = snapshotService.calculateQuantityRefund(
                3, BigDecimal.valueOf(90000), BigDecimal.valueOf(10000),
                0, BigDecimal.ZERO, BigDecimal.ZERO, 1);
        assertEquals(30000L, first[0]);
        assertEquals(3333L, first[1]);

        long[] second = snapshotService.calculateQuantityRefund(
                3, BigDecimal.valueOf(90000), BigDecimal.valueOf(10000),
                1, BigDecimal.valueOf(first[0]), BigDecimal.valueOf(first[1]), 2);
        assertEquals(90000L, first[0] + second[0]);
        assertEquals(10000L, first[1] + second[1]);
    }
}
