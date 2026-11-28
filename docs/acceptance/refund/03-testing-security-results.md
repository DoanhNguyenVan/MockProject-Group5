# 03 — Testing & Security Results (Refund, Governance & PayOS Payout)

- **Owner:** `qa-verifier` (independent QA)
- **Branch:** `feat/refund-payos-payout` (HEAD at time of run: Stage 4 commit `e834a68`)
- **Run date:** 2026-11-27 (Asia/Ho_Chi_Minh)
- **Safety posture during all runs:** `LIVE_PAYOUT_ENABLED=false` (default), `PAYOS_PAYOUT_MODE=MOCK`, `payos.collection.mode=MOCK`. Backend tests ran only against `box_cinema_test` (forced by `src/test/resources/application-test-override.properties`). No live PayOS endpoint was called and no real transfer was made.
- **Overall verdict:** **PASS** — 215/215 backend tests and 76/76 frontend tests green; no open refund-scope defect.

---

## 1. Full Regression Results

### 1.1 Backend — `mvn test` (entire suite)

Command:

```text
mvn test
```

Result: `Tests run: 215, Failures: 0, Errors: 0, Skipped: 0` — `BUILD SUCCESS` (total time 01:57 min).

| Scope | Test class | Run | Fail | Err | Skip |
|---|---|---|---|---|---|
| admin | `AdminPageControllerTest` | 19 | 0 | 0 | 0 |
| admin | `AdminAccountServiceTest` | 8 | 0 | 0 | 0 |
| admin | `Wave8CoreAndSecurityIntegrationTest` | 4 | 0 | 0 | 0 |
| auth | `AuthControllerTest` | 18 | 0 | 0 | 0 |
| auth | `PasswordResetControllerTest` | 5 | 0 | 0 | 0 |
| auth | `AuthenticationArchitectureIntegrationTest` | 5 | 0 | 0 | 0 |
| auth | `GoogleAutoLinkAcceptanceIntegrationTest` | 8 | 0 | 0 | 0 |
| auth | `GoogleOidcServiceTest` | 12 | 0 | 0 | 0 |
| auth | `OtpServiceTest` | 2 | 0 | 0 | 0 |
| auth | `PasswordResetServiceIntegrationTest` | 20 | 0 | 0 | 0 |
| auth | `ResetTokenServiceTest` | 2 | 0 | 0 | 0 |
| auth | `V12AuthSecurityUnitTest` | 4 | 0 | 0 | 0 |
| baseline | `BaselineV1VerificationTest` | 4 | 0 | 0 | 0 |
| baseline | `V12SecurityIntegrationTest` | 2 | 0 | 0 | 0 |
| common | `EmailServiceImplTest` | 5 | 0 | 0 | 0 |
| customer | `BookingContextApiControllerTest` | 5 | 0 | 0 | 0 |
| customer | `BookingPageControllerTest` | 3 | 0 | 0 | 0 |
| customer | `HomeControllerTest` | 1 | 0 | 0 | 0 |
| customer | `MovieDetailsControllerTest` | 2 | 0 | 0 | 0 |
| customer | `QuickBookingApiControllerTest` | 3 | 0 | 0 | 0 |
| customer | `PayOsCollectionAndBookingIntegrationTest` | 3 | 0 | 0 | 0 |
| customer | `PayOsCollectionFullAuditIntegrationTest` | 5 | 0 | 0 | 0 |
| customer | `PayOsCollectionWebhookResilienceIntegrationTest` | 3 | 0 | 0 | 0 |
| customer | `InquiryServiceImplTest` | 1 | 0 | 0 | 0 |
| customer | `PromotionServiceTest` | 5 | 0 | 0 | 0 |
| refund | `CustomerBankAccountAndPayoutIntegrationTest` | 6 | 0 | 0 | 0 |
| refund | `PayOsPayoutAndRefundFullAuditIntegrationTest` | 5 | 0 | 0 | 0 |
| refund | `PayOsPayoutSignatureAndBankSecurityTest` | 6 | 0 | 0 | 0 |
| refund | `PayOsPayoutWebhookResilienceIntegrationTest` | 2 | 0 | 0 | 0 |
| refund | `PurchaseSnapshotServiceTest` | 3 | 0 | 0 | 0 |
| refund | `RefundAndIncidentIntegrationTest` | 8 | 0 | 0 | 0 |
| refund | `RefundPayoutBatchWorkflowIntegrationTest` | 5 | 0 | 0 | 0 |
| refund | `Stage1VoucherCalculationAndDeadlineTest` | 10 | 0 | 0 | 0 |
| refund | `Stage2GovernanceLimitsAppealsSecurityTest` | 6 | 0 | 0 | 0 |
| refund | `Stage3PayOsPayoutAdapterAndFeePolicyTest` | 6 | 0 | 0 | 0 |
| refund | `Stage4FrontendTemplatesAndWiringTest` | 4 | 0 | 0 | 0 |
| refund | `Wave8RefundAndSecurityIntegrationTest` | 5 | 0 | 0 | 0 |
| | **Total** | **215** | **0** | **0** | **0** |

