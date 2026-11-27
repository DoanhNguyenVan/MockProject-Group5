# BOX Cinema — Movie Ticket QR Migration Report (Ngrok to Render)

## 1. Executive Summary
- **Scope**: Migrated the **Movie Ticket QR** generation, public verification, and staff gate check-in workflow from Ngrok (`https://absence-turret-chapped.ngrok-free.dev`) to the Render HTTPS deployment domain (`https://box-cinema.onrender.com`), strictly isolated from payment QR (PayOS/VietQR), payment callbacks, refunds, and authentication flows.
- **Status**: **Local implementation and automated verification `PASS` (74/74 targeted Ticket QR, WebMvc Security, and regression tests passed). Live Render deployment of the newly modified code is `BLOCKED` (awaiting user `git push` / Render redeploy because no Render CLI or deployment token is configured in this IDE environment; existing Render service `https://box-cinema.onrender.com/scan` verified reachable with `HTTP 200 OK`).**

---

## 2. Original Architecture
- **Spring Boot Application**: Monolith Spring Boot `3.3.5` / Java `17` web application packaged via `Dockerfile` (`maven:3.9-eclipse-temurin-17` builder + `eclipse-temurin:17-jre-jammy` runtime) with Thymeleaf UI + REST controllers connected to MySQL locally and TiDB Cloud in production.
- **Original Ngrok Dependency**:
  - `src/main/resources/application.properties` defaulted `app.public-base-url` to `https://absence-turret-chapped.ngrok-free.dev`.
  - `BookingQrServiceImpl` and `TicketServiceImpl` injected `@Value("${app.public-base-url:https://absence-turret-chapped.ngrok-free.dev}")`.
  - Because `BOX-Cinema.env` on Render did not define `PUBLIC_BASE_URL` or `APP_PUBLIC_BASE_URL`, newly issued `BookingQr` entries encoded `https://absence-turret-chapped.ngrok-free.dev/t/<token>` inside the ZXing QR image and `booking_qr.ticket_url`.
  - Public endpoints `/t/{token}` (`PublicTicketViewController`) and `/api/public/tickets/{token}` (`PublicTicketApiController`) also permitted sequential `BOX-YYYYMMDD-NNNN` booking-code lookups, did not surface full ticket state (`USED`, `REFUNDED`, `CANCELLED`, `EXPIRED`) or `CONFIRM TICKET USED` controls on `/t/{token}`, and `scanner.html` redirected directly to arbitrary `http(s)://` URLs decoded from QR payloads.

---

