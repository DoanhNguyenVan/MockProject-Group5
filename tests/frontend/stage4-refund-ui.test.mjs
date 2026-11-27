import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { initContactRefund } from '../../src/main/resources/static/js/modules/contact-refund.js';
import { initRefundOperationsWorkspace, formatPayoutStage } from '../../src/main/resources/static/js/modules/refund-ops.js';

function createFakeElement(tag, id = '') {
  const listeners = {};
  const attrs = {};
  let ownText = '';
  let rawValue = '';

  const el = {
    tagName: String(tag || 'DIV').toUpperCase(),
    id,
    className: '',
    disabled: false,
    checked: false,
    required: false,
    type: '',
    rows: 0,
    min: '',
    max: '',
    placeholder: '',
    href: '',
    target: '',
    rel: '',
    style: {},
    dataset: {},
    files: [],
    children: [],
    options: [],
    get value() {
      return rawValue;
    },
    set value(v) {
      const next = v === null || v === undefined ? '' : String(v);
      if (el.tagName === 'SELECT' && el.options.length > 0) {
        rawValue = el.options.some(o => String(o.value) === next) ? next : '';
      } else {
        rawValue = next;
      }
    },
    get textContent() {
      if (!el.children.length) return ownText;
      const childText = el.children.map(c => c.textContent || '').filter(Boolean).join(' ');
      return ownText && childText ? `${ownText} ${childText}` : (ownText || childText);
    },
    set textContent(v) {
      ownText = v === null || v === undefined ? '' : String(v);
      el.children = [];
      el.options = [];
    },
    classList: {
      add(...cls) {
        const cur = new Set(el.className.split(/\s+/).filter(Boolean));
        cls.forEach(c => cur.add(c));
        el.className = Array.from(cur).join(' ');
      },
      remove(...cls) {
        const cur = new Set(el.className.split(/\s+/).filter(Boolean));
        cls.forEach(c => cur.delete(c));
        el.className = Array.from(cur).join(' ');
      },
      contains(c) {
        return el.className.split(/\s+/).includes(c);
      }
    },
    get firstChild() {
      return el.children[0] || null;
    },
    setAttribute(k, v) {
      attrs[k] = String(v);
      if (k === 'id') el.id = String(v);
      if (k === 'class') el.className = String(v);
    },
    getAttribute(k) {
      return attrs[k] ?? null;
    },
    append(...nodes) {
      for (const n of nodes) {
        if (!n) continue;
        el.children.push(n);
        if (n.tagName === 'OPTION') {
          el.options.push(n);
          if (el.tagName === 'SELECT' && rawValue === '' && el.options.length === 1) {
            rawValue = String(n.value ?? '');
          }
        }
      }
    },
    replaceChildren(...nodes) {
      ownText = '';
      el.children = [];
      el.options = [];
      el.append(...nodes);
    },
    addEventListener(type, fn) {
      listeners[type] = listeners[type] || [];
      listeners[type].push(fn);
    },
    dispatchEvent(ev) {
      const eventObj = {
        preventDefault() {},
        target: el,
        ...ev
      };
      for (const fn of listeners[eventObj.type] || []) {
        fn(eventObj);
      }
    },
    click() {
      if (typeof el.onclick === 'function') {
        el.onclick({ type: 'click', target: el, preventDefault() {} });
      }
      el.dispatchEvent({ type: 'click', target: el });
    },
    focus() {},
    querySelector(sel) {
      return findInSubtree(el, node => matchesSelector(node, sel))[0] || null;
    },
    querySelectorAll(sel) {
      return findInSubtree(el, node => matchesSelector(node, sel));
    }
  };
  return el;
}

function findInSubtree(root, predicate, acc = []) {
  for (const child of root.children || []) {
    if (predicate(child)) acc.push(child);
    findInSubtree(child, predicate, acc);
  }
  return acc;
}

function matchesSelector(node, sel) {
  if (!node || !sel) return false;
  if (sel === 'button[type="submit"]') {
    return node.tagName === 'BUTTON' && String(node.type).toLowerCase() === 'submit';
  }
  if (sel === '.refund-item-check:checked') {
    return node.classList.contains('refund-item-check') && Boolean(node.checked);
  }
  if (sel === '.refund-item-check') {
    return node.classList.contains('refund-item-check');
  }
  const qtyMatch = sel.match(/^\.refund-item-qty\[data-item-kind="([^"]+)"\]\[data-target-id="([^"]+)"\]$/);
  if (qtyMatch) {
    return node.classList.contains('refund-item-qty')
      && String(node.dataset.itemKind) === qtyMatch[1]
      && String(node.dataset.targetId) === qtyMatch[2];
  }
  if (sel.startsWith('.')) {
    return node.classList.contains(sel.slice(1));
  }
  if (sel.startsWith('#')) {
    return node.id === sel.slice(1);
  }
  return node.tagName === sel.toUpperCase();
}

function createFakeDocument(ids, { search = '' } = {}) {
  const elements = {};
  for (const [id, tag] of Object.entries(ids)) {
    elements[id] = createFakeElement(tag, id);
  }
  const allNodes = () => {
    const seen = new Set();
    const list = [];
    const walk = n => {
      if (!n || seen.has(n)) return;
      seen.add(n);
      list.push(n);
      for (const c of n.children || []) walk(c);
    };
    Object.values(elements).forEach(walk);
    return list;
  };
  return {
    elements,
    defaultView: { location: { search } },
    getElementById(id) {
      if (elements[id]) return elements[id];
      return allNodes().find(n => n.id === id) || null;
    },
    createElement(tag) {
      return createFakeElement(tag);
    },
    querySelector(sel) {
      return allNodes().find(n => matchesSelector(n, sel)) || null;
    },
    querySelectorAll(sel) {
      return allNodes().filter(n => matchesSelector(n, sel));
    }
  };
}

function jsonResponse(body, status = 200, headers = {}) {
  const normHeaders = {};
  for (const [k, v] of Object.entries(headers)) {
    normHeaders[k.toLowerCase()] = String(v);
  }
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: {
      get(name) {
        return normHeaders[String(name || '').toLowerCase()] ?? null;
      }
    },
    async json() {
      return body;
    }
  };
}

async function flushAsync(turns = 10) {
  for (let i = 0; i < turns; i++) {
    await new Promise(resolve => setTimeout(resolve, 0));
  }
}

