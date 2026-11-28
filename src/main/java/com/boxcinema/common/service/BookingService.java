package com.boxcinema.common.service;

import com.boxcinema.customer.dto.request.BookingCreateRequestDto;
import com.boxcinema.common.dto.BookingResponseDto;
import com.boxcinema.customer.dto.request.UpdateFoodItemsRequestDto;

public interface BookingService {
    BookingResponseDto createBooking(BookingCreateRequestDto request, Long currentUserId);
    BookingResponseDto updateFoodItems(Long bookingId, UpdateFoodItemsRequestDto request, Long currentUserId);
    BookingResponseDto confirmBooking(Long bookingId, Long currentUserId);
    BookingResponseDto getBookingById(Long bookingId);
    BookingResponseDto getBookingById(Long bookingId, Long currentUserId);
    default BookingResponseDto applyVoucherToBooking(Long bookingId, String voucherCode, Long currentUserId) {
        throw new UnsupportedOperationException("Not implemented");
    }
}
