package com.boxcinema.customer.dto.response;

import java.math.BigDecimal;

public record ScreeningContextResponse(
        String showtimeId,
        String movieId,
        String movieTitle,
        String ageRating,
        String posterUrl,
        String format,
        String cinemaId,
        String cinemaName,
        String roomName,
        String screeningLabel,
        BigDecimal basePrice
) {
}
