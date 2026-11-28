package com.boxcinema.common.service.impl;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.BookingFoodItemComponentRepository;
import com.boxcinema.common.repository.FoodComboItemRepository;
import com.boxcinema.common.repository.OrderVoucherAllocationRepository;
import com.boxcinema.common.repository.OrderVoucherRedemptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.*;

/**
 * Computes immutable purchase-time snapshots, combo component decompositions,
 * and exact integer VND Largest-Remainder (Hamilton) discount allocations with
 * deterministic ascending ticket_id (ticket_item_id) tie-breaker and applicableScope filtering.
 */
@Service
public class PurchaseSnapshotService {

    private final FoodComboItemRepository foodComboItemRepository;
    private final BookingFoodItemComponentRepository componentRepository;
    private final OrderVoucherRedemptionRepository redemptionRepository;
    private final OrderVoucherAllocationRepository allocationRepository;

    public PurchaseSnapshotService(
            FoodComboItemRepository foodComboItemRepository,
            BookingFoodItemComponentRepository componentRepository) {
        this(foodComboItemRepository, componentRepository, null, null);
    }

    @Autowired
    public PurchaseSnapshotService(
            FoodComboItemRepository foodComboItemRepository,
            BookingFoodItemComponentRepository componentRepository,
            @Autowired(required = false) OrderVoucherRedemptionRepository redemptionRepository,
            @Autowired(required = false) OrderVoucherAllocationRepository allocationRepository) {
        this.foodComboItemRepository = foodComboItemRepository;
        this.componentRepository = componentRepository;
        this.redemptionRepository = redemptionRepository;
        this.allocationRepository = allocationRepository;
    }

    /**
     * Largest-Remainder (Hamilton) integer VND allocation.
     * Invariants:
     *   - sum(result) == totalToAllocate (when totalToAllocate <= sum(weights) and sum(weights) > 0)
     *   - 0 <= result[i] <= weights[i]
     *   - Deterministic tie-break: lowest index first
     */
    public long[] allocateProportional(long totalToAllocate, long[] weights) {
        return allocateProportional(totalToAllocate, weights, null);
    }

    /**
     * Largest-Remainder (Hamilton) integer VND allocation with explicit deterministic tie-breaker IDs
     * (e.g. ascending ticket_id / ticket_item_id).
     */
    public long[] allocateProportional(long totalToAllocate, long[] weights, long[] tieBreakerIds) {
        int n = weights.length;
        long[] result = new long[n];
        if (n == 0 || totalToAllocate <= 0) {
            return result;
        }
        long sumWeights = 0;
        for (long w : weights) {
            if (w > 0) sumWeights += w;
        }
        if (sumWeights <= 0) {
            return result;
        }

        long target = Math.min(totalToAllocate, sumWeights);
        long[] remainders = new long[n];
        long allocatedSum = 0;

        for (int i = 0; i < n; i++) {
            long w = Math.max(0L, weights[i]);
            if (w <= 0) {
                result[i] = 0L;
                remainders[i] = 0L;
                continue;
            }
            BigInteger num = BigInteger.valueOf(target).multiply(BigInteger.valueOf(w));
            BigInteger[] divRem = num.divideAndRemainder(BigInteger.valueOf(sumWeights));
            result[i] = Math.min(w, divRem[0].longValueExact());
            remainders[i] = divRem[1].longValueExact();
            allocatedSum += result[i];
        }

        long leftover = target - allocatedSum;
        if (leftover > 0) {
            Integer[] indices = new Integer[n];
            for (int i = 0; i < n; i++) indices[i] = i;
            Arrays.sort(indices, (a, b) -> {
                int cmp = Long.compare(remainders[b], remainders[a]);
                if (cmp != 0) {
                    return cmp;
                }
                if (tieBreakerIds != null && a < tieBreakerIds.length && b < tieBreakerIds.length) {
                    int idCmp = Long.compare(tieBreakerIds[a], tieBreakerIds[b]);
                    if (idCmp != 0) {
                        return idCmp;
                    }
                }
                return Integer.compare(a, b);
            });
            for (int idx : indices) {
                if (leftover <= 0) break;
                if (result[idx] < Math.max(0L, weights[idx])) {
                    result[idx]++;
                    leftover--;
                }
            }
        }
        return result;
    }

