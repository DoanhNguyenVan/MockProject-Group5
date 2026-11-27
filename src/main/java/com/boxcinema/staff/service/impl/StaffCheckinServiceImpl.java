package com.boxcinema.staff.service.impl;

import com.boxcinema.admin.service.UserActivityLogService;
import com.boxcinema.common.dto.BookingResponseDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BookingService;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.staff.dto.response.StaffCheckinResponseDto;
import com.boxcinema.staff.service.StaffCheckinService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Transactional
public class StaffCheckinServiceImpl implements StaffCheckinService {

    private static final Pattern BOX_TOKEN_PATTERN = Pattern.compile("BOX1\\.[a-zA-Z0-9_-]{16,}");
    private static final Pattern BOOKING_CODE_PATTERN = Pattern.compile("BOX-\\d{8}-\\d{4}");
    private static final String ACTION_PRINT_TICKET = "PRINT_PHYSICAL_TICKET";

    private final BookingQrRepository bookingQrRepository;
    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final BookingCheckinRepository checkinRepository;
    private final UserRepository userRepository;
    private final BookingService bookingService;
    private final PaymentRepository paymentRepository;
    private final CinemaRepository cinemaRepository;
    private final UserActivityLogService userActivityLogService;
    private final UserActivityLogRepository userActivityLogRepository;

    public StaffCheckinServiceImpl(
            BookingQrRepository bookingQrRepository,
            BookingRepository bookingRepository,
            TicketRepository ticketRepository,
            BookingCheckinRepository checkinRepository,
            UserRepository userRepository,
            BookingService bookingService,
            PaymentRepository paymentRepository) {
        this(bookingQrRepository, bookingRepository, ticketRepository, checkinRepository,
                userRepository, bookingService, paymentRepository, null, null, null);
    }

    @Autowired
    public StaffCheckinServiceImpl(
            BookingQrRepository bookingQrRepository,
            BookingRepository bookingRepository,
            TicketRepository ticketRepository,
            BookingCheckinRepository checkinRepository,
            UserRepository userRepository,
            BookingService bookingService,
            PaymentRepository paymentRepository,
            CinemaRepository cinemaRepository,
            UserActivityLogService userActivityLogService,
            UserActivityLogRepository userActivityLogRepository) {
        this.bookingQrRepository = bookingQrRepository;
        this.bookingRepository = bookingRepository;
        this.ticketRepository = ticketRepository;
        this.checkinRepository = checkinRepository;
        this.userRepository = userRepository;
        this.bookingService = bookingService;
        this.paymentRepository = paymentRepository;
        this.cinemaRepository = cinemaRepository;
        this.userActivityLogService = userActivityLogService;
        this.userActivityLogRepository = userActivityLogRepository;
    }

