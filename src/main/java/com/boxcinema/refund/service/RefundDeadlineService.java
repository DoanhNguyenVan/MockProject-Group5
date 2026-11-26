package com.boxcinema.refund.service;

import com.boxcinema.common.entity.Booking;
import com.boxcinema.common.entity.IncidentReport;
import com.boxcinema.common.entity.Showtime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;

/**
 * Refund Deadline Calculation & Enforcement Service in Asia/Ho_Chi_Minh timezone.
 *
 * Rules:
 *   1. Standard deadline: showtime.endAt + 72 hours (in Asia/Ho_Chi_Minh).
 *   2. Late-announced verified cinema incident (when incident is VERIFIED and customerNotifiedAt != null):
 *      deadline = min(showtime.endAt + 30 days, max(showtime.endAt + 72 hours, customerNotifiedAt + 7 days))
 *   3. Unverified incident or null customerNotifiedAt falls back to standard showtime.endAt + 72 hours.
 *   4. Exact boundary (evaluatedAt <= deadlineAt) is within deadline (deadlineExceeded = false);
 *      evaluatedAt > deadlineAt is past deadline (deadlineExceeded = true).
 */
@Service
public class RefundDeadlineService {

    public static final ZoneId HO_CHI_MINH_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final String BASIS_NORMAL_72H = "NORMAL_72H";
    public static final String BASIS_LATE_INCIDENT = "LATE_INCIDENT";
    public static final String BASIS_NO_SHOWTIME_72H = "NO_SHOWTIME_72H";

    private volatile Clock clock;

    @Autowired
    public RefundDeadlineService() {
        this(Clock.system(HO_CHI_MINH_ZONE));
    }

    public RefundDeadlineService(Clock clock) {
        this.clock = clock != null ? clock.withZone(HO_CHI_MINH_ZONE) : Clock.system(HO_CHI_MINH_ZONE);
    }

    public Clock getClock() {
        return clock;
    }

    public void setClock(Clock clock) {
        this.clock = clock != null ? clock.withZone(HO_CHI_MINH_ZONE) : Clock.system(HO_CHI_MINH_ZONE);
    }

    public RefundDeadlineService withClock(Clock customClock) {
        return new RefundDeadlineService(customClock);
    }

    public LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    public record DeadlineEvaluation(
            LocalDateTime showtimeEndAt,
            LocalDateTime customerNotifiedAt,
            LocalDateTime evaluatedAt,
            LocalDateTime deadlineAt,
            String deadlineBasis,
            boolean deadlineExceeded,
            boolean lateIncidentFormulaApplied,
            boolean cappedAt30Days
    ) {
        public boolean isWithinDeadline() {
            return !deadlineExceeded;
        }
    }

    public LocalDateTime computeNormalDeadline(LocalDateTime showtimeEndAt) {
        Objects.requireNonNull(showtimeEndAt, "showtimeEndAt is required");
        return showtimeEndAt.plusHours(72);
    }

    public LocalDateTime computeLateIncidentDeadline(LocalDateTime showtimeEndAt, LocalDateTime customerNotifiedAt) {
        Objects.requireNonNull(showtimeEndAt, "showtimeEndAt is required");
        if (customerNotifiedAt == null) {
            return computeNormalDeadline(showtimeEndAt);
        }
        LocalDateTime normal72h = showtimeEndAt.plusHours(72);
        LocalDateTime notifiedPlus7d = customerNotifiedAt.plusDays(7);
        LocalDateTime maxWindow = normal72h.isAfter(notifiedPlus7d) ? normal72h : notifiedPlus7d;
        LocalDateTime cap30d = showtimeEndAt.plusDays(30);
        return maxWindow.isBefore(cap30d) ? maxWindow : cap30d;
    }

    public DeadlineEvaluation evaluate(Booking booking, IncidentReport incident) {
        return evaluate(booking, incident, now());
    }

    public DeadlineEvaluation evaluate(Booking booking, IncidentReport incident, LocalDateTime referenceNow) {
        LocalDateTime evalTime = referenceNow != null ? referenceNow : now();
        LocalDateTime showEnd = resolveShowtimeEndAt(booking);
        if (showEnd == null) {
            LocalDateTime anchor = (booking != null && booking.getConfirmedAt() != null)
                    ? booking.getConfirmedAt()
                    : ((booking != null && booking.getBookedAt() != null) ? booking.getBookedAt() : evalTime);
            if (anchor.isBefore(evalTime.minusHours(72))) {
                anchor = evalTime;
            }
            LocalDateTime fallbackDeadline = anchor.plusHours(72);
            return new DeadlineEvaluation(
                    null,
                    incident != null ? incident.getCustomerNotifiedAt() : null,
                    evalTime,
                    fallbackDeadline,
                    BASIS_NO_SHOWTIME_72H,
                    evalTime.isAfter(fallbackDeadline),
                    false,
                    false);
        }
        return evaluate(showEnd, incident, evalTime);
    }

    public DeadlineEvaluation evaluate(LocalDateTime showtimeEndAt, IncidentReport incident) {
        return evaluate(showtimeEndAt, incident, now());
    }

    public DeadlineEvaluation evaluate(LocalDateTime showtimeEndAt, IncidentReport incident, LocalDateTime referenceNow) {
        LocalDateTime evalTime = referenceNow != null ? referenceNow : now();
        if (showtimeEndAt == null) {
            LocalDateTime fallbackDeadline = evalTime.plusHours(72);
            return new DeadlineEvaluation(null, null, evalTime, fallbackDeadline, BASIS_NO_SHOWTIME_72H, false, false, false);
        }

        boolean verifiedIncidentWithNotice = incident != null
                && "VERIFIED".equalsIgnoreCase(incident.getStatus())
                && incident.getCustomerNotifiedAt() != null;

        if (verifiedIncidentWithNotice) {
            LocalDateTime notifiedAt = incident.getCustomerNotifiedAt();
            LocalDateTime normal72h = showtimeEndAt.plusHours(72);
            LocalDateTime notifiedPlus7d = notifiedAt.plusDays(7);
            LocalDateTime maxWindow = normal72h.isAfter(notifiedPlus7d) ? normal72h : notifiedPlus7d;
            LocalDateTime cap30d = showtimeEndAt.plusDays(30);
            boolean capped = maxWindow.isAfter(cap30d);
            LocalDateTime deadline = capped ? cap30d : maxWindow;
            boolean exceeded = evalTime.isAfter(deadline);
            return new DeadlineEvaluation(
                    showtimeEndAt,
                    notifiedAt,
                    evalTime,
                    deadline,
                    BASIS_LATE_INCIDENT,
                    exceeded,
                    true,
                    capped);
        }

        LocalDateTime normalDeadline = showtimeEndAt.plusHours(72);
        boolean exceeded = evalTime.isAfter(normalDeadline);
        return new DeadlineEvaluation(
                showtimeEndAt,
                incident != null ? incident.getCustomerNotifiedAt() : null,
                evalTime,
                normalDeadline,
                BASIS_NORMAL_72H,
                exceeded,
                false,
                false);
    }

    public static LocalDateTime resolveShowtimeEndAt(Booking booking) {
        if (booking == null || booking.getShowtime() == null) {
            return null;
        }
        Showtime st = booking.getShowtime();
        if (st.getEndAt() != null) {
            return st.getEndAt();
        }
        if (st.getStartAt() != null) {
            return st.getStartAt().plusHours(2);
        }
        return null;
    }
}
