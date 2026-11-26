# RESUME SWARM INSTRUCTION — BOX CINEMA REFUND & PAYOS PAYOUT

Copy and paste the prompt block below into a new Antigravity Swarm session (`/swarm`) tomorrow once your AI quota has refreshed.

---

```text
/swarm

Resume the **BOX Cinema — Refund & PayOS Payout** implementation from the verified Stage 1 emergency checkpoint on branch `feat/refund-payos-payout` in `C:\Users\dangi\.gemini\antigravity\scratch\MockProject-Group5`.

## MANDATORY FIRST STEPS (DO NOT SKIP)
1. Read these three files completely before assigning work:
   - `docs/acceptance/refund/_progress.md` (especially the Stage 0, Stage 1, and Emergency Swarm Checkpoint entries)
   - `docs/acceptance/refund/01-current-state-discovery.md`
   - `docs/acceptance/refund/RESUME_SWARM.md`
2. Run read-only verification commands:
   - `git branch --show-current` (must be `feat/refund-payos-payout`)
   - `git log -n 3 --oneline` (verify Stage 0 commit `6fbc4f7` is present)
   - `git status --short` (verify all pre-existing user changes and uncommitted Stage 1 files are intact)
3. Inspect the model catalog (`swarm{action:"models", all:true}`) and route tasks to the strongest models with available quota (`AGENTS.md`).

## VERIFIED CHECKPOINT STATE — DO NOT REPEAT COMPLETED WORK
- **DO NOT** re-run Stage 0 (branch creation, DB discovery, Stage 0 backup/restore, `01-current-state-discovery.md`, or PayOS official doc re-discovery unless verifying a specific endpoint detail). Stage 0 is **PASS** and committed at `6fbc4f7`.
- **DO NOT** re-write or delete `database/migration_v7_refund_governance_vouchers_appeals.sql` or re-run the Stage 1 historical scan unless new schema needs emerge in Stage 2+. Both `box_cinema` (`56` tables) and `box_cinema_test` (`56` tables) already have `migration_v7` applied, `uk_ovr_booking_id` (`UNIQUE(booking_id)`) verified, and pre-migration backups saved in `C:\Users\dangi\.gemini\antigravity\scratch\boxcinema_db_backups\`.
- **DO NOT** restart the project or discard uncommitted files (`git reset --hard`, `git clean`, `git checkout --`, and `git add .` are strictly forbidden).

## EXACT RESUME ENTRY POINT (STAGE 1 COMPLETION -> STAGE 5)

### Step A — Finish & Verify Stage 1 (Immediate Entry Point)
1. Assign a QA/Backend worker to fix ONLY the two test-helper setup issues in `src/test/java/com/boxcinema/refund/Stage1VoucherCalculationAndDeadlineTest.java`:
   - Line `276`: authenticate the MockMvc `POST /api/bookings/{id}/voucher` request with `BoxCinemaUserDetails` principal (as expected by `SecurityContextUtils.resolveCurrentUserId()`) instead of generic `.with(user(...))`.
   - Line `1128` (`submitCustomerTicketRefund`): avoid `b.getUser().getEmail()` / `b.getUser().getUserId()` on a detached `Booking` (`LazyInitializationException`); use `b.getGuestEmail()` / `customer.getUserId()`.
2. Run:
   - `mvn test -Dtest=Stage1VoucherCalculationAndDeadlineTest,PurchaseSnapshotServiceTest` -> verify **13/13 PASS**.
   - Run existing voucher/payment/refund regression suites on `box_cinema_test`:
     `mvn test -Dtest=PromotionServiceTest,PayOsCollectionAndBookingIntegrationTest,RefundAndIncidentIntegrationTest,RefundPayoutBatchWorkflowIntegrationTest,PayOsPayoutWebhookResilienceIntegrationTest,PayOsPayoutAndRefundFullAuditIntegrationTest,CustomerBankAccountAndPayoutIntegrationTest,Wave8RefundAndSecurityIntegrationTest`
   - Repair any genuine regression discovered (without weakening assertions or deleting tests).
3. Record actual Maven command outputs in `docs/acceptance/refund/_progress.md` (append-only), mark **Stage 1 Gate: PASS**, and selectively commit ONLY the verified Stage 1 files (the 10 new Stage 1 main files, `Stage1VoucherCalculationAndDeadlineTest.java`, the 22 Stage 1-modified files listed in `_progress.md`, and `_progress.md` — **never** `git add .` and never commit unrelated user files).

### Step B — Execute Stage 2 (Incidents, Appeals, Limits, Reservations, Upload Security & Rate Limiting)
Use the Stage 2 schema tables already created in `migration_v7` (`refund_limit_configs`, `refund_limit_config_history`, `refund_limit_buckets`, `refund_limit_reservations`, `refund_limit_overrides`, `refund_appeals`, `refund_appeal_history`, `refund_evidence_files`, `refund_requests` columns):
1. Enforce `manager_admin` incident policy (cinema-scoped Manager/Admin incident management, `ON DELETE RESTRICT` + service guard on linked refunds, zero auto-refund).
2. Implement configurable approval/payout limits (`500,000` req / `2,000,000` mgr/day / `5,000,000` cinema/day / `10,000,000` incident), two-stage validation (at approval and immediately before payout), atomic reservation ledger (`RESERVED`/`COMMITTED`/`RELEASED`, `Asia/Ho_Chi_Minh` midnight rollover, no double-counting), and Admin limit overrides with audit.
3. Implement separate Customer Refund Appeals (`APPEAL_SUBMITTED`, `APPEAL_UNDER_REVIEW`, `APPEAL_ACCEPTED` -> original refund `UNDER_REVIEW`, `APPEAL_REJECTED` -> original stays `REJECTED`).
4. Harden `EvidenceStorageService` & download authorization: reject double extensions (`a.php.jpg`), populate `refund_evidence_files` (SHA-256 + `CLEAN`/`QUARANTINED`), remove `permitAll()` on `/api/refunds/public/evidence/{filename}`, enforce ownership/cinema authorization + audit on downloads.
5. Implement `RefundRateLimitService` + `Retry-After` HTTP header on `429`: `5/customer/10m`, `20/IP/h`, plus upload & appeal thresholds (in-process thread-safe store since Redis is not installed locally).
6. Write & pass Stage 2 authorization, concurrency, appeal, upload-security, and rate-limit tests; log to `_progress.md`; selectively commit Stage 2.

### Step C — Execute Stage 3 (PayOS Payout Adapter, Fee Policy, State Machine & Reconciliation)
1. Implement `RefundMethod` priority (`ORIGINAL_PAYMENT_METHOD` check -> `BANK_PAYOUT` fallback).
2. Implement `PayoutProviderAdapter` + `PayoutFeeAssessment` (`SENDER_PAYS`, `RECIPIENT_DEDUCTED`, `NO_FEE`, `UNKNOWN`, `UNVERIFIED`), automatic `PAYOUT_BLOCKED` guard when 100% customer receipt is unverified, and `LIVE_PAYOUT_ENABLED=false` hard kill-switch.
3. Align `PayOsPayoutService` with official PayOS Payout v1 docs (verified in `_progress.md` / `#7` during Stage 0): response header `x-signature` verification, `ON_HOLD`/`PARTIAL_COMPLETED`/`UNKNOWN` state handling, `POST /v1/payouts/estimate-credit` & `GET /v1/payouts/{id}` polling reconciliation, immutable recipient snapshot validation, and defensive handling of undocumented Payout webhooks / unverified 6xx codes.
4. Write & pass Stage 3 mock provider, fee-blocking, timeout/UNKNOWN, signature, and idempotency tests (never calling live PayOS); log to `_progress.md`; selectively commit Stage 3.

