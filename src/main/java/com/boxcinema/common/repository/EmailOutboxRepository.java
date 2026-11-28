package com.boxcinema.common.repository;

import com.boxcinema.common.entity.EmailOutbox;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmailOutboxRepository extends JpaRepository<EmailOutbox, Long> {
    @EntityGraph(attributePaths = {"booking", "booking.user"})
    List<EmailOutbox> findByStatusOrderByCreatedAtAsc(String status);

    @EntityGraph(attributePaths = {"booking", "booking.user"})
    List<EmailOutbox> findByStatusAndRetryCountLessThan(String status, int maxRetries);

    @EntityGraph(attributePaths = {"booking", "booking.user"})
    Optional<EmailOutbox> findWithBookingById(Long id);

    @Query(value = "SELECT id FROM email_outbox WHERE status IN ('PENDING', 'RETRYING') " +
                   "AND retry_count < :maxRetries " +
                   "ORDER BY id ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<Long> findClaimableIdsForUpdateSkipLocked(@Param("maxRetries") int maxRetries,
                                                   @Param("batchSize") int batchSize);

    @Query(value = "SELECT id FROM email_outbox WHERE status = 'PROCESSING' " +
                   "ORDER BY id ASC LIMIT :batchSize FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<Long> findProcessingIdsForUpdateSkipLocked(@Param("batchSize") int batchSize);

    boolean existsByDedupKey(String dedupKey);
}
