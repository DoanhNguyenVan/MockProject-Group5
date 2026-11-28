# BOX Cinema — PayOS Live Payout Testing Guide & Operational Runbook

- **Document:** `docs/acceptance/refund/04-live-test-guide.md` (Acceptance Deliverable 04 — Part 14.4 & Part 15.4)
- **System:** BOX Cinema Refund & Automated PayOS Payout v1 Module (`box-cinema`)
- **Default Runtime State:** `LIVE_PAYOUT_ENABLED=false`, `PAYOS_PAYOUT_MODE=MOCK`, `PAYOS_PAYOUT_LIVE_FEE_POLICY=UNVERIFIED`
- **Secret Hygiene Statement:** This guide lists **environment variable names only**. No passwords, API keys, checksum keys, encryption keys, or full bank account numbers are recorded anywhere in this document.

> **CRITICAL WARNING — NO PAYOS SANDBOX EXISTS:** Per official PayOS documentation (`https://payos.vn/docs/moi-truong-test`), PayOS does **not** provide a sandbox environment for Payout APIs. Any call executed with `PAYOS_PAYOUT_MODE=LIVE` and `LIVE_PAYOUT_ENABLED=true` against `https://api-merchant.payos.vn` moves **real VND funds** from the cinema's linked Bảo Kim / PayOS Payout account. Never run automated integration tests or CI jobs in `LIVE` mode.

---

## 1. Preconditions Before Any Live Payout Test

Every precondition below MUST be verified and signed off before flipping `PAYOS_PAYOUT_MODE=LIVE` or `LIVE_PAYOUT_ENABLED=true`:

1. **Verified PayOS Organization Account:** The BOX Cinema merchant account on `https://my.payos.vn` must have completed business/organization KYC verification and have Payout (`Chi tiền` / Disbursement) enabled.
2. **Linked & Funded Bảo Kim E-Wallet (`Tài khoản chi hộ`):** The PayOS Payout channel must be linked to an active Bảo Kim e-wallet with sufficient VND balance to cover the test transfer amount **plus** any sender-side transfer fees.
3. **Dedicated PayOS Payout Channel (`Kênh chuyển tiền`):**
   - Create a **dedicated Payout channel** on `my.payos.vn`, strictly separate from the PayOS Collection (`Kênh thanh toán` / VietQR) channel.
   - Generate a distinct set of Payout credentials (`PAYOS_PAYOUT_CLIENT_ID`, `PAYOS_PAYOUT_API_KEY`, `PAYOS_PAYOUT_CHECKSUM_KEY`). BOX Cinema enforces strict credential separation at both startup (`PayOsPayoutService.validateConfiguration()`) and pre-dispatch (`409 LIVE_PAYOUT_CREDENTIALS_SHARED`).
4. **Outbound IP Whitelisting:** Register the exact public egress IP address(es) of the BOX Cinema application host in the PayOS Payout channel IP whitelist on `my.payos.vn`. Unwhitelisted hosts are rejected by PayOS with HTTP `401`/`403`.
5. **Active PayOS Payout Service Credit Package:** Confirm on `my.payos.vn` that the Payout channel has an active service credit balance (verifiable via `POST /v1/payouts/estimate-credit`).
6. **Verified Database Backup Taken Immediately Prior to Test:**
   - Confirm target schema is the intended environment (`box_cinema`, never an unverified or shared production host without backup).
   - Take a fresh consistent backup stored **outside** the Git repository:
     ```bash
     mysqldump --single-transaction --routines --triggers --events \
       -h <DB_HOST> -P <DB_PORT> -u <DB_USER> -p box_cinema \
       > <BACKUP_DIR>/box_cinema_pre_live_payout_YYYYMMDD_HHMMSS.sql
     ```
   - Verify non-zero dump size and confirm `migration_v7_refund_governance_vouchers_appeals.sql` is applied (all 56 tables including `payout_fee_assessments`, `payout_reconciliation_logs`, `refund_limit_*`, `refund_audit_logs`).
7. **Controlled Small-Amount Test Recipient Account:**
   - Prepare **one** operator-owned Vietnamese bank account at a supported NAPAS/VietQR bank (6-digit BIN in `BankDataEncryptionService.SUPPORTED_VIETQR_BANKS`, 45 supported banks) so the operator can directly inspect the bank mobile app / statement to verify **exact 100% VND credit receipt**.
   - Use a small refundable test booking amount (e.g., `10,000 VND`).
8. **Dual-Role / Maker-Checker Readiness:**
   - Have a Manager account (cinema-scoped) and a separate Admin account (`ROLE_ADMIN`, system-wide payout approver/dispatcher) ready so maker-checker controls remain intact during testing.
9. **Strong AES-256 Bank Encryption Key Provisioned:**
   - `BANK_DATA_ENC_KEY` must be at least 32 characters (or Base64-encoded 32+ bytes). `BankDataEncryptionService.init()` refuses to start in `PAYOS_PAYOUT_MODE=LIVE` if `BANK_DATA_ENC_KEY` is blank or weak.

