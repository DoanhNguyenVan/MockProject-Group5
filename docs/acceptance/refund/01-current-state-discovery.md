# Stage 0 — Current-State Discovery & Gap Analysis Report

**Project**: BOX Cinema (`com.boxcinema:box-cinema:1.0.0-SNAPSHOT`)  
**Workspace**: `C:\Users\dangi\.gemini\antigravity\scratch\MockProject-Group5`  
**Branch**: `feat/refund-payos-payout`  
**Stage**: Stage 0 (Architecture & Current-State Discovery — Read-Only)  
**Author**: `@architect` (`gemini-4-argon-high`)  
**Date**: 2026-11-26 (`Asia/Ho_Chi_Minh`, UTC+07:00)

---

## 1. Actual Architecture

### 1.1 Build, Runtime & Framework Stack
Verified directly from `pom.xml` and runtime environment checks:
- **Java / Spring Boot**: Java 17 target in `pom.xml` (running on JDK 21.0.10 locally), **Spring Boot 3.3.5** (`spring-boot-starter-parent:3.3.5`).
- **Core Dependencies**:
  - `spring-boot-starter-web` (Spring MVC + embedded Tomcat, `RestTemplate` for HTTP calls)
  - `spring-boot-starter-thymeleaf` + `thymeleaf-extras-springsecurity6` (server-side HTML templates)
  - `spring-boot-starter-data-jpa` (Hibernate 6 / Jakarta Persistence 3.1)
  - `spring-boot-starter-security` (Spring Security 6 stateful `HttpSession` architecture)
  - `spring-boot-starter-mail` (SMTP email dispatch backed by database `email_outbox` polling worker)
  - `com.mysql:mysql-connector-j` (MySQL 8.0 InnoDB driver, UTF8MB4, timezone `Asia/Ho_Chi_Minh`)
  - `org.bouncycastle:bcprov-jdk18on:1.78.1` (Argon2id password hashing & cryptographic primitives)
  - `com.google.zxing:core:3.5.3` & `javase:3.5.3` (QR code generation for tickets)
  - `org.hibernate.validator:hibernate-validator` (Jakarta Bean Validation)
  - `org.projectlombok:lombok:1.18.46`
  - **Note on PayOS SDK**: `pom.xml` does **not** depend on `vn.payos:payos-java`; both PayOS Collection (Pay-in v2) and PayOS Payout (Disbursement v1) are implemented via Spring `RestTemplate` + Jackson `ObjectMapper` + custom HMAC-SHA256 signing utilities (`CryptoUtils` and `PayOsPayoutSignatureUtil`).
- **Schema Management**: `spring.jpa.hibernate.ddl-auto=none` (`src/main/resources/application.properties:25`). All schema changes are managed through idempotent SQL scripts in `database/`:
  - `database/schema.sql` (19-table baseline)
  - `database/migration_auth_v2.sql`
  - `database/migration_v2_1_booking_food_payos.sql`
  - `database/migration_v3_refund_incident_payos_payout.sql`
  - `database/migration_v4_refund_payout_workflow.sql` (+ rollback script)
  - `database/migration_v5_customer_bank_accounts.sql` (+ rollback script)
  - `database/migration_v6_integrity_indexes_bootstrap_parity.sql` (+ rollback script)

### 1.2 Backend Package Structure (`src/main/java/com/boxcinema/`)
The backend is organized into 7 top-level domain packages:
1. **`common`**:
   - `config`: `SecurityConfig`, `AsyncConfig`, `SchedulingConfig`, `DataInitializer`, `OtpProperties`
   - `constant`: `ApiErrorCodes`, `AppConstants`, `SessionConstants`
   - `dto`: `ApiResponse`, `BookingResponseDto`, `BookingSeatDto`, `BookingFoodItemDto`, `BookingTicketViewDto`, `CinemaDto`, `MovieOptionDto`, `UserSummaryDto`
   - `entity`: All 38 JPA entities (shared across modules)
   - `enums`: `AccountStatus`, `BookingStatus`, `OtpPurpose`, `PasswordResetStatus`, `SeatStatus`, `UserRole`
   - `exception`: `BusinessException`, `AuthException`, `ResourceNotFoundException`, `GlobalExceptionHandler`
   - `repository`: All 38 Spring Data JPA repositories + `AdminWorkspaceJdbcRepository`
   - `security`: `BoxCinemaAuthenticationProvider`, `BoxCinemaUserDetails`, `BoxCinemaUserDetailsService`, `SessionValidationFilter`, `SecuritySessionHelper`, `UnverifiedEmailAuthenticationException`
   - `service`: `BankDataEncryptionService`, `PurchaseSnapshotService`, `BookingService`, `BookingQrService`, `CinemaService`, `EmailService`, `MovieService`, `PasswordHashService`, `QrCodeService`, `SessionManagementService`
   - `util`: `CryptoUtils`, `PayOsPayoutSignatureUtil`, `SecurityContextUtils`
2. **`auth`**:
   - Login, registration, email verification OTP, password reset OTP, Google OIDC + PKCE auto-link/manual-link, `AuthRateLimitService`, `AuthAuditService`, `LoginAttemptService`.
3. **`customer`**:
   - Customer booking flow (`BookingApiController`, `BookingPageController`, `BookingServiceImpl`), concessions (`FoodApiController`, `FoodServiceImpl`), PayOS Pay-in (`PaymentApiController`, `PayOsServiceImpl`), vouchers (`VoucherApiController`, `PromotionServiceImpl`), ticket history & E-ticket view (`TicketPageController`, `TicketApiController`, `TicketServiceImpl`), inquiries (`HomeController`, `InquiryServiceImpl`).
4. **`staff`**:
   - Gate ticket QR check-in (`StaffCheckinApiController`, `StaffCheckinServiceImpl`), concessions kitchen display & fulfillment (`StaffFoodApiController`, `FoodFulfillmentServiceImpl`), branch incident reporting (`StaffIncidentApiController`), and staff pages (`StaffPageController`).
5. **`refund`** (Pre-existing Wave 3–8 implementation in working tree):
   - `controller`: `PublicRefundApiController` (`/api/refunds/public/**`), `StaffRefundApiController` (`/api/staff/refunds/**`), `ManagerRefundApiController` (`/api/manager/**`), `ManagerPageController` (`/manager/**`), `CustomerBankAccountController` (`/api/customer/bank-accounts/**`).
   - `service`: `RefundService`, `RefundApprovalTxService`, `PayOsPayoutService`, `PayoutEligibilityService`, `IncidentService`, `VoucherRecoveryService`, `CustomerBankAccountService`, `EvidenceStorageService`, `RefundEmailService`.
   - `dto`: 17 DTOs covering order lookup, public/counter refund submission, item review decisions, payout decisions, batch payout preview/execution, bank account CRUD, incidents, and staff error logs.