Refund-scope classes: 11 classes / 66 tests (the 12 `refund` rows above sum to 66... see note) — all green. Non-refund (auth/admin/customer/baseline/common) classes: all green; **no pre-existing unrelated failure was observed** and no test was removed, skipped or weakened.

> Note on log noise: the `Duplicate entry '…' for key 'order_voucher_redemptions.uk_ovr_booking_id'` ERROR lines in the log are the *expected, asserted* rejections produced by the 8-thread concurrent voucher redemption test and the duplicate-insert constraint test (Stage 1), not failures.

### 1.2 Frontend — `node --test tests/frontend/*.test.mjs`

Result at Stage 4 gate (`#59`): **76/76 PASS**, 0 failed, 0 skipped (`stage4-refund-ui.test.mjs` 7/7, `refund-ui.test.mjs`, `security-guards.test.mjs`, booking/admin/theme suites). Only refund-test and doc files were committed after that run (Stage 4 commit `e834a68` contains the two new Stage 4 test files plus `_progress.md`); no frontend source file was modified afterwards, so the result stands.

### 1.3 Packaging

`mvn package -DskipTests` → `BUILD SUCCESS` (`target/box-cinema-1.0.0-SNAPSHOT.jar`).

---

## 2. Stage-by-Stage Test Coverage

### 2.1 Stage 1 — Voucher invariant, calculations & deadlines (`Stage1VoucherCalculationAndDeadlineTest` 10/10 + `PurchaseSnapshotServiceTest` 3/3)

- **Historical voucher scan (read-only, pre-`migration_v7`, `box_cinema`):** 16 bookings, 8 promotions, 16 payments, 16 tickets, 1 refund item, 0 voucher recoveries scanned → 0 multi-voucher-per-order violations, 0 paid mismatches, 0 duplicate restorations, 1 minor unpaid (`PENDING_PAYMENT`) snapshot gap that is reconcilable from `promotions`. Unresolved: 0.
- **One voucher per order:** backend and HTTP rejection of a second voucher / multi-code strings; DB `UNIQUE(booking_id)` constraint `uk_ovr_booking_id`; 8-thread concurrent redemption race → exactly one winner, rest fail with conflict.
- **Math:** fixed voucher `min(value, eligible)`; percentage voucher with max-discount cap; eligibility scope restrictions; Largest Remainder integer VND allocation with exact-sum invariant and deterministic ascending `ticket_id` tie-breaker (including 25 randomized trials).
- **Refund calculation:** single/multi-ticket, partial refund with previous-refund deduction, settled vs unsettled/mismatched payment guard, voucher restoration kept separate from cash and never duplicated.
- **Deadline (`Asia/Ho_Chi_Minh`):** exact 72h boundary (before/at/after), late-incident formula `min(show_end+30d, max(show_end+72h, notified+7d))`, 30-day cap clamp, null `customerNotifiedAt` fallback, Admin deadline exception requiring a reason with audit; `USED`/`CHECKED_IN` tickets never auto-rejected at intake and remain reviewable with a verified incident.