    /**
     * Evaluates whether an item kind ("SEAT", "TICKET", "FOOD", "FOOD_ITEM", "COMP", "COMBO_COMPONENT")
     * is eligible under the promotion's applicableScope.
     */
    public static boolean isItemEligibleForScope(String itemKind, String applicableScope) {
        if (applicableScope == null || applicableScope.isBlank()) {
            return true;
        }
        String scope = applicableScope.trim().toUpperCase(Locale.ROOT);
        String kind = itemKind != null ? itemKind.trim().toUpperCase(Locale.ROOT) : "";
        return switch (scope) {
            case "ALL", "ORDER", "ANY" -> true;
            case "TICKET", "TICKETS", "TICKET_ONLY", "SEAT", "SEATS" ->
                    "SEAT".equals(kind) || "TICKET".equals(kind);
            case "FOOD", "FOOD_ONLY", "FOOD_ITEM", "FNB", "CONCESSION", "CONCESSIONS", "FOOD_AND_COMBO" ->
                    "FOOD".equals(kind) || "FOOD_ITEM".equals(kind) || "COMP".equals(kind) || "COMBO_COMPONENT".equals(kind);
            case "STANDALONE_FOOD" ->
                    "FOOD".equals(kind) || "FOOD_ITEM".equals(kind);
            case "COMBO", "COMBOS", "COMBO_ONLY", "COMBO_COMPONENT" ->
                    "COMP".equals(kind) || "COMBO_COMPONENT".equals(kind);
            default -> true;
        };
    }

    /**
     * Computes the exact integer VND voucher discount for a Promotion over totalEligibleAmountVnd:
     *   - FIXED:   actual_discount = min(voucher_value, total_eligible_amount)
     *   - PERCENT: actual_discount = min(round_half_up(total_eligible_amount * pct / 100), max_discount, total_eligible_amount)
     */
    public static long computeConfiguredVoucherDiscountVnd(Promotion promo, long totalEligibleAmountVnd) {
        if (promo == null || totalEligibleAmountVnd <= 0) {
            return 0L;
        }
        return computeConfiguredVoucherDiscountVnd(
                promo.getDiscountType(),
                promo.getDiscountValue(),
                promo.getMaxDiscount(),
                totalEligibleAmountVnd);
    }

    public static long computeConfiguredVoucherDiscountVnd(
            String discountType,
            BigDecimal discountValue,
            BigDecimal maxDiscount,
            long totalEligibleAmountVnd) {
        if (totalEligibleAmountVnd <= 0 || discountType == null || discountValue == null) {
            return 0L;
        }
        String type = discountType.trim().toUpperCase(Locale.ROOT);
        long rawDiscount;
        if ("PERCENT".equals(type) || "PERCENTAGE".equals(type)) {
            rawDiscount = BigDecimal.valueOf(totalEligibleAmountVnd)
                    .multiply(discountValue)
                    .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                    .longValue();
            if (maxDiscount != null && maxDiscount.compareTo(BigDecimal.ZERO) > 0) {
                rawDiscount = Math.min(rawDiscount, toVndLong(maxDiscount));
            }
        } else {
            rawDiscount = toVndLong(discountValue);
        }
        return Math.max(0L, Math.min(rawDiscount, totalEligibleAmountVnd));
    }