function buildContactIds() {
  return {
    refundSupportRoot: 'div',
    'tab-inquiry-btn': 'button',
    'tab-refund-btn': 'button',
    'tab-lookup-btn': 'button',
    'tab-inquiry': 'div',
    'tab-refund': 'div',
    'tab-lookup': 'div',
    refundOrderCode: 'input',
    refundPurchaseEmail: 'input',
    btnLookupOrder: 'button',
    orderLookupStatus: 'div',
    refundOrderDetailsBox: 'div',
    refundOrderMeta: 'div',
    refundSelectedTotalBadge: 'span',
    refundItemList: 'div',
    publicRefundForm: 'form',
    refundCustomerName: 'input',
    refundCustomerPhone: 'input',
    refundReasonCategory: 'select',
    refundEvidenceFile: 'input',
    refundEvidenceUrl: 'input',
    refundDescription: 'textarea',
    refundSavedBankWrap: 'div',
    refundSavedBankSelect: 'select',
    refundBankBin: 'select',
    refundBankHolder: 'input',
    refundAccountNumber: 'input',
    refundAccountNumberConfirm: 'input',
    refundAccountConfirmError: 'div',
    refundBankNote: 'input',
    refundSaveBankWrap: 'div',
    refundSaveBankAccount: 'input',
    refundSubmitStatus: 'div',
    refundStatusLookupForm: 'form',
    lookupRefundCode: 'input',
    lookupRefundEmail: 'input',
    refundLookupStatus: 'div',
    refundLookupResult: 'div'
  };
}

function buildOpsIds() {
  return {
    refundOpsRoot: 'main',
    opsPayoutPreflightBadge: 'span',
    opsLimitSummaryBanner: 'div',
    opsLimitSummaryContent: 'div',
    btnOpsReloadLimits: 'button',
    adminLimitSummaryBox: 'div',
    limitSummaryManagerId: 'input',
    limitSummaryIncidentId: 'input',
    limitSummaryDate: 'input',
    btnAdminReloadLimitSummary: 'button',
    opsLimitConfigTableBody: 'tbody',
    limitConfigKeySelect: 'select',
    limitAmountVndInput: 'input',
    limitChangeReasonInput: 'input',
    btnSaveLimitConfig: 'button',
    btnReloadLimitConfigs: 'button',
    limitConfigStatusMsg: 'div',
    opsCinemaFilter: 'select',
    payoutQueueSearch: 'input',
    payoutQueueCinemaFilter: 'select',
    payoutQueueCategoryFilter: 'select',
    payoutQueueVerifierFilter: 'input',
    payoutQueueMinAmount: 'input',
    payoutQueueMaxAmount: 'input',
    payoutQueueFromDate: 'input',
    payoutQueueToDate: 'input',
    btnFilterPayoutQueue: 'button',
    payoutQueueSelectAll: 'input',
    payoutQueueTableBody: 'tbody',
    payoutQueueSelectionSummary: 'span',
    btnOpenBatchConfirm: 'button',
    btnCheckPayoutBalance: 'button',
    payoutBalanceInfo: 'div',
    batchExecutionResultPanel: 'div',
    opsRefundSearch: 'input',
    opsRefundStatusFilter: 'select',
    opsPayoutStatusFilter: 'select',
    opsReasonCategoryFilter: 'select',
    opsVerifierFilter: 'input',
    opsMinAmountFilter: 'input',
    opsMaxAmountFilter: 'input',
    opsFromDateFilter: 'input',
    opsToDateFilter: 'input',
    btnOpsFilterRefunds: 'button',
    opsRefundTableBody: 'tbody',
    opsRefundDetailPanel: 'div',
    opsAppealCinemaFilter: 'select',
    opsAppealStatusFilter: 'select',
    btnOpsFilterAppeals: 'button',
    opsAppealTableBody: 'tbody',
    opsAppealDetailPanel: 'div',
    payoutTxSearch: 'input',
    payoutTxBatchFilter: 'input',
    payoutTxStatusFilter: 'select',
    payoutTxFromDate: 'input',
    payoutTxToDate: 'input',
    btnFilterPayoutTx: 'button',
    payoutTxTableBody: 'tbody',
    payoutTxDetailPanel: 'div',
    payoutConfirmModal: 'div',
    confirmModalTitle: 'h5',
    confirmPreflightBanner: 'div',
    confirmFeeBlockedBanner: 'div',
    confirmSelectedCount: 'strong',
    confirmSelectedTotal: 'strong',
    confirmIneligibleWarning: 'div',
    confirmItemTableBody: 'tbody',
    confirmNoteInput: 'input',
    confirmLimitOverrideApproved: 'input',
    confirmLimitOverrideReason: 'input',
    confirmModalError: 'div',
    btnConfirmCancel: 'button',
    btnConfirmExecute: 'button',
    payoutRejectModal: 'div',
    rejectModalTargetLabel: 'div',
    rejectReasonInput: 'textarea',
    rejectModalError: 'div',
    btnRejectCancel: 'button',
    btnRejectConfirm: 'button',
    opsIncidentFormCard: 'div',
    incFormHeading: 'h6',
    incFormId: 'input',
    incFormCinemaId: 'select',
    incFormGroup: 'select',
    incFormType: 'input',
    incFormSeverity: 'select',
    incFormTitle: 'input',
    incFormCustomerNotifiedAt: 'input',
    incFormShowtimeId: 'input',
    incFormBookingCode: 'input',
    incFormAffectedQty: 'input',
    incFormDescription: 'textarea',
    incFormEvidenceUrl: 'input',
    btnSaveIncident: 'button',
    btnCancelIncidentEdit: 'button',
    opsIncidentFormStatus: 'div',
    opsIncidentGroupFilter: 'select',
    opsIncidentStatusFilter: 'select',
    btnOpsFilterIncidents: 'button',
    opsIncidentTableBody: 'tbody',
    opsIncidentDetailPanel: 'div',
    btnOpsReloadStaffErrors: 'button',
    opsStaffErrorTableBody: 'tbody'
  };
}