### 2.2 Stage 2 — Governance, limits, appeals, upload & rate limits (`Stage2GovernanceLimitsAppealsSecurityTest` 6/6)

- **Incidents:** Manager cinema-scoped, Admin system-wide, Customer forbidden; deleting a linked incident is blocked (`409 INCIDENT_LINKED_TO_REFUNDS`); incidents never auto-refund.
- **Limits:** exact-vs-exceeded thresholds for 500,000 / 2,000,000 / 5,000,000 / 10,000,000 VND, Manager escalation, Admin override with audit, config history.
- **Reservations / concurrency:** `RESERVED→COMMITTED/RELEASED` without double counting, post-approval tightening, `Asia/Ho_Chi_Minh` midnight rollover, concurrent approvals (8 threads) and concurrent Admin batch payouts (no deadlock).
- **Appeals:** state machine `APPEAL_SUBMITTED → APPEAL_UNDER_REVIEW → APPEAL_ACCEPTED` (original returns to `UNDER_REVIEW`, never auto-paid) / `APPEAL_REJECTED` (original stays `REJECTED`); ownership, branch isolation, one-open-appeal lock, audit trail.
- **Upload security:** rejects double extension, magic-byte spoof, polyglot, oversize and oversized batches; role-scoped authorized download; quarantined file blocked; hardening headers (`nosniff`, restrictive CSP, `sandbox`).
- **Rate limiting:** customer and IP windows return `429` with `Retry-After`; reset on clock advance.

### 2.3 Stage 3 — PayOS payout adapter, fee policy & reconciliation (`Stage3PayOsPayoutAdapterAndFeePolicyTest` 6/6)

- **All 8 provider fee-policy mock scenarios** enforce 100% customer receipt; unsafe scenarios (`RECIPIENT_DEDUCTED`, `UNKNOWN`, `UNVERIFIED`, …) are `PAYOUT_BLOCKED` **without any provider call** and keep the limit reservation.
- **Original-payment reversal check:** PayOS VietQR cannot be reversed → `BANK_PAYOUT` fallback recorded with audit.
- **Kill switch:** `LIVE_PAYOUT_ENABLED=false` blocks live execution; estimate-credit signature; live response `x-signature` verification fails closed.
- **State machine:** timeout → `UNKNOWN`, `ON_HOLD`, `PARTIAL_COMPLETED`, reconciliation logs, immutable recipient snapshot tamper guard (`RECIPIENT_SNAPSHOT_TAMPERED`, audit durable).
- **Webhook / idempotency:** invalid signature rejected, constant-time HMAC, valid transition logs reconciliation, replay causes zero duplicate effects.
- **One-click and bulk payout:** preview exposes metadata and fee assessment; mixed bulk executes each item independently.

### 2.4 Stage 4 — Frontend & Thymeleaf wiring (`stage4-refund-ui.test.mjs` 7/7, `Stage4FrontendTemplatesAndWiringTest` 4/4)

