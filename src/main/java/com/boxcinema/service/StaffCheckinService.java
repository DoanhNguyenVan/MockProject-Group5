package com.boxcinema.service;

import com.boxcinema.dto.BookingResponseDto;
import com.boxcinema.dto.StaffCheckinResponseDto;
import com.boxcinema.entity.BookingCheckin;

import java.util.List;

public interface StaffCheckinService {
    StaffCheckinResponseDto checkinByQrToken(String qrToken, Long staffUserId);
    BookingResponseDto getBookingForStaff(Long bookingId);
    List<BookingCheckin> getCheckinHistory();
}
