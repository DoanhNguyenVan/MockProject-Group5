package com.boxcinema.common.repository;

import com.boxcinema.common.entity.CustomerBankAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerBankAccountRepository extends JpaRepository<CustomerBankAccount, Long> {

    List<CustomerBankAccount> findByUser_UserIdOrderByIsDefaultDescUpdatedAtDesc(Long userId);

    Optional<CustomerBankAccount> findByIdAndUser_UserId(Long id, Long userId);

    Optional<CustomerBankAccount> findByUser_UserIdAndBankBinAndAccountNumberHash(
            Long userId, String bankBin, String accountNumberHash);

    boolean existsByUser_UserIdAndBankBinAndAccountNumberHash(
            Long userId, String bankBin, String accountNumberHash);

    boolean existsByUser_UserIdAndBankBinAndAccountNumberHashAndIdNot(
            Long userId, String bankBin, String accountNumberHash, Long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE CustomerBankAccount c SET c.isDefault = false WHERE c.user.userId = :userId AND c.isDefault = true")
    int clearDefaultForUser(@Param("userId") Long userId);
}
