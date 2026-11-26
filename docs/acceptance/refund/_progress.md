# BOX Cinema — Refund & PayOS Payout Progress Log

---

## Stage 0 — Safety, Git Baseline, Database Verification & Backup

- **Stage:** Stage 0 (`Safety, Git, Database Verification & Backup`)
- **Started:** `2026-11-26T15:38:21+07:00`
- **Finished:** `2026-11-26T15:39:56+07:00`
- **Agents Involved:**
  - `coordinator` — `gemini-pro-orchestrator-neon` (Gemini 4 Neon High / Neon Orchestrator, fixed reasoning budget)
  - `architect` — `gemini-4-argon-high` (Stage 0 architecture & gap discovery)
  - `db-ops` — `gemini-4-hydrogen-high` (Stage 0 safety, Git baseline, DB verification, backup & isolated restore)
  - `payos-researcher` — `gemini-4-neon-high` (Stage 0 PayOS Payout official documentation research)
  - `monitor` — coordinator model (swarm stall monitor)
  - *Quota note:* `claude-opus-5-5-*`, `claude-sonnet-5-5-*`, and `gpt-oss-120b-medium` reported at 0% quota.

### 1. Git Safety & Dirty-Tree Baseline

- **Previous branch:** `main`
- **Working branch created:** `feat/refund-payos-payout` (`git checkout -b feat/refund-payos-payout`)
- **Pre-existing dirty tree preserved (no reset/clean/checkout/add-all run):**
  - Total `git status --short` entries: **179** (`91` modified `M`, `83` untracked `??`, `5` deleted `D` in `tests/frontend/`)
  - Category breakdown (`Code, Category: Count`):
    - `M, java-src: 63`
    - `M, static/resources: 18`
    - `M, java-test: 4`
    - `M, resources: 2`
    - `M, database: 2`
    - `M, root/other: 2` (`.gitignore`, `pom.xml`)
    - `D, frontend-test: 5`
    - `??, java-src: 37`
    - `??, root/other: 19` (audit/guide markdowns, zip archives, `AGENTS.md`, `.agents/`, `node_modules/`, etc.)
    - `??, static/resources: 8`
    - `??, database: 7` (`migration_v3`..`v6` + rollbacks)
    - `??, java-test: 6`
    - `??, frontend-test: 6`
- **Stage 0 files created/modified by `db-ops`:** `docs/acceptance/refund/_progress.md` only. Later stages must stage only stage-owned / newly created files explicitly.

### 2. Config & Database Environment Identification (Sanitized, Read-Only)

- **Config files inspected (secrets/credentials redacted):**
  - `src/main/resources/application.properties` (imports `application-local.properties` and `.env`, `.env` takes precedence)
  - `src/main/resources/application-local.properties`
  - `.env`
- **Sanitized Datasource & Runtime Mode:**
  - **Engine:** MySQL `8.0.43` (`com.mysql.cj.jdbc.Driver`, Windows service `MySQL80` = `Running`)
  - **Host / Port / Schema:** `127.0.0.1:3306` / `localhost:3306` (server `@@hostname = dung`), database/schema `box_cinema`
  - **Spring JPA DDL mode:** `spring.jpa.hibernate.ddl-auto=none`, `app.database.seed-enabled=false`
  - **PayOS Mode:** `payos.collection.mode=MOCK`, `payos.payout.mode=MOCK`, `PAYOS_PAYOUT_MODE=MOCK` (no live PayOS endpoints called)
  - **Credential hygiene:** All passwords, API keys, checksum keys, OAuth secrets, SMTP credentials, and `BANK_DATA_ENC_KEY` redacted and never logged.
- **Dev/Test Copy Verification (Read-Only):**
  - Local MySQL instance contains 20 local/dev databases (`box_cinema`, `box_cinema_test`, `baitapdatabase`, `hocdatabase`, `testdb`, etc.).
  - `box_cinema` contains **44** InnoDB tables with small dev-scale row counts:
    - `users`: `16`
    - `bookings`: `16`
    - `payments`: `16` (`9` `PAID`, `7` `PENDING`)
    - `promotions`: `8`
    - `tickets`: `16`
    - `booking_seats`: `25`
    - `booking_food_item`: `8`
    - `showtimes`: `1569`
    - `refund_requests`: `1`
    - `refund_request_items`: `1`
    - `voucher_recoveries`: `0`
    - `incident_reports`: `2`
    - `payos_payout_transactions`: `1`
    - `customer_bank_accounts`: `0`
- **Voucher / Booking Historical Data & Unique Constraints (Read-Only):**
  - `bookings` stores voucher usage as a single nullable FK column `promotion_id` (`fk_bookings_promotion` non-unique index; `1` row with `promotion_id=2`, `voucher_code_snapshot='BOXDUO'`, `discount_amount=24000.00`, `total_amount=0.00`, `booking_status='PENDING_PAYMENT'`). No dedicated `order_vouchers` / `voucher_redemptions` join table exists; `bookings.promotion_id` structurally allows at most one promotion per booking row, and `promotions.voucher_code` has a unique index (`NON_UNIQUE=0`).
  - `voucher_recoveries` (`0` rows) has unique indexes: `PRIMARY (id)`, `uk_vr_request_item (refund_request_id, refund_request_item_id)`, `active_item_lock`, `new_promotion_id`, `new_voucher_code`.
  - `refund_request_items` has unique `active_lock_key`; `payos_payout_transactions` has unique `active_request_lock`, `idempotency_key`, `reference_id`.

