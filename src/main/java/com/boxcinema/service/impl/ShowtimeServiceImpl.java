package com.boxcinema.service.impl;

import com.boxcinema.dto.ShowtimeOptionDto;
import com.boxcinema.entity.Showtime;
import com.boxcinema.repository.ShowtimeRepository;
import com.boxcinema.service.ShowtimeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ShowtimeServiceImpl implements ShowtimeService {

    private final ShowtimeRepository showtimeRepository;

    public ShowtimeServiceImpl(ShowtimeRepository showtimeRepository) {
        this.showtimeRepository = showtimeRepository;
    }

    @Override
    public List<ShowtimeOptionDto> getShowtimeOptions(Long movieId, Long cinemaId, LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.atTime(LocalTime.MAX);

        List<Showtime> showtimes = showtimeRepository.findShowtimesByMovieCinemaAndDate(movieId, cinemaId, start, end);
        return showtimes.stream()
                .map(s -> new ShowtimeOptionDto(
                        s.getShowtimeId(),
                        s.getFormattedStartTime(),
                        s.getRoom().getRoomName(),
                        s.getRoom().getRoomType(),
                        s.getBasePrice()
                ))
                .collect(Collectors.toList());
    }

    @Override
    public List<LocalDate> getAvailableDates(Long movieId, Long cinemaId) {
        LocalDate today = LocalDate.now();
        List<LocalDate> dates = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            dates.add(today.plusDays(i));
        }
        return dates;
    }
}