- Customer UI: `?tab=refund&orderCode=…&email=…` auto-tab + prefill + `POST /api/refunds/public/lookup-order`; per-item gross / discount / net; `Asia/Ho_Chi_Minh` deadline badge; `USED`/`CHECKED_IN` notice (never client-side blocked); evidence upload with public fallback and `400 EVIDENCE_QUARANTINED` / `429 Retry-After` surfacing; tracking tab with separate workflow / payout / fee / `PAYOUT_BLOCKED` / appeal status; appeal form when `REJECTED`.
- Manager/Admin UI: `formatPayoutStage` states, `LIVE_PAYOUT_ENABLED: FALSE` badge, quota banner, appeals queue + decide, incident create/update/delete, limits config, override and deadline exception controls, assess-fee, confirmation modal disabling execute when blocked, reconciliation logs.
- MockMvc: `/contact`, `/booking/history`, `/booking/history/{id}` and `/booking/detail/{id}` render `200`; `/manager` and `/manager/dashboard` (`MANAGER`/`ADMIN` 200, `CUSTOMER` 403); `/admin/refunds` and `/admin/incidents` (`ADMIN` 200, `MANAGER`/`CUSTOMER` 403); all required DOM hooks present.
- **Endpoint wiring:** every `fetch(...)` URL + HTTP method in `contact-refund.js` and `refund-ops.js` (both `/api/manager` and `/api/admin` expansions) resolves to a real Spring MVC handler.

---

## 3. Security & Financial-Integrity Audit

| # | Check | Method | Result |
|---|---|---|---|
| 1 | `app.refund.live-payout-enabled=${LIVE_PAYOUT_ENABLED:false}` | Read `application.properties` | **PASS** (default `false`) |
| 2 | `payos.payout.mode=${PAYOS_PAYOUT_MODE:MOCK}` | Read `application.properties` | **PASS** (default `MOCK`) |
| 3 | Payout live fee policy safe default | `payos.payout.live-fee-policy=${PAYOS_PAYOUT_LIVE_FEE_POLICY:UNVERIFIED}` → payouts blocked until explicitly verified | **PASS** |
| 4 | Payout credentials strictly separate from collection credentials, empty by default | `payos.payout.client-id/api-key/checksum-key` default empty; `app.security.bank-encryption-key` default empty | **PASS** |
| 5 | Tests cannot reach `box_cinema` or live PayOS | `application-test-override.properties` forces `box_cinema_test`, collection/payout `MOCK`, auto-fail-on-not-found `false` | **PASS** |
| 6 | No payout secret / live-enable literal in `src/main/java`, templates, static JS, `database/`, `docs/acceptance/refund/` | Regex scan for payout API/checksum key assignments and `LIVE_PAYOUT_ENABLED=true` | **PASS** — one match, a documentation sentence in `01-current-state-discovery.md` describing the condition required to enable live mode (no secret) |
| 7 | No full bank-account-number literals (12–19 digits) in refund services/controllers or frontend modules | Regex scan of `src/main/java/com/boxcinema/refund` and `static/js/modules` | **PASS** (0 hits) |
| 8 | `uk_ovr_booking_id` is `UNIQUE(booking_id)` on both databases | Read-only `information_schema.statistics`: `box_cinema` → `NON_UNIQUE=0`, `box_cinema_test` → `NON_UNIQUE=0` | **PASS** |
| 9 | Zero unsafe DOM sinks in refund frontend | Scan of `contact-refund.js` + `refund-ops.js` for `.innerHTML =`, `insertAdjacentHTML(` (count 0) plus Node test for `.outerHTML`, `document.write`, `eval` | **PASS** |
| 10 | Role/branch isolation on pages and APIs | Stage 2 + Stage 4 MockMvc RBAC tests | **PASS** |

Pre-existing, non-refund configuration observations (recorded honestly, not changed by this work):

- `application.properties` still ships development fallbacks for the DB password (`DB_PASSWORD` default), `app.security.hmac-secret`, and the **collection** `payos.checksum-key` / `payos.client-id` / `payos.api-key` dummy values. These pre-date the refund work, are used only when environment/`.env`/`application-local.properties` do not override them, and must be overridden for any non-local deployment. The **payout** credentials have no fallback values.

---

## 4. Defects Found and Repaired Across Stages 1–4

