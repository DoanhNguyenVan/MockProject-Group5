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
