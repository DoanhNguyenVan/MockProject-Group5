# Admin-Controlled Live PayOS Payout — Handover

No secret values appear in this document. No real payout was executed or attempted by the AI.

## What was added (existing Stage 0–4 work preserved)
- `LivePayoutGate` — runtime Admin gate (default DISABLED each start, no max amount, no authorization), audited mode/max changes, single-use confirmation tokens, per-refund single-use dispatch authorization, single-flight lock.
- `LivePayoutWorkflowService` — read-only confirmation screen data + guarded `execute` (typed phrase, acknowledgement, re-typed amount, token, fresh re-evaluation of balance/fee/eligibility).
- `PayOsPayoutService` — a NEW real `POST /v1/payouts` now also requires the gate (checked right before the payout row is created). Reconcile paths are unaffected. Preflight adds `adminLiveEnabled`, `maxTestAmountConfigured`, `liveExecutionReady`.
- `AdminRefundApiController` (ADMIN only) — `GET /api/admin/payouts/live-control`, `PUT .../live-control/mode`, `PUT .../live-control/max-amount`, `GET /api/admin/refunds/{id}/payout/live-confirmation`, `POST /api/admin/refunds/{id}/payout/live-execute`. In LIVE, plain `/payout/approve` returns `LIVE_CONFIRMATION_REQUIRED` and `/payout/batch-approve` returns `LIVE_BATCH_PAYOUT_DISABLED`.
- UI: panel "LIVE PAYOUT CONTROL" at the top of `/admin/refunds` (`live-payout.js`).

## Activation (manual, by the Admin)
1. Back up the DB (see `04-live-test-guide.md` §1). Keep `PAYOS_PAYOUT_*` credentials distinct from collection credentials.
2. In git-ignored `.env`: `PAYOS_PAYOUT_MODE=LIVE`, `LIVE_PAYOUT_ENABLED=true`, `PAYOS_PAYOUT_LIVE_FEE_POLICY=SENDER_PAYS` (or `NO_FEE`) ONLY after PayOS confirms in writing that the recipient gets 100%. Restart the app.
3. Log in as ADMIN → `/admin/refunds` → panel: confirm env info shows Mode=LIVE, credentials configured.
4. Click **Enable LIVE** (audited). Enter **your own** max test amount → **Set max test amount**.
5. Enter one approved refund ID → **Review live payout**. Check all 9 fields and that there are no blockers.
6. Re-type the amount, type `CONFIRM LIVE PAYOUT`, tick the acknowledgement → **Execute REAL payout**.
7. If status is PROCESSING/UNKNOWN: use Reconcile on the original reference; never retry/create another payout. Verify the bank statement shows 100%.
8. Click **Disable**, then revert `.env` to `LIVE_PAYOUT_ENABLED=false`, `PAYOS_PAYOUT_MODE=MOCK`, and restart.

## Verification (MOCK ONLY — not provider verification)
- `LivePayoutGateAndWorkflowTest` 21/21, `LivePayoutControlAuthorizationTest` 4/4, `tests/frontend/live-payout.test.mjs` 4/4.
- Full `mvn test`: 240/240; `node --test tests/frontend/*.test.mjs`: 80/80.

## Not verified against the real provider (blockers until you do)
PayOS account/KYC, payout channel activation, Bảo Kim wallet link, allowed-IP whitelist, production auth, wallet balance, credit estimation, written fee guarantee, webhook configuration (PayOS documents no payout webhook; polling/reconcile is the source of truth). Account-holder name is shown but flagged "not verified by provider".

## Limits of design
Gate state is in-process (single instance). Restarting resets it to DISABLED by design.
