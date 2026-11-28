package com.boxcinema.customer.service;

import com.boxcinema.customer.dto.response.BookingHistoryDetailDto;
import com.boxcinema.customer.dto.response.BookingHistoryItemDto;
import com.boxcinema.common.dto.BookingTicketViewDto;
import java.util.List;

public interface TicketService {
    BookingTicketViewDto getTicketView(Long bookingId, Long currentUserId);
    BookingTicketViewDto getTicketViewForOutbox(Long bookingId);
    byte[] getBookingQrImage(Long bookingId, Long currentUserId);
    void resendEmail(Long bookingId, Long currentUserId);
    List<BookingHistoryItemDto> getBookingHistory(Long userId);
    BookingHistoryDetailDto getBookingDetail(Long bookingId, Long userId);
}