### 3. Database Backup & Isolated Restore Verification

- **Backup location (outside Git tracking):**
  - `C:\Users\dangi\.gemini\antigravity\scratch\boxcinema_db_backups\box_cinema_stage0_20261126_153933.sql`
  - Size: `368,756` bytes (`mysqldump --single-transaction --routines --triggers --events ... box_cinema`, without `--databases` so no hardcoded `USE box_cinema;` exists in the dump).
- **Isolated Scratch Restore Proof:**
  - Created scratch DB `boxcinema_restore_verify_20261126_153933`, restored the dump into it, verified exact parity against `box_cinema`, then dropped ONLY `boxcinema_restore_verify_20261126_153933`:

```text
+-------------------+-------------+-----------+--------------+--------------+----------------+---------------+---------------------+
| db_label          | table_count | users_cnt | bookings_cnt | payments_cnt | promotions_cnt | showtimes_cnt | refund_requests_cnt |
+-------------------+-------------+-----------+--------------+--------------+----------------+---------------+---------------------+
| source_box_cinema |          44 |        16 |           16 |           16 |              8 |          1569 |                   1 |
| scratch_verify    |          44 |        16 |           16 |           16 |              8 |          1569 |                   1 |
+-------------------+-------------+-----------+--------------+--------------+----------------+---------------+---------------------+
SCRATCH_DB_DROPPED=true
```

### 4. Available Tooling & Local Services (Read-Only)

- **Java:** `21.0.10` LTS (`C:\Program Files\Java\jdk-21.0.10`)
- **Maven:** `3.9.12` (`C:\Users\dangi\Downloads\apache-maven-3.9.12-bin\apache-maven-3.9.12`)
- **Node.js / npm:** `v24.18.0` / `11.16.0`
- **MySQL CLI:** `mysql.exe` & `mysqldump.exe` at `C:\Program Files\MySQL\MySQL Server 8.0\bin\` (`MySQL80` Windows service `Running`)
- **SQL Server:** `SQLCMD.EXE` present; `MSSQL$SQLEXPRESS` Running (not used by `box-cinema`)
- **Redis (`redis-cli` / `redis-server`):** `NOT_FOUND` (no Redis service installed)
- **ClamAV (`clamscan` / `clamdscan`):** `NOT_FOUND` (no ClamAV scanner installed)
- **Docker:** `NOT_FOUND`

### 5. Sanitized Command & Output Summary

- `git branch -a` & `git status --short` -> `179` dirty entries (`91 M`, `83 ??`, `5 D`); created `feat/refund-payos-payout` cleanly.
- `mysql.exe` read-only queries -> verified MySQL `8.0.43` on `127.0.0.1:3306`, schema `box_cinema` (`44` tables).
- `mysqldump.exe` + `mysql.exe` scratch restore -> backup `368,756` bytes verified against `boxcinema_restore_verify_20261126_153933` (`44` tables, identical counts) and scratch DB dropped.
- `java -version`, `mvn -version`, `node -v`, `npm -v` -> all available.

### 6. Blockers & Gate Decision

- **Blockers:** None for Stage 0 Git/DB/backup verification.
  - *Operational notes for Stage 1+:* Redis and ClamAV are absent on this host, so rate limiting and upload malware/quarantine handling must use in-app/DB-backed or explicit quarantine/scanner-adapter mechanisms without assuming local `redis-server`/`clamd` daemons. `PAYOS_PAYOUT_MODE` remains `MOCK`.
- **Gate decision:** `PASS`
  - **Coordinator Review Citation:** Stage 0 Gate approved (`#10`), citing [01-current-state-discovery.md](docs/acceptance/refund/01-current-state-discovery.md) (`#6`, `architect`) and PayOS official Payout docs verification (`#7`, `payos-researcher`).
- **Stage 0 Commit Hash:** `6fbc4f7` (`6fbc4f79ed36ebdb98c1a44f9204cd86646df542`) on branch `feat/refund-payos-payout`
  - Staged & committed ONLY: `docs/acceptance/refund/01-current-state-discovery.md`, `docs/acceptance/refund/_progress.md`.
  - Verified `git status --short` immediately post-commit retained all **179** pre-existing dirty entries untouched.

---

## Stage 1 (Part A) — Pre-Migration Historical Voucher Violation Scan (Read-Only)

- **Executed At:** `2026-11-26T15:51:25+07:00` (against `box_cinema`, prior to `migration_v7`)
- **Scan Totals:**
  - `bookings`: `16` total (`1` with voucher: `booking_id=17`, `BOX-20261118-9137`, `PENDING_PAYMENT`)
  - `promotions`: `8`
  - `payments`: `16` (`9 PAID`, `7 PENDING`)
  - `tickets`: `16`
  - `refund_request_items`: `1`
  - `voucher_recoveries`: `0`
