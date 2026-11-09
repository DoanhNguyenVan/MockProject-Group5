package com.boxcinema.service.impl;

import com.boxcinema.dto.DateTabDto;
import com.boxcinema.dto.MovieShowtimeGroupDto;
import com.boxcinema.dto.ShowtimeOptionDto;
import com.boxcinema.entity.Movie;
import com.boxcinema.entity.Showtime;
import com.boxcinema.repository.ShowtimeRepository;
import com.boxcinema.service.ShowtimeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
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

    @Override
    public Map<Long, String> getMovieCinemaIdsMap() {
        List<Object[]> pairs = showtimeRepository.findActiveMovieCinemaPairs();
        Map<Long, Set<Long>> map = new HashMap<>();
        for (Object[] row : pairs) {
            Long movieId = ((Number) row[0]).longValue();
            Long cinemaId = ((Number) row[1]).longValue();
            map.computeIfAbsent(movieId, k -> new HashSet<>()).add(cinemaId);
        }
        Map<Long, String> result = new HashMap<>();
        for (Map.Entry<Long, Set<Long>> entry : map.entrySet()) {
            String ids = entry.getValue().stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(","));
            result.put(entry.getKey(), ids);
        }
        return result;
    }

    @Override
    public List<MovieShowtimeGroupDto> getMovieShowtimeGroups(Long cinemaId, LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.atTime(LocalTime.MAX);

        List<Showtime> showtimes = showtimeRepository.findShowtimesByCinemaAndDate(cinemaId, start, end);
        Map<Long, MovieShowtimeGroupDto> groupMap = new LinkedHashMap<>();

        for (Showtime s : showtimes) {
            Movie m = s.getMovie();
            if (m == null) continue;

            MovieShowtimeGroupDto dto = groupMap.computeIfAbsent(m.getMovieId(), k -> {
                MovieShowtimeGroupDto g = new MovieShowtimeGroupDto();
                g.setMovieId(m.getMovieId());
                g.setTitle(m.getTitle());
                g.setPosterUrl(m.getPosterUrl());
                g.setTrailerUrl(m.getTrailerUrl());
                g.setAgeRating(m.getAgeRating());
                g.setDurationMinutes(m.getDurationMinutes());
                g.setGenreNamesDisplay(m.getGenreNamesDisplay());
                g.setLanguage(m.getLanguage());
                return g;
            });

            String timeStr = s.getFormattedStartTime();
            String dateSub = s.getStartAt().format(DateTimeFormatter.ofPattern("dd/MM"));
            boolean isLate = s.getStartAt().getHour() >= 22;

            int capacity = 150;
            if (s.getRoom() != null && s.getRoom().getRoomType() != null) {
                String rt = s.getRoom().getRoomType().toLowerCase();
                if (rt.contains("imax")) capacity = 180;
                else if (rt.contains("dolby")) capacity = 160;
                else if (rt.contains("4dx")) capacity = 140;
            }
            int booked = Math.abs((int)(s.getShowtimeId() * 17 + 5)) % 45;
            int availableSeats = Math.max(10, capacity - booked);

            String roomType = (s.getRoom() != null && s.getRoom().getRoomType() != null) ? s.getRoom().getRoomType() : "Standard";
            String roomName = (s.getRoom() != null) ? s.getRoom().getRoomName() : "Screen";

            String formatKey = "2D SUBTITLES";
            if (roomType.toLowerCase().contains("imax")) {
                formatKey = "IMAX 2D";
            } else if (roomType.toLowerCase().contains("4dx")) {
                formatKey = "4DX 2D";
            } else if (roomType.toLowerCase().contains("dolby")) {
                formatKey = "2D DOLBY ATMOS";
            }

            MovieShowtimeGroupDto.SlotDto slot = new MovieShowtimeGroupDto.SlotDto(
                    s.getShowtimeId(),
                    timeStr,
                    dateSub,
                    isLate,
                    availableSeats,
                    s.getBasePrice(),
                    roomName,
                    roomType
            );

            dto.addSlot(formatKey, slot);
        }

        return new ArrayList<>(groupMap.values());
    }

    @Override
    public List<DateTabDto> getDateTabs(LocalDate selectedDate) {
        if (selectedDate == null) {
            selectedDate = LocalDate.now();
        }
        LocalDate today = LocalDate.now();
        List<DateTabDto> list = new ArrayList<>();

        for (int i = 0; i < 7; i++) {
            LocalDate d = today.plusDays(i);
            String dateStr = d.toString();
            String dayNumber = d.format(DateTimeFormatter.ofPattern("dd"));
            String month = d.format(DateTimeFormatter.ofPattern("MM"));
            String dayOfWeek = switch (d.getDayOfWeek()) {
                case MONDAY -> "Mon";
                case TUESDAY -> "Tue";
                case WEDNESDAY -> "Wed";
                case THURSDAY -> "Thu";
                case FRIDAY -> "Fri";
                case SATURDAY -> "Sat";
                case SUNDAY -> "Sun";
            };
            String monthAndDayOfWeek = "/" + month + " - " + dayOfWeek;
            boolean active = d.equals(selectedDate);
            list.add(new DateTabDto(dateStr, dayNumber, monthAndDayOfWeek, active));
        }
        return list;
    }
}