---

## 2. Environment Variable Reference (Names Only)

Spring Boot loads configuration in the following order (`application.properties` line 8):
1. `src/main/resources/application.properties` (safe `MOCK` / locked defaults)
2. `application-local.properties` (git-ignored, optional local override; template at `src/main/resources/application-local.properties.example`)
3. `.env` (git-ignored, **highest file precedence**, overrides `application-local.properties`)
4. Process/OS environment variables

> **Rule:** Never commit `.env` or `application-local.properties` to Git, and never place real credentials in `application.properties`.

### 2.1 PayOS Payout Gateway Variables (Strictly Separate from Collection)

| Environment Variable Name | Spring Property | Safe Default | Required in `LIVE`? | Purpose & Guardrail Behavior |
|---|---|---|---|---|
| `LIVE_PAYOUT_ENABLED` | `app.refund.live-payout-enabled` | `false` | **Yes (`true` during test window only)** | Hard live-dispatch kill-switch. When `false`, any `LIVE` payout attempt fails closed with HTTP `409 LIVE_PAYOUT_DISABLED` before any HTTP call. |
| `PAYOS_PAYOUT_MODE` | `payos.payout.mode` | `MOCK` | **Yes (`LIVE` during test window only)** | Switches between local simulation (`MOCK`) and real PayOS Payout v1 HTTP calls (`LIVE`). Scheduled polling (`runScheduledPayoutReconcile`) runs **only** when `LIVE`. |
| `PAYOS_PAYOUT_BASE_URL` | `payos.payout.base-url` | `https://api-merchant.payos.vn` | Yes (default OK) | Official PayOS Payout v1 base URL. |
| `PAYOS_PAYOUT_CLIENT_ID` | `payos.payout.client-id` | *(blank)* | **Yes** | Dedicated PayOS Payout channel Client ID (`x-client-id`). Must NOT equal `PAYOS_CLIENT_ID` or `PAYOS_COLLECTION_CLIENT_ID`. |
| `PAYOS_PAYOUT_API_KEY` | `payos.payout.api-key` | *(blank)* | **Yes** | Dedicated PayOS Payout channel API Key (`x-api-key`). Must NOT equal `PAYOS_API_KEY` or `PAYOS_COLLECTION_API_KEY`. |
| `PAYOS_PAYOUT_CHECKSUM_KEY` | `payos.payout.checksum-key` | *(blank)* | **Yes** | Dedicated PayOS Payout channel Checksum Key for HMAC-SHA256 `x-signature` request signing and response/webhook verification. Must NOT equal `PAYOS_CHECKSUM_KEY` or `PAYOS_COLLECTION_CHECKSUM_KEY`. |
| `PAYOS_PAYOUT_LIVE_FEE_POLICY` | `payos.payout.live-fee-policy` | `UNVERIFIED` | **Yes** | Controls live fee-bearer verification (`UNVERIFIED`, `SENDER_PAYS`, `NO_FEE`, `RECIPIENT_DEDUCTED`, `UNKNOWN`). Defaults fail-closed to `UNVERIFIED` -> `PAYOUT_BLOCKED`. Set to `SENDER_PAYS` or `NO_FEE` **only** after completing Section 4. |
| `PAYOS_PAYOUT_MOCK_FEE_POLICY` | `payos.payout.mock-fee-policy` | `SENDER_PAYS` | No (`MOCK` only) | Fee classification used in `MOCK` mode (`payos.payout.mock-estimated-fee-vnd` defaults to `0`). |
| `PAYOS_PAYOUT_CONNECT_TIMEOUT_MS` | `payos.payout.connect-timeout-ms` | `10000` | No | Outbound HTTP connect timeout in ms (clamped `1000`–`10000` in `@PostConstruct`). |
| `PAYOS_PAYOUT_READ_TIMEOUT_MS` | `payos.payout.read-timeout-ms` | `30000` | No | Outbound HTTP read timeout in ms (clamped `1000`–`30000`). On timeout, transaction transitions to `PROCESSING` / `UNKNOWN_OUTCOME` (`workflowStatus=UNKNOWN`) and retains reservations. |
| `PAYOS_PAYOUT_RECONCILE_POLL_INTERVAL_MS` | `payos.payout.reconcile-poll-interval-ms` | `30000` | No | Interval (ms) for `@Scheduled` background reconciliation of `PENDING`/`PROCESSING` payouts in `LIVE` mode. |
| `PAYOS_PAYOUT_NOT_FOUND_GRACE_SECONDS` | `payos.payout.not-found-grace-seconds` | `900` | No | Grace window (15 min default) before a PayOS `608` / not-found lookup escalates to `MANUAL_RECONCILE_REQUIRED`. |
| `PAYOS_PAYOUT_ALLOW_AUTO_FAIL_ON_NOT_FOUND` | `payos.payout.allow-auto-fail-on-not-found` | `false` | **Keep `false`** | When `false` (mandatory in `LIVE`), PayOS `608`/not-found after grace window sets `MANUAL_RECONCILE_REQUIRED` instead of auto-failing, preventing duplicate payouts until an Admin confirms failure on `my.payos.vn`. |

