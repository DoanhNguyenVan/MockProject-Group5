package com.boxcinema.customer.service.impl;

import com.boxcinema.common.dto.*;
import com.boxcinema.customer.dto.request.*;
import com.boxcinema.customer.dto.response.*;
import com.boxcinema.common.entity.*;
import com.boxcinema.common.exception.BusinessException;
import com.boxcinema.common.repository.*;
import com.boxcinema.common.service.BookingQrService;
import com.boxcinema.common.service.impl.PurchaseSnapshotService;
import com.boxcinema.common.util.SecurityContextUtils;
import com.boxcinema.customer.service.FoodService;
import com.boxcinema.customer.service.PayOsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Supplier;

@Service
public class PayOsServiceImpl implements PayOsService {

    private static final Logger log = LoggerFactory.getLogger(PayOsServiceImpl.class);
    private static final String DEFAULT_CLIENT_ID = "dummy-client-id";
    private static final String DEFAULT_API_KEY = "dummy-api-key";
    private static final String DEFAULT_CHECKSUM_KEY = "BOX_CINEMA_DEFAULT_SECURE_PAYOS_KEY_2026_@!#";

    private final BookingRepository bookingRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentWebhookEventRepository webhookEventRepository;
    private final TicketRepository ticketRepository;
    private final BookingQrRepository bookingQrRepository;
    private final BookingQrService bookingQrService;
    private final FoodFulfillmentRepository foodFulfillmentRepository;
    private final EmailOutboxRepository emailOutboxRepository;
    private final FoodService foodService;
    private final PurchaseSnapshotService purchaseSnapshotService;
    private final JdbcTemplate jdbcTemplate;
    private final UserRepository userRepository;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();
    private final TransactionTemplate txTemplate;
    private final TransactionTemplate readOnlyTxTemplate;
    private volatile CollectionTransportExecutor customTransportExecutor;

    @PersistenceContext
    private EntityManager entityManager;

    public interface CollectionTransportExecutor {
        ResponseEntity<String> post(String url, HttpHeaders headers, Map<String, Object> body);
        ResponseEntity<JsonNode> get(String url, HttpHeaders headers);
        default ResponseEntity<String> cancel(String url, HttpHeaders headers, Map<String, Object> body) {
            return ResponseEntity.ok("{}");
        }
    }

    public void setCustomTransportExecutorForTesting(CollectionTransportExecutor customTransportExecutor) {
        this.customTransportExecutor = customTransportExecutor;
    }

    @Value("${payos.collection.mode:MOCK}")
    private String collectionMode = "MOCK";

    @Value("${payos.collection.client-id:${payos.client-id:dummy-client-id}}")
    private String clientId;

    @Value("${payos.collection.api-key:${payos.api-key:dummy-api-key}}")
    private String apiKey;

    @Value("${payos.collection.checksum-key:${payos.checksum-key:BOX_CINEMA_DEFAULT_SECURE_PAYOS_KEY_2026_@!#}}")
    private String checksumKey;

    @Value("${payos.collection.base-url:${payos.base-url:https://api-merchant.payos.vn}}")
    private String baseUrl = "https://api-merchant.payos.vn";

    @Value("${payos.collection.return-url:${payos.return-url:http://localhost:8080/booking/buy-tickets}}")
    private String returnUrl = "http://localhost:8080/booking/buy-tickets";

    @Value("${payos.collection.cancel-url:${payos.cancel-url:http://localhost:8080/booking/buy-tickets}}")
    private String cancelUrl = "http://localhost:8080/booking/buy-tickets";

    @Value("${payos.payout.client-id:}")
    private String payoutClientId;

    @Value("${payos.payout.api-key:}")
    private String payoutApiKey;

    @Value("${payos.payout.checksum-key:}")
    private String payoutChecksumKey;

    public PayOsServiceImpl(
            BookingRepository bookingRepository,
            PaymentRepository paymentRepository,
            PaymentWebhookEventRepository webhookEventRepository,
            TicketRepository ticketRepository,
            BookingQrRepository bookingQrRepository,
            BookingQrService bookingQrService,
            FoodFulfillmentRepository foodFulfillmentRepository,
            EmailOutboxRepository emailOutboxRepository,
            FoodService foodService,
            PurchaseSnapshotService purchaseSnapshotService,
            JdbcTemplate jdbcTemplate
    ) {
        this(bookingRepository, paymentRepository, webhookEventRepository, ticketRepository,
                bookingQrRepository, bookingQrService, foodFulfillmentRepository, emailOutboxRepository,
                foodService, purchaseSnapshotService, jdbcTemplate, null, null);
    }

    public PayOsServiceImpl(
            BookingRepository bookingRepository,
            PaymentRepository paymentRepository,
            PaymentWebhookEventRepository webhookEventRepository,
            TicketRepository ticketRepository,
            BookingQrRepository bookingQrRepository,
            BookingQrService bookingQrService,
            FoodFulfillmentRepository foodFulfillmentRepository,
            EmailOutboxRepository emailOutboxRepository,
            FoodService foodService,
            PurchaseSnapshotService purchaseSnapshotService,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager
    ) {
        this(bookingRepository, paymentRepository, webhookEventRepository, ticketRepository,
                bookingQrRepository, bookingQrService, foodFulfillmentRepository, emailOutboxRepository,
                foodService, purchaseSnapshotService, jdbcTemplate, transactionManager, null);
    }

    @Autowired
    public PayOsServiceImpl(
            BookingRepository bookingRepository,
            PaymentRepository paymentRepository,
            PaymentWebhookEventRepository webhookEventRepository,
            TicketRepository ticketRepository,
            BookingQrRepository bookingQrRepository,
            BookingQrService bookingQrService,
            FoodFulfillmentRepository foodFulfillmentRepository,
            EmailOutboxRepository emailOutboxRepository,
            FoodService foodService,
            PurchaseSnapshotService purchaseSnapshotService,
            JdbcTemplate jdbcTemplate,
            @Autowired(required = false) PlatformTransactionManager transactionManager,
            @Autowired(required = false) UserRepository userRepository
    ) {
        this.bookingRepository = bookingRepository;
        this.paymentRepository = paymentRepository;
        this.webhookEventRepository = webhookEventRepository;
        this.ticketRepository = ticketRepository;
        this.bookingQrRepository = bookingQrRepository;
        this.bookingQrService = bookingQrService;
        this.foodFulfillmentRepository = foodFulfillmentRepository;
        this.emailOutboxRepository = emailOutboxRepository;
        this.foodService = foodService;
        this.purchaseSnapshotService = purchaseSnapshotService;
        this.jdbcTemplate = jdbcTemplate;
        this.userRepository = userRepository;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(15000);
        this.restTemplate = new RestTemplate(factory);
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

    @PostConstruct
    public void validateCollectionConfiguration() {
        String normalizedMode = collectionMode != null ? collectionMode.trim() : "MOCK";
        if (!"LIVE".equalsIgnoreCase(normalizedMode)) {
            return;
        }
        String cId = clientId != null ? clientId.trim() : "";
        String aKey = apiKey != null ? apiKey.trim() : "";
        String csKey = checksumKey != null ? checksumKey.trim() : "";
        if (cId.isBlank() || aKey.isBlank() || csKey.isBlank()) {
            throw new IllegalStateException("PayOS Collection credentials (client-id, api-key, checksum-key) are required in LIVE mode.");
        }
        if (DEFAULT_CLIENT_ID.equalsIgnoreCase(cId)
                || DEFAULT_API_KEY.equalsIgnoreCase(aKey)
                || DEFAULT_CHECKSUM_KEY.equals(csKey)) {
            throw new IllegalStateException("Default placeholder PayOS Collection credentials cannot be used in LIVE mode.");
        }
        String pClient = payoutClientId != null ? payoutClientId.trim() : "";
        String pApi = payoutApiKey != null ? payoutApiKey.trim() : "";
        String pChecksum = payoutChecksumKey != null ? payoutChecksumKey.trim() : "";
        if ((!pClient.isBlank() && cId.equalsIgnoreCase(pClient))
                || (!pApi.isBlank() && aKey.equalsIgnoreCase(pApi))
                || (!pChecksum.isBlank() && csKey.equalsIgnoreCase(pChecksum))) {
            throw new IllegalStateException("PayOS Collection credentials must be strictly distinct from PayOS Payout credentials.");
        }
        String rUrl = trimSafe(returnUrl);
        String cUrl = trimSafe(cancelUrl);
        if (rUrl.isBlank() || cUrl.isBlank()
                || !rUrl.toLowerCase(Locale.ROOT).startsWith("https://")
                || !cUrl.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IllegalStateException("PayOS Collection return-url and cancel-url must be non-blank HTTPS URLs in LIVE mode.");
        }
    }

    private <T> T runInTx(TransactionTemplate tpl, Supplier<T> action) {
        return tpl != null ? tpl.execute(status -> action.get()) : action.get();
    }

    private record PreparedPayment(
            Long bookingId,
            Long paymentId,
            long orderCode,
            long amountInt,
            String description,
            LocalDateTime expiredAt,
            PayOsCreatePaymentResponseDto reusedResponse,
            Long waitInFlightPaymentId,
            List<Long> supersededOrderCodes
    ) {}

    private record PaymentBookingIds(Long bookingId, Long paymentId) {}

    private record GatewayDetails(
            String paymentLinkId,
            String qrCodePayload,
            String checkoutUrl,
            String bin,
            String accountNumber,
            String accountName
    ) {}

    private record PollState(
            PaymentStatusDto dto,
            boolean needsPoll,
            Long bookingId,
            Long paymentId,
            long orderCode,
            long amount,
            String paymentLinkId
    ) {}

    @Override
    public PayOsCreatePaymentResponseDto createPayment(Long bookingId, Long currentUserId) {
        // M1 Phase 1 (TX1): Validate, reserve PENDING Payment, and commit DB locks BEFORE network I/O
        PreparedPayment prep = runInTx(txTemplate, () -> preparePaymentTx1(bookingId, currentUserId));
        if (prep.reusedResponse() != null) {
            return prep.reusedResponse();
        }
        if (prep.waitInFlightPaymentId() != null) {
            return waitForInFlightPayment(prep.waitInFlightPaymentId());
        }

        if (prep.supersededOrderCodes() != null && !prep.supersededOrderCodes().isEmpty()
                && ("LIVE".equalsIgnoreCase(collectionMode) || customTransportExecutor != null)) {
            for (Long oldOrderCode : prep.supersededOrderCodes()) {
                bestEffortCancelPayOsLink(oldOrderCode);
            }
        }

        // M1 Phase 2 (Outside DB TX): Call PayOS or generate local VietQR fallback
        GatewayDetails details;
        if ("MOCK".equalsIgnoreCase(collectionMode) && customTransportExecutor == null) {
            details = generateLocalVietQrFallback(prep.amountInt(), prep.orderCode(), prep.description());
        } else {
            try {
                details = callPayOsApi(prep.amountInt(), prep.orderCode(), prep.description(), prep.expiredAt());
            } catch (ResourceAccessException ex) {
                log.warn("PayOS createPayment timed out for orderCode={}. Checking PayOS GET recovery: {}", prep.orderCode(), ex.getMessage());
                GatewayDetails recovered = tryFetchCreatedPaymentLink(prep.orderCode());
                if (recovered != null) {
                    details = recovered;
                } else if ("LIVE".equalsIgnoreCase(collectionMode)) {
                    markPaymentFailedTx2(prep.bookingId(), prep.paymentId(), prep.orderCode());
                    throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_GATEWAY_ERROR",
                            "Unable to initialize PayOS payment gateway. Please try again.");
                } else {
                    log.warn("PayOS gateway unavailable in {} mode; falling back to local VietQR for orderCode={}",
                            collectionMode, prep.orderCode());
                    details = generateLocalVietQrFallback(prep.amountInt(), prep.orderCode(), prep.description());
                }
            } catch (Exception ex) {
                if ("LIVE".equalsIgnoreCase(collectionMode)) {
                    log.error("PayOS createPayment failed in LIVE mode for orderCode={}: {}", prep.orderCode(), ex.getMessage());
                    markPaymentFailedTx2(prep.bookingId(), prep.paymentId(), prep.orderCode());
                    throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_GATEWAY_ERROR",
                            "Unable to initialize PayOS payment gateway. Please try again.");
                }
                log.warn("PayOS API call failed in {} mode. Generating local VietQR payload: {}", collectionMode, ex.getMessage());
                details = generateLocalVietQrFallback(prep.amountInt(), prep.orderCode(), prep.description());
            }
        }
        final GatewayDetails finalDetails = details;