6. **`admin`**:
   - `AdminPageController`, `AdminApiController`, `AdminAccountController`, `AdminMovieController`, `AdminGenreController`, `AdminInquiryApiController`, `AdminPaymentReconciliationController`, `AdminRefundPageController` (`/admin/refunds`, `/admin/incidents`), `AdminRefundApiController` (`/api/admin/refunds/**`, `/api/admin/payouts/**`, `/api/admin/incidents/**`, `/api/admin/staff-errors`).
7. **`publicsite`**:
   - Public QR ticket view (`PublicTicketViewController`, `PublicTicketApiController`) and `GlobalNavbarAdvice`.

### 1.3 Frontend Templates & JavaScript Modules
- **Thymeleaf Templates (`src/main/resources/templates/`)**:
  - `customer/`: `home.html`, `showtimes.html`, `movie-details.html`, `buy-tickets.html`, `ticket.html`, `booking-history.html`, `booking-detail.html`, `contact.html` (contains 3 tabs: Customer Inquiry, Request Refund / Issue, Track Refund & Reply), `profile.html`, `profile-edit.html`, `account-settings.html` (embeds `#customerBankAccountsRoot`), `change-password.html`, `about.html`.
  - `manager/`: `dashboard.html` (3 tabs: Refund Requests, Incident Verification, Staff Error Logs).
  - `admin/`: `workspace.html`, `accounts.html`, `refunds.html` (3 tabs: Payout Approval Queue, All Refunds & Screening, Payout History & Balance), `incidents.html`, `movies/*`, `genres/*`, `fragments/admin-shell.html`.
  - `staff/`: `checkin.html`, `food.html`, `incidents.html`.
  - `auth/` & `public/` & `fragments/`.
- **ES Modules (`src/main/resources/static/js/`)**:
  - `core/http-client.js` (CSRF header helper `getCsrfHeaders()`), `core/theme.js`
  - `modules/contact-refund.js` (552 lines — wires `/contact` Tabs 2 & 3 to `/api/refunds/public/**` and `/api/customer/bank-accounts`)
  - `modules/customer-bank-accounts.js` (274 lines — wires saved bank account CRUD in `/account-settings`)
  - `modules/refund-ops.js` (1,319 lines — shared Manager/Admin workspace logic for refund screening, single/batch payout confirmation, payout reconciliation, incident verification, and appeal resolution; uses DOM construction with zero `.innerHTML` assignments)
  - `modules/admin.js`, `booking.js`, `booking-payment.js`, `booking-screenings.js`, `booking-data.js`, `navbar.js`, etc.

### 1.4 Existing Automated Test Suite
- **Backend JUnit 5 / Spring Boot Integration Tests (`src/test/java/com/boxcinema/`)**:
  - `refund/PurchaseSnapshotServiceTest.java` (unit test for Hamilton Largest-Remainder discount allocation and partial-quantity refund math)
  - `refund/PayOsPayoutSignatureAndBankSecurityTest.java` (unit test for PayOS Payout HMAC-SHA256 signature & AES-256-GCM bank encryption)
  - `refund/RefundAndIncidentIntegrationTest.java` (1,583 lines — end-to-end MockMvc + MySQL integration tests)
  - `refund/RefundPayoutBatchWorkflowIntegrationTest.java` (718 lines — 2-stage screening, 13-point eligibility, single & batch payout tests)
  - `refund/PayOsPayoutWebhookResilienceIntegrationTest.java`
  - `refund/PayOsPayoutAndRefundFullAuditIntegrationTest.java`
  - `refund/CustomerBankAccountAndPayoutIntegrationTest.java`
  - `refund/Wave8RefundAndSecurityIntegrationTest.java`
  - Plus `auth/*`, `customer/*`, `admin/*`, `baseline/*` test suites.
- **Frontend Node Test Runner Suite (`tests/frontend/*.test.mjs`)**:
  - `refund-ui.test.mjs` (929 lines — tests `contact-refund.js`, `refund-ops.js`, `customer-bank-accounts.js`, XSS safety, batch 3/5 selection, bank confirmation).

---

## 2. Database and Related Modules

### 2.1 Verified Database Environment (Read-Only Summary)
- **Engine**: MySQL 8.0.43 (`InnoDB`, `utf8mb4_unicode_ci`), local service `MySQL80` on `127.0.0.1:3306`, schema `box_cinema` (44 tables verified by `@db-ops`).
- **Users, Accounts & Roles (`users` table / `User.java`)**:
  - Single `users` table (no separate `roles` join table).
  - `role VARCHAR(20) NOT NULL`: values `'CUSTOMER'`, `'STAFF'`, `'MANAGER'`, `'ADMIN'` (matching `UserRole.java`).
  - `cinema_id BIGINT NULL` (FK `fk_users_cinema -> cinemas(cinema_id)`): assigns `STAFF` and `MANAGER` accounts to a specific cinema branch (`ADMIN` and `CUSTOMER` have `cinema_id = NULL`).
  - `account_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'`.

### 2.2 Orders, Tickets, Seats, Showtimes & Movies
- **`bookings` (`Booking.java`)**:
  - Primary key `booking_id`, unique `booking_code` (e.g., `BOX-20261121-1234`).
  - Foreign keys: `user_id` (nullable for guest orders), `showtime_id` (nullable for food-only orders), `cinema_id`, `sold_by_staff_id`, `promotion_id`.
  - Financial columns (`DECIMAL(12,2)`): `ticket_subtotal`, `food_subtotal`, `subtotal`, `discount_amount`, `service_fee`, `total_amount`.
  - Snapshot columns (`migration_v2_1` & `migration_v3`): `voucher_code_snapshot`, `promotion_discount_type_snapshot`, `promotion_discount_value_snapshot`, `promotion_max_discount_snapshot`, `promotion_end_at_snapshot`, `snapshot_review_required`.
  - Status column `booking_status`: `'DRAFT'`, `'HOLDING'`, `'PENDING_PAYMENT'`, `'CONFIRMED'`, `'PAID'`, `'PAID_SEAT_CONFLICT'`, `'PAYMENT_REVIEW'`, `'PARTIALLY_REFUNDED'`, `'REFUNDED'`, `'CANCELLED'`, `'EXPIRED'`.
