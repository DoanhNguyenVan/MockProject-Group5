package com.boxcinema.common.repository;

import com.boxcinema.common.entity.OrderVoucherAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderVoucherAllocationRepository extends JpaRepository<OrderVoucherAllocation, Long> {

    List<OrderVoucherAllocation> findByRedemption_IdOrderByIdAsc(Long redemptionId);

    List<OrderVoucherAllocation> findByBooking_BookingIdOrderByIdAsc(Long bookingId);

    List<OrderVoucherAllocation> findByTicketId(Long ticketId);

    void deleteByRedemption_Id(Long redemptionId);

    void deleteByBooking_BookingId(Long bookingId);
}