        // M1 Phase 3 (TX2): Persist gateway link/QR only if still PENDING & matching orderCode
        return runInTx(txTemplate, () -> finalizeCreatedPaymentTx2(prep, finalDetails));
    }

    private void refreshIfManaged(Object entity) {
        if (entity != null && entityManager != null
                && TransactionSynchronizationManager.isActualTransactionActive()
                && entityManager.contains(entity)) {
            try {
                entityManager.refresh(entity);
            } catch (jakarta.persistence.EntityNotFoundException ex) {
                entityManager.detach(entity);
            }
        }
    }

    private void clearReadOnlyPersistenceContext() {
        if (entityManager != null && TransactionSynchronizationManager.isActualTransactionActive()) {
            entityManager.clear();
        }
    }

    private PayOsCreatePaymentResponseDto waitForInFlightPayment(Long paymentId) {
        for (int i = 0; i < 30; i++) {
            PayOsCreatePaymentResponseDto ready = runInTx(readOnlyTxTemplate, () -> {
                clearReadOnlyPersistenceContext();
                Payment p = paymentRepository.findById(paymentId).orElse(null);
                if (p == null) {
                    return null;
                }
                if ("FAILED".equalsIgnoreCase(p.getPaymentStatus())) {
                    throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_GATEWAY_ERROR",
                            "Unable to initialize PayOS payment gateway. Please try again.");
                }
                if ((p.getQrCodePayload() != null && !p.getQrCodePayload().isBlank())
                        || !"PENDING".equalsIgnoreCase(p.getPaymentStatus())) {
                    return mapToCreateResponse(p, p.getBooking());
                }
                return null;
            });
            if (ready != null) {
                return ready;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_GATEWAY_ERROR",
                "Payment initialization is still in progress. Please try again.");
    }

    private PreparedPayment preparePaymentTx1(Long bookingId, Long currentUserId) {
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));

        enforceBookingOwnership(booking, currentUserId);

        String currentStatus = booking.getBookingStatus() != null ? booking.getBookingStatus().toUpperCase(Locale.ROOT) : "";
        if (!"DRAFT".equals(currentStatus) && !"PENDING_PAYMENT".equals(currentStatus)) {
            if (Set.of("CONFIRMED", "PAID", "PAID_SEAT_CONFLICT", "PAYMENT_REVIEW").contains(currentStatus)) {
                throw new IllegalStateException("This booking order has already been paid or is under review.");
            }
            throw new IllegalStateException("This booking order cannot accept payment (status: " + booking.getBookingStatus() + ").");
        }

        booking.recalculateTotals();
        long amountInt = booking.getTotalAmount() != null
                ? booking.getTotalAmount().setScale(0, RoundingMode.HALF_UP).longValue()
                : 0L;
        if (amountInt <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than 0 VND.");
        }

        // Lock latest payment if present in deterministic order: Booking -> Payment
        Optional<Payment> existingOpt = paymentRepository.findTopByBooking_BookingIdOrderByPaymentIdDesc(bookingId)
                .flatMap(p -> paymentRepository.findByIdForUpdate(p.getPaymentId()));
        if (existingOpt.isPresent()) {
            Payment existing = existingOpt.get();
            long existingAmt = existing.getAmount() != null
                    ? existing.getAmount().setScale(0, RoundingMode.HALF_UP).longValue() : -1L;
            if ("PENDING".equalsIgnoreCase(existing.getPaymentStatus())
                    && existing.getExpiredAt() != null
                    && existing.getExpiredAt().isAfter(LocalDateTime.now())
                    && existingAmt == amountInt) {
                if (existing.getQrCodePayload() != null && !existing.getQrCodePayload().isBlank()) {
                    return new PreparedPayment(bookingId, existing.getPaymentId(), existing.getOrderCode(), amountInt,
                            existing.getDescription(), existing.getExpiredAt(), mapToCreateResponse(existing, booking), null, List.of());
                }
                if (existing.getCreatedAt() != null
                        && existing.getCreatedAt().isAfter(LocalDateTime.now().minusSeconds(20))) {
                    return new PreparedPayment(bookingId, existing.getPaymentId(), existing.getOrderCode(), amountInt,
                            existing.getDescription(), existing.getExpiredAt(), null, existing.getPaymentId(), List.of());
                }
            }
        }

        List<Payment> pendingPayments = new ArrayList<>(
                paymentRepository.findByBooking_BookingIdAndPaymentStatusOrderByPaymentIdDesc(bookingId, "PENDING"));
        pendingPayments.sort(Comparator.comparing(Payment::getPaymentId));
        List<Long> supersededOrderCodes = new ArrayList<>();
        for (Payment pending : pendingPayments) {
            Payment lockedPending = paymentRepository.findByIdForUpdate(pending.getPaymentId()).orElse(pending);
            if ("PENDING".equalsIgnoreCase(lockedPending.getPaymentStatus())) {
                lockedPending.setPaymentStatus("SUPERSEDED");
                paymentRepository.save(lockedPending);
                if (lockedPending.getOrderCode() != null) {
                    supersededOrderCodes.add(lockedPending.getOrderCode());
                }
            }
        }

        booking.setBookingStatus("PENDING_PAYMENT");
        booking.setPaymentDeadline(LocalDateTime.now().plusMinutes(10));
        bookingRepository.save(booking);

        long orderCode = generateUniqueOrderCode();
        String description = "BOX" + (orderCode % 100000L);
        if (description.length() > 9) {
            description = description.substring(0, 9);
        }

        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setOrderCode(orderCode);
        payment.setAmount(BigDecimal.valueOf(amountInt));
        payment.setCurrency("VND");
        payment.setPaymentProvider("PAYOS");
        payment.setPaymentMethod("PAYOS_QR");
        payment.setDescription(description);
        payment.setIdempotencyKey("IDEMP-" + orderCode + "-" + System.nanoTime());
        payment.setPaymentStatus("PENDING");
        payment.setPaymentLinkId(null);
        payment.setQrCodePayload(null);
        payment.setCheckoutUrl(null);
        payment.setCreatedAt(LocalDateTime.now());
        payment.setExpiredAt(LocalDateTime.now().plusMinutes(10));
        payment = paymentRepository.save(payment);

        return new PreparedPayment(bookingId, payment.getPaymentId(), orderCode, amountInt, description, payment.getExpiredAt(), null, null, supersededOrderCodes);
    }

    private long generateUniqueOrderCode() {
        for (int attempt = 0; attempt < 15; attempt++) {
            long candidate;
            if (attempt == 0) {
                candidate = (System.currentTimeMillis() % 1_000_000_000L) * 1000L + random.nextInt(1000);
            } else {
                candidate = (System.currentTimeMillis() % 100_000_000L) * 10_000L + random.nextInt(10_000);
            }
            if (candidate <= 0) {
                candidate = Math.abs(random.nextLong() % 1_000_000_000_000L) + 1L;
            }
            if (paymentRepository.findByOrderCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        return Math.abs(System.nanoTime() % 9_000_000_000_000L) + 1L;
    }

    private void enforceBookingOwnership(Booking booking, Long currentUserId) {
        if (booking == null) {
            return;
        }
        if (booking.getUser() == null) {
            if (isAuthorizedOperatorForBooking(booking, currentUserId)) {
                return;
            }
            if (com.boxcinema.common.util.SecurityContextUtils.hasGuestBookingAccess(booking)) {
                return;
            }
            throw new BusinessException(HttpStatus.FORBIDDEN, "BOOKING_ACCESS_DENIED",
                    "You do not have permission to access this guest booking payment.");
        }
        if (currentUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                    "Please sign in to access this booking payment.");
        }
        if (Objects.equals(booking.getUser().getUserId(), currentUserId)) {
            return;
        }
        if (isAuthorizedOperatorForBooking(booking, currentUserId)) {
            return;
        }
        throw new BusinessException(HttpStatus.FORBIDDEN, "BOOKING_ACCESS_DENIED",
                "You do not have permission to access this booking payment.");
    }

    private boolean isAuthorizedOperatorForBooking(Booking booking, Long currentUserId) {
        if (currentUserId == null || userRepository == null) {
            return false;
        }
        User actor = userRepository.findById(currentUserId).orElse(null);
        if (actor == null || actor.getRole() == null) {
            return false;
        }
        String role = actor.getRole().trim().toUpperCase(Locale.ROOT);
        if ("ADMIN".equals(role)) {
            return true;
        }
        if ("MANAGER".equals(role) || "STAFF".equals(role)) {
            Long actorCinemaId = actor.getCinemaId();
            Long bookingCinemaId = booking.resolveCinemaId();
            return actorCinemaId != null && bookingCinemaId != null && actorCinemaId.equals(bookingCinemaId);
        }
        return false;
    }

    private void markPaymentFailedTx2(Long bookingId, Long paymentId, long orderCode) {
        runInTx(txTemplate, () -> {
            if (bookingId != null) {
                bookingRepository.findByIdForUpdate(bookingId);
            }
            Payment payment = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
            refreshIfManaged(payment);
            if (payment != null && "PENDING".equalsIgnoreCase(payment.getPaymentStatus())
                    && Objects.equals(payment.getOrderCode(), orderCode)) {
                payment.setPaymentStatus("FAILED");
                paymentRepository.save(payment);
            }
            return null;
        });
    }

    private void bestEffortCancelPayOsLink(long orderCode) {
        try {
            if (customTransportExecutor == null && (isBlank(clientId) || isBlank(apiKey))) {
                return;
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("x-client-id", clientId);
            headers.set("x-api-key", apiKey);
            Map<String, Object> body = Map.of("cancellationReason", "Superseded by newer payment");
            String url = trimTrailingSlash(baseUrl) + "/v2/payment-requests/" + orderCode + "/cancel";
            if (customTransportExecutor != null) {
                customTransportExecutor.cancel(url, headers, body);
            } else {
                restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            }
        } catch (Exception ex) {
            log.debug("Best-effort PayOS cancel skipped for orderCode={}: {}", orderCode, ex.getMessage());
        }
    }

    private GatewayDetails tryFetchCreatedPaymentLink(long orderCode) {
        try {
            if (customTransportExecutor == null && (isBlank(clientId) || isBlank(apiKey))) {
                return null;
            }
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-client-id", clientId);
            headers.set("x-api-key", apiKey);
            String url = trimTrailingSlash(baseUrl) + "/v2/payment-requests/" + orderCode;
            ResponseEntity<JsonNode> resp = customTransportExecutor != null
                    ? customTransportExecutor.get(url, headers)
                    : restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
            if (resp != null && resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                JsonNode root = resp.getBody();
                if ("00".equals(root.path("code").asText())) {
                    JsonNode data = root.path("data");
                    String qrCode = data.path("qrCode").asText(null);
                    if (qrCode != null && !qrCode.isBlank()) {
                        String linkId = data.path("paymentLinkId").asText(null);
                        if (linkId == null || linkId.isBlank()) {
                            linkId = data.path("id").asText(null);
                        }
                        return new GatewayDetails(
                                linkId,
                                qrCode,
                                data.path("checkoutUrl").asText(null),
                                data.path("bin").asText(null),
                                data.path("accountNumber").asText(null),
                                data.path("accountName").asText(null)
                        );
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("PayOS GET recovery failed for orderCode={}: {}", orderCode, ex.getMessage());
        }
        return null;
    }

    private PayOsCreatePaymentResponseDto finalizeCreatedPaymentTx2(PreparedPayment prep, GatewayDetails details) {
        if (prep.bookingId() != null) {
            bookingRepository.findByIdForUpdate(prep.bookingId());
        }
        Payment payment = paymentRepository.findByIdForUpdate(prep.paymentId())
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + prep.paymentId()));
        refreshIfManaged(payment);
        if ("PENDING".equalsIgnoreCase(payment.getPaymentStatus())
                && Objects.equals(payment.getOrderCode(), prep.orderCode())) {
            payment.setPaymentLinkId(details.paymentLinkId());
            payment.setQrCodePayload(details.qrCodePayload());
            payment.setCheckoutUrl(details.checkoutUrl());
            payment.setBin(details.bin());
            payment.setAccountNumber(details.accountNumber());
            payment.setAccountName(details.accountName());
            payment = paymentRepository.save(payment);
        }
        return mapToCreateResponse(payment, payment.getBooking());
    }

    private GatewayDetails callPayOsApi(long amount, long orderCode, String description, LocalDateTime expiredAt) throws Exception {
        String effectiveReturnUrl = trimSafe(returnUrl);
        String effectiveCancelUrl = trimSafe(cancelUrl);
        String dataToSign = "amount=" + amount +
                "&cancelUrl=" + effectiveCancelUrl +
                "&description=" + description +
                "&orderCode=" + orderCode +
                "&returnUrl=" + effectiveReturnUrl;

        String signature = com.boxcinema.common.util.CryptoUtils.hmacSha256(dataToSign, checksumKey);

        long expiredAtEpoch = expiredAt != null
                ? expiredAt.atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
                : java.time.Instant.now().plusSeconds(600).getEpochSecond();

        Map<String, Object> reqBody = new LinkedHashMap<>();
        reqBody.put("orderCode", orderCode);
        reqBody.put("amount", amount);
        reqBody.put("description", description);
        reqBody.put("returnUrl", effectiveReturnUrl);
        reqBody.put("cancelUrl", effectiveCancelUrl);
        reqBody.put("expiredAt", expiredAtEpoch);
        reqBody.put("signature", signature);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-client-id", clientId);
        headers.set("x-api-key", apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(reqBody, headers);
        String url = trimTrailingSlash(baseUrl) + "/v2/payment-requests";

        ResponseEntity<String> response = customTransportExecutor != null
                ? customTransportExecutor.post(url, headers, reqBody)
                : restTemplate.postForEntity(url, entity, String.class);
        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            JsonNode root = objectMapper.readTree(response.getBody());
            if ("00".equals(root.path("code").asText())) {
                JsonNode data = root.path("data");
                return new GatewayDetails(
                        data.path("paymentLinkId").asText(null),
                        data.path("qrCode").asText(null),
                        data.path("checkoutUrl").asText(null),
                        data.path("bin").asText(null),
                        data.path("accountNumber").asText(null),
                        data.path("accountName").asText(null)
                );
            }
        }
        throw new RuntimeException("PayOS returned non-success response: " + response.getBody());
    }

    private GatewayDetails generateLocalVietQrFallback(long amount, long orderCode, String description) {
        String paymentLinkId = "payos_link_" + orderCode;
        // Standard VietQR EMVCo format payload for local direct display
        String vietQr = "00020101021238540010A0000007270124000697042201121098765432100208QRIBFTTA520460115303704" +
                "540" + String.valueOf(amount).length() + amount +
                "5802VN62" + String.format("%02d", description.length() + 4) + "08" + String.format("%02d", description.length()) + description +
                "6304" + com.boxcinema.common.util.CryptoUtils.sha256(description).substring(0, 4).toUpperCase();

        return new GatewayDetails(
                paymentLinkId,
                vietQr,
                "https://pay.payos.vn/web/" + paymentLinkId,
                "970422",
                "0000000000",
                "BOX CINEMA MOCK"
        );
    }

    @Override
    public PaymentStatusDto getPaymentStatus(Long paymentId) {
        return getPaymentStatus(paymentId, SecurityContextUtils.resolveCurrentUserId());
    }

    @Override
    public PaymentStatusDto getPaymentStatus(Long paymentId, Long currentUserId) {
        PollState pollState = runInTx(readOnlyTxTemplate, () -> loadPollStateTx(paymentId, currentUserId));
        if (pollState.needsPoll()) {
            boolean synced = trySyncPayOsStatus(pollState.bookingId(), pollState.paymentId(),
                    pollState.orderCode(), pollState.amount(), pollState.paymentLinkId());
            if (synced) {
                return runInTx(readOnlyTxTemplate, () -> loadPollStateTx(paymentId, currentUserId)).dto();
            }
        }
        return pollState.dto();
    }

    private PollState loadPollStateTx(Long paymentId, Long currentUserId) {
        clearReadOnlyPersistenceContext();
        Payment p = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found with id: " + paymentId));

        if (p.getBooking() != null) {
            enforceBookingOwnership(p.getBooking(), currentUserId);
        }

        PaymentStatusDto dto = new PaymentStatusDto();
        dto.setPaymentId(p.getPaymentId());
        Long bookingId = null;
        if (p.getBooking() != null) {
            bookingId = p.getBooking().getBookingId();
            dto.setBookingId(bookingId);
            dto.setBookingCode(p.getBooking().getBookingCode());
            dto.setBookingStatus(p.getBooking().getBookingStatus());
        }
        dto.setOrderCode(p.getOrderCode());
        dto.setAmount(p.getAmount());
        dto.setStatus(p.getPaymentStatus());
        dto.setPaid("PAID".equalsIgnoreCase(p.getPaymentStatus()));
        dto.setExpired("EXPIRED".equalsIgnoreCase(p.getPaymentStatus()) ||
                (p.getExpiredAt() != null && p.getExpiredAt().isBefore(LocalDateTime.now()) && !"PAID".equalsIgnoreCase(p.getPaymentStatus())));
        dto.setQrCode(p.getQrCodePayload());
        dto.setPaymentLinkId(p.getPaymentLinkId());
        dto.setPaidAt(p.getPaidAt());

        boolean needsPoll = "PENDING".equalsIgnoreCase(p.getPaymentStatus()) && p.getOrderCode() != null;
        long amt = p.getAmount() != null ? p.getAmount().setScale(0, RoundingMode.HALF_UP).longValue() : 0L;
        return new PollState(dto, needsPoll, bookingId, p.getPaymentId(),
                p.getOrderCode() != null ? p.getOrderCode() : 0L, amt, p.getPaymentLinkId());
    }

    private boolean trySyncPayOsStatus(Long bookingId, Long paymentId, long orderCode, long expectedAmt, String paymentLinkId) {
        try {
            if ("MOCK".equalsIgnoreCase(collectionMode) && customTransportExecutor == null) {
                return false;
            }
            if (customTransportExecutor == null && (isBlank(clientId) || isBlank(apiKey))) {
                return false;
            }
            HttpHeaders headers = new HttpHeaders();
            headers.set("x-client-id", clientId);
            headers.set("x-api-key", apiKey);
            HttpEntity<?> entity = new HttpEntity<>(headers);

            String url = trimTrailingSlash(baseUrl) + "/v2/payment-requests/" + orderCode;
            ResponseEntity<JsonNode> resp = customTransportExecutor != null
                    ? customTransportExecutor.get(url, headers)
                    : restTemplate.exchange(url, HttpMethod.GET, entity, JsonNode.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                JsonNode body = resp.getBody();
                if (!"00".equals(body.path("code").asText(""))) {
                    return false;
                }
                JsonNode data = body.path("data");
                String payosStatus = data.path("status").asText("");
                if ("PAID".equalsIgnoreCase(payosStatus)) {
                    log.info("Proactive polling detected PayOS status PAID for orderCode {}", orderCode);
                    PayOsWebhookPayloadDto payload = new PayOsWebhookPayloadDto();
                    payload.setCode("00");
                    payload.setDesc("success");

                    PayOsWebhookPayloadDto.PayOsWebhookDataDto d = new PayOsWebhookPayloadDto.PayOsWebhookDataDto();
                    d.setOrderCode(orderCode);
                    Long extractedAmt = null;
                    if (data.hasNonNull("amountPaid") && data.path("amountPaid").asLong(0L) > 0) {
                        extractedAmt = data.path("amountPaid").asLong();
                    } else if (data.hasNonNull("amount")) {
                        extractedAmt = data.path("amount").asLong();
                    }
                    d.setAmount(extractedAmt);
                    d.setPaymentLinkId(data.path("id").asText(paymentLinkId));
                    d.setCurrency(data.path("currency").asText("VND"));
                    d.setCode("00");
                    d.setDesc("success");

                    JsonNode txns = data.path("transactions");
                    if (txns.isArray() && !txns.isEmpty()) {
                        JsonNode firstTxn = txns.get(0);
                        String txnRef = firstTxn.path("reference").asText(null);
                        d.setReference((txnRef != null && !txnRef.isBlank()) ? txnRef.trim() : null);
                        d.setTransactionDateTime(firstTxn.path("transactionDateTime").asText(null));
                        d.setAccountNumber(firstTxn.path("accountNumber").asText(null));
                    } else {
                        // Leave null so processWebhook computes a deterministic fallback (LINK:<id> or NO_REF:<sha256>)
                        d.setReference(null);
                    }
                    payload.setData(d);

                    processWebhook(payload);
                    return true;
                } else if ("CANCELLED".equalsIgnoreCase(payosStatus) || "EXPIRED".equalsIgnoreCase(payosStatus)) {
                    String targetStatus = "CANCELLED".equalsIgnoreCase(payosStatus) ? "CANCELLED" : "EXPIRED";
                    return Boolean.TRUE.equals(runInTx(txTemplate, () -> {
                        Booking booking = bookingId != null
                                ? bookingRepository.findByIdForUpdate(bookingId).orElse(null)
                                : null;
                        Payment p = paymentRepository.findByIdForUpdate(paymentId).orElse(null);
                        if (p != null && "PENDING".equalsIgnoreCase(p.getPaymentStatus())) {
                            p.setPaymentStatus(targetStatus);
                            paymentRepository.save(p);
                            if ("CANCELLED".equals(targetStatus) && booking != null
                                    && "PENDING_PAYMENT".equalsIgnoreCase(booking.getBookingStatus())) {
                                cancelPendingBookingOnGatewayCancel(booking, paymentId);
                            }
                            return true;
                        }
                        return false;
                    }));
                }
            }
        } catch (Exception ex) {
            log.debug("Proactive PayOS polling check skipped or failed: {}", ex.getMessage());
        }
        return false;
    }

    private final Map<Long, Long> lastExpiredReconcileCheckMs = new java.util.concurrent.ConcurrentHashMap<>();

    private record ReconcileCandidate(PollState pollState, boolean expired) {}

    private void cancelPendingBookingOnGatewayCancel(Booking booking, Long cancelledPaymentId) {
        boolean hasOtherActiveOrPaid = paymentRepository.findByBooking_BookingIdOrderByPaymentIdDesc(booking.getBookingId())
                .stream()
                .anyMatch(other -> !Objects.equals(other.getPaymentId(), cancelledPaymentId)
                        && ("PAID".equalsIgnoreCase(other.getPaymentStatus())
                        || "PENDING".equalsIgnoreCase(other.getPaymentStatus())));
        if (hasOtherActiveOrPaid) {
            return;
        }
        if (jdbcTemplate != null) {
            if (booking.getUser() != null && booking.getUser().getUserId() != null) {
                jdbcTemplate.update(
                        "UPDATE showtime_seats ss "
                                + "JOIN booking_seats bs ON ss.showtime_seat_id = bs.showtime_seat_id "
                                + "SET ss.seat_status = 'AVAILABLE', ss.hold_token_hash = NULL, "
                                + "    ss.held_by_user_id = NULL, ss.hold_session_id = NULL, ss.hold_expires_at = NULL "
                                + "WHERE bs.booking_id = ? AND ss.seat_status = 'HELD' "
                                + "  AND (ss.held_by_user_id IS NULL OR ss.held_by_user_id = ?)",
                        booking.getBookingId(), booking.getUser().getUserId());
            } else {
                jdbcTemplate.update(
                        "UPDATE showtime_seats ss "
                                + "JOIN booking_seats bs ON ss.showtime_seat_id = bs.showtime_seat_id "
                                + "SET ss.seat_status = 'AVAILABLE', ss.hold_token_hash = NULL, "
                                + "    ss.held_by_user_id = NULL, ss.hold_session_id = NULL, ss.hold_expires_at = NULL "
                                + "WHERE bs.booking_id = ? AND ss.seat_status = 'HELD' AND ss.held_by_user_id IS NULL",
                        booking.getBookingId());
            }
            jdbcTemplate.update(
                    "UPDATE booking_seats SET status = 'CANCELLED', active_seat_key = NULL WHERE booking_id = ?",
                    booking.getBookingId());
        }
        if (booking.getBookingSeats() != null) {
            for (BookingSeat bs : booking.getBookingSeats()) {
                bs.setStatus("CANCELLED");
            }
        }
        for (BookingFoodItem bfi : sortedFoodItems(booking)) {
            if ("RELEASED".equalsIgnoreCase(bfi.getDeliveryStatus())) {
                continue;
            }
            if ("FOOD".equalsIgnoreCase(bfi.getItemType()) && bfi.getFoodItem() != null) {
                foodService.releaseFoodStock(bfi.getFoodItem().getId(), bfi.getQuantity());
                bfi.setDeliveryStatus("RELEASED");
            } else if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getCombo() != null) {
                foodService.releaseComboStock(bfi.getCombo().getId(), bfi.getQuantity());
                bfi.setDeliveryStatus("RELEASED");
            }
        }
        booking.setBookingStatus("CANCELLED");
        booking.setCancelledAt(LocalDateTime.now());
        bookingRepository.save(booking);
        if (jdbcTemplate != null) {
            try {
                jdbcTemplate.update(
                        "UPDATE order_voucher_redemptions SET status = 'CANCELLED', updated_at = NOW() WHERE booking_id = ? AND status = 'APPLIED'",
                        booking.getBookingId());
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Scheduled Collection reconciler (LIVE mode only so background scheduler never interferes with MOCK tests).
     * Covers the case where the PayOS webhook was never delivered and the customer closed the browser: polls PayOS
     * GET /v2/payment-requests/{orderCode} for PENDING payments and recently EXPIRED payments (last 24h) and feeds
     * the existing idempotent webhook path.
     */
    @org.springframework.scheduling.annotation.Scheduled(
            fixedDelayString = "${payos.collection.reconcile-poll-interval-ms:15000}",
            initialDelayString = "${payos.collection.reconcile-initial-delay-ms:30000}")
    public void scheduledReconcileCollectionPayments() {
        if (!"LIVE".equalsIgnoreCase(collectionMode == null ? "" : collectionMode.trim())) {
            return;
        }
        try {
            reconcilePendingCollectionPayments();
        } catch (Exception ex) {
            log.warn("Scheduled Collection reconciliation skipped: {}", ex.getClass().getSimpleName());
        }
    }

    /** Idempotent; safe to call repeatedly. Returns number of payments whose state was updated. */
    public int reconcilePendingCollectionPayments() {
        if (jdbcTemplate == null || txTemplate == null || readOnlyTxTemplate == null) {
            return 0;
        }
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT payment_id FROM payments WHERE order_code IS NOT NULL "
                        + "AND (qr_code_payload IS NOT NULL OR created_at <= DATE_SUB(NOW(), INTERVAL 20 SECOND)) "
                        + "AND (payment_status = 'PENDING' "
                        + "  OR (payment_status = 'EXPIRED' AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR))) "
                        + "ORDER BY payment_id ASC LIMIT 50", Long.class);
        long nowMs = System.currentTimeMillis();
        if (lastExpiredReconcileCheckMs.size() > 1000) {
            lastExpiredReconcileCheckMs.entrySet().removeIf(e -> nowMs - e.getValue() >= 3_600_000L);
        }
        int updated = 0;
        for (Long pid : ids) {
            try {
                ReconcileCandidate candidate = runInTx(readOnlyTxTemplate, () -> {
                    Payment p = paymentRepository.findById(pid).orElse(null);
                    if (p == null || p.getOrderCode() == null) {
                        return null;
                    }
                    String status = p.getPaymentStatus();
                    boolean isPending = "PENDING".equalsIgnoreCase(status);
                    boolean isExpired = "EXPIRED".equalsIgnoreCase(status);
                    if (!isPending && !isExpired) {
                        return null;
                    }
                    long amt = p.getAmount() != null ? p.getAmount().setScale(0, RoundingMode.HALF_UP).longValue() : 0L;
                    PollState ps = new PollState(null, true,
                            p.getBooking() != null ? p.getBooking().getBookingId() : null,
                            p.getPaymentId(), p.getOrderCode(), amt, p.getPaymentLinkId());
                    return new ReconcileCandidate(ps, isExpired);
                });
                if (candidate == null || !candidate.pollState().needsPoll()) {
                    continue;
                }
                Long last = lastExpiredReconcileCheckMs.get(pid);
                if (candidate.expired() && last != null && nowMs - last < 300_000L) {
                    continue;
                }
                if (candidate.expired()) {
                    lastExpiredReconcileCheckMs.put(pid, nowMs);
                }
                PollState ps = candidate.pollState();
                if (trySyncPayOsStatus(ps.bookingId(), ps.paymentId(), ps.orderCode(), ps.amount(), ps.paymentLinkId())) {
                    updated++;
                }
            } catch (Exception ex) {
                log.debug("Collection reconcile skipped for paymentId={}: {}", pid, ex.getClass().getSimpleName());
            }
        }
        return updated;
    }

    @Override
    public boolean verifyWebhookSignature(PayOsWebhookPayloadDto payload) {
        if (payload == null || payload.isDataParseError() || payload.getData() == null) {
            return false;
        }
        if (payload.getRawData() != null && !payload.getRawData().isObject()) {
            return false;
        }
        if (payload.getSignature() == null || payload.getSignature().isBlank()) {
            return false;
        }
        String effectiveKey = trimSafe(checksumKey);
        if (effectiveKey.isBlank()
                || ("LIVE".equalsIgnoreCase(trimSafe(collectionMode)) && DEFAULT_CHECKSUM_KEY.equals(effectiveKey))) {
            return false;
        }
        String computed = computeWebhookSignature(payload);
        return MessageDigest.isEqual(
                computed.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8),
                payload.getSignature().trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private String computeWebhookSignature(PayOsWebhookPayloadDto payload) {
        String dataStr = buildCanonicalWebhookDataString(payload);
        return com.boxcinema.common.util.CryptoUtils.hmacSha256(dataStr, trimSafe(checksumKey));
    }

    private String buildCanonicalWebhookDataString(PayOsWebhookPayloadDto payload) {
        Map<String, String> sortedMap = new TreeMap<>();
        if (payload.getRawData() != null && payload.getRawData().isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = payload.getRawData().fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> entry = it.next();
                JsonNode val = entry.getValue();
                if (val == null || val.isNull()) {
                    sortedMap.put(entry.getKey(), "");
                } else if (val.isObject() || val.isArray()) {
                    try {
                        sortedMap.put(entry.getKey(), objectMapper.writeValueAsString(canonicalizeJsonNode(val)));
                    } catch (Exception ex) {
                        sortedMap.put(entry.getKey(), val.toString());
                    }
                } else {
                    String text = val.asText();
                    sortedMap.put(entry.getKey(), ("null".equals(text) || "undefined".equals(text)) ? "" : text);
                }
            }
        } else if (payload.getData() != null) {
            PayOsWebhookPayloadDto.PayOsWebhookDataDto d = payload.getData();
            Map<String, Object> rawMap = objectMapper.convertValue(d, new TypeReference<Map<String, Object>>() {});
            if (d.getAdditionalProperties() != null) {
                rawMap.putAll(d.getAdditionalProperties());
            }
            rawMap.remove("additionalProperties");
            for (Map.Entry<String, Object> entry : rawMap.entrySet()) {
                Object val = entry.getValue();
                if (val == null) {
                    sortedMap.put(entry.getKey(), "");
                } else if (val instanceof Map || val instanceof Collection) {
                    try {
                        sortedMap.put(entry.getKey(), objectMapper.writeValueAsString(canonicalizePlainObject(val)));
                    } catch (Exception ex) {
                        sortedMap.put(entry.getKey(), String.valueOf(val));
                    }
                } else {
                    String text = String.valueOf(val);
                    sortedMap.put(entry.getKey(), ("null".equals(text) || "undefined".equals(text)) ? "" : text);
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : sortedMap.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    private Object canonicalizeJsonNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            Map<String, Object> sorted = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> e = fields.next();
                sorted.put(e.getKey(), canonicalizeJsonNode(e.getValue()));
            }
            return sorted;
        }
        if (node.isArray()) {
            List<Object> items = new ArrayList<>();
            for (JsonNode item : node) {
                items.add(canonicalizeJsonNode(item));
            }
            return items;
        }
        return objectMapper.convertValue(node, Object.class);
    }

    private Object canonicalizePlainObject(Object val) {
        if (val instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sorted.put(String.valueOf(e.getKey()), canonicalizePlainObject(e.getValue()));
            }
            return sorted;
        }
        if (val instanceof Collection<?> col) {
            List<Object> items = new ArrayList<>(col.size());
            for (Object item : col) {
                items.add(canonicalizePlainObject(item));
            }
            return items;
        }
        return val;
    }

    @Override
    public void processWebhook(PayOsWebhookPayloadDto payload) {
        if (payload == null || payload.isDataParseError() || payload.getData() == null) {
            throw new IllegalArgumentException("Invalid webhook payload.");
        }
        PayOsWebhookPayloadDto.PayOsWebhookDataDto data = payload.getData();
        if (data.getOrderCode() == null || data.getOrderCode() <= 0) {
            throw new IllegalArgumentException("Missing or invalid webhook orderCode.");
        }
        String topCode = payload.getCode();
        String dataCode = data.getCode();
        if (isBlank(topCode) && isBlank(dataCode)) {
            throw new IllegalArgumentException("Missing webhook status code.");
        }

        Long orderCode = data.getOrderCode();
        String rawRef = data.getReference();
        String candidateRef;
        if (!isBlank(rawRef)) {
            candidateRef = rawRef.trim();
        } else if (!isBlank(data.getPaymentLinkId())) {
            candidateRef = "LINK:" + data.getPaymentLinkId().trim();
        } else {
            String canonicalPrefix = com.boxcinema.common.util.CryptoUtils.sha256(buildCanonicalWebhookDataString(payload))
                    .substring(0, 16);
            candidateRef = "NO_REF:" + canonicalPrefix;
        }
        final String reference = candidateRef.length() > 150 ? candidateRef.substring(0, 150) : candidateRef;
        Long amount = data.getAmount();
        String fingerprint = com.boxcinema.common.util.CryptoUtils.sha256(orderCode + "_" + reference + "_" + amount);
        String fpShort = fingerprint.substring(0, 12);

        // H2: Resolve (bookingId, paymentId) in readOnly TX BEFORE entering write TX so Hibernate L1 cache and
        // MySQL REPEATABLE READ snapshot never precede findByIdForUpdate(...) locks.
        PaymentBookingIds ids = runInTx(readOnlyTxTemplate, () -> {
            clearReadOnlyPersistenceContext();
            return paymentRepository.findByOrderCode(orderCode)
                    .map(p -> new PaymentBookingIds(
                            p.getBooking() != null ? p.getBooking().getBookingId() : null,
                            p.getPaymentId()))
                    .orElse(null);
        });

        if (ids == null || ids.bookingId() == null) {
            String unknownStatus = (orderCode == 123L) ? "CONFIRM_WEBHOOK_TEST" : "UNKNOWN_ORDER_CODE";
            String unknownFp = fingerprint + "_" + unknownStatus + "_" + safeCodeToken(topCode) + "_" + safeCodeToken(dataCode);
            log.warn("Collection webhook outcome={} orderCode={} fp={} ref={}",
                    unknownStatus, orderCode, fpShort, sanitizeLogToken(reference));
            runInTx(txTemplate, () -> {
                saveWebhookEventIdempotently(null, orderCode, reference, unknownFp, unknownStatus);
                return null;
            });
            return;
        }

        runInTx(txTemplate, () -> {
            processWebhookTx(ids.bookingId(), ids.paymentId(), payload, reference, fingerprint);
            return null;
        });
    }

    private void processWebhookTx(Long bookingId, Long paymentId, PayOsWebhookPayloadDto payload,
                                  String reference, String fingerprint) {
        PayOsWebhookPayloadDto.PayOsWebhookDataDto data = payload.getData();
        Long orderCode = data.getOrderCode();
        Long amount = data.getAmount();
        String fpShort = fingerprint.substring(0, 12);

        // Lock in canonical order BEFORE any non-locking reads in this write TX: Booking -> Payment
        Booking booking = bookingRepository.findByIdForUpdate(bookingId)
                .orElseThrow(() -> new IllegalArgumentException("Booking not found: " + bookingId));
        Payment payment = paymentRepository.findByIdForUpdate(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        String topCode = payload.getCode();
        String dataCode = data.getCode();
        boolean nonSuccessCode = (topCode != null && !topCode.isBlank() && !"00".equals(topCode.trim()))
                || (dataCode != null && !dataCode.isBlank() && !"00".equals(dataCode.trim()));
        String payStatus = payment.getPaymentStatus() != null ? payment.getPaymentStatus().toUpperCase(Locale.ROOT) : "";
        String bookStatus = booking.getBookingStatus() != null ? booking.getBookingStatus().toUpperCase(Locale.ROOT) : "";
        boolean alreadyFinalized = Set.of("PAID", "REVIEW_REQUIRED").contains(payStatus)
                || Set.of("CONFIRMED", "PAID_SEAT_CONFLICT", "PAYMENT_REVIEW", "REFUNDED", "PARTIALLY_REFUNDED", "REFUND_PENDING")
                        .contains(bookStatus);

        // H1: Non-success / cancelled webhook must NEVER mark PAID/CONFIRMED and must NEVER downgrade already PAID/CONFIRMED
        if (nonSuccessCode) {
            if (alreadyFinalized) {
                log.info("Collection webhook outcome=IGNORED_LATE_NON00 orderCode={} bookingCode={} fp={} topCode={} dataCode={}",
                        orderCode, sanitizeLogToken(booking.getBookingCode()), fpShort, sanitizeLogToken(topCode), sanitizeLogToken(dataCode));
                return;
            }
            saveWebhookEventIdempotently(payment.getPaymentId(), orderCode, reference,
                    fingerprint + "_NON00_" + safeCodeToken(topCode) + "_" + safeCodeToken(dataCode), "IGNORED_NON_SUCCESS");
            log.info("Collection webhook outcome=IGNORED_NON_SUCCESS orderCode={} bookingCode={} fp={}",
                    orderCode, sanitizeLogToken(booking.getBookingCode()), fpShort);
            return;
        }

        // H2: Re-check idempotency & status AFTER acquiring pessimistic locks (prevents TOCTOU race)
        if (webhookEventRepository.existsByEventFingerprint(fingerprint) || alreadyFinalized) {
            log.info("Collection webhook outcome=DUPLICATE_OR_FINALIZED orderCode={} bookingCode={} fp={}",
                    orderCode, booking.getBookingCode(), fpShort);
            return;
        }

        // M2a + HIGH-02: Verification of amount, currency, paymentLinkId (LIVE), and superseded status
        long expectedPaymentAmount = payment.getAmount() != null
                ? payment.getAmount().setScale(0, RoundingMode.HALF_UP).longValue() : -1L;
        long expectedBookingAmount = booking.getTotalAmount() != null
                ? booking.getTotalAmount().setScale(0, RoundingMode.HALF_UP).longValue() : -1L;
        boolean isSuperseded = "SUPERSEDED".equalsIgnoreCase(payStatus)
                || "CANCELLED".equalsIgnoreCase(payStatus);
        boolean amountMismatch = amount == null || amount <= 0 || amount != expectedPaymentAmount || amount != expectedBookingAmount;
        boolean currencyMismatch = !isBlank(data.getCurrency()) && !"VND".equalsIgnoreCase(data.getCurrency().trim());
        boolean paymentLinkMismatch = "LIVE".equalsIgnoreCase(trimSafe(collectionMode))
                && !isBlank(payment.getPaymentLinkId())
                && !isBlank(data.getPaymentLinkId())
                && !payment.getPaymentLinkId().trim().equals(data.getPaymentLinkId().trim());
        if (isSuperseded || amountMismatch || currencyMismatch || paymentLinkMismatch) {
            String eventStatus = isSuperseded ? "SUPERSEDED_PAYMENT_PAID"
                    : (currencyMismatch ? "CURRENCY_MISMATCH"
                    : (paymentLinkMismatch ? "PAYMENT_LINK_MISMATCH" : "AMOUNT_MISMATCH"));
            log.error("Collection webhook outcome={} orderCode={} fp={} expectedPay={} expectedBook={} received={} status={}",
                    eventStatus, orderCode, fpShort, expectedPaymentAmount, expectedBookingAmount, amount, payment.getPaymentStatus());
            payment.setPaymentStatus("REVIEW_REQUIRED");
            payment.setProviderTransactionId(reference);
            booking.setBookingStatus("PAYMENT_REVIEW");
            paymentRepository.save(payment);
            bookingRepository.save(booking);
            saveWebhookEventIdempotently(payment.getPaymentId(), orderCode, reference, fingerprint, eventStatus);
            return;
        }

        // C3: Detect late payment on EXPIRED/CANCELLED booking and check for seat takeover conflicts
        boolean expiredOrCancelled = "EXPIRED".equalsIgnoreCase(booking.getBookingStatus())
                || "CANCELLED".equalsIgnoreCase(booking.getBookingStatus())
                || (booking.getExpiresAt() != null && booking.getExpiresAt().isBefore(LocalDateTime.now()))
                || (booking.getPaymentDeadline() != null && booking.getPaymentDeadline().isBefore(LocalDateTime.now()));
        boolean hasSeatConflict = detectSeatConflictForLatePayment(booking, expiredOrCancelled);

        // 1. Mark Payment PAID
        payment.setPaymentStatus("PAID");
        payment.setPaidAt(LocalDateTime.now());
        payment.setProviderTransactionId(reference);
        paymentRepository.save(payment);

        if (hasSeatConflict) {
            // C3 Case B: Preserve other customer's seats, release reserved food stock once (M2c), create CONFLICT_UNISSUED tickets (no BookingQr), lock snapshot for instant refund
            booking.setBookingStatus("PAID_SEAT_CONFLICT");
            if (booking.getBookingSeats() != null) {
                for (BookingSeat bs : booking.getBookingSeats()) {
                    bs.setStatus("CONFLICT");
                    bs.setActiveSeatKey(null);
                }
            }
            for (BookingFoodItem bfi : sortedFoodItems(booking)) {
                if ("RELEASED".equalsIgnoreCase(bfi.getDeliveryStatus())) {
                    continue;
                }
                if ("FOOD".equalsIgnoreCase(bfi.getItemType()) && bfi.getFoodItem() != null) {
                    foodService.releaseFoodStock(bfi.getFoodItem().getId(), bfi.getQuantity());
                    bfi.setDeliveryStatus("RELEASED");
                } else if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getCombo() != null) {
                    foodService.releaseComboStock(bfi.getCombo().getId(), bfi.getQuantity());
                    bfi.setDeliveryStatus("RELEASED");
                }
            }
            bookingRepository.save(booking);

            List<Ticket> conflictTickets = createTicketsIfAbsent(booking, "CONFLICT_UNISSUED");
            purchaseSnapshotService.allocateOrderSnapshot(booking, payment.getAmount(), conflictTickets);
            if (!conflictTickets.isEmpty()) {
                ticketRepository.saveAll(conflictTickets);
            }
            bookingRepository.save(booking);

            String safeBookingCodeHtml = HtmlUtils.htmlEscape(booking.getBookingCode() != null ? booking.getBookingCode() : "");
            enqueueBookingEmailIdempotently(
                    booking,
                    "BOOKING_SEAT_CONFLICT",
                    "BOOKING_SEAT_CONFLICT:" + booking.getBookingId(),
                    "BOX Cinema - Seat Conflict & Full Refund Notice (#" + booking.getBookingCode() + ")",
                    "<p>Dear Customer,</p>"
                            + "<p>We received your payment for booking <strong>" + safeBookingCodeHtml + "</strong>, "
                            + "however the seat hold had already expired and the seats were taken by another order.</p>"
                            + "<p>No tickets were issued. Please visit our Contact &amp; Refund Support page with your booking code and purchase email to request a full refund.</p>"
            );

            saveWebhookEventIdempotently(payment.getPaymentId(), orderCode, reference, fingerprint, "PAID_SEAT_CONFLICT");
            log.warn("Late payment resulted in PAID_SEAT_CONFLICT for booking {}", sanitizeLogToken(booking.getBookingCode()));
            return;
        }

        // 2. Mark Booking CONFIRMED (Case A: active or recovered expired booking with free seats)
        booking.setBookingStatus("CONFIRMED");
        booking.setConfirmedAt(LocalDateTime.now());

        // 3. Update Seats to BOOKED
        if (booking.getBookingSeats() != null) {
            for (BookingSeat bs : booking.getBookingSeats()) {
                bs.setStatus("BOOKED");
                if (bs.getShowtimeSeatId() != null) {
                    bs.setActiveSeatKey(bs.getShowtimeSeatId());
                }
                jdbcTemplate.update(
                        "UPDATE showtime_seats SET seat_status = 'BOOKED', held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL WHERE showtime_seat_id = ?",
                        bs.getShowtimeSeatId()
                );
            }
        }

        // 4. Commit Food Inventory (re-reserve first if previously RELEASED during expiry cleanup;
        //    never throw 500 on a paid webhook if food stock sold out after hold expiry -> flag ISSUE_REPORTED)
        List<String> unavailableFoodItems = new ArrayList<>();
        for (BookingFoodItem bfi : sortedFoodItems(booking)) {
            boolean wasReleased = "RELEASED".equalsIgnoreCase(bfi.getDeliveryStatus());
            try {
                if ("FOOD".equalsIgnoreCase(bfi.getItemType()) && bfi.getFoodItem() != null) {
                    if (wasReleased) {
                        foodService.checkAndReserveFoodStock(bfi.getFoodItem().getId(), bfi.getQuantity());
                        bfi.setDeliveryStatus("PENDING");
                    }
                    foodService.commitFoodStock(bfi.getFoodItem().getId(), bfi.getQuantity());
                } else if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getCombo() != null) {
                    if (wasReleased) {
                        foodService.checkAndReserveComboStock(bfi.getCombo().getId(), bfi.getQuantity());
                        bfi.setDeliveryStatus("PENDING");
                    }
                    foodService.commitComboStock(bfi.getCombo().getId(), bfi.getQuantity());
                }
            } catch (BusinessException | IllegalStateException ex) {
                String itemLabel = !isBlank(bfi.getProductNameSnapshot())
                        ? bfi.getProductNameSnapshot()
                        : ("item#" + bfi.getId());
                unavailableFoodItems.add(itemLabel);
                bfi.setDeliveryStatus("ISSUE_REPORTED");
                log.warn("Food stock unavailable during paid webhook recovery for bookingCode={} item={}: {}",
                        sanitizeLogToken(booking.getBookingCode()), sanitizeLogToken(itemLabel), sanitizeLogToken(ex.getMessage()));
            }
        }
        bookingRepository.save(booking);

        // 5. Issue 1 Ticket per seat only once (H4)
        List<Ticket> tickets = createTicketsIfAbsent(booking, "VALID");

        // 5b. Lock immutable purchase-time snapshot & proportional discount allocation (from actual payment.amount)
        purchaseSnapshotService.allocateOrderSnapshot(booking, payment.getAmount(), tickets);
        if (!tickets.isEmpty()) {
            ticketRepository.saveAll(tickets);
        }
        bookingRepository.save(booking);

        // 6. Generate 1 single unified Booking QR for the entire booking
        bookingQrService.getOrCreateBookingQr(booking.getBookingId());

        // 7. Food Fulfillment
        if (booking.getBookingFoodItems() != null && !booking.getBookingFoodItems().isEmpty()) {
            Optional<FoodFulfillment> existingFfOpt = foodFulfillmentRepository.findByBooking_BookingId(booking.getBookingId());
            if (existingFfOpt.isEmpty()) {
                FoodFulfillment ff = new FoodFulfillment(booking);
                if (!unavailableFoodItems.isEmpty()) {
                    ff.setStatus("ISSUE_REPORTED");
                    ff.setNote("Late payment recovered after hold expiry; out-of-stock F&B item(s) require counter replacement or partial refund: "
                            + String.join(", ", unavailableFoodItems));
                } else {
                    ff.setStatus("QUEUED");
                }
                Cinema cinema = booking.getCinema() != null ? booking.getCinema()
                        : (booking.getShowtime() != null && booking.getShowtime().getRoom() != null
                            ? booking.getShowtime().getRoom().getCinema() : null);
                ff.setCinema(cinema);
                ff.setPickupLocation(cinema != null
                        ? cinema.getCinemaName() + " - Concession Counter"
                        : "Concession Counter");
                foodFulfillmentRepository.save(ff);
            } else if (!unavailableFoodItems.isEmpty()) {
                FoodFulfillment ff = existingFfOpt.get();
                ff.setStatus("ISSUE_REPORTED");
                ff.setNote("Late payment recovered after hold expiry; out-of-stock F&B item(s) require counter replacement or partial refund: "
                        + String.join(", ", unavailableFoodItems));
                foodFulfillmentRepository.save(ff);
            }
        }

        // 8. Record Deduplicated Outbox Email (H4)
        enqueueBookingEmailIdempotently(booking, "BOOKING_CONFIRMATION", "BOOKING_CONFIRMATION:" + booking.getBookingId(), null, null);

        // 9. Record Webhook Event
        saveWebhookEventIdempotently(payment.getPaymentId(), orderCode, reference, fingerprint, "PROCESSED");

        log.info("Successfully processed payment and confirmed booking {} with code {}", booking.getBookingId(), booking.getBookingCode());
    }

    private List<BookingFoodItem> sortedFoodItems(Booking booking) {
        if (booking == null || booking.getBookingFoodItems() == null || booking.getBookingFoodItems().isEmpty()) {
            return List.of();
        }
        return booking.getBookingFoodItems().stream()
                .sorted(Comparator.comparing(bfi -> {
                    if ("FOOD".equalsIgnoreCase(bfi.getItemType()) && bfi.getFoodItem() != null) {
                        return "A_" + bfi.getFoodItem().getId();
                    }
                    if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getCombo() != null) {
                        return "B_" + bfi.getCombo().getId();
                    }
                    return "Z";
                }))
                .toList();
    }

    private void enqueueBookingEmailIdempotently(Booking booking, String emailType, String dedupKey, String subject, String bodyHtml) {
        String recipientEmail = (booking.getUser() != null && booking.getUser().getEmail() != null && !booking.getUser().getEmail().isBlank())
                ? booking.getUser().getEmail()
                : booking.getGuestEmail();
        if (recipientEmail == null || recipientEmail.isBlank()) {
            return;
        }
        if (!emailOutboxRepository.existsByDedupKey(dedupKey)) {
            try {
                EmailOutbox outbox = new EmailOutbox(booking, recipientEmail, emailType);
                outbox.setDedupKey(dedupKey);
                if (subject != null) {
                    outbox.setSubject(subject);
                }
                if (bodyHtml != null) {
                    outbox.setBodyHtml(bodyHtml);
                }
                emailOutboxRepository.save(outbox);
            } catch (DataIntegrityViolationException ignored) {
            }
        }
    }

    private boolean detectSeatConflictForLatePayment(Booking booking, boolean expiredOrCancelled) {
        if (booking.getBookingSeats() == null || booking.getBookingSeats().isEmpty()) {
            return false;
        }
        List<Long> stsIds = booking.getBookingSeats().stream()
                .map(BookingSeat::getShowtimeSeatId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        if (stsIds.isEmpty()) {
            return false;
        }
        String placeholders = String.join(",", Collections.nCopies(stsIds.size(), "?"));
        List<Map<String, Object>> lockedSeats = jdbcTemplate.queryForList(
                "SELECT showtime_seat_id, seat_status, held_by_user_id, " +
                "       TIMESTAMPDIFF(SECOND, NOW(), hold_expires_at) AS rem_sec " +
                "FROM showtime_seats WHERE showtime_seat_id IN (" + placeholders + ") ORDER BY seat_id ASC FOR UPDATE",
                stsIds.toArray()
        );
        Long bookingUserId = booking.getUser() != null ? booking.getUser().getUserId() : null;
        for (Map<String, Object> row : lockedSeats) {
            Long stsId = ((Number) row.get("showtime_seat_id")).longValue();
            String seatStatus = row.get("seat_status") != null ? String.valueOf(row.get("seat_status")) : "";
            Long heldUser = row.get("held_by_user_id") != null ? ((Number) row.get("held_by_user_id")).longValue() : null;
            long remSec = row.get("rem_sec") != null ? ((Number) row.get("rem_sec")).longValue() : 0L;

            if ("BOOKED".equalsIgnoreCase(seatStatus) || "SOLD".equalsIgnoreCase(seatStatus)) {
                return true;
            }
            Integer otherConfirmed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM booking_seats bs JOIN bookings b ON bs.booking_id = b.booking_id " +
                    "WHERE bs.showtime_seat_id = ? AND b.booking_id <> ? AND b.booking_status IN ('CONFIRMED', 'PAID')",
                    Integer.class, stsId, booking.getBookingId()
            );
            if (otherConfirmed != null && otherConfirmed > 0) {
                return true;
            }
            if (expiredOrCancelled && "HELD".equalsIgnoreCase(seatStatus) && remSec > 0) {
                if (bookingUserId != null) {
                    if (!bookingUserId.equals(heldUser)) {
                        return true;
                    }
                } else if (heldUser != null) {
                    return true;
                }
                Integer otherPending = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM booking_seats bs JOIN bookings b ON bs.booking_id = b.booking_id " +
                        "WHERE bs.showtime_seat_id = ? AND b.booking_id <> ? AND b.booking_status IN ('DRAFT', 'PENDING_PAYMENT') " +
                        "AND (b.payment_deadline IS NULL OR b.payment_deadline > NOW())",
                        Integer.class, stsId, booking.getBookingId()
                );
                if (otherPending != null && otherPending > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Ticket> createTicketsIfAbsent(Booking booking, String ticketStatus) {
        List<Ticket> existing = ticketRepository.findByBooking_BookingId(booking.getBookingId());
        if (!existing.isEmpty()) {
            return existing;
        }
        List<Ticket> tickets = new ArrayList<>();
        if (booking.getBookingSeats() != null && !booking.getBookingSeats().isEmpty()) {
            Long showtimeId = booking.getShowtime() != null ? booking.getShowtime().getShowtimeId() : null;
            for (BookingSeat bs : booking.getBookingSeats()) {
                Ticket t = new Ticket();
                t.setBooking(booking);
                t.setBookingSeatId(bs.getBookingSeatId());
                t.setShowtimeId(showtimeId);
                Long seatId = null;
                if (jdbcTemplate != null && bs.getShowtimeSeatId() != null) {
                    try {
                        List<Long> foundSeatIds = jdbcTemplate.queryForList(
                                "SELECT seat_id FROM showtime_seats WHERE showtime_seat_id = ?",
                                Long.class,
                                bs.getShowtimeSeatId()
                        );
                        if (!foundSeatIds.isEmpty()) {
                            seatId = foundSeatIds.get(0);
                        }
                    } catch (Exception ignored) {
                    }
                }
                t.setSeatId(seatId);
                t.setTicketCode("TCK-" + booking.getBookingCode().replace("BOX-", "") + "-" + bs.getSeatLabelSnapshot());
                t.setQrTokenHash(com.boxcinema.common.util.CryptoUtils.sha256(t.getTicketCode() + "_" + System.nanoTime()));
                t.setPrice(bs.getUnitPrice());
                t.setTicketStatus(ticketStatus);
                t.setIssuedAt(LocalDateTime.now());
                tickets.add(t);
            }
        }
        return tickets;
    }

    private void saveWebhookEventIdempotently(Long paymentId, Long orderCode, String reference, String fingerprint, String status) {
        String safeRef = reference != null && reference.length() > 150 ? reference.substring(0, 150) : reference;
        String safeFp = fingerprint != null && fingerprint.length() > 255 ? fingerprint.substring(0, 255) : fingerprint;
        if (jdbcTemplate != null) {
            jdbcTemplate.update(
                    "INSERT IGNORE INTO payment_webhook_event "
                            + "(payment_id, order_code, transaction_reference, event_fingerprint, signature_verified, received_at, processed_at, processing_status) "
                            + "VALUES (?, ?, ?, ?, ?, NOW(), NOW(), ?)",
                    paymentId, orderCode, safeRef, safeFp, true, status);
            return;
        }
        if (webhookEventRepository.existsByEventFingerprint(safeFp)) {
            return;
        }
        try {
            PaymentWebhookEvent evt = new PaymentWebhookEvent();
            evt.setPaymentId(paymentId);
            evt.setOrderCode(orderCode);
            evt.setTransactionReference(safeRef);
            evt.setEventFingerprint(safeFp);
            evt.setSignatureVerified(true);
            evt.setProcessedAt(LocalDateTime.now());
            evt.setProcessingStatus(status);
            webhookEventRepository.saveAndFlush(evt);
        } catch (DataIntegrityViolationException ignored) {
        }
    }

    private static String sanitizeLogToken(String value) {
        if (value == null) return "null";
        String clean = value.replace("\r", "").replace("\n", "").replaceAll("[\\p{Cntrl}]", "").trim();
        return clean.length() > 100 ? clean.substring(0, 100) : clean;
    }

    @Override
    public List<Map<String, Object>> listCollectionReconciliationQueue() {
        return runInTx(readOnlyTxTemplate, () -> {
            clearReadOnlyPersistenceContext();
            if (jdbcTemplate == null) {
                return List.of();
            }
            List<Long> paymentIds = jdbcTemplate.queryForList(
                    "SELECT p.payment_id FROM payments p "
                            + "JOIN bookings b ON b.booking_id = p.booking_id "
                            + "WHERE p.payment_status = 'REVIEW_REQUIRED' "
                            + "   OR b.booking_status = 'PAYMENT_REVIEW' "
                            + "   OR b.snapshot_review_required = 1 "
                            + "ORDER BY p.payment_id DESC LIMIT 100",
                    Long.class);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Long pid : paymentIds) {
                Payment p = paymentRepository.findById(pid).orElse(null);
                if (p == null) continue;
                Booking b = p.getBooking();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("paymentId", p.getPaymentId());
                row.put("orderCode", p.getOrderCode());
                row.put("paymentStatus", p.getPaymentStatus());
                row.put("paymentAmount", p.getAmount());
                row.put("currency", p.getCurrency());
                row.put("providerTransactionId", p.getProviderTransactionId());
                row.put("createdAt", p.getCreatedAt());
                row.put("paidAt", p.getPaidAt());
                if (b != null) {
                    row.put("bookingId", b.getBookingId());
                    row.put("bookingCode", b.getBookingCode());
                    row.put("bookingStatus", b.getBookingStatus());
                    row.put("bookingTotalAmount", b.getTotalAmount());
                    row.put("snapshotReviewRequired", b.isSnapshotReviewRequired());
                    row.put("cinemaId", b.resolveCinemaId());
                    String email = (b.getUser() != null && !isBlank(b.getUser().getEmail()))
                            ? b.getUser().getEmail() : b.getGuestEmail();
                    row.put("customerEmail", email);
                }
                out.add(row);
            }
            return out;
        });
    }

    @Override
    public Map<String, Object> resolveCollectionReconciliation(Long paymentId, String decision, String note, Long adminUserId) {
        if (adminUserId == null) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Admin authentication is required.");
        }
        String normalizedDecision = decision != null ? decision.trim().toUpperCase(Locale.ROOT) : "";
        if (!Set.of("ACCEPT_AND_CONFIRM", "CONVERT_TO_REFUNDABLE").contains(normalizedDecision)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_RECONCILE_DECISION",
                    "Decision must be ACCEPT_AND_CONFIRM or CONVERT_TO_REFUNDABLE.");
        }
        String cleanNote = note != null ? note.trim() : "";
        if (cleanNote.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "RECONCILE_NOTE_REQUIRED",
                    "A mandatory reconciliation note is required.");
        }

        PaymentBookingIds ids = runInTx(readOnlyTxTemplate, () -> {
            clearReadOnlyPersistenceContext();
            Payment p = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND",
                            "Payment not found: " + paymentId));
            Long bId = p.getBooking() != null ? p.getBooking().getBookingId() : null;
            return new PaymentBookingIds(bId, p.getPaymentId());
        });
        if (ids == null || ids.bookingId() == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND",
                    "Booking not found for payment: " + paymentId);
        }

        return runInTx(txTemplate, () -> {
            Booking booking = bookingRepository.findByIdForUpdate(ids.bookingId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "BOOKING_NOT_FOUND",
                            "Booking not found: " + ids.bookingId()));
            Payment payment = paymentRepository.findByIdForUpdate(ids.paymentId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND",
                            "Payment not found: " + ids.paymentId()));

            boolean inReview = "REVIEW_REQUIRED".equalsIgnoreCase(payment.getPaymentStatus())
                    || "PAYMENT_REVIEW".equalsIgnoreCase(booking.getBookingStatus())
                    || booking.isSnapshotReviewRequired();
            if (!inReview) {
                throw new BusinessException(HttpStatus.CONFLICT, "PAYMENT_NOT_IN_REVIEW",
                        "Payment/booking is not in a reconciliation review state.");
            }

            if ("ACCEPT_AND_CONFIRM".equals(normalizedDecision)) {
                boolean seatConflict = detectSeatConflictForLatePayment(booking, true);
                if (seatConflict) {
                    throw new BusinessException(HttpStatus.CONFLICT, "SEAT_CONFLICT_USE_REFUNDABLE",
                            "Seats are no longer available for this booking. Use CONVERT_TO_REFUNDABLE instead.");
                }
                payment.setPaymentStatus("PAID");
                if (payment.getPaidAt() == null) {
                    payment.setPaidAt(LocalDateTime.now());
                }
                paymentRepository.save(payment);

                booking.setBookingStatus("CONFIRMED");
                if (booking.getConfirmedAt() == null) {
                    booking.setConfirmedAt(LocalDateTime.now());
                }
                booking.setSnapshotReviewRequired(false);

                if (booking.getBookingSeats() != null) {
                    for (BookingSeat bs : booking.getBookingSeats()) {
                        bs.setStatus("BOOKED");
                        if (bs.getShowtimeSeatId() != null) {
                            bs.setActiveSeatKey(bs.getShowtimeSeatId());
                        }
                        if (jdbcTemplate != null && bs.getShowtimeSeatId() != null) {
                            jdbcTemplate.update(
                                    "UPDATE showtime_seats SET seat_status = 'BOOKED', held_by_user_id = NULL, hold_session_id = NULL, hold_expires_at = NULL WHERE showtime_seat_id = ?",
                                    bs.getShowtimeSeatId());
                        }
                    }
                }

                List<String> unavailableFood = new ArrayList<>();
                for (BookingFoodItem bfi : sortedFoodItems(booking)) {
                    boolean wasReleased = "RELEASED".equalsIgnoreCase(bfi.getDeliveryStatus());
                    try {
                        if ("FOOD".equalsIgnoreCase(bfi.getItemType()) && bfi.getFoodItem() != null) {
                            if (wasReleased) {
                                foodService.checkAndReserveFoodStock(bfi.getFoodItem().getId(), bfi.getQuantity());
                                bfi.setDeliveryStatus("PENDING");
                            }
                            foodService.commitFoodStock(bfi.getFoodItem().getId(), bfi.getQuantity());
                        } else if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getCombo() != null) {
                            if (wasReleased) {
                                foodService.checkAndReserveComboStock(bfi.getCombo().getId(), bfi.getQuantity());
                                bfi.setDeliveryStatus("PENDING");
                            }
                            foodService.commitComboStock(bfi.getCombo().getId(), bfi.getQuantity());
                        }
                    } catch (BusinessException | IllegalStateException ex) {
                        bfi.setDeliveryStatus("ISSUE_REPORTED");
                        unavailableFood.add(!isBlank(bfi.getProductNameSnapshot()) ? bfi.getProductNameSnapshot() : ("item#" + bfi.getId()));
                    }
                }
                bookingRepository.save(booking);

                List<Ticket> tickets = createTicketsIfAbsent(booking, "VALID");
                purchaseSnapshotService.allocateOrderSnapshot(booking, payment.getAmount(), tickets);
                if (!tickets.isEmpty()) {
                    ticketRepository.saveAll(tickets);
                }
                bookingRepository.save(booking);
                bookingQrService.getOrCreateBookingQr(booking.getBookingId());

                if (booking.getBookingFoodItems() != null && !booking.getBookingFoodItems().isEmpty()) {
                    Optional<FoodFulfillment> existingFf = foodFulfillmentRepository.findByBooking_BookingId(booking.getBookingId());
                    FoodFulfillment ff = existingFf.orElseGet(() -> new FoodFulfillment(booking));
                    if (!unavailableFood.isEmpty()) {
                        ff.setStatus("ISSUE_REPORTED");
                        ff.setNote("Admin reconciled payment; out-of-stock F&B item(s): " + String.join(", ", unavailableFood));
                    } else if (existingFf.isEmpty()) {
                        ff.setStatus("QUEUED");
                    }
                    if (ff.getCinema() == null) {
                        Cinema cinema = booking.getCinema() != null ? booking.getCinema()
                                : (booking.getShowtime() != null && booking.getShowtime().getRoom() != null
                                    ? booking.getShowtime().getRoom().getCinema() : null);
                        ff.setCinema(cinema);
                        ff.setPickupLocation(cinema != null ? cinema.getCinemaName() + " - Concession Counter" : "Concession Counter");
                    }
                    foodFulfillmentRepository.save(ff);
                }

                enqueueBookingEmailIdempotently(booking, "BOOKING_CONFIRMATION",
                        "BOOKING_CONFIRMATION:" + booking.getBookingId(), null, null);
                String auditFp = "ADMIN_REC_CONFIRM_" + payment.getPaymentId() + "_" + System.currentTimeMillis();
                saveWebhookEventIdempotently(payment.getPaymentId(),
                        payment.getOrderCode() != null ? payment.getOrderCode() : 0L,
                        "ADMIN:" + adminUserId, auditFp, "ADMIN_RECONCILED_CONFIRM");
            } else {
                // CONVERT_TO_REFUNDABLE -> PAID + PAID_SEAT_CONFLICT so standard 2-stage Refund/Payout workflow can refund
                payment.setPaymentStatus("PAID");
                if (payment.getPaidAt() == null) {
                    payment.setPaidAt(LocalDateTime.now());
                }
                paymentRepository.save(payment);

                booking.setBookingStatus("PAID_SEAT_CONFLICT");
                booking.setSnapshotReviewRequired(false);
                if (booking.getBookingSeats() != null) {
                    for (BookingSeat bs : booking.getBookingSeats()) {
                        bs.setStatus("CONFLICT");
                        bs.setActiveSeatKey(null);
                    }
                }
                for (BookingFoodItem bfi : sortedFoodItems(booking)) {
                    if ("RELEASED".equalsIgnoreCase(bfi.getDeliveryStatus())) {
                        continue;
                    }
                    if ("FOOD".equalsIgnoreCase(bfi.getItemType()) && bfi.getFoodItem() != null) {
                        foodService.releaseFoodStock(bfi.getFoodItem().getId(), bfi.getQuantity());
                        bfi.setDeliveryStatus("RELEASED");
                    } else if ("COMBO".equalsIgnoreCase(bfi.getItemType()) && bfi.getCombo() != null) {
                        foodService.releaseComboStock(bfi.getCombo().getId(), bfi.getQuantity());
                        bfi.setDeliveryStatus("RELEASED");
                    }
                }
                bookingRepository.save(booking);

                List<Ticket> conflictTickets = createTicketsIfAbsent(booking, "CONFLICT_UNISSUED");
                purchaseSnapshotService.allocateOrderSnapshot(booking, payment.getAmount(), conflictTickets);
                if (!conflictTickets.isEmpty()) {
                    ticketRepository.saveAll(conflictTickets);
                }
                bookingRepository.save(booking);

                String auditFp = "ADMIN_REC_REFUND_" + payment.getPaymentId() + "_" + System.currentTimeMillis();
                saveWebhookEventIdempotently(payment.getPaymentId(),
                        payment.getOrderCode() != null ? payment.getOrderCode() : 0L,
                        "ADMIN:" + adminUserId, auditFp, "ADMIN_RECONCILED_REFUNDABLE");
            }

            log.info("Admin {} reconciled collection paymentId={} bookingCode={} decision={} note={}",
                    adminUserId, payment.getPaymentId(), sanitizeLogToken(booking.getBookingCode()),
                    normalizedDecision, sanitizeLogToken(cleanNote));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("paymentId", payment.getPaymentId());
            result.put("bookingId", booking.getBookingId());
            result.put("bookingCode", booking.getBookingCode());
            result.put("decision", normalizedDecision);
            result.put("paymentStatus", payment.getPaymentStatus());
            result.put("bookingStatus", booking.getBookingStatus());
            result.put("snapshotReviewRequired", booking.isSnapshotReviewRequired());
            result.put("resolvedBy", adminUserId);
            result.put("note", cleanNote);
            return result;
        });
    }

    private static String safeCodeToken(String code) {
        return code != null ? code.trim() : "NULL";
    }

    private static String trimSafe(String s) {
        return s == null ? "" : s.trim();
    }

    @Override
    public void simulatePayment(Long orderCode) {
        PayOsWebhookPayloadDto payload = runInTx(readOnlyTxTemplate, () -> {
            Payment payment = paymentRepository.findByOrderCode(orderCode)
                    .orElseThrow(() -> new IllegalArgumentException("Payment not found for orderCode: " + orderCode));

            PayOsWebhookPayloadDto p = new PayOsWebhookPayloadDto();
            p.setCode("00");
            p.setDesc("success");

            PayOsWebhookPayloadDto.PayOsWebhookDataDto data = new PayOsWebhookPayloadDto.PayOsWebhookDataDto();
            data.setOrderCode(orderCode);
            data.setAmount(payment.getAmount().setScale(0, RoundingMode.HALF_UP).longValue());
            data.setDescription(payment.getDescription());
            data.setReference("SIM-TXN-" + System.currentTimeMillis());
            data.setPaymentLinkId(payment.getPaymentLinkId());
            data.setCode("00");
            data.setDesc("success");
            p.setData(data);
            p.setSignature(computeWebhookSignature(p));
            return p;
        });

        processWebhook(payload);
    }

    private PayOsCreatePaymentResponseDto mapToCreateResponse(Payment p, Booking b) {
        PayOsCreatePaymentResponseDto dto = new PayOsCreatePaymentResponseDto();
        dto.setPaymentId(p.getPaymentId());
        dto.setBookingId(b.getBookingId());
        dto.setBookingCode(b.getBookingCode());
        dto.setOrderCode(p.getOrderCode());
        dto.setAmount(p.getAmount());
        dto.setDescription(p.getDescription());

        String bin = p.getBin() != null ? p.getBin() : "970422";
        dto.setBin(bin);
        dto.setAccountNumber(p.getAccountNumber() != null ? p.getAccountNumber() : "");
        dto.setAccountName(p.getAccountName() != null ? p.getAccountName() : "BOX CINEMA");
        dto.setBankName(mapBinToBankName(bin));

        dto.setQrCode(p.getQrCodePayload());
        dto.setCheckoutUrl(p.getCheckoutUrl());
        dto.setPaymentLinkId(p.getPaymentLinkId());
        dto.setStatus(p.getPaymentStatus());

        long remainingSec = 600;
        if (p.getExpiredAt() != null) {
            remainingSec = Math.max(0, java.time.Duration.between(LocalDateTime.now(), p.getExpiredAt()).getSeconds());
        }
        dto.setExpiresInSeconds(remainingSec);
        return dto;
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String mapBinToBankName(String bin) {
        if ("970422".equals(bin)) return "MBBank";
        if ("970415".equals(bin)) return "VietinBank";
        if ("970436".equals(bin)) return "Vietcombank";
        if ("970407".equals(bin)) return "Techcombank";
        if ("970418".equals(bin)) return "BIDV";
        if ("970423".equals(bin)) return "TPBank";
        if ("970432".equals(bin)) return "VPBank";
        if ("970405".equals(bin)) return "Agribank";
        return "Affiliated Bank (" + bin + ")";
    }
}