test('Stage 4 Security & Template Option Integrity: zero unsafe DOM sinks and matching RefundLimitConfig keys', () => {
  const jsFiles = [
    '../../src/main/resources/static/js/modules/contact-refund.js',
    '../../src/main/resources/static/js/modules/refund-ops.js'
  ];
  for (const rel of jsFiles) {
    const src = fs.readFileSync(new URL(rel, import.meta.url), 'utf8');
    assert.doesNotMatch(src, /\.innerHTML\s*=/, `${rel} must not assign .innerHTML`);
    assert.doesNotMatch(src, /\.outerHTML\s*=/, `${rel} must not assign .outerHTML`);
    assert.doesNotMatch(src, /insertAdjacentHTML\s*\(/, `${rel} must not call insertAdjacentHTML`);
    assert.doesNotMatch(src, /document\.write\s*\(/, `${rel} must not call document.write`);
    assert.doesNotMatch(src, /\beval\s*\(/, `${rel} must not call eval`);
  }

  const adminRefundsHtml = fs.readFileSync(
    new URL('../../src/main/resources/templates/admin/refunds.html', import.meta.url),
    'utf8'
  );
  const selectMatch = adminRefundsHtml.match(/<select[^>]*id="limitConfigKeySelect"[^>]*>([\s\S]*?)<\/select>/);
  assert.ok(selectMatch, 'admin/refunds.html must contain #limitConfigKeySelect');
  const optionValues = Array.from(selectMatch[1].matchAll(/<option\s+value="([^"]+)"/g), m => m[1]);
  assert.deepEqual(
    optionValues,
    ['MANAGER_PER_REQUEST', 'MANAGER_DAILY', 'CINEMA_DAILY', 'INCIDENT_CUMULATIVE'],
    'admin/refunds.html #limitConfigKeySelect option values must match RefundLimitConfig backend keys'
  );
});

test('Stage 4 Customer UI: ?tab=refund&orderCode=...&email=... auto-switches tab, prefills, calls lookup-order, renders breakdown, Asia/Ho_Chi_Minh deadline & USED/CHECKED_IN notice', async () => {
  const origFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (url, opts = {}) => {
    calls.push({ url: String(url), method: opts.method || 'GET', body: opts.body });
    if (String(url) === '/api/refunds/public/banks') {
      return jsonResponse({ '970422': 'MB Bank' });
    }
    if (String(url) === '/api/customer/bank-accounts') {
      return jsonResponse([], 401);
    }
    if (String(url) === '/api/refunds/public/lookup-order') {
      return jsonResponse({
        bookingId: 77,
        bookingCode: 'BOX-20261127-9001',
        orderType: 'TICKET_AND_FOOD',
        bookingStatus: 'CONFIRMED',
        cinemaId: 1,
        cinemaName: 'BOX Cinema Royal City',
        totalPaidAmount: 100000,
        discountAmount: 20000,
        voucherCode: 'PROMO20K',
        checkedIn: true,
        refundDeadlineAt: '2026-11-30T21:30:00',
        deadlineBasis: 'LATE_INCIDENT_7D',
        deadlineExceeded: false,
        items: [
          {
            itemKind: 'TICKET',
            targetId: 501,
            label: 'Seat A1 (Standard)',
            purchasedQuantity: 1,
            remainingRefundableQuantity: 1,
            grossAmount: 120000,
            discountAllocated: 20000,
            netPaidAmount: 100000,
            itemStatus: 'USED',
            checkedIn: true,
            eligibleHint: true,
            eligibilityNote: 'Checked-in ticket eligible for incident review'
          }
        ]
      });
    }
    return jsonResponse({}, 404);
  };

  try {
    const doc = createFakeDocument(buildContactIds(), {
      search: '?tab=refund&orderCode=BOX-20261127-9001&email=guest%40box.test'
    });
    doc.elements['tab-inquiry-btn'].className = 'active';
    doc.elements['tab-inquiry'].className = 'tab-pane fade show active';
    doc.elements['tab-refund'].className = 'tab-pane fade';
    doc.elements.refundOrderDetailsBox.className = 'd-none';

    initContactRefund(doc);
    await flushAsync();

    assert.equal(doc.elements['tab-refund-btn'].classList.contains('active'), true);
    assert.equal(doc.elements['tab-refund'].classList.contains('active'), true);
    assert.equal(doc.elements['tab-inquiry'].classList.contains('active'), false);
    assert.equal(doc.elements.refundOrderCode.value, 'BOX-20261127-9001');
    assert.equal(doc.elements.refundPurchaseEmail.value, 'guest@box.test');

    const lookupCall = calls.find(c => c.url === '/api/refunds/public/lookup-order' && c.method === 'POST');
    assert.ok(lookupCall, 'must call POST /api/refunds/public/lookup-order automatically');
    assert.deepEqual(JSON.parse(lookupCall.body), {
      orderCode: 'BOX-20261127-9001',
      purchaseEmail: 'guest@box.test'
    });

    const metaText = doc.elements.refundOrderMeta.textContent;
    assert.match(metaText, /Refund Window \(Asia\/Ho_Chi_Minh\): 2026-11-30T21:30:00 \[LATE_INCIDENT_7D\]/);
    assert.match(metaText, /WITHIN REFUND WINDOW/);
    assert.match(metaText, /USED \/ CHECKED_IN tickets are eligible for submission and Manager\/Admin review/);
    assert.match(metaText, /Backend Refundable Balance: 100\.000 VND/);

    const itemText = doc.elements.refundItemList.textContent;
    assert.match(itemText, /Gross: 120\.000 VND/);
    assert.match(itemText, /Net Paid: 100\.000 VND/);
    assert.match(itemText, /Voucher Disc: 20\.000 VND/);
    assert.match(itemText, /Status: USED \(Reviewable under Incident\)/);

    const cb = doc.getElementById('rfi-TICKET-501');
    assert.ok(cb, 'USED ticket checkbox must be rendered');
    assert.equal(cb.disabled, false, 'USED ticket must NOT be client-side disabled when remainingRefundableQuantity > 0');
    cb.checked = true;
    cb.dispatchEvent({ type: 'change' });
    assert.match(doc.elements.refundSelectedTotalBadge.textContent, /Selected Refundable Total: 100\.000 VND/);
  } finally {
    globalThis.fetch = origFetch;
  }
});

test('Stage 4 Customer UI: evidence upload uses /api/refunds/evidence/upload with public fallback and surfaces 400 quarantine & 429 Retry-After', async () => {
  const origFetch = globalThis.fetch;
  let scenario = 'fallback_clean';
  const uploadCalls = [];

  globalThis.fetch = async (url, opts = {}) => {
    const u = String(url);
    if (u === '/api/refunds/public/banks') return jsonResponse({});
    if (u === '/api/customer/bank-accounts') return jsonResponse([], 401);
    if (u === '/api/refunds/evidence/upload' || u === '/api/refunds/public/evidence-upload') {
      uploadCalls.push({ url: u, method: opts.method });
      if (scenario === 'fallback_clean') {
        if (u === '/api/refunds/evidence/upload') return jsonResponse({ message: 'Unauthorized' }, 401);
        return jsonResponse({ url: '/api/refunds/evidence/ev-clean-1.png' }, 200);
      }
      if (scenario === 'quarantined_400') {
        return jsonResponse({
          errorCode: 'EVIDENCE_QUARANTINED',
          message: 'Embedded script signature rejected'
        }, 400);
      }
      if (scenario === 'ratelimit_429') {
        return jsonResponse(
          { errorCode: 'RATE_LIMITED', message: 'Upload rate limit exceeded' },
          429,
          { 'Retry-After': '45' }
        );
      }
    }
    return jsonResponse({}, 404);
  };

  try {
    const doc = createFakeDocument(buildContactIds());
    initContactRefund(doc);
    await flushAsync();

    doc.elements.refundEvidenceFile.files = [{ name: 'proof.png', type: 'image/png' }];
    doc.elements.refundEvidenceFile.dispatchEvent({ type: 'change' });
    await flushAsync();

    assert.deepEqual(
      uploadCalls.map(c => c.url),
      ['/api/refunds/evidence/upload', '/api/refunds/public/evidence-upload']
    );
    assert.equal(doc.elements.refundEvidenceUrl.value, '/api/refunds/evidence/ev-clean-1.png');
    assert.match(doc.elements.refundSubmitStatus.textContent, /CLEAN/);

    uploadCalls.length = 0;
    scenario = 'quarantined_400';
    doc.elements.refundEvidenceFile.dispatchEvent({ type: 'change' });
    await flushAsync();
    assert.equal(uploadCalls.length, 1, '400 validation/quarantine must not retry fallback endpoint');
    assert.match(doc.elements.refundSubmitStatus.textContent, /Security Scan Quarantined File: Embedded script signature rejected/);

    uploadCalls.length = 0;
    scenario = 'ratelimit_429';
    doc.elements.refundEvidenceFile.dispatchEvent({ type: 'change' });
    await flushAsync();
    assert.match(doc.elements.refundSubmitStatus.textContent, /Rate limit 429 — Retry-After: 45s/);
  } finally {
    globalThis.fetch = origFetch;
  }
});

test('Stage 4 Customer UI: tracking tab renders distinct workflow/payout/fee/PAYOUT_BLOCKED/appeal statuses and submits Customer Appeal when REJECTED', async () => {
  const origFetch = globalThis.fetch;
  const calls = [];
  let appealsState = [];
  let currentLookupPayload = {
    id: 901,
    requestCode: 'RFD-20261127-0901',
    bookingCode: 'BOX-20261127-9002',
    cinemaName: 'BOX Cinema Royal City',
    requestStatus: 'PAYOUT_BLOCKED',
    workflowStatus: 'PAYOUT_BLOCKED',
    payoutStatus: 'PAYOUT_BLOCKED',
    feeAssessmentStatus: 'RECIPIENT_DEDUCTED',
    payoutBlockedReason: 'Provider deducts 5,500 VND fee from recipient',
    voucherStatus: 'NOT_REQUIRED',
    refundMethod: 'BANK_PAYOUT',
    toBankName: 'MB Bank',
    toAccountNumberMasked: '****6789',
    accountHolderName: 'NGUYEN VAN A',
    requestedCashTotal: 150000,
    approvedCashTotal: 150000,
    approvedVoucherTotal: 0,
    refundDeadlineAt: '2026-11-29T18:00:00',
    deadlineBasis: 'STANDARD_72H',
    bankAccountEditable: false,
    items: []
  };

  globalThis.fetch = async (url, opts = {}) => {
    const u = String(url);
    calls.push({ url: u, method: opts.method || 'GET', body: opts.body });
    if (u === '/api/refunds/public/banks') return jsonResponse({});
    if (u === '/api/customer/bank-accounts') return jsonResponse([], 401);
    if (u.startsWith('/api/refunds/public/lookup?')) return jsonResponse(currentLookupPayload);
    if (u.startsWith('/api/refunds/public/RFD-20261127-0901/appeals?')) return jsonResponse(appealsState);
    if (u === '/api/refunds/public/901/appeals' && opts.method === 'POST') {
      const parsed = JSON.parse(opts.body);
      const created = {
        id: 301,
        appealCode: 'APL-RFD-20261127-0901-1',
        status: 'APPEAL_SUBMITTED',
        appealReason: parsed.appealReason,
        evidenceUrl: parsed.evidenceUrl,
        activeLockOpen: true,
        submittedAt: '2026-11-27T11:00:00',
        history: [
          {
            action: 'APPEAL_SUBMITTED',
            previousStatus: null,
            newStatus: 'APPEAL_SUBMITTED',
            actorRole: 'CUSTOMER',
            note: parsed.appealReason,
            createdAt: '2026-11-27T11:00:00'
          }
        ]
      };
      appealsState = [created];
      return jsonResponse(created, 201);
    }
    return jsonResponse({}, 404);
  };

  try {
    const doc = createFakeDocument(buildContactIds());
    initContactRefund(doc);
    await flushAsync();

    // 1. PAYOUT_BLOCKED lookup
    doc.elements.lookupRefundCode.value = 'RFD-20261127-0901';
    doc.elements.lookupRefundEmail.value = 'buyer@box.test';
    doc.elements.refundStatusLookupForm.dispatchEvent({ type: 'submit' });
    await flushAsync();

    const blockedText = doc.elements.refundLookupResult.textContent;
    assert.match(blockedText, /Refund Status: PAYOUT_BLOCKED/);
    assert.match(blockedText, /Payout Status: PAYOUT_BLOCKED/);
    assert.match(blockedText, /Fee Policy: RECIPIENT_DEDUCTED/);
    assert.match(blockedText, /Appeal Status: NONE/);
    assert.match(blockedText, /PAYOUT_BLOCKED: Provider deducts 5,500 VND fee from recipient/);
    assert.equal(doc.getElementById('customerAppealForm'), null, 'Appeal form must not appear when PAYOUT_BLOCKED');

    // 2. REJECTED lookup -> customer submits appeal -> refreshes appeal status & history
    currentLookupPayload = {
      ...currentLookupPayload,
      requestStatus: 'REJECTED',
      workflowStatus: 'REJECTED',
      payoutStatus: 'NOT_REQUIRED',
      feeAssessmentStatus: null,
      payoutBlockedReason: null,
      decisionNote: 'Initial screenshot did not show ticket code'
    };
    doc.elements.refundStatusLookupForm.dispatchEvent({ type: 'submit' });
    await flushAsync();

    const rejectedText = doc.elements.refundLookupResult.textContent;
    assert.match(rejectedText, /Refund Status: REJECTED/);
    assert.match(rejectedText, /Review \/ Decision Reason: Initial screenshot did not show ticket code/);

    const appealForm = doc.getElementById('customerAppealForm');
    assert.ok(appealForm, 'Appeal submission form must render when refund is REJECTED with no open appeal');
    doc.getElementById('customerAppealReason').value = 'Attached full e-ticket and projector outage photo';
    doc.getElementById('customerAppealEvidenceUrl').value = '/api/refunds/evidence/full-ticket.png';
    appealForm.dispatchEvent({ type: 'submit' });
    await flushAsync();

    const postAppeal = calls.find(c => c.url === '/api/refunds/public/901/appeals' && c.method === 'POST');
    assert.ok(postAppeal, 'must POST to /api/refunds/public/{idOrCode}/appeals');
    assert.deepEqual(JSON.parse(postAppeal.body), {
      purchaseEmail: 'buyer@box.test',
      appealReason: 'Attached full e-ticket and projector outage photo',
      evidenceUrl: '/api/refunds/evidence/full-ticket.png'
    });

    const refreshedText = doc.elements.refundLookupResult.textContent;
    assert.match(refreshedText, /Appeal Status: APPEAL_SUBMITTED/);
    assert.match(refreshedText, /APL-RFD-20261127-0901-1 — Status: APPEAL_SUBMITTED/);
    assert.equal(doc.getElementById('customerAppealForm'), null, 'Appeal form must hide while active appeal is open');
  } finally {
    globalThis.fetch = origFetch;
  }
});

test('Stage 4 Manager & Admin UI: formatPayoutStage renders all canonical, blocked, unknown, and appeal states', () => {
  assert.match(formatPayoutStage('PAYOUT_BLOCKED').label, /PAYOUT_BLOCKED/);
  assert.equal(formatPayoutStage('PAYOUT_BLOCKED').cls, 'bg-danger');
  assert.equal(formatPayoutStage('UNKNOWN').label, 'Chưa xác định kết quả');
  assert.equal(formatPayoutStage('REQUESTED').label, 'Mới yêu cầu');
  assert.equal(formatPayoutStage('UNDER_REVIEW').label, 'Đang thẩm định');
  assert.equal(formatPayoutStage('APPROVED').label, 'Đã duyệt nghiệp vụ');
  assert.equal(formatPayoutStage('SUCCESS').label, 'Thành công');
  assert.equal(formatPayoutStage('FAILED').label, 'Thất bại');
  assert.equal(formatPayoutStage('APPEAL_SUBMITTED').label, 'Kháng nghị mới');
  assert.equal(formatPayoutStage('APPEAL_UNDER_REVIEW').label, 'Đang xét kháng nghị');
  assert.equal(formatPayoutStage('APPEAL_ACCEPTED').label, 'Chấp nhận kháng nghị');
  assert.equal(formatPayoutStage('APPEAL_REJECTED').label, 'Bác kháng nghị');
});

test('Stage 4 Manager UI: preflight badge, quota summary banner, appeals queue + decide, and incident CRUD + 409 INCIDENT_LINKED_TO_REFUNDS', async () => {
  const origFetch = globalThis.fetch;
  const calls = [];
  let appealRow = {
    id: 55,
    appealCode: 'APL-RFD-55-1',
    refundRequestId: 500,
    refundRequestCode: 'RFD-500',
    cinemaId: 1,
    cinemaName: 'BOX Royal City',
    purchaseEmail: 'cust@box.test',
    appealReason: 'Power outage verified by usher',
    status: 'APPEAL_SUBMITTED',
    submittedAt: '2026-11-27T09:00:00',
    history: []
  };

  globalThis.fetch = async (url, opts = {}) => {
    const u = String(url);
    const method = opts.method || 'GET';
    calls.push({ url: u, method, body: opts.body });

    if (u === '/api/manager/payouts/preflight-status') {
      return jsonResponse({ livePayoutEnabled: false, payoutMode: 'MOCK', configuredFeePolicy: 'SENDER_PAYS' });
    }
    if (u.startsWith('/api/manager/refunds/limits/summary')) {
      return jsonResponse({
        quotaDate: '2026-11-27',
        managerPerRequestLimitVnd: 500000,
        managerDailyLimitVnd: 2000000,
        managerCommittedVnd: 200000,
        managerReservedVnd: 300000,
        managerReleasedVnd: 100000,
        managerAvailableVnd: 1500000,
        cinemaId: 1,
        cinemaDailyLimitVnd: 5000000,
        cinemaCommittedVnd: 500000,
        cinemaReservedVnd: 300000,
        cinemaReleasedVnd: 0,
        cinemaAvailableVnd: 4200000,
        incidentCumulativeLimitVnd: 10000000,
        incidentCommittedVnd: 0,
        incidentReservedVnd: 300000,
        incidentReleasedVnd: 0,
        incidentAvailableVnd: 9700000
      });
    }
    if (u.startsWith('/api/manager/refunds?')) return jsonResponse({ content: [] });
    if (u.startsWith('/api/manager/refunds/appeals')) {
      if (u === '/api/manager/refunds/appeals/55' && method === 'GET') return jsonResponse(appealRow);
      if (u === '/api/manager/refunds/appeals/55/decide' && method === 'POST') {
        const body = JSON.parse(opts.body);
        appealRow = { ...appealRow, status: body.decision, decisionNote: body.decisionNote };
        return jsonResponse(appealRow);
      }
      return jsonResponse([appealRow]);
    }
    if (u.startsWith('/api/manager/incidents?')) {
      return jsonResponse({
        content: [
          {
            id: 88,
            incidentCode: 'INC-20261127-88',
            cinemaName: 'BOX Royal City',
            incidentCategory: 'PROJECTION_FAILURE',
            title: 'Projector lamp failure',
            status: 'VERIFIED',
            customerNotifiedAt: '2026-11-27T08:30:00',
            createdAt: '2026-11-27T08:00:00'
          }
        ]
      });
    }
    if (u === '/api/manager/incidents' && method === 'POST') {
      return jsonResponse({ id: 89, incidentCode: 'INC-20261127-89', status: 'OPEN', title: 'AC outage' }, 201);
    }
    if (u === '/api/manager/incidents/89' && method === 'GET') {
      return jsonResponse({
        id: 89,
        incidentCode: 'INC-20261127-89',
        cinemaName: 'BOX Royal City',
        incidentCategory: 'FACILITY_ISSUE',
        title: 'AC outage',
        description: 'Hall 2 AC stopped',
        status: 'OPEN',
        customerNotifiedAt: '2026-11-27T10:15:00'
      });
    }
    if (u === '/api/manager/incidents/89/customer-notified' && method === 'PATCH') {
      return jsonResponse({ id: 89, incidentCode: 'INC-20261127-89', customerNotifiedAt: '2026-11-27T11:30:00' });
    }
    if (u === '/api/manager/incidents/88' && method === 'DELETE') {
      return jsonResponse({
        status: 409,
        errorCode: 'INCIDENT_LINKED_TO_REFUNDS',
        message: 'Cannot delete incident INC-20261127-88 because it is linked to refund requests.'
      }, 409);
    }
    if (u.startsWith('/api/manager/staff-errors')) return jsonResponse({ content: [] });
    return jsonResponse({}, 404);
  };

  try {
    const doc = createFakeDocument(buildOpsIds());
    doc.elements.refundOpsRoot.dataset.roleScope = 'manager';
    doc.elements.refundOpsRoot.dataset.opsMode = 'all';

    initRefundOperationsWorkspace(doc);
    await flushAsync();

    // Preflight badge
    assert.match(doc.elements.opsPayoutPreflightBadge.textContent, /LIVE_PAYOUT_ENABLED: FALSE/);
    assert.match(doc.elements.opsPayoutPreflightBadge.textContent, /Mode: MOCK/);

    // Manager quota summary banner
    const quotaText = doc.elements.opsLimitSummaryContent.textContent;
    assert.match(quotaText, /500\.000 VND/);
    assert.match(quotaText, /1\.500\.000 VND/);
    assert.match(quotaText, /4\.200\.000 VND/);

    // Appeals queue + decide APPEAL_ACCEPTED
    const appealRows = doc.elements.opsAppealTableBody.children;
    assert.equal(appealRows.length, 1);
    const openAppealBtn = appealRows[0].querySelector('button');
    openAppealBtn.click();
    await flushAsync();

    const noteTextarea = doc.elements.opsAppealDetailPanel.querySelector('textarea');
    assert.ok(noteTextarea, 'Appeal decision note textarea must render');
    noteTextarea.value = 'Verified outage log; reopening refund for review';
    const acceptBtn = doc.elements.opsAppealDetailPanel.querySelectorAll('button')
      .find(b => b.textContent.includes('APPEAL_ACCEPTED'));
    assert.ok(acceptBtn, 'APPEAL_ACCEPTED button must render');
    acceptBtn.click();
    await flushAsync();

    const decideCall = calls.find(c => c.url === '/api/manager/refunds/appeals/55/decide' && c.method === 'POST');
    assert.ok(decideCall, 'must POST to /api/manager/refunds/appeals/55/decide');
    assert.deepEqual(JSON.parse(decideCall.body), {
      decision: 'APPEAL_ACCEPTED',
      decisionNote: 'Verified outage log; reopening refund for review'
    });

    // Incident create with customerNotifiedAt + PATCH customer-notified + DELETE 409 INCIDENT_LINKED_TO_REFUNDS
    doc.elements.incFormGroup.value = 'CINEMA_INCIDENT';
    doc.elements.incFormType.value = 'FACILITY_ISSUE';
    doc.elements.incFormTitle.value = 'AC outage';
    doc.elements.incFormCustomerNotifiedAt.value = '2026-11-27T10:15';
    doc.elements.incFormDescription.value = 'Hall 2 AC stopped mid-screening';
    doc.elements.btnSaveIncident.click();
    await flushAsync();

    const createIncCall = calls.find(c => c.url === '/api/manager/incidents' && c.method === 'POST');
    assert.ok(createIncCall, 'must POST /api/manager/incidents');
    assert.equal(JSON.parse(createIncCall.body).customerNotifiedAt, '2026-11-27T10:15:00');

    // Trigger PATCH /customer-notified in incident detail panel
    const patchInput = doc.elements.opsIncidentDetailPanel.querySelector('input');
    const patchBtn = doc.elements.opsIncidentDetailPanel.querySelectorAll('button')
      .find(b => b.textContent.includes('Lưu thời điểm thông báo khách'));
    assert.ok(patchInput && patchBtn, 'customerNotifiedAt update controls must render');
    patchInput.value = '2026-11-27T11:30';
    patchBtn.click();
    await flushAsync();
    assert.ok(calls.some(c => c.url === '/api/manager/incidents/89/customer-notified' && c.method === 'PATCH'));

    // Trigger DELETE on linked incident #88 -> must surface [INCIDENT_LINKED_TO_REFUNDS]
    const deleteBtn = doc.elements.opsIncidentTableBody.children[0].querySelectorAll('button')
      .find(b => b.textContent === 'Xóa');
    assert.ok(deleteBtn, 'Delete incident button must render');
    deleteBtn.click();
    await flushAsync();
    assert.match(
      doc.elements.opsIncidentFormStatus.textContent,
      /\[INCIDENT_LINKED_TO_REFUNDS\].*Cannot delete incident INC-20261127-88/
    );
  } finally {
    globalThis.fetch = origFetch;
  }
});

test('Stage 4 Admin UI: limits config GET/PUT, Admin Limit Override & Deadline Exception, Assess Fee fields, PAYOUT_BLOCKED confirm guard, and Reconciliation Logs', async () => {
  const origFetch = globalThis.fetch;
  const calls = [];
  let previewBlocked = true;

  const limitConfigs = [
    { configKey: 'MANAGER_PER_REQUEST', limitAmountVnd: 500000, description: 'Per-request cap', updatedAt: '2026-11-27T08:00:00' },
    { configKey: 'MANAGER_DAILY', limitAmountVnd: 2000000, description: 'Manager daily cap', updatedAt: '2026-11-27T08:00:00' },
    { configKey: 'CINEMA_DAILY', limitAmountVnd: 5000000, description: 'Cinema daily cap', updatedAt: '2026-11-27T08:00:00' },
    { configKey: 'INCIDENT_CUMULATIVE', limitAmountVnd: 10000000, description: 'Incident cap', updatedAt: '2026-11-27T08:00:00' }
  ];

  const adminDetail = {
    id: 701,
    requestCode: 'RFD-701',
    bookingCode: 'BOX-20261127-701',
    orderType: 'TICKET_ONLY',
    cinemaId: 1,
    cinemaName: 'BOX Royal City',
    customerName: 'TRAN THI B',
    purchaseEmail: 'tranb@box.test',
    reasonCategory: 'CINEMA_INCIDENT',
    description: 'Audio outage during screening',
    requestStatus: 'BUSINESS_APPROVED',
    workflowStatus: 'APPROVED',
    payoutStatus: 'PENDING_ADMIN',
    voucherStatus: 'NOT_REQUIRED',
    refundMethod: 'BANK_PAYOUT',
    toBankName: 'Vietcombank',
    toBin: '970436',
    toAccountNumberMasked: '****9999',
    accountHolderName: 'TRAN THI B',
    transferDescription: 'BOX HOAN TIEN RFD701',
    recipientSnapshotHash: 'sha256-abc123',
    requestedCashTotal: 600000,
    approvedCashTotal: 600000,
    approvedVoucherTotal: 0,
    refundDeadlineAt: '2026-11-26T12:00:00',
    deadlineBasis: 'STANDARD_72H',
    deadlineExceeded: true,
    feeAssessmentStatus: 'SENDER_PAYS',
    feeAmountVnd: 0,
    netRecipientVnd: 600000,
    guaranteedFullReceipt: true,
    livePayoutEnabled: false,
    payoutMode: 'MOCK',
    payoutEligible: true,
    items: [
      {
        id: 9001,
        itemKind: 'TICKET',
        itemLabel: 'Seat B5',
        purchasedQuantity: 1,
        requestedQuantity: 1,
        approvedQuantity: 1,
        grossUnitPrice: 600000,
        discountAllocatedAmount: 0,
        netPaidAmount: 600000,
        itemStatus: 'PENDING'
      }
    ]
  };

  globalThis.fetch = async (url, opts = {}) => {
    const u = String(url);
    const method = opts.method || 'GET';
    calls.push({ url: u, method, body: opts.body });

    if (u === '/api/admin/payouts/preflight-status') {
      return jsonResponse({ livePayoutEnabled: false, payoutMode: 'MOCK', configuredFeePolicy: 'SENDER_PAYS' });
    }
    if (u.startsWith('/api/admin/refunds/limits/summary')) {
      return jsonResponse({ quotaDate: '2026-11-27', managerPerRequestLimitVnd: 500000 });
    }
    if (u === '/api/admin/refunds/limits' && method === 'GET') return jsonResponse(limitConfigs);
    if (u === '/api/admin/refunds/limits' && method === 'PUT') {
      const b = JSON.parse(opts.body);
      return jsonResponse({ configKey: b.configKey, limitAmountVnd: b.limitAmountVnd });
    }
    if (u === '/api/admin/cinemas') return jsonResponse([{ cinemaId: 1, cinemaName: 'BOX Royal City' }]);
    if (u.startsWith('/api/admin/refunds/payout-queue?')) return jsonResponse({ content: [adminDetail] });
    if (u.startsWith('/api/admin/refunds?')) return jsonResponse({ content: [adminDetail] });
    if (u.startsWith('/api/admin/refunds/appeals')) return jsonResponse([]);
    if (u.startsWith('/api/admin/payouts?')) {
      return jsonResponse({
        content: [
          {
            id: 401,
            referenceId: 'PO-701-1',
            refundRequestCode: 'RFD-701',
            batchCode: 'BATCH-01',
            payoutMode: 'MOCK',
            amount: 600000,
            status: 'SUCCESS',
            displayStage: 'PAYOUT_SUCCEEDED'
          }
        ]
      });
    }
    if (u === '/api/admin/payouts/401' && method === 'GET') {
      return jsonResponse({
        id: 401,
        referenceId: 'PO-701-1',
        idempotencyKey: 'idem-401',
        refundRequestCode: 'RFD-701',
        payoutMode: 'MOCK',
        amount: 600000,
        status: 'SUCCESS',
        displayStage: 'PAYOUT_SUCCEEDED',
        attemptCount: 1
      });
    }
    if (u === '/api/admin/payouts/401/reconciliation-logs') {
      return jsonResponse([
        {
          id: 1,
          triggerSource: 'WEBHOOK',
          previousState: 'PROCESSING',
          newState: 'SUCCESS',
          providerCode: '00',
          providerDesc: 'Mock transfer completed',
          createdAt: '2026-11-27T10:05:00'
        }
      ]);
    }
    if (u === '/api/admin/refunds/701' && method === 'GET') return jsonResponse(adminDetail);
    if (u === '/api/admin/refunds/701/payout/assess-fee' && method === 'POST') {
      return jsonResponse({
        providerName: 'MOCK_PAYOS',
        classification: 'RECIPIENT_DEDUCTED',
        approvedRefundVnd: 600000,
        estimatedFeeVnd: 3300,
        netRecipientVnd: 596700,
        totalDebitVnd: 600000,
        guaranteedFullReceipt: false,
        blocked: true,
        reason: 'Recipient fee deduction violates 100% guarantee'
      });
    }
    if (u === '/api/admin/refunds/701/payout/preview' || u === '/api/admin/refunds/payout/batch-preview') {
      if (previewBlocked) {
        return jsonResponse({
          requestedCount: 1,
          eligibleCount: 1,
          ineligibleCount: 0,
          blockedCount: 1,
          totalAmountVnd: 600000,
          livePayoutEnabled: false,
          payoutMode: 'MOCK',
          configuredFeePolicy: 'RECIPIENT_DEDUCTED',
          eligibleItems: [
            {
              refundRequestId: 701,
              requestCode: 'RFD-701',
              bookingCode: 'BOX-20261127-701',
              cinemaName: 'BOX Royal City',
              customerName: 'TRAN THI B',
              accountHolderName: 'TRAN THI B',
              toBankName: 'Vietcombank',
              toBin: '970436',
              toAccountNumberMasked: '****9999',
              refundMethod: 'BANK_PAYOUT',
              feeClassification: 'RECIPIENT_DEDUCTED',
              estimatedFeeVnd: 3300,
              netRecipientVnd: 596700,
              guaranteedFullReceipt: false,
              feeBlocked: true,
              feeBlockReason: 'Recipient fee deduction violates 100% guarantee',
              workflowStatus: 'PAYOUT_BLOCKED',
              amountVnd: 600000
            }
          ],
          ineligibleItems: []
        });
      }
      return jsonResponse({
        requestedCount: 1,
        eligibleCount: 1,
        ineligibleCount: 0,
        blockedCount: 0,
        totalAmountVnd: 600000,
        livePayoutEnabled: false,
        payoutMode: 'MOCK',
        configuredFeePolicy: 'SENDER_PAYS',
        eligibleItems: [
          {
            refundRequestId: 701,
            requestCode: 'RFD-701',
            bookingCode: 'BOX-20261127-701',
            cinemaName: 'BOX Royal City',
            customerName: 'TRAN THI B',
            accountHolderName: 'TRAN THI B',
            toBankName: 'Vietcombank',
            toBin: '970436',
            toAccountNumberMasked: '****9999',
            refundMethod: 'BANK_PAYOUT',
            feeClassification: 'SENDER_PAYS',
            estimatedFeeVnd: 0,
            netRecipientVnd: 600000,
            guaranteedFullReceipt: true,
            feeBlocked: false,
            workflowStatus: 'APPROVED',
            amountVnd: 600000
          }
        ],
        ineligibleItems: []
      });
    }
    if (u === '/api/admin/refunds/701/payout/approve' && method === 'POST') {
      return jsonResponse({ ...adminDetail, payoutStatus: 'SUCCESS', workflowStatus: 'SUCCESS' });
    }
    if (u === '/api/admin/refunds/701/review' && method === 'POST') {
      return jsonResponse(adminDetail);
    }
    return jsonResponse({}, 404);
  };

  try {
    const doc = createFakeDocument(buildOpsIds());
    doc.elements.refundOpsRoot.dataset.roleScope = 'admin';
    doc.elements.refundOpsRoot.dataset.opsMode = 'refunds';

    // Populate #limitConfigKeySelect with the options from admin/refunds.html
    for (const key of ['MANAGER_PER_REQUEST', 'MANAGER_DAILY', 'CINEMA_DAILY', 'INCIDENT_CUMULATIVE']) {
      const opt = doc.createElement('option');
      opt.value = key;
      opt.textContent = key;
      doc.elements.limitConfigKeySelect.append(opt);
    }

    initRefundOperationsWorkspace(doc);
    await flushAsync();

    // 1. Limits config table & PUT /api/admin/refunds/limits
    assert.equal(doc.elements.opsLimitConfigTableBody.children.length, 4);
    const firstPickBtn = doc.elements.opsLimitConfigTableBody.children[0].querySelector('button');
    firstPickBtn.click();
    assert.equal(doc.elements.limitConfigKeySelect.value, 'MANAGER_PER_REQUEST');
    doc.elements.limitAmountVndInput.value = '750000';
    doc.elements.limitChangeReasonInput.value = 'Holiday peak threshold adjustment';
    doc.elements.btnSaveLimitConfig.click();
    await flushAsync();

    const putLimitCall = calls.find(c => c.url === '/api/admin/refunds/limits' && c.method === 'PUT');
    assert.ok(putLimitCall, 'must PUT /api/admin/refunds/limits');
    assert.deepEqual(JSON.parse(putLimitCall.body), {
      configKey: 'MANAGER_PER_REQUEST',
      limitAmountVnd: 750000,
      reason: 'Holiday peak threshold adjustment'
    });

    // 2. Open refund detail -> check Assess Fee + Admin Limit Override & Deadline Exception controls
    const detailBtn = doc.elements.opsRefundTableBody.children[0].querySelector('button');
    detailBtn.click();
    await flushAsync();

    const assessFeeBtn = doc.elements.opsRefundDetailPanel.querySelectorAll('button')
      .find(b => b.textContent.includes('Assess Fee'));
    assert.ok(assessFeeBtn, 'Assess Fee button must render for Admin');
    assessFeeBtn.click();
    await flushAsync();

    const detailPanelText = doc.elements.opsRefundDetailPanel.textContent;
    assert.doesNotMatch(detailPanelText, /undefined/, 'Assess Fee result must not render undefined field names');
    assert.match(detailPanelText, /RECIPIENT_DEDUCTED/);
    assert.match(detailPanelText, /Recipient fee deduction violates 100% guarantee/);

    // Verify Admin Limit Override & Deadline Exception controls exist and send payload on review
    const reviewDetail = { ...adminDetail, requestStatus: 'PENDING_APPROVAL', workflowStatus: 'UNDER_REVIEW', payoutStatus: 'NOT_REQUIRED' };
    calls.length = 0;
    const prevFetch = globalThis.fetch;
    globalThis.fetch = async (u, o) => {
      if (String(u) === '/api/admin/refunds/701' && (!o || !o.method || o.method === 'GET')) {
        return jsonResponse(reviewDetail);
      }
      return prevFetch(u, o);
    };
    detailBtn.click();
    await flushAsync();
    globalThis.fetch = prevFetch;

    const limOvCb = doc.getElementById('adminLimitOverrideApproved');
    const limOvReason = doc.getElementById('adminLimitOverrideReason');
    const dlExCb = doc.getElementById('adminDeadlineExceptionApproved');
    const dlExReason = doc.getElementById('adminDeadlineExceptionReason');
    assert.ok(limOvCb && limOvReason && dlExCb && dlExReason, 'Admin override and deadline exception controls must render');

    limOvCb.checked = true;
    limOvReason.value = 'Director authorized above-cap incident refund';
    dlExCb.checked = true;
    dlExReason.value = 'Late customer notification verified';
    const approveBizBtn = doc.elements.opsRefundDetailPanel.querySelectorAll('button')
      .find(b => b.textContent.includes('Duyệt hoàn tiền (Chuyển Admin chi)'));
    approveBizBtn.click();
    await flushAsync();

    const reviewCall = calls.find(c => c.url === '/api/admin/refunds/701/review' && c.method === 'POST');
    assert.ok(reviewCall, 'must POST /api/admin/refunds/701/review');
    const reviewBody = JSON.parse(reviewCall.body);
    assert.equal(reviewBody.limitOverrideApproved, true);
    assert.equal(reviewBody.limitOverrideReason, 'Director authorized above-cap incident refund');
    assert.equal(reviewBody.deadlineExceptionApproved, true);
    assert.equal(reviewBody.deadlineExceptionReason, 'Late customer notification verified');

    // 3. One-Click & Batch Payout confirmation modal blocks confirm button when PAYOUT_BLOCKED (feeBlocked)
    previewBlocked = true;
    const queueApproveBtn = doc.elements.payoutQueueTableBody.children[0].querySelectorAll('button')
      .find(b => b.textContent.includes('Duyệt chi'));
    assert.ok(queueApproveBtn, 'One-click payout button must render in Admin payout queue');
    queueApproveBtn.click();
    await flushAsync();

    assert.equal(doc.elements.btnConfirmExecute.disabled, true, 'Confirm execute button MUST be disabled when feeBlocked / PAYOUT_BLOCKED');
    assert.equal(doc.elements.confirmFeeBlockedBanner.classList.contains('d-none'), false);
    assert.match(doc.elements.confirmFeeBlockedBanner.textContent, /PAYOUT_BLOCKED.*RFD-701/);
    assert.match(doc.elements.confirmItemTableBody.textContent, /RECIPIENT_DEDUCTED/);

    // Unblock fee policy -> confirm button enables and forwards Admin limitOverride on single approve
    previewBlocked = false;
    queueApproveBtn.click();
    await flushAsync();
    assert.equal(doc.elements.btnConfirmExecute.disabled, false, 'Confirm execute button must enable when SENDER_PAYS & not blocked');
    doc.elements.confirmLimitOverrideApproved.checked = true;
    doc.elements.confirmLimitOverrideReason.value = 'Approved emergency payout';
    doc.elements.btnConfirmExecute.click();
    await flushAsync();

    const singlePayoutApprove = calls.find(c => c.url === '/api/admin/refunds/701/payout/approve' && c.method === 'POST');
    assert.ok(singlePayoutApprove, 'must POST /api/admin/refunds/701/payout/approve');
    assert.deepEqual(JSON.parse(singlePayoutApprove.body), {
      note: '',
      limitOverrideApproved: true,
      limitOverrideReason: 'Approved emergency payout'
    });

    // 4. Reconciliation logs rendered via GET /api/admin/payouts/{txId}/reconciliation-logs
    const txDetailBtn = doc.elements.payoutTxTableBody.children[0].querySelector('button');
    txDetailBtn.click();
    await flushAsync();
    assert.ok(calls.some(c => c.url === '/api/admin/payouts/401/reconciliation-logs'));
    assert.match(doc.elements.payoutTxDetailPanel.textContent, /Payout Reconciliation Logs/);
    assert.match(doc.elements.payoutTxDetailPanel.textContent, /WEBHOOK: PROCESSING -> SUCCESS \(Code: 00 \| Mock transfer completed\)/);
  } finally {
    globalThis.fetch = origFetch;
  }
});
