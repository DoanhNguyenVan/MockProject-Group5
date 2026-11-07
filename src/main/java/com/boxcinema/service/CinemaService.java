package com.boxcinema.service;

import com.boxcinema.dto.CinemaDto;
import com.boxcinema.entity.Cinema;

import java.util.List;

public interface CinemaService {
    List<Cinema> getAllActiveCinemas();
    List<CinemaDto> getActiveCinemaDtos();
    List<CinemaDto> getCinemasShowingMovie(Long movieId);
}