- **Violation & Reconciliation Summary:**
  - **Duplicate vouchers per order:** `0` violations (scalar `bookings.promotion_id` has 1 row with `promotion_id=2`, `voucher_code_snapshot='BOXDUO'`).
  - **Orphan promotion FK / discount without `promotion_id`:** `0` violations.
  - **Discount-vs-settled-payment mismatches:** `0` violations (`booking_id=17` is `PENDING_PAYMENT` with `subtotal=24000.00`, `discount_amount=24000.00`, `total_amount=0.00`, `settled_paid_sum=0.00`, `ticket_cnt=0`; all `9` `PAID` payments reconcile with `bookings.total_amount`).
  - **Duplicate voucher restorations:** `0` violations (`voucher_recoveries=0`, `dup_vr_booking_promo=0`, `rri_with_voucher_recovery=0`).
  - **Missing promotion snapshots:** `1` historical snapshot gap on `booking_id=17` (`PENDING_PAYMENT`, `promotion_id=2`, `voucher_code_snapshot='BOXDUO'`, `promotion_discount_type_snapshot=NULL`, `promotion_discount_value_snapshot=NULL`, `promotion_max_discount_snapshot=NULL`, `promotion_end_at_snapshot=NULL`, `snapshot_review_required=0`). Reconcilable cleanly from `promotions(promotion_id=2)` (`FIXED`, `discount_value=30000.00`, `applicable_scope='ALL'`, `min(30000, 24000) = 24000.00`).
  - **Totals:** Scanned=`16` bookings / `16` payments / `16` tickets / `1` refund item / `0` voucher recoveries | Violations=`1` minor non-paid snapshot gap (`booking_id=17`) | Reconciled=`1` (`booking_id=17` reconcilable via `promotions.promotion_id=2`, `0` paid mismatches) | Unresolved=`0`.

---

## Stage 1 (Part B) — `migration_v7` Scratch Verification & `box_cinema` Application

- **Executed At:** `2026-11-26T16:12:53+07:00` to `2026-11-26T16:13:35+07:00`
- **Agent:** `db-ops` (`gemini-4-hydrogen-high`)
- **Migration Files Verified:**
  - `database/migration_v7_refund_governance_vouchers_appeals.sql`
  - `database/rollback_v7_refund_governance_vouchers_appeals.sql`

### 1. Isolated Scratch DB Verification (`boxcinema_mig7_test_20261126_161253`)

1. Restored Stage 0 backup (`box_cinema_stage0_20261126_153933.sql`, `44` tables) into `boxcinema_mig7_test_20261126_161253`.
2. **Apply `migration_v7` (Run 1):** Exit `0` -> `56` tables (`+12` tables), `order_voucher_redemptions=1`, `refund_limit_configs=4` (`500000` / `2000000` / `5000000` / `10000000`).
3. **Apply `migration_v7` (Run 2 — Idempotency):** Exit `0`, zero errors.
4. **Apply `rollback_v7`:** Exit `0` -> `44` tables cleanly restored.
5. **Re-apply `migration_v7` (Run 3):** Exit `0` -> `56` tables cleanly restored.
6. **Unique Constraint Proof (`uk_ovr_booking_id`):** Inserting a duplicate `booking_id=17` row into `order_voucher_redemptions` failed as expected with exit `1`:
   - `ERROR 1062 (23000) at line 1: Duplicate entry '17' for key 'order_voucher_redemptions.uk_ovr_booking_id'`
7. **Cleanup:** Dropped ONLY `boxcinema_mig7_test_20261126_161253` (`SCRATCH_DB_DROPPED=true`).

### 2. Application to `box_cinema` & Post-Migration Verification

- **Apply `migration_v7` to `box_cinema`:** Exit `0`
- **Table count:** `56` (verified all 12 new tables: `order_voucher_redemptions`, `order_voucher_allocations`, `refund_limit_configs`, `refund_limit_config_history`, `refund_limit_buckets`, `refund_limit_reservations`, `refund_limit_overrides`, `refund_appeals`, `refund_appeal_history`, `refund_evidence_files`, `payout_fee_assessments`, `payout_reconciliation_logs`).
- **Pre-existing Data Preservation:**
  - `users=16`, `bookings=16`, `payments=16`, `promotions=8`, `refund_requests=1` (`id=55`, `request_status='COMPLETED'`, `payout_status='SUCCEEDED'`, backfilled to `workflow_status='SUCCESS'`, `refund_method='BANK_PAYOUT'`).
- **Backfill & Schema Verification:**
  - `order_voucher_redemptions`: `1` row (`id=1`, `booking_id=17`, `promotion_id=2`, `voucher_code='BOXDUO'`, `discount_type='FIXED'`, `actual_discount=24000`, `status='APPLIED'`), `uk_ovr_booking_id` (`NON_UNIQUE=0`, column `booking_id`).
  - `order_voucher_allocations`: `2` rows backfilled.
  - `refund_limit_configs`: `4` rows (`MANAGER_PER_REQUEST=500000`, `MANAGER_DAILY=2000000`, `CINEMA_DAILY=5000000`, `INCIDENT_CUMULATIVE=10000000`).
  - `incident_reports`: `customer_notified_at`, `updated_by` present.
  - `refund_requests`: all `19` new columns present; `fk_rr_incident` verified `DELETE_RULE='RESTRICT'`.
- **Stage 1 DB Gate:** Ready for `@qa-verifier` Stage 1 test execution.

### 3. Test Schema (`box_cinema_test`) Backup & `migration_v7` Application (`#13`)

