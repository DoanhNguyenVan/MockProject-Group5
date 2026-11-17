package com.boxcinema.service;

import com.boxcinema.dto.BookingCreateRequestDto;
import com.boxcinema.dto.BookingResponseDto;
import com.boxcinema.dto.UpdateFoodItemsRequestDto;

public interface BookingService {
    BookingResponseDto createBooking(BookingCreateRequestDto request, Long currentUserId);
    BookingResponseDto updateFoodItems(Long bookingId, UpdateFoodItemsRequestDto request, Long currentUserId);
    BookingResponseDto confirmBooking(Long bookingId, Long currentUserId);
    BookingResponseDto getBookingById(Long bookingId);
}
