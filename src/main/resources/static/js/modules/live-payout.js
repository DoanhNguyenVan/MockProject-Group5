import { getCsrfHeaders } from '../core/http-client.js';

/**
 * Admin-only Live PayOS Payout control panel (real money). All server-provided text is rendered with
 * textContent (never innerHTML). The UI never auto-submits anything: executing requires the typed phrase,
 * the explicit acknowledgement, the re-typed amount and a single-use confirmation token from the server.
 * Server-side authorization/limits are authoritative; this panel only guides the Admin.
 */

export const LIVE_CONFIRM_PHRASE = 'CONFIRM LIVE PAYOUT';

export function isPhraseValid(value) {
  return value === LIVE_CONFIRM_PHRASE;
}

export function formatVnd(value) {
  const n = Number(value);
  if (!Number.isFinite(n)) return '—';
  return `${Math.trunc(n).toLocaleString('en-US')} VND`;
}

/** Pure gate for enabling the "Execute" button. */
export function canExecute(state) {
  if (!state || state.busy || !state.confirmation) return false;
  const c = state.confirmation;
  if (!c.readyToExecute || !c.confirmationToken) return false;
  if (!isPhraseValid(state.phrase)) return false;
  if (!state.acknowledged) return false;
  return Number(state.typedAmount) === Number(c.approvedAmountVnd) && Number(c.approvedAmountVnd) > 0;
}