- **Executed At:** `2026-11-26T16:29:07+07:00` to `2026-11-26T16:29:18+07:00`
- **Verified Target:** `jdbc:mysql://localhost:3306/box_cinema_test` (`src/test/resources/application-test-override.properties`, `payos.collection.mode=MOCK`, `payos.payout.mode=MOCK`).
- **Pre-Migration Backup:** `C:\Users\dangi\.gemini\antigravity\scratch\boxcinema_db_backups\box_cinema_test_pre_mig7_20261126_162907.sql` (`357,999` bytes).
- **Post-Migration Verification:**
  - `box_cinema_test_table_count`: `56`
  - Pre-existing data preserved: `users=13`, `bookings=16`, `payments=16`, `promotions=8`, `refund_requests=0`
  - Backfill & constraints: `order_voucher_redemptions=1`, `order_voucher_allocations=2`, `refund_limit_configs=4`, `uk_ovr_booking_id` (`NON_UNIQUE=0`, column `booking_id`).


---

## EMERGENCY SWARM CHECKPOINT — SAFE PAUSE (`2026-11-26T16:32:00+07:00`)

### 1. Current Project Status
- **Status:** `PAUSED AT STAGE 1 VERIFICATION (SAFE CHECKPOINT)` due to critically low AI model quota (~16% remaining on Gemini 4 models, 0% on Claude/GPT-OSS).
- **Live Payout Safety:** `LIVE_PAYOUT_ENABLED` is NOT enabled (no live payout code active; `PAYOS_PAYOUT_MODE=MOCK`, `payos.collection.mode=MOCK`, `payos.payout.mode=MOCK`). Zero real-money transfers or live PayOS API calls were ever executed.

### 2. Current Stage & Exact Progress
- **Current Stage:** **Stage 1** (`Database Design, Voucher Invariant, Refund Calculations & Deadlines`).
- **Progress inside Stage 1:**
  - Pre-migration historical voucher scan on `box_cinema`: **DONE** (`#12`).
  - `migration_v7_refund_governance_vouchers_appeals.sql` & `rollback_v7_refund_governance_vouchers_appeals.sql`: **DONE**, verified idempotent & rollback-tested on isolated scratch DB `boxcinema_mig7_test_20261126_161253`, and applied to both `box_cinema` (`56` tables) and `box_cinema_test` (`56` tables) with pre-migration backups saved outside Git.
  - Stage 1 backend implementation (enums, entities, repositories, one-voucher-per-order enforcement, Largest Remainder `ticket_id` ascending tie-breaker, `applicableScope`, fixed/percent math, `OrderVoucherRedemption`/`OrderVoucherAllocation`, `VoucherHistoricalScanService`, `RefundDeadlineService` in `Asia/Ho_Chi_Minh`, `USED`/`CHECKED_IN` reviewability, `VoucherRecoveryService` separation, and Defects A & B fixed): **DONE** (`#11`, `#15`, `#17`, `mvn test-compile` -> `BUILD SUCCESS`).
  - Stage 1 QA test suite (`Stage1VoucherCalculationAndDeadlineTest.java`): **AUTHORED & PARTIALLY VERIFIED** (`8/13` passed on `Stage1VoucherCalculationAndDeadlineTest` + `PurchaseSnapshotServiceTest`; `5` remaining failures/errors are strictly 2 test-helper setup issues on lines 276 & 1128 of `Stage1VoucherCalculationAndDeadlineTest.java`, zero production defects found so far).
  - Remaining in Stage 1: Fix the 2 test-helper lines in `Stage1VoucherCalculationAndDeadlineTest.java`, re-run to `13/13 PASS`, run existing refund/voucher regression tests, pass Stage 1 Gate, and selectively commit Stage 1 files.

### 3. Stage 0–5 Status Table

| Stage | Name | Status | Notes |
|---|---|---|---|
| **Stage 0** | Safety, DB Identification, Backup & Discovery | `PASS` | Committed on `feat/refund-payos-payout` at `6fbc4f7`. Discovery report + PayOS audit + verified DB backup/restore complete. |
| **Stage 1** | DB Design, Voucher Invariant, Refund Calculations & Deadlines | `IN PROGRESS` | Migration v7 verified & applied to `box_cinema` & `box_cinema_test`; Stage 1 backend compiled cleanly; 8/13 Stage 1 tests passed (5 failed/errored due to 2 test-helper lines); regression & selective commit pending. |
| **Stage 2** | Incidents, Refund Requests, Appeals, Authorization, Limits & Concurrency | `NOT RUN` | DB tables (`refund_limit_*`, `refund_appeals*`, `refund_evidence_files`, `payout_fee_assessments`, `payout_reconciliation_logs`) already created cleanly in `migration_v7`. Service/controller/security/rate-limit logic pending. |
| **Stage 3** | PayOS Payout Adapter, State Machine, Webhooks & Reconciliation | `NOT RUN` | Official PayOS Payout v1 docs & code mismatches audited in Stage 0 (`#7`). Adapter, fee policy (`PAYOUT_BLOCKED`), `LIVE_PAYOUT_ENABLED=false` kill-switch, and mock tests pending. |
| **Stage 4** | Frontend Integration (Customer, Manager, Admin) | `NOT RUN` | Pre-existing `refund-ops.js`, `contact-refund.js`, `customer-bank-accounts.js` audited; Stage 4 wiring & build verification pending. |
| **Stage 5** | Regression, Security, Final 5 Acceptance Reports & Live-Test Runbook | `NOT RUN` | 1 of 5 final reports (`01-current-state-discovery.md`) completed; `02`–`05` pending. |

