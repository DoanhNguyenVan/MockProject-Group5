package com.boxcinema.customer.service;

import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Service handling live screening context, seat availability map, seat holding, and seat releasing.
 */
public interface SeatBookingContextService {

    ResponseEntity<?> getScreeningContext(Long showtimeId);

    ResponseEntity<?> getBookingData(Long showtimeId, Long callerUserId, String callerSessionId);

    ResponseEntity<?> holdSeats(Long showtimeId, Map<String, Object> body, Long callerUserId, String callerSessionId);

    ResponseEntity<?> releaseSeats(Long showtimeId, Long callerUserId, String callerSessionId);
}
