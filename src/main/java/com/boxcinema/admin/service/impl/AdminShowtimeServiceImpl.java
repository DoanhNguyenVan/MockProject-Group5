package com.boxcinema.admin.service.impl;

import com.boxcinema.admin.dto.AdminShowtimeRequestDto;
import com.boxcinema.admin.service.AdminShowtimeService;
import com.boxcinema.common.entity.Cinema;
import com.boxcinema.common.entity.Movie;
import com.boxcinema.common.entity.Room;
import com.boxcinema.common.entity.Showtime;
import com.boxcinema.common.repository.BookingRepository;
import com.boxcinema.common.repository.CinemaRepository;
import com.boxcinema.common.repository.MovieRepository;
import com.boxcinema.common.repository.RoomRepository;
import com.boxcinema.common.repository.ShowtimeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class AdminShowtimeServiceImpl implements AdminShowtimeService {

    private final ShowtimeRepository showtimeRepository;
    private final MovieRepository movieRepository;
    private final CinemaRepository cinemaRepository;
    private final RoomRepository roomRepository;
    private final BookingRepository bookingRepository;

    public AdminShowtimeServiceImpl(
            ShowtimeRepository showtimeRepository,
            MovieRepository movieRepository,
            CinemaRepository cinemaRepository,
            RoomRepository roomRepository,
            BookingRepository bookingRepository) {
        this.showtimeRepository = showtimeRepository;
        this.movieRepository = movieRepository;
        this.cinemaRepository = cinemaRepository;
        this.roomRepository = roomRepository;
        this.bookingRepository = bookingRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Showtime> getShowtimes(Long cinemaId, String status) {
        List<Showtime> list = showtimeRepository.findAllForAdmin(cinemaId);
        if (status != null && !status.trim().isEmpty() && !status.equalsIgnoreCase("ALL")) {
            list = list.stream()
                    .filter(s -> status.equalsIgnoreCase(s.getStatus()))
                    .collect(Collectors.toList());
        }
        return list;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Cinema> getAllCinemas() {
        return cinemaRepository.findByStatusOrderByCinemaNameAsc("ACTIVE");
    }

    @Override
    @Transactional(readOnly = true)
    public List<Room> getAllActiveRooms() {
        return roomRepository.findAllActiveWithCinema();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Movie> getAllMovies() {
        return movieRepository.findAllWithGenres();
    }

    @Override
    @Transactional(readOnly = true)
    public Showtime getShowtimeById(Long id) {
        return showtimeRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Showtime not found with ID: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public Cinema getCinemaById(Long id) {
        return cinemaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Cinema not found with ID: " + id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Room> getRoomsByCinema(Long cinemaId) {
        return roomRepository.findByCinemaCinemaIdAndStatus(cinemaId, "ACTIVE");
    }

    @Override
    public Showtime createShowtime(AdminShowtimeRequestDto request) {
        validateRequest(request);

        Movie movie = movieRepository.findById(request.getMovieId())
                .orElseThrow(() -> new IllegalArgumentException("Movie not found with ID: " + request.getMovieId()));

        Room room = roomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new IllegalArgumentException("Screening room not found with ID: " + request.getRoomId()));

        if (request.getCinemaId() != null && !room.getCinema().getCinemaId().equals(request.getCinemaId())) {
            throw new IllegalArgumentException("The selected room does not belong to this cinema!");
        }

        // Validate time overlap
        long overlapCount = showtimeRepository.countOverlappingShowtimes(
                room.getRoomId(),
                null,
                request.getStartAt(),
                request.getEndAt()
        );
        if (overlapCount > 0) {
            throw new IllegalArgumentException("Room " + room.getRoomName() + " (" + room.getCinema().getCinemaName() + ") already has another showtime scheduled in this time slot!");
        }

        BigDecimal price = request.getBasePrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            price = new BigDecimal("75000.00");
        }

        String status = (request.getStatus() != null && !request.getStatus().trim().isEmpty())
                ? request.getStatus()
                : "ACTIVE";

        Showtime showtime = new Showtime(movie, room, request.getStartAt(), request.getEndAt(), price);
        showtime.setStatus(status);

        return showtimeRepository.save(showtime);
    }

    @Override
    public Showtime updateShowtime(Long id, AdminShowtimeRequestDto request) {
        validateRequest(request);

        Showtime existing = getShowtimeById(id);

        Movie movie = movieRepository.findById(request.getMovieId())
                .orElseThrow(() -> new IllegalArgumentException("Movie not found with ID: " + request.getMovieId()));

        Room room = roomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new IllegalArgumentException("Screening room not found with ID: " + request.getRoomId()));

        if (request.getCinemaId() != null && !room.getCinema().getCinemaId().equals(request.getCinemaId())) {
            throw new IllegalArgumentException("The selected room does not belong to this cinema!");
        }

        // Validate time overlap (excluding current showtime)
        long overlapCount = showtimeRepository.countOverlappingShowtimes(
                room.getRoomId(),
                id,
                request.getStartAt(),
                request.getEndAt()
        );
        if (overlapCount > 0) {
            throw new IllegalArgumentException("Room " + room.getRoomName() + " (" + room.getCinema().getCinemaName() + ") already has another showtime scheduled in this time slot!");
        }

        BigDecimal price = request.getBasePrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            price = existing.getBasePrice();
        }

        existing.setMovie(movie);
        existing.setRoom(room);
        existing.setStartAt(request.getStartAt());
        existing.setEndAt(request.getEndAt());
        existing.setBasePrice(price);
        if (request.getStatus() != null && !request.getStatus().trim().isEmpty()) {
            existing.setStatus(request.getStatus());
        }

        return showtimeRepository.save(existing);
    }

    @Override
    public void deleteShowtime(Long id) {
        Showtime existing = getShowtimeById(id);

        // Check if there are existing bookings for this showtime
        long bookingCount = bookingRepository.countByShowtime_ShowtimeId(id);
        if (bookingCount > 0) {
            throw new IllegalStateException("Cannot delete showtime #" + id + " because it already has " + bookingCount + " booking(s)! Please change its status to CANCELLED or INACTIVE instead.");
        }

        showtimeRepository.delete(existing);
    }

    @Override
    public Showtime createManagerShowtime(Long managerCinemaId, AdminShowtimeRequestDto request) {
        if (managerCinemaId == null) {
            throw new IllegalArgumentException("Manager account has no assigned cinema!");
        }
        request.setCinemaId(managerCinemaId);
        return createShowtime(request);
    }

    @Override
    public Showtime updateManagerShowtime(Long managerCinemaId, Long showtimeId, AdminShowtimeRequestDto request) {
        if (managerCinemaId == null) {
            throw new IllegalArgumentException("Manager account has no assigned cinema!");
        }
        Showtime existing = getShowtimeById(showtimeId);
        if (!existing.getRoom().getCinema().getCinemaId().equals(managerCinemaId)) {
            throw new IllegalArgumentException("You are not authorized to update showtimes for other cinemas!");
        }
        request.setCinemaId(managerCinemaId);
        return updateShowtime(showtimeId, request);
    }

    @Override
    public void deleteManagerShowtime(Long managerCinemaId, Long showtimeId) {
        if (managerCinemaId == null) {
            throw new IllegalArgumentException("Manager account has no assigned cinema!");
        }
        Showtime existing = getShowtimeById(showtimeId);
        if (!existing.getRoom().getCinema().getCinemaId().equals(managerCinemaId)) {
            throw new IllegalArgumentException("You are not authorized to delete showtimes for other cinemas!");
        }
        deleteShowtime(showtimeId);
    }

    private void validateRequest(AdminShowtimeRequestDto request) {
        if (request.getMovieId() == null) {
            throw new IllegalArgumentException("Please select a movie!");
        }
        if (request.getRoomId() == null) {
            throw new IllegalArgumentException("Please select a screening room!");
        }
        if (request.getStartAt() == null) {
            throw new IllegalArgumentException("Please select start time!");
        }
        if (request.getEndAt() == null) {
            throw new IllegalArgumentException("Please select end time!");
        }
        if (!request.getEndAt().isAfter(request.getStartAt())) {
            throw new IllegalArgumentException("End time must be after start time!");
        }
    }
}
