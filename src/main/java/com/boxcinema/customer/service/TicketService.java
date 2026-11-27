package com.boxcinema.customer.service;

import com.boxcinema.customer.dto.response.BookingHistoryDetailDto;
import com.boxcinema.customer.dto.response.BookingHistoryItemDto;
import com.boxcinema.common.dto.BookingTicketViewDto;
import java.util.List;
import java.util.Set;

public interface TicketService {
    BookingTicketViewDto getTicketView(Long bookingId, Long currentUserId);
    BookingTicketViewDto getTicketView(Long bookingId, Long currentUserId, Set<Long> guestSessionBookingIds);
    byte[] getBookingQrImage(Long bookingId, Long currentUserId);
    byte[] getBookingQrImage(Long bookingId, Long currentUserId, Set<Long> guestSessionBookingIds);
    void resendEmail(Long bookingId, Long currentUserId);
    void resendEmail(Long bookingId, Long currentUserId, Set<Long> guestSessionBookingIds);
    List<BookingHistoryItemDto> getBookingHistory(Long userId);
    BookingHistoryDetailDto getBookingDetail(Long bookingId, Long userId);
    BookingHistoryDetailDto getBookingDetail(Long bookingId, Long userId, Set<Long> guestSessionBookingIds);
    void assertCanAccessBooking(Long bookingId, Long currentUserId, Set<Long> guestSessionBookingIds);
}

