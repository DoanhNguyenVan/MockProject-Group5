package com.boxcinema.common.repository;

import com.boxcinema.common.entity.Inquiry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InquiryRepository extends JpaRepository<Inquiry, Long> {
    List<Inquiry> findByEmailOrderByCreatedAtDesc(String email);
    List<Inquiry> findByInquiryStatusOrderByCreatedAtDesc(String inquiryStatus);

    long countByInquiryStatus(String inquiryStatus);

    @Query("SELECT i FROM Inquiry i WHERE " +
           "(:status IS NULL OR :status = '' OR i.inquiryStatus = :status) AND " +
           "(:topic IS NULL OR :topic = '' OR LOWER(i.inquiryTopic) = LOWER(:topic)) AND " +
           "(:search IS NULL OR :search = '' OR " +
           " LOWER(i.fullName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(i.email) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(i.phone) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " LOWER(i.message) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
           " CAST(i.inquiryId AS string) LIKE CONCAT('%', :search, '%')) " +
           "ORDER BY i.createdAt DESC")
    Page<Inquiry> findWithFilters(@Param("status") String status,
                                  @Param("topic") String topic,
                                  @Param("search") String search,
                                  Pageable pageable);
}
