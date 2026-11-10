package com.boxcinema.controller;

import com.boxcinema.dto.UserSummaryDto;
import com.boxcinema.entity.Cinema;
import com.boxcinema.service.CinemaService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ControllerAdvice
public class GlobalNavbarAdvice {

    private final CinemaService cinemaService;

    public GlobalNavbarAdvice(@Autowired(required = false) CinemaService cinemaService) {
        this.cinemaService = cinemaService;
    }

    @ModelAttribute("cinemasByCity")
    public Map<String, List<Cinema>> populateCinemasByCity() {
        if (cinemaService == null) {
            return Collections.emptyMap();
        }
        return cinemaService.getAllActiveCinemas().stream()
                .collect(Collectors.groupingBy(Cinema::getCity, LinkedHashMap::new, Collectors.toList()));
    }

    @ModelAttribute("currentUser")
    public UserSummaryDto populateCurrentUser(HttpSession session) {
        if (session == null) {
            return null;
        }
        return (UserSummaryDto) session.getAttribute("currentUser");
    }
}

