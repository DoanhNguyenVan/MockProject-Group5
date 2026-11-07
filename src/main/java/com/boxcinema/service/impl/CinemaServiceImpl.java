package com.boxcinema.service.impl;

import com.boxcinema.dto.CinemaDto;
import com.boxcinema.entity.Cinema;
import com.boxcinema.repository.CinemaRepository;
import com.boxcinema.repository.ShowtimeRepository;
import com.boxcinema.service.CinemaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CinemaServiceImpl implements CinemaService {

    private final CinemaRepository cinemaRepository;
    private final ShowtimeRepository showtimeRepository;

    public CinemaServiceImpl(CinemaRepository cinemaRepository, ShowtimeRepository showtimeRepository) {
        this.cinemaRepository = cinemaRepository;
        this.showtimeRepository = showtimeRepository;
    }

    @Override
    public List<Cinema> getAllActiveCinemas() {
        return cinemaRepository.findByStatusOrderByCinemaNameAsc("ACTIVE");
    }

    @Override
    public List<CinemaDto> getActiveCinemaDtos() {
        return getAllActiveCinemas().stream()
                .map(c -> new CinemaDto(c.getCinemaId(), c.getCinemaName(), c.getCity()))
                .collect(Collectors.toList());
    }

    @Override
    public List<CinemaDto> getCinemasShowingMovie(Long movieId) {
        List<Cinema> cinemas = showtimeRepository.findCinemasByMovie(movieId, LocalDateTime.now().minusHours(2));
        if (cinemas == null || cinemas.isEmpty()) {
            return getActiveCinemaDtos();
        }
        return cinemas.stream()
                .map(c -> new CinemaDto(c.getCinemaId(), c.getCinemaName(), c.getCity()))
                .collect(Collectors.toList());
    }
}