- **`booking_seats` (`BookingSeat.java`)**:
  - `booking_seat_id`, `booking_id`, `showtime_seat_id`, `unit_price`, `gross_price`, `discount_allocated`, `net_paid_amount`, `seat_label_snapshot`, `status` (`'HOLDING'`, `'BOOKED'`, `'REFUNDED'`), `active_seat_key BIGINT NULL UNIQUE`.
- **`tickets` (`Ticket.java`)**:
  - `ticket_id`, `booking_id`, `booking_seat_id` (`UNIQUE`), `showtime_id`, `seat_id`, `ticket_code` (`UNIQUE`), `qr_token_hash` (`UNIQUE`), `price`, `gross_price`, `discount_allocated`, `net_paid_amount`.
  - `ticket_status VARCHAR(20)`: `'VALID'`, `'USED'`, `'REFUNDED'`, `'CANCELLED'`, `'CONFLICT_UNISSUED'`.
  - `refund_status VARCHAR(30)`: `'NONE'`, `'REFUNDED'`.
  - `issued_at`, `checked_in_at`, `checked_in_by`, `used_at`.
- **`booking_food_item` & `booking_food_item_component` (`BookingFoodItem.java`, `BookingFoodItemComponent.java`)**:
  - Tracks standalone food items (`item_type = 'FOOD'`) and combos (`item_type = 'COMBO'`), decomposed into atomic `booking_food_item_component` rows with `allocated_unit_price`, `allocated_subtotal`, `discount_allocated`, `net_paid_amount`, `delivered_quantity`, `refunded_quantity`, `replacement_accepted_quantity`, and `delivery_status`.
- **`showtimes` (`Showtime.java`) & `movies` (`Movie.java`)**:
  - `showtimes`: `showtime_id`, `movie_id`, `room_id`, `start_at DATETIME NOT NULL`, `end_at DATETIME NOT NULL`, `base_price`, `status`.
  - `rooms`: links `room_id` to `cinema_id`.

### 2.3 Pre-Existing Refund, Payout, Incident, Bank Account & Audit Tables
Already created by `migration_v3` through `migration_v6`:
1. **`refund_requests` (`RefundRequest.java`)**:
   - Identification & scope: `id`, `request_code` (`UNIQUE`), `booking_id`, `cinema_id`, `customer_user_id`, `customer_name`, `customer_phone`, `purchase_email`, `submitted_order_code`, `incident_report_id`, `reason_category`, `description`, `evidence_url`.
   - Bank snapshot & link: `to_bin`, `to_bank_name`, `to_account_number_encrypted`, `to_account_number_masked`, `to_account_number_hash`, `account_holder_name`, `bank_note`, `saved_bank_account_id`.
   - Totals: `requested_cash_total`, `approved_cash_total`, `approved_voucher_total`.
   - Statuses: `request_status`, `payout_status`, `voucher_status`.
   - Stage 1 review metadata: `reviewed_by`, `reviewed_at`, `decision_note`.
   - Stage 2 payout decision metadata (`migration_v4`): `payout_decided_by`, `payout_decided_at`, `payout_decision_note`.
2. **`refund_request_items` (`RefundRequestItem.java`)**:
   - Line-level items (`item_kind`: `'TICKET'`, `'FOOD_ITEM'`, `'COMBO_COMPONENT'`), target FKs (`ticket_id`, `booking_seat_id`, `booking_food_item_id`, `booking_food_item_component_id`), quantities (`purchased_quantity`, `requested_quantity`, `approved_quantity`), financial snapshot (`gross_unit_price`, `discount_allocated_amount`, `net_paid_amount`, `approved_cash_amount`, `approved_voucher_amount`), `item_status`, `decision_reason`, `decided_by`, `decided_at`, and `active_lock_key VARCHAR(100) NULL UNIQUE` (prevents concurrent active refund requests on the same ticket/item).
3. **`refund_approval_history` (`RefundApprovalHistory.java`)**:
   - Append-only state transition history (`refund_request_id`, `refund_request_item_id`, `actor_user_id`, `actor_role`, `action`, `previous_status`, `new_status`, `note`, `created_at`).
4. **`refund_communications` (`RefundCommunication.java`)**:
   - Two-way message thread between Customer/Staff and Manager/Admin (`sender_type`, `sender_user_id`, `sender_name`, `message`, `attachment_url`, `email_dispatched`).
5. **`refund_audit_logs` (`RefundAuditLog.java`)**:
   - Structured audit log (`refund_request_id`, `entity_type`, `entity_id`, `event_type`, `actor_user_id`, `actor_role`, `cinema_id`, `ip_address`, `details_json`, `created_at`).
6. **`payos_payout_transactions` (`PayOsPayoutTransaction.java`)**:
   - `id`, `refund_request_id`, `reference_id` (`UNIQUE`, format `RFD-{reqId}-A{attempt}`), `batch_code`, `idempotency_key` (`UNIQUE`), `payos_payout_id`, `payos_transaction_id`, `provider_reference`, `payout_mode` (`'MOCK'` / `'LIVE'`), `amount` (`BIGINT` VND), `description`, `to_bin`, `to_account_number_masked`, `to_account_holder_name`, `category`, `status` (`'PENDING'`, `'PROCESSING'`, `'SUCCEEDED'`, `'FAILED'`), `approval_state`, `transaction_state` (including `'UNKNOWN_OUTCOME'`, `'MANUAL_RECONCILE_REQUIRED'`), `payos_response_code`, `payos_response_desc`, `error_message`, `raw_response_sanitized`, `attempt_count`, `active_request_lock BIGINT NULL UNIQUE`, `initiated_by`, `initiated_at`, `last_reconciled_at`, `succeeded_at`, `failed_at`.
7. **`customer_bank_accounts` (`CustomerBankAccount.java`)**:
   - `id`, `user_id`, `bank_bin`, `bank_name`, `account_number_encrypted`, `account_number_masked`, `account_number_hash`, `account_holder_name`, `note`, `is_default`, and virtual generated column `default_user_lock` with `UNIQUE KEY uk_cba_single_default` (`migration_v6`) enforcing at most one default bank account per user.
8. **`incident_reports`, `incident_verifications`, `staff_error_logs`**:
   - Branch-scoped incident reports with verification trail and isolated staff error logs.
9. **`voucher_recoveries` (`VoucherRecovery.java`)**:
   - Tracks single-use replacement vouchers (`old_promotion_id`, `old_voucher_code_snapshot`, `new_promotion_id UNIQUE`, `new_voucher_code UNIQUE`, `recovery_amount`, `expires_at`, `status`, `active_item_lock UNIQUE`).

