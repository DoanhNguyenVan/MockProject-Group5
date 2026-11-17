package com.boxcinema.service.impl;

import com.boxcinema.dto.DateTabDto;
import com.boxcinema.dto.MovieShowtimeGroupDto;
import com.boxcinema.dto.ShowtimeOptionDto;
import com.boxcinema.entity.Movie;
import com.boxcinema.entity.Showtime;
import com.boxcinema.repository.RoomRepository;
import com.boxcinema.repository.ShowtimeRepository;
import com.boxcinema.service.ShowtimeService;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final RoomRepository roomRepository;
    private final JdbcTemplate jdbcTemplate;

    public ShowtimeServiceImpl(ShowtimeRepository showtimeRepository,
                               RoomRepository roomRepository,
                               JdbcTemplate jdbcTemplate) {
        this.showtimeRepository = showtimeRepository;
        this.roomRepository = roomRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ShowtimeOptionDto> getShowtimeOptions(Long movieId, Long cinemaId, LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        if (date.isBefore(LocalDate.now())) {
            return Collections.emptyList();
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = date.equals(LocalDate.now()) ? now : date.atStartOfDay();
        LocalDateTime end = date.atTime(LocalTime.MAX);

        List<Showtime> showtimes = showtimeRepository.findShowtimesByMovieCinemaAndDate(movieId, cinemaId, start, end);
        return showtimes.stream()
                .filter(s -> s.getStartAt() != null && s.getStartAt().isAfter(now))
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
        List<Object[]> pairs = showtimeRepository.findActiveMovieCinemaPairs(LocalDateTime.now());
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
    public List<String> getAllActiveRoomFormats() {
        return roomRepository.findDistinctActiveRoomTypes();
    }

    @Override
    public Map<Long, String> getMovieFormatsMap() {
        List<Object[]> pairs = showtimeRepository.findActiveMovieRoomTypes();
        Map<Long, Set<String>> map = new LinkedHashMap<>();
        for (Object[] row : pairs) {
            Long movieId = ((Number) row[0]).longValue();
            String roomType = (String) row[1];
            if (roomType != null && !roomType.isBlank()) {
                map.computeIfAbsent(movieId, k -> new LinkedHashSet<>()).add(roomType);
            }
        }
        Map<Long, String> result = new HashMap<>();
        for (Map.Entry<Long, Set<String>> entry : map.entrySet()) {
            result.put(entry.getKey(), String.join(",", entry.getValue()));
        }
        return result;
    }

    @Override
    public List<MovieShowtimeGroupDto> getMovieShowtimeGroups(Long cinemaId, LocalDate date) {
        if (date == null) {
            date = LocalDate.now();
        }
        if (date.isBefore(LocalDate.now())) {
            return Collections.emptyList();
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = date.equals(LocalDate.now()) ? now : date.atStartOfDay();
        LocalDateTime end = date.atTime(LocalTime.MAX);

        List<Showtime> showtimes = showtimeRepository.findShowtimesByCinemaAndDate(cinemaId, start, end);
        Map<Long, MovieShowtimeGroupDto> groupMap = new LinkedHashMap<>();

        // Real room seat capacities from database
        Map<Long, Integer> roomCapacityMap = new HashMap<>();
        try {
            jdbcTemplate.query("SELECT room_id, COUNT(*) as cnt FROM seats WHERE status = 'ACTIVE' GROUP BY room_id", rs -> {
                roomCapacityMap.put(rs.getLong("room_id"), rs.getInt("cnt"));
            });
        } catch (Exception ignored) {
        }

        // Real unavailable seats (BOOKED or currently active HELD)
        Map<Long, Integer> showtimeOccupiedMap = new HashMap<>();
        try {
            jdbcTemplate.query(
                    "SELECT showtime_id, COUNT(*) as cnt FROM showtime_seats " +
                    "WHERE seat_status = 'BOOKED' OR (seat_status = 'HELD' AND hold_expires_at > NOW()) " +
                    "GROUP BY showtime_id",
                    rs -> {
                        showtimeOccupiedMap.put(rs.getLong("showtime_id"), rs.getInt("cnt"));
                    }
            );
        } catch (Exception ignored) {
        }

        for (Showtime s : showtimes) {
            if (s.getStartAt() == null || !s.getStartAt().isAfter(now)) {
                continue;
            }
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

            Long roomId = (s.getRoom() != null) ? s.getRoom().getRoomId() : null;
            int totalSeats = (roomId != null && roomCapacityMap.containsKey(roomId))
                    ? roomCapacityMap.get(roomId)
                    : (s.getRoom() != null && s.getRoom().getRoomType() != null && s.getRoom().getRoomType().toLowerCase().contains("imax") ? 70 : 60);
            int occupied = showtimeOccupiedMap.getOrDefault(s.getShowtimeId(), 0);
            int availableSeats = Math.max(0, totalSeats - occupied);

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


