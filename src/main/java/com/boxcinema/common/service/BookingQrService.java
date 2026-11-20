package com.boxcinema.common.service;

import com.boxcinema.common.entity.BookingQr;

public interface BookingQrService {
    BookingQr getOrCreateBookingQr(Long bookingId);
    byte[] getBookingQrPngBytes(Long bookingId);
    String getBookingTicketUrl(Long bookingId);
}