export function initLivePayoutPanel(doc = (typeof document !== 'undefined' ? document : null), fetchImpl = (typeof fetch !== 'undefined' ? fetch : null)) {
  if (!doc) return null;
  const get = (id) => doc.getElementById(id);
  const root = get('livePayoutPanel');
  if (!root) return null;

  const state = { busy: false, confirmation: null, phrase: '', acknowledged: false, typedAmount: '' };

  const setText = (id, text) => { const el = get(id); if (el) el.textContent = text; };

  async function api(path, options = {}) {
    const res = await fetchImpl(`/api/admin${path}`, {
      credentials: 'same-origin',
      ...options,
      headers: { 'Content-Type': 'application/json', ...getCsrfHeaders(), ...(options.headers || {}) },
    });
    let body = null;
    try { body = await res.json(); } catch (e) { body = null; }
    if (!res.ok) {
      const code = body && (body.errorCode || body.code) ? `[${body.errorCode || body.code}] ` : '';
      throw new Error(`${code}${(body && (body.message || body.error)) || `HTTP ${res.status}`}`);
    }
    return body;
  }

  function showError(msg) {
    const el = get('liveError');
    if (!el) return;
    el.textContent = msg || '';
    el.classList.toggle('d-none', !msg);
  }

  function refreshExecuteButton() {
    state.phrase = (get('liveConfirmPhrase') || {}).value || '';
    state.acknowledged = !!(get('liveAckRealMoney') || {}).checked;
    state.typedAmount = (get('liveConfirmAmount') || {}).value || '';
    const btn = get('btnLiveExecute');
    if (btn) btn.disabled = !canExecute(state);
  }

  async function loadStatus() {
    try {
      const s = await api('/payouts/live-control');
      const live = s.effectiveMode === 'LIVE';
      const badge = get('liveModeBadge');
      if (badge) {
        badge.textContent = live ? 'LIVE — REAL MONEY ENABLED' : 'DISABLED';
        badge.className = `badge py-2 px-3 ${live ? 'bg-danger' : 'bg-secondary'}`;
      }
      setText('liveEnvInfo', `Env flag LIVE_PAYOUT_ENABLED=${s.livePayoutEnabled ? 'true' : 'false'} • Mode=${s.payoutMode} • Admin switch=${s.adminLiveMode} • Max test amount=${s.maxTestAmountVnd == null ? 'NOT SET' : formatVnd(s.maxTestAmountVnd)} • Credentials configured=${s.credentialsConfigured ? 'yes' : 'no'} • Fee policy=${s.configuredFeePolicy}`);
      showError('');
    } catch (e) { showError(e.message); }
  }

  async function putJson(path, body) {
    return api(path, { method: 'PUT', body: JSON.stringify(body) });
  }

  async function onSetMode(enabled) {
    if (state.busy) return;
    if (enabled && !(typeof window !== 'undefined' && window.confirm
      && window.confirm('Enable LIVE real-money payout? Every payout still needs its own typed confirmation.'))) return;
    state.busy = true;
    try {
      await putJson('/payouts/live-control/mode', { enabled, note: (get('liveModeNote') || {}).value || '' });
      await loadStatus();
    } catch (e) { showError(e.message); } finally { state.busy = false; }
  }

  async function onSetMax() {
    if (state.busy) return;
    const raw = (get('liveMaxAmountInput') || {}).value;
    const amount = Number(raw);
    if (!Number.isInteger(amount) || amount <= 0) { showError('Enter a positive integer VND maximum test amount.'); return; }
    state.busy = true;
    try {
      await putJson('/payouts/live-control/max-amount', { maxAmountVnd: amount, note: (get('liveModeNote') || {}).value || '' });
      await loadStatus();
    } catch (e) { showError(e.message); } finally { state.busy = false; }
  }

  function renderConfirmation(c) {
    const box = get('liveConfirmationBox');
    const dl = get('liveConfirmationFields');
    if (!box || !dl) return;
    while (dl.firstChild) dl.removeChild(dl.firstChild);
    const rows = [
      ['1. Refund request ID', `${c.refundRequestId} (${c.requestCode || '—'})`],
      ['2. Approved refund amount', formatVnd(c.approvedAmountVnd)],
      ['3. Destination bank', `${c.destinationBankName || '—'} (BIN ${c.destinationBin || '—'})`],
      ['4. Destination account (masked)', c.destinationAccountMasked || '—'],
      ['5. Account-holder name', `${c.accountHolderName || '—'} ${c.accountHolderNameVerifiedByProvider ? '(provider-verified)' : '(NOT verified by provider — check manually)'}`],
      ['6. Estimated transfer fee', `${formatVnd(c.estimatedFeeVnd)} (${c.feeClassification || '—'})`],
      ['7. Recipient will receive', formatVnd(c.expectedRecipientAmountVnd)],
      ['8. Source wallet balance', c.sourceWalletBalance == null ? 'unavailable' : `${c.sourceWalletBalance} ${c.sourceWalletCurrency || ''} ${c.sourceWalletVerified ? '(verified)' : '(NOT verified)'}`],
      ['9. Unique payout reference', c.payoutReference || '—'],
      ['Transfer description', c.transferDescription || '—'],
      ['Max test amount', c.maxTestAmountVnd == null ? 'NOT SET' : formatVnd(c.maxTestAmountVnd)],
    ];
    rows.forEach(([k, v]) => {
      const dt = doc.createElement('dt'); dt.textContent = k;
      const dd = doc.createElement('dd'); dd.textContent = v;
      dl.appendChild(dt); dl.appendChild(dd);
    });
    setText('liveWarning', c.warning || 'WARNING: this operation transfers REAL MONEY.');
    const bl = get('liveBlockers');
    if (bl) {
      while (bl.firstChild) bl.removeChild(bl.firstChild);
      (c.blockers || []).forEach((b) => { const li = doc.createElement('li'); li.textContent = b; bl.appendChild(li); });
      bl.classList.toggle('d-none', !(c.blockers || []).length);
    }
    box.classList.remove('d-none');
    ['liveConfirmPhrase', 'liveConfirmAmount'].forEach((id) => { const el = get(id); if (el) el.value = ''; });
    const ack = get('liveAckRealMoney'); if (ack) ack.checked = false;
    refreshExecuteButton();
  }

  async function onLoadConfirmation() {
    if (state.busy) return;
    const id = Number((get('liveRefundIdInput') || {}).value);
    if (!Number.isInteger(id) || id <= 0) { showError('Enter a valid refund request ID.'); return; }
    state.busy = true; state.confirmation = null;
    try {
      const c = await api(`/refunds/${id}/payout/live-confirmation`);
      state.confirmation = c;
      renderConfirmation(c);
      setText('liveResult', '');
      showError('');
    } catch (e) { showError(e.message); } finally { state.busy = false; refreshExecuteButton(); }
  }

  async function onExecute() {
    if (!canExecute(state)) return;
    const c = state.confirmation;
    const payload = {
      confirmationToken: c.confirmationToken,
      confirmPhrase: state.phrase,
      confirmAmountVnd: Number(state.typedAmount),
      acknowledgeRealMoney: true,
      note: (get('liveModeNote') || {}).value || '',
    };
    // Single-use: drop the token immediately so a double-click can never send a second request.
    state.busy = true; state.confirmation = null; refreshExecuteButton();
    try {
      const res = await api(`/refunds/${c.refundRequestId}/payout/live-execute`, { method: 'POST', body: JSON.stringify(payload) });
      setText('liveResult', `${res.message || 'Dispatch returned.'} Reference: ${res.payoutReference || c.payoutReference}`);
      showError('');
    } catch (e) {
      showError(`${e.message} — Do NOT retry blindly: check the payout transaction list and reconcile the original reference first.`);
    } finally {
      state.busy = false; refreshExecuteButton(); loadStatus();
    }
  }

  const bind = (id, evt, fn) => { const el = get(id); if (el) el.addEventListener(evt, fn); };
  bind('btnLiveEnable', 'click', () => onSetMode(true));
  bind('btnLiveDisable', 'click', () => onSetMode(false));
  bind('btnLiveSetMax', 'click', onSetMax);
  bind('btnLiveLoadConfirmation', 'click', onLoadConfirmation);
  bind('btnLiveExecute', 'click', onExecute);
  bind('liveConfirmPhrase', 'input', refreshExecuteButton);
  bind('liveConfirmAmount', 'input', refreshExecuteButton);
  bind('liveAckRealMoney', 'change', refreshExecuteButton);
  bind('btnLiveRefreshStatus', 'click', loadStatus);

  loadStatus();
  return { state, loadStatus, refreshExecuteButton };
}

if (typeof document !== 'undefined' && document.getElementById && document.getElementById('livePayoutPanel')) {
  initLivePayoutPanel();
}