    /**
     * Builds (or refreshes) BookingFoodItemComponent rows for all COMBO items on a booking,
     * allocating the combo's purchase-time subtotal across its components proportionally
     * to each component's standalone catalog price x quantity.
     */
    public List<BookingFoodItemComponent> buildComboComponents(Booking booking, BookingFoodItem comboLine) {
        if (comboLine == null || !"COMBO".equalsIgnoreCase(comboLine.getItemType()) || comboLine.getCombo() == null) {
            return List.of();
        }
        List<FoodComboItem> comboItems = foodComboItemRepository.findByCombo_Id(comboLine.getCombo().getId());
        if (comboItems.isEmpty()) {
            return List.of();
        }
        comboItems.sort(Comparator.comparing(FoodComboItem::getId));

        int m = comboItems.size();
        long[] refWeights = new long[m];
        long[] catalogUnitPrices = new long[m];
        int[] totalQtys = new int[m];

        for (int i = 0; i < m; i++) {
            FoodComboItem ci = comboItems.get(i);
            BigDecimal refPrice = (ci.getVariant() != null && ci.getVariant().getPrice() != null)
                    ? ci.getVariant().getPrice()
                    : (ci.getFoodItem() != null && ci.getFoodItem().getPrice() != null ? ci.getFoodItem().getPrice() : BigDecimal.ZERO);
            long unitVnd = toVndLong(refPrice);
            int qty = Math.max(1, comboLine.getQuantity()) * Math.max(1, ci.getQuantity());
            catalogUnitPrices[i] = unitVnd;
            totalQtys[i] = qty;
            refWeights[i] = unitVnd * qty;
        }

        long comboGross = toVndLong(comboLine.getSubtotal());
        boolean allZero = Arrays.stream(refWeights).allMatch(w -> w <= 0);
        if (allZero) {
            for (int i = 0; i < m; i++) refWeights[i] = totalQtys[i];
        }
        long[] allocatedSubtotals = allocateProportional(comboGross, refWeights);

        List<BookingFoodItemComponent> components = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            FoodComboItem ci = comboItems.get(i);
            BookingFoodItemComponent comp = new BookingFoodItemComponent();
            comp.setBookingFoodItem(comboLine);
            comp.setBooking(booking);
            comp.setFoodItem(ci.getFoodItem());
            comp.setVariant(ci.getVariant());
            comp.setFoodComboItemId(ci.getId());
            comp.setComponentNameSnapshot(ci.getFoodItem() != null ? ci.getFoodItem().getName() : "Combo Item");
            comp.setVariantNameSnapshot(ci.getVariant() != null ? ci.getVariant().getName() : null);
            comp.setQuantityPerCombo(ci.getQuantity());
            comp.setTotalQuantity(totalQtys[i]);
            comp.setCatalogUnitPriceSnapshot(BigDecimal.valueOf(catalogUnitPrices[i]).setScale(2, RoundingMode.HALF_UP));

            BigDecimal allocSub = BigDecimal.valueOf(allocatedSubtotals[i]).setScale(2, RoundingMode.HALF_UP);
            BigDecimal allocUnit = totalQtys[i] > 0
                    ? allocSub.divide(BigDecimal.valueOf(totalQtys[i]), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            comp.setAllocatedUnitPrice(allocUnit);
            comp.setAllocatedSubtotal(allocSub);
            comp.setDiscountAllocated(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            comp.setNetPaidAmount(allocSub);
            comp.setDeliveryStatus("PENDING");
            components.add(comp);
        }
        return components;
    }

    /**
     * Allocates discount across all atomic units (Tickets/BookingSeats, standalone FOOD items,
     * and COMBO BookingFoodItemComponents) using Largest-Remainder with deterministic ascending
     * ticket_id (ticket_item_id) tie-breaker and Promotion.applicableScope eligibility filtering.
     */
    public void allocateOrderSnapshot(Booking booking, BigDecimal actualPaidAmount, List<Ticket> tickets) {
        if (booking == null) return;

        Map<Long, Ticket> ticketBySeatId = new HashMap<>();
        if (tickets != null) {
            for (Ticket t : tickets) {
                if (t != null && t.getBookingSeatId() != null) {
                    ticketBySeatId.put(t.getBookingSeatId(), t);
                }
            }
        }

        // Sort seats by ascending ticket_id when tickets are present, falling back to ascending booking_seat_id
        List<BookingSeat> seats = booking.getBookingSeats() != null
                ? booking.getBookingSeats().stream()
                    .sorted(Comparator
                            .comparing((BookingSeat s) -> {
                                Ticket matched = s.getBookingSeatId() != null ? ticketBySeatId.get(s.getBookingSeatId()) : null;
                                return (matched != null && matched.getTicketId() != null)
                                        ? matched.getTicketId()
                                        : (s.getBookingSeatId() != null ? s.getBookingSeatId() : Long.MAX_VALUE);
                            })
                            .thenComparing(s -> s.getBookingSeatId() != null ? s.getBookingSeatId() : Long.MAX_VALUE))
                    .toList()
                : List.of();

        List<BookingFoodItem> foodLines = booking.getBookingFoodItems() != null
                ? booking.getBookingFoodItems().stream()
                    .sorted(Comparator.comparing(f -> f.getId() != null ? f.getId() : Long.MAX_VALUE))
                    .toList()
                : List.of();

        List<BookingFoodItemComponent> existingComponents = (booking.getBookingId() != null && componentRepository != null)
                ? componentRepository.findByBooking_BookingId(booking.getBookingId())
                : List.of();

        Map<Long, List<BookingFoodItemComponent>> compByBfiId = new LinkedHashMap<>();
        for (BookingFoodItemComponent c : existingComponents) {
            if (c.getBookingFoodItem() != null && c.getBookingFoodItem().getId() != null) {
                compByBfiId.computeIfAbsent(c.getBookingFoodItem().getId(), k -> new ArrayList<>()).add(c);
            }
        }

        // Ensure combo components exist if any COMBO line has none yet
        List<BookingFoodItemComponent> newlyBuilt = new ArrayList<>();
        for (BookingFoodItem bfi : foodLines) {
            if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getId() != null) {
                List<BookingFoodItemComponent> comps = compByBfiId.get(bfi.getId());
                if (comps == null || comps.isEmpty()) {
                    comps = buildComboComponents(booking, bfi);
                    if (!comps.isEmpty()) {
                        newlyBuilt.addAll(comps);
                        compByBfiId.put(bfi.getId(), comps);
                    }
                } else {
                    comps.sort(Comparator.comparing(c -> c.getId() != null ? c.getId() : Long.MAX_VALUE));
                }
            }
        }
        if (!newlyBuilt.isEmpty() && componentRepository != null) {
            componentRepository.saveAll(newlyBuilt);
        }

        Promotion promo = booking.getPromotion();
        String scope = promo != null ? promo.getApplicableScope() : "ALL";

        List<AtomicTarget> targets = new ArrayList<>();
        long sumGross = 0L;
        long sumEligible = 0L;

        if (!seats.isEmpty()) {
            int seatIdx = 0;
            for (BookingSeat bs : seats) {
                seatIdx++;
                long g = toVndLong(bs.getUnitPrice());
                bs.setGrossPrice(BigDecimal.valueOf(g).setScale(2, RoundingMode.HALF_UP));
                Ticket matched = bs.getBookingSeatId() != null ? ticketBySeatId.get(bs.getBookingSeatId()) : null;
                boolean eligible = isItemEligibleForScope("TICKET", scope);
                long elig = eligible ? g : 0L;
                long tieId = (matched != null && matched.getTicketId() != null)
                        ? matched.getTicketId()
                        : (bs.getBookingSeatId() != null ? bs.getBookingSeatId() : seatIdx);
                String refKey = (matched != null && matched.getTicketId() != null)
                        ? ("TICKET:" + matched.getTicketId())
                        : ("SEAT:" + (bs.getBookingSeatId() != null ? bs.getBookingSeatId() : seatIdx));
                targets.add(new AtomicTarget("SEAT", bs, matched, g, elig, tieId, refKey));
                sumGross += g;
                sumEligible += elig;
            }
        } else if (tickets != null && !tickets.isEmpty()) {
            // Support direct ticket-list allocation even if bookingSeats collection was not populated
            List<Ticket> sortedTickets = tickets.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator
                            .comparing((Ticket t) -> t.getTicketId() != null ? t.getTicketId() : Long.MAX_VALUE)
                            .thenComparing(t -> t.getBookingSeatId() != null ? t.getBookingSeatId() : Long.MAX_VALUE))
                    .toList();
            int tIdx = 0;
            for (Ticket t : sortedTickets) {
                tIdx++;
                BigDecimal basePrice = (t.getGrossPrice() != null && t.getGrossPrice().compareTo(BigDecimal.ZERO) > 0)
                        ? t.getGrossPrice()
                        : t.getPrice();
                long g = toVndLong(basePrice);
                t.setGrossPrice(BigDecimal.valueOf(g).setScale(2, RoundingMode.HALF_UP));
                boolean eligible = isItemEligibleForScope("TICKET", scope);
                long elig = eligible ? g : 0L;
                long tieId = t.getTicketId() != null ? t.getTicketId() : (t.getBookingSeatId() != null ? t.getBookingSeatId() : tIdx);
                String refKey = "TICKET:" + tieId;
                targets.add(new AtomicTarget("TICKET", t, t, g, elig, tieId, refKey));
                sumGross += g;
                sumEligible += elig;
            }
        }

