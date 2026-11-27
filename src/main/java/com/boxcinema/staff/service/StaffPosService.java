package com.boxcinema.staff.service;

import com.boxcinema.staff.dto.request.StaffPosCreateBookingRequest;
import com.boxcinema.staff.dto.response.StaffPosBookingResultDto;
import com.boxcinema.staff.dto.response.StaffPosOrderSummaryDto;
import com.boxcinema.staff.dto.response.StaffPosShiftSummaryDto;
import com.boxcinema.staff.dto.response.StaffPosShowtimeDto;

import java.time.LocalDate;
import java.util.List;

public interface StaffPosService {
    List<StaffPosShowtimeDto> getUpcomingShowtimes(Long staffUserId);
    String getAssignedCinemaName(Long staffUserId);
    StaffPosBookingResultDto createCounterBooking(StaffPosCreateBookingRequest request, Long staffUserId);
    void cancelPendingPosBooking(Long bookingId, Long staffUserId);
    byte[] getBookingQrPng(Long bookingId);
    List<StaffPosOrderSummaryDto> lookupBookings(Long staffUserId, String query);
    StaffPosShiftSummaryDto getShiftSummary(Long staffUserId, LocalDate date);
    StaffPosBookingResultDto getBookingResult(Long bookingId);
}