### 2.2 Bank Encryption, Refund Governance & PayOS Collection Separation Variables

| Environment Variable Name | Spring Property | Safe Default | Required in `LIVE`? | Purpose & Guardrail Behavior |
|---|---|---|---|---|
| `BANK_DATA_ENC_KEY` | `app.security.bank-encryption-key` | *(blank)* | **Yes** | Master secret for AES-256-GCM bank account encryption and HMAC-SHA256 account hashing (`BankDataEncryptionService`). Must be >=32 chars (or Base64 >=32 bytes) in `LIVE` mode; startup fails otherwise. |
| `PAYOS_COLLECTION_MODE` | `payos.collection.mode` | `MOCK` | Independent | Collection (VietQR pay-in) mode. Keep `MOCK` if testing Payout only. |
| `PAYOS_COLLECTION_ALLOW_SIMULATE` | `payos.collection.allow-simulate` | `false` | Keep `false` | Disables `/api/payments/payos/simulate-payment/{orderCode}` when `false` or when `PAYOS_COLLECTION_MODE=LIVE`. |
| `PAYOS_CLIENT_ID` / `PAYOS_COLLECTION_CLIENT_ID` | `payos.client-id` / `payos.collection.client-id` | `dummy-client-id` | Must differ from Payout | Collection Client ID. Checked against `PAYOS_PAYOUT_CLIENT_ID` to enforce strict channel separation. |
| `PAYOS_API_KEY` / `PAYOS_COLLECTION_API_KEY` | `payos.api-key` / `payos.collection.api-key` | `dummy-api-key` | Must differ from Payout | Collection API Key. Checked against `PAYOS_PAYOUT_API_KEY`. |
| `PAYOS_CHECKSUM_KEY` / `PAYOS_COLLECTION_CHECKSUM_KEY` | `payos.checksum-key` / `payos.collection.checksum-key` | *(dev fallback)* | Must differ from Payout | Collection Checksum Key. Checked against `PAYOS_PAYOUT_CHECKSUM_KEY`. |
| `HMAC_SECRET` | `app.security.hmac-secret` | *(dev fallback)* | Recommended | General security HMAC secret; set strong unique value in production. |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | `spring.datasource.*` | local MySQL `box_cinema` | Yes | MySQL 8 datasource connection (`serverTimezone=Asia/Ho_Chi_Minh`). |
| `APP_SCHEDULING_ENABLED` | `app.scheduling.enabled` | `true` | Yes (`true`) | Enables scheduled payout reconciliation and email outbox dispatch. |
| `REFUND_VOUCHER_EXPIRY_DAYS` | `app.refund.voucher.expiry-days` | `30` | No | Expiry days for restored/replacement refund vouchers. |
| `REFUND_EVIDENCE_DIR` | `app.refund.evidence-dir` | `uploads/refund-evidence` | No | Local storage directory for uploaded refund evidence files. |
| `PUBLIC_BASE_URL` / `APP_PUBLIC_BASE_URL` | `app.public-base-url` | `http://localhost:8080` | If HTTPS/public | Base URL for links/emails; pair with `BOX_SESSION_COOKIE_SECURE=true`, `SERVER_FORWARD_HEADERS_STRATEGY=framework`, and `CORS_ALLOWED_ORIGINS` on HTTPS deployments. |

---

## 3. PayOS Authentication, Permission & Balance Preflight Checks

Before selecting or dispatching any real-money refund, verify authentication, credential isolation, IP whitelisting, account balance, and payout credit availability.

### 3.1 How BOX Cinema Signs & Verifies Official PayOS Payout v1 Requests

- **Request headers on `POST /v1/payouts` and `POST /v1/payouts/estimate-credit`:**
  - `x-client-id`: `PAYOS_PAYOUT_CLIENT_ID`
  - `x-api-key`: `PAYOS_PAYOUT_API_KEY`
  - `x-idempotency-key`: unique UUID per attempt (`POST /v1/payouts`)
  - `x-signature`: lowercase hex HMAC-SHA256 using `PAYOS_PAYOUT_CHECKSUM_KEY` over alphabetically sorted top-level keys, deep-sorted nested objects, JSON-serialized arrays/objects, and RFC 3986 URI-encoded key/value pairs joined by `&` (`PayOsPayoutSignatureUtil.signPayload`, aligned with `payos-lib-java v2.0.2`).