---

## 3. Existing Pay-In Workflow (PayOS Collection)

Verified in `PayOsServiceImpl.java` (`src/main/java/com/boxcinema/customer/service/impl/PayOsServiceImpl.java`) and `PaymentApiController.java`:
- **3-Phase Transaction Pattern (No HTTP inside DB lock)**:
  - **TX1 (`preparePaymentTx1`)**: Acquires `Booking FOR UPDATE` -> latest `Payment FOR UPDATE`, supersedes stale `PENDING` payments, sets `booking_status = 'PENDING_PAYMENT'` with 10-minute deadline, inserts a `PENDING` row in `payments` with a unique numeric `order_code` and `idempotency_key`.
  - **Outside TX**: In `LIVE` mode, sends `POST /v2/payment-requests` to `payos.collection.base-url` with headers `x-client-id` and `x-api-key`, signing `amount={amount}&cancelUrl={cancelUrl}&description={description}&orderCode={orderCode}&returnUrl={returnUrl}` via HMAC-SHA256 (`CryptoUtils.hmacSha256`). In `MOCK` mode, generates a local EMVCo VietQR payload.
  - **TX2 (`finalizeCreatedPaymentTx2`)**: Persists `payment_link_id`, `qr_code_payload`, `checkout_url`, `bin`, `account_number`, `account_name`.
- **Webhook & Polling**:
  - `POST /api/payments/payos/webhook` verifies the PayOS v2 webhook signature (`CryptoUtils.hmacSha256` over sorted flat `data` fields), deduplicates events in `payment_webhook_event` via `event_fingerprint UNIQUE`, locks `Booking FOR UPDATE` -> `Payment FOR UPDATE` -> `ShowtimeSeat FOR UPDATE`, issues `Ticket` and `BookingQr` rows (or marks `PAID_SEAT_CONFLICT` with `CONFLICT_UNISSUED` tickets if seat hold expired and was taken), calls `purchaseSnapshotService.allocateOrderSnapshot(booking, payment.getAmount(), issuedTickets)`, and enqueues confirmation email in `email_outbox`.
- **Strict Credential Separation**:
  - `PayOsServiceImpl.validateCollectionConfiguration()` and `PayOsPayoutService.validateConfiguration()` enforce at startup that `payos.collection.*` credentials and `payos.payout.*` credentials are non-empty and strictly distinct whenever `LIVE` mode is active.

---

## 4. Voucher Schema & Allocation Logic

### 4.1 Current Schema
- **`promotions` table (`Promotion.java`)**:
  - Columns: `promotion_id`, `promotion_name`, `description`, `voucher_code` (`UNIQUE`), `discount_type` (`'PERCENT'` or `'FIXED'`), `discount_value`, `max_discount`, `min_order_amount`, `applicable_scope`, `start_at`, `end_at`, `usage_limit`, `usage_count`, `per_user_limit`, `status` (`'ACTIVE'` / `'INACTIVE'`), `is_public` (`BOOLEAN`), `promotion_source` (`'CAMPAIGN'` / `'REFUND_RECOVERY'`), `target_user_id`, `target_email`.
- **Order-to-Voucher Relationship (`bookings.promotion_id`)**:
  - Currently, voucher application is stored directly as a nullable foreign key `promotion_id BIGINT NULL` on the `bookings` table (`fk_bookings_promotion`), accompanied by `voucher_code_snapshot`, `promotion_discount_type_snapshot`, `promotion_discount_value_snapshot`, `promotion_max_discount_snapshot`, and `promotion_end_at_snapshot`.
  - Because `promotion_id` is a scalar column on `bookings`, a single `bookings` row can only point to one `promotion_id`.
  - **Gap**: There is **no dedicated `booking_vouchers` / `order_vouchers` table with an explicit `UNIQUE(booking_id)` / `UNIQUE(order_id)` constraint**, nor is `bookings.usage_count` incremented via a dedicated redemption ledger table (instead `PromotionRepository` counts active `bookings` rows where `promotion_id = :promotionId AND booking_status NOT IN ('CANCELLED', 'EXPIRED')` under `Promotion FOR UPDATE`).
- **Historical Data Check**:
  - Verified by `@db-ops` on local MySQL `box_cinema`: `bookings` has 16 rows total; only 1 row has `promotion_id IS NOT NULL` (`voucher_code_snapshot = 'BOXDUO'`), and `voucher_recoveries` has 0 rows. Zero historical multi-voucher-per-order violations exist in the local database.

### 4.2 Current Allocation & Recovery Implementation
- **`PurchaseSnapshotService.java`**:
  - Implements Largest Remainder Method (`allocateProportional(long totalToAllocate, long[] weights)`) using exact `BigInteger.divideAndRemainder` math in integer VND (`scale = 0`).
  - **Gap in allocation scope & tie-breaker**: `allocateOrderSnapshot` currently allocates `booking.discountAmount` across **all** order items (`BookingSeat`, standalone `BookingFoodItem`, and `BookingFoodItemComponent`) and breaks ties by array index (`BookingSeat.bookingSeatId ASC`, then `BookingFoodItem.id ASC`, then `BookingFoodItemComponent.id ASC`). We must verify and enforce deterministic per-ticket allocation with **`ticket_id` (`ticket_item_id`) ascending tie-breaker** and exact invariant `sum(allocated_voucher_discount) == voucher_discount_total` and `refundable_cash = original_ticket_price - allocated_voucher_discount`.
- **`VoucherRecoveryService.java`**:
  - Executes in an independent `PROPAGATION_REQUIRES_NEW` transaction so voucher generation failure never rolls back cash approval/payout.
  - Issues a new private single-use `Promotion` (`RCV-{bookingId}-{6chars}`, `is_public = false`, `usage_limit = 1`) rather than mutating the original promotion, and supports `revokeRecoveryVoucherInCurrentTx` when an un-dispatched refund is cancelled or held for re-verification.

---

## 5. Upload & Storage Infrastructure