        int foodIdx = 0;
        for (BookingFoodItem bfi : foodLines) {
            foodIdx++;
            if ("COMBO".equalsIgnoreCase(bfi.getItemType())
                    && bfi.getId() != null
                    && compByBfiId.containsKey(bfi.getId())
                    && !compByBfiId.get(bfi.getId()).isEmpty()) {
                int compIdx = 0;
                for (BookingFoodItemComponent comp : compByBfiId.get(bfi.getId())) {
                    compIdx++;
                    long g = toVndLong(comp.getAllocatedSubtotal());
                    boolean eligible = isItemEligibleForScope("COMBO_COMPONENT", scope);
                    long elig = eligible ? g : 0L;
                    long tieId = comp.getId() != null ? comp.getId() : (1_000_000L + compIdx);
                    String refKey = "COMP:" + (comp.getId() != null ? comp.getId() : (bfi.getId() + "_" + compIdx));
                    targets.add(new AtomicTarget("COMP", comp, null, g, elig, tieId, refKey));
                    sumGross += g;
                    sumEligible += elig;
                }
            } else {
                long g = toVndLong(bfi.getSubtotal());
                boolean eligible = isItemEligibleForScope("FOOD_ITEM", scope);
                long elig = eligible ? g : 0L;
                long tieId = bfi.getId() != null ? bfi.getId() : (500_000L + foodIdx);
                String refKey = "FOOD:" + (bfi.getId() != null ? bfi.getId() : foodIdx);
                targets.add(new AtomicTarget("FOOD", bfi, null, g, elig, tieId, refKey));
                sumGross += g;
                sumEligible += elig;
            }
        }

