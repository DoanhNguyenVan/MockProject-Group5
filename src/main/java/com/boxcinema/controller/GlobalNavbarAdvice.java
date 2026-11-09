package com.boxcinema.controller;

import com.boxcinema.entity.Cinema;
import com.boxcinema.service.CinemaService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ControllerAdvice
public class GlobalNavbarAdvice {

    private final CinemaService cinemaService;

    public GlobalNavbarAdvice(CinemaService cinemaService) {
        this.cinemaService = cinemaService;
    }

    @ModelAttribute("cinemasByCity")
    public Map<String, List<Cinema>> populateCinemasByCity() {
        return cinemaService.getAllActiveCinemas().stream()
                .collect(Collectors.groupingBy(Cinema::getCity, LinkedHashMap::new, Collectors.toList()));
    }
}

