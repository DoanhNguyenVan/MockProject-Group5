package com.boxcinema.service.impl;

import com.boxcinema.dto.BookingResponseDto;
import com.boxcinema.dto.StaffCheckinResponseDto;
import com.boxcinema.entity.*;
import com.boxcinema.repository.*;
import com.boxcinema.service.BookingService;
import com.boxcinema.service.StaffCheckinService;
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
        String tokenHash = CryptoHelper.sha256(tokenClean);

        // Search by tokenHash or direct match with pessimistic write lock
        Optional<BookingQr> bQrOpt = bookingQrRepository.findByTokenHashForUpdate(tokenHash);
        if (bQrOpt.isEmpty()) {
            bQrOpt = bookingQrRepository.findByTokenHashForUpdate(tokenClean);
        }

        // Support extracting BOX1.<token> if embedded inside rich ticket text or URL
        if (bQrOpt.isEmpty()) {
            java.util.regex.Matcher mToken = java.util.regex.Pattern.compile("BOX1\\.[a-zA-Z0-9_-]+")

                    .matcher(tokenClean);
            if (mToken.find()) {
                String extractedToken = mToken.group();
                String extractedHash = CryptoHelper.sha256(extractedToken);
                bQrOpt = bookingQrRepository.findByTokenHashForUpdate(extractedHash);
                if (bQrOpt.isEmpty()) {
                    bQrOpt = bookingQrRepository.findByTokenHashForUpdate(extractedToken);
                }
            }
        }

        // Fallback by bookingCode, e.g. BOX-20261116-3484
        if (bQrOpt.isEmpty()) {
            java.util.regex.Matcher mCode = java.util.regex.Pattern.compile("BOX-\\d{8}-\\d{4}").matcher(tokenClean);
            if (mCode.find()) {
                String bookingCode = mCode.group();
                Optional<Booking> bOpt = bookingRepository.findByBookingCode(bookingCode);
                if (bOpt.isPresent()) {
                    bQrOpt = bookingQrRepository.findByBooking_BookingId(bOpt.get().getBookingId());
                }
            }
        }

        if (bQrOpt.isEmpty()) {
            StaffCheckinResponseDto fail = new StaffCheckinResponseDto();
            fail.setSuccess(false);
            fail.setCode("INVALID_QR_TOKEN");
            fail.setMessage("QR code does not exist in the system or is invalid.");
            return fail;
        }

        BookingQr bookingQr = bQrOpt.get();
        Booking booking = bookingRepository.findByIdForUpdate(bookingQr.getBooking().getBookingId())
                .orElseThrow(() -> new IllegalStateException("No booking found associated with this QR code."));

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

        if (!"ACTIVE".equalsIgnoreCase(bookingQr.getStatus())) {
            StaffCheckinResponseDto fail = new StaffCheckinResponseDto();
            fail.setSuccess(false);
            fail.setCode("QR_NOT_ACTIVE");
            fail.setMessage("QR code has been revoked or is no longer valid.");
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

        User staff = staffUserId != null ? userRepository.findById(staffUserId).orElse(null) : null;
        LocalDateTime now = LocalDateTime.now();

        // Check and mark all Tickets in booking as USED (Section 4 & 30: 1 QR marks ALL
        // tickets in booking)
        List<Ticket> tickets = ticketRepository.findByBookingIdForUpdate(booking.getBookingId());
        List<String> seatLabels = new ArrayList<>();
        for (Ticket t : tickets) {
            t.setTicketStatus("USED");
            t.setCheckedInAt(now);
            t.setUsedAt(now);
            t.setCheckedInBy(staff);

            if (booking.getBookingSeats() != null) {
                booking.getBookingSeats().stream()
                        .filter(bs -> bs.getBookingSeatId().equals(t.getBookingSeatId()))
                        .findFirst()
                        .ifPresent(bs -> seatLabels.add(bs.getSeatLabelSnapshot()));
            }
        }
        ticketRepository.saveAll(tickets);

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
        res.setCheckedInTickets(tickets.size());
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
        return bookingService.getBookingById(bookingId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingCheckin> getCheckinHistory() {
        return checkinRepository.findAllByOrderByCheckedInAtDesc();
    }
}