        long serviceFee = toVndLong(booking.getServiceFee());
        long configuredPromoDiscount = promo != null
                ? computeConfiguredVoucherDiscountVnd(promo, sumEligible)
                : Math.max(0L, Math.min(toVndLong(booking.getDiscountAmount()), sumEligible));

        long effectiveDiscount;
        if (actualPaidAmount != null) {
            long paid = toVndLong(actualPaidAmount);
            long impliedDiscount = sumGross + serviceFee - paid;
            if (impliedDiscount < 0 || impliedDiscount > sumGross || impliedDiscount > sumEligible) {
                booking.setSnapshotReviewRequired(true);
                effectiveDiscount = Math.max(0L, Math.min(impliedDiscount, sumEligible));
            } else {
                if (promo != null && promo.getDiscountType() != null && promo.getDiscountValue() != null) {
                    long currentBookingDisc = toVndLong(booking.getDiscountAmount());
                    if (impliedDiscount != configuredPromoDiscount && impliedDiscount != currentBookingDisc) {
                        booking.setSnapshotReviewRequired(true);
                    }
                }
                effectiveDiscount = impliedDiscount;
            }
        } else {
            long currentBookingDisc = toVndLong(booking.getDiscountAmount());
            if (promo != null && promo.getDiscountType() != null && promo.getDiscountValue() != null) {
                if (currentBookingDisc <= 0 || !"ALL".equalsIgnoreCase(scope)) {
                    effectiveDiscount = configuredPromoDiscount;
                    booking.setDiscountAmount(BigDecimal.valueOf(effectiveDiscount).setScale(2, RoundingMode.HALF_UP));
                    if (booking.getSubtotal() != null && booking.getSubtotal().compareTo(BigDecimal.ZERO) > 0) {
                        booking.recalculateTotals();
                    }
                } else {
                    effectiveDiscount = Math.max(0L, Math.min(currentBookingDisc, sumEligible));
                }
            } else {
                effectiveDiscount = Math.max(0L, Math.min(currentBookingDisc, sumEligible));
            }
        }

        long[] weights = new long[targets.size()];
        long[] tieBreakerIds = new long[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            weights[i] = targets.get(i).eligibleVnd();
            tieBreakerIds[i] = targets.get(i).tieBreakerId();
        }
        long[] discounts = allocateProportional(effectiveDiscount, weights, tieBreakerIds);

