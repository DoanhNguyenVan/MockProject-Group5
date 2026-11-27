package com.boxcinema.staff.service;

import com.boxcinema.common.dto.BookingResponseDto;
import com.boxcinema.staff.dto.response.StaffCheckinResponseDto;
import com.boxcinema.common.entity.BookingCheckin;

import java.util.List;
import java.util.Map;

public interface StaffCheckinService {
    StaffCheckinResponseDto checkinByQrToken(String qrToken, Long staffUserId);
    BookingResponseDto getBookingForStaff(Long bookingId);
    List<BookingCheckin> getCheckinHistory();
    List<BookingCheckin> getCheckinHistoryForStaff(Long staffUserId);
    Map<String, Object> recordTicketPrint(Long bookingId, Long staffUserId);
}
