package com.boxcinema.repository;

import com.boxcinema.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RoomRepository extends JpaRepository<Room, Long> {
    List<Room> findByCinemaCinemaIdAndStatus(Long cinemaId, String status);

    @Query("SELECT DISTINCT r.roomType FROM Room r WHERE r.status = 'ACTIVE' ORDER BY r.roomType ASC")
    List<String> findDistinctActiveRoomTypes();
}

