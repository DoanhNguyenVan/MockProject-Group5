package com.boxcinema.repository;

import com.boxcinema.entity.OtpPurpose;
import com.boxcinema.entity.PasswordReset;
import com.boxcinema.entity.PasswordResetId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface PasswordResetRepository extends JpaRepository<PasswordReset, PasswordResetId> {

    Optional<PasswordReset> findByUserIdAndPurpose(Long userId, OtpPurpose purpose);

    Optional<PasswordReset> findByChallengeId(String challengeId);

    Optional<PasswordReset> findByChallengeIdAndPurpose(String challengeId, OtpPurpose purpose);

    Optional<PasswordReset> findByResetTokenHash(String resetTokenHash);

    @Query("SELECT pr.userId FROM PasswordReset pr WHERE pr.resetTokenHash = :resetTokenHash AND pr.purpose = com.boxcinema.entity.OtpPurpose.PASSWORD_RESET")
    Optional<Long> findUserIdByResetTokenHash(@Param("resetTokenHash") String resetTokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pr FROM PasswordReset pr WHERE pr.userId = :userId AND pr.purpose = :purpose")
    Optional<PasswordReset> findByUserIdAndPurposeForUpdate(@Param("userId") Long userId, @Param("purpose") OtpPurpose purpose);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pr FROM PasswordReset pr WHERE pr.challengeId = :challengeId AND pr.purpose = :purpose")
    Optional<PasswordReset> findByChallengeIdAndPurposeForUpdate(@Param("challengeId") String challengeId, @Param("purpose") OtpPurpose purpose);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pr FROM PasswordReset pr WHERE pr.userId = :userId")
    java.util.List<PasswordReset> findAllByUserIdForUpdate(@Param("userId") Long userId);

    @Modifying
    @Transactional
    @Query("DELETE FROM PasswordReset pr WHERE pr.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
