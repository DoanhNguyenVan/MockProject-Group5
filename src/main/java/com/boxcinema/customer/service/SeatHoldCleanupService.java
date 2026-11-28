package com.boxcinema.customer.service;

import com.boxcinema.common.entity.Booking;
import com.boxcinema.common.entity.BookingFoodItem;
import com.boxcinema.common.entity.Payment;
import com.boxcinema.common.repository.BookingRepository;
import com.boxcinema.common.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Automatically releases expired seat holds in SHOWTIME_SEATS and expires stale
 * HOLDING/DRAFT/PENDING_PAYMENT bookings along with reserved food/combo inventory and pending payments.
 * Fulfils FR-04 / TS-BOOK-003 (Hold Seats with Expiry - 10 minutes default).
 */
@Service
public class SeatHoldCleanupService {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldCleanupService.class);
    private static final Set<String> EXPIRABLE_BOOKING_STATUSES = Set.of("HOLDING", "DRAFT", "PENDING_PAYMENT");

    private final JdbcTemplate jdbcTemplate;
    private final SeatRealtimePushService seatRealtimePushService;
    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final FoodService foodService;

    public SeatHoldCleanupService(
            JdbcTemplate jdbcTemplate,
            SeatRealtimePushService seatRealtimePushService) {
        this(jdbcTemplate, seatRealtimePushService, null, null, null);
    }

    @Autowired
    public SeatHoldCleanupService(
            @Autowired(required = false) JdbcTemplate jdbcTemplate,
            @Autowired(required = false) SeatRealtimePushService seatRealtimePushService,
            @Autowired(required = false) BookingRepository bookingRepository,
            @Autowired(required = false) PaymentRepository paymentRepository,
            @Autowired(required = false) FoodService foodService) {
        this.jdbcTemplate = jdbcTemplate;
        this.seatRealtimePushService = seatRealtimePushService;
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.foodService = foodService;
    }

    @Scheduled(fixedDelayString = "${app.booking.hold-cleanup-interval-ms:5000}")
    @Transactional
    public void scheduledReleaseExpiredHolds() {
        int released = releaseExpiredHolds();
        if (released > 0) {
            log.info("Released {} expired seat hold(s) back to AVAILABLE.", released);
            if (seatRealtimePushService != null) {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            seatRealtimePushService.broadcastAllShowtimes("HOLD_EXPIRED");
                        }
                    });
                } else {
                    seatRealtimePushService.broadcastAllShowtimes("HOLD_EXPIRED");
                }
            }
        }
    }

    @Transactional
    public int releaseExpiredHolds() {
        if (jdbcTemplate == null) {
            return 0;
        }
        int releasedSeats = 0;

        try {
            if (bookingRepository != null) {
                List<Long> expiredIds = jdbcTemplate.queryForList(
                        "SELECT booking_id FROM bookings " +
                        "WHERE booking_status IN ('HOLDING', 'DRAFT', 'PENDING_PAYMENT') " +
                        "  AND COALESCE(payment_deadline, expires_at) IS NOT NULL " +
                        "  AND COALESCE(payment_deadline, expires_at) <= NOW() " +
                        "ORDER BY booking_id ASC",
                        Long.class
                );
                LocalDateTime now = LocalDateTime.now();
                for (Long bid : expiredIds) {
                    Booking b = bookingRepository.findByIdForUpdate(bid).orElse(null);
                    if (b == null || b.getBookingStatus() == null
                            || !EXPIRABLE_BOOKING_STATUSES.contains(b.getBookingStatus().toUpperCase())) {
                        continue;
                    }
                    LocalDateTime deadline = b.getPaymentDeadline() != null ? b.getPaymentDeadline() : b.getExpiresAt();
                    if (deadline == null || deadline.isAfter(now)) {
                        continue;
                    }
                    jdbcTemplate.update(
                            "UPDATE showtime_seats ss " +
                            "JOIN booking_seats bs ON bs.showtime_seat_id = ss.showtime_seat_id " +
                            "SET ss.seat_status = 'AVAILABLE', ss.hold_token_hash = NULL, " +
                            "    ss.held_by_user_id = NULL, ss.hold_session_id = NULL, ss.hold_expires_at = NULL " +
                            "WHERE bs.booking_id = ? AND ss.seat_status = 'HELD'",
                            bid
                    );
                    jdbcTemplate.update(
                            "UPDATE booking_seats SET status = 'EXPIRED', active_seat_key = NULL WHERE booking_id = ?",
                            bid
                    );
                    if (foodService != null && b.getBookingFoodItems() != null) {
                        List<BookingFoodItem> items = b.getBookingFoodItems().stream()
                                .filter(bfi -> !"RELEASED".equalsIgnoreCase(bfi.getDeliveryStatus()))
                                .sorted(Comparator.comparing(bfi -> {
                                    if ("FOOD".equalsIgnoreCase(bfi.getItemType()) && bfi.getFoodItem() != null) {
                                        return "A_" + bfi.getFoodItem().getId();
                                    }
                                    if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getCombo() != null) {
                                        return "B_" + bfi.getCombo().getId();
                                    }
                                    return "Z";
                                }))
                                .toList();
                        for (BookingFoodItem bfi : items) {
                            if ("FOOD".equalsIgnoreCase(bfi.getItemType()) && bfi.getFoodItem() != null) {
                                foodService.releaseFoodStock(bfi.getFoodItem().getId(), bfi.getQuantity());
                                bfi.setDeliveryStatus("RELEASED");
                            } else if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getCombo() != null) {
                                foodService.releaseComboStock(bfi.getCombo().getId(), bfi.getQuantity());
                                bfi.setDeliveryStatus("RELEASED");
                            }
                        }
                    }
                    if (paymentRepository != null) {
                        for (Payment p : paymentRepository.findByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(bid, "PENDING")) {
                            p.setPaymentStatus("EXPIRED");
                            paymentRepository.save(p);
                        }
                    }
                    b.setBookingStatus("EXPIRED");
                    b.setCancelledAt(now);
                    bookingRepository.save(b);
                    jdbcTemplate.update(
                            "UPDATE order_voucher_redemptions SET status = 'CANCELLED', updated_at = NOW() WHERE booking_id = ? AND status = 'APPLIED'",
                            bid
                    );
                }
            } else {
                jdbcTemplate.update(
                        "UPDATE order_voucher_redemptions ovr " +
                        "JOIN bookings b ON ovr.booking_id = b.booking_id " +
                        "SET ovr.status = 'CANCELLED', ovr.updated_at = NOW() " +
                        "WHERE b.booking_status IN ('HOLDING', 'DRAFT', 'PENDING_PAYMENT') " +
                        "  AND COALESCE(b.payment_deadline, b.expires_at) IS NOT NULL " +
                        "  AND COALESCE(b.payment_deadline, b.expires_at) <= NOW()"
                );
                jdbcTemplate.update(
                        "UPDATE booking_seats bs " +
                        "JOIN bookings b ON bs.booking_id = b.booking_id " +
                        "SET bs.status = 'EXPIRED', bs.active_seat_key = NULL " +
                        "WHERE b.booking_status IN ('HOLDING', 'DRAFT', 'PENDING_PAYMENT') " +
                        "  AND COALESCE(b.payment_deadline, b.expires_at) IS NOT NULL " +
                        "  AND COALESCE(b.payment_deadline, b.expires_at) <= NOW()"
                );
                jdbcTemplate.update(
                        "UPDATE bookings " +
                        "SET booking_status = 'EXPIRED', cancelled_at = NOW() " +
                        "WHERE booking_status IN ('HOLDING', 'DRAFT', 'PENDING_PAYMENT') " +
                        "  AND COALESCE(payment_deadline, expires_at) IS NOT NULL " +
                        "  AND COALESCE(payment_deadline, expires_at) <= NOW()"
                );
            }
        } catch (Exception ex) {
            log.warn("Could not clean up expired bookings: {}", ex.getMessage());
        }

        try {
            releasedSeats = jdbcTemplate.update(
                    "UPDATE showtime_seats " +
                    "SET seat_status = 'AVAILABLE', " +
                    "    hold_token_hash = NULL, " +
                    "    held_by_user_id = NULL, " +
                    "    hold_session_id = NULL, " +
                    "    hold_expires_at = NULL " +
                    "WHERE seat_status = 'HELD' " +
                    "  AND (hold_expires_at IS NULL OR hold_expires_at <= NOW())"
            );
        } catch (Exception ex) {
            log.warn("Could not clean up expired showtime_seats: {}", ex.getMessage());
        }

        return releasedSeats;
    }
}