        long sumAllocated = 0L;
        for (int i = 0; i < targets.size(); i++) {
            AtomicTarget t = targets.get(i);
            long gross = t.grossVnd();
            long disc = discounts[i];
            long net = gross - disc;
            sumAllocated += disc;
            BigDecimal discBd = BigDecimal.valueOf(disc).setScale(2, RoundingMode.HALF_UP);
            BigDecimal netBd = BigDecimal.valueOf(net).setScale(2, RoundingMode.HALF_UP);

            if ("SEAT".equals(t.kind())) {
                BookingSeat bs = (BookingSeat) t.ref();
                bs.setDiscountAllocated(discBd);
                bs.setNetPaidAmount(netBd);
            } else if ("TICKET".equals(t.kind())) {
                Ticket ticket = (Ticket) t.ref();
                ticket.setDiscountAllocated(discBd);
                ticket.setNetPaidAmount(netBd);
            } else if ("FOOD".equals(t.kind())) {
                BookingFoodItem bfi = (BookingFoodItem) t.ref();
                bfi.setDiscountAllocated(discBd);
                bfi.setNetPaidAmount(netBd);
            } else if ("COMP".equals(t.kind())) {
                BookingFoodItemComponent comp = (BookingFoodItemComponent) t.ref();
                comp.setDiscountAllocated(discBd);
                comp.setNetPaidAmount(netBd);
            }
        }

        if (sumEligible > 0 && sumAllocated != effectiveDiscount) {
            throw new IllegalStateException("Largest-Remainder exact sum invariant violated: sum(allocated)="
                    + sumAllocated + " != effectiveDiscount=" + effectiveDiscount);
        }

