package com.boxcinema.repository;

import com.boxcinema.entity.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByPhone(String phone);
    Optional<User> findByGoogleId(String googleId);
    boolean existsByEmail(String email);
    boolean existsByPhone(String phone);
    boolean existsByPhoneIn(java.util.Collection<String> phones);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.userId = :userId")
    Optional<User> findByIdForUpdate(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findByEmailForUpdate(@Param("email") String email);

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.googleId = :googleSub, u.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE u.userId = :userId AND UPPER(u.accountStatus) = 'ACTIVE' AND u.googleId IS NULL "
            + "AND u.passwordHash IS NOT NULL AND TRIM(u.passwordHash) <> ''")
    int atomicLinkGoogle(
            @Param("userId") Long userId,
            @Param("googleSub") String googleSub
    );

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.googleId = :googleSub, u.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE u.userId = :userId AND UPPER(u.accountStatus) = 'ACTIVE' AND u.googleId IS NULL "
            + "AND LOWER(TRIM(u.email)) = LOWER(TRIM(:verifiedEmail))")
    int atomicAutoLinkGoogle(
            @Param("userId") Long userId,
            @Param("googleSub") String googleSub,
            @Param("verifiedEmail") String verifiedEmail
    );

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.googleId = :googleSub, u.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE u.userId = :userId AND UPPER(u.accountStatus) = 'ACTIVE' AND u.googleId IS NULL")
    int atomicVerifiedLinkGoogle(
            @Param("userId") Long userId,
            @Param("googleSub") String googleSub
    );

    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE User u SET u.googleId = NULL, u.updatedAt = CURRENT_TIMESTAMP "
            + "WHERE u.userId = :userId AND UPPER(u.accountStatus) = 'ACTIVE' "
            + "AND u.googleId = :expectedGoogleSub "
            + "AND u.passwordHash IS NOT NULL AND TRIM(u.passwordHash) <> ''")
    int atomicUnlinkGoogle(
            @Param("userId") Long userId,
            @Param("expectedGoogleSub") String expectedGoogleSub
    );

    boolean existsByPhoneAndUserIdNot(String phone, Long userId);

    boolean existsByPhoneInAndUserIdNot(java.util.Collection<String> phones, Long userId);

    @Query("SELECT u FROM User u WHERE UPPER(u.accountStatus) IN ('PENDING_VERIFICATION', 'PENDING') AND u.createdAt < :cutoff")
    List<User> findPendingAccountsOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