- **Response verification (`LIVE` mode — fail-closed):**
  - `PayOsPayoutService.verifyRestResponseSignature(...)` **requires** a valid HTTP response header `x-signature` on `POST /v1/payouts`, `GET /v1/payouts/{id}`, `GET /v1/payouts`, and `GET /v1/payouts-account/balance`.
  - If the header is missing or fails constant-time HMAC verification, balance checks return `liveVerified=false` and payout submission/reconciliation fails closed into `UNKNOWN` (`UNKNOWN_OUTCOME`) instead of trusting unverified response data.

### 3.2 Mandatory Read-Only Preflight Checks (Zero Money Movement)

Execute all four read-only checks while authenticated as `ROLE_ADMIN`:

1. **Application Preflight Status Check:**
   - **Endpoint:** `GET /api/admin/payouts/preflight-status` (also readable in read-only summary at `GET /api/manager/payouts/preflight-status`)
   - **Expected JSON fields (zero secrets returned):**
     ```json
     {
       "livePayoutEnabled": true,
       "payoutMode": "LIVE",
       "providerName": "PAYOS",
       "configuredFeePolicy": "SENDER_PAYS",
       "feePolicyGuaranteesFullReceipt": true,
       "credentialsConfigured": true,
       "credentialsDistinct": true,
       "liveDispatchReady": true
     }
     ```
   - **Gate:** Do **not** proceed unless `credentialsConfigured=true`, `credentialsDistinct=true`, `feePolicyGuaranteesFullReceipt=true`, and `liveDispatchReady=true`.

2. **PayOS Payout Account Balance Check (`GET /v1/payouts-account/balance`):**
   - **Endpoint:** `GET /api/admin/payouts/account-balance`
   - **Under the hood:** Calls `GET https://api-merchant.payos.vn/v1/payouts-account/balance` with `x-client-id` + `x-api-key`, verifies `code == "00"` and response header `x-signature`, and masks `accountNumber` via `BankDataEncryptionService.maskAccountNumber(...)`.
   - **Expected Result:** `httpStatus=200`, `responseCode="00"`, `liveVerified=true`, `currency="VND"`, `accountNumberMasked` matches the expected linked Bảo Kim payout account, and numeric `balance` exceeds `approvedCashTotal + estimatedFee`.
   - **Failure Triage:**
     - `HTTP 401` / `403`: invalid Payout credentials or outbound host IP not whitelisted on `my.payos.vn`.
     - `liveVerified=false` with signature error: `PAYOS_PAYOUT_CHECKSUM_KEY` mismatch for the Payout channel.

3. **PayOS Remote Payout Listing Permission Check (`GET /v1/payouts`):**
   - **Endpoint:** `GET /api/admin/payouts/payos-remote?limit=5&offset=0`
   - **Under the hood:** Calls `GET https://api-merchant.payos.vn/v1/payouts?limit=5&offset=0`, verifies response `x-signature`, and recursively masks any `toAccountNumber` / `accountNumber` fields.
   - **Expected Result:** `httpStatus=200`, `code="00"`, valid `payouts` array (empty or prior payouts), zero `INVALID_SIGNATURE` error.

4. **PayOS Payout Credit Estimation Check (`POST /v1/payouts/estimate-credit`):**
   - PayOS Payout v1 charges service credits per disbursement via `POST https://api-merchant.payos.vn/v1/payouts/estimate-credit` (implemented in `PayOsPayoutProviderAdapter.estimateCredit(...)` using official batch schema `{referenceId, category, validateDestination, payouts:[...]}` and signed with `x-signature`).
   - Confirm in `my.payos.vn` (Payout channel credit balance) that sufficient payout credits exist for the test transfer and that `estimateCredit` returns a valid non-negative integer (`code="00"`).

---

## 4. Transfer Fee Verification Procedure (`UNVERIFIED` -> `PAYOUT_BLOCKED`)

### 4.1 Business Invariant

> **100% Customer Receipt Guarantee:** The customer MUST receive **100% of `approvedCashTotal`** (`netRecipientVnd == approvedRefundVnd`). All bank/PayOS transfer fees and service credits MUST be borne solely by BOX Cinema (`SENDER_PAYS` or `NO_FEE`).

### 4.2 Why `PAYOS_PAYOUT_LIVE_FEE_POLICY` Defaults to `UNVERIFIED`

Official PayOS Payout v1 API documentation (`https://payos.vn/docs/api/` and `payos-lib-java v2.0.2`) does **not** expose a `feeBearer` request parameter on `POST /v1/payouts` and does **not** publish an API-level contractual guarantee that recipient accounts will never have transfer fees deducted.

Because fee deduction from the recipient cannot be ruled out purely from the API specification, BOX Cinema enforces a **fail-closed policy**:

| `PayoutFeeClassification` | `guaranteedFullReceipt` | `blocked` | Runtime Action in `PayOsPayoutService.prepareAttemptTx1` |
|---|---|---|---|
| `SENDER_PAYS` | `true` | `false` | Allowed — cinema pays fees, customer receives 100% of `approvedCashTotal`. |
| `NO_FEE` | `true` | `false` | Allowed — zero transfer fee, customer receives 100% of `approvedCashTotal`. |
| `UNVERIFIED` *(LIVE default)* | `false` | `true` | **Blocked (`PAYOUT_BLOCKED`)** — persists `payout_fee_assessments` + `PAYOUT_BLOCKED_FEE_POLICY` audit, retains `RESERVED` quota, **0 PayOS HTTP calls**. |
| `RECIPIENT_DEDUCTED` | `false` | `true` | **Blocked (`PAYOUT_BLOCKED`)** — violates 100% customer receipt invariant; **0 PayOS HTTP calls**. |
| `UNKNOWN` *(or assessment error / net mismatch)* | `false` | `true` | **Blocked (`PAYOUT_BLOCKED`)** — defensive fail-closed block; **0 PayOS HTTP calls**. |

### 4.3 Mandatory Verification Steps Before Changing `PAYOS_PAYOUT_LIVE_FEE_POLICY`

1. **Contractual / Support Verification:** Confirm in writing with PayOS Merchant Support (and review the active PayOS / Bảo Kim Payout contract & fee schedule on `my.payos.vn`) that:
   - The full `amount` field sent to `POST /v1/payouts` is credited 100% to `toAccountNumber` via NAPAS 24/7; and
   - Any disbursement fee or service credit is billed separately to the cinema's Bảo Kim / PayOS Payout account.
2. **Scoped Activation:** Only after Step 1 is confirmed in writing, set `PAYOS_PAYOUT_LIVE_FEE_POLICY=SENDER_PAYS` (or `NO_FEE` if contractually zero-fee) for the controlled live test window.
3. **Preflight API Proof:** Call `POST /api/admin/refunds/{id}/payout/assess-fee` and verify `classification` is `SENDER_PAYS` or `NO_FEE`, `guaranteedFullReceipt=true`, `blocked=false`, and `netRecipientVnd == approvedRefundVnd`.
4. **Empirical Bank-Statement Confirmation (Steps 12–13 below):** During the first small live transfer, verify directly on the recipient bank statement that the credited amount equals **100% of `approvedCashTotal`**. If even 1 VND is deducted from the recipient, **STOP IMMEDIATELY**, revert `PAYOS_PAYOUT_LIVE_FEE_POLICY=UNVERIFIED` and `LIVE_PAYOUT_ENABLED=false`, and open a ticket with PayOS support before allowing any further payouts.

---

## 5. Complete 15-Step Manual First Real-Money Transfer Procedure (Part 14.4)

Follow all 15 steps strictly in sequence for the first real-money payout test. Do not skip any step or batch multiple refunds during the initial live verification.

### Step 1 — Verify Initial Locked State (`LIVE_PAYOUT_ENABLED=false`) & Backup
1. Confirm the running server starts with default safe flags (`LIVE_PAYOUT_ENABLED=false`, `PAYOS_PAYOUT_MODE=MOCK`, `PAYOS_PAYOUT_LIVE_FEE_POLICY=UNVERIFIED`).
2. Call `GET /api/admin/payouts/preflight-status` as `ROLE_ADMIN` and verify:
   - `livePayoutEnabled: false`
   - `payoutMode: "MOCK"`
   - `liveDispatchReady: false`
3. Confirm the fresh pre-test MySQL dump from Section 1 Item 6 is saved outside Git and readable.

### Step 2 — Safely Enable Live Payout Mode (Controlled Test Window)
1. In the runtime environment (or git-ignored `.env`), set ONLY the Payout environment variables (using **environment variable names** from Section 2):
   - `PAYOS_PAYOUT_MODE=LIVE`
   - `LIVE_PAYOUT_ENABLED=true`
   - `PAYOS_PAYOUT_CLIENT_ID` *(dedicated Payout channel — distinct from Collection)*
   - `PAYOS_PAYOUT_API_KEY` *(dedicated Payout channel — distinct from Collection)*
   - `PAYOS_PAYOUT_CHECKSUM_KEY` *(dedicated Payout channel — distinct from Collection)*
   - `PAYOS_PAYOUT_LIVE_FEE_POLICY=SENDER_PAYS` *(or `NO_FEE`, strictly after Section 4 written verification)*
   - `BANK_DATA_ENC_KEY` *(strong >=32-byte key)*
   - Keep `PAYOS_PAYOUT_ALLOW_AUTO_FAIL_ON_NOT_FOUND=false` and `APP_SCHEDULING_ENABLED=true`.
2. Restart the Spring Boot application and inspect startup logs to confirm:
   - `PayOsPayoutService.validateConfiguration()` passed with zero exceptions (credentials present and distinct from Collection).
   - `BankDataEncryptionService.init()` passed with zero exceptions (`BANK_DATA_ENC_KEY` strength verified).
   - No secret values appear anywhere in startup logs.