## 3. Modified Files
### Production Code & Configuration
- `src/main/resources/application.properties`: Introduced dedicated `app.ticket.public-base-url=${TICKET_PUBLIC_BASE_URL:${PUBLIC_BASE_URL:${APP_PUBLIC_BASE_URL:https://box-cinema.onrender.com}}}` and replaced the Ngrok fallback on `app.public-base-url` with `https://box-cinema.onrender.com`. Payment/PayOS (`payos.*`), Refund (`app.refund.*`), and OAuth2 properties were not modified.
- `src/main/resources/application-local.properties.example`: Documented optional `# app.ticket.public-base-url=http://localhost:8080` override for local development.
- `src/main/java/com/boxcinema/common/service/impl/BookingQrServiceImpl.java`: Switched QR URL generation to `app.ticket.public-base-url`, rejected Ngrok domains in `resolveTicketBaseUrl()`, and updated `ensureQrFileExists()` / `reconstructAndPersistQrPng()` to transparently migrate existing Ngrok/localhost `ticket_url` records to the Render HTTPS URL while preserving the original encrypted token (`token_encrypted`) and `token_hash`.
- `src/main/java/com/boxcinema/customer/service/impl/TicketServiceImpl.java`: Removed the unused Ngrok-defaulted `publicBaseUrl` field.
- `src/main/java/com/boxcinema/common/config/SchemaCompatibilityInitializer.java`: Added idempotent, non-destructive column compatibility checks (`ensureColumnExists`) for `booking_qr` (`used_at`, `revoked_at`, `ticket_url`, `qr_image_path`).
- `src/main/java/com/boxcinema/common/repository/BookingQrRepository.java`: Added atomic conditional update `markUsedIfActive(qrId, now)` requiring `UPPER(q.status) = 'ACTIVE'`.
- `src/main/java/com/boxcinema/common/repository/TicketRepository.java`: Added atomic conditional update `markBookingTicketsUsed(bookingId, now, staff)` requiring `UPPER(t.ticketStatus) = 'VALID'`.
- `src/main/java/com/boxcinema/publicsite/dto/response/PublicTicketDto.java`: Added `confirmable`, `ticketStatus`, `statusReason`, `checkedInAt`, `cinemaAddress`, and `ticketCodes`.
- `src/main/java/com/boxcinema/publicsite/service/impl/PublicTicketServiceImpl.java`: Removed unauthenticated sequential booking-code (`BOX-YYYYMMDD-NNNN`) enumeration; enforced strict `BOX1.<token>` cryptographic lookup via `SHA-256(token)`; added `evaluateTicketState(...)` evaluating `REFUNDED`, `CANCELLED`, `USED`, `REVOKED`, `EXPIRED`, `VALID`, and showtime cancellation without exposing PII.
- `src/main/java/com/boxcinema/publicsite/controller/PublicTicketViewController.java`: Added authenticated staff authority detection (`isStaff`) and `ticketToken` model binding for `/t/{token}`.
- `src/main/java/com/boxcinema/staff/service/impl/StaffCheckinServiceImpl.java`: Enforced explicit `STAFF`/`MANAGER`/`ADMIN` role check, pessimistic row locking on `BookingQr`/`Booking`/`Ticket`, validation of `REFUNDED`, `CANCELLED`, `SHOWTIME_CANCELLED`, `EXPIRED`, `REVOKED`, `USED`, atomic `markUsedIfActive` + `markBookingTicketsUsed` updates, and `BookingCheckin` insert.
- `src/main/java/com/boxcinema/staff/controller/StaffCheckinApiController.java`: Enforced authenticated staff ID check and returned accurate HTTP status codes (`200 OK`, `409 Conflict`, `404 Not Found`, `400 Bad Request`, `401 Unauthorized`, `403 Forbidden`).
- `src/main/resources/templates/public/ticket-view.html`: Preserved the existing card design while adding movie poster thumbnail, cinema address, ticket code(s), accurate ticket status badges (`VALID`, `USED`, `REFUNDED`, `CANCELLED`, `EXPIRED`, `REVOKED`), and staff-only `CONFIRM TICKET USED` action calling `/api/staff/bookings/check-in`.
- `src/main/resources/templates/public/scanner.html`: Removed Ngrok comment reference and restricted scanned URL handling to `/t/BOX1.<token>` extraction / same-origin navigation to prevent open redirects.
- `src/main/resources/templates/staff/checkin.html`: Updated seat badge rendering (`data.seats || data.seatLabels || data.seatsCheckedIn`) to match `StaffCheckinResponseDto`.

### Automated Tests Added
- `src/test/java/com/boxcinema/common/service/impl/BookingQrServiceImplTest.java`
- `src/test/java/com/boxcinema/publicsite/service/impl/PublicTicketServiceImplTest.java`
- `src/test/java/com/boxcinema/publicsite/controller/PublicTicketControllerTest.java`
- `src/test/java/com/boxcinema/publicsite/controller/TicketQrSecurityWebMvcTest.java`
- `src/test/java/com/boxcinema/staff/service/impl/StaffCheckinServiceImplTest.java`
- `src/test/java/com/boxcinema/staff/controller/StaffCheckinApiControllerTest.java`

---

## 4. Database Changes
- **No destructive schema changes** (no `DROP`, `TRUNCATE`, `DELETE`, or record invalidation).
- `SchemaCompatibilityInitializer` non-destructively ensures the following nullable columns already mapped by `BookingQr.java` exist on `booking_qr` (`information_schema.columns` check -> `ALTER TABLE booking_qr ADD COLUMN ... NULL` only if absent):
  - `used_at DATETIME NULL`
  - `revoked_at DATETIME NULL`
  - `ticket_url VARCHAR(500) NULL`
  - `qr_image_path VARCHAR(500) NULL`