    @Override
    public StaffCheckinResponseDto checkinByQrToken(String qrToken, Long staffUserId) {
        if (staffUserId == null) {
            throw new AccessDeniedException("Staff authentication is required to confirm ticket check-in.");
        }
        User staff = userRepository.findById(staffUserId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated staff account not found."));
        String role = staff.getRole() != null ? staff.getRole().trim().toUpperCase() : "";
        if (!"STAFF".equals(role) && !"MANAGER".equals(role) && !"ADMIN".equals(role)) {
            throw new AccessDeniedException("Only authorized cinema staff can confirm ticket usage.");
        }

        if (qrToken == null || qrToken.trim().isBlank()) {
            throw new IllegalArgumentException("QR code is invalid or empty.");
        }

        String tokenClean = qrToken.trim();
        Optional<BookingQr> bQrOpt = resolveLockedBookingQr(tokenClean);
        if (bQrOpt.isEmpty()) {
            return buildFailure("INVALID_QR_TOKEN", "QR code does not exist in the system or is invalid.", null);
        }

        BookingQr bookingQr = bQrOpt.get();
        Booking booking = bookingRepository.findByIdForUpdate(bookingQr.getBooking().getBookingId())
                .orElseThrow(() -> new IllegalStateException("No booking found associated with this QR code."));

        verifyStaffCinemaBranch(staff, role, booking, "TICKET_CHECKIN_DENIED_BRANCH");

        if ("USED".equalsIgnoreCase(bookingQr.getStatus())
                || checkinRepository.findByBooking_BookingId(booking.getBookingId()).isPresent()) {
            logStaffAudit(staff, "TICKET_CHECKIN_DUPLICATE", "/api/staff/bookings/check-in",
                    "Duplicate check-in attempt for " + booking.getBookingCode());
            return buildFailure("BOOKING_ALREADY_CHECKED_IN", "Ticket has already been used / checked in.", booking);
        }

        Optional<Payment> paymentOpt = paymentRepository.findByBooking_BookingId(booking.getBookingId());
        if (paymentOpt.isPresent() && "REFUNDED".equalsIgnoreCase(paymentOpt.get().getPaymentStatus())) {
            return buildFailure("BOOKING_REFUNDED", "Ticket has been refunded and cannot be used.", booking);
        }

        if ("CANCELLED".equalsIgnoreCase(booking.getBookingStatus())) {
            return buildFailure("BOOKING_CANCELLED", "Booking has been cancelled and cannot be checked in.", booking);
        }

        LocalDateTime now = LocalDateTime.now();
        Showtime showtime = booking.getShowtime();
        if (showtime != null && "CANCELLED".equalsIgnoreCase(showtime.getStatus())) {
            return buildFailure("SHOWTIME_CANCELLED", "Showtime has been cancelled.", booking);
        }

        LocalDateTime thirtyMinGrace = (showtime != null && showtime.getEndAt() != null)
                ? showtime.getEndAt().plusMinutes(30)
                : null;
        LocalDateTime qrExpiry = bookingQr.getExpiresAt();
        LocalDateTime effectiveExpiry = (qrExpiry != null && thirtyMinGrace != null)
                ? (qrExpiry.isBefore(thirtyMinGrace) ? qrExpiry : thirtyMinGrace)
                : (thirtyMinGrace != null ? thirtyMinGrace : qrExpiry);
        if ("EXPIRED".equalsIgnoreCase(booking.getBookingStatus())
                || (effectiveExpiry != null && now.isAfter(effectiveExpiry))) {
            logStaffAudit(staff, "TICKET_CHECKIN_EXPIRED", "/api/staff/bookings/check-in",
                    "Expired ticket check-in blocked for " + booking.getBookingCode());
            return buildFailure("TICKET_EXPIRED", "This ticket QR code has expired for the scheduled showtime.", booking);
        }

        if ("REVOKED".equalsIgnoreCase(bookingQr.getStatus())
                || !"ACTIVE".equalsIgnoreCase(bookingQr.getStatus())) {
            return buildFailure("QR_NOT_ACTIVE", "QR code has been revoked or is no longer active.", booking);
        }

        if (!"CONFIRMED".equalsIgnoreCase(booking.getBookingStatus())) {
            return buildFailure(
                    "BOOKING_NOT_CONFIRMED",
                    "Booking has not been paid successfully (Status: " + booking.getBookingStatus() + ").",
                    booking
            );
        }

        List<Ticket> tickets = ticketRepository.findByBookingIdForUpdate(booking.getBookingId());
        if (tickets.isEmpty()) {
            return buildFailure("NO_TICKETS_FOUND", "No valid tickets found for this booking.", booking);
        }
        for (Ticket t : tickets) {
            String tStatus = t.getTicketStatus() != null ? t.getTicketStatus().trim().toUpperCase() : "";
            if ("REFUNDED".equals(tStatus)) {
                return buildFailure("BOOKING_REFUNDED", "Ticket has been refunded and cannot be used.", booking);
            }
            if ("CANCELLED".equals(tStatus)) {
                return buildFailure("BOOKING_CANCELLED", "Ticket has been cancelled.", booking);
            }
            if ("USED".equals(tStatus)) {
                return buildFailure("BOOKING_ALREADY_CHECKED_IN", "Ticket has already been used / checked in.", booking);
            }
            if (!"VALID".equals(tStatus)) {
                return buildFailure("TICKET_NOT_VALID", "Ticket is not valid for entry (Status: " + tStatus + ").", booking);
            }
        }

        // Atomic conditional DB update prevents two concurrent requests from both confirming the same ticket
        int updatedQrRows = bookingQrRepository.markUsedIfActive(bookingQr.getId(), now);
        if (updatedQrRows != 1) {
            return buildFailure("BOOKING_ALREADY_CHECKED_IN", "Ticket has already been used / checked in.", booking);
        }

        int updatedTicketRows = ticketRepository.markBookingTicketsUsed(booking.getBookingId(), now, staff);
        if (updatedTicketRows != tickets.size()) {
            throw new IllegalStateException("Concurrent ticket status modification detected; check-in rolled back.");
        }

        try {
            BookingCheckin checkin = new BookingCheckin(booking, staff, "SUCCESS");
            checkin.setCheckedInAt(now);
            checkinRepository.saveAndFlush(checkin);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("Duplicate check-in detected; transaction rolled back.", ex);
        }

        List<String> seatLabels = new ArrayList<>();
        for (Ticket t : tickets) {
            if (booking.getBookingSeats() != null) {
                booking.getBookingSeats().stream()
                        .filter(bs -> bs.getBookingSeatId().equals(t.getBookingSeatId()))
                        .findFirst()
                        .ifPresent(bs -> seatLabels.add(bs.getSeatLabelSnapshot()));
            }
        }

        StaffCheckinResponseDto res = new StaffCheckinResponseDto();
        res.setSuccess(true);
        res.setCode("SUCCESS");
        res.setMessage("Check-in successful — Ticket marked as USED");
        res.setBookingId(booking.getBookingId());
        res.setBookingCode(booking.getBookingCode());
        if (showtime != null) {
            if (showtime.getMovie() != null) {
                res.setMovieTitle(showtime.getMovie().getTitle());
            }
            if (showtime.getRoom() != null) {
                res.setRoomName(showtime.getRoom().getRoomName());
                if (showtime.getRoom().getCinema() != null) {
                    res.setCinemaName(showtime.getRoom().getCinema().getCinemaName());
                }
            }
            if (showtime.getStartAt() != null) {
                res.setShowtimeStart(
                        showtime.getStartAt().format(DateTimeFormatter.ofPattern("HH:mm - dd/MM/yyyy")));
            }
        }
        res.setTotalTickets(tickets.size());
        res.setCheckedInTickets(tickets.size());
        res.setSeats(seatLabels);
        res.setCheckInStatus("USED");
        res.setStaffName(staff.getFullName() != null ? staff.getFullName() : "Ticket Staff");
        res.setCheckedInAt(now);
        res.setResult("USED");

        logStaffAudit(staff, "TICKET_CHECKIN_USED", "/api/staff/bookings/check-in",
                "Checked in " + booking.getBookingCode() + " (" + String.join(", ", seatLabels) + ")");
        return res;
    }

    private Optional<BookingQr> resolveLockedBookingQr(String tokenClean) {
        Matcher mToken = BOX_TOKEN_PATTERN.matcher(tokenClean);
        if (mToken.find()) {
            String extractedToken = mToken.group();
            String extractedHash = CryptoUtils.sha256(extractedToken);
            Optional<BookingQr> qr = bookingQrRepository.findByTokenHashForUpdate(extractedHash);
            if (qr.isPresent()) {
                return qr;
            }
        }

        String tokenHash = CryptoUtils.sha256(tokenClean);
        Optional<BookingQr> directHash = bookingQrRepository.findByTokenHashForUpdate(tokenHash);
        if (directHash.isPresent()) {
            return directHash;
        }

        // Counter fallback for authenticated staff typing printed bookingCode (e.g. BOX-20261116-3484)
        Matcher mCode = BOOKING_CODE_PATTERN.matcher(tokenClean);
        if (mCode.find()) {
            Optional<Booking> bOpt = bookingRepository.findByBookingCode(mCode.group());
            if (bOpt.isPresent()) {
                return bookingQrRepository.findByBookingIdForUpdate(bOpt.get().getBookingId());
            }
        }
        return Optional.empty();
    }

    private StaffCheckinResponseDto buildFailure(String code, String message, Booking booking) {
        StaffCheckinResponseDto fail = new StaffCheckinResponseDto();
        fail.setSuccess(false);
        fail.setCode(code);
        fail.setMessage(message);
        if (booking != null) {
            fail.setBookingId(booking.getBookingId());
            fail.setBookingCode(booking.getBookingCode());
        }
        return fail;
    }

    @Override
    @Transactional(readOnly = true)
    public BookingResponseDto getBookingForStaff(Long bookingId) {
        return bookingService.getBookingById(bookingId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingCheckin> getCheckinHistory() {
        return checkinRepository.findAllByOrderByCheckedInAtDesc();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingCheckin> getCheckinHistoryForStaff(Long staffUserId) {
        List<BookingCheckin> all = checkinRepository.findAllByOrderByCheckedInAtDesc();
        if (staffUserId == null) {
            return all;
        }
        User staff = userRepository.findById(staffUserId).orElse(null);
        if (staff == null || "ADMIN".equalsIgnoreCase(staff.getRole()) || staff.getCinemaId() == null) {
            return all;
        }
        Long assignedCinemaId = staff.getCinemaId();
        return all.stream()
                .filter(c -> c.getBooking() != null
                        && c.getBooking().getShowtime() != null
                        && c.getBooking().getShowtime().getRoom() != null
                        && c.getBooking().getShowtime().getRoom().getCinema() != null
                        && assignedCinemaId.equals(c.getBooking().getShowtime().getRoom().getCinema().getCinemaId()))
                .toList();
    }

    @Override
    public Map<String, Object> recordTicketPrint(Long bookingId, Long staffUserId) {
        if (staffUserId == null) {
            throw new AccessDeniedException("Staff authentication is required.");
        }
        User staff = userRepository.findById(staffUserId)
                .orElseThrow(() -> new AccessDeniedException("Authenticated staff account not found."));
        String role = staff.getRole() != null ? staff.getRole().trim().toUpperCase() : "";
        if (!"STAFF".equals(role) && !"MANAGER".equals(role) && !"ADMIN".equals(role)) {
            throw new AccessDeniedException("Only authorized cinema staff can print tickets.");
        }

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
        verifyStaffCinemaBranch(staff, role, booking, "PRINT_TICKET_DENIED_BRANCH");

        String logUrl = "/t/booking/" + booking.getBookingId();
        long priorPrints = userActivityLogRepository != null
                ? userActivityLogRepository.countByActionTypeAndPageUrl(ACTION_PRINT_TICKET, logUrl)
                : 0L;
        if (userActivityLogRepository != null) {
            UserActivityLog entry = new UserActivityLog(
                    staff.getUserId(),
                    staff.getEmail(),
                    role,
                    ACTION_PRINT_TICKET,
                    logUrl,
                    "Printed physical slips for " + booking.getBookingCode(),
                    "counter",
                    null,
                    "POS/Counter"
            );
            userActivityLogRepository.save(entry);
        }

        LocalDateTime printedAt = LocalDateTime.now();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("bookingId", booking.getBookingId());
        out.put("bookingCode", booking.getBookingCode());
        out.put("printCount", priorPrints + 1);
        out.put("printedAt", printedAt.format(DateTimeFormatter.ofPattern("HH:mm:ss - dd/MM/yyyy")));
        return out;
    }

    private void verifyStaffCinemaBranch(User staff, String role, Booking booking, String auditAction) {
        if ("ADMIN".equals(role) || staff.getCinemaId() == null || booking == null) {
            return;
        }
        Showtime st = booking.getShowtime();
        Cinema targetCinema = (st != null && st.getRoom() != null) ? st.getRoom().getCinema() : null;
        Long targetCinemaId = targetCinema != null ? targetCinema.getCinemaId() : null;
        if (targetCinemaId != null && !staff.getCinemaId().equals(targetCinemaId)) {
            String assignedName = cinemaRepository != null
                    ? cinemaRepository.findById(staff.getCinemaId())
                            .map(Cinema::getCinemaName)
                            .orElse("your assigned cinema branch")
                    : "your assigned cinema branch";
            String targetName = targetCinema.getCinemaName() != null
                    ? targetCinema.getCinemaName()
                    : "another cinema branch";
            logStaffAudit(staff, auditAction, "/api/staff/bookings",
                    "Blocked cross-branch access to " + booking.getBookingCode() + " (" + targetName + ")");
            throw new AccessDeniedException(
                    "WRONG_CINEMA_BRANCH|This ticket belongs to " + targetName
                            + " and cannot be processed from " + assignedName + ".");
        }
    }

    private void logStaffAudit(User staff, String action, String url, String title) {
        if (userActivityLogService == null || staff == null) {
            return;
        }
        try {
            userActivityLogService.logActivityAsync(
                    staff.getUserId(),
                    staff.getEmail(),
                    staff.getRole(),
                    action,
                    url,
                    title,
                    "counter",
                    null,
                    "Staff Portal"
            );
        } catch (Exception ignored) {
        }
    }
}
