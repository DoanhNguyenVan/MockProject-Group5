package com.boxcinema.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "ROOMS")
public class Room {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "room_id")
    private Long roomId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cinema_id", nullable = false)
    private Cinema cinema;

    @Column(name = "room_name", length = 50, nullable = false)
    private String roomName;

    @Column(name = "room_type", length = 30, nullable = false)
    private String roomType;

    @Column(name = "status", length = 20, nullable = false)
    private String status = "ACTIVE";

    public Room() {
    }

    public Room(Cinema cinema, String roomName, String roomType) {
        this.cinema = cinema;
        this.roomName = roomName;
        this.roomType = roomType;
        this.status = "ACTIVE";
    }

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public Cinema getCinema() {
        return cinema;
    }

    public void setCinema(Cinema cinema) {
        this.cinema = cinema;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}

