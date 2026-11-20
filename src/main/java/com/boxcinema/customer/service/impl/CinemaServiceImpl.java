package com.boxcinema.customer.service.impl;

import com.boxcinema.common.dto.CinemaDto;
import com.boxcinema.common.entity.Cinema;
import com.boxcinema.common.mapper.CatalogMapper;
import com.boxcinema.common.repository.CinemaRepository;
import com.boxcinema.common.repository.ShowtimeRepository;
import com.boxcinema.common.service.CinemaService;
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
                .map(CatalogMapper::toCinemaDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<CinemaDto> getCinemasShowingMovie(Long movieId) {
        List<Cinema> cinemas = showtimeRepository.findCinemasByMovie(movieId, LocalDateTime.now());
        if (cinemas == null || cinemas.isEmpty()) {
            return getActiveCinemaDtos();
        }
        return cinemas.stream()
                .map(CatalogMapper::toCinemaDto)
                .collect(Collectors.toList());
    }
}