Verified in `EvidenceStorageService.java` (`src/main/java/com/boxcinema/refund/service/EvidenceStorageService.java`) and `PublicRefundApiController.java` / `StaffIncidentApiController.java`:
- **Storage Location**: Configured via `app.refund.evidence-dir=${REFUND_EVIDENCE_DIR:uploads/refund-evidence}` (`Paths.get(evidenceDir).toAbsolutePath().normalize()`), which resolves outside the classpath static webroot (`src/main/resources/static`).
- **Current Validations**:
  - Max file size: `5 MB` (`5 * 1024 * 1024` bytes).
  - Allowed extensions: `jpg`, `jpeg`, `png`, `webp` (SVG, HTML, PDF, EXE are excluded).
  - Allowed MIME types: `image/jpeg`, `image/jpg`, `image/png`, `image/webp`.
  - Magic-byte signature check (`detectImageExtensionByMagicBytes`): verifies JPEG (`FF D8 FF`), PNG (`89 50 4E 47 0D 0A 1A 0A`), or WEBP (`RIFF....WEBP`) headers and requires exact agreement between magic bytes, declared Content-Type, and file extension.
  - Random filename: `UUID.randomUUID() + "." + detectedExt` with `target.startsWith(uploadRoot)` path-traversal check.
  - Response headers on download: `Content-Disposition: inline`, `X-Content-Type-Options: nosniff`, `Content-Security-Policy: default-src 'none'; img-src 'self'; style-src 'none'; sandbox`.
- **Identified Upload Security Gaps**:
  1. **Double-Extension Bypass**: `EvidenceStorageService.storeImage` (lines 48–56) only inspects the substring after `original.lastIndexOf('.')`. A file named `payload.php.jpg` or `exploit.html.png` with valid image magic bytes is currently **accepted** instead of being rejected for double/multiple extensions.
  2. **Unauthenticated Public Evidence Download**: `GET /api/refunds/public/evidence/{filename}` falls under `.requestMatchers("/api/refunds/public/**").permitAll()` in `SecurityConfig.java:157`. Any anonymous caller who has the UUID URL can download evidence files without authentication or ownership/role authorization.
  3. **No Evidence Metadata Table, Malware/Quarantine Status, or Upload/Download Audit Trail**: Files are saved directly to disk without a database table tracking file metadata, SHA-256 hash, uploader identity, malware scan / quarantine status (`CLEAN`, `QUARANTINED`, `PENDING_SCAN`), or download audit logs.
  4. **Unthrottled Staff Upload Endpoint**: `POST /api/staff/incidents/evidence-upload` does not invoke any rate limiter.

---

## 6. Security, Authorization & Rate-Limiting Architecture

### 6.1 Authentication & Role-Based Authorization (`SecurityConfig.java`)
- **Stateful Session Security**: Uses `HttpSessionSecurityContextRepository` and `SessionValidationFilter` (validates active user status and role changes on every request).
- **CSRF Protection**: Enabled across all state-changing endpoints; only external payment/payout webhooks (`/api/payments/payos/webhook`, `/api/payments/payos/payout/webhook`) are CSRF-exempt (`SecurityConfig.java:115-117`) and protected by HMAC-SHA256 signature verification.
- **URL & Method Access Control (`@EnableMethodSecurity`)**:
  - `/admin/**`, `/api/admin/**` -> `hasRole("ADMIN")`
  - `/manager/**`, `/api/manager/**` -> `hasAnyRole("MANAGER", "ADMIN")`
  - `/staff/**`, `/api/staff/**` -> `hasAnyRole("STAFF", "MANAGER", "ADMIN")`
  - `/api/customer/bank-accounts/**` -> `authenticated()`
  - `/api/refunds/public/**` -> `permitAll()` (uses `orderCode + purchaseEmail` verification, plus owner check if `booking.user != null`).
- **Cinema Branch Scope & Maker-Checker Enforcement**:
  - `RefundService`, `RefundApprovalTxService`, `PayoutEligibilityService`, and `IncidentService` enforce branch isolation: a `MANAGER` or `STAFF` can only view or act on records where `record.cinemaId == actor.cinemaId`.
  - Maker-checker rules prevent self-approval (`actorUserId == customerUserId`) and prevent the Stage 1 business reviewer from self-approving Stage 2 payout (`adminUserId == reviewedBy.userId`).

### 6.2 Existing Rate-Limiting Infrastructure
Two separate in-memory rate limiters currently exist:
1. **`AuthRateLimitService.java` (`com.boxcinema.auth.service.AuthRateLimitService`)**:
   - Fixed-window counter map (`ConcurrentHashMap<String, WindowState>`) used for login, registration, OTP, forgot-password, and Google link endpoints.
   - Throws `RateLimitExceededException` carrying `retryAfterSeconds`.
2. **`RefundService.enforcePublicRateLimit` (`RefundService.java:1701-1714`)**:
   - Sliding-window timestamp deque (`ConcurrentHashMap<String, Deque<Long>>`) used only on 5 public methods (`lookupPublicOrder`: 30/min/IP; `submitPublicRefundRequest`: 10/5min/IP+email; `lookupPublicRefundStatus`: 30/min/IP; `replyToRefundRequest`: 15/min/IP; `updatePublicRefundBankAccount`: 15/min/IP; `checkPublicUploadRateLimit`: 20/5min/IP).
   - **Critical Gaps**:
     - Throws `BusinessException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", ...)` which `GlobalExceptionHandler` serializes as HTTP `429` JSON **without** a `Retry-After` HTTP header.
     - Does **not** enforce the required **`5 requests / customer / 10 minutes`** and **`20 requests / IP / hour`** thresholds on refund request submission, nor dedicated rate limits on evidence uploads, appeal submissions, and authenticated customer refund endpoints.

---

## 7. Reusable Components

The working tree already contains substantial, well-tested building blocks that should be **reused and extended** rather than rewritten from scratch:

