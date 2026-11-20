package com.boxcinema.common.service;

import com.boxcinema.customer.dto.request.BookingCreateRequestDto;
import com.boxcinema.common.dto.BookingResponseDto;
import com.boxcinema.customer.dto.request.UpdateFoodItemsRequestDto;

public interface BookingService {
    BookingResponseDto createBooking(BookingCreateRequestDto request, Long currentUserId);
    BookingResponseDto updateFoodItems(Long bookingId, UpdateFoodItemsRequestDto request, Long currentUserId);
    BookingResponseDto confirmBooking(Long bookingId, Long currentUserId);
    BookingResponseDto getBookingById(Long bookingId);
}