- **Legacy Ngrok Record Handling**: Existing `booking_qr` rows with `ngrok` URLs are automatically migrated to `https://box-cinema.onrender.com/t/<existing_token>` when fetched or rendered via `BookingQrServiceImpl` without altering `token_hash` or invalidating existing tickets.

---

## 5. QR Migration
- **Environment Variable**: `TICKET_PUBLIC_BASE_URL` (bound to `app.ticket.public-base-url`, defaulting to `https://box-cinema.onrender.com` with fallback chain `PUBLIC_BASE_URL` / `APP_PUBLIC_BASE_URL` and no Ngrok fallback).
- **QR Payload Format**: `https://box-cinema.onrender.com/t/BOX1.<256-bit-SecureRandom-Base64URL-token>` generated via ZXing (`QrCodeServiceImpl`, `300x300` PNG).
- **Ephemeral Render Disk Safety**: If the container restarts on Render and `./uploads/booking-qr/qr-booking-<id>.png` is absent, `BookingQrServiceImpl.getBookingQrPngBytes()` reconstructs the exact PNG deterministically from `token_encrypted` + `app.ticket.public-base-url` (never rotating/invalidating the customer's token).

---

## 6. Ticket Display
- Scanning the ticket QR opens `GET /t/{token}` (`PublicTicketViewController` -> `public/ticket-view.html`) directly on any desktop, Android, or iOS browser without requiring customer login.
- Displays: Movie Title, Movie Poster (if present), Cinema Name, Cinema Address (if present), Auditorium/Screen Room, Screening Date, Screening Time, Seat Numbers, Ticket Code(s), Booking Code, Payment Status, Food & Concessions, and accurate Ticket Status (`VALID`, `USED`, `CANCELLED`, `REFUNDED`, `EXPIRED`, `REVOKED`).
- Invalid/tampered/unknown tokens return HTTP `404` on `/api/public/tickets/{token}` and render the `Ticket Not Found` state cleanly on `/t/{token}`.

---

## 7. Staff Confirmation
- Logged-in staff (`ROLE_STAFF`, `ROLE_MANAGER`, `ROLE_ADMIN`) viewing a valid confirmable ticket on `/t/{token}` see the **Staff Gate Verification** panel with the **`CONFIRM TICKET USED`** button.
- Clicking **`CONFIRM TICKET USED`** sends `POST /api/staff/bookings/check-in` with CSRF token header and `{ "qrPayload": "<token>" }`.
- Backend (`StaffCheckinServiceImpl.checkinByQrToken`) runs inside `@Transactional`:
  1. Verifies authenticated staff role (`STAFF`/`MANAGER`/`ADMIN`).
  2. Acquires `PESSIMISTIC_WRITE` locks on `BookingQr`, `Booking`, and `Ticket`.
  3. Validates token existence, active QR status, `CONFIRMED` booking status, absence of `REFUNDED` payment/ticket status, absence of booking/showtime cancellation, showtime expiry (`expires_at` / `end_at + 4h`), and `VALID` ticket status.
  4. Executes conditional atomic SQL updates `markUsedIfActive` (`BookingQr`) and `markBookingTicketsUsed` (`Ticket`) plus unique-constrained `BookingCheckin` insert recording timestamp and confirming staff member.
  5. Rejects duplicate/concurrent confirmations with `409 Conflict` (`BOOKING_ALREADY_CHECKED_IN`) and never reports success unless DB updates succeed.

---

## 8. Security
- **Enumerating Tickets via `/t/{token}` Prevented**: Removed sequential `BOX-YYYYMMDD-NNNN` fallback from `PublicTicketServiceImpl`; only `SHA-256` matches against 256-bit `BOX1.<token>` secrets succeed.
- **Role & CSRF Enforcement**: `SecurityConfig` restricts `/api/staff/**` and `/staff/**` to `STAFF`, `MANAGER`, `ADMIN`; `StaffCheckinApiController` enforces `@PreAuthorize` + non-null `staffUserId`; `StaffCheckinServiceImpl` re-verifies staff role; `ticket-view.html` omits the confirmation button and script completely for unauthenticated visitors and `ROLE_USER` customers.
- **PII Protection**: Public `/t/{token}` and `/api/public/tickets/{token}` responses omit customer email, phone, user ID, password hashes, raw token hashes, and internal DB primary keys.
- **Open Redirect Mitigation**: `scanner.html` validates decoded QR URLs and only navigates to `/t/<BOX1_token>` or same-origin `/t/...` paths.
- **Remaining Operational Risk**: Staff authentication uses browser session cookies (`JSESSIONID`). After logging in once on `https://box-cinema.onrender.com/login`, scanning QRs opens `/t/{token}` authenticated in that browser; if an unauthenticated staff browser scans first, clicking `"Sign in"` goes through the existing `/login` flow which redirects `ROLE_STAFF` to `/staff` (left untouched per login scope restriction), after which scanning the QR opens `/t/{token}` authenticated.

---

## 9. Automated Tests
Executed offline Maven test command after final edit:
`mvn.cmd test -o "-Dtest=BookingQrServiceImplTest,PublicTicketServiceImplTest,PublicTicketControllerTest,TicketQrSecurityWebMvcTest,StaffCheckinServiceImplTest,StaffCheckinApiControllerTest,EmailServiceImplTest,BookingContextApiControllerTest,BookingPageControllerTest,QuickBookingApiControllerTest,V12AuthSecurityUnitTest,AdminPageControllerTest,CloudinaryMediaClientTest,MovieMediaUploadServiceImplTest,AdminMovieControllerUploadTest,ProfileAvatarServiceImplTest,AuthControllerAvatarTest"`

**Result: `PASS` — 74/74 tests passed (0 failures, 0 errors, 0 skipped)**

| Test Class | Scope | Run | Failures | Status |
|---|---|---:|---:|---|
| `BookingQrServiceImplTest` | QR generation, Render HTTPS URL, zero Ngrok, legacy Ngrok migration, token uniqueness | 4 | 0 | **PASS** |
| `PublicTicketServiceImplTest` | Valid token details, enumeration rejection, `REFUNDED`/`CANCELLED`/`EXPIRED`/`USED` states | 3 | 0 | **PASS** |
| `PublicTicketControllerTest` | Staff vs guest/customer model binding, `/api/public/tickets/{token}` HTTP statuses | 2 | 0 | **PASS** |
| `TicketQrSecurityWebMvcTest` | Full `SecurityConfig` + Thymeleaf HTML render (`CONFIRM TICKET USED`, `USED` UI, `401`/`403`/`200`, `/scan` zero-Ngrok) | 4 | 0 | **PASS** |
| `StaffCheckinServiceImplTest` | Atomic check-in, unauthorized rejection, concurrency race check, refunded/cancelled/expired rejection, DB failure propagation | 5 | 0 | **PASS** |
| `StaffCheckinApiControllerTest` | HTTP `200`/`409`/`404`/`400`/`401`/`403` controller contract | 2 | 0 | **PASS** |
| `EmailServiceImplTest`, `BookingContextApiControllerTest`, `BookingPageControllerTest`, `QuickBookingApiControllerTest`, `V12AuthSecurityUnitTest`, `AdminPageControllerTest`, `CloudinaryMediaClientTest`, `MovieMediaUploadServiceImplTest`, `AdminMovieControllerUploadTest`, `ProfileAvatarServiceImplTest`, `AuthControllerAvatarTest` | Non-DB unit/MockMvc regression | 54 | 0 | **PASS** |
| `@SpringBootTest` integration suites (`V12SecurityIntegrationTest`, `BaselineV1VerificationTest`, etc.) | Full MySQL integration tests | — | — | **NOT TESTED** in this run |
| `AuthControllerTest` | Ran in earlier batch (`5` pre-existing `/account-settings` vs `/profile` assertion mismatches predating this task; unrelated to Ticket QR) | 16 | 5 | **FAIL** *(pre-existing, outside scope)* |

---

## 10. Regression Verification
- **Payment QR / PayOS / VietQR**: `PASS` — Verified via `git diff` that `PayOsServiceImpl` and `payos.*` properties (`PAYOS_BASE_URL`, `PAYOS_RETURN_URL`, `PAYOS_CANCEL_URL`, `PAYOS_CHECKSUM_KEY`, collection & payout settings) were not modified.
- **Payment Callbacks & Webhooks**: `PASS` — Verified untouched via `git diff`.
- **Refund Functionality**: `PASS` — Verified refund controllers/services/properties (`app.refund.*`, payout logic) were not modified; check-in only reads `Payment.paymentStatus` to block refunded tickets.
- **Booking & Quick-Booking Regression**: `PASS` — `BookingContextApiControllerTest` (5/5), `BookingPageControllerTest` (3/3), `QuickBookingApiControllerTest` (3/3), `EmailServiceImplTest` (5/5) all passed.

---

## 11. Render Deployment
- **Deployment Architecture**: Single Spring Boot monolith (`box-cinema`, Docker runtime on Render Free Tier via `Dockerfile`, port `$PORT`, database TiDB Cloud Serverless). Both Thymeleaf frontend and REST backend are served on **`https://box-cinema.onrender.com`**.
- **Existing Service Reachability**: `PASS` — `curl.exe -sI https://box-cinema.onrender.com/scan` returned `HTTP/1.1 200 OK` (`x-render-origin-server: Render`).
- **Deploying New Changes to Render**: **`BLOCKED`** — Render CLI (`render`) is not installed and no Render API key is available in this environment; additionally, Git push was not executed automatically per rules (`Do not automatically commit or push changes unless explicitly authorized`).

---

## 12. Remaining Manual Actions
1. **Commit and push** the updated repository to GitHub `origin/main` so Render builds the new `Dockerfile` image:
   ```powershell
   git add -A
   git commit -m "feat: migrate ticket QR verification from ngrok to Render HTTPS with atomic staff confirmation"
   git push origin main
   ```
2. **Optional / Recommended in Render Dashboard → Environment**: Add environment variable:
   ```text
   TICKET_PUBLIC_BASE_URL=https://box-cinema.onrender.com
   ```
   *(Already defaulted in `application.properties`, so configuring it explicitly on Render ensures future environment overrides stay explicit without redeploying code.)*
3. **Wait for Render deploy** to reach **Live** status and confirm startup logs show `SchemaCompatibilityInitializer` completed without errors.

---

## 13. Production Testing
1. On a phone or PC browser, verify `https://box-cinema.onrender.com/scan` loads cleanly (`200 OK`, no Ngrok references).
2. Log in at `https://box-cinema.onrender.com/login` using a `STAFF` (or `MANAGER`/`ADMIN`) account in the browser that will scan tickets at the gate.
3. Purchase or issue a test confirmed booking (via customer checkout or `/staff/pos`) and download/view the customer e-ticket QR (`/booking/ticket/{id}` or email attachment).
4. Scan the QR code using a smartphone camera or webcam at `/scan` — verify the QR decodes to `https://box-cinema.onrender.com/t/BOX1....` (never Ngrok, never `localhost`).
5. Confirm the opened page shows movie poster, title, cinema name & address, room, date, time, seats, ticket code(s), booking reference, `VALID TICKET` status, and the **`CONFIRM TICKET USED`** button.
6. Click **`CONFIRM TICKET USED`** — verify the status changes to **`USED`** with timestamp and staff name.
7. Refresh `/t/BOX1....` or scan the same QR a second time — verify **`USED`** status banner appears and confirmation button is no longer offered (duplicate check-in rejected).
8. Open the same `/t/BOX1....` URL in an Incognito/unauthenticated browser — verify ticket info is viewable without customer login and **`CONFIRM TICKET USED`** button is NOT visible.
