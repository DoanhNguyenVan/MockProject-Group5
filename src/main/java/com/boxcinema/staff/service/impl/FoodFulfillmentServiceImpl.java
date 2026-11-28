package com.boxcinema.staff.service.impl;

import com.boxcinema.common.dto.BookingFoodItemDto;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.util.CryptoUtils;
import com.boxcinema.refund.dto.StaffFoodActionRequestDto;
import com.boxcinema.refund.service.EvidenceStorageService;
import com.boxcinema.staff.dto.response.FoodFulfillmentDto;
import com.boxcinema.staff.service.FoodFulfillmentService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Transactional
public class FoodFulfillmentServiceImpl implements FoodFulfillmentService {

    private static final Set<String> VALID_ACTIONS = Set.of(
            "INITIAL_DELIVERY", "MISSING_REDELIVERY", "WRONG_ITEM_EXCHANGE", "REPLACEMENT_CONFIRMED", "CANNOT_FULFILL"
    );

    private final FoodFulfillmentRepository fulfillmentRepository;
    private final FoodFulfillmentHistoryRepository historyRepository;
    private final BookingFoodItemRepository bookingFoodItemRepository;
    private final BookingFoodItemComponentRepository componentRepository;
    private final BookingQrRepository bookingQrRepository;
    private final BookingRepository bookingRepository;
    private final StaffErrorLogRepository staffErrorLogRepository;
    private final UserRepository userRepository;
    private final EvidenceStorageService evidenceStorageService;

    public FoodFulfillmentServiceImpl(
            FoodFulfillmentRepository fulfillmentRepository,
            FoodFulfillmentHistoryRepository historyRepository,
            BookingFoodItemRepository bookingFoodItemRepository,
            BookingFoodItemComponentRepository componentRepository,
            BookingQrRepository bookingQrRepository,
            BookingRepository bookingRepository,
            StaffErrorLogRepository staffErrorLogRepository,
            UserRepository userRepository,
            EvidenceStorageService evidenceStorageService) {
        this.fulfillmentRepository = fulfillmentRepository;
        this.historyRepository = historyRepository;
        this.bookingFoodItemRepository = bookingFoodItemRepository;
        this.componentRepository = componentRepository;
        this.bookingQrRepository = bookingQrRepository;
        this.bookingRepository = bookingRepository;
        this.staffErrorLogRepository = staffErrorLogRepository;
        this.userRepository = userRepository;
        this.evidenceStorageService = evidenceStorageService;
    }

