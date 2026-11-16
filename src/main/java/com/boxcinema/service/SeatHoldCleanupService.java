package com.boxcinema.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Automatically releases expired seat holds in SHOWTIME_SEATS and expires stale HOLDING bookings.
 * Fulfils FR-04 / TS-BOOK-003 (Hold Seats with Expiry - 10 minutes default).
 */
@Service
public class SeatHoldCleanupService {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldCleanupService.class);

    private final JdbcTemplate jdbcTemplate;
    private final SeatRealtimePushService seatRealtimePushService;

    @Autowired
    public SeatHoldCleanupService(
            @Autowired(required = false) JdbcTemplate jdbcTemplate,
            @Autowired(required = false) SeatRealtimePushService seatRealtimePushService) {
        this.jdbcTemplate = jdbcTemplate;
        this.seatRealtimePushService = seatRealtimePushService;
    }

    @Scheduled(fixedDelayString = "${app.booking.hold-cleanup-interval-ms:5000}")
    @Transactional
    public void scheduledReleaseExpiredHolds() {
        int released = releaseExpiredHolds();
        if (released > 0) {
            log.info("Released {} expired seat hold(s) back to AVAILABLE.", released);
            if (seatRealtimePushService != null) {
                seatRealtimePushService.broadcastAllShowtimes("HOLD_EXPIRED");
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
            log.debug("Could not clean up expired showtime_seats: {}", ex.getMessage());
        }

        try {
            jdbcTemplate.update(
                    "UPDATE booking_seats bs " +
                    "JOIN bookings b ON bs.booking_id = b.booking_id " +
                    "SET bs.status = 'EXPIRED', bs.active_seat_key = NULL " +
                    "WHERE b.booking_status = 'HOLDING' " +
                    "  AND b.expires_at IS NOT NULL " +
                    "  AND b.expires_at <= NOW()"
            );
            jdbcTemplate.update(
                    "UPDATE bookings " +
                    "SET booking_status = 'EXPIRED', cancelled_at = NOW() " +
                    "WHERE booking_status = 'HOLDING' " +
                    "  AND expires_at IS NOT NULL " +
                    "  AND expires_at <= NOW()"
            );
        } catch (Exception ex) {
            log.debug("Could not clean up expired holding bookings: {}", ex.getMessage());
        }

        return releasedSeats;
    }
}