### Step 3 — Run Preflight, Balance, Remote Listing & Credit Checks
1. Execute `GET /api/admin/payouts/preflight-status` -> verify `liveDispatchReady: true`, `credentialsDistinct: true`, `feePolicyGuaranteesFullReceipt: true`.
2. Execute `GET /api/admin/payouts/account-balance` -> verify `httpStatus: 200`, `responseCode: "00"`, `liveVerified: true`, and sufficient VND balance. Record the **opening balance**.
3. Execute `GET /api/admin/payouts/payos-remote?limit=5&offset=0` -> verify `httpStatus: 200`, `code: "00"`.
4. Confirm active Payout service credit balance on `my.payos.vn` (and/or `estimate-credit`).

### Step 4 — Select Exactly 1 Eligible Approved Small-Amount Refund
1. Log in to `/admin/refunds` as `ROLE_ADMIN` (a different user than the Manager who reviewed/submitted the request, honoring maker-checker).
2. Open the **Payout Queue** (`GET /api/admin/refunds/payout-queue`) and locate the **single** pre-approved, small-amount test refund request (e.g., `10,000 VND`) in status `AWAITING_PAYOUT_APPROVAL` whose recipient bank account belongs to the test operator.
3. Check `GET /api/admin/refunds/limits/summary?cinemaId=<id>` to confirm per-request, manager daily, cinema daily, and incident cumulative quotas have sufficient `AVAILABLE` headroom.

### Step 5 — Verify Recipient, Bank, Masked Account & Immutable Snapshot
1. Inspect full refund details (`GET /api/admin/refunds/{id}`) and single-item payout preview (`GET /api/admin/refunds/{id}/payout/preview`).
2. Verify every recipient field matches the operator's controlled test bank account:
   - `refundMethod` is `BANK_PAYOUT` (with fallback reason noting PayOS Collection VietQR does not support direct reversal)
   - Cinema name & order code (`bookingCode`)
   - Recipient account holder name (`accountHolderName`, normalized uppercase ASCII)
   - 6-digit NAPAS/VietQR bank BIN (`toBin`) and resolved bank name (`bankName`)
   - Masked account number (`toAccountNumberMasked`, last 4 digits only — full number is NEVER returned in API/UI)
   - Transfer description: `BOX REFUND <refundRequestId>` (sanitized ASCII, <=25 chars)
   - `recipientSnapshotHash` is populated and intact (if tampered in DB, dispatch will abort with HTTP `409 RECIPIENT_SNAPSHOT_TAMPERED` and persist a durable `RECIPIENT_SNAPSHOT_TAMPERED` row in `refund_audit_logs` via `PROPAGATION_REQUIRES_NEW`).

### Step 6 — Verify Exact Approved VND Refund Amount
1. Confirm `approvedCashTotal` (and preview `amountVnd` / `approvedRefundVnd`) is an exact positive integer VND amount matching the small test value (e.g., `10,000 VND`).
2. Confirm cumulative payouts on the booking do not exceed net paid amount (`booking.totalAmount - booking.serviceFee` capped by `PAID` payment amount; enforced server-side by `409 PAYOUT_CAP_EXCEEDED`).

### Step 7 — Run Transfer Fee Preflight Assessment
1. Trigger `POST /api/admin/refunds/{id}/payout/assess-fee` (or inspect the fee section inside the Payout Preview modal).
2. Confirm:
   - `providerName == "PAYOS"`
   - `classification` is `"SENDER_PAYS"` or `"NO_FEE"`
   - `guaranteedFullReceipt == true`
   - `blocked == false`
   - `netRecipientVnd == approvedRefundVnd`
3. If `blocked == true`, **STOP** — inspect `reason` and return to Section 4.

### Step 8 — Explicitly Confirm Single-Item Transfer in Admin UI
1. In `/admin/refunds`, open the confirmation modal for **that single refund request only** (do not select batch items).
2. Re-read the displayed cinema, recipient name, bank name, masked account (`****XXXX`), exact VND amount, transfer description, fee policy badge, and `LIVE` mode warning banner.
3. Click **Approve & Dispatch Payout** (`POST /api/admin/refunds/{id}/payout/approve` with optional operator note).
4. Record the returned `payos_payout_transactions` metadata:
   - Local transaction ID (`txId`)
   - `referenceId` (`RFD-<refundRequestId>-A1`)
   - `idempotencyKey` (UUID)
   - `payosPayoutId` (if returned synchronously)