        // Roll up combo BookingFoodItem totals as exact sum of its components
        for (BookingFoodItem bfi : foodLines) {
            if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getId() != null) {
                List<BookingFoodItemComponent> comps = compByBfiId.get(bfi.getId());
                if (comps != null && !comps.isEmpty()) {
                    BigDecimal dSum = BigDecimal.ZERO;
                    BigDecimal nSum = BigDecimal.ZERO;
                    for (BookingFoodItemComponent c : comps) {
                        dSum = dSum.add(c.getDiscountAllocated());
                        nSum = nSum.add(c.getNetPaidAmount());
                    }
                    bfi.setDiscountAllocated(dSum.setScale(2, RoundingMode.HALF_UP));
                    bfi.setNetPaidAmount(nSum.setScale(2, RoundingMode.HALF_UP));
                }
            }
        }

        // Copy seat snapshots to issued Tickets when seats were the primary targets
        if (!seats.isEmpty() && tickets != null && !tickets.isEmpty()) {
            Map<Long, BookingSeat> seatById = new HashMap<>();
            for (BookingSeat bs : seats) {
                if (bs.getBookingSeatId() != null) {
                    seatById.put(bs.getBookingSeatId(), bs);
                }
            }
            for (Ticket ticket : tickets) {
                BookingSeat bs = seatById.get(ticket.getBookingSeatId());
                if (bs != null) {
                    ticket.setGrossPrice(bs.getGrossPrice());
                    ticket.setDiscountAllocated(bs.getDiscountAllocated());
                    ticket.setNetPaidAmount(bs.getNetPaidAmount());
                } else {
                    BigDecimal p = ticket.getPrice() != null ? ticket.getPrice() : BigDecimal.ZERO;
                    ticket.setGrossPrice(p);
                    ticket.setDiscountAllocated(BigDecimal.ZERO);
                    ticket.setNetPaidAmount(p);
                }
            }
        }

        // Snapshot promotion terms on booking & persist OrderVoucherRedemption + OrderVoucherAllocation rows
        if (promo != null) {
            booking.setPromotionDiscountTypeSnapshot(promo.getDiscountType());
            booking.setPromotionDiscountValueSnapshot(promo.getDiscountValue());
            booking.setPromotionMaxDiscountSnapshot(promo.getMaxDiscount());
            booking.setPromotionEndAtSnapshot(promo.getEndAt());
            persistVoucherRedemptionAndAllocations(booking, promo, sumEligible, effectiveDiscount, targets, discounts);
        }

        // Persist updated components
        List<BookingFoodItemComponent> allComps = new ArrayList<>();
        compByBfiId.values().forEach(allComps::addAll);
        if (!allComps.isEmpty() && componentRepository != null) {
            componentRepository.saveAll(allComps);
        }
    }

    private record AtomicTarget(
            String kind,
            Object ref,
            Ticket matchedTicket,
            long grossVnd,
            long eligibleVnd,
            long tieBreakerId,
            String itemRefKey
    ) {}

    private void persistVoucherRedemptionAndAllocations(
            Booking booking,
            Promotion promo,
            long sumEligible,
            long effectiveDiscount,
            List<AtomicTarget> targets,
            long[] discounts) {
        if (redemptionRepository == null || booking.getBookingId() == null || promo.getPromotionId() == null) {
            return;
        }
        OrderVoucherRedemption redemption = redemptionRepository.findByBooking_BookingId(booking.getBookingId())
                .orElseGet(OrderVoucherRedemption::new);
        redemption.setBooking(booking);
        redemption.setPromotion(promo);
        redemption.setVoucherCode(booking.getVoucherCodeSnapshot() != null && !booking.getVoucherCodeSnapshot().isBlank()
                ? booking.getVoucherCodeSnapshot()
                : (promo.getVoucherCode() != null ? promo.getVoucherCode() : ("PROMO-" + promo.getPromotionId())));
        redemption.setDiscountType(promo.getDiscountType() != null ? promo.getDiscountType() : "FIXED");
        redemption.setDiscountValue(promo.getDiscountValue() != null ? promo.getDiscountValue() : BigDecimal.ZERO);
        redemption.setMaxDiscount(promo.getMaxDiscount());
        redemption.setEligibleAmount(sumEligible);
        redemption.setActualDiscount(effectiveDiscount);
        if (redemption.getStatus() == null || redemption.getStatus().isBlank()) {
            redemption.setStatus("APPLIED");
        }
        redemption = redemptionRepository.save(redemption);

        if (allocationRepository == null || redemption.getId() == null) {
            return;
        }
        List<OrderVoucherAllocation> existingAllocs = allocationRepository.findByRedemption_IdOrderByIdAsc(redemption.getId());
        Map<String, OrderVoucherAllocation> byKey = new HashMap<>();
        for (OrderVoucherAllocation a : existingAllocs) {
            byKey.put(a.getItemRefKey(), a);
        }

        List<OrderVoucherAllocation> toSave = new ArrayList<>(targets.size());
        Set<String> activeKeys = new HashSet<>();
        for (int i = 0; i < targets.size(); i++) {
            AtomicTarget t = targets.get(i);
            long gross = t.grossVnd();
            long elig = t.eligibleVnd();
            long disc = discounts[i];
            long net = gross - disc;

            OrderVoucherAllocation alloc = byKey.getOrDefault(t.itemRefKey(), new OrderVoucherAllocation());
            alloc.setRedemption(redemption);
            alloc.setBooking(booking);
            alloc.setItemRefKey(t.itemRefKey());
            alloc.setGrossAmount(gross);
            alloc.setEligibleAmount(elig);
            alloc.setAllocatedDiscount(disc);
            alloc.setNetPaidAmount(net);

            if ("SEAT".equals(t.kind())) {
                BookingSeat bs = (BookingSeat) t.ref();
                Ticket ticket = t.matchedTicket();
                alloc.setItemKind(ticket != null && ticket.getTicketId() != null ? "TICKET" : "SEAT");
                alloc.setBookingSeatId(bs.getBookingSeatId());
                alloc.setTicketId(ticket != null ? ticket.getTicketId() : null);
            } else if ("TICKET".equals(t.kind())) {
                Ticket ticket = (Ticket) t.ref();
                alloc.setItemKind("TICKET");
                alloc.setTicketId(ticket.getTicketId());
                alloc.setBookingSeatId(ticket.getBookingSeatId());
            } else if ("FOOD".equals(t.kind())) {
                BookingFoodItem bfi = (BookingFoodItem) t.ref();
                alloc.setItemKind("FOOD_ITEM");
                alloc.setBookingFoodItemId(bfi.getId());
            } else if ("COMP".equals(t.kind())) {
                BookingFoodItemComponent comp = (BookingFoodItemComponent) t.ref();
                alloc.setItemKind("COMBO_COMPONENT");
                alloc.setBookingFoodItemId(comp.getBookingFoodItem() != null ? comp.getBookingFoodItem().getId() : null);
                alloc.setBookingFoodItemComponentId(comp.getId());
            }

            toSave.add(alloc);
            activeKeys.add(t.itemRefKey());
        }

        for (OrderVoucherAllocation old : existingAllocs) {
            if (!activeKeys.contains(old.getItemRefKey())) {
                allocationRepository.delete(old);
            }
        }
        if (!toSave.isEmpty()) {
            allocationRepository.saveAll(toSave);
        }
    }

    /**
     * Reconciles a booking against its settled PAID payment and cumulative cash refunds.
     * If historical financial data is inconsistent or cumulative refunds would exceed settled payment.amount,
     * sets booking.snapshotReviewRequired = true and returns false.
     */
    public boolean verifySettledPaymentReconciliation(Booking booking, Payment paidPayment, long additionalCashRefundVnd) {
        if (booking == null || paidPayment == null) {
            return false;
        }
        if (!"PAID".equalsIgnoreCase(paidPayment.getPaymentStatus()) || paidPayment.getAmount() == null) {
            return false;
        }
        long settledVnd = toVndLong(paidPayment.getAmount());
        if (settledVnd <= 0) {
            return false;
        }
        long bookingTotalVnd = toVndLong(booking.getTotalAmount());
        long bookingSubVnd = toVndLong(booking.getSubtotal());
        long bookingDiscVnd = toVndLong(booking.getDiscountAmount());
        long bookingFeeVnd = toVndLong(booking.getServiceFee());

        if (bookingTotalVnd != settledVnd
                || (bookingSubVnd > 0 && Math.max(0L, bookingSubVnd - bookingDiscVnd + bookingFeeVnd) != settledVnd)) {
            booking.setSnapshotReviewRequired(true);
            return false;
        }
        if (additionalCashRefundVnd > settledVnd) {
            return false;
        }
        return !booking.isSnapshotReviewRequired();
    }

    /**
     * Computes remaining refundable cash for a ticket/item:
     * refundable_amount_i = net_paid_i - previous_refunded_amount_i.
     */
    public long calculateRemainingRefundableCash(BigDecimal netPaidAmount, BigDecimal previousRefundedCashAmount) {
        long net = toVndLong(netPaidAmount);
        long prev = toVndLong(previousRefundedCashAmount);
        if (prev < 0 || prev > net) {
            throw new IllegalArgumentException("Previous refunded amount (" + prev
                    + ") cannot be negative or exceed net paid amount (" + net + ").");
        }
        return net - prev;
    }

    /**
     * Computes partial-quantity cash refund and voucher recovery without exceeding line totals.
     * Returns long[]{cashRefundVnd, voucherRecoveryVnd}.
     */
    public long[] calculateQuantityRefund(
            int totalQty,
            BigDecimal totalNetPaid,
            BigDecimal totalDiscountAllocated,
            int alreadyRefundedQty,
            BigDecimal alreadyRefundedCash,
            BigDecimal alreadyRefundedVoucher,
            int requestedQty) {
        if (totalQty <= 0 || requestedQty <= 0 || alreadyRefundedQty + requestedQty > totalQty) {
            throw new IllegalArgumentException("Invalid refund quantity requested.");
        }
        long netTotal = toVndLong(totalNetPaid);
        long discTotal = toVndLong(totalDiscountAllocated);
        long prevCash = toVndLong(alreadyRefundedCash);
        long prevVoucher = toVndLong(alreadyRefundedVoucher);

        long remainingCash = Math.max(0L, netTotal - prevCash);
        long remainingVoucher = Math.max(0L, discTotal - prevVoucher);

        if (alreadyRefundedQty + requestedQty == totalQty) {
            return new long[]{remainingCash, remainingVoucher};
        }

        long cash = BigDecimal.valueOf(netTotal)
                .multiply(BigDecimal.valueOf(requestedQty))
                .divide(BigDecimal.valueOf(totalQty), 0, RoundingMode.HALF_UP)
                .longValue();
        long voucher = BigDecimal.valueOf(discTotal)
                .multiply(BigDecimal.valueOf(requestedQty))
                .divide(BigDecimal.valueOf(totalQty), 0, RoundingMode.HALF_UP)
                .longValue();

        cash = Math.min(cash, remainingCash);
        voucher = Math.min(voucher, remainingVoucher);
        return new long[]{cash, voucher};
    }

    public static long toVndLong(BigDecimal amount) {
        if (amount == null) return 0L;
        return amount.setScale(0, RoundingMode.HALF_UP).longValue();
    }
}