### Step D — Execute Stage 4 (Frontend Integration)
1. Wire Customer (`booking-detail.html`, `booking-history.html`, `contact.html`, `contact-refund.js`), Manager (`manager/dashboard.html`, `refund-ops.js`), and Admin (`admin/refunds.html`, `admin/incidents.html`, `refund-ops.js`) to real backend APIs for deadlines, appeals, quota bars, Admin overrides, fee/`PAYOUT_BLOCKED` banners, `LIVE_PAYOUT_ENABLED` indicator, and bulk/one-click confirmation.
2. Run Node frontend tests (`tests/frontend/*.test.mjs`) and verify UI flows; log to `_progress.md`; selectively commit Stage 4.

### Step E — Execute Stage 5 (Full Regression, Security Review, Final 5 Reports & Live-Test Runbook)
1. Run full backend + frontend regression suite and security/financial audit.
2. Complete the remaining 4 acceptance reports in `docs/acceptance/refund/` (alongside existing `01-current-state-discovery.md` so there are **exactly five** final reports):
   - `02-api-payment-authorization.md`
   - `03-testing-security-results.md`
   - `04-live-test-guide.md`
   - `05-final-acceptance-report.md`
3. Append Stage 5 Gate to `_progress.md` and selectively commit Stage 5.

## NON-NEGOTIABLE SAFETY CONSTRAINTS
- **NEVER** initiate real-money transfers or call live PayOS endpoints. Keep `LIVE_PAYOUT_ENABLED=false` and `PAYOS_PAYOUT_MODE=MOCK`.
- **NEVER** write passwords, API keys, checksum keys, tokens, or full bank account numbers into `_progress.md`, reports, or commits.
- **NEVER** run tests or migrations against `box_cinema` without verified backups; keep integration tests isolated on `box_cinema_test`.
- **NEVER** claim `PASS` without actual command output observed after the final edit.
```
