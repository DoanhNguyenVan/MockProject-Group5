package com.boxcinema.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MovieShowtimeGroupDto {

    private Long movieId;
    private String title;
    private String posterUrl;
    private String trailerUrl;
    private String ageRating;
    private Integer durationMinutes;
    private String genreNamesDisplay;
    private String language;
    private Map<String, List<SlotDto>> formats = new LinkedHashMap<>();

    public static class SlotDto {
        private Long showtimeId;
        private String time;
        private String dateSub;
        private boolean late;
        private int availableSeats;
        private BigDecimal price;
        private String roomName;
        private String roomType;

        public SlotDto() {
        }

        public SlotDto(Long showtimeId, String time, String dateSub, boolean late, int availableSeats, BigDecimal price, String roomName, String roomType) {
            this.showtimeId = showtimeId;
            this.time = time;
            this.dateSub = dateSub;
            this.late = late;
            this.availableSeats = availableSeats;
            this.price = price;
            this.roomName = roomName;
            this.roomType = roomType;
        }

        public Long getShowtimeId() {
            return showtimeId;
        }

        public void setShowtimeId(Long showtimeId) {
            this.showtimeId = showtimeId;
        }

        public String getTime() {
            return time;
        }

        public void setTime(String time) {
            this.time = time;
        }

        public String getDateSub() {
            return dateSub;
        }

        public void setDateSub(String dateSub) {
            this.dateSub = dateSub;
        }

        public boolean isLate() {
            return late;
        }

        public void setLate(boolean late) {
            this.late = late;
        }

        public int getAvailableSeats() {
            return availableSeats;
        }

        public void setAvailableSeats(int availableSeats) {
            this.availableSeats = availableSeats;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }

        public String getRoomName() {
            return roomName;
        }

        public void setRoomName(String roomName) {
            this.roomName = roomName;
        }

        public String getRoomType() {
            return roomType;
        }

        public void setRoomType(String roomType) {
            this.roomType = roomType;
        }
    }

    public MovieShowtimeGroupDto() {
    }

    public void addSlot(String format, SlotDto slot) {
        formats.computeIfAbsent(format, k -> new ArrayList<>()).add(slot);
    }

    public Long getMovieId() {
        return movieId;
    }

    public void setMovieId(Long movieId) {
        this.movieId = movieId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getPosterUrl() {
        return posterUrl;
    }

    public void setPosterUrl(String posterUrl) {
        this.posterUrl = posterUrl;
    }

    public String getTrailerUrl() {
        return trailerUrl;
    }

    public void setTrailerUrl(String trailerUrl) {
        this.trailerUrl = trailerUrl;
    }

    public String getAgeRating() {
        return ageRating;
    }

    public void setAgeRating(String ageRating) {
        this.ageRating = ageRating;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
    }

    public String getGenreNamesDisplay() {
        return genreNamesDisplay;
    }

    public void setGenreNamesDisplay(String genreNamesDisplay) {
        this.genreNamesDisplay = genreNamesDisplay;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Map<String, List<SlotDto>> getFormats() {
        return formats;
    }

    public void setFormats(Map<String, List<SlotDto>> formats) {
        this.formats = formats;
    }
}

