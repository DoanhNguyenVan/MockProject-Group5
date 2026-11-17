package com.boxcinema.repository;

import com.boxcinema.entity.EmailOutbox;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {
    @EntityGraph(attributePaths = {"booking", "booking.user"})
    List<EmailOutbox> findByStatusOrderByCreatedAtAsc(String status);

    @EntityGraph(attributePaths = {"booking", "booking.user"})
    List<EmailOutbox> findByStatusAndRetryCountLessThan(String status, int maxRetries);
}
