package com.boxcinema.common.service;

import com.boxcinema.common.dto.CinemaDto;
import com.boxcinema.common.entity.Cinema;

import java.util.List;

public interface CinemaService {
    List<Cinema> getAllActiveCinemas();
    List<CinemaDto> getActiveCinemaDtos();
    List<CinemaDto> getCinemasShowingMovie(Long movieId);
}