### 4. Completed Tasks with Evidence
1. **Git Safety & Branch Setup (`Stage 0`):** Created `feat/refund-payos-payout` from `main`; preserved all 179 pre-existing dirty entries (`91 M`, `83 ??`, `5 D`); committed Stage 0 docs cleanly at `6fbc4f7`.
2. **DB Identification, Backup & Isolated Restore (`Stage 0`):** Verified local MySQL `8.0.43` (`127.0.0.1:3306`, schema `box_cinema`, 44 tables -> 56 after v7). Backups saved outside Git:
   - `C:\Users\dangi\.gemini\antigravity\scratch\boxcinema_db_backups\box_cinema_stage0_20261126_153933.sql` (`368,756` bytes, scratch restore verified 44/44 tables & dropped).
   - `C:\Users\dangi\.gemini\antigravity\scratch\boxcinema_db_backups\box_cinema_test_pre_mig7_20261126_162907.sql` (`357,999` bytes).
3. **Architecture & Gap Discovery (`Stage 0`):** Authored `docs/acceptance/refund/01-current-state-discovery.md` (`368` lines).
4. **PayOS Official Docs Audit (`Stage 0`, `#7`):** Verified all 6 PayOS Payout v1 endpoints, separate payout credentials, URI-encoded deep-sorted HMAC-SHA256 `x-signature` (including response header `x-signature` check in `payos-lib-java v2.0.2`), `NO SANDBOX` policy, and unverified items (no official Payout webhook, unverified fee-bearer contract, unverified 6xx numeric codes).
5. **Pre-Migration Historical Voucher Scan (`Stage 1`, `#12`):** Scanned `box_cinema` (`16` bookings, `8` promotions, `16` payments, `16` tickets, `1` refund item, `0` voucher recoveries) -> `0` multi-voucher violations, `0` paid mismatches, `0` duplicate restorations, `1` minor unpaid `PENDING_PAYMENT` snapshot gap (`booking_id=17`, `BOXDUO`) cleanly reconciled.
6. **Migration v7 Scratch Verification & Application (`Stage 1`, `#12`, `#14`):** Verified `migration_v7` + idempotency + `rollback_v7` + duplicate `ERROR 1062` rejection on `uk_ovr_booking_id` in isolated scratch DB `boxcinema_mig7_test_20261126_161253`; applied `migration_v7` to `box_cinema` (`56` tables) and `box_cinema_test` (`56` tables).
7. **Stage 1 Backend Implementation & Defect Fixes (`Stage 1`, `#11`, `#15`, `#17`):** Implemented all Stage 1 files (`10` created, `22` backend/migration modified across Stage 1) and fixed both code-review defects (`Defect A` deadline clock & review anchor; `Defect B` `booking.recalculateTotals()` after scope-filtered discount). `mvn test-compile` exited `0` (`BUILD SUCCESS`).
8. **Stage 1 Test Suite Authored (`Stage 1`, `#18`):** Created `src/test/java/com/boxcinema/refund/Stage1VoucherCalculationAndDeadlineTest.java` (`10` test methods) and executed partial verification.

### 5. Incomplete Tasks
- **Stage 1 Remaining:**
  - Fix 2 test-helper lines in `Stage1VoucherCalculationAndDeadlineTest.java` (line `276` `BoxCinemaUserDetails` principal; line `1128` avoid lazy `b.getUser()` on detached `Booking`).
  - Re-run `mvn test -Dtest=Stage1VoucherCalculationAndDeadlineTest,PurchaseSnapshotServiceTest` to verify `13/13 PASS`.
  - Run existing voucher/payment/refund regression tests on `box_cinema_test`.
  - Evaluate Stage 1 Gate and selectively commit Stage 1 implementation + test files (excluding pre-existing user changes).
- **Stage 2 Remaining (Code & Tests):**
  - Restrict incident management to `manager_admin` (remove `STAFF` incident creation if required by `manager_admin` policy, enforce cinema scope & `ON DELETE RESTRICT` service guard against deleting linked incidents, zero auto-refund).
  - Implement configurable approval/payout limit service & atomic financial reservation ledger (`refund_limit_configs`, `refund_limit_config_history`, `refund_limit_buckets`, `refund_limit_reservations`, `refund_limit_overrides` — schema already in `migration_v7`): `500k/request`, `2M/manager/day`, `5M/cinema/day`, `10M/incident`, two-stage checks (approval + immediately before payout), `RESERVED -> COMMITTED` / `RELEASED`, `Asia/Ho_Chi_Minh` midnight rollover without double-counting, and Admin overrides with audit.
  - Implement separate Customer Refund Appeals service & endpoints (`refund_appeals`, `refund_appeal_history` — schema already in `migration_v7`): `APPEAL_SUBMITTED` -> `APPEAL_UNDER_REVIEW` -> `APPEAL_ACCEPTED` (returns original refund to `UNDER_REVIEW`) / `APPEAL_REJECTED` (original stays `REJECTED`).
  - Harden `EvidenceStorageService` & `PublicRefundApiController` / `SecurityConfig`: reject double extensions (`a.php.jpg`), add `refund_evidence_files` metadata + SHA-256 + `CLEAN`/`QUARANTINED` status + download audit, remove `permitAll()` on `/api/refunds/public/evidence/{filename}` in favor of authenticated/authorized download.
  - Implement `RefundRateLimitService` + `Retry-After` HTTP header on `429`: `5/customer/10m`, `20/IP/h`, plus upload and appeal thresholds.
  - Author & run Stage 2 authorization, concurrency, appeal, upload-security, and rate-limit tests.