| Component | File Path | Reusable Capability |
|---|---|---|
| **Bank Encryption & Masking** | `src/main/java/com/boxcinema/common/service/BankDataEncryptionService.java` | AES-256-GCM encryption (`GCM1:` prefix + 12-byte IV), HMAC-SHA256 account hashing, `****1234` masking, account holder ASCII normalization, 45-bank VietQR BIN registry. |
| **Customer Saved Bank Accounts** | `src/main/java/com/boxcinema/refund/service/CustomerBankAccountService.java` | Owner-isolated CRUD, single-default virtual column lock (`uk_cba_single_default`), confirmation check. |
| **PayOS Payout HMAC Signer** | `src/main/java/com/boxcinema/common/util/PayOsPayoutSignatureUtil.java` | Deep-sorted key canonicalization, URI component encoding (`+ -> %20`), constant-time HMAC-SHA256 verification. |
| **PayOS Payout 3-Step Engine** | `src/main/java/com/boxcinema/refund/service/PayOsPayoutService.java` | Tx1 lock & `PENDING` record -> Outside-TX HTTP call (`POST /v1/payouts`, `GET /v1/payouts`, `GET /v1/payouts-account/balance`) -> Tx2 finalize (`SUCCEEDED`, `PROCESSING`/`UNKNOWN_OUTCOME`, `FAILED`), webhook deduplication, 30s scheduled reconciliation, `TransportExecutor` test seam. |
| **Hamilton Largest-Remainder Allocator** | `src/main/java/com/boxcinema/common/service/impl/PurchaseSnapshotService.java` | `allocateProportional(long, long[])` using `BigInteger.divideAndRemainder`, purchase-time order snapshots, partial-quantity refund math. |
| **Independent Voucher Recovery** | `src/main/java/com/boxcinema/refund/service/VoucherRecoveryService.java` | `REQUIRES_NEW` isolated single-use private `Promotion` generation + atomic unredeemed voucher revocation on cancel/hold. |
| **Canonical Lock Ordering** | `src/main/java/com/boxcinema/refund/service/RefundApprovalTxService.java` | Deadlock-free pessimistic lock ordering (`Booking FOR UPDATE -> RefundRequest FOR UPDATE -> RefundRequestItems FOR UPDATE -> Tickets FOR UPDATE`). |
| **Durable Email Outbox** | `src/main/java/com/boxcinema/refund/service/RefundEmailService.java` | Deduplicated (`uk_email_outbox_dedup`) transactional HTML emails via `email_outbox` + `EmailOutboxWorkerService`. |
| **DOM-Safe Frontend Modules** | `src/main/resources/static/js/modules/refund-ops.js`, `contact-refund.js`, `customer-bank-accounts.js` | Zero-`innerHTML` UI components for Customer, Manager, and Admin refund/payout/incident operations. |

---

## 8. Required Changes — Exact Gap List by Stage (Stages 1–4)

### Stage 1: Database Migrations, Core Domain Models, Deadlines, Limits, Voucher Constraints & Appeals Schema
1. **Refund Deadlines (`Asia/Ho_Chi_Minh`) & Incident Notification Timestamp**:
   - Add `customer_notified_at DATETIME NULL` (and affected scope metadata if missing) to `incident_reports`.
   - Add deadline tracking columns to `refund_requests` (e.g., `refund_deadline_at DATETIME NULL`, `is_late_incident_eligible BOOLEAN NOT NULL DEFAULT FALSE`, `refund_method VARCHAR(30) NOT NULL DEFAULT 'BANK_PAYOUT'`).
   - Enforce in `RefundService`:
     - Standard refund deadline: **`showtime.end_at + 72 hours`** in `Asia/Ho_Chi_Minh`.
     - Late-incident refund deadline when linked to a verified incident with `customer_notified_at`:  
       **`deadline = min(showtime_end + 30 days, max(showtime_end + 72 hours, customer_notified_at + 7 days))`** in `Asia/Ho_Chi_Minh`.
     - Keep `USED` / `CHECKED_IN` tickets reviewable when backed by a verified incident rather than auto-rejecting at intake.
2. **Incident Management (`manager_admin`) Hardening**:
   - Restrict incident creation/management in `IncidentService` and controllers to `MANAGER` (scoped to their assigned `cinema_id`) and `ADMIN` (all cinemas), or ensure `manager_admin` governance rules are strictly enforced.
   - Prevent physical deletion of any `IncidentReport` linked to one or more `RefundRequest` rows (change/enforce FK `ON DELETE RESTRICT` semantics + explicit service guard; zero auto-refund on incident creation).
3. **Configurable Two-Stage Approval Limits & Atomic Financial Quota Reservations**:
   - Add configurable limit properties (and/or policy table) with initial defaults:
     - `max_per_request = 500,000 VND`
     - `max_per_manager_per_day = 2,000,000 VND` (`Asia/Ho_Chi_Minh` calendar day)
     - `max_per_cinema_per_day = 5,000,000 VND` (`Asia/Ho_Chi_Minh` calendar day)
     - `max_per_incident_total = 10,000,000 VND`
   - Create an atomic financial quota reservation ledger/table (e.g., `refund_quota_reservations` / `refund_limit_ledgers`) tracking `RESERVED`, `COMMITTED`, and `RELEASED` amounts per `(refund_request_id)` across Manager/Day (`Asia/Ho_Chi_Minh` `quota_date`), Cinema/Day (`quota_date`), and Incident (`incident_report_id`):
     - When approved into payout queue / in-flight: atomically reserve quota (`RESERVED`) under row lock, verifying `committed + reserved + current <= limit`.
     - When payout reaches `SUCCESS`: transition reservation `RESERVED -> COMMITTED` (never double-counted).
     - When payout fails/cancelled/rejected: transition reservation `RESERVED -> RELEASED` (restoring available quota for the anchored `quota_date`).
     - Midnight `Asia/Ho_Chi_Minh` rollover: anchor each reservation to the `Asia/Ho_Chi_Minh` local date of approval so cross-midnight transitions never corrupt the new day's quota or double-count.
   - Support **Admin Limit Overrides**: when a request exceeds `500k/req`, `2M/mgr/day`, `5M/cinema/day`, or `10M/incident`, Manager approval is blocked (or escalated), and only `ADMIN` can approve with an explicit override flag, mandatory override reason, and `RefundAuditLog` entry recording the exact breached threshold(s).
4. **One-Voucher-Per-Order DB Unique Constraint & Largest Remainder Per-Ticket Tie-Breaker**:
   - Add an explicit one-voucher-per-order DB constraint (`order_voucher_redemptions` with `UNIQUE (booking_id)` or equivalent DB-enforced unique constraint on order voucher linkage) after running an automated pre-migration historical violation scan.
   - Ensure `PurchaseSnapshotService` per-ticket voucher allocation uses integer VND Largest Remainder Method with deterministic **`ticket_id` (`ticket_item_id`) ascending tie-breaker**, guaranteeing `sum(allocated_discount) == total_voucher_discount` and `refundable_amount = gross_price - allocated_discount`.
   - Enforce partial ticket refund vs voucher restoration rules in a separate transaction (`VoucherRecoveryService`) with full audit logging.
5. **Separate Customer Refund Appeals Schema & Workflow**:
   - Create a dedicated `refund_appeals` table (`RefundAppeal` entity) linked to `refund_requests` with states:
     - `APPEAL_SUBMITTED`
     - `APPEAL_UNDER_REVIEW`
     - `APPEAL_ACCEPTED` -> atomically transitions original `RefundRequest` from `REJECTED` back to `UNDER_REVIEW` and logs history/audit
     - `APPEAL_REJECTED` -> original `RefundRequest` remains `REJECTED`
