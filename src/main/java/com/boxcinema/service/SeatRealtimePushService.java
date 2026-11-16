package com.boxcinema.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Real-time Server-Sent Events (SSE) push broker for live seat map synchronization.
 * Broadcasts instant seat hold/release/expiry updates to all browsers viewing a screening.
 */
@Service
public class SeatRealtimePushService {

    private static final Logger log = LoggerFactory.getLogger(SeatRealtimePushService.class);
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutes

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> subscribersByShowtime = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long showtimeId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        CopyOnWriteArrayList<SseEmitter> list = subscribersByShowtime.computeIfAbsent(
                showtimeId, k -> new CopyOnWriteArrayList<>()
        );
        list.add(emitter);

        Runnable remove = () -> {
            list.remove(emitter);
            if (list.isEmpty()) {
                subscribersByShowtime.remove(showtimeId, list);
            }
        };

        emitter.onCompletion(remove);
        emitter.onTimeout(() -> {
            emitter.complete();
            remove.run();
        });
        emitter.onError(ex -> remove.run());

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("showtimeId", String.valueOf(showtimeId), "status", "CONNECTED"), MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            remove.run();
        }

        return emitter;
    }

    public void broadcastSeatUpdate(Long showtimeId, String reason) {
        if (showtimeId == null) return;
        List<SseEmitter> list = subscribersByShowtime.get(showtimeId);
        if (list == null || list.isEmpty()) return;

        Map<String, Object> payload = Map.of(
                "showtimeId", String.valueOf(showtimeId),
                "reason", reason != null ? reason : "SEAT_UPDATED",
                "timestamp", System.currentTimeMillis()
        );

        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("seat-update")
                        .data(payload, MediaType.APPLICATION_JSON));
            } catch (Exception ex) {
                emitter.complete();
                list.remove(emitter);
            }
        }
    }

    public void broadcastAllShowtimes(String reason) {
        for (Long showtimeId : subscribersByShowtime.keySet()) {
            broadcastSeatUpdate(showtimeId, reason);
        }
    }
}