### Step 9 — Monitor Immediate Post-Dispatch Status
1. Inspect the immediate response and open `GET /api/admin/payouts/{txId}` (and `/admin/refunds` Payout Transactions tab).
2. Observe:
   - `payoutMode == "LIVE"`
   - `status` (`SUCCEEDED`, `PROCESSING`, or `FAILED`)
   - `approvalState` (e.g., `COMPLETED`, `SUBMITTED`, `PROCESSING`, `PARTIAL_COMPLETED`)
   - `transactionState` (e.g., `SUCCEEDED`, `RECEIVED`, `PROCESSING`, `ON_HOLD`, `UNKNOWN_OUTCOME`)
   - `workflowStatus` (`SUCCESS`, `PROCESSING`, `UNKNOWN`, `FAILED`, or `PAYOUT_BLOCKED`)
3. **Important Webhook Note:** PayOS Payout v1 does **not** document official payout webhooks. Although BOX Cinema provides a hardened, signature-verified, idempotent endpoint at `POST /api/payments/payos/payout/webhook` as defensive support, **active polling/reconciliation (`GET /v1/payouts/{id}` / `GET /v1/payouts?referenceId=...`) is the primary source of truth**.

### Step 10 — Reconcile via `GET /v1/payouts/{id}` & Inspect `payout_reconciliation_logs`
1. If status is `PROCESSING` or `UNKNOWN` (or even to verify a fast completion), click **Reconcile** in `/admin/refunds` -> calls `POST /api/admin/payouts/{txId}/reconcile`:
   - Calls `GET /v1/payouts/{payosPayoutId}` (or `GET /v1/payouts?referenceId=<ref>&limit=10&offset=0` if `payosPayoutId` was not received due to timeout) with strict response header `x-signature` verification.
   - Cross-checks `referenceId`, `payosPayoutId`, `amount`, `toBin`, and beneficiary account hash before applying state changes.
   - Writes an immutable audit row in `payout_reconciliation_logs` (`trigger_source="MANUAL"`, `"SCHEDULED"`, `"PRE_RETRY"`, or `"WEBHOOK"`).
2. Inspect `GET /api/admin/payouts/{txId}/reconciliation-logs` and `GET /api/admin/payouts/payos-remote?referenceId=<referenceId>` to verify local state matches PayOS remote state 1:1.

### Step 11 — Handle Non-Terminal or Failure States Safely
If the transaction does not immediately reach `SUCCEEDED` / `SUCCESS`, follow this exact state-handling matrix (NEVER blindly send a duplicate transfer):

| Observed State (`workflowStatus` / `tx.status` / `tx.transactionState`) | Quota Reservation State | Safe Operator Action |
|---|---|---|
| `PROCESSING` / `PROCESSING` / `RECEIVED` or `PROCESSING` | `RESERVED` (retained) | Wait for scheduled poller (`30s`) or click **Reconcile** (`POST /api/admin/payouts/{txId}/reconcile`). Do NOT retry. |
| `PROCESSING` / `PROCESSING` / `ON_HOLD` | `RESERVED` (retained) | PayOS/bank compliance hold. Never treat as `SUCCESS`. Monitor `my.payos.vn` and reconcile until terminal (`SUCCEEDED` or `FAILED`). |
| `UNKNOWN` / `PROCESSING` / `UNKNOWN_OUTCOME` (timeout, 5xx, 429, interrupted code `632`/`633`/`634`/`606`, invalid response `x-signature`, or `PARTIAL_COMPLETED`) | `RESERVED` (retained) | **Active lock prevents duplicate `POST /v1/payouts`.** Clicking Retry or Reconcile runs `PRE_RETRY`/`MANUAL` `GET` reconciliation on the existing `referenceId`. Verify status on `my.payos.vn`. |
| `UNKNOWN` / `PROCESSING` / `MANUAL_RECONCILE_REQUIRED` (PayOS `608` or not found after 900s grace window) | `RESERVED` (retained) | Check `my.payos.vn` AND Bảo Kim transaction history. **ONLY** if definitively confirmed absent/failed on PayOS, call `POST /api/admin/payouts/{txId}/confirm-failed` with a mandatory verification note to release `active_request_lock` and quota reservation. |
| `FAILED` / `FAILED` / `FAILED` (4xx rejection, rejected by bank, or admin-confirmed failed) | `RELEASED` | Inspect sanitized `payosResponseCode` / `errorMessage` (e.g., invalid account, insufficient balance, IP whitelist). Fix root cause first; if bank details were wrong, have customer update bank info; then dispatch a new attempt (`RFD-<id>-A2`) via `POST /api/admin/refunds/{id}/retry-payout`. |
| `PAYOUT_BLOCKED` / *(no tx created)* | `RESERVED` (retained) | Fee policy failed closed (`UNVERIFIED` / `RECIPIENT_DEDUCTED` / `UNKNOWN`). Zero money sent. Resolve fee policy (Section 4), then resume via `POST /api/admin/refunds/{id}/retry-payout`. |

