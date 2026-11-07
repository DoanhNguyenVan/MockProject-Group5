package com.boxcinema.dto;

public class CinemaDto {
    private Long cinemaId;
    private String cinemaName;
    private String city;
    private String displayName;

    public CinemaDto() {
    }

    public CinemaDto(Long cinemaId, String cinemaName, String city) {
        this.cinemaId = cinemaId;
        this.cinemaName = cinemaName;
        this.city = city;
        this.displayName = cinemaName + " (" + city + ")";
    }

    public Long getCinemaId() {
        return cinemaId;
    }

    public void setCinemaId(Long cinemaId) {
        this.cinemaId = cinemaId;
    }

    public String getCinemaName() {
        return cinemaName;
    }

    public void setCinemaName(String cinemaName) {
        this.cinemaName = cinemaName;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}