- **Stage 3 Remaining:**
  - Implement `RefundMethod` priority (`ORIGINAL_PAYMENT_METHOD` check -> `BANK_PAYOUT` fallback).
  - Implement `PayoutProviderAdapter` + `PayoutFeeAssessment` (`SENDER_PAYS`, `RECIPIENT_DEDUCTED`, `NO_FEE`, `UNKNOWN`, `UNVERIFIED`), `PAYOUT_BLOCKED` guard when full receipt cannot be guaranteed, and `LIVE_PAYOUT_ENABLED=false` kill-switch.
  - Align `PayOsPayoutService` with Stage 0 PayOS doc findings (`#7`: enforce response header `x-signature` verification, handle `ON_HOLD` / `PARTIAL_COMPLETED` / `UNKNOWN`, reconcile via `GET /v1/payouts/{id}` & `POST /v1/payouts/estimate-credit`, treat undocumented webhooks defensively, avoid relying on unverified 6xx codes, persist `recipient_snapshot_json`/`hash` & `payout_reconciliation_logs`).
  - Author & run Stage 3 mock provider, fee-policy blocking, timeout/UNKNOWN, signature, and idempotency tests.
- **Stage 4 Remaining:**
  - Wire Customer (`booking-detail.html`, `booking-history.html`, `contact.html`, `contact-refund.js` — refund request, deadlines, appeal submission/tracking), Manager (`manager/dashboard.html`, `refund-ops.js` — quota bars, `manager_admin` incidents, `customer_notified_at`, appeal review), and Admin (`admin/refunds.html`, `admin/incidents.html`, `refund-ops.js` — limit config & overrides, fee assessment/`PAYOUT_BLOCKED`, `LIVE_PAYOUT_ENABLED` indicator, appeal review, bulk/one-click confirmation, `UNKNOWN`/`PROCESSING` reconciliation).
  - Verify frontend production build / Node tests (`tests/frontend/*.test.mjs`) and E2E flows.
- **Stage 5 Remaining:**
  - Full regression suite, security & financial-integrity audit, and produce the remaining 4 final acceptance reports (`02-api-payment-authorization.md`, `03-testing-security-results.md`, `04-live-test-guide.md`, `05-final-acceptance-report.md`).

### 6. Known Errors & Unresolved Issues
1. **Stage 1 Test-Helper Issues Only (No Production Defect Open):**
   - `Stage1VoucherCalculationAndDeadlineTest.java:1128`: `LazyInitializationException` on detached `Booking.getUser().getEmail()` in test helper `submitCustomerTicketRefund`.
   - `Stage1VoucherCalculationAndDeadlineTest.java:276`: `POST /api/bookings/{id}/voucher` MockMvc call used generic `.with(user(...))` instead of `BoxCinemaUserDetails`, returning `401` before controller logic.
2. **Stage 1 Production Defects Found & Already Fixed (`#15`):**
   - `Defect A` (`RefundService`, `RefundApprovalTxService`): fixed injected `Clock` submission timestamp and anchored delayed-review deadline check to submission time.
   - `Defect B` (`PurchaseSnapshotService`): fixed `booking.recalculateTotals()` after scope-filtered discount update.
3. **Unverified External PayOS Behaviors (from `#7`, expected to remain `BLOCKED`/guarded in live mode):**
   - No official PayOS Payout webhook documented; no contractual sender-pays fee guarantee on `/v1/payouts`; no sandbox environment; 6xx error codes unverified in public docs.

### 7. Tests Executed & Actual Results
- `mvn test-compile` (after Defect A/B fixes): **BUILD SUCCESS** (exit `0`, 289 main + 34 test classes).
- `mvn test -Dtest=PurchaseSnapshotServiceTest,PayOsPayoutSignatureAndBankSecurityTest`: **9/9 PASS** (`PurchaseSnapshotServiceTest` 3/3 PASS, `PayOsPayoutSignatureAndBankSecurityTest` 6/6 PASS).
- `mvn test -Dtest=Stage1VoucherCalculationAndDeadlineTest,PurchaseSnapshotServiceTest` (finished right as checkpoint stopped new runs): **8/13 PASS, 2 Failures, 3 Errors**:
  - Passed (`8`): `PurchaseSnapshotServiceTest` (3/3), `uk_ovr_booking_id` DB unique constraint test, 8-thread concurrent voucher redemption race test, fixed/percent/scope/Largest-Remainder math test, `ticket_id` ascending tie-breaker + 25 randomized trials test, and `RefundDeadlineService` `Asia/Ho_Chi_Minh` 72h + late-incident + 30d cap test.
  - Failed/Errored (`5`): `4` errors from `LazyInitializationException` at test helper line `1128`; `1` failure from `401` on MockMvc generic user principal at line `276`.
