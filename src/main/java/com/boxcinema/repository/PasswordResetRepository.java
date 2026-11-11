package com.boxcinema.repository;

import com.boxcinema.entity.PasswordReset;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PasswordResetRepository extends JpaRepository<PasswordReset, Long> {

    Optional<PasswordReset> findByChallengeId(String challengeId);

    Optional<PasswordReset> findByResetTokenHash(String resetTokenHash);

    @Query("SELECT pr.userId FROM PasswordReset pr WHERE pr.resetTokenHash = :resetTokenHash")
    Optional<Long> findUserIdByResetTokenHash(@Param("resetTokenHash") String resetTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pr FROM PasswordReset pr WHERE pr.userId = :userId")
    Optional<PasswordReset> findByIdForUpdate(@Param("userId") Long userId);
}
