package com.boxcinema.service;

import com.boxcinema.dto.BookingHistoryDetailDto;
import com.boxcinema.dto.BookingHistoryItemDto;
import com.boxcinema.dto.BookingTicketViewDto;
import java.util.List;

public interface TicketService {
    BookingTicketViewDto getTicketView(Long bookingId, Long currentUserId);
    byte[] getBookingQrImage(Long bookingId, Long currentUserId);
    void resendEmail(Long bookingId, Long currentUserId);
    List<BookingHistoryItemDto> getBookingHistory(Long userId);
    BookingHistoryDetailDto getBookingDetail(Long bookingId, Long userId);
}

