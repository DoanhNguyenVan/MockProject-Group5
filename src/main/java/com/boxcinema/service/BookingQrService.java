package com.boxcinema.service;

import com.boxcinema.entity.BookingQr;

public interface BookingQrService {
    BookingQr getOrCreateBookingQr(Long bookingId);
    byte[] getBookingQrPngBytes(Long bookingId);
    String getBookingTicketUrl(Long bookingId);
}
