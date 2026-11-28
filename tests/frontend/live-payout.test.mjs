import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { isPhraseValid, canExecute, formatVnd, LIVE_CONFIRM_PHRASE } from '../../src/main/resources/static/js/modules/live-payout.js';

// MOCK/UI-LOGIC ONLY: no network, no PayOS.

const ready = { readyToExecute: true, confirmationToken: 'tok', approvedAmountVnd: 10000 };

test('phrase must match exactly and case-sensitively', () => {
  assert.equal(LIVE_CONFIRM_PHRASE, 'CONFIRM LIVE PAYOUT');
  assert.equal(isPhraseValid('CONFIRM LIVE PAYOUT'), true);
  assert.equal(isPhraseValid('confirm live payout'), false);
  assert.equal(isPhraseValid('CONFIRM LIVE PAYOUT '), false);
  assert.equal(isPhraseValid(''), false);
});

test('execute is enabled only with phrase + acknowledgement + exact amount + ready confirmation', () => {
  const ok = { busy: false, confirmation: ready, phrase: LIVE_CONFIRM_PHRASE, acknowledged: true, typedAmount: '10000' };
  assert.equal(canExecute(ok), true);
  assert.equal(canExecute({ ...ok, phrase: 'nope' }), false);
  assert.equal(canExecute({ ...ok, acknowledged: false }), false);
  assert.equal(canExecute({ ...ok, typedAmount: '9999' }), false);
  assert.equal(canExecute({ ...ok, typedAmount: '' }), false);
  assert.equal(canExecute({ ...ok, busy: true }), false, 'double-click while busy is ignored');
  assert.equal(canExecute({ ...ok, confirmation: null }), false, 'token consumed => disabled');
  assert.equal(canExecute({ ...ok, confirmation: { ...ready, readyToExecute: false } }), false, 'blockers present');
  assert.equal(canExecute({ ...ok, confirmation: { ...ready, confirmationToken: undefined } }), false);
});

test('formatVnd is safe on bad input', () => {
  assert.equal(formatVnd(10000), '10,000 VND');
  assert.equal(formatVnd('abc'), '—');
});

test('refunds.html wires the live panel and module, never innerHTML for server data', () => {
  const html = fs.readFileSync('src/main/resources/templates/admin/refunds.html', 'utf8');
  for (const id of ['livePayoutPanel', 'liveModeBadge', 'btnLiveEnable', 'btnLiveDisable', 'liveMaxAmountInput',
    'btnLiveSetMax', 'liveRefundIdInput', 'btnLiveLoadConfirmation', 'liveConfirmPhrase', 'liveConfirmAmount',
    'liveAckRealMoney', 'btnLiveExecute']) {
    assert.ok(html.includes(`id="${id}"`), `missing #${id}`);
  }
  assert.ok(html.includes('live-payout.js'));
  assert.ok(/id="btnLiveExecute"[^>]*disabled/.test(html), 'execute starts disabled');
  const js = fs.readFileSync('src/main/resources/static/js/modules/live-payout.js', 'utf8');
  assert.equal(/innerHTML\s*=/.test(js), false);
  assert.equal(/api[-_ ]?key|checksum/i.test(js), false, 'no credentials in frontend');
});