- Existing refund/voucher regression suite (`RefundAndIncidentIntegrationTest`, etc.): **NOT YET RUN** post-Stage-1 edits (paused per emergency checkpoint).

### 8. Database Migration Status
- `box_cinema` (local dev MySQL `8.0.43`, `127.0.0.1:3306`): **`migration_v7` APPLIED** (`56` tables, `uk_ovr_booking_id` active, `rlc=4`, all existing rows preserved). Pre-migration backup verified.
- `box_cinema_test` (local test MySQL `8.0.43`, `localhost:3306`): **`migration_v7` APPLIED** (`56` tables, `uk_ovr_booking_id` active, `rlc=4`, all existing rows preserved). Pre-migration backup saved.
- Scratch DBs: Both `boxcinema_restore_verify_20261126_153933` and `boxcinema_mig7_test_20261126_161253` cleanly dropped after verification.

### 9. PayOS Integration Status
- Official PayOS Payout v1 specification fully researched (`#7`).
- Runtime remains strictly `PAYOS_PAYOUT_MODE=MOCK` and `payos.collection.mode=MOCK`. Zero live PayOS calls made.
- `LIVE_PAYOUT_ENABLED=false` guard and `PayoutProviderAdapter` / `PayoutFeeAssessment` (`PAYOUT_BLOCKED`) implementation scheduled for Stage 3.

### 10. Current Git Branch, Commit & Working Tree Status
- **Branch:** `feat/refund-payos-payout`
- **Latest Commit:** `6fbc4f7` (`docs(refund): complete Stage 0 safety, discovery, and PayOS audit`)
- **Working Tree (`git status --short`):** **188** entries total (`92 M`, `91 ??`, `5 D`), preserving all 179 pre-existing user entries plus Stage 1 uncommitted changes. Full snapshot saved to `C:\Users\dangi\.gemini\antigravity-cli\brain\e98c84a9-a19b-4ef1-9a83-62304432c9ed\scratch\git_status_checkpoint.txt`.
- **Stage 1 Uncommitted Files (`backend-core` + `qa-verifier`):**
  - *Created (`11`):* `database/migration_v7_refund_governance_vouchers_appeals.sql`, `database/rollback_v7_refund_governance_vouchers_appeals.sql`, `src/main/java/com/boxcinema/common/enums/RefundWorkflowState.java`, `src/main/java/com/boxcinema/common/enums/RefundMethod.java`, `src/main/java/com/boxcinema/common/entity/OrderVoucherRedemption.java`, `src/main/java/com/boxcinema/common/entity/OrderVoucherAllocation.java`, `src/main/java/com/boxcinema/common/repository/OrderVoucherRedemptionRepository.java`, `src/main/java/com/boxcinema/common/repository/OrderVoucherAllocationRepository.java`, `src/main/java/com/boxcinema/refund/service/VoucherHistoricalScanService.java`, `src/main/java/com/boxcinema/refund/service/RefundDeadlineService.java`, `src/test/java/com/boxcinema/refund/Stage1VoucherCalculationAndDeadlineTest.java`.
  - *Modified by Stage 1 (`22`):* `Promotion.java`, `IncidentReport.java`, `RefundRequest.java`, `VoucherRecoveryRepository.java`, `BookingService.java`, `PurchaseSnapshotService.java`, `BookingApiController.java`, `PromotionService.java`, `SeatHoldCleanupService.java`, `BookingServiceImpl.java`, `PayOsServiceImpl.java`, `PromotionServiceImpl.java`, `IncidentCreateRequestDto.java`, `IncidentResponseDto.java`, `OrderLookupResponseDto.java`, `RefundRequestDetailDto.java`, `RefundReviewRequestDto.java`, `IncidentService.java`, `PayoutEligibilityService.java`, `RefundApprovalTxService.java`, `RefundService.java`, `VoucherRecoveryService.java`.
  - *Modified/Created at Checkpoint (`2`):* `docs/acceptance/refund/_progress.md` (appended), `docs/acceptance/refund/RESUME_SWARM.md` (created).

### 11. Swarm Agent States at Checkpoint

| Agent | Model | Assigned Task | Final State | Last Completed Operation |
|---|---|---|---|---|
| `coordinator` | `gemini-4-hydrogen-high` (Neon Orchestrator) | Orchestration, gates, checkpoint | `ACTIVE -> PAUSING` | Saved checkpoint in `_progress.md` and `RESUME_SWARM.md`. |
| `architect` | `gemini-4-argon-high` | Stage 0 architecture & gap discovery | `COMPLETED (waiting)` | Completed `01-current-state-discovery.md` (`#6`). |
| `payos-researcher` | `gemini-4-neon-high` | Stage 0 PayOS Payout official docs audit | `COMPLETED (waiting)` | Posted full verified/unverified PayOS report (`#7`). |
| `db-ops` | `gemini-4-hydrogen-high` | Stage 0 safety/backup/commit + Stage 1 scan & `v7` migrations | `COMPLETED (waiting)` | Committed Stage 0 (`6fbc4f7`), ran historical scan, verified scratch DB, applied `v7` to `box_cinema` & `box_cinema_test` (`#12`, `#14`). |
| `backend-core` | `gemini-4-argon-high` | Stage 1 DB script, voucher invariant, math & deadlines | `COMPLETED Stage 1 (waiting)` | Finished all Stage 1 backend tasks & Defects A/B fixes; `mvn test-compile` green (`#17`). |
| `qa-verifier` | `gemini-4-carbon-high` | Stage 1 adversarial review & tests | `UNFINISHED / PAUSED (waiting)` | Authored `Stage1VoucherCalculationAndDeadlineTest.java`, ran 8/13 passing; paused cleanly before fixing 2 test-helper lines & regression run (`#18`). |
| `monitor` | `gemini-4-hydrogen-high` | Stall monitor | `WAITING` | Idle (`#14`). |