    @Override
    @Transactional(readOnly = true)
    public List<FoodFulfillmentDto> getOrders(String status) {
        return getOrdersForActor(status, null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FoodFulfillmentDto> getOrdersForActor(String status, Long actorUserId) {
        Long branchCinemaId = null;
        if (actorUserId != null) {
            User actor = userRepository.findById(actorUserId).orElse(null);
            if (actor != null && !"ADMIN".equalsIgnoreCase(actor.getRole())) {
                if (actor.getCinemaId() == null) {
                    throw new BusinessException(HttpStatus.FORBIDDEN, "NO_BRANCH_ASSIGNED", "Your account has no cinema branch assigned.");
                }
                branchCinemaId = actor.getCinemaId();
            }
        }
        String st = (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status))
                ? status.trim().toUpperCase(Locale.ROOT)
                : null;

        List<FoodFulfillment> list;
        if (branchCinemaId != null) {
            list = st != null
                    ? fulfillmentRepository.findByCinema_CinemaIdAndStatusOrderByCreatedAtAsc(branchCinemaId, st)
                    : fulfillmentRepository.findByCinema_CinemaIdOrderByCreatedAtDesc(branchCinemaId);
        } else {
            list = st != null
                    ? fulfillmentRepository.findByStatusOrderByCreatedAtAsc(st)
                    : fulfillmentRepository.findAllByOrderByCreatedAtDesc();
        }
        return list.stream().map(this::mapToDto).collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public FoodFulfillmentDto getOrderById(Long id) {
        return getOrderByIdForActor(id, null);
    }

    @Override
    @Transactional(readOnly = true)
    public FoodFulfillmentDto getOrderByIdForActor(Long id, Long actorUserId) {
        FoodFulfillment ff = fulfillmentRepository.findById(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "FULFILLMENT_NOT_FOUND", "Food fulfillment order not found: " + id));
        enforceBranchScope(ff, actorUserId);
        return mapToDto(ff);
    }

    @Override
    @Transactional(readOnly = true)
    public FoodFulfillmentDto scanOrder(String qrOrCode, Long staffUserId) {
        if (qrOrCode == null || qrOrCode.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "QR_EMPTY", "QR payload or booking code is required.");
        }
        String clean = qrOrCode.trim();
        Optional<Booking> bookingOpt = bookingRepository.findByBookingCodeIgnoreCase(clean);

        if (bookingOpt.isEmpty()) {
            Matcher mCode = Pattern.compile("BOX-\\d{8}-\\d{4}", Pattern.CASE_INSENSITIVE).matcher(clean);
            if (mCode.find()) {
                bookingOpt = bookingRepository.findByBookingCodeIgnoreCase(mCode.group());
            }
        }
        if (bookingOpt.isEmpty()) {
            String hash = CryptoUtils.sha256(clean);
            Optional<BookingQr> qrOpt = bookingQrRepository.findByTokenHash(hash);
            if (qrOpt.isEmpty()) {
                Matcher mTok = Pattern.compile("BOX1\\.[a-zA-Z0-9_-]+").matcher(clean);
                if (mTok.find()) {
                    qrOpt = bookingQrRepository.findByTokenHash(CryptoUtils.sha256(mTok.group()));
                }
            }
            if (qrOpt.isPresent()) {
                bookingOpt = Optional.ofNullable(qrOpt.get().getBooking());
            }
        }

        Booking booking = bookingOpt.orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "No food order found for scanned QR/code."));
        FoodFulfillment ff = fulfillmentRepository.findByBooking_BookingId(booking.getBookingId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "NO_FOOD_ORDER", "This booking has no concession fulfillment order."));
        enforceBranchScope(ff, staffUserId);
        return mapToDto(ff);
    }

    @Override
    public FoodFulfillmentDto updateStatus(Long id, String newStatus, String note, Long staffUserId) {
        FoodFulfillment ff = fulfillmentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "FULFILLMENT_NOT_FOUND", "Food fulfillment order not found: " + id));
        enforceBranchScope(ff, staffUserId);

        String prevStatus = ff.getStatus() != null ? ff.getStatus().trim().toUpperCase(Locale.ROOT) : "QUEUED";
        String s = newStatus != null ? newStatus.trim().toUpperCase(Locale.ROOT) : "";
        validateForwardKdsTransition(prevStatus, s);

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
                ff.setStatus("PICKED_UP");
                if (ff.getPickedUpAt() == null) ff.setPickedUpAt(now);
                markRemainingItemsDelivered(ff.getBooking().getBookingId());
                break;
            case "CANCELLED":
                ff.setStatus("CANCELLED");
                break;
            default:
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "Unsupported fulfillment status: " + s);
        }

        if (note != null && !note.isBlank()) {
            ff.setNote(note.trim());
        }

        ff = fulfillmentRepository.save(ff);
        appendHistory(ff, null, null, "STATUS_CHANGE", prevStatus, ff.getStatus(), 0,
                null, null, null, null, staff, note, null);
        return mapToDto(ff);
    }

    @Override
    public FoodFulfillmentDto recordDeliveryAction(Long id, StaffFoodActionRequestDto request, Long staffUserId) {
        FoodFulfillment ff = fulfillmentRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "FULFILLMENT_NOT_FOUND", "Food fulfillment order not found."));
        enforceBranchScope(ff, staffUserId);
        String cleanEvidenceUrl = evidenceStorageService.sanitizeEvidenceUrl(request.getEvidenceUrl());
        String actionType = request.getActionType() != null ? request.getActionType().trim().toUpperCase(Locale.ROOT) : "";
        if (!VALID_ACTIONS.contains(actionType)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ACTION", "Unsupported delivery action: " + actionType);
        }
        int qty = request.getQuantity();
        if (qty <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_QUANTITY", "Quantity must be greater than 0.");
        }

        User staff = staffUserId != null ? userRepository.findById(staffUserId).orElse(null) : null;
        String prevOrderStatus = ff.getStatus();
        String itemNameSnapshot;

        if (request.getBookingFoodItemComponentId() != null) {
            BookingFoodItemComponent comp = componentRepository.findByIdForUpdate(request.getBookingFoodItemComponentId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "COMPONENT_NOT_FOUND", "Combo component not found."));
            if (!comp.getBooking().getBookingId().equals(ff.getBooking().getBookingId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "ITEM_MISMATCH", "Component does not belong to this order.");
            }
            itemNameSnapshot = comp.getComponentNameSnapshot() + (comp.getVariantNameSnapshot() != null ? " (" + comp.getVariantNameSnapshot() + ")" : "");
            applyComponentAction(comp, actionType, qty, request);
            componentRepository.save(comp);
            syncParentComboStatus(comp.getBookingFoodItem());
        } else if (request.getBookingFoodItemId() != null) {
            BookingFoodItem bfi = bookingFoodItemRepository.findByIdForUpdate(request.getBookingFoodItemId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Food item not found."));
            if (!bfi.getBooking().getBookingId().equals(ff.getBooking().getBookingId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "ITEM_MISMATCH", "Food item does not belong to this order.");
            }
            itemNameSnapshot = bfi.getProductNameSnapshot() + (bfi.getVariantNameSnapshot() != null ? " (" + bfi.getVariantNameSnapshot() + ")" : "");
            applyFoodItemAction(bfi, actionType, qty, request);
            bookingFoodItemRepository.save(bfi);
        } else {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "TARGET_REQUIRED", "Specify bookingFoodItemId or bookingFoodItemComponentId.");
        }

        // Recompute overall FoodFulfillment status
        String nextOrderStatus = recomputeOrderStatus(ff.getBooking().getBookingId(), actionType);
        ff.setStatus(nextOrderStatus);
        if ("PICKED_UP".equals(nextOrderStatus) && ff.getPickedUpAt() == null) {
            ff.setPickedUpAt(LocalDateTime.now());
        }
        if (staff != null) {
            ff.setAssignedStaff(staff);
        }
        ff = fulfillmentRepository.save(ff);

        // Append immutable history row (INSERT only)
        appendHistory(ff, request.getBookingFoodItemId(), request.getBookingFoodItemComponentId(),
                actionType, prevOrderStatus, nextOrderStatus, qty, itemNameSnapshot,
                request.getWrongItemDescription(), request.getReplacementDescription(),
                request.getCustomerAcceptedReplacement(), staff, request.getNote(), cleanEvidenceUrl);

        // Staff error on wrong item is logged in isolated staff_error_logs
        if ("WRONG_ITEM_EXCHANGE".equals(actionType) && staff != null) {
            Cinema cinema = ff.getCinema() != null ? ff.getCinema() : ff.getBooking().getCinema();
            if (cinema == null && ff.getBooking().getShowtime() != null && ff.getBooking().getShowtime().getRoom() != null) {
                cinema = ff.getBooking().getShowtime().getRoom().getCinema();
            }
            if (cinema != null) {
                User errStaff = request.getStaffErrorUserId() != null
                        ? userRepository.findById(request.getStaffErrorUserId()).orElse(staff) : staff;
                StaffErrorLog sel = new StaffErrorLog();
                sel.setCinema(cinema);
                sel.setStaffUser(errStaff);
                sel.setReportedByUser(staff);
                sel.setBooking(ff.getBooking());
                sel.setErrorCategory("WRONG_FOOD_DELIVERED");
                sel.setSeverity("LOW");
                sel.setDescription("Wrong item delivered for " + itemNameSnapshot + ": "
                        + (request.getWrongItemDescription() != null ? request.getWrongItemDescription() : "Exchanged at counter"));
                sel.setCorrectiveAction("Exchanged qty " + qty + " to customer.");
                sel.setStatus("LOGGED");
                staffErrorLogRepository.save(sel);
            }
        }

        return mapToDto(ff);
    }

    private void applyFoodItemAction(BookingFoodItem bfi, String actionType, int qty, StaffFoodActionRequestDto req) {
        switch (actionType) {
            case "INITIAL_DELIVERY", "MISSING_REDELIVERY" -> {
                if (bfi.getDeliveredQuantity() + bfi.getReplacementAcceptedQuantity() + bfi.getRefundedQuantity() + qty > bfi.getQuantity()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "EXCESS_QTY",
                            "Delivered quantity cannot exceed remaining non-refunded ordered quantity (" + bfi.getQuantity() + ").");
                }
                bfi.setDeliveredQuantity(bfi.getDeliveredQuantity() + qty);
                bfi.setDeliveryStatus(bfi.getDeliveredQuantity() + bfi.getReplacementAcceptedQuantity() + bfi.getRefundedQuantity() >= bfi.getQuantity()
                        ? "DELIVERED" : "PARTIAL");
            }
            case "WRONG_ITEM_EXCHANGE" -> {
                if (qty > bfi.getQuantity() - bfi.getRefundedQuantity()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "EXCESS_QTY",
                            "Exchange quantity exceeds non-refunded ordered quantity.");
                }
                // Exchange ensures customer has the valid item (if not yet counted as delivered, increment up to non-refunded qty)
                int totalFulfilled = bfi.getDeliveredQuantity() + bfi.getReplacementAcceptedQuantity() + bfi.getRefundedQuantity();
                if (totalFulfilled < bfi.getQuantity()) {
                    bfi.setDeliveredQuantity(Math.min(
                            bfi.getQuantity() - bfi.getReplacementAcceptedQuantity() - bfi.getRefundedQuantity(),
                            bfi.getDeliveredQuantity() + qty));
                }
                bfi.setDeliveryStatus("EXCHANGED");
            }
            case "REPLACEMENT_CONFIRMED" -> {
                if (!Boolean.TRUE.equals(req.getCustomerAcceptedReplacement())) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "CONFIRMATION_REQUIRED",
                            "Customer acceptance must be confirmed for replacement.");
                }
                if (bfi.getDeliveredQuantity() + bfi.getReplacementAcceptedQuantity() + bfi.getRefundedQuantity() + qty > bfi.getQuantity()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "EXCESS_QTY", "Replacement quantity exceeds remaining ordered quantity.");
                }
                bfi.setReplacementAcceptedQuantity(bfi.getReplacementAcceptedQuantity() + qty);
                bfi.setDeliveryStatus("EXCHANGED");
            }
            case "CANNOT_FULFILL" -> {
                if (qty > bfi.getQuantity() - bfi.getRefundedQuantity()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "EXCESS_QTY",
                            "Cannot-fulfill quantity exceeds non-refunded ordered quantity.");
                }
                int maxDeliveredAfterIssue = Math.max(0, bfi.getQuantity() - bfi.getRefundedQuantity() - bfi.getReplacementAcceptedQuantity() - qty);
                if (bfi.getDeliveredQuantity() > maxDeliveredAfterIssue) {
                    bfi.setDeliveredQuantity(maxDeliveredAfterIssue);
                }
                bfi.setDeliveryStatus("ISSUE_REPORTED");
            }
        }
    }

    private void applyComponentAction(BookingFoodItemComponent comp, String actionType, int qty, StaffFoodActionRequestDto req) {
        switch (actionType) {
            case "INITIAL_DELIVERY", "MISSING_REDELIVERY" -> {
                if (comp.getDeliveredQuantity() + comp.getReplacementAcceptedQuantity() + comp.getRefundedQuantity() + qty > comp.getTotalQuantity()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "EXCESS_QTY",
                            "Delivered quantity cannot exceed remaining non-refunded component quantity (" + comp.getTotalQuantity() + ").");
                }
                comp.setDeliveredQuantity(comp.getDeliveredQuantity() + qty);
                comp.setDeliveryStatus(comp.getDeliveredQuantity() + comp.getReplacementAcceptedQuantity() + comp.getRefundedQuantity() >= comp.getTotalQuantity()
                        ? "DELIVERED" : "PARTIAL");
            }
            case "WRONG_ITEM_EXCHANGE" -> {
                if (qty > comp.getTotalQuantity() - comp.getRefundedQuantity()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "EXCESS_QTY",
                            "Exchange quantity exceeds non-refunded component quantity.");
                }
                int totalFulfilled = comp.getDeliveredQuantity() + comp.getReplacementAcceptedQuantity() + comp.getRefundedQuantity();
                if (totalFulfilled < comp.getTotalQuantity()) {
                    comp.setDeliveredQuantity(Math.min(
                            comp.getTotalQuantity() - comp.getReplacementAcceptedQuantity() - comp.getRefundedQuantity(),
                            comp.getDeliveredQuantity() + qty));
                }
                comp.setDeliveryStatus("EXCHANGED");
            }
            case "REPLACEMENT_CONFIRMED" -> {
                if (!Boolean.TRUE.equals(req.getCustomerAcceptedReplacement())) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "CONFIRMATION_REQUIRED",
                            "Customer acceptance must be confirmed for replacement.");
                }
                if (comp.getDeliveredQuantity() + comp.getReplacementAcceptedQuantity() + comp.getRefundedQuantity() + qty > comp.getTotalQuantity()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "EXCESS_QTY", "Replacement quantity exceeds remaining component quantity.");
                }
                comp.setReplacementAcceptedQuantity(comp.getReplacementAcceptedQuantity() + qty);
                comp.setDeliveryStatus("EXCHANGED");
            }
            case "CANNOT_FULFILL" -> {
                if (qty > comp.getTotalQuantity() - comp.getRefundedQuantity()) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "EXCESS_QTY",
                            "Cannot-fulfill quantity exceeds non-refunded component quantity.");
                }
                int maxDeliveredAfterIssue = Math.max(0, comp.getTotalQuantity() - comp.getRefundedQuantity() - comp.getReplacementAcceptedQuantity() - qty);
                if (comp.getDeliveredQuantity() > maxDeliveredAfterIssue) {
                    comp.setDeliveredQuantity(maxDeliveredAfterIssue);
                }
                comp.setDeliveryStatus("ISSUE_REPORTED");
            }
        }
    }

    private void syncParentComboStatus(BookingFoodItem parentCombo) {
        if (parentCombo == null) return;
        List<BookingFoodItemComponent> comps = componentRepository.findByBookingFoodItem_Id(parentCombo.getId());
        if (comps.isEmpty()) return;
        boolean allFulfilled = comps.stream().allMatch(c -> (c.getDeliveredQuantity() + c.getReplacementAcceptedQuantity() + c.getRefundedQuantity()) >= c.getTotalQuantity());
        boolean anyFulfilled = comps.stream().anyMatch(c -> (c.getDeliveredQuantity() + c.getReplacementAcceptedQuantity() + c.getRefundedQuantity()) > 0);
        if (allFulfilled) {
            parentCombo.setDeliveredQuantity(parentCombo.getQuantity());
            parentCombo.setDeliveryStatus("DELIVERED");
        } else if (anyFulfilled) {
            parentCombo.setDeliveryStatus("PARTIAL");
        }
        bookingFoodItemRepository.save(parentCombo);
    }

    private String recomputeOrderStatus(Long bookingId, String lastAction) {
        if ("CANNOT_FULFILL".equals(lastAction)) {
            return "ISSUE_REPORTED";
        }
        List<BookingFoodItem> items = bookingFoodItemRepository.findByBooking_BookingId(bookingId);
        List<BookingFoodItemComponent> comps = componentRepository.findByBooking_BookingId(bookingId);
        Map<Long, List<BookingFoodItemComponent>> compMap = comps.stream()
                .filter(c -> c.getBookingFoodItem() != null)
                .collect(Collectors.groupingBy(c -> c.getBookingFoodItem().getId()));

        boolean allFulfilled = true;
        boolean anyFulfilled = false;
        for (BookingFoodItem bfi : items) {
            List<BookingFoodItemComponent> cList = compMap.get(bfi.getId());
            if (cList != null && !cList.isEmpty()) {
                for (BookingFoodItemComponent c : cList) {
                    int done = c.getDeliveredQuantity() + c.getReplacementAcceptedQuantity() + c.getRefundedQuantity();
                    if (done < c.getTotalQuantity()) allFulfilled = false;
                    if (done > 0) anyFulfilled = true;
                }
            } else {
                int done = bfi.getDeliveredQuantity() + bfi.getReplacementAcceptedQuantity() + bfi.getRefundedQuantity();
                if (done < bfi.getQuantity()) allFulfilled = false;
                if (done > 0) anyFulfilled = true;
            }
        }
        if (allFulfilled && !items.isEmpty()) return "PICKED_UP";
        if (anyFulfilled) return "PARTIAL_DELIVERED";
        return "READY";
    }

    private void markRemainingItemsDelivered(Long bookingId) {
        List<BookingFoodItem> items = bookingFoodItemRepository.findByBooking_BookingId(bookingId);
        for (BookingFoodItem bfi : items) {
            int maxDeliverable = Math.max(0, bfi.getQuantity() - bfi.getReplacementAcceptedQuantity() - bfi.getRefundedQuantity());
            if (bfi.getDeliveredQuantity() < maxDeliverable) {
                bfi.setDeliveredQuantity(maxDeliverable);
                bfi.setDeliveryStatus("DELIVERED");
            }
        }
        bookingFoodItemRepository.saveAll(items);
        List<BookingFoodItemComponent> comps = componentRepository.findByBooking_BookingId(bookingId);
        for (BookingFoodItemComponent c : comps) {
            int maxDeliverable = Math.max(0, c.getTotalQuantity() - c.getReplacementAcceptedQuantity() - c.getRefundedQuantity());
            if (c.getDeliveredQuantity() < maxDeliverable) {
                c.setDeliveredQuantity(maxDeliverable);
                c.setDeliveryStatus("DELIVERED");
            }
        }
        if (!comps.isEmpty()) componentRepository.saveAll(comps);
    }

    private void validateForwardKdsTransition(String currentStatus, String nextStatus) {
        if ("PICKED_UP".equals(currentStatus)) {
            throw new IllegalStateException("This food order has already been picked up previously.");
        }
        if ("CANCELLED".equals(currentStatus)) {
            throw new IllegalStateException("Cannot update a cancelled food order.");
        }
        if (currentStatus.equals(nextStatus)) {
            return;
        }
        boolean allowed = switch (currentStatus) {
            case "QUEUED" -> Set.of("PREPARING", "READY", "PICKED_UP", "CANCELLED").contains(nextStatus);
            case "PREPARING" -> Set.of("READY", "PICKED_UP", "CANCELLED").contains(nextStatus);
            case "READY", "PARTIAL_DELIVERED", "ISSUE_REPORTED" -> Set.of("PREPARING", "READY", "PICKED_UP", "CANCELLED").contains(nextStatus);
            default -> false;
        };
        if (!allowed) {
            throw new BusinessException(HttpStatus.CONFLICT, "INVALID_STATUS_TRANSITION",
                    "Cannot transition food fulfillment from " + currentStatus + " to " + nextStatus + ".");
        }
    }

    private void enforceBranchScope(FoodFulfillment ff, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Staff authentication required.");
        }
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Staff user not found."));
        if ("ADMIN".equalsIgnoreCase(actor.getRole())) return;
        Long userCinemaId = actor.getCinemaId();
        Long ffCinemaId = ff.getCinema() != null ? ff.getCinema().getCinemaId()
                : (ff.getBooking() != null ? ff.getBooking().resolveCinemaId() : null);
        if (userCinemaId == null || !userCinemaId.equals(ffCinemaId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "BRANCH_ACCESS_DENIED", "Access denied for this cinema branch.");
        }
    }

    private void appendHistory(FoodFulfillment ff, Long bfiId, Long compId, String actionType,
                               String prevStatus, String newStatus, int qtyAffected, String itemName,
                               String wrongDesc, String replDesc, Boolean acceptedRepl, User staff,
                               String note, String evidenceUrl) {
        FoodFulfillmentHistory h = new FoodFulfillmentHistory();
        h.setFoodFulfillment(ff);
        h.setBooking(ff.getBooking());
        h.setBookingFoodItemId(bfiId);
        h.setBookingFoodItemComponentId(compId);
        h.setActionType(actionType);
        h.setPreviousStatus(prevStatus);
        h.setNewStatus(newStatus);
        h.setQuantityAffected(qtyAffected);
        h.setDeliveredItemNameSnapshot(itemName);
        h.setWrongItemDescription(wrongDesc);
        h.setReplacementDescription(replDesc);
        h.setCustomerAcceptedReplacement(acceptedRepl);
        h.setStaff(staff);
        h.setNote(note);
        h.setEvidenceUrl(evidenceUrl);
        historyRepository.save(h);
    }

    private FoodFulfillmentDto mapToDto(FoodFulfillment ff) {
        FoodFulfillmentDto dto = new FoodFulfillmentDto();
        dto.setId(ff.getId());
        if (ff.getBooking() != null) {
            Booking b = ff.getBooking();
            dto.setBookingId(b.getBookingId());
            dto.setBookingCode(b.getBookingCode());
            dto.setGuestName(b.getGuestName());
            dto.setGuestPhone(b.getGuestPhone());
            dto.setCinemaId(b.resolveCinemaId());

            if (b.getShowtime() != null) {
                if (b.getShowtime().getMovie() != null) {
                    dto.setMovieTitle(b.getShowtime().getMovie().getTitle());
                }
                if (b.getShowtime().getRoom() != null && b.getShowtime().getRoom().getCinema() != null) {
                    dto.setCinemaName(b.getShowtime().getRoom().getCinema().getCinemaName());
                }
            }
            if (dto.getCinemaName() == null && ff.getCinema() != null) {
                dto.setCinemaName(ff.getCinema().getCinemaName());
            } else if (dto.getCinemaName() == null && b.getCinema() != null) {
                dto.setCinemaName(b.getCinema().getCinemaName());
            }

            List<BookingFoodItemComponent> comps = componentRepository.findByBooking_BookingId(b.getBookingId());
            Map<Long, List<BookingFoodItemComponent>> compMap = comps.stream()
                    .filter(c -> c.getBookingFoodItem() != null)
                    .collect(Collectors.groupingBy(c -> c.getBookingFoodItem().getId()));

            if (b.getBookingFoodItems() != null) {
                for (BookingFoodItem bfi : b.getBookingFoodItems()) {
                    BookingFoodItemDto bfiDto = new BookingFoodItemDto();
                    bfiDto.setId(bfi.getId());
                    bfiDto.setItemType(bfi.getItemType());
                    bfiDto.setProductName(bfi.getProductNameSnapshot());
                    bfiDto.setVariantName(bfi.getVariantNameSnapshot());
                    bfiDto.setQuantity(bfi.getQuantity());
                    bfiDto.setUnitPrice(bfi.getUnitPriceSnapshot());
                    bfiDto.setSubtotal(bfi.getSubtotal());
                    bfiDto.setDiscountAllocated(bfi.getDiscountAllocated());
                    bfiDto.setNetPaidAmount(bfi.getNetPaidAmount());
                    bfiDto.setDeliveredQuantity(bfi.getDeliveredQuantity());
                    bfiDto.setRefundedQuantity(bfi.getRefundedQuantity());
                    bfiDto.setReplacementAcceptedQuantity(bfi.getReplacementAcceptedQuantity());
                    bfiDto.setDeliveryStatus(bfi.getDeliveryStatus());

                    for (BookingFoodItemComponent c : compMap.getOrDefault(bfi.getId(), List.of())) {
                        BookingFoodItemDto.ComboComponentDto cDto = new BookingFoodItemDto.ComboComponentDto();
                        cDto.setId(c.getId());
                        cDto.setComponentName(c.getComponentNameSnapshot());
                        cDto.setVariantName(c.getVariantNameSnapshot());
                        cDto.setTotalQuantity(c.getTotalQuantity());
                        cDto.setDeliveredQuantity(c.getDeliveredQuantity());
                        cDto.setRefundedQuantity(c.getRefundedQuantity());
                        cDto.setReplacementAcceptedQuantity(c.getReplacementAcceptedQuantity());
                        cDto.setDeliveryStatus(c.getDeliveryStatus());
                        cDto.setAllocatedSubtotal(c.getAllocatedSubtotal());
                        cDto.setDiscountAllocated(c.getDiscountAllocated());
                        cDto.setNetPaidAmount(c.getNetPaidAmount());
                        bfiDto.getComponents().add(cDto);
                    }
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

        for (FoodFulfillmentHistory h : historyRepository.findByFoodFulfillment_IdOrderByCreatedAtDesc(ff.getId())) {
            FoodFulfillmentDto.HistoryEntryDto hDto = new FoodFulfillmentDto.HistoryEntryDto();
            hDto.setId(h.getId());
            hDto.setActionType(h.getActionType());
            hDto.setPreviousStatus(h.getPreviousStatus());
            hDto.setNewStatus(h.getNewStatus());
            hDto.setQuantityAffected(h.getQuantityAffected());
            hDto.setDeliveredItemName(h.getDeliveredItemNameSnapshot());
            hDto.setWrongItemDescription(h.getWrongItemDescription());
            hDto.setReplacementDescription(h.getReplacementDescription());
            hDto.setCustomerAcceptedReplacement(h.getCustomerAcceptedReplacement());
            hDto.setStaffName(h.getStaff() != null ? h.getStaff().getFullName() : null);
            hDto.setNote(h.getNote());
            hDto.setCreatedAt(h.getCreatedAt());
            dto.getHistory().add(hDto);
        }
        return dto;
    }
}
