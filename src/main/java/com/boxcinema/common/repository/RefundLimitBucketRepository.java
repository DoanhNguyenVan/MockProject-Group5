package com.boxcinema.common.repository;

import com.boxcinema.common.entity.RefundLimitBucket;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RefundLimitBucketRepository extends JpaRepository<RefundLimitBucket, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM RefundLimitBucket b WHERE b.bucketKey = :bucketKey")
    Optional<RefundLimitBucket> findByBucketKeyForUpdate(@Param("bucketKey") String bucketKey);
}
