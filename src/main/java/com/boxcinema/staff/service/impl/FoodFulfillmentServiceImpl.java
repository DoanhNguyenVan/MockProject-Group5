package com.boxcinema.staff.service.impl;

import com.boxcinema.common.dto.BookingFoodItemDto;
import com.boxcinema.staff.dto.response.FoodFulfillmentDto;
import com.boxcinema.common.entity.BookingFoodItem;
import com.boxcinema.common.entity.FoodFulfillment;
import com.boxcinema.common.entity.User;
import com.boxcinema.common.repository.FoodFulfillmentRepository;
import com.boxcinema.common.repository.UserRepository;
import com.boxcinema.staff.service.FoodFulfillmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Transactional
public class FoodFulfillmentServiceImpl implements FoodFulfillmentService {

    private final FoodFulfillmentRepository fulfillmentRepository;
    private final UserRepository userRepository;

    public FoodFulfillmentServiceImpl(FoodFulfillmentRepository fulfillmentRepository, UserRepository userRepository) {
        this.fulfillmentRepository = fulfillmentRepository;
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FoodFulfillmentDto> getOrders(String status) {
        List<FoodFulfillment> list;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)) {
            list = fulfillmentRepository.findByStatusOrderByCreatedAtAsc(status.trim().toUpperCase());
        } else {
            list = fulfillmentRepository.findAllByOrderByCreatedAtDesc();
        }

        return list.stream().map(this::mapToDto).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public FoodFulfillmentDto getOrderById(Long id) {
        FoodFulfillment ff = fulfillmentRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Food fulfillment order not found: " + id));
        return mapToDto(ff);
    }

    @Override
    public FoodFulfillmentDto updateStatus(Long id, String newStatus, String note, Long staffUserId) {
        FoodFulfillment ff = fulfillmentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("Food fulfillment order not found: " + id));

        String s = newStatus != null ? newStatus.trim().toUpperCase() : "QUEUED";
        User staff = staffUserId != null ? userRepository.findById(staffUserId).orElse(null) : null;
        if (staff != null) {
            ff.setAssignedStaff(staff);
        }

        LocalDateTime now = LocalDateTime.now();
        switch (s) {
            case "PREPARING":
                ff.setStatus("PREPARING");
                if (ff.getPreparingAt() == null) ff.setPreparingAt(now);
                break;
            case "READY":
                ff.setStatus("READY");
                if (ff.getReadyAt() == null) ff.setReadyAt(now);
                break;
            case "PICKED_UP":
                if ("PICKED_UP".equalsIgnoreCase(ff.getStatus())) {
                    throw new IllegalStateException("This food order has already been picked up previously.");
                }
                ff.setStatus("PICKED_UP");
                if (ff.getPickedUpAt() == null) ff.setPickedUpAt(now);
                break;
            case "CANCELLED":
                ff.setStatus("CANCELLED");
                break;
            default:
                ff.setStatus(s);
                break;
        }

        if (note != null && !note.isBlank()) {
            ff.setNote(note.trim());
        }

        ff = fulfillmentRepository.save(ff);
        return mapToDto(ff);
    }

    private FoodFulfillmentDto mapToDto(FoodFulfillment ff) {
        FoodFulfillmentDto dto = new FoodFulfillmentDto();
        dto.setId(ff.getId());
        if (ff.getBooking() != null) {
            dto.setBookingId(ff.getBooking().getBookingId());
            dto.setBookingCode(ff.getBooking().getBookingCode());
            dto.setGuestName(ff.getBooking().getGuestName());
            dto.setGuestPhone(ff.getBooking().getGuestPhone());

            if (ff.getBooking().getShowtime() != null) {
                if (ff.getBooking().getShowtime().getMovie() != null) {
                    dto.setMovieTitle(ff.getBooking().getShowtime().getMovie().getTitle());
                }
                if (ff.getBooking().getShowtime().getRoom() != null && ff.getBooking().getShowtime().getRoom().getCinema() != null) {
                    dto.setCinemaName(ff.getBooking().getShowtime().getRoom().getCinema().getCinemaName());
                }
            }

            if (ff.getBooking().getBookingFoodItems() != null) {
                for (BookingFoodItem bfi : ff.getBooking().getBookingFoodItems()) {
                    BookingFoodItemDto bfiDto = new BookingFoodItemDto();
                    bfiDto.setId(bfi.getId());
                    bfiDto.setItemType(bfi.getItemType());
                    bfiDto.setProductName(bfi.getProductNameSnapshot());
                    bfiDto.setVariantName(bfi.getVariantNameSnapshot());
                    bfiDto.setQuantity(bfi.getQuantity());
                    bfiDto.setUnitPrice(bfi.getUnitPriceSnapshot());
                    bfiDto.setSubtotal(bfi.getSubtotal());
                    dto.getItems().add(bfiDto);
                }
            }
        }

        dto.setStatus(ff.getStatus());
        dto.setFulfillmentType(ff.getFulfillmentType());
        dto.setPickupLocation(ff.getPickupLocation());
        dto.setAssignedStaffName(ff.getAssignedStaff() != null ? ff.getAssignedStaff().getFullName() : null);
        dto.setCreatedAt(ff.getCreatedAt());
        dto.setPreparingAt(ff.getPreparingAt());
        dto.setReadyAt(ff.getReadyAt());
        dto.setPickedUpAt(ff.getPickedUpAt());
        dto.setNote(ff.getNote());
        return dto;
    }
}