| Stage | Defect | Found by | Fixed by | Verified |
|---|---|---|---|---|
| 1 | Refund submission timestamp / delayed-review deadline anchored to wrong clock (Defect A) | code review | `backend-core` (`#15`) | Stage 1 tests |
| 1 | Booking totals not recalculated after scope-filtered voucher discount (Defect B) | code review | `backend-core` (`#15`) | Stage 1 tests |
| 2 | InnoDB `REPEATABLE READ` stale snapshot let 8 concurrent Manager approvals exceed the daily quota (`FOR SHARE` current reads missing) | Stage 2 concurrency test (`#35`) | `backend-core` (`#36`) | Stage 2 tests |
| 2 | Rate limit ran before stateless validation (400 typos returned 429) and throttled guest `purchaseEmail`; shared-IP bucket bleed across test classes | Stage 2 regression (`#35`) | `backend-core` (`#36`) | Stage 2 + regression |
| 2 | AB-BA InnoDB deadlock between payout commit and pre-payout revalidation (lock order buckets vs reservations) | Stage 2 batch test (`#37`) | `backend-core` (`#38`) | Stage 2 + regression |
| 3 | `RECIPIENT_SNAPSHOT_TAMPERED` forensic audit row rolled back with the failing transaction | Stage 3 test (`#45`) | `payos-researcher` (`#46`) via `REQUIRES_NEW` | Stage 3 tests |
| 4 | `admin/refunds.html` limit key `<option>` values did not match backend keys → `PUT /api/admin/refunds/limits` rejected, "Chọn sửa" broken | Stage 4 test (`#56`) | `architect` (`#58`) | Node + template test |
| 4 | Assess-fee UI read `feeClassification`/`blockReason` instead of `classification`/`reason`, and the result was wiped by an immediate detail refresh | Stage 4 test (`#56`) | `architect` (`#58`) | Node test |
| 4 | UI read `code` instead of `errorCode` so `[INCIDENT_LINKED_TO_REFUNDS]` / `[LIMIT_EXCEEDED_ESCALATION_REQUIRED]` badges were lost | Stage 4 test (`#56`) | `architect` (`#58`) | Node test |
| 4 | `/manager/dashboard` and `/booking/detail/{id}` routes missing; refund-link ID absent in one template branch | Stage 4 test (`#56`) | `backend-core` (`#57`) | MockMvc test |

Two QA-side test issues (not production defects) were also corrected during the work: `SecurityContextHolder` leakage between test threads (Stage 2) and a lazy-proxy access in a test fixture (Stage 3).

---

## 5. Remaining Risks and Limitations

1. **No live PayOS verification.** PayOS Payout has no sandbox; all payout behavior is verified against the mock adapter and signature/header unit tests. First live transfer must follow `04-live-test-guide.md` with a small amount and manual bank-credit confirmation.
2. **Fee-bearer contract unverified.** The sender-pays guarantee on `/v1/payouts` is not confirmed by PayOS documentation; `PAYOS_PAYOUT_LIVE_FEE_POLICY` therefore defaults to `UNVERIFIED` (payouts blocked) and must be set only after written confirmation.
3. **No documented PayOS Payout webhook.** The webhook handler is defensive (signature, idempotency, replay-safe), but reconciliation by polling `GET /v1/payouts/{id}` is the authoritative path.
4. **Host tooling limits.** No Redis and no ClamAV on the test host: rate limiting is application-local (not shared across multiple nodes) and the malware scan uses the in-app scanner/quarantine adapter rather than a real antivirus daemon.
5. **Tests share one local MySQL schema** (`box_cinema_test`) and use time-stamped tags and per-test cleanup; running two test JVMs concurrently against the same schema is not supported.
6. **Concurrency coverage is bounded** (8–12 threads on a single JVM and single MySQL node); behavior under multi-node deployment is analyzed by lock design (canonical lock order) rather than load-tested.
7. **Dev configuration fallbacks** noted in section 3 must be overridden outside local development.
8. **Pre-existing dirty working tree** (user changes from before Stage 0) was intentionally left untouched; only stage-owned files were committed.