### Step 12 — Verify Actual Bank Credit Received == 100% of Approved Amount
1. Open the operator's recipient bank mobile app or online banking statement.
2. Confirm:
   - Incoming NAPAS 24/7 transfer arrived.
   - **Credited amount equals exactly 100% of `approvedCashTotal`** (zero VND deducted).
   - Transfer description contains `BOX REFUND <refundRequestId>`.
   - Transaction timestamp and bank reference align with `providerReference` / `succeededAt`.

### Step 13 — Verify Transfer Fee & Credit Charged Separately to Cinema
1. Open `my.payos.vn` (Payout channel history) and the linked Bảo Kim e-wallet transaction ledger.
2. Call `GET /api/admin/payouts/account-balance` again and compare **closing balance** vs **opening balance** (Step 3).
3. Verify:
   - Principal debited == `approvedCashTotal`.
   - Any transfer fee and/or payout service credit was billed separately to the cinema's PayOS/Bảo Kim account (confirming `SENDER_PAYS` or `NO_FEE`).
   - Zero deduction from the customer's received amount.

### Step 14 — Inspect Refund, Payout, Ledger, Audit & Log Hygiene
Verify all database records and application logs for the test refund request:
1. **`refund_requests`:** `workflow_status='SUCCESS'`, `payout_status='SUCCEEDED'`, `request_status='COMPLETED'` (or `PAYOUT_SUCCEEDED` if voucher recovery is still separate), `fee_assessment_status='SENDER_PAYS'` (or `'NO_FEE'`), `recipient_snapshot_hash` preserved.
2. **`payos_payout_transactions`:** `payout_mode='LIVE'`, `status='SUCCEEDED'`, `transaction_state='SUCCEEDED'`, `approval_state='COMPLETED'`, `payos_payout_id` and `provider_reference` populated, `to_account_number_masked` masked, `raw_response_sanitized` contains zero plaintext account numbers or secrets.
3. **`payout_fee_assessments`:** Linked to `refund_request_id` and `payout_transaction_id`, `guaranteed_full_receipt=1`, `blocked=0`, `net_recipient_vnd == approved_refund_vnd`.
4. **`payout_reconciliation_logs`:** Contains complete trail of reconciliation checks (`MANUAL` / `SCHEDULED` / etc.).
5. **`refund_limit_reservations`:** Reservation rows for this `refund_request_id` transitioned from `RESERVED` to `COMMITTED` (quota properly accounted, zero double-counting).
6. **`refund_audit_logs` & Email Outbox:** Contains `PAYOUT_SUCCEEDED` audit record and queued customer payout-succeeded email (showing masked account only).
7. **Server Log Check:** Confirm zero occurrences of full bank account numbers, `PAYOS_PAYOUT_API_KEY`, `PAYOS_PAYOUT_CHECKSUM_KEY`, or `BANK_DATA_ENC_KEY`.

### Step 15 — Immediately Disable Live Payout Mode After Testing
**Do NOT leave live payout flags enabled after completing the manual verification test.**
1. Immediately revert the runtime environment / `.env` to safe locked defaults:
   - `LIVE_PAYOUT_ENABLED=false`
   - `PAYOS_PAYOUT_MODE=MOCK`
   - `PAYOS_PAYOUT_LIVE_FEE_POLICY=UNVERIFIED` *(unless formal production go-live sign-off is simultaneously approved)*
2. Restart the Spring Boot application.
3. Call `GET /api/admin/payouts/preflight-status` as `ROLE_ADMIN` and confirm:
   - `livePayoutEnabled: false`
   - `payoutMode: "MOCK"`
   - `liveDispatchReady: false`
4. Archive the sanitized test evidence (preflight JSON, masked balance before/after, `referenceId`, `payosPayoutId`, masked bank receipt confirming 100% credit, and Step 15 lock-confirmation output) in the release sign-off ticket.

---

## 6. Emergency Stop & Rollback Procedure

- **Immediate Kill-Switch:** Set `LIVE_PAYOUT_ENABLED=false` and `PAYOS_PAYOUT_MODE=MOCK` and restart the service. All live payout dispatches immediately fail closed with HTTP `409 LIVE_PAYOUT_DISABLED` and scheduled live polling stops.
- **Database Rollback (if ever needed in a staging/test DB):** Restore the pre-test `mysqldump` snapshot taken in Section 1 Item 6. Never roll back a production database after a real bank transfer has succeeded without preserving the `payos_payout_transactions`, `payout_reconciliation_logs`, and `refund_audit_logs` financial records.
- **Official PayOS References:**
  - PayOS Payout v1 API Reference: `https://payos.vn/docs/api/`
  - PayOS Testing Environment Policy (No Sandbox): `https://payos.vn/docs/moi-truong-test`
  - PayOS Signature Verification Guide: `https://payos.vn/docs/tich-hop-webhook/kiem-tra-du-lieu-voi-signature`
  - Official PayOS Java SDK (`payos-lib-java v2.0.2`): `https://payos.vn/docs/sdks/back-end/java` / `https://github.com/payOSHQ/payos-lib-java`
