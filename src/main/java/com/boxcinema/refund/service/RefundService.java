package com.boxcinema.refund.service;

import com.boxcinema.common.entity.*;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BankDataEncryptionService;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import com.boxcinema.refund.dto.*;
import com.boxcinema.refund.provider.PayoutFeeAssessment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final BookingFoodItemRepository bookingFoodItemRepository;
    private final BookingFoodItemComponentRepository componentRepository;
    private final FoodFulfillmentRepository foodFulfillmentRepository;
    private final BookingCheckinRepository checkinRepository;
    private final IncidentReportRepository incidentRepository;
    private final RefundRequestRepository refundRequestRepository;
    private final RefundRequestItemRepository refundRequestItemRepository;
    private final RefundApprovalHistoryRepository historyRepository;
    private final RefundCommunicationRepository communicationRepository;
    private final RefundAuditLogRepository auditLogRepository;
    private final PayOsPayoutTransactionRepository payoutRepository;
    private final VoucherRecoveryRepository voucherRecoveryRepository;
    private final UserRepository userRepository;
    private final CinemaRepository cinemaRepository;
    private final PaymentRepository paymentRepository;
    private final BankDataEncryptionService bankEncryptionService;
    private final EvidenceStorageService evidenceStorageService;
    private final PurchaseSnapshotService snapshotService;
    private final RefundEmailService refundEmailService;
    private final RefundApprovalTxService approvalTxService;
    private final PayOsPayoutService payOsPayoutService;
    private final VoucherRecoveryService voucherRecoveryService;
    private final IncidentService incidentService;
    private final PayoutEligibilityService payoutEligibilityService;
    private final CustomerBankAccountService customerBankAccountService;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Deque<Long>> publicRateBuckets = new ConcurrentHashMap<>();
    private final TransactionTemplate txTemplate;
    private final TransactionTemplate readOnlyTxTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired(required = false)
    private RefundDeadlineService refundDeadlineService;

    @Autowired(required = false)
    private RefundRateLimitService refundRateLimitService;

    @Autowired(required = false)
    private RefundLimitService refundLimitService;

    @Autowired(required = false)
    private RefundAppealService refundAppealService;

    public void setRefundDeadlineService(RefundDeadlineService refundDeadlineService) {
        this.refundDeadlineService = refundDeadlineService;
        if (this.approvalTxService != null) {
            this.approvalTxService.setRefundDeadlineService(refundDeadlineService);
        }
    }

    public void setRefundRateLimitService(RefundRateLimitService refundRateLimitService) {
        this.refundRateLimitService = refundRateLimitService;
    }

    public void setRefundLimitService(RefundLimitService refundLimitService) {
        this.refundLimitService = refundLimitService;
        if (this.approvalTxService != null) {
            this.approvalTxService.setRefundLimitService(refundLimitService);
        }
        if (this.payOsPayoutService != null) {
            this.payOsPayoutService.setRefundLimitService(refundLimitService);
        }
    }

    public void setRefundAppealService(RefundAppealService refundAppealService) {
        this.refundAppealService = refundAppealService;
    }

    public RefundService(
            BookingRepository bookingRepository,
            TicketRepository ticketRepository,
            BookingSeatRepository bookingSeatRepository,
            BookingFoodItemRepository bookingFoodItemRepository,
            BookingFoodItemComponentRepository componentRepository,
            FoodFulfillmentRepository foodFulfillmentRepository,
            BookingCheckinRepository checkinRepository,
            IncidentReportRepository incidentRepository,
            RefundRequestRepository refundRequestRepository,
            RefundRequestItemRepository refundRequestItemRepository,
            RefundApprovalHistoryRepository historyRepository,
            RefundCommunicationRepository communicationRepository,
            RefundAuditLogRepository auditLogRepository,
            PayOsPayoutTransactionRepository payoutRepository,
            VoucherRecoveryRepository voucherRecoveryRepository,
            UserRepository userRepository,
            CinemaRepository cinemaRepository,
            PaymentRepository paymentRepository,
            BankDataEncryptionService bankEncryptionService,
            EvidenceStorageService evidenceStorageService,
            PurchaseSnapshotService snapshotService,
            RefundEmailService refundEmailService,
            RefundApprovalTxService approvalTxService,
            PayOsPayoutService payOsPayoutService,
            VoucherRecoveryService voucherRecoveryService,
            IncidentService incidentService,
            PayoutEligibilityService payoutEligibilityService) {
        this(bookingRepository, ticketRepository, bookingSeatRepository, bookingFoodItemRepository,
                componentRepository, foodFulfillmentRepository, checkinRepository, incidentRepository,
                refundRequestRepository, refundRequestItemRepository, historyRepository, communicationRepository,
                auditLogRepository, payoutRepository, voucherRecoveryRepository, userRepository,
                cinemaRepository, paymentRepository, bankEncryptionService, evidenceStorageService,
                snapshotService, refundEmailService, approvalTxService, payOsPayoutService,
                voucherRecoveryService, incidentService, payoutEligibilityService, null, null);
    }

    public RefundService(
            BookingRepository bookingRepository,
            TicketRepository ticketRepository,
            BookingSeatRepository bookingSeatRepository,
            BookingFoodItemRepository bookingFoodItemRepository,
            BookingFoodItemComponentRepository componentRepository,
            FoodFulfillmentRepository foodFulfillmentRepository,
            BookingCheckinRepository checkinRepository,
            IncidentReportRepository incidentRepository,
            RefundRequestRepository refundRequestRepository,
            RefundRequestItemRepository refundRequestItemRepository,
            RefundApprovalHistoryRepository historyRepository,
            RefundCommunicationRepository communicationRepository,
            RefundAuditLogRepository auditLogRepository,
            PayOsPayoutTransactionRepository payoutRepository,
            VoucherRecoveryRepository voucherRecoveryRepository,
            UserRepository userRepository,
            CinemaRepository cinemaRepository,
            PaymentRepository paymentRepository,
            BankDataEncryptionService bankEncryptionService,
            EvidenceStorageService evidenceStorageService,
            PurchaseSnapshotService snapshotService,
            RefundEmailService refundEmailService,
            RefundApprovalTxService approvalTxService,
            PayOsPayoutService payOsPayoutService,
            VoucherRecoveryService voucherRecoveryService,
            IncidentService incidentService,
            PayoutEligibilityService payoutEligibilityService,
            PlatformTransactionManager transactionManager) {
        this(bookingRepository, ticketRepository, bookingSeatRepository, bookingFoodItemRepository,
                componentRepository, foodFulfillmentRepository, checkinRepository, incidentRepository,
                refundRequestRepository, refundRequestItemRepository, historyRepository, communicationRepository,
                auditLogRepository, payoutRepository, voucherRecoveryRepository, userRepository,
                cinemaRepository, paymentRepository, bankEncryptionService, evidenceStorageService,
                snapshotService, refundEmailService, approvalTxService, payOsPayoutService,
                voucherRecoveryService, incidentService, payoutEligibilityService, transactionManager, null);
    }

    @Autowired
    public RefundService(
            BookingRepository bookingRepository,
            TicketRepository ticketRepository,
            BookingSeatRepository bookingSeatRepository,
            BookingFoodItemRepository bookingFoodItemRepository,
            BookingFoodItemComponentRepository componentRepository,
            FoodFulfillmentRepository foodFulfillmentRepository,
            BookingCheckinRepository checkinRepository,
            IncidentReportRepository incidentRepository,
            RefundRequestRepository refundRequestRepository,
            RefundRequestItemRepository refundRequestItemRepository,
            RefundApprovalHistoryRepository historyRepository,
            RefundCommunicationRepository communicationRepository,
            RefundAuditLogRepository auditLogRepository,
            PayOsPayoutTransactionRepository payoutRepository,
            VoucherRecoveryRepository voucherRecoveryRepository,
            UserRepository userRepository,
            CinemaRepository cinemaRepository,
            PaymentRepository paymentRepository,
            BankDataEncryptionService bankEncryptionService,
            EvidenceStorageService evidenceStorageService,
            PurchaseSnapshotService snapshotService,
            RefundEmailService refundEmailService,
            RefundApprovalTxService approvalTxService,
            PayOsPayoutService payOsPayoutService,
            VoucherRecoveryService voucherRecoveryService,
            IncidentService incidentService,
            PayoutEligibilityService payoutEligibilityService,
            @Autowired(required = false) PlatformTransactionManager transactionManager,
            @Autowired(required = false) CustomerBankAccountService customerBankAccountService) {
        this.bookingRepository = bookingRepository;
        this.ticketRepository = ticketRepository;
        this.bookingSeatRepository = bookingSeatRepository;
        this.bookingFoodItemRepository = bookingFoodItemRepository;
        this.componentRepository = componentRepository;
        this.foodFulfillmentRepository = foodFulfillmentRepository;
        this.checkinRepository = checkinRepository;
        this.incidentRepository = incidentRepository;
        this.refundRequestRepository = refundRequestRepository;
        this.refundRequestItemRepository = refundRequestItemRepository;
        this.historyRepository = historyRepository;
        this.communicationRepository = communicationRepository;
        this.auditLogRepository = auditLogRepository;
        this.payoutRepository = payoutRepository;
        this.voucherRecoveryRepository = voucherRecoveryRepository;
        this.userRepository = userRepository;
        this.cinemaRepository = cinemaRepository;
        this.paymentRepository = paymentRepository;
        this.bankEncryptionService = bankEncryptionService;
        this.evidenceStorageService = evidenceStorageService;
        this.snapshotService = snapshotService;
        this.refundEmailService = refundEmailService;
        this.approvalTxService = approvalTxService;
        this.payOsPayoutService = payOsPayoutService;
        this.voucherRecoveryService = voucherRecoveryService;
        this.incidentService = incidentService;
        this.payoutEligibilityService = payoutEligibilityService;
        this.customerBankAccountService = customerBankAccountService;
        if (transactionManager != null) {
            TransactionTemplate writeTpl = new TransactionTemplate(transactionManager);
            writeTpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            this.txTemplate = writeTpl;

            TransactionTemplate readTpl = new TransactionTemplate(transactionManager);
            readTpl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
            readTpl.setReadOnly(true);
            this.readOnlyTxTemplate = readTpl;
        } else {
            this.txTemplate = null;
            this.readOnlyTxTemplate = null;
        }
    }

    private <T> T runInTx(TransactionTemplate tpl, Supplier<T> action) {
        return tpl != null ? tpl.execute(status -> action.get()) : action.get();
    }

    @Transactional(readOnly = true)
    public OrderLookupResponseDto lookupPublicOrder(String orderCode, String purchaseEmail, String clientIp) {
        enforcePublicRateLimit("lookup:" + (clientIp != null ? clientIp : "unknown"), 30, 60_000L);
        Booking booking = resolveAndVerifyPaidBooking(orderCode, purchaseEmail);
        return buildOrderLookupDto(booking);
    }

    @PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
    @Transactional(readOnly = true)
    public OrderLookupResponseDto lookupOrderForStaff(String orderCode, String purchaseEmail, Long actorUserId) {
        User actor = requireStaffOrManagerActor(actorUserId);
        if (orderCode == null || orderCode.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "LOOKUP_INVALID", "Order or ticket code is required.");
        }
        String cleanCode = orderCode.trim();
        Optional<Booking> bookingOpt = bookingRepository.findByBookingCodeIgnoreCase(cleanCode);
        if (bookingOpt.isEmpty()) {
            bookingOpt = ticketRepository.findByTicketCode(cleanCode.toUpperCase(Locale.ROOT)).map(Ticket::getBooking);
        }
        Booking booking = bookingOpt.orElseThrow(() ->
                new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND", "Order not found."));
        enforceBookingBranchScope(actor, booking);
        if (purchaseEmail != null && !purchaseEmail.isBlank()) {
            resolveAndVerifyPaidBooking(booking.getBookingCode(), purchaseEmail);
        } else {
            String status = booking.getBookingStatus() != null ? booking.getBookingStatus().toUpperCase(Locale.ROOT) : "";
            if (!PayoutEligibilityService.PAYOUT_ELIGIBLE_BOOKING_STATUSES.contains(status)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_NOT_PAID",
                        "Only paid/confirmed orders are eligible for refund requests.");
            }
        }
        return buildOrderLookupDto(booking);
    }

    private OrderLookupResponseDto buildOrderLookupDto(Booking booking) {

        OrderLookupResponseDto dto = new OrderLookupResponseDto();
        dto.setBookingId(booking.getBookingId());
        dto.setBookingCode(booking.getBookingCode());
        dto.setOrderType(booking.getOrderType());
        dto.setBookingStatus(booking.getBookingStatus());
        dto.setCinemaId(booking.resolveCinemaId());
        if (booking.getCinema() != null) {
            dto.setCinemaName(booking.getCinema().getCinemaName());
        } else if (booking.getShowtime() != null && booking.getShowtime().getRoom() != null && booking.getShowtime().getRoom().getCinema() != null) {
            dto.setCinemaName(booking.getShowtime().getRoom().getCinema().getCinemaName());
        }
        if (booking.getShowtime() != null) {
            if (booking.getShowtime().getMovie() != null) {
                dto.setMovieTitle(booking.getShowtime().getMovie().getTitle());
            }
            if (booking.getShowtime().getStartAt() != null) {
                dto.setShowtimeStart(booking.getShowtime().getStartAt().format(DateTimeFormatter.ofPattern("HH:mm - dd/MM/yyyy")));
            }
        }
        dto.setTotalPaidAmount(booking.getTotalAmount());
        dto.setDiscountAmount(booking.getDiscountAmount());
        dto.setVoucherCode(booking.getVoucherCodeSnapshot());

        boolean bookingCheckedIn = checkinRepository.findByBooking_BookingId(booking.getBookingId()).isPresent();
        dto.setCheckedIn(bookingCheckedIn);

        Optional<FoodFulfillment> ffOpt = foodFulfillmentRepository.findByBooking_BookingId(booking.getBookingId());
        dto.setFoodFulfillmentStatus(ffOpt.map(FoodFulfillment::getStatus).orElse("NONE"));

        Long showtimeId = booking.getShowtime() != null ? booking.getShowtime().getShowtimeId() : null;
        boolean hasCinemaIncident = incidentRepository.existsVerifiedIncidentForBookingOrShowtime("CINEMA_INCIDENT", booking.getBookingId(), showtimeId);
        boolean hasFoodIncident = incidentRepository.existsVerifiedIncidentForBookingOrShowtime("PRODUCT_INCIDENT", booking.getBookingId(), showtimeId)
                || incidentRepository.existsVerifiedIncidentForBookingOrShowtime("STAFF_ERROR", booking.getBookingId(), showtimeId);

        IncidentReport verifiedInc = null;
        List<IncidentReport> byBookInc = incidentRepository.findByBooking_BookingIdAndStatus(booking.getBookingId(), "VERIFIED");
        if (!byBookInc.isEmpty()) {
            verifiedInc = byBookInc.get(0);
        } else if (showtimeId != null) {
            List<IncidentReport> byShowInc = incidentRepository.findByShowtime_ShowtimeIdAndStatus(showtimeId, "VERIFIED");
            if (!byShowInc.isEmpty()) {
                verifiedInc = byShowInc.get(0);
            }
        }
        if (refundDeadlineService != null) {
            RefundDeadlineService.DeadlineEvaluation dl = refundDeadlineService.evaluate(booking, verifiedInc);
            dto.setRefundDeadlineAt(dl.deadlineAt());
            dto.setDeadlineBasis(dl.deadlineBasis());
            dto.setDeadlineExceeded(dl.deadlineExceeded());
        }

        List<Ticket> tickets = ticketRepository.findByBooking_BookingId(booking.getBookingId());
        Map<Long, BookingSeat> seatMap = new HashMap<>();
        for (BookingSeat bs : bookingSeatRepository.findByBooking_BookingId(booking.getBookingId())) {
            seatMap.put(bs.getBookingSeatId(), bs);
        }
        for (Ticket t : tickets) {
            BookingSeat bs = seatMap.get(t.getBookingSeatId());
            String label = bs != null ? ("Ticket " + bs.getSeatLabelSnapshot() + " (" + t.getTicketCode() + ")") : t.getTicketCode();
            boolean hasActiveOrApproved = refundRequestItemRepository.existsActiveOrApprovedTicket(t.getTicketId());
            boolean isRefunded = "REFUNDED".equalsIgnoreCase(t.getTicketStatus()) || "REFUNDED".equalsIgnoreCase(t.getRefundStatus());
            boolean isConflictUnissued = "CONFLICT_UNISSUED".equalsIgnoreCase(t.getTicketStatus());
            boolean ticketCheckedIn = !isConflictUnissued
                    && ("USED".equalsIgnoreCase(t.getTicketStatus())
                    || "CHECKED_IN".equalsIgnoreCase(t.getTicketStatus())
                    || t.getCheckedInAt() != null);

            OrderLookupResponseDto.OrderItemOption opt = new OrderLookupResponseDto.OrderItemOption();
            opt.setItemKind("TICKET");
            opt.setTargetId(t.getTicketId());
            opt.setLabel(label);
            opt.setPurchasedQuantity(1);
            opt.setAlreadyRefundedQuantity(isRefunded ? 1 : 0);
            opt.setActiveRequestedQuantity(hasActiveOrApproved && !isRefunded ? 1 : 0);
            opt.setRemainingRefundableQuantity((isRefunded || hasActiveOrApproved) ? 0 : 1);
            opt.setGrossAmount(t.getGrossPrice() != null && t.getGrossPrice().compareTo(BigDecimal.ZERO) > 0 ? t.getGrossPrice() : t.getPrice());
            opt.setDiscountAllocated(t.getDiscountAllocated());
            opt.setNetPaidAmount(t.getNetPaidAmount() != null && t.getNetPaidAmount().compareTo(BigDecimal.ZERO) > 0 ? t.getNetPaidAmount() : t.getPrice());
            opt.setItemStatus(t.getTicketStatus());
            opt.setCheckedIn(ticketCheckedIn);
            opt.setEligibleHint(!isRefunded && !hasActiveOrApproved && (isConflictUnissued || !ticketCheckedIn || hasCinemaIncident));
            if (isRefunded) opt.setEligibilityNote("Already refunded");
            else if (hasActiveOrApproved) opt.setEligibilityNote("Refund request already pending");
            else if (isConflictUnissued) opt.setEligibilityNote("Seat conflict on late payment — eligible for full refund");
            else if (ticketCheckedIn && !hasCinemaIncident) opt.setEligibilityNote("Checked-in (requires verified cinema incident)");
            else opt.setEligibilityNote("Eligible for review");
            dto.getItems().add(opt);
        }

        List<BookingFoodItemComponent> allComps = componentRepository.findByBooking_BookingId(booking.getBookingId());
        Map<Long, List<BookingFoodItemComponent>> compMap = new LinkedHashMap<>();
        for (BookingFoodItemComponent c : allComps) {
            if (c.getBookingFoodItem() != null) {
                compMap.computeIfAbsent(c.getBookingFoodItem().getId(), k -> new ArrayList<>()).add(c);
            }
        }

        for (BookingFoodItem bfi : bookingFoodItemRepository.findByBooking_BookingId(booking.getBookingId())) {
            if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && compMap.containsKey(bfi.getId())) {
                for (BookingFoodItemComponent comp : compMap.get(bfi.getId())) {
                    int activeQty = refundRequestItemRepository.sumPendingComponentQty(comp.getId());
                    int maxRemaining = Math.max(0, comp.getTotalQuantity() - comp.getRefundedQuantity() - comp.getReplacementAcceptedQuantity() - activeQty);
                    OrderLookupResponseDto.OrderItemOption opt = new OrderLookupResponseDto.OrderItemOption();
                    opt.setItemKind("COMBO_COMPONENT");
                    opt.setTargetId(comp.getId());
                    opt.setParentLabel(bfi.getProductNameSnapshot());
                    opt.setLabel(comp.getComponentNameSnapshot() + (comp.getVariantNameSnapshot() != null ? " (" + comp.getVariantNameSnapshot() + ")" : "") + " — from " + bfi.getProductNameSnapshot());
                    opt.setPurchasedQuantity(comp.getTotalQuantity());
                    opt.setDeliveredQuantity(comp.getDeliveredQuantity());
                    opt.setReplacementAcceptedQuantity(comp.getReplacementAcceptedQuantity());
                    opt.setAlreadyRefundedQuantity(comp.getRefundedQuantity());
                    opt.setActiveRequestedQuantity(activeQty);
                    opt.setRemainingRefundableQuantity(maxRemaining);
                    opt.setGrossAmount(comp.getAllocatedSubtotal());
                    opt.setDiscountAllocated(comp.getDiscountAllocated());
                    opt.setNetPaidAmount(comp.getNetPaidAmount());
                    opt.setItemStatus(comp.getDeliveryStatus());
                    boolean allDelivered = comp.getDeliveredQuantity() >= comp.getTotalQuantity();
                    opt.setEligibleHint(maxRemaining > 0 && (!allDelivered || hasFoodIncident));
                    opt.setEligibilityNote(buildFoodEligibilityNote(maxRemaining, comp.getDeliveredQuantity(), comp.getTotalQuantity(), comp.getReplacementAcceptedQuantity(), hasFoodIncident));
                    dto.getItems().add(opt);
                }
            } else {
                int activeQty = refundRequestItemRepository.sumPendingFoodQty(bfi.getId());
                int maxRemaining = Math.max(0, bfi.getQuantity() - bfi.getRefundedQuantity() - bfi.getReplacementAcceptedQuantity() - activeQty);
                OrderLookupResponseDto.OrderItemOption opt = new OrderLookupResponseDto.OrderItemOption();
                opt.setItemKind("FOOD_ITEM");
                opt.setTargetId(bfi.getId());
                opt.setLabel(bfi.getProductNameSnapshot() + (bfi.getVariantNameSnapshot() != null ? " (" + bfi.getVariantNameSnapshot() + ")" : ""));
                opt.setPurchasedQuantity(bfi.getQuantity());
                opt.setDeliveredQuantity(bfi.getDeliveredQuantity());
                opt.setReplacementAcceptedQuantity(bfi.getReplacementAcceptedQuantity());
                opt.setAlreadyRefundedQuantity(bfi.getRefundedQuantity());
                opt.setActiveRequestedQuantity(activeQty);
                opt.setRemainingRefundableQuantity(maxRemaining);
                opt.setGrossAmount(bfi.getSubtotal());
                opt.setDiscountAllocated(bfi.getDiscountAllocated());
                opt.setNetPaidAmount(bfi.getNetPaidAmount() != null && bfi.getNetPaidAmount().compareTo(BigDecimal.ZERO) > 0 ? bfi.getNetPaidAmount() : bfi.getSubtotal());
                opt.setItemStatus(bfi.getDeliveryStatus());
                boolean allDelivered = bfi.getDeliveredQuantity() >= bfi.getQuantity();
                opt.setEligibleHint(maxRemaining > 0 && (!allDelivered || hasFoodIncident));
                opt.setEligibilityNote(buildFoodEligibilityNote(maxRemaining, bfi.getDeliveredQuantity(), bfi.getQuantity(), bfi.getReplacementAcceptedQuantity(), hasFoodIncident));
                dto.getItems().add(opt);
            }
        }
        return dto;
    }

    public RefundRequestDetailDto submitPublicRefundRequest(PublicRefundSubmitRequestDto req, Long loggedInUserId, String clientIp) {
        String emailNorm = req.getPurchaseEmail() != null ? req.getPurchaseEmail().trim().toLowerCase(Locale.ROOT) : "";

        if (req.getReasonCategory() == null || req.getReasonCategory().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Refund reason category is required.");
        }
        if (req.getDescription() == null || req.getDescription().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DESCRIPTION_REQUIRED", "Please describe the issue.");
        }
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ITEMS_REQUIRED", "Please select at least one ticket, food item, or combo component.");
        }

        ResolvedBankPayload bankPayload = resolveBankPayload(
                req.getSavedBankAccountId(),
                req.getToBin(),
                req.getToAccountNumber(),
                req.getConfirmAccountNumber(),
                req.getBankAccountName(),
                req.getBankNote(),
                loggedInUserId);
        String cleanEvidence = evidenceStorageService.sanitizeEvidenceUrl(req.getEvidenceUrl());

        if (refundRateLimitService != null) {
            refundRateLimitService.checkRefundCreateLimit(loggedInUserId, clientIp);
        } else {
            enforcePublicRateLimit("submit:" + (clientIp != null ? clientIp : "unknown") + ":" + emailNorm, 10, 300_000L);
        }

        // Resolve bookingId in a separate read-only TX BEFORE the write TX so InnoDB REPEATABLE READ
        // never establishes a stale snapshot before bookingRepository.findByIdForUpdate(bookingId).
        Long bookingId = runInTx(readOnlyTxTemplate,
                () -> resolveAndVerifyPaidBooking(req.getOrderCode(), req.getPurchaseEmail()).getBookingId());

        return runInTx(txTemplate, () -> submitPublicRefundRequestTx(
                req, loggedInUserId, clientIp, emailNorm, bankPayload, cleanEvidence, bookingId, null));
    }

    @PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
    public RefundRequestDetailDto submitStaffRefundRequest(
            PublicRefundSubmitRequestDto req, Long actorUserId, String clientIp) {
        User staffActor = requireStaffOrManagerActor(actorUserId);
        if (req == null || req.getOrderCode() == null || req.getOrderCode().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "LOOKUP_INVALID", "Order code is required.");
        }
        if (req.getReasonCategory() == null || req.getReasonCategory().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "Refund reason category is required.");
        }
        if (req.getDescription() == null || req.getDescription().isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "DESCRIPTION_REQUIRED", "Please describe the issue.");
        }
        if (req.getItems() == null || req.getItems().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ITEMS_REQUIRED",
                    "Please select at least one ticket, food item, or combo component.");
        }
        if (req.getSavedBankAccountId() != null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "SAVED_BANK_NOT_ALLOWED_FOR_STAFF",
                    "Counter refund intake requires explicit recipient bank account details.");
        }

        ResolvedBankPayload bankPayload = resolveBankPayload(
                null,
                req.getToBin(),
                req.getToAccountNumber(),
                req.getConfirmAccountNumber(),
                req.getBankAccountName(),
                req.getBankNote(),
                null);
        String cleanEvidence = evidenceStorageService.sanitizeEvidenceUrl(req.getEvidenceUrl());

        record StaffPreBooking(Long bookingId, String resolvedEmail) {}
        StaffPreBooking pre = runInTx(readOnlyTxTemplate, () -> {
            String cleanCode = req.getOrderCode().trim();
            Optional<Booking> bookingOpt = bookingRepository.findByBookingCodeIgnoreCase(cleanCode);
            if (bookingOpt.isEmpty()) {
                bookingOpt = ticketRepository.findByTicketCode(cleanCode.toUpperCase(Locale.ROOT)).map(Ticket::getBooking);
            }
            Booking b = bookingOpt.orElseThrow(() ->
                    new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found."));
            enforceBookingBranchScope(staffActor, b);
            String storedEmail = (b.getUser() != null && b.getUser().getEmail() != null && !b.getUser().getEmail().isBlank())
                    ? b.getUser().getEmail()
                    : b.getGuestEmail();
            String candidateEmail = (req.getPurchaseEmail() != null && !req.getPurchaseEmail().isBlank())
                    ? req.getPurchaseEmail().trim().toLowerCase(Locale.ROOT)
                    : (storedEmail != null ? storedEmail.trim().toLowerCase(Locale.ROOT) : "");
            if (candidateEmail.isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "CUSTOMER_EMAIL_REQUIRED",
                        "Customer purchase email is required for refund notification and tracking.");
            }
            if (req.getPurchaseEmail() != null && !req.getPurchaseEmail().isBlank()) {
                resolveAndVerifyPaidBooking(b.getBookingCode(), candidateEmail);
            } else {
                String status = b.getBookingStatus() != null ? b.getBookingStatus().toUpperCase(Locale.ROOT) : "";
                if (!PayoutEligibilityService.PAYOUT_ELIGIBLE_BOOKING_STATUSES.contains(status)) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_NOT_PAID",
                            "Only paid/confirmed orders are eligible for refund requests.");
                }
            }
            return new StaffPreBooking(b.getBookingId(), candidateEmail);
        });

        return runInTx(txTemplate, () -> submitPublicRefundRequestTx(
                req, null, clientIp, pre.resolvedEmail(), bankPayload, cleanEvidence, pre.bookingId(), staffActor));
    }

    private RefundRequestDetailDto submitPublicRefundRequestTx(
            PublicRefundSubmitRequestDto req, Long loggedInUserId, String clientIp, String emailNorm,
            ResolvedBankPayload bankPayload, String cleanEvidence, Long bookingId, User staffIntakeActor) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "Order not found or email mismatch."));

        if (staffIntakeActor != null) {
            enforceBookingBranchScope(staffIntakeActor, booking);
        } else if (booking.getUser() != null
                && (loggedInUserId == null || !booking.getUser().getUserId().equals(loggedInUserId))) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "BANK_ACCOUNT_ACCESS_DENIED",
                    "Authentication as the booking owner is required to submit a refund request for a member order.");
        }

        String holderNormalized = bankPayload.holderNormalized();
        Long cinemaId = booking.resolveCinemaId();
        Cinema cinema = cinemaId != null ? cinemaRepository.findById(cinemaId).orElse(null) : null;
        if (cinema == null && staffIntakeActor != null && staffIntakeActor.getCinemaId() != null) {
            cinema = cinemaRepository.findById(staffIntakeActor.getCinemaId()).orElse(null);
        }
        if (cinema == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "CINEMA_NOT_FOUND", "Cinema branch could not be resolved for this order.");
        }

        String resolvedCustomerName = req.getCustomerName() != null && !req.getCustomerName().isBlank()
                ? req.getCustomerName().trim()
                : (booking.getGuestName() != null && !booking.getGuestName().isBlank()
                    ? booking.getGuestName()
                    : (booking.getUser() != null ? booking.getUser().getFullName() : null));

        Long resolvedSavedBankId = bankPayload.savedBankAccountId();
        if (Boolean.TRUE.equals(req.getSaveBankAccount())
                && loggedInUserId != null
                && resolvedSavedBankId == null
                && customerBankAccountService != null) {
            resolvedSavedBankId = customerBankAccountService.saveFromRefundIfAbsent(
                    loggedInUserId,
                    bankPayload.bankBin(),
                    bankPayload.bankName(),
                    bankPayload.encryptedAccount(),
                    bankPayload.maskedAccount(),
                    bankPayload.accountHash(),
                    holderNormalized,
                    bankPayload.bankNote());
        }

        RefundRequest refundRequest = new RefundRequest();
        refundRequest.setRequestCode(generateRefundCode());
        refundRequest.setBooking(booking);
        refundRequest.setCinema(cinema);
        refundRequest.setCustomerUser(booking.getUser() != null ? booking.getUser()
                : (loggedInUserId != null ? userRepository.findById(loggedInUserId).orElse(null) : null));
        refundRequest.setCustomerName(resolvedCustomerName);
        refundRequest.setCustomerPhone(req.getCustomerPhone() != null && !req.getCustomerPhone().isBlank()
                ? req.getCustomerPhone().trim() : booking.getGuestPhone());
        refundRequest.setPurchaseEmail(emailNorm);
        refundRequest.setSubmittedOrderCode(req.getOrderCode().trim().toUpperCase(Locale.ROOT));
        refundRequest.setReasonCategory(req.getReasonCategory().trim().toUpperCase(Locale.ROOT));
        refundRequest.setDescription(req.getDescription().trim());
        refundRequest.setEvidenceUrl(cleanEvidence);
        refundRequest.setToBin(bankPayload.bankBin());
        refundRequest.setToBankName(bankPayload.bankName());
        refundRequest.setToAccountNumberEncrypted(bankPayload.encryptedAccount());
        refundRequest.setToAccountNumberMasked(bankPayload.maskedAccount());
        refundRequest.setToAccountNumberHash(bankPayload.accountHash());
        refundRequest.setAccountHolderName(holderNormalized);
        refundRequest.setBankNote(bankPayload.bankNote());
        refundRequest.setSavedBankAccountId(resolvedSavedBankId);
        refundRequest.setRequestStatus("PENDING_REVIEW");
        refundRequest.setPayoutStatus("NOT_REQUIRED");
        refundRequest.setVoucherStatus("NOT_REQUIRED");
        refundRequest.setRefundMethod(com.boxcinema.common.enums.RefundMethod.BANK_PAYOUT.name());

        Optional<Payment> origPaidOpt = paymentRepository.findFirstByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(
                booking.getBookingId(), "PAID");
        if (origPaidOpt.isPresent()) {
            Payment pay = origPaidOpt.get();
            refundRequest.setOriginalPaymentId(pay.getPaymentId());
            refundRequest.setOriginalPaymentRef(pay.getProviderTransactionId() != null
                    ? pay.getProviderTransactionId() : pay.getPaymentLinkId());
        }

        Long showtimeId = booking.getShowtime() != null ? booking.getShowtime().getShowtimeId() : null;
        List<IncidentReport> verifiedIncidents = incidentRepository.findByBooking_BookingIdAndStatus(booking.getBookingId(), "VERIFIED");
        if (verifiedIncidents.isEmpty() && showtimeId != null) {
            verifiedIncidents = incidentRepository.findByShowtime_ShowtimeIdAndStatus(showtimeId, "VERIFIED");
        }
        if (!verifiedIncidents.isEmpty()) {
            refundRequest.setIncidentReport(verifiedIncidents.get(0));
        }

        RefundDeadlineService effectiveDeadlineService = refundDeadlineService != null
                ? refundDeadlineService
                : new RefundDeadlineService();
        LocalDateTime submissionNow = effectiveDeadlineService.now();
        refundRequest.setCreatedAt(submissionNow);
        refundRequest.setUpdatedAt(submissionNow);
        RefundDeadlineService.DeadlineEvaluation deadlineEval =
                effectiveDeadlineService.evaluate(booking, refundRequest.getIncidentReport(), submissionNow);
        refundRequest.setRefundDeadlineAt(deadlineEval.deadlineAt());
        refundRequest.setDeadlineBasis(deadlineEval.deadlineBasis());
        refundRequest.setDeadlineExceeded(deadlineEval.deadlineExceeded());

        refundRequest = refundRequestRepository.save(refundRequest);

        List<RefundRequestItem> itemEntities = new ArrayList<>();
        BigDecimal requestedCashTotal = BigDecimal.ZERO;
        Set<String> seenTargets = new HashSet<>();

        for (PublicRefundSubmitRequestDto.RequestedItemDto itemReq : req.getItems()) {
            String kind = itemReq.getItemKind() != null ? itemReq.getItemKind().trim().toUpperCase(Locale.ROOT) : "";
            Long targetId = itemReq.getTargetId();
            if (targetId == null) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ITEM", "Item targetId is required.");
            }
            if (!seenTargets.add(kind + ":" + targetId)) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "DUPLICATE_ITEM_IN_REQUEST",
                        "Duplicate item selected in the same request.");
            }

            RefundRequestItem entity = new RefundRequestItem();
            entity.setRefundRequest(refundRequest);
            entity.setItemKind(kind);

            if ("TICKET".equals(kind)) {
                Ticket t = ticketRepository.findById(targetId)
                        .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "TICKET_NOT_FOUND", "Ticket not found."));
                if (!t.getBooking().getBookingId().equals(booking.getBookingId())) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "ITEM_MISMATCH", "Ticket does not belong to this order.");
                }
                if ("REFUNDED".equalsIgnoreCase(t.getTicketStatus())
                        || "REFUNDED".equalsIgnoreCase(t.getRefundStatus())
                        || refundRequestItemRepository.existsActiveOrApprovedTicket(t.getTicketId())) {
                    throw new BusinessException(HttpStatus.CONFLICT, "DUPLICATE_REFUND_REQUEST",
                            "Ticket " + t.getTicketCode() + " already has an active or completed refund.");
                }
                String seatLabel = bookingSeatRepository.findByBooking_BookingId(booking.getBookingId()).stream()
                        .filter(bs -> bs.getBookingSeatId().equals(t.getBookingSeatId()))
                        .map(BookingSeat::getSeatLabelSnapshot).findFirst().orElse("");
                BigDecimal gross = t.getGrossPrice() != null && t.getGrossPrice().compareTo(BigDecimal.ZERO) > 0 ? t.getGrossPrice() : t.getPrice();
                BigDecimal disc = t.getDiscountAllocated() != null ? t.getDiscountAllocated() : BigDecimal.ZERO;
                BigDecimal net = t.getNetPaidAmount() != null && t.getNetPaidAmount().compareTo(BigDecimal.ZERO) > 0 ? t.getNetPaidAmount() : gross.subtract(disc);

                entity.setTicketId(t.getTicketId());
                entity.setBookingSeatId(t.getBookingSeatId());
                entity.setItemLabelSnapshot("Ticket " + seatLabel + " (" + t.getTicketCode() + ")");
                entity.setPurchasedQuantity(1);
                entity.setRequestedQuantity(1);
                entity.setGrossUnitPrice(gross);
                entity.setDiscountAllocatedAmount(disc);
                entity.setNetPaidAmount(net);
                entity.setActiveLockKey("TICKET:" + t.getTicketId());
                requestedCashTotal = requestedCashTotal.add(net);
            } else if ("FOOD_ITEM".equals(kind)) {
                BookingFoodItem bfi = bookingFoodItemRepository.findByIdForUpdate(targetId)
                        .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "FOOD_NOT_FOUND", "Food item not found."));
                if (!bfi.getBooking().getBookingId().equals(booking.getBookingId())) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "ITEM_MISMATCH", "Food item does not belong to this order.");
                }
                int active = refundRequestItemRepository.sumPendingFoodQty(bfi.getId());
                int availableQty = bfi.getQuantity() - bfi.getRefundedQuantity() - bfi.getReplacementAcceptedQuantity() - active;
                int reqQty = itemReq.getQuantity();
                if (reqQty <= 0 || reqQty > availableQty) {
                    throw new BusinessException(HttpStatus.CONFLICT, "DUPLICATE_OR_EXCESS_QTY",
                            "Requested quantity exceeds remaining refundable quantity for " + bfi.getProductNameSnapshot());
                }
                BigDecimal lineNet = bfi.getNetPaidAmount() != null && bfi.getNetPaidAmount().compareTo(BigDecimal.ZERO) > 0 ? bfi.getNetPaidAmount() : bfi.getSubtotal();
                BigDecimal lineDisc = bfi.getDiscountAllocated() != null ? bfi.getDiscountAllocated() : BigDecimal.ZERO;
                long[] prev = approvalTxService.computePriorFoodRefundedTotals(booking.getBookingId(), bfi.getId(), null);
                long[] est = snapshotService.calculateQuantityRefund(bfi.getQuantity(), lineNet, lineDisc,
                        bfi.getRefundedQuantity(), BigDecimal.valueOf(prev[0]), BigDecimal.valueOf(prev[1]), reqQty);

                entity.setBookingFoodItemId(bfi.getId());
                entity.setItemLabelSnapshot(bfi.getProductNameSnapshot() + (bfi.getVariantNameSnapshot() != null ? " (" + bfi.getVariantNameSnapshot() + ")" : ""));
                entity.setPurchasedQuantity(bfi.getQuantity());
                entity.setRequestedQuantity(reqQty);
                entity.setGrossUnitPrice(bfi.getUnitPriceSnapshot());
                entity.setDiscountAllocatedAmount(BigDecimal.valueOf(est[1]).setScale(2, RoundingMode.HALF_UP));
                entity.setNetPaidAmount(BigDecimal.valueOf(est[0]).setScale(2, RoundingMode.HALF_UP));
                entity.setActiveLockKey("FOOD:" + bfi.getId() + ":" + refundRequest.getId());
                requestedCashTotal = requestedCashTotal.add(entity.getNetPaidAmount());
            } else if ("COMBO_COMPONENT".equals(kind)) {
                BookingFoodItemComponent comp = componentRepository.findByIdForUpdate(targetId)
                        .orElseThrow(() -> new BusinessException(HttpStatus.BAD_REQUEST, "COMPONENT_NOT_FOUND", "Combo component not found."));
                if (!comp.getBooking().getBookingId().equals(booking.getBookingId())) {
                    throw new BusinessException(HttpStatus.BAD_REQUEST, "ITEM_MISMATCH", "Combo component does not belong to this order.");
                }
                int active = refundRequestItemRepository.sumPendingComponentQty(comp.getId());
                int availableQty = comp.getTotalQuantity() - comp.getRefundedQuantity() - comp.getReplacementAcceptedQuantity() - active;
                int reqQty = itemReq.getQuantity();
                if (reqQty <= 0 || reqQty > availableQty) {
                    throw new BusinessException(HttpStatus.CONFLICT, "DUPLICATE_OR_EXCESS_QTY",
                            "Requested quantity exceeds remaining refundable quantity for " + comp.getComponentNameSnapshot());
                }
                long[] prev = approvalTxService.computePriorFoodRefundedTotals(booking.getBookingId(), null, comp.getId());
                long[] est = snapshotService.calculateQuantityRefund(comp.getTotalQuantity(), comp.getNetPaidAmount(), comp.getDiscountAllocated(),
                        comp.getRefundedQuantity(), BigDecimal.valueOf(prev[0]), BigDecimal.valueOf(prev[1]), reqQty);

                entity.setBookingFoodItemId(comp.getBookingFoodItem() != null ? comp.getBookingFoodItem().getId() : null);
                entity.setBookingFoodItemComponentId(comp.getId());
                entity.setItemLabelSnapshot(comp.getComponentNameSnapshot()
                        + (comp.getVariantNameSnapshot() != null ? " (" + comp.getVariantNameSnapshot() + ")" : "")
                        + (comp.getBookingFoodItem() != null ? " — " + comp.getBookingFoodItem().getProductNameSnapshot() : ""));
                entity.setPurchasedQuantity(comp.getTotalQuantity());
                entity.setRequestedQuantity(reqQty);
                entity.setGrossUnitPrice(comp.getAllocatedUnitPrice());
                entity.setDiscountAllocatedAmount(BigDecimal.valueOf(est[1]).setScale(2, RoundingMode.HALF_UP));
                entity.setNetPaidAmount(BigDecimal.valueOf(est[0]).setScale(2, RoundingMode.HALF_UP));
                entity.setActiveLockKey("COMP:" + comp.getId() + ":" + refundRequest.getId());
                requestedCashTotal = requestedCashTotal.add(entity.getNetPaidAmount());
            } else {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_ITEM_KIND", "Unsupported item kind: " + kind);
            }

            itemEntities.add(entity);
        }

        try {
            refundRequestItemRepository.saveAll(itemEntities);
        } catch (DataIntegrityViolationException ex) {
            throw new BusinessException(HttpStatus.CONFLICT, "DUPLICATE_REFUND_REQUEST",
                    "An active refund request already exists for one or more selected items.");
        }

        refundRequest.setRequestedCashTotal(requestedCashTotal.setScale(2, RoundingMode.HALF_UP));
        refundRequest = refundRequestRepository.save(refundRequest);
        if (cleanEvidence != null && evidenceStorageService != null) {
            evidenceStorageService.linkEvidenceToRefund(
                    cleanEvidence,
                    refundRequest.getId(),
                    staffIntakeActor != null ? staffIntakeActor.getUserId() : loggedInUserId,
                    clientIp);
        }

        RefundApprovalHistory h = new RefundApprovalHistory();
        h.setRefundRequest(refundRequest);
        if (staffIntakeActor != null) {
            h.setActorUser(staffIntakeActor);
            h.setActorRole(staffIntakeActor.getRole());
            h.setAction("SUBMITTED_BY_STAFF");
            h.setNewStatus("PENDING_REVIEW");
            h.setNote("Submitted at branch counter by " + staffIntakeActor.getFullName()
                    + " with masked payout account " + bankPayload.maskedAccount());
        } else {
            h.setActorUser(refundRequest.getCustomerUser());
            h.setActorRole(refundRequest.getCustomerUser() != null ? "CUSTOMER" : "CUSTOMER_GUEST");
            h.setAction("SUBMITTED");
            h.setNewStatus("PENDING_REVIEW");
            h.setNote("Submitted via /contact with masked payout account " + bankPayload.maskedAccount());
        }
        historyRepository.save(h);

        RefundCommunication comm = new RefundCommunication();
        comm.setRefundRequest(refundRequest);
        comm.setSenderType(staffIntakeActor != null ? "STAFF" : "CUSTOMER");
        comm.setSenderUser(staffIntakeActor != null ? staffIntakeActor : refundRequest.getCustomerUser());
        comm.setSenderName(staffIntakeActor != null ? staffIntakeActor.getFullName() : refundRequest.getCustomerName());
        comm.setMessage(refundRequest.getDescription());
        comm.setAttachmentUrl(cleanEvidence);
        communicationRepository.save(comm);

        RefundAuditLog audit = new RefundAuditLog();
        audit.setRefundRequestId(refundRequest.getId());
        audit.setEntityType("REFUND_REQUEST");
        audit.setEntityId(refundRequest.getId());
        audit.setEventType(staffIntakeActor != null ? "REFUND_SUBMITTED_BY_STAFF" : "REFUND_SUBMITTED");
        audit.setActorUserId(staffIntakeActor != null ? staffIntakeActor.getUserId() : loggedInUserId);
        audit.setActorRole(staffIntakeActor != null
                ? staffIntakeActor.getRole()
                : (refundRequest.getCustomerUser() != null ? "CUSTOMER" : "CUSTOMER_GUEST"));
        audit.setCinemaId(cinema.getCinemaId());
        audit.setIpAddress(clientIp);
        audit.setDetailsJson(toJson(Map.of(
                "requestCode", refundRequest.getRequestCode(),
                "maskedAccount", bankPayload.maskedAccount()
        )));
        auditLogRepository.save(audit);

        refundEmailService.enqueueReceivedEmail(refundRequest, itemEntities);
        return mapToDetailDto(refundRequest);
    }

    @Transactional(readOnly = true)
    public RefundRequestDetailDto lookupPublicRefundStatus(String requestCode, String purchaseEmail, String clientIp) {
        enforcePublicRateLimit("status:" + (clientIp != null ? clientIp : "unknown"), 30, 60_000L);
        RefundRequest req = verifyPublicRefundAccess(requestCode, purchaseEmail);
        return mapToDetailDto(req);
    }

    @Transactional
    public RefundRequestDetailDto replyToRefundRequest(String requestCode, String purchaseEmail, String message, String evidenceUrl, String clientIp) {
        enforcePublicRateLimit("reply:" + (clientIp != null ? clientIp : "unknown"), 15, 60_000L);
        RefundRequest lookup = verifyPublicRefundAccess(requestCode, purchaseEmail);
        if (message == null || message.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "MESSAGE_REQUIRED", "Reply message cannot be empty.");
        }
        String cleanUrl = evidenceStorageService.sanitizeEvidenceUrl(evidenceUrl);

        Long bookingId = lookup.getBooking() != null ? lookup.getBooking().getBookingId() : null;
        if (entityManager != null && entityManager.contains(lookup)) {
            entityManager.detach(lookup);
        }
        if (bookingId != null) {
            bookingRepository.findByIdForUpdate(bookingId);
        }
        RefundRequest req = refundRequestRepository.findByIdForUpdate(lookup.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_EMAIL_MISMATCH", "Refund request not found or email mismatch."));

        RefundCommunication comm = new RefundCommunication();
        comm.setRefundRequest(req);
        comm.setSenderType("CUSTOMER");
        comm.setSenderUser(req.getCustomerUser());
        comm.setSenderName(req.getCustomerName());
        comm.setMessage(message.trim());
        comm.setAttachmentUrl(cleanUrl);
        communicationRepository.save(comm);

        if (cleanUrl != null && (req.getEvidenceUrl() == null || req.getEvidenceUrl().isBlank())) {
            req.setEvidenceUrl(cleanUrl);
        }
        if (cleanUrl != null && evidenceStorageService != null) {
            evidenceStorageService.linkEvidenceToRefund(
                    cleanUrl,
                    req.getId(),
                    req.getCustomerUser() != null ? req.getCustomerUser().getUserId() : null,
                    clientIp);
        }
        if ("MORE_INFO_REQUIRED".equalsIgnoreCase(req.getRequestStatus())) {
            req.setRequestStatus("PENDING_REVIEW");
            for (RefundRequestItem item : refundRequestItemRepository.findByRefundRequestIdForUpdate(req.getId())) {
                if ("MORE_INFO_REQUIRED".equalsIgnoreCase(item.getItemStatus())) {
                    item.setItemStatus("PENDING");
                }
            }
        }
        refundRequestRepository.save(req);

        RefundApprovalHistory h = new RefundApprovalHistory();
        h.setRefundRequest(req);
        h.setActorRole("CUSTOMER");
        h.setAction("CUSTOMER_REPLIED");
        h.setPreviousStatus("MORE_INFO_REQUIRED");
        h.setNewStatus(req.getRequestStatus());
        h.setNote("Customer submitted additional information.");
        historyRepository.save(h);

        return mapToDetailDto(req);
    }

    public RefundRequestDetailDto updatePublicRefundBankAccount(
            String requestCode,
            RefundBankUpdateRequestDto req,
            Long loggedInUserId,
            String clientIp) {
        enforcePublicRateLimit("bank-update:" + (clientIp != null ? clientIp : "unknown"), 15, 60_000L);
        String purchaseEmail = req != null ? req.getPurchaseEmail() : null;

        record PreUpdateCheck(Long refundId, Long bookingId) {}
        PreUpdateCheck preCheck = runInTx(readOnlyTxTemplate, () -> {
            RefundRequest lookup = verifyPublicRefundAccess(requestCode, purchaseEmail);
            if (lookup.getCustomerUser() != null
                    && (loggedInUserId == null || !lookup.getCustomerUser().getUserId().equals(loggedInUserId))) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "BANK_ACCOUNT_ACCESS_DENIED",
                        "Authentication as the refund owner is required to update recipient bank account details.");
            }
            Long bId = lookup.getBooking() != null ? lookup.getBooking().getBookingId() : null;
            Long rId = lookup.getId();
            if (entityManager != null && entityManager.contains(lookup)) {
                entityManager.detach(lookup);
            }
            return new PreUpdateCheck(rId, bId);
        });

        ResolvedBankPayload bankPayload = resolveBankPayload(
                req != null ? req.getSavedBankAccountId() : null,
                req != null ? req.getToBin() : null,
                req != null ? req.getToAccountNumber() : null,
                req != null ? req.getConfirmAccountNumber() : null,
                req != null ? req.getBankAccountName() : null,
                req != null ? req.getBankNote() : null,
                loggedInUserId);

        return runInTx(txTemplate, () -> {
            if (preCheck.bookingId() != null) {
                bookingRepository.findByIdForUpdate(preCheck.bookingId());
            }
            RefundRequest locked = refundRequestRepository.findByIdForUpdate(preCheck.refundId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_EMAIL_MISMATCH",
                            "Refund request not found or email mismatch."));
            if (!locked.getPurchaseEmail().equalsIgnoreCase(purchaseEmail.trim())) {
                throw new BusinessException(HttpStatus.NOT_FOUND, "REFUND_EMAIL_MISMATCH",
                        "Refund request not found or email mismatch.");
            }
            if (locked.getCustomerUser() != null
                    && (loggedInUserId == null || !locked.getCustomerUser().getUserId().equals(loggedInUserId))) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "BANK_ACCOUNT_ACCESS_DENIED",
                        "Authentication as the refund owner is required to update recipient bank account details.");
            }

            if (!isBankAccountEditable(locked) || payoutRepository.findByActiveRequestLock(locked.getId()).isPresent()) {
                throw new BusinessException(HttpStatus.CONFLICT, "BANK_ACCOUNT_LOCKED_AFTER_APPROVAL",
                        "Recipient bank account is locked after business approval or during payout processing.");
            }

            String oldMasked = (locked.getToBankName() != null ? locked.getToBankName() : "")
                    + " " + (locked.getToAccountNumberMasked() != null ? locked.getToAccountNumberMasked() : "");
            String newMasked = bankPayload.bankName() + " " + bankPayload.maskedAccount();
            String prevStatus = locked.getRequestStatus();

            locked.setToBin(bankPayload.bankBin());
            locked.setToBankName(bankPayload.bankName());
            locked.setToAccountNumberEncrypted(bankPayload.encryptedAccount());
            locked.setToAccountNumberMasked(bankPayload.maskedAccount());
            locked.setToAccountNumberHash(bankPayload.accountHash());
            locked.setAccountHolderName(bankPayload.holderNormalized());
            locked.setBankNote(bankPayload.bankNote());
            locked.setSavedBankAccountId(bankPayload.savedBankAccountId());
            if ("MORE_INFO_REQUIRED".equalsIgnoreCase(prevStatus)) {
                locked.setRequestStatus("PENDING_REVIEW");
                List<RefundRequestItem> lockedItems = refundRequestItemRepository.findByRefundRequestIdForUpdate(locked.getId());
                for (RefundRequestItem item : lockedItems) {
                    if ("MORE_INFO_REQUIRED".equalsIgnoreCase(item.getItemStatus())) {
                        item.setItemStatus("PENDING");
                    }
                }
                refundRequestItemRepository.saveAll(lockedItems);
            }
            locked = refundRequestRepository.save(locked);

            RefundApprovalHistory h = new RefundApprovalHistory();
            h.setRefundRequest(locked);
            h.setActorUser(loggedInUserId != null ? userRepository.findById(loggedInUserId).orElse(null) : locked.getCustomerUser());
            h.setActorRole(locked.getCustomerUser() != null ? "CUSTOMER" : "CUSTOMER_GUEST");
            h.setAction("BANK_ACCOUNT_UPDATED");
            h.setPreviousStatus(prevStatus);
            h.setNewStatus(locked.getRequestStatus());
            h.setNote("Updated bank account: " + oldMasked.trim() + " -> " + newMasked.trim());
            historyRepository.save(h);

            RefundAuditLog audit = new RefundAuditLog();
            audit.setRefundRequestId(locked.getId());
            audit.setEntityType("REFUND_REQUEST");
            audit.setEntityId(locked.getId());
            audit.setEventType("REFUND_BANK_ACCOUNT_UPDATED");
            audit.setActorUserId(loggedInUserId);
            audit.setActorRole(locked.getCustomerUser() != null ? "CUSTOMER" : "CUSTOMER_GUEST");
            audit.setCinemaId(locked.getCinema() != null ? locked.getCinema().getCinemaId() : null);
            audit.setIpAddress(clientIp);
            audit.setDetailsJson(toJson(Map.of(
                    "oldMasked", oldMasked.trim(),
                    "newMasked", newMasked.trim()
            )));
            auditLogRepository.save(audit);

            return mapToDetailDto(locked);
        });
    }

    /**
     * Stage 1 business verification & approval entrypoint.
     * Cash payouts are NEVER dispatched automatically here — approved cash requests enter AWAITING_PAYOUT_APPROVAL
     * for Admin Financial Review (Stage 2). Voucher recovery still runs independently in REQUIRES_NEW.
     */
    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public RefundRequestDetailDto reviewRefundRequest(Long refundRequestId, RefundReviewRequestDto reviewDto, Long actorUserId) {
        RefundApprovalTxService.ReviewOutcome outcome = approvalTxService.applyReviewDecision(refundRequestId, reviewDto, actorUserId);

        if (outcome.triggerVoucherRecovery()) {
            try {
                voucherRecoveryService.generateRecoveryVoucher(refundRequestId, outcome.firstApprovedItemId(), actorUserId);
            } catch (Exception ex) {
                log.error("Voucher recovery generation failed for refundRequestId={}: {}",
                        refundRequestId, sanitizeLogMessage(ex.getMessage()));
            }
        }
        approvalTxService.syncBookingAndSeatsOnTerminalRefund(refundRequestId);
        return getRefundDetailForStaffOrManager(refundRequestId, actorUserId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public RefundRequestDetailDto grantDeadlineException(Long refundRequestId, String reason, Long adminUserId) {
        approvalTxService.grantDeadlineExceptionTx(refundRequestId, reason, adminUserId);
        return getRefundDetailForStaffOrManager(refundRequestId, adminUserId);
    }

    /**
     * Stage 2 (Admin only): Single Refund Payout Approval & PayOS Dispatch.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public RefundRequestDetailDto approveSinglePayout(Long refundRequestId, String note, Long adminUserId) {
        return approveSinglePayout(refundRequestId, note, null, null, adminUserId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public RefundRequestDetailDto approveSinglePayout(Long refundRequestId,
                                                      String note,
                                                      Boolean limitOverrideApproved,
                                                      String limitOverrideReason,
                                                      Long adminUserId) {
        RefundApprovalTxService.PayoutDispatchPrep prep =
                approvalTxService.approvePayoutForDispatchTx(
                        refundRequestId, adminUserId, note, null, limitOverrideApproved, limitOverrideReason);
        if (!prep.proceed()) {
            if (!prep.alreadyProcessed()) {
                throw new BusinessException(HttpStatus.CONFLICT, "PAYOUT_INELIGIBLE", prep.message());
            }
            // Idempotent re-dispatch / reconcile if already PAYOUT_APPROVED or PROCESSING
            RefundRequest current = refundRequestRepository.findById(refundRequestId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
            if ("PAYOUT_APPROVED".equalsIgnoreCase(current.getRequestStatus())
                    || "PAYOUT_PROCESSING".equalsIgnoreCase(current.getRequestStatus())) {
                payOsPayoutService.initiateOrReconcilePayout(refundRequestId, adminUserId, null);
            }
            approvalTxService.syncBookingAndSeatsOnTerminalRefund(refundRequestId);
            return getRefundDetailForStaffOrManager(refundRequestId, adminUserId);
        }

        payOsPayoutService.initiateOrReconcilePayout(refundRequestId, adminUserId, null);
        approvalTxService.syncBookingAndSeatsOnTerminalRefund(refundRequestId);
        return getRefundDetailForStaffOrManager(refundRequestId, adminUserId);
    }

    /**
     * Stage 2 (Admin only): Single Refund Payout Rejection with mandatory reason.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public RefundRequestDetailDto rejectSinglePayout(Long refundRequestId, String reason, Long adminUserId) {
        approvalTxService.rejectPayoutTx(refundRequestId, adminUserId, reason);
        return getRefundDetailForStaffOrManager(refundRequestId, adminUserId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public RefundRequestDetailDto holdPayoutForReverify(Long refundRequestId, String reason, Long adminUserId) {
        approvalTxService.holdForBankReverifyTx(refundRequestId, adminUserId, reason);
        return getRefundDetailForStaffOrManager(refundRequestId, adminUserId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public PayoutTransactionDetailDto confirmPayoutFailed(Long txId, String reason, Long adminUserId) {
        requireAdminActor(adminUserId);
        PayOsPayoutTransaction tx = payOsPayoutService.confirmPayoutFailedByAdmin(txId, reason, adminUserId);
        return getPayoutTransactionDetail(tx.getId(), adminUserId);
    }

    /**
     * Stage 2 (Admin only): Preview a batch payout selection (count, total VND, eligible vs ineligible items).
     */
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public BatchPayoutPreviewDto previewBatchPayout(List<Long> refundRequestIds, Long adminUserId) {
        requireAdminActor(adminUserId);
        List<Long> orderedIds = deduplicateIds(refundRequestIds);

        BatchPayoutPreviewDto preview = new BatchPayoutPreviewDto();
        preview.setRequestedCount(orderedIds.size());
        if (payOsPayoutService != null) {
            preview.setLivePayoutEnabled(payOsPayoutService.isLivePayoutEnabled());
            preview.setPayoutMode(payOsPayoutService.getMode());
            preview.setProviderName(payOsPayoutService.getPayoutProviderAdapter().getProviderName());
            preview.setConfiguredFeePolicy(payOsPayoutService.getPayoutProviderAdapter()
                    .resolveConfiguredPolicy("LIVE".equalsIgnoreCase(payOsPayoutService.getMode())).name());
        }
        if (orderedIds.isEmpty()) {
            return preview;
        }

        Map<Long, RefundRequest> reqMap = new HashMap<>();
        for (RefundRequest rr : refundRequestRepository.findByIdIn(orderedIds)) {
            reqMap.put(rr.getId(), rr);
        }
        Set<Long> sharedBeneficiaryIds = findSharedBeneficiaryIdsAcrossDistinctBookings(reqMap.values());

        long totalVnd = 0L;
        int blockedCount = 0;
        for (Long id : orderedIds) {
            RefundRequest rr = reqMap.get(id);
            BatchPayoutPreviewDto.BatchPreviewItemDto item = new BatchPayoutPreviewDto.BatchPreviewItemDto();
            item.setRefundRequestId(id);
            if (rr == null) {
                item.setRequestCode("#" + id);
                item.setEligible(false);
                item.setIneligibleReasons(List.of("Không tìm thấy yêu cầu hoàn tiền."));
                preview.getIneligibleItems().add(item);
                continue;
            }
            PayoutEligibilityService.EvaluationResult eval = payoutEligibilityService.evaluate(rr, true, adminUserId);
            List<String> reasons = new ArrayList<>(eval.reasons());
            boolean eligible = eval.eligible();
            if (sharedBeneficiaryIds.contains(rr.getId())) {
                eligible = false;
                reasons.add("Tài khoản ngân hàng nhận tiền bị trùng lặp giữa nhiều đơn hàng/khách hàng trong cùng lô duyệt chi (SHARED_BENEFICIARY_IN_BATCH).");
            }
            item.setRequestCode(rr.getRequestCode());
            item.setBookingCode(rr.getBooking() != null ? rr.getBooking().getBookingCode() : rr.getSubmittedOrderCode());
            item.setCustomerName(PayoutEligibilityService.resolveRecipientHolderName(rr, rr.getBooking()));
            item.setPurchaseEmail(rr.getPurchaseEmail());
            item.setCinemaId(rr.getCinema() != null ? rr.getCinema().getCinemaId() : null);
            item.setCinemaName(rr.getCinema() != null ? rr.getCinema().getCinemaName() : null);
            item.setReasonCategory(rr.getReasonCategory());
            item.setVerifierName(rr.getReviewedBy() != null ? rr.getReviewedBy().getFullName() : null);
            item.setToBankName(rr.getToBankName());
            item.setToBin(rr.getToBin());
            item.setToAccountNumberMasked(rr.getToAccountNumberMasked());
            item.setAccountHolderName(rr.getAccountHolderName());
            item.setTransferDescription(PayOsPayoutService.buildTransferDescription(rr.getId()));
            item.setRefundMethod(rr.getRefundMethod());
            item.setAmountVnd(eval.amountVnd());
            item.setRequestStatus(rr.getRequestStatus());
            item.setPayoutStatus(rr.getPayoutStatus());
            item.setWorkflowStatus(rr.getWorkflowStatus());
            if (payOsPayoutService != null && eval.amountVnd() > 0) {
                PayoutFeeAssessment fee = payOsPayoutService.assessFeePreflight(rr, eval.amountVnd());
                item.setFeeClassification(fee.classification().name());
                item.setEstimatedFeeVnd(fee.estimatedFeeVnd());
                item.setNetRecipientVnd(fee.netRecipientVnd());
                item.setTotalDebitVnd(fee.totalDebitVnd());
                item.setGuaranteedFullReceipt(fee.guaranteedFullReceipt());
                item.setFeeBlocked(fee.blocked());
                item.setFeeBlockReason(fee.blocked() ? fee.reason() : null);
                if (fee.blocked()) {
                    blockedCount++;
                }
            }
            item.setEligible(eligible);
            item.setIneligibleReasons(reasons);

            if (eligible) {
                totalVnd += eval.amountVnd();
                preview.getEligibleItems().add(item);
            } else {
                preview.getIneligibleItems().add(item);
            }
        }

        preview.setEligibleCount(preview.getEligibleItems().size());
        preview.setIneligibleCount(preview.getIneligibleItems().size());
        preview.setBlockedCount(blockedCount);
        preview.setTotalAmountVnd(totalVnd);
        return preview;
    }

    /**
     * Stage 2 (Admin only): Executes a Batch Payout where EACH RefundRequest gets its own independent
     * atomic approval + PayOS Payout transaction + unique referenceId & idempotencyKey.
     * Intentionally NON-transactional so PayOS HTTP runs outside DB locks and one failure never rolls back others.
     */
    @PreAuthorize("hasRole('ADMIN')")
    public BatchPayoutResponseDto executeBatchPayout(BatchPayoutRequestDto requestDto, Long adminUserId) {
        User admin = requireAdminActor(adminUserId);
        List<Long> orderedIds = requestDto != null ? deduplicateIds(requestDto.getRefundRequestIds()) : List.of();
        if (orderedIds.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "BATCH_IDS_REQUIRED",
                    "Please select at least one refund request for batch payout.");
        }
        if (orderedIds.size() > 100) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "BATCH_SIZE_EXCEEDED",
                    "Maximum 100 refund requests allowed per batch payout.");
        }

        List<RefundRequest> batchSnapshot = refundRequestRepository.findByIdIn(orderedIds);
        Set<Long> sharedBeneficiaryIds = findSharedBeneficiaryIdsAcrossDistinctBookings(batchSnapshot);
        if (!sharedBeneficiaryIds.isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "SHARED_BENEFICIARY_IN_BATCH",
                    "Batch payout rejected: multiple refund requests across distinct orders/customers share the same recipient bank account.");
        }

        if (requestDto.getExpectedTotalVnd() != null && !Boolean.FALSE.equals(requestDto.getStrictTotalMatch())) {
            BatchPayoutPreviewDto pre = previewBatchPayout(orderedIds, adminUserId);
            if (pre.getTotalAmountVnd() != requestDto.getExpectedTotalVnd()) {
                throw new BusinessException(HttpStatus.CONFLICT, "BATCH_TOTAL_MISMATCH",
                        "Expected batch total (" + requestDto.getExpectedTotalVnd()
                                + " VND) does not match currently eligible total (" + pre.getTotalAmountVnd() + " VND).");
            }
        }

        String batchCode = generateBatchCode();
        String note = requestDto.getNote() != null ? requestDto.getNote().trim() : null;

        BatchPayoutResponseDto response = new BatchPayoutResponseDto();
        response.setBatchCode(batchCode);
        response.setRequestedCount(orderedIds.size());
        if (payOsPayoutService != null) {
            response.setLivePayoutEnabled(payOsPayoutService.isLivePayoutEnabled());
            response.setPayoutMode(payOsPayoutService.getMode());
        }

        int eligibleCount = 0;
        int succeededCount = 0;
        int processingCount = 0;
        int unknownCount = 0;
        int blockedCount = 0;
        int failedCount = 0;
        int skippedCount = 0;
        long requestedTotal = 0L;
        long dispatchedTotal = 0L;
        long succeededTotal = 0L;

        for (Long reqId : orderedIds) {
            BatchPayoutResponseDto.BatchPayoutItemResultDto itemRes = new BatchPayoutResponseDto.BatchPayoutItemResultDto();
            itemRes.setRefundRequestId(reqId);

            RefundRequest snap = refundRequestRepository.findById(reqId).orElse(null);
            if (snap != null) {
                itemRes.setRequestCode(snap.getRequestCode());
                itemRes.setBookingCode(snap.getBooking() != null ? snap.getBooking().getBookingCode() : snap.getSubmittedOrderCode());
                itemRes.setCustomerName(PayoutEligibilityService.resolveRecipientHolderName(snap, snap.getBooking()));
                itemRes.setRecipientBank((snap.getToBankName() != null ? snap.getToBankName() : "") + (snap.getToBin() != null ? " (" + snap.getToBin() + ")" : ""));
                itemRes.setMaskedAccount(snap.getToAccountNumberMasked());
                long amt = snap.getApprovedCashTotal() != null ? PurchaseSnapshotService.toVndLong(snap.getApprovedCashTotal()) : 0L;
                itemRes.setAmount(amt);
                requestedTotal += amt;
            } else {
                itemRes.setRequestCode("#" + reqId);
            }

            RefundApprovalTxService.PayoutDispatchPrep prep;
            try {
                prep = approvalTxService.approvePayoutForDispatchTx(reqId, adminUserId, note, batchCode);
            } catch (BusinessException ex) {
                if (ex.getStatus() == HttpStatus.FORBIDDEN) {
                    throw ex;
                }
                skippedCount++;
                itemRes.setResultStatus("SKIPPED_INELIGIBLE");
                itemRes.setMessage(sanitizeLogMessage(ex.getMessage()));
                response.getResults().add(itemRes);
                continue;
            } catch (Exception ex) {
                skippedCount++;
                itemRes.setResultStatus("SKIPPED_INELIGIBLE");
                itemRes.setMessage(sanitizeLogMessage(ex.getMessage()));
                response.getResults().add(itemRes);
                continue;
            }

            if (!prep.proceed()) {
                skippedCount++;
                itemRes.setResultStatus(prep.alreadyProcessed() ? "ALREADY_PROCESSED" : "SKIPPED_INELIGIBLE");
                itemRes.setMessage(prep.message());
                if (snap != null) {
                    itemRes.setRequestStatus(snap.getRequestStatus());
                    itemRes.setPayoutStatus(snap.getPayoutStatus());
                }
                response.getResults().add(itemRes);
                continue;
            }

            eligibleCount++;
            long amtVnd = prep.amountVnd() != null ? prep.amountVnd() : itemRes.getAmount();
            itemRes.setAmount(amtVnd);
            dispatchedTotal += amtVnd;

            try {
                PayOsPayoutTransaction tx = payOsPayoutService.initiateOrReconcilePayout(reqId, adminUserId, batchCode);
                approvalTxService.syncBookingAndSeatsOnTerminalRefund(reqId);
                RefundRequest updated = refundRequestRepository.findById(reqId).orElse(null);
                if (updated != null) {
                    itemRes.setRequestStatus(updated.getRequestStatus());
                    itemRes.setPayoutStatus(updated.getPayoutStatus());
                    itemRes.setWorkflowStatus(updated.getWorkflowStatus());
                    itemRes.setRefundMethod(updated.getRefundMethod());
                    itemRes.setFeeClassification(updated.getFeeAssessmentStatus());
                    itemRes.setEstimatedFeeVnd(updated.getFeeAmountVnd() != null ? updated.getFeeAmountVnd() : 0L);
                }
                if (updated != null && "PAYOUT_BLOCKED".equalsIgnoreCase(updated.getWorkflowStatus())) {
                    blockedCount++;
                    itemRes.setBlocked(true);
                    itemRes.setNetRecipientVnd(0L);
                    itemRes.setResultStatus("PAYOUT_BLOCKED");
                    itemRes.setMessage(updated.getPayoutBlockedReason() != null
                            ? updated.getPayoutBlockedReason()
                            : "Payout blocked by transfer fee policy.");
                } else if (tx != null) {
                    itemRes.setReferenceId(tx.getReferenceId());
                    itemRes.setPayosPayoutId(tx.getPayosPayoutId());
                    itemRes.setIdempotencyKey(tx.getIdempotencyKey());
                    itemRes.setNetRecipientVnd(tx.getAmount() != null ? tx.getAmount() : amtVnd);
                    String txStatus = tx.getStatus() != null ? tx.getStatus().toUpperCase(Locale.ROOT) : "PROCESSING";
                    if ("SUCCEEDED".equals(txStatus)) {
                        succeededCount++;
                        succeededTotal += tx.getAmount() != null ? tx.getAmount() : amtVnd;
                        itemRes.setResultStatus("SUCCEEDED");
                        itemRes.setMessage("Chuyển tiền thành công (" + tx.getReferenceId() + ").");
                    } else if ("FAILED".equals(txStatus)) {
                        failedCount++;
                        itemRes.setResultStatus("FAILED");
                        itemRes.setMessage(tx.getErrorMessage() != null ? tx.getErrorMessage() : "PayOS payout thất bại.");
                    } else {
                        boolean unknown = "UNKNOWN_OUTCOME".equalsIgnoreCase(tx.getTransactionState())
                                || "MANUAL_RECONCILE_REQUIRED".equalsIgnoreCase(tx.getTransactionState())
                                || (updated != null && "UNKNOWN".equalsIgnoreCase(updated.getWorkflowStatus()));
                        itemRes.setUnknownOutcome(unknown);
                        if (unknown) {
                            unknownCount++;
                            itemRes.setResultStatus("UNKNOWN_OUTCOME");
                            itemRes.setMessage(tx.getErrorMessage() != null ? tx.getErrorMessage() : "Chưa xác định kết quả — cần đối soát lại.");
                        } else {
                            processingCount++;
                            itemRes.setResultStatus("PROCESSING");
                            itemRes.setMessage("PayOS đang xử lý (" + tx.getReferenceId() + ").");
                        }
                    }
                } else {
                    failedCount++;
                    itemRes.setResultStatus("FAILED");
                    itemRes.setMessage("No PayOS payout transaction created.");
                }
            } catch (Exception ex) {
                failedCount++;
                itemRes.setResultStatus("FAILED");
                itemRes.setMessage(sanitizeLogMessage(ex.getMessage()));
                RefundRequest updated = refundRequestRepository.findById(reqId).orElse(null);
                if (updated != null) {
                    itemRes.setRequestStatus(updated.getRequestStatus());
                    itemRes.setPayoutStatus(updated.getPayoutStatus());
                    itemRes.setWorkflowStatus(updated.getWorkflowStatus());
                }
            }
            response.getResults().add(itemRes);
        }

        String overallStatus;
        if (eligibleCount == 0) {
            overallStatus = "NO_ELIGIBLE_ITEMS";
        } else if (succeededCount == response.getRequestedCount() && blockedCount == 0) {
            overallStatus = "ALL_SUCCEEDED";
        } else if (blockedCount == eligibleCount && skippedCount == 0 && succeededCount == 0 && failedCount == 0 && processingCount == 0 && unknownCount == 0) {
            overallStatus = "ALL_BLOCKED";
        } else if (failedCount == eligibleCount && skippedCount == 0 && processingCount == 0 && unknownCount == 0 && blockedCount == 0) {
            overallStatus = "ALL_FAILED";
        } else if (succeededCount == 0 && (processingCount + unknownCount) > 0 && failedCount == 0 && skippedCount == 0 && blockedCount == 0) {
            overallStatus = "IN_PROGRESS";
        } else {
            overallStatus = "PARTIAL_SUCCESS";
        }

        response.setOverallStatus(overallStatus);
        response.setEligibleCount(eligibleCount);
        response.setSucceededCount(succeededCount);
        response.setProcessingCount(processingCount);
        response.setUnknownOutcomeCount(unknownCount);
        response.setBlockedCount(blockedCount);
        response.setFailedCount(failedCount);
        response.setSkippedCount(skippedCount);
        response.setRequestedTotalAmount(requestedTotal);
        response.setDispatchedTotalAmount(dispatchedTotal);
        response.setSucceededTotalAmount(succeededTotal);

        RefundAuditLog batchAudit = new RefundAuditLog();
        batchAudit.setEntityType("PAYOUT_BATCH");
        batchAudit.setEntityId(0L);
        batchAudit.setEventType("BATCH_PAYOUT_" + overallStatus);
        batchAudit.setActorUserId(admin.getUserId());
        batchAudit.setActorRole(admin.getRole());
        Map<String, Object> batchDetails = new LinkedHashMap<>();
        batchDetails.put("batchCode", batchCode);
        batchDetails.put("requested", orderedIds.size());
        batchDetails.put("eligible", eligibleCount);
        batchDetails.put("succeeded", succeededCount);
        batchDetails.put("processing", processingCount);
        batchDetails.put("unknownOutcome", unknownCount);
        batchDetails.put("failed", failedCount);
        batchDetails.put("skipped", skippedCount);
        batchDetails.put("succeededTotalVnd", succeededTotal);
        batchAudit.setDetailsJson(toJson(batchDetails));
        auditLogRepository.save(batchAudit);

        return response;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public RefundRequestDetailDto retryPayoutOnly(Long refundRequestId, Long actorUserId) {
        User actor = requireBranchAuthorizedActor(refundRequestId, actorUserId);
        if (!"ADMIN".equalsIgnoreCase(actor.getRole())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_PAYOUT_ONLY",
                    "Only Admin can dispatch or retry cash payouts.");
        }
        RefundRequest req = refundRequestRepository.findById(refundRequestId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
        if (!Set.of("PAYOUT_APPROVED", "PAYOUT_PROCESSING", "PAYOUT_FAILED", "PAYOUT_BLOCKED", "PAYOUT_SUCCEEDED", "COMPLETED")
                .contains(req.getRequestStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "REFUND_NOT_APPROVED",
                    "Payout can only be dispatched or retried after Admin payout approval (current status: " + req.getRequestStatus() + ").");
        }
        payOsPayoutService.initiateOrReconcilePayout(refundRequestId, actor.getUserId(), null);
        approvalTxService.syncBookingAndSeatsOnTerminalRefund(refundRequestId);
        return getRefundDetailForStaffOrManager(refundRequestId, actorUserId);
    }

    @PreAuthorize("hasAnyRole('MANAGER', 'ADMIN')")
    public RefundRequestDetailDto retryVoucherOnly(Long refundRequestId, Long actorUserId) {
        User actor = requireBranchAuthorizedActor(refundRequestId, actorUserId);
        RefundRequest req = refundRequestRepository.findById(refundRequestId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
        if (!Set.of(
                "AWAITING_PAYOUT_APPROVAL", "PAYOUT_APPROVED", "PAYOUT_PROCESSING",
                "PAYOUT_SUCCEEDED", "PAYOUT_FAILED", "APPROVED", "PARTIALLY_APPROVED", "COMPLETED"
        ).contains(req.getRequestStatus())) {
            throw new BusinessException(HttpStatus.CONFLICT, "REFUND_NOT_APPROVED",
                    "Voucher recovery can only be retried for approved refund requests (current status: " + req.getRequestStatus() + ").");
        }
        Long firstApprovedItemId = refundRequestItemRepository.findByRefundRequest_IdOrderByIdAsc(refundRequestId).stream()
                .filter(i -> "APPROVED".equalsIgnoreCase(i.getItemStatus()))
                .map(RefundRequestItem::getId).findFirst().orElse(null);
        voucherRecoveryService.generateRecoveryVoucher(req.getId(), firstApprovedItemId, actor.getUserId());
        approvalTxService.syncBookingAndSeatsOnTerminalRefund(refundRequestId);
        return getRefundDetailForStaffOrManager(refundRequestId, actorUserId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Page<RefundRequestDetailDto> listAdminPayoutQueue(
            Long cinemaId, String reasonCategory, String verifier,
            BigDecimal minAmount, BigDecimal maxAmount,
            LocalDateTime fromDate, LocalDateTime toDate,
            String search, int page, int size, Long adminUserId) {
        requireAdminActor(adminUserId);
        return listRefundRequestsFiltered(
                cinemaId, "AWAITING_PAYOUT_APPROVAL", "AWAITING_APPROVAL",
                reasonCategory, verifier, minAmount, maxAmount, fromDate, toDate,
                search, page, size, adminUserId);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Page<PayoutTransactionDetailDto> listPayoutTransactions(
            String status, String batchCode, String search,
            LocalDateTime fromDate, LocalDateTime toDate,
            int page, int size, Long adminUserId) {
        requireAdminActor(adminUserId);
        String st = (status != null && !status.isBlank()) ? status.trim().toUpperCase(Locale.ROOT) : null;
        String bc = (batchCode != null && !batchCode.isBlank()) ? batchCode.trim() : null;
        String q = (search != null && !search.isBlank()) ? search.trim() : null;
        return payoutRepository.searchPayoutTransactions(
                        st, bc, q, fromDate, toDate,
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)))
                .map(this::mapToPayoutTxDetailDto);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public PayoutTransactionDetailDto getPayoutTransactionDetail(Long txId, Long adminUserId) {
        requireAdminActor(adminUserId);
        PayOsPayoutTransaction tx = payoutRepository.findById(txId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYOUT_NOT_FOUND", "Payout transaction not found."));
        return mapToPayoutTxDetailDto(tx);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public PayoutFeeAssessment assessSinglePayoutFeePreflight(Long refundRequestId, Long adminUserId) {
        requireAdminActor(adminUserId);
        RefundRequest req = refundRequestRepository.findById(refundRequestId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
        long amountVnd = req.getApprovedCashTotal() != null
                ? PurchaseSnapshotService.toVndLong(req.getApprovedCashTotal())
                : (req.getRequestedCashTotal() != null ? PurchaseSnapshotService.toVndLong(req.getRequestedCashTotal()) : 0L);
        return payOsPayoutService.assessFeePreflight(req, amountVnd);
    }

    @PreAuthorize("hasRole('ADMIN')")
    public PayoutTransactionDetailDto reconcilePayoutTransaction(Long txId, Long adminUserId) {
        requireAdminActor(adminUserId);
        PayOsPayoutTransaction tx = payOsPayoutService.reconcileTransaction(txId, adminUserId, "MANUAL");
        if (tx != null && tx.getRefundRequest() != null) {
            approvalTxService.syncBookingAndSeatsOnTerminalRefund(tx.getRefundRequest().getId());
        }
        return getPayoutTransactionDetail(tx.getId(), adminUserId);
    }

    public void checkPublicUploadRateLimit(String clientIp) {
        checkPublicUploadRateLimit(null, clientIp);
    }

    public void checkPublicUploadRateLimit(Long actorUserId, String clientIp) {
        if (refundRateLimitService != null) {
            refundRateLimitService.checkEvidenceUploadLimit(actorUserId, clientIp);
        } else {
            enforcePublicRateLimit("upload:" + (clientIp != null ? clientIp : "unknown"), 15, 600_000L);
        }
    }

    public RefundAppealResponseDto submitCustomerAppeal(String idOrCode,
                                                        RefundAppealSubmitRequestDto body,
                                                        Long loggedInUserId,
                                                        String clientIp) {
        String emailNorm = body != null && body.getPurchaseEmail() != null
                ? body.getPurchaseEmail().trim().toLowerCase(Locale.ROOT)
                : null;
        if (refundRateLimitService != null) {
            refundRateLimitService.checkAppealSubmitLimit(loggedInUserId, emailNorm, clientIp);
        } else {
            enforcePublicRateLimit("appeal:" + (clientIp != null ? clientIp : "unknown"), 5, 600_000L);
        }
        if (refundAppealService == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "APPEAL_SERVICE_UNAVAILABLE",
                    "Refund appeal service is not configured.");
        }
        Long refundId = resolveRefundRequestId(idOrCode);
        return refundAppealService.submitCustomerAppeal(refundId, body, loggedInUserId);
    }

    @Transactional(readOnly = true)
    public List<RefundAppealResponseDto> getAppealsForRefundRequest(String idOrCode,
                                                                    Long actorUserId,
                                                                    String purchaseEmail) {
        if (refundAppealService == null) {
            return List.of();
        }
        Long refundId = resolveRefundRequestId(idOrCode);
        return refundAppealService.getAppealsForRefundRequest(refundId, actorUserId, purchaseEmail);
    }

    private Long resolveRefundRequestId(String idOrCode) {
        if (idOrCode == null || idOrCode.trim().isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "REFUND_ID_REQUIRED", "Refund request ID or code is required.");
        }
        String clean = idOrCode.trim();
        if (clean.matches("^\\d+$")) {
            return Long.parseLong(clean);
        }
        return refundRequestRepository.findByRequestCodeIgnoreCase(clean)
                .map(RefundRequest::getId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND",
                        "Refund request not found: " + clean));
    }

    @PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
    @Transactional(readOnly = true)
    public Page<RefundRequestDetailDto> listRefundRequests(Long requestedCinemaId, String status, String payoutStatus, String search, int page, int size, Long actorUserId) {
        return listRefundRequestsFiltered(requestedCinemaId, status, payoutStatus, null, null, null, null, null, null, search, page, size, actorUserId);
    }

    @PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
    @Transactional(readOnly = true)
    public Page<RefundRequestDetailDto> listRefundRequestsFiltered(
            Long requestedCinemaId, String status, String payoutStatus,
            String reasonCategory, String verifier,
            BigDecimal minAmount, BigDecimal maxAmount,
            LocalDateTime fromDate, LocalDateTime toDate,
            String search, int page, int size, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User not found.");
        }
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User not found."));
        Long effectiveCinemaId = requestedCinemaId;
        if (!"ADMIN".equalsIgnoreCase(actor.getRole())) {
            if (actor.getCinemaId() == null) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "NO_BRANCH_ASSIGNED", "Manager has no assigned cinema branch.");
            }
            effectiveCinemaId = actor.getCinemaId();
        }

        List<String> statusList = List.of("__NONE__");
        boolean applyStatuses = false;
        if (status != null && !status.isBlank()) {
            List<String> parsed = Arrays.stream(status.split(","))
                    .map(s -> s.trim().toUpperCase(Locale.ROOT))
                    .filter(s -> !s.isBlank())
                    .toList();
            if (!parsed.isEmpty()) {
                statusList = parsed;
                applyStatuses = true;
            }
        }
        String ps = (payoutStatus != null && !payoutStatus.isBlank()) ? payoutStatus.trim().toUpperCase(Locale.ROOT) : null;
        String rc = (reasonCategory != null && !reasonCategory.isBlank()) ? reasonCategory.trim().toUpperCase(Locale.ROOT) : null;
        Long verifierId = null;
        String verifierSearch = null;
        if (verifier != null && !verifier.isBlank()) {
            String trimmedV = verifier.trim();
            if (trimmedV.matches("^\\d+$")) {
                verifierId = Long.parseLong(trimmedV);
            } else {
                verifierSearch = trimmedV;
            }
        }
        String q = (search != null && !search.isBlank()) ? search.trim() : null;

        return refundRequestRepository.searchRefundRequestsFiltered(
                        effectiveCinemaId, applyStatuses, statusList, ps, rc,
                        verifierId, verifierSearch, minAmount, maxAmount, fromDate, toDate, q,
                        PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)))
                .map(this::mapToDetailDto);
    }

    @PreAuthorize("hasAnyRole('STAFF', 'MANAGER', 'ADMIN')")
    @Transactional(readOnly = true)
    public RefundRequestDetailDto getRefundDetailForStaffOrManager(Long refundRequestId, Long actorUserId) {
        return runInTx(readOnlyTxTemplate, () -> {
            requireBranchAuthorizedActor(refundRequestId, actorUserId);
            RefundRequest req = refundRequestRepository.findById(refundRequestId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
            return mapToDetailDto(req);
        });
    }

    public static LocalDateTime parseDateTimeBound(String raw, boolean endOfDay) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        try {
            if (s.length() == 10) {
                java.time.LocalDate d = java.time.LocalDate.parse(s);
                return endOfDay ? d.atTime(java.time.LocalTime.MAX) : d.atStartOfDay();
            }
            return LocalDateTime.parse(s);
        } catch (Exception ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DATE_FILTER",
                    "Invalid date format '" + s + "'. Use yyyy-MM-dd or yyyy-MM-ddTHH:mm:ss.");
        }
    }

    private static final Set<String> BANK_EDITABLE_REQUEST_STATUSES = Set.of(
            "PENDING_REVIEW", "UNDER_VERIFICATION", "MORE_INFO_REQUIRED", "VERIFIED", "ESCALATED_TO_ADMIN"
    );

    public static boolean isBankAccountEditable(RefundRequest req) {
        if (req == null) return false;
        String rs = req.getRequestStatus() != null ? req.getRequestStatus().trim().toUpperCase(Locale.ROOT) : "";
        String ps = req.getPayoutStatus() != null ? req.getPayoutStatus().trim().toUpperCase(Locale.ROOT) : "";
        return BANK_EDITABLE_REQUEST_STATUSES.contains(rs) && "NOT_REQUIRED".equals(ps);
    }

    private record ResolvedBankPayload(
            String bankBin,
            String bankName,
            String encryptedAccount,
            String maskedAccount,
            String accountHash,
            String holderNormalized,
            String bankNote,
            Long savedBankAccountId
    ) {}

    private ResolvedBankPayload resolveBankPayload(
            Long savedBankAccountId,
            String toBin,
            String toAccountNumber,
            String confirmAccountNumber,
            String bankAccountName,
            String bankNote,
            Long actorUserId) {
        if (savedBankAccountId != null) {
            if (customerBankAccountService == null) {
                throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "BANK_SERVICE_UNAVAILABLE",
                        "Saved bank account service is unavailable.");
            }
            CustomerBankAccountService.ResolvedBankAccount resolved =
                    customerBankAccountService.resolveDecryptedForOwner(savedBankAccountId, actorUserId);
            String cleanNote = (bankNote != null && !bankNote.isBlank()) ? sanitizeBankNote(bankNote) : resolved.note();
            return new ResolvedBankPayload(
                    resolved.bankBin(),
                    resolved.bankName(),
                    resolved.encryptedAccountNumber(),
                    resolved.maskedAccountNumber(),
                    resolved.accountNumberHash(),
                    resolved.accountHolderName(),
                    cleanNote,
                    resolved.savedBankAccountId());
        }

        String bankName;
        String cleanAccount;
        try {
            bankName = bankEncryptionService.validateAndResolveBankName(toBin);
            cleanAccount = bankEncryptionService.validateAccountNumber(toAccountNumber);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_BANK_INFO", ex.getMessage());
        }

        if (confirmAccountNumber == null || confirmAccountNumber.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ACCOUNT_NUMBER_CONFIRM_REQUIRED",
                    "Please confirm your bank account number.");
        }
        String cleanConfirm = confirmAccountNumber.replaceAll("\\s+", "");
        if (!cleanAccount.equals(cleanConfirm)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ACCOUNT_NUMBER_CONFIRM_MISMATCH",
                    "Account number and confirmation account number do not match.");
        }

        if (bankAccountName == null || bankAccountName.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ACCOUNT_HOLDER_REQUIRED",
                    "Bank account holder name is required.");
        }
        String holderNormalized = bankEncryptionService.normalizeAccountHolderName(bankAccountName);
        if (holderNormalized == null || holderNormalized.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ACCOUNT_HOLDER_REQUIRED",
                    "Bank account holder name must contain valid alphanumeric characters.");
        }

        String encryptedAccount = bankEncryptionService.encryptAccountNumber(cleanAccount);
        String maskedAccount = bankEncryptionService.maskAccountNumber(cleanAccount);
        String accountHash = bankEncryptionService.hashAccountNumber(cleanAccount);
        String cleanNote = sanitizeBankNote(bankNote);
        return new ResolvedBankPayload(
                toBin.trim(),
                bankName,
                encryptedAccount,
                maskedAccount,
                accountHash,
                holderNormalized,
                cleanNote,
                null);
    }

    private static String sanitizeBankNote(String rawNote) {
        if (rawNote == null || rawNote.isBlank()) {
            return null;
        }
        String clean = rawNote.trim();
        if (clean.length() > 255) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "BANK_NOTE_TOO_LONG",
                    "Bank note must not exceed 255 characters.");
        }
        return clean;
    }

    private User requireStaffOrManagerActor(Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User not found.");
        }
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User not found."));
        String role = actor.getRole() != null ? actor.getRole().trim().toUpperCase(Locale.ROOT) : "";
        if (!Set.of("STAFF", "MANAGER", "ADMIN").contains(role)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Staff, Manager, or Admin role required.");
        }
        if (!"ADMIN".equals(role) && actor.getCinemaId() == null) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "NO_BRANCH_ASSIGNED", "Account has no assigned cinema branch.");
        }
        return actor;
    }

    private void enforceBookingBranchScope(User actor, Booking booking) {
        if (!"ADMIN".equalsIgnoreCase(actor.getRole())) {
            Long actorCinemaId = actor.getCinemaId();
            Long bookingCinemaId = booking.resolveCinemaId();
            if (actorCinemaId == null || !actorCinemaId.equals(bookingCinemaId)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "BRANCH_ACCESS_DENIED",
                        "Access denied: order belongs to another cinema branch.");
            }
        }
    }

    private Set<Long> findSharedBeneficiaryIdsAcrossDistinctBookings(Collection<RefundRequest> requests) {
        if (requests == null || requests.size() < 2) {
            return Set.of();
        }
        Map<String, List<RefundRequest>> byBeneficiary = new HashMap<>();
        for (RefundRequest rr : requests) {
            if (rr == null || rr.getId() == null) continue;
            String benKey = resolveBeneficiaryKey(rr);
            if (benKey != null) {
                byBeneficiary.computeIfAbsent(benKey, k -> new ArrayList<>()).add(rr);
            }
        }
        Set<Long> collidingIds = new HashSet<>();
        for (List<RefundRequest> group : byBeneficiary.values()) {
            if (group.size() < 2) continue;
            Set<Long> distinctBookingIds = new HashSet<>();
            for (RefundRequest rr : group) {
                distinctBookingIds.add(rr.getBooking() != null ? rr.getBooking().getBookingId() : -rr.getId());
            }
            if (distinctBookingIds.size() > 1) {
                for (RefundRequest rr : group) {
                    collidingIds.add(rr.getId());
                }
            }
        }
        return collidingIds;
    }

    private String resolveBeneficiaryKey(RefundRequest rr) {
        if (rr == null) return null;
        String bin = rr.getToBin() != null ? rr.getToBin().trim().toUpperCase(Locale.ROOT) : "";
        if (bin.isEmpty()) return null;
        String hash = rr.getToAccountNumberHash();
        if ((hash == null || hash.isBlank())
                && rr.getToAccountNumberEncrypted() != null
                && !rr.getToAccountNumberEncrypted().isBlank()) {
            try {
                String plain = bankEncryptionService.decryptAccountNumber(rr.getToAccountNumberEncrypted());
                hash = bankEncryptionService.hashAccountNumber(plain);
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        if (hash == null || hash.isBlank()) return null;
        return bin + ":" + hash.trim().toLowerCase(Locale.ROOT);
    }

    private static String toJson(Map<String, ?> map) {
        try {
            return JSON_MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private User requireAdminActor(Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User not found.");
        }
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User not found."));
        if (!"ADMIN".equalsIgnoreCase(actor.getRole())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "ADMIN_PAYOUT_ONLY", "Only Admin can perform payout operations.");
        }
        return actor;
    }

    private User requireBranchAuthorizedActor(Long refundRequestId, Long actorUserId) {
        if (actorUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User not found.");
        }
        User actor = userRepository.findById(actorUserId)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User not found."));
        RefundRequest req = refundRequestRepository.findById(refundRequestId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_NOT_FOUND", "Refund request not found."));
        if (!"ADMIN".equalsIgnoreCase(actor.getRole())) {
            Long userCinemaId = actor.getCinemaId();
            Long reqCinemaId = req.getCinema() != null ? req.getCinema().getCinemaId() : null;
            if (userCinemaId == null || !userCinemaId.equals(reqCinemaId)) {
                throw new BusinessException(HttpStatus.FORBIDDEN, "BRANCH_ACCESS_DENIED", "Access denied for this cinema branch.");
            }
        }
        return actor;
    }

    private Booking resolveAndVerifyPaidBooking(String orderCode, String purchaseEmail) {
        if (orderCode == null || orderCode.isBlank() || purchaseEmail == null || purchaseEmail.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "LOOKUP_INVALID", "Order/ticket code and purchase email are required.");
        }
        String cleanCode = orderCode.trim();
        String cleanEmail = purchaseEmail.trim();

        Optional<Booking> bookingOpt = bookingRepository.findByBookingCodeIgnoreCase(cleanCode);
        if (bookingOpt.isEmpty()) {
            bookingOpt = ticketRepository.findByTicketCode(cleanCode.toUpperCase(Locale.ROOT)).map(Ticket::getBooking);
        }
        if (bookingOpt.isEmpty()) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_EMAIL_MISMATCH", "Order not found or email mismatch.");
        }
        Booking booking = bookingOpt.get();
        String storedEmail = (booking.getUser() != null && booking.getUser().getEmail() != null && !booking.getUser().getEmail().isBlank())
                ? booking.getUser().getEmail()
                : booking.getGuestEmail();

        boolean matchesGuest = booking.getGuestEmail() != null && booking.getGuestEmail().equalsIgnoreCase(cleanEmail);
        boolean matchesUser = storedEmail != null && storedEmail.equalsIgnoreCase(cleanEmail);
        if (!matchesGuest && !matchesUser) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "ORDER_EMAIL_MISMATCH", "Order not found or email mismatch.");
        }
        String status = booking.getBookingStatus() != null ? booking.getBookingStatus().toUpperCase(Locale.ROOT) : "";
        if (!PayoutEligibilityService.PAYOUT_ELIGIBLE_BOOKING_STATUSES.contains(status)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "ORDER_NOT_PAID", "Only paid/confirmed orders are eligible for refund requests.");
        }
        return booking;
    }

    private RefundRequest verifyPublicRefundAccess(String requestCode, String purchaseEmail) {
        if (requestCode == null || requestCode.isBlank() || purchaseEmail == null || purchaseEmail.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "LOOKUP_INVALID", "Refund request code and purchase email are required.");
        }
        RefundRequest req = refundRequestRepository.findByRequestCodeIgnoreCase(requestCode.trim())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REFUND_EMAIL_MISMATCH", "Refund request not found or email mismatch."));
        if (!req.getPurchaseEmail().equalsIgnoreCase(purchaseEmail.trim())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "REFUND_EMAIL_MISMATCH", "Refund request not found or email mismatch.");
        }
        return req;
    }

    private void enforcePublicRateLimit(String key, int maxRequests, long windowMs) {
        long now = System.currentTimeMillis();
        Deque<Long> deque = publicRateBuckets.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > windowMs) {
                deque.pollFirst();
            }
            if (deque.size() >= maxRequests) {
                throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                        "Too many requests. Please wait a moment and try again.");
            }
            deque.addLast(now);
        }
    }

    private String buildFoodEligibilityNote(int remaining, int delivered, int total, int replaced, boolean hasIncident) {
        if (remaining <= 0) return replaced > 0 ? "Fulfilled / replaced or already requested" : "Already refunded or requested";
        if (delivered >= total) {
            return hasIncident ? "Delivered — backed by verified incident" : "Already delivered (requires verified incident)";
        }
        return "Undelivered qty: " + (total - delivered - replaced) + " — eligible for review";
    }

    private String generateRefundCode() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String code;
        do {
            code = "RFD-" + date + "-" + (1000 + random.nextInt(9000));
        } while (refundRequestRepository.findByRequestCodeIgnoreCase(code).isPresent());
        return code;
    }

    private String generateBatchCode() {
        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return "BATCH-" + date + "-" + (1000 + random.nextInt(9000));
    }

    private static List<Long> deduplicateIds(List<Long> raw) {
        if (raw == null || raw.isEmpty()) return List.of();
        LinkedHashSet<Long> set = new LinkedHashSet<>();
        for (Long id : raw) {
            if (id != null && id > 0) set.add(id);
        }
        return new ArrayList<>(set);
    }

    private static String sanitizeLogMessage(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown error";
        }
        String masked = raw.replaceAll("\\b\\d{6,19}\\b", "******");
        return masked.length() > 300 ? masked.substring(0, 300) : masked;
    }

    public RefundRequestDetailDto mapToDetailDto(RefundRequest req) {
        RefundRequestDetailDto dto = new RefundRequestDetailDto();
        dto.setId(req.getId());
        dto.setRequestCode(req.getRequestCode());
        dto.setBookingId(req.getBooking().getBookingId());
        dto.setBookingCode(req.getBooking().getBookingCode());
        dto.setOrderType(req.getBooking().getOrderType());
        dto.setCinemaId(req.getCinema().getCinemaId());
        dto.setCinemaName(req.getCinema().getCinemaName());
        dto.setCustomerName(req.getCustomerName());
        dto.setCustomerPhone(req.getCustomerPhone());
        dto.setPurchaseEmail(req.getPurchaseEmail());
        dto.setSubmittedOrderCode(req.getSubmittedOrderCode());
        dto.setReasonCategory(req.getReasonCategory());
        dto.setDescription(req.getDescription());
        dto.setEvidenceUrl(req.getEvidenceUrl());
        dto.setToBin(req.getToBin());
        dto.setToBankName(req.getToBankName());
        dto.setToAccountNumberMasked(req.getToAccountNumberMasked());
        dto.setAccountHolderName(req.getAccountHolderName());
        dto.setBankNote(req.getBankNote());
        dto.setSavedBankAccountId(req.getSavedBankAccountId());
        dto.setBankAccountEditable(isBankAccountEditable(req));
        dto.setRequestedCashTotal(req.getRequestedCashTotal());
        dto.setApprovedCashTotal(req.getApprovedCashTotal());
        dto.setApprovedVoucherTotal(req.getApprovedVoucherTotal());
        dto.setRequestStatus(req.getRequestStatus());
        dto.setPayoutStatus(req.getPayoutStatus());
        dto.setVoucherStatus(req.getVoucherStatus());
        dto.setWorkflowStatus(req.getWorkflowStatus());
        dto.setRefundMethod(req.getRefundMethod());
        dto.setOriginalPaymentId(req.getOriginalPaymentId());
        dto.setOriginalPaymentRef(req.getOriginalPaymentRef());
        dto.setRefundDeadlineAt(req.getRefundDeadlineAt());
        dto.setDeadlineBasis(req.getDeadlineBasis());
        dto.setDeadlineExceeded(req.isDeadlineExceeded());
        dto.setDeadlineExceptionBy(req.getDeadlineExceptionBy());
        dto.setDeadlineExceptionReason(req.getDeadlineExceptionReason());
        dto.setDeadlineExceptionAt(req.getDeadlineExceptionAt());
        dto.setFeeAssessmentStatus(req.getFeeAssessmentStatus());
        dto.setFeeAmountVnd(req.getFeeAmountVnd());
        dto.setPayoutBlockedReason(req.getPayoutBlockedReason());
        dto.setRecipientSnapshotHash(req.getRecipientSnapshotHash());
        dto.setTransferDescription(PayOsPayoutService.buildTransferDescription(req.getId()));
        if (payOsPayoutService != null) {
            dto.setLivePayoutEnabled(payOsPayoutService.isLivePayoutEnabled());
            dto.setPayoutMode(payOsPayoutService.getMode());
            long approvedVnd = req.getApprovedCashTotal() != null ? PurchaseSnapshotService.toVndLong(req.getApprovedCashTotal()) : 0L;
            if (approvedVnd > 0) {
                PayoutFeeAssessment fee = payOsPayoutService.assessFeePreflight(req, approvedVnd);
                if (dto.getFeeAssessmentStatus() == null || dto.getFeeAssessmentStatus().isBlank()) {
                    dto.setFeeAssessmentStatus(fee.classification().name());
                }
                dto.setNetRecipientVnd(fee.netRecipientVnd());
                dto.setTotalDebitVnd(fee.totalDebitVnd());
                dto.setGuaranteedFullReceipt(fee.guaranteedFullReceipt());
            }
        }
        dto.setLimitOverrideApplied(req.isLimitOverrideApplied());
        dto.setLimitOverrideBy(req.getLimitOverrideBy());
        dto.setLimitOverrideReason(req.getLimitOverrideReason());
        dto.setLimitOverrideAt(req.getLimitOverrideAt());
        dto.setDecisionNote(req.getDecisionNote());
        if (req.getReviewedBy() != null) {
            dto.setReviewedById(req.getReviewedBy().getUserId());
            dto.setReviewedByName(req.getReviewedBy().getFullName());
            dto.setReviewedByRole(req.getReviewedBy().getRole());
        }
        dto.setReviewedAt(req.getReviewedAt());
        if (req.getPayoutDecidedBy() != null) {
            dto.setPayoutDecidedById(req.getPayoutDecidedBy().getUserId());
            dto.setPayoutDecidedByName(req.getPayoutDecidedBy().getFullName());
        }
        dto.setPayoutDecidedAt(req.getPayoutDecidedAt());
        dto.setPayoutDecisionNote(req.getPayoutDecisionNote());
        dto.setCreatedAt(req.getCreatedAt());

        paymentRepository.findFirstByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(
                req.getBooking().getBookingId(), "PAID").ifPresent(pay -> {
            RefundRequestDetailDto.OriginalPaymentDto op = new RefundRequestDetailDto.OriginalPaymentDto();
            op.setPaymentId(pay.getPaymentId());
            op.setOrderCode(pay.getOrderCode());
            op.setAmount(pay.getAmount());
            op.setPaymentStatus(pay.getPaymentStatus());
            op.setPaymentMethod(pay.getPaymentMethod());
            op.setPaidAt(pay.getPaidAt());
            op.setTransactionRef(pay.getProviderTransactionId() != null ? pay.getProviderTransactionId() : pay.getPaymentLinkId());
            dto.setOriginalPayment(op);
        });

        PayoutEligibilityService.EvaluationResult eval = payoutEligibilityService.evaluate(req, true);
        dto.setPayoutEligible(eval.eligible());
        dto.setPayoutEligibilityErrors(eval.reasons());

        for (RefundRequestItem item : refundRequestItemRepository.findByRefundRequest_IdOrderByIdAsc(req.getId())) {
            RefundRequestDetailDto.RefundItemDto idto = new RefundRequestDetailDto.RefundItemDto();
            idto.setId(item.getId());
            idto.setItemKind(item.getItemKind());
            idto.setTicketId(item.getTicketId());
            idto.setBookingFoodItemId(item.getBookingFoodItemId());
            idto.setBookingFoodItemComponentId(item.getBookingFoodItemComponentId());
            idto.setItemLabel(item.getItemLabelSnapshot());
            idto.setPurchasedQuantity(item.getPurchasedQuantity());
            idto.setRequestedQuantity(item.getRequestedQuantity());
            idto.setApprovedQuantity(item.getApprovedQuantity());
            idto.setGrossUnitPrice(item.getGrossUnitPrice());
            idto.setDiscountAllocatedAmount(item.getDiscountAllocatedAmount());
            idto.setNetPaidAmount(item.getNetPaidAmount());
            idto.setApprovedCashAmount(item.getApprovedCashAmount());
            idto.setApprovedVoucherAmount(item.getApprovedVoucherAmount());
            idto.setItemStatus(item.getItemStatus());
            idto.setDecisionReason(item.getDecisionReason());
            dto.getItems().add(idto);
        }

        for (RefundCommunication c : communicationRepository.findByRefundRequest_IdOrderByCreatedAtAsc(req.getId())) {
            RefundRequestDetailDto.CommunicationDto cdto = new RefundRequestDetailDto.CommunicationDto();
            cdto.setId(c.getId());
            cdto.setSenderType(c.getSenderType());
            cdto.setSenderName(c.getSenderName());
            cdto.setMessage(c.getMessage());
            cdto.setAttachmentUrl(c.getAttachmentUrl());
            cdto.setCreatedAt(c.getCreatedAt());
            dto.getCommunications().add(cdto);
        }

        for (RefundApprovalHistory h : historyRepository.findByRefundRequest_IdOrderByCreatedAtAsc(req.getId())) {
            RefundRequestDetailDto.ApprovalHistoryDto hdto = new RefundRequestDetailDto.ApprovalHistoryDto();
            hdto.setActorName(h.getActorUser() != null ? h.getActorUser().getFullName() : null);
            hdto.setActorRole(h.getActorRole());
            hdto.setAction(h.getAction());
            hdto.setPreviousStatus(h.getPreviousStatus());
            hdto.setNewStatus(h.getNewStatus());
            hdto.setNote(h.getNote());
            hdto.setCreatedAt(h.getCreatedAt());
            dto.getHistory().add(hdto);
        }

        List<PayOsPayoutTransaction> txs = payoutRepository.findByRefundRequest_IdOrderByAttemptCountDesc(req.getId());
        for (PayOsPayoutTransaction tx : txs) {
            dto.getPayoutTransactions().add(mapToPayoutSummaryDto(tx));
        }
        PayOsPayoutTransaction latestTx = txs.isEmpty() ? null : txs.get(0);
        if (latestTx != null) {
            dto.setLatestPayout(dto.getPayoutTransactions().get(0));
        }
        dto.setPayoutDisplayStage(PayoutEligibilityService.resolveDisplayStage(req, latestTx));

        List<VoucherRecovery> vrs = voucherRecoveryRepository.findByRefundRequest_IdOrderByIdAsc(req.getId());
        if (!vrs.isEmpty()) {
            VoucherRecovery vr = vrs.get(vrs.size() - 1);
            RefundRequestDetailDto.VoucherSummaryDto vdto = new RefundRequestDetailDto.VoucherSummaryDto();
            vdto.setNewVoucherCode(vr.getNewVoucherCode());
            vdto.setRecoveryAmount(vr.getRecoveryAmount());
            vdto.setExpiresAt(vr.getExpiresAt());
            vdto.setStatus(vr.getStatus());
            vdto.setLastError(vr.getLastError());
            dto.setRecoveryVoucher(vdto);
        }

        Long bId = req.getBooking().getBookingId();
        Long sId = req.getBooking().getShowtime() != null ? req.getBooking().getShowtime().getShowtimeId() : null;
        Set<Long> seenInc = new HashSet<>();
        for (IncidentReport ir : incidentRepository.findByBooking_BookingIdAndStatus(bId, "VERIFIED")) {
            if (seenInc.add(ir.getId())) dto.getRelatedIncidents().add(incidentService.mapToDto(ir));
        }
        if (sId != null) {
            for (IncidentReport ir : incidentRepository.findByShowtime_ShowtimeIdAndStatus(sId, "VERIFIED")) {
                if (seenInc.add(ir.getId())) dto.getRelatedIncidents().add(incidentService.mapToDto(ir));
            }
        }
        return dto;
    }

    private RefundRequestDetailDto.PayoutSummaryDto mapToPayoutSummaryDto(PayOsPayoutTransaction tx) {
        RefundRequestDetailDto.PayoutSummaryDto pdto = new RefundRequestDetailDto.PayoutSummaryDto();
        pdto.setId(tx.getId());
        pdto.setReferenceId(tx.getReferenceId());
        pdto.setBatchCode(tx.getBatchCode());
        pdto.setIdempotencyKey(tx.getIdempotencyKey());
        pdto.setPayosPayoutId(tx.getPayosPayoutId());
        pdto.setPayosTransactionId(tx.getPayosTransactionId());
        pdto.setProviderReference(tx.getProviderReference());
        pdto.setPayoutMode(tx.getPayoutMode());
        pdto.setAmount(tx.getAmount());
        pdto.setStatus(tx.getStatus());
        pdto.setApprovalState(tx.getApprovalState());
        pdto.setTransactionState(tx.getTransactionState());
        pdto.setPayosResponseCode(tx.getPayosResponseCode());
        pdto.setPayosResponseDesc(tx.getPayosResponseDesc());
        pdto.setAttemptCount(tx.getAttemptCount());
        pdto.setInitiatedBy(tx.getInitiatedBy());
        pdto.setToBin(tx.getToBin());
        pdto.setToAccountNumberMasked(tx.getToAccountNumberMasked());
        pdto.setErrorMessage(tx.getErrorMessage());
        pdto.setInitiatedAt(tx.getInitiatedAt());
        pdto.setLastReconciledAt(tx.getLastReconciledAt());
        pdto.setSucceededAt(tx.getSucceededAt());
        pdto.setFailedAt(tx.getFailedAt());
        pdto.setUnknownOutcome("PROCESSING".equalsIgnoreCase(tx.getStatus())
                && "UNKNOWN_OUTCOME".equalsIgnoreCase(tx.getTransactionState()));
        return pdto;
    }

    private PayoutTransactionDetailDto mapToPayoutTxDetailDto(PayOsPayoutTransaction tx) {
        PayoutTransactionDetailDto dto = new PayoutTransactionDetailDto();
        dto.setId(tx.getId());
        RefundRequest rr = tx.getRefundRequest();
        if (rr != null) {
            dto.setRefundRequestId(rr.getId());
            dto.setRequestCode(rr.getRequestCode());
            dto.setBookingCode(rr.getBooking() != null ? rr.getBooking().getBookingCode() : rr.getSubmittedOrderCode());
            dto.setCinemaName(rr.getCinema() != null ? rr.getCinema().getCinemaName() : null);
            dto.setCustomerName(rr.getCustomerName());
            dto.setPurchaseEmail(rr.getPurchaseEmail());
        }
        dto.setReferenceId(tx.getReferenceId());
        dto.setBatchCode(tx.getBatchCode());
        dto.setIdempotencyKey(tx.getIdempotencyKey());
        dto.setPayosPayoutId(tx.getPayosPayoutId());
        dto.setPayosTransactionId(tx.getPayosTransactionId());
        dto.setProviderReference(tx.getProviderReference());
        dto.setPayoutMode(tx.getPayoutMode());
        dto.setAmount(tx.getAmount());
        dto.setDescription(tx.getDescription());
        dto.setToBin(tx.getToBin());
        dto.setToAccountNumberMasked(tx.getToAccountNumberMasked());
        dto.setToAccountHolderName(tx.getToAccountHolderName());
        dto.setCategory(tx.getCategory());
        dto.setStatus(tx.getStatus());
        dto.setApprovalState(tx.getApprovalState());
        dto.setTransactionState(tx.getTransactionState());
        dto.setPayosResponseCode(tx.getPayosResponseCode());
        dto.setPayosResponseDesc(tx.getPayosResponseDesc());
        dto.setErrorMessage(tx.getErrorMessage());
        dto.setRawResponseSanitized(tx.getRawResponseSanitized());
        dto.setAttemptCount(tx.getAttemptCount());
        dto.setInitiatedBy(tx.getInitiatedBy());
        if (tx.getInitiatedBy() != null) {
            userRepository.findById(tx.getInitiatedBy()).ifPresent(u -> dto.setInitiatedByName(u.getFullName()));
        }
        dto.setInitiatedAt(tx.getInitiatedAt());
        dto.setLastReconciledAt(tx.getLastReconciledAt());
        dto.setSucceededAt(tx.getSucceededAt());
        dto.setFailedAt(tx.getFailedAt());
        boolean unknown = "PROCESSING".equalsIgnoreCase(tx.getStatus())
                && "UNKNOWN_OUTCOME".equalsIgnoreCase(tx.getTransactionState());
        dto.setUnknownOutcome(unknown);
        dto.setDisplayStage(PayoutEligibilityService.resolveDisplayStage(rr, tx));
        return dto;
    }
}
