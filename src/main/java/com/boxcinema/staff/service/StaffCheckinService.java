package com.boxcinema.staff.service;

import com.boxcinema.common.dto.BookingResponseDto;
import com.boxcinema.staff.dto.response.StaffCheckinResponseDto;
import com.boxcinema.common.entity.BookingCheckin;

import java.util.List;

public interface StaffCheckinService {
    StaffCheckinResponseDto checkinByQrToken(String qrToken, Long staffUserId);
    BookingResponseDto getBookingForStaff(Long bookingId);
    List<BookingCheckin> getCheckinHistory();
}
