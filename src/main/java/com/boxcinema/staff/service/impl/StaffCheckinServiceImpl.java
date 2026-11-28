package com.boxcinema.staff.service.impl;

import com.boxcinema.common.dto.BookingResponseDto;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.staff.dto.response.StaffCheckinHistoryDto;
import com.boxcinema.staff.dto.response.StaffCheckinResponseDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BookingService;
import com.boxcinema.staff.service.StaffCheckinService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class StaffCheckinServiceImpl implements StaffCheckinService {

    private final BookingQrRepository bookingQrRepository;
    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final BookingCheckinRepository checkinRepository;
    private final UserRepository userRepository;
    private final BookingService bookingService;

    public StaffCheckinServiceImpl(
            BookingQrRepository bookingQrRepository,
            BookingRepository bookingRepository,
            TicketRepository ticketRepository,
            BookingCheckinRepository checkinRepository,
            UserRepository userRepository,
            BookingService bookingService) {
        this.bookingQrRepository = bookingQrRepository;
        this.bookingRepository = bookingRepository;
        this.ticketRepository = ticketRepository;
        this.checkinRepository = checkinRepository;
        this.userRepository = userRepository;
        this.bookingService = bookingService;
    }

    @Override
    public StaffCheckinResponseDto checkinByQrToken(String qrToken, Long staffUserId) {
        if (qrToken == null || qrToken.trim().isBlank()) {
            throw new IllegalArgumentException("QR code is invalid or empty.");
        }

        String tokenClean = qrToken.trim();
        String tokenHash = com.boxcinema.common.util.CryptoUtils.sha256(tokenClean);

        // Read-only lookup first to resolve bookingId, then acquire locks in canonical order:
        // Booking -> Ticket -> BookingQr (prevents AB-BA deadlock with RefundApprovalTxService)
        Optional<BookingQr> bQrOpt = bookingQrRepository.findByTokenHash(tokenHash);
        if (bQrOpt.isEmpty()) {
            bQrOpt = bookingQrRepository.findByTokenHash(tokenClean);
        }

        // Support extracting BOX1.<token> if embedded inside rich ticket text or URL
        if (bQrOpt.isEmpty()) {
            java.util.regex.Matcher mToken = java.util.regex.Pattern.compile("BOX1\\.[a-zA-Z0-9_-]+")
                    .matcher(tokenClean);
            if (mToken.find()) {
                String extractedToken = mToken.group();
                String extractedHash = com.boxcinema.common.util.CryptoUtils.sha256(extractedToken);
                bQrOpt = bookingQrRepository.findByTokenHash(extractedHash);
                if (bQrOpt.isEmpty()) {
                    bQrOpt = bookingQrRepository.findByTokenHash(extractedToken);
                }
            }
        }

        if (bQrOpt.isEmpty() || bQrOpt.get().getBooking() == null) {
            StaffCheckinResponseDto fail = new StaffCheckinResponseDto();
            fail.setSuccess(false);
            fail.setCode("INVALID_QR_TOKEN");
            fail.setMessage("QR code does not exist in the system or is invalid.");
            return fail;
        }

        User staff = requireStaffActor(staffUserId);
        Long bookingId = bQrOpt.get().getBooking().getBookingId();
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new IllegalStateException("No booking found associated with this QR code."));

        if (!"ADMIN".equalsIgnoreCase(staff.getRole())) {
            Long staffCinemaId = staff.getCinemaId();
            Long bookingCinemaId = booking.resolveCinemaId();
            if (staffCinemaId == null || !staffCinemaId.equals(bookingCinemaId)) {
                StaffCheckinResponseDto fail = new StaffCheckinResponseDto();
                fail.setSuccess(false);
                fail.setCode("BRANCH_ACCESS_DENIED");
                fail.setMessage("Access denied: booking belongs to another cinema branch.");
                fail.setBookingId(booking.getBookingId());
                fail.setBookingCode(booking.getBookingCode());
                return fail;
            }
        }

        // Acquire Ticket and BookingQr locks in canonical order after Booking
        List<Ticket> tickets = ticketRepository.findByBookingIdForUpdate(booking.getBookingId());
        BookingQr bookingQr = bookingQrRepository.findByBookingIdForUpdate(bookingId)
                .orElse(bQrOpt.get());

        // Check if already checked in (Section 32 / QR-11: Second scan rejection)
        if ("USED".equalsIgnoreCase(bookingQr.getStatus())
                || checkinRepository.findByBooking_BookingId(booking.getBookingId()).isPresent()) {
            StaffCheckinResponseDto fail = new StaffCheckinResponseDto();
            fail.setSuccess(false);
            fail.setCode("BOOKING_ALREADY_CHECKED_IN");
            fail.setMessage("Booking has already been checked in");
            fail.setBookingId(booking.getBookingId());
            fail.setBookingCode(booking.getBookingCode());
            return fail;
        }

        LocalDateTime now = LocalDateTime.now();

        if (!"ACTIVE".equalsIgnoreCase(bookingQr.getStatus())) {
            StaffCheckinResponseDto fail = new StaffCheckinResponseDto();
            fail.setSuccess(false);
            fail.setCode("QR_NOT_ACTIVE");
            fail.setMessage("QR code has been revoked or is no longer valid.");
            return fail;
        }

        if ((bookingQr.getExpiresAt() != null && bookingQr.getExpiresAt().isBefore(now))
                || (booking.getShowtime() != null && booking.getShowtime().getEndAt() != null
                        && booking.getShowtime().getEndAt().plusHours(4).isBefore(now))) {
            StaffCheckinResponseDto fail = new StaffCheckinResponseDto();
            fail.setSuccess(false);
            fail.setCode("QR_EXPIRED");
            fail.setMessage("QR code has expired.");
            fail.setBookingId(booking.getBookingId());
            fail.setBookingCode(booking.getBookingCode());
            return fail;
        }

        if (!"CONFIRMED".equalsIgnoreCase(booking.getBookingStatus())) {
            StaffCheckinResponseDto fail = new StaffCheckinResponseDto();
            fail.setSuccess(false);
            fail.setCode("BOOKING_NOT_CONFIRMED");
            fail.setMessage(
                    "Booking has not been paid successfully (Status: " + booking.getBookingStatus() + ").");
            return fail;
        }

        // Check and mark all non-refunded VALID Tickets in booking as USED
        List<Ticket> checkInTickets = new ArrayList<>();
        List<String> seatLabels = new ArrayList<>();
        for (Ticket t : tickets) {
            if ("REFUNDED".equalsIgnoreCase(t.getTicketStatus()) || "REFUNDED".equalsIgnoreCase(t.getRefundStatus())) {
                continue;
            }
            t.setTicketStatus("USED");
            t.setCheckedInAt(now);
            t.setUsedAt(now);
            t.setCheckedInBy(staff);
            checkInTickets.add(t);

            if (booking.getBookingSeats() != null) {
                booking.getBookingSeats().stream()
                        .filter(bs -> bs.getBookingSeatId().equals(t.getBookingSeatId()))
                        .findFirst()
                        .ifPresent(bs -> seatLabels.add(bs.getSeatLabelSnapshot()));
            }
        }
        if (checkInTickets.isEmpty()) {
            StaffCheckinResponseDto fail = new StaffCheckinResponseDto();
            fail.setSuccess(false);
            if (tickets.isEmpty()) {
                fail.setCode("NO_TICKETS_TO_CHECK_IN");
                fail.setMessage("This booking has no movie tickets to check in.");
            } else {
                fail.setCode("ALL_TICKETS_REFUNDED");
                fail.setMessage("All tickets in this booking have been refunded.");
            }
            fail.setBookingId(booking.getBookingId());
            fail.setBookingCode(booking.getBookingCode());
            return fail;
        }
        ticketRepository.saveAll(checkInTickets);

        // Mark BookingQr as USED
        bookingQr.setStatus("USED");
        bookingQr.setUsedAt(now);
        bookingQrRepository.save(bookingQr);

        // Record BookingCheckin
        BookingCheckin checkin = new BookingCheckin(booking, staff, "SUCCESS");
        checkin.setCheckedInAt(now);
        checkinRepository.save(checkin);

        // Build Response DTO (Section 31)
        StaffCheckinResponseDto res = new StaffCheckinResponseDto();
        res.setSuccess(true);
        res.setCode("SUCCESS");
        res.setMessage("Check-in successful");
        res.setBookingId(booking.getBookingId());
        res.setBookingCode(booking.getBookingCode());
        if (booking.getShowtime() != null) {
            if (booking.getShowtime().getMovie() != null) {
                res.setMovieTitle(booking.getShowtime().getMovie().getTitle());
            }
            if (booking.getShowtime().getRoom() != null) {
                res.setRoomName(booking.getShowtime().getRoom().getRoomName());
                if (booking.getShowtime().getRoom().getCinema() != null) {
                    res.setCinemaName(booking.getShowtime().getRoom().getCinema().getCinemaName());
                }
            }
            if (booking.getShowtime().getStartAt() != null) {
                res.setShowtimeStart(
                        booking.getShowtime().getStartAt().format(DateTimeFormatter.ofPattern("HH:mm - dd/MM/yyyy")));
            }
        }
        res.setTotalTickets(tickets.size());
        res.setCheckedInTickets(checkInTickets.size());
        res.setSeats(seatLabels);
        res.setCheckInStatus("CHECKED_IN");
        res.setStaffName(staff != null ? staff.getFullName() : "Ticket Staff");
        res.setCheckedInAt(now);
        res.setResult("CHECKED_IN");
        return res;
    }

    @Override
    @Transactional(readOnly = true)
    public BookingResponseDto getBookingForStaff(Long bookingId) {
        return getBookingForStaff(bookingId, SecurityContextUtils.resolveCurrentUserId());
    }

    @Override
    @Transactional(readOnly = true)
    public BookingResponseDto getBookingForStaff(Long bookingId, Long staffUserId) {
        User staff = requireStaffActor(staffUserId);
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Booking not found."));
        if (!"ADMIN".equalsIgnoreCase(staff.getRole())) {
            Long staffCinemaId = staff.getCinemaId();
            Long bookingCinemaId = booking.resolveCinemaId();
            if (staffCinemaId == null || !staffCinemaId.equals(bookingCinemaId)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "BRANCH_ACCESS_DENIED",
                        "Access denied: booking belongs to another cinema branch.");
            }
        }
        return bookingService.getBookingById(bookingId, staffUserId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingCheckin> getCheckinHistory() {
        return getCheckinHistory(SecurityContextUtils.resolveCurrentUserId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingCheckin> getCheckinHistory(Long staffUserId) {
        User staff = requireStaffActor(staffUserId);
        if ("ADMIN".equalsIgnoreCase(staff.getRole())) {
            return checkinRepository.findAllByOrderByCheckedInAtDesc();
        }
        if (staff.getCinemaId() == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "NO_BRANCH_ASSIGNED",
                    "Staff account has no assigned cinema branch.");
        }
        return checkinRepository.findByCinemaScopeOrderByCheckedInAtDesc(staff.getCinemaId());
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffCheckinHistoryDto> getCheckinHistoryDtos(Long staffUserId) {
        return getCheckinHistory(staffUserId).stream()
                .map(bc -> {
                    StaffCheckinHistoryDto dto = new StaffCheckinHistoryDto();
                    dto.setId(bc.getId());
                    dto.setCheckedInAt(bc.getCheckedInAt());
                    dto.setResult(bc.getResult());
                    dto.setStatus("SUCCESS".equalsIgnoreCase(bc.getResult()) ? "CHECKED_IN" : bc.getResult());
                    Booking b = bc.getBooking();
                    if (b != null) {
                        dto.setBooking(new StaffCheckinHistoryDto.BookingRefDto(b.getBookingId(), b.getBookingCode()));
                        int seatCount = (b.getBookingSeats() != null && !b.getBookingSeats().isEmpty())
                                ? b.getBookingSeats().size() : 1;
                        dto.setSeatsCount(seatCount);
                    } else {
                        dto.setSeatsCount(1);
                    }
                    return dto;
                })
                .toList();
    }

    private User requireStaffActor(Long staffUserId) {
        if (staffUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Staff authentication required.");
        }
        return userRepository.findById(staffUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Staff user not found."));
    }
}