### 12. Exact Next Actions on Resume
1. Fix lines `276` and `1128` in `src/test/java/com/boxcinema/refund/Stage1VoucherCalculationAndDeadlineTest.java`.
2. Run `mvn test -Dtest=Stage1VoucherCalculationAndDeadlineTest,PurchaseSnapshotServiceTest` -> confirm `13/13 PASS`.
3. Run regression tests (`PromotionServiceTest`, `PayOsCollectionAndBookingIntegrationTest`, `RefundAndIncidentIntegrationTest`, `RefundPayoutBatchWorkflowIntegrationTest`, `PayOsPayoutWebhookResilienceIntegrationTest`, `PayOsPayoutAndRefundFullAuditIntegrationTest`, `CustomerBankAccountAndPayoutIntegrationTest`, `Wave8RefundAndSecurityIntegrationTest`).
4. Pass Stage 1 Gate and selectively commit Stage 1 files (`git add <exact Stage 1 files>`).
5. Proceed to Stage 2 -> Stage 3 -> Stage 4 -> Stage 5, updating `_progress.md` and committing after each verified stage.

### 13. External Dependencies & Quota Limitations
- **AI Quota at Pause:** `claude-opus-5-5-*`, `claude-sonnet-5-5-*`, `gpt-oss-120b-medium` at `0%` (reset `2026-11-27T19:42:27Z`); Gemini 4 models at ~`16%` (reset `2026-11-26T13:35:08Z`).
- **Host Environment:** Java 21.0.10, Maven 3.9.12, Node v24.18.0, npm 11.16.0, MySQL `8.0.43` (`MySQL80` Running). Redis, ClamAV, and Docker are `NOT_FOUND` on this machine.
- **PayOS Constraints:** No sandbox environment; no documented Payout webhook; transfer fee-bearer rule is unverified at the API contract level (requires `PAYOUT_BLOCKED` guard unless verified).

---

## Stage 1 Gate Closure — `PASS` (`2026-11-26T20:56:09+07:00`)

- **Gate Decision:** `PASS` (Coordinator `#26`, following Independent QA `#25`)
- **Stage 1 Test & Regression Summary (`58/58 PASS`, `0` Failures, `0` Errors, `LIVE_PAYOUT_ENABLED=false`, `PAYOS_PAYOUT_MODE=MOCK`):**
  - `mvn test "-Dtest=Stage1VoucherCalculationAndDeadlineTest,PurchaseSnapshotServiceTest"` -> **13/13 PASS** (`BUILD SUCCESS`, `26.471 s`; `Stage1VoucherCalculationAndDeadlineTest` 10/10, `PurchaseSnapshotServiceTest` 3/3)
  - `mvn test "-Dtest=PromotionServiceTest,PayOsCollectionAndBookingIntegrationTest,RefundAndIncidentIntegrationTest,RefundPayoutBatchWorkflowIntegrationTest,PayOsPayoutWebhookResilienceIntegrationTest,PayOsPayoutAndRefundFullAuditIntegrationTest,CustomerBankAccountAndPayoutIntegrationTest,Wave8RefundAndSecurityIntegrationTest"` -> **39/39 PASS** (`BUILD SUCCESS`, `46.202 s`)
  - `mvn test -Dtest=PayOsPayoutSignatureAndBankSecurityTest` -> **6/6 PASS**
- **Selective Commit Scope (13 newly created Stage 1 files + docs):**
  - `database/migration_v7_refund_governance_vouchers_appeals.sql`
  - `database/rollback_v7_refund_governance_vouchers_appeals.sql`
  - `src/main/java/com/boxcinema/common/enums/RefundWorkflowState.java`
  - `src/main/java/com/boxcinema/common/enums/RefundMethod.java`
  - `src/main/java/com/boxcinema/common/entity/OrderVoucherRedemption.java`
  - `src/main/java/com/boxcinema/common/entity/OrderVoucherAllocation.java`
  - `src/main/java/com/boxcinema/common/repository/OrderVoucherRedemptionRepository.java`
  - `src/main/java/com/boxcinema/common/repository/OrderVoucherAllocationRepository.java`
  - `src/main/java/com/boxcinema/refund/service/VoucherHistoricalScanService.java`
  - `src/main/java/com/boxcinema/refund/service/RefundDeadlineService.java`
  - `src/test/java/com/boxcinema/refund/Stage1VoucherCalculationAndDeadlineTest.java`
  - `docs/acceptance/refund/_progress.md`
  - `docs/acceptance/refund/RESUME_SWARM.md`