6. **Canonical Refund State Machine Alignment**:
   - Align `RefundRequest` state transitions and status constants with the required state machine:
     `REQUESTED`, `UNDER_REVIEW`, `APPROVED`, `REJECTED`, `PAYOUT_PENDING`, `PROCESSING`, `SUCCESS`, `FAILED`, `UNKNOWN`, `PAYOUT_BLOCKED`, `CANCELLED` (while maintaining clean compatibility/mapping for existing columns and queries).

### Stage 2: PayOS Payout Adapter, Transfer Fee Guarantee, Security, Evidence Hardening & Rate Limiting
1. **Refund Method Priority & `PayoutProviderAdapter` / `PayoutFeeAssessment`**:
   - Implement refund method resolution: attempt `ORIGINAL_PAYMENT_METHOD` first if supported by the payment provider; since PayOS Collection (VietQR bank transfer) does not support automated card/gateway chargeback reversal, record the fallback to `BANK_PAYOUT` explicitly in the refund record and audit log.
   - Define `PayoutProviderAdapter` and `PayoutFeeAssessment` (`feeMode`: `SENDER_PAYS`, `RECIPIENT_DEDUCTED`, `NO_FEE`, `UNKNOWN`, `UNVERIFIED`; `approvedRefundVnd`, `estimatedFeeVnd`, `netRecipientVnd`, `totalDebitVnd`, `guaranteedFullReceipt`).
   - Enforce the **100% Customer Receipt Guarantee**:
     - Customer must receive 100% of `approvedCashTotal`.
     - If fee assessment returns `UNKNOWN`, `UNVERIFIED`, or `RECIPIENT_DEDUCTED` without verified sender-paid gross-up capability, **block payout dispatch** and transition the refund request to **`PAYOUT_BLOCKED`** with an explicit audit log and operator alert.
2. **`LIVE_PAYOUT_ENABLED=false` Hard Safety Gate**:
   - Add `@Value("${app.refund.live-payout-enabled:${LIVE_PAYOUT_ENABLED:false}}")` (default `false` in `application.properties`).
   - Require **both** `LIVE_PAYOUT_ENABLED=true` **and** `payos.payout.mode=LIVE` (plus valid separate credentials and verified fee assessment) before any live HTTP payout request can be sent to PayOS.
3. **Evidence Upload Security Hardening**:
   - Reject double/multiple extensions (e.g., `file.php.jpg`, `file.html.png`, any filename with >1 dot or control/path characters) in `EvidenceStorageService`.
   - Create an `evidence_files` metadata table (or audit record) tracking `id`, `stored_filename`, `original_filename_sanitized`, `content_type`, `file_size_bytes`, `sha256_hash`, `scan_status` (`CLEAN`, `QUARANTINED`, `REJECTED`), `uploaded_by_user_id`, `uploaded_by_ip`, `refund_request_id` / `incident_report_id`, `created_at`.
   - Replace unauthenticated `GET /api/refunds/public/evidence/{filename}` with **authenticated/authorized download** (verifying session role `MANAGER`/`ADMIN`/`STAFF` of the branch or verified refund owner/lookup token, verifying `scan_status == 'CLEAN'`, and writing an audit log entry).
4. **Backend Rate Limiting (`429 Too Many Requests` + `Retry-After` Header)**:
   - Implement a dedicated `RefundRateLimitService` (or enhance `GlobalExceptionHandler` + rate limiter) that throws a typed `RateLimitExceededException(message, retryAfterSeconds)` and sets the HTTP **`Retry-After: <seconds>`** header on every `429` response.
   - Enforce exact thresholds:
     - Customer refund request submission: **`5 requests / customer / 10 minutes`** AND **`20 requests / IP / hour`**.
     - Evidence file upload: rate-limited per customer and per IP (on both customer and staff upload endpoints).
     - Refund appeal submission & public lookup/reply endpoints: rate-limited per customer/email and per IP with `Retry-After`.

### Stage 3: Frontend Customer, Manager & Admin Real API Wiring
1. **Customer UI (`booking-detail.html`, `booking-history.html`, `contact.html`, `contact-refund.js`)**:
   - Wire authenticated customer refund submission and status tracking directly from `booking-detail.html` / `booking-history.html` (as well as `/contact`), showing per-ticket allocated voucher discount, net refundable cash, `Asia/Ho_Chi_Minh` refund deadline (`72h` or late-incident formula), and `USED`/`CHECKED_IN` incident-review notice.
   - Add Customer **Refund Appeal Submission UI** when a refund request is in `REJECTED` state, displaying appeal status (`APPEAL_SUBMITTED`, `APPEAL_UNDER_REVIEW`, `APPEAL_ACCEPTED`, `APPEAL_REJECTED`).
2. **Manager UI (`manager/dashboard.html`, `refund-ops.js`)**:
   - Display real-time **Manager & Cinema Daily Financial Quota bar** (`2,000,000 VND / manager / day`, `5,000,000 VND / cinema / day`, `500,000 VND / request`, `10,000,000 VND / incident` with Committed, Reserved, and Available VND in `Asia/Ho_Chi_Minh`).
   - Add cinema-scoped **Incident Management (`manager_admin`) UI** with `customer_notified_at` input and linked-refund delete protection.
3. **Admin UI (`admin/refunds.html`, `admin/incidents.html`, `refund-ops.js`)**:
   - Display **Admin Limit Override** controls (highlighting breached Manager/Cinema/Incident/Per-Request limits and requiring an explicit override reason).
   - Display **Transfer Fee Assessment & `PAYOUT_BLOCKED` banner** and `LIVE_PAYOUT_ENABLED` mode badge in the One-Click & Bulk Payout confirmation modal.
   - Add **Customer Refund Appeals Review tab/panel** (`APPEAL_SUBMITTED` -> `APPEAL_UNDER_REVIEW` -> `APPEAL_ACCEPTED` / `APPEAL_REJECTED`).

### Stage 4: End-to-End Verification, Concurrency & Adversarial Testing
- Unit, integration, and concurrency tests covering:
  - `Asia/Ho_Chi_Minh` 72h & late-incident deadline boundary cases.
  - Concurrent Manager approvals racing against the `2M/manager/day`, `5M/cinema/day`, and `10M/incident` limits, plus midnight `Asia/Ho_Chi_Minh` rollover and failure release (`RESERVED -> RELEASED`).
  - Admin override audit logging.
  - Largest Remainder Method per-ticket allocation with `ticket_id` tie-breaker and `UNIQUE(booking_id)` one-voucher-per-order enforcement.
  - Separate Refund Appeal lifecycle (`REJECTED -> APPEAL_SUBMITTED -> APPEAL_UNDER_REVIEW -> APPEAL_ACCEPTED -> UNDER_REVIEW`).
  - Transfer fee assessment (`SENDER_PAYS` vs `UNKNOWN`/`UNVERIFIED` -> `PAYOUT_BLOCKED`) and `LIVE_PAYOUT_ENABLED=false` guard.
  - Evidence upload double-extension/SVG/HTML/magic-byte rejection, authenticated download, and `429` + `Retry-After` rate limiting.

---

## 9. Historical Data Issues & Risks to Verify

1. **Historical Status Values in `refund_requests`**:
   - Verified by `@db-ops`: `refund_requests` currently has `1` row in local MySQL `box_cinema`, and `payos_payout_transactions` has `0` rows.
   - **Risk**: Existing integration tests (`RefundAndIncidentIntegrationTest`, `RefundPayoutBatchWorkflowIntegrationTest`, `Wave8RefundAndSecurityIntegrationTest`) assert against legacy status names (`PENDING_REVIEW`, `AWAITING_PAYOUT_APPROVAL`, `PAYOUT_APPROVED`, `PAYOUT_SUCCEEDED`, `COMPLETED`) OR new canonical state names (`REQUESTED`, `UNDER_REVIEW`, `APPROVED`, `REJECTED`, `PAYOUT_PENDING`, `PROCESSING`, `SUCCESS`, `FAILED`, `UNKNOWN`, `PAYOUT_BLOCKED`, `CANCELLED`).
   - **Mitigation**: Migration V7 must safely normalize any existing `refund_requests` rows, and the service/DTO layer should either expose the canonical state machine (`REQUESTED`, `UNDER_REVIEW`, `APPROVED`, `REJECTED`, `PAYOUT_PENDING`, `PROCESSING`, `SUCCESS`, `FAILED`, `UNKNOWN`, `PAYOUT_BLOCKED`, `CANCELLED`) with clear mapping or update tests and endpoints consistently so zero regressions occur.
2. **Historical Voucher Redemptions on `bookings`**:
   - Verified by `@db-ops`: Only 1 of 16 rows in `bookings` uses a promotion (`voucher_code_snapshot = 'BOXDUO'`), and 0 orders have duplicate vouchers.
   - **Mitigation**: Migration V7 will include an idempotent pre-check query before adding the one-voucher-per-order unique constraint so it never fails silently or corrupts historical data.
3. **Missing Local ClamAV / Redis Services**:
   - Verified by `@db-ops`: Neither `Redis` nor `ClamAV` (`clamd`) is installed on the local Windows machine.
   - **Mitigation**:
     - Rate limiting must use a thread-safe in-process sliding/fixed-window engine (Spring `@Service` bean) with deterministic clock injection for tests, returning standard `429` + `Retry-After` headers without requiring an external Redis server.
     - Malware scanning for evidence uploads must use a pluggable scanner interface (`EvidenceMalwareScanner`) whose default local implementation performs synchronous deep magic-byte, embedded-script/polyglot (`<?php`, `<script`, `<svg`, `<html`, `%PDF`), and header structural validation and assigns `scan_status = 'CLEAN'` / `'QUARANTINED'`, while allowing an external ClamAV/antivirus adapter when configured.

---

## 10. Assumptions vs Verified Findings

| Topic | Initial Assumption / Question | Verified Finding in Repo & Local Environment |
|---|---|---|
| **Refund Module Existence** | Repo might only have baseline booking/payment tables and need refund built from zero. | **VERIFIED**: Migrations `v3`–`v6` and `com.boxcinema.refund.*` already implement `refund_requests`, `refund_request_items`, `payos_payout_transactions`, `customer_bank_accounts`, `incident_reports`, `voucher_recoveries`, AES-256-GCM bank encryption, Hamilton allocator, and Manager/Admin UIs. |
| **PayOS Java SDK** | Repo might use `vn.payos:payos-java` SDK. | **VERIFIED**: `pom.xml` does NOT include `payos-java`. Pay-in (`PayOsServiceImpl`) and Payout (`PayOsPayoutService`) use Spring `RestTemplate` + custom HMAC-SHA256 utilities (`CryptoUtils`, `PayOsPayoutSignatureUtil`). |
| **PayOS Transfer Fee Behavior** | Whether PayOS Payout API provides an official fee-bearer field or deducts fee from recipient. | **VERIFIED / UNVERIFIED SPLIT**: The current repo has **no** `PayoutProviderAdapter` or `PayoutFeeAssessment`. PayOS Payout `/v1/payouts` payload only takes `referenceId`, `amount`, `description`, `toBin`, `toAccountNumber`, `category`. Fee policy must be modeled via `PayoutProviderAdapter` + `PayoutFeeAssessment` and default to `PAYOUT_BLOCKED` whenever fee behavior is `UNKNOWN` or `UNVERIFIED`. |
| **Approval Financial Limits** | Repo might already check Manager/Cinema/Incident limits. | **VERIFIED**: Currently **no** financial limits (`500k/req`, `2M/mgr/day`, `5M/cinema/day`, `10M/incident`) or quota reservation tables exist in the repo. All cash refunds unconditionally go to Admin payout queue without quota tracking. |
| **Refund Deadlines (`72h` & Late Incident)** | Repo might already check showtime end + 72h. | **VERIFIED**: Currently **no** showtime deadline check exists in `RefundService.submitPublicRefundRequest` or `RefundApprovalTxService`, and `incident_reports` lacks `customer_notified_at`. |
| **Appeals Workflow** | Repo has `resolveAppealByAdmin` in `IncidentService`. | **VERIFIED**: Existing appeal in `IncidentService` is only for *Staff appealing a Manager's incident verification*. **Customer Refund Request Appeals** (`APPEAL_SUBMITTED`, `APPEAL_UNDER_REVIEW`, `APPEAL_ACCEPTED -> UNDER_REVIEW`, `APPEAL_REJECTED -> REJECTED`) do **not** exist yet. |
| **Rate Limiting `Retry-After`** | `RefundService` already has `enforcePublicRateLimit`. | **VERIFIED**: `RefundService` throws `BusinessException(429)` **without** setting the `Retry-After` HTTP header, and uses `10/5m` instead of `5/customer/10m` and `20/IP/h`. |
| **Evidence Download Security** | `EvidenceStorageService` stores files outside webroot with magic-byte check. | **VERIFIED**: Magic-byte check and outside-webroot storage exist, **but** double extensions (`a.php.jpg`) are not blocked, `/api/refunds/public/evidence/{filename}` is `permitAll()` (unauthenticated), and there is no malware/quarantine status table or download audit log. |
