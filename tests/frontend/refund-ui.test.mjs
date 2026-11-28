import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { initContactRefund } from '../../src/main/resources/static/js/modules/contact-refund.js';
import { initCustomerBankAccounts } from '../../src/main/resources/static/js/modules/customer-bank-accounts.js';
import { initRefundOperationsWorkspace, formatPayoutStage } from '../../src/main/resources/static/js/modules/refund-ops.js';
import { initAdmin } from '../../src/main/resources/static/js/modules/admin.js';

function createFakeElement(tag, id = '') {
  const listeners = {};
  const attrs = {};
  const el = {
    tagName: tag.toUpperCase(),
    id,
    value: '',
    textContent: '',
    className: '',
    disabled: false,
    checked: false,
    type: '',
    rows: 0,
    min: '',
    max: '',
    placeholder: '',
    style: {},
    dataset: {},
    children: [],
    options: [],
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
    get firstChild() { return el.children[0] || null; },
    setAttribute(k, v) { attrs[k] = String(v); },
    getAttribute(k) { return attrs[k] ?? null; },
    append(...nodes) {
      for (const n of nodes) {
        el.children.push(n);
        if (n.tagName === 'OPTION') el.options.push(n);
        el.textContent += (n.textContent || '');
      }
    },
    replaceChildren(...nodes) {
      el.children = [];
      el.options = [];
      el.textContent = '';
      el.append(...nodes);
    },
    addEventListener(type, fn) {
      listeners[type] = listeners[type] || [];
      listeners[type].push(fn);
    },
    dispatchEvent(ev) {
      for (const fn of listeners[ev.type] || []) fn(ev);
    },
    click() {
      if (typeof el.onclick === 'function') el.onclick({ type: 'click', target: el });
      el.dispatchEvent({ type: 'click', target: el, preventDefault() {} });
    },
    querySelectorAll() { return []; }
  };
  return el;
}

function createFakeDocument(ids, selectorMap = {}) {
  const elements = {};
  for (const [id, tag] of Object.entries(ids)) {
    elements[id] = createFakeElement(tag, id);
  }
  return {
    elements,
    getElementById(id) { return elements[id] || null; },
    createElement(tag) { return createFakeElement(tag); },
    querySelector(sel) { return selectorMap[sel] || null; },
    querySelectorAll(sel) { return selectorMap[sel] || []; }
  };
}

test('refund-ops, contact-refund, and customer-bank-accounts modules have zero innerHTML assignments', () => {
  const contactJs = fs.readFileSync(new URL('../../src/main/resources/static/js/modules/contact-refund.js', import.meta.url), 'utf8');
  const opsJs = fs.readFileSync(new URL('../../src/main/resources/static/js/modules/refund-ops.js', import.meta.url), 'utf8');
  const bankJs = fs.readFileSync(new URL('../../src/main/resources/static/js/modules/customer-bank-accounts.js', import.meta.url), 'utf8');
  assert.doesNotMatch(contactJs, /\.innerHTML\s*=/);
  assert.doesNotMatch(opsJs, /\.innerHTML\s*=/);
  assert.doesNotMatch(bankJs, /\.innerHTML\s*=/);
});

test('initContactRefund validates missing fields, loads banks, renders items safely without XSS, and clears raw account after submit', async () => {
  const doc = createFakeDocument({
    refundSupportRoot: 'div',
    refundBankBin: 'select',
    refundOrderCode: 'input',
    refundPurchaseEmail: 'input',
    orderLookupStatus: 'div',
    refundOrderDetailsBox: 'div',
    refundOrderMeta: 'div',
    refundItemList: 'div',
    btnLookupOrder: 'button',
    publicRefundForm: 'form',
    refundReasonCategory: 'select',
    refundDescription: 'textarea',
    refundAccountNumber: 'input',
    refundSubmitStatus: 'div',
    lookupRefundCode: 'input',
    lookupRefundEmail: 'input',
    refundStatusLookupForm: 'form',
    refundLookupStatus: 'div',
    refundLookupResult: 'div'
  });

  let submittedBody = null;
  const origFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    if (String(url).includes('/banks')) {
      return { ok: true, json: async () => ({ '970422': 'MB Bank' }) };
    }
    if (String(url).includes('/lookup-order')) {
      return {
        ok: true,
        json: async () => ({
          bookingCode: 'BOX-20261121-1001',
          cinemaName: 'BOX Royal',
          orderType: 'TICKET_AND_FOOD',
          totalPaidAmount: 162000,
          discountAmount: 18000,
          items: [{
            itemKind: 'COMBO_COMPONENT', targetId: 12,
            label: '<script>alert(1)</script> Popcorn',
            purchasedQuantity: 1, remainingRefundableQuantity: 1,
            netPaidAmount: 45000, discountAllocated: 5000, eligibleHint: true, eligibilityNote: 'Undelivered'
          }]
        })
      };
    }
    if (String(url) === '/api/refunds/public') {
      submittedBody = JSON.parse(opts.body);
      return {
        ok: true,
        json: async () => ({ requestCode: 'RFD-20261121-9999', requestStatus: 'PENDING_REVIEW', toAccountNumberMasked: '******4321' })
      };
    }
    return { ok: false, json: async () => ({ message: 'Not found' }) };
  };

  try {
    initContactRefund(doc);
    await new Promise(r => setTimeout(r, 15));
    assert.equal(doc.elements.refundBankBin.options.length, 2);

    // Empty verify shows validation message
    doc.elements.btnLookupOrder.click();
    assert.match(doc.elements.orderLookupStatus.textContent, /enter both/i);

    // Valid verify renders item safely
    doc.elements.refundOrderCode.value = 'BOX-20261121-1001';
    doc.elements.refundPurchaseEmail.value = 'guest@example.com';
    doc.elements.btnLookupOrder.click();
    await new Promise(r => setTimeout(r, 15));
    assert.match(doc.elements.refundItemList.textContent, /<script>alert\(1\)<\/script> Popcorn/);
    assert.equal(doc.elements.refundOrderDetailsBox.classList.contains('d-none'), false);

    // Submit with selected checkbox clears raw account number
    const cb = createFakeElement('input');
    cb.dataset = { itemKind: 'COMBO_COMPONENT', targetId: '12' };
    const qtyEl = createFakeElement('input');
    qtyEl.value = '1';
    doc.querySelectorAll = sel => sel === '.refund-item-check:checked' ? [cb] : [];
    doc.querySelector = sel => sel.includes('.refund-item-qty') ? qtyEl : null;

    doc.elements.refundReasonCategory.value = 'PRODUCT_INCIDENT';
    doc.elements.refundDescription.value = 'Stale popcorn';
    doc.elements.refundBankBin.value = '970422';
    doc.elements.refundAccountNumber.value = '0987654321';
    doc.elements.publicRefundForm.dispatchEvent({ type: 'submit', preventDefault() {} });
    await new Promise(r => setTimeout(r, 15));

    assert.equal(submittedBody.toAccountNumber, '0987654321');
    assert.equal(doc.elements.refundAccountNumber.value, '');
    assert.match(doc.elements.refundSubmitStatus.textContent, /RFD-20261121-9999/);
    assert.match(doc.elements.refundSubmitStatus.textContent, /\*\*\*\*\*\*4321/);
  } finally {
    globalThis.fetch = origFetch;
  }
});

test('initRefundOperationsWorkspace handles pagination and managerTab activation', async () => {
  const tabBtn = createFakeElement('button');
  const tabPane = createFakeElement('div', 'mgr-incidents');
  const doc = createFakeDocument({
    refundOpsRoot: 'main',
    opsRefundTableBody: 'tbody',
    opsRefundPageInfo: 'span',
    opsRefundPrev: 'button',
    opsRefundNext: 'button',
    opsIncidentTableBody: 'tbody',
    opsStaffErrorTableBody: 'tbody'
  }, {
    '[data-bs-target="#mgr-incidents"]': tabBtn,
    '.mgr-tab-btn': [tabBtn],
    '.tab-pane': [tabPane]
  });
  const origGetById = doc.getElementById.bind(doc);
  doc.getElementById = id => id === 'mgr-incidents' ? tabPane : origGetById(id);

  doc.elements.refundOpsRoot.dataset = { roleScope: 'manager', opsMode: 'all', activeTab: 'incidents' };

  const requestedPages = [];
  const origFetch = globalThis.fetch;
  globalThis.fetch = async url => {
    if (String(url).includes('/api/manager/refunds')) {
      const m = String(url).match(/page=(\d+)/);
      requestedPages.push(Number(m ? m[1] : 0));
      return {
        ok: true,
        json: async () => ({
          content: [{ id: 1, requestCode: 'RFD-1', bookingCode: 'BOX-1', cinemaName: 'Royal', purchaseEmail: 'a@b.c', requestedCashTotal: 50000, approvedCashTotal: 0, toAccountNumberMasked: '******1234', requestStatus: 'PENDING_REVIEW', payoutStatus: 'NOT_REQUIRED', voucherStatus: 'NOT_REQUIRED' }],
          totalPages: 2, totalElements: 20
        })
      };
    }
    return { ok: true, json: async () => ({ content: [] }) };
  };

  try {
    initRefundOperationsWorkspace(doc);
    await new Promise(r => setTimeout(r, 15));
    assert.equal(tabBtn.classList.contains('active'), true);
    assert.equal(tabPane.classList.contains('active'), true);
    const dashboardHtml = fs.readFileSync('src/main/resources/templates/manager/dashboard.html', 'utf8');
    assert.ok(dashboardHtml.includes('data-bs-target="#mgr-staff-errors"'));
    assert.ok(dashboardHtml.includes('id="mgr-staff-errors"'));
    assert.equal(doc.elements.opsRefundPrev.disabled, true);
    assert.equal(doc.elements.opsRefundNext.disabled, false);

    doc.elements.opsRefundNext.click();
    await new Promise(r => setTimeout(r, 15));
    assert.deepEqual(requestedPages, [0, 1]);
  } finally {
    globalThis.fetch = origFetch;
  }
});

test('admin workspace sidebar and quickbar expose Refunds & Payouts and Incidents & Appeals', () => {
  const adminNav = createFakeElement('nav', 'adminNavigation');
  const root = createFakeElement('div');
  root.dataset = { adminSection: 'dashboard' };
  const autoElements = { adminNavigation: adminNav };
  const getOrCreateEl = id => {
    if (!autoElements[id]) {
      const el = createFakeElement('div', id);
      el.showModal = () => {};
      el.close = () => {};
      el.replaceChildren = (...nodes) => {
        el.children = [];
        el.append(...nodes);
      };
      el.querySelectorAll = () => [];
      el.querySelector = () => null;
      autoElements[id] = el;
    }
    return autoElements[id];
  };
  adminNav.replaceChildren = (...nodes) => {
    adminNav.children = [];
    adminNav.append(...nodes);
  };
  const makeNode = (tag, id = '') => {
    const node = createFakeElement(tag, id);
    node.replaceChildren = (...nodes) => {
      node.children = [];
      node.append(...nodes);
    };
    node.querySelectorAll = () => [];
    node.querySelector = () => null;
    node.closest = () => null;
    node.setAttribute = (k, v) => {
      if (!node._attrs) node._attrs = {};
      node._attrs[k] = String(v);
    };
    node.getAttribute = k => node._attrs?.[k] ?? null;
    return node;
  };
  const mockDoc = {
    title: '',
    defaultView: {
      addEventListener: () => {},
      fetch: async () => ({ ok: false }),
      location: { search: '' },
      sessionStorage: { getItem: () => null, setItem: () => {} },
      setTimeout
    },
    getElementById: getOrCreateEl,
    createElement: tag => makeNode(tag),
    createTextNode: text => ({ nodeType: 3, textContent: String(text) }),
    querySelector: sel => (sel === '[data-admin-section]' ? root : null),
    querySelectorAll: () => []
  };

  const origDoc = globalThis.document;
  globalThis.document = mockDoc;
  try {
    initAdmin(mockDoc);
  } finally {
    globalThis.document = origDoc;
  }

  const navLinks = adminNav.children
    .filter(n => n.tagName === 'A')
    .map(a => ({
      href: a.href,
      text: a.children.map(c => c.textContent).join(' ').trim()
    }));
  const bookingsIdx = navLinks.findIndex(l => l.href === '/admin/bookings');
  assert.ok(bookingsIdx >= 0, 'bookings nav link should be appended');
  assert.deepEqual(navLinks[bookingsIdx + 1], { href: '/admin/refunds', text: '↺ Refunds & Payouts' });
  assert.deepEqual(navLinks[bookingsIdx + 2], { href: '/admin/incidents', text: '⚠ Incidents & Appeals' });

  const workspaceHtml = fs.readFileSync('src/main/resources/templates/admin/workspace.html', 'utf8');
  assert.ok(workspaceHtml.includes('href="/admin/refunds" class="admin-quick-chip"'));
  assert.ok(workspaceHtml.includes('href="/admin/incidents" class="admin-quick-chip"'));
});

test('staff food KDS skips background auto-refresh when modal, scan filter, focus, or hover is active', () => {
  const foodHtml = fs.readFileSync('src/main/resources/templates/staff/food.html', 'utf8');
  const fnMatch = foodHtml.match(/function shouldAutoRefreshOrders\(\) \{[\s\S]*?\n    \}/);
  assert.ok(fnMatch, 'shouldAutoRefreshOrders helper should exist in staff/food.html');
  assert.match(foodHtml, /setInterval\(\(\) => \{\s*if \(shouldAutoRefreshOrders\(\)\) loadOrders\(\);\s*\}, 8000\)/);

  let modalShown = false;
  let scanValue = '';
  let gridContainsActive = false;
  let gridHovered = false;
  const mockDocument = {
    activeElement: {},
    getElementById(id) {
      if (id === 'foodActionModal') return { classList: { contains: cls => cls === 'show' && modalShown } };
      if (id === 'foodQrScanInput') return { get value() { return scanValue; } };
      if (id === 'orderGrid') {
        return {
          contains: () => gridContainsActive,
          matches: sel => sel === ':hover' && gridHovered
        };
      }
      return null;
    }
  };
  const shouldAutoRefreshOrders = new Function('document', `${fnMatch[0]}\nreturn shouldAutoRefreshOrders();`);
  assert.equal(shouldAutoRefreshOrders(mockDocument), true);

  modalShown = true;
  assert.equal(shouldAutoRefreshOrders(mockDocument), false);
  modalShown = false;

  scanValue = 'BOX-123';
  assert.equal(shouldAutoRefreshOrders(mockDocument), false);
  scanValue = '   ';

  gridContainsActive = true;
  assert.equal(shouldAutoRefreshOrders(mockDocument), false);
  gridContainsActive = false;

  gridHovered = true;
  assert.equal(shouldAutoRefreshOrders(mockDocument), false);
});

test('admin payout queue supports partial 3/5 selection, live VND total, batch preview/confirm, independent result panel, and distinct payout stages', async () => {
  assert.equal(formatPayoutStage('PAYOUT_APPROVED').label, 'Đã phê duyệt');
  assert.equal(formatPayoutStage('DISPATCHED').label, 'Đã gửi lệnh');
  assert.equal(formatPayoutStage('PROCESSING').label, 'Đang xử lý');
  assert.equal(formatPayoutStage('SUCCEEDED').label, 'Thành công');
  assert.equal(formatPayoutStage('FAILED').label, 'Thất bại');
  assert.equal(formatPayoutStage('UNKNOWN_OUTCOME').label, 'Chưa xác định kết quả');

  const doc = createFakeDocument({
    refundOpsRoot: 'div',
    opsCinemaFilter: 'select',
    opsRefundSearch: 'input',
    opsReasonFilter: 'select',
    opsVerifierFilter: 'input',
    opsMinAmount: 'input',
    opsMaxAmount: 'input',
    opsFromDate: 'input',
    opsToDate: 'input',
    opsRefundStatusFilter: 'select',
    opsPayoutStatusFilter: 'select',
    btnOpsReloadQueue: 'button',
    payoutQueueSelectAll: 'input',
    btnBatchSelectAllEligible: 'button',
    btnBatchClearSelection: 'button',
    btnOpenBatchConfirm: 'button',
    batchSelectedCount: 'strong',
    batchSelectedTotal: 'strong',
    opsPayoutQueueBody: 'tbody',
    opsQueuePageInfo: 'span',
    opsQueuePrev: 'button',
    opsQueueNext: 'button',
    batchPayoutResultPanel: 'div',
    batchResultSummary: 'div',
    batchResultTableBody: 'tbody',
    opsRefundTableBody: 'tbody',
    opsPayoutTxBody: 'tbody',
    payoutBalanceInfo: 'div',
    btnCheckPayoutBalance: 'button',
    payoutConfirmModal: 'div',
    confirmModalTitle: 'h5',
    confirmSelectedCount: 'strong',
    confirmSelectedTotal: 'strong',
    confirmIneligibleWarning: 'div',
    confirmItemTableBody: 'tbody',
    confirmNoteInput: 'textarea',
    confirmModalError: 'div',
    btnConfirmCancel: 'button',
    btnConfirmExecute: 'button'
  });
  doc.elements.refundOpsRoot.dataset = { roleScope: 'admin', opsMode: 'refunds' };
  doc.elements.payoutConfirmModal.classList.add('d-none');
  doc.elements.batchPayoutResultPanel.classList.add('d-none');

  const queueRows = [
    { id: 1, requestCode: 'RFD-1', bookingCode: 'BOX-1', cinemaName: 'Royal', customerName: 'A', purchaseEmail: 'a@b.c', approvedCashTotal: 50000, toBankName: 'MB', toBin: '970422', toAccountNumberMasked: '****1111', requestStatus: 'AWAITING_PAYOUT_APPROVAL', payoutStatus: 'AWAITING_APPROVAL', payoutEligible: true },
    { id: 2, requestCode: 'RFD-2', bookingCode: 'BOX-2', cinemaName: 'Royal', customerName: 'B', purchaseEmail: 'b@b.c', approvedCashTotal: 80000, toBankName: 'MB', toBin: '970422', toAccountNumberMasked: '****2222', requestStatus: 'AWAITING_PAYOUT_APPROVAL', payoutStatus: 'AWAITING_APPROVAL', payoutEligible: true },
    { id: 3, requestCode: 'RFD-3', bookingCode: 'BOX-3', cinemaName: 'Royal', customerName: 'C', purchaseEmail: 'c@b.c', approvedCashTotal: 100000, toBankName: 'MB', toBin: '970422', toAccountNumberMasked: '****3333', requestStatus: 'AWAITING_PAYOUT_APPROVAL', payoutStatus: 'AWAITING_APPROVAL', payoutEligible: true },
    { id: 4, requestCode: 'RFD-4', bookingCode: 'BOX-4', cinemaName: 'Royal', customerName: 'D', purchaseEmail: 'd@b.c', approvedCashTotal: 120000, toBankName: 'MB', toBin: '970422', toAccountNumberMasked: '****4444', requestStatus: 'AWAITING_PAYOUT_APPROVAL', payoutStatus: 'AWAITING_APPROVAL', payoutEligible: true },
    { id: 5, requestCode: 'RFD-5', bookingCode: 'BOX-5', cinemaName: 'Royal', customerName: 'E', purchaseEmail: 'e@b.c', approvedCashTotal: 150000, toBankName: 'MB', toBin: '970422', toAccountNumberMasked: '****5555', requestStatus: 'AWAITING_PAYOUT_APPROVAL', payoutStatus: 'AWAITING_APPROVAL', payoutEligible: false, payoutEligibilityErrors: ['Paid cap exceeded'] }
  ];

  const rowCheckboxes = [];
  const origCreate = doc.createElement.bind(doc);
  doc.createElement = tag => {
    const node = origCreate(tag);
    return node;
  };

  let batchApprovePayload = null;
  const origFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    const u = String(url);
    if (u === '/api/admin/cinemas') return { ok: true, json: async () => [] };
    if (u.startsWith('/api/admin/refunds/payout-queue')) {
      return { ok: true, json: async () => ({ content: queueRows, totalPages: 1, totalElements: 5 }) };
    }
    if (u.startsWith('/api/admin/refunds/payout/batch-preview')) {
      const reqIds = JSON.parse(opts.body).refundRequestIds;
      const items = queueRows.filter(r => reqIds.includes(r.id)).map(r => ({
        refundRequestId: r.id, requestCode: r.requestCode, bookingCode: r.bookingCode, customerName: r.customerName,
        toBankName: r.toBankName, toBin: r.toBin, toAccountNumberMasked: r.toAccountNumberMasked, amountVnd: r.approvedCashTotal
      }));
      const sum = items.reduce((a, b) => a + b.amountVnd, 0);
      return { ok: true, json: async () => ({ requestedCount: items.length, eligibleCount: items.length, ineligibleCount: 0, totalAmountVnd: sum, eligibleItems: items, ineligibleItems: [] }) };
    }
    if (u.startsWith('/api/admin/refunds/payout/batch-approve')) {
      batchApprovePayload = JSON.parse(opts.body);
      return {
        ok: true,
        json: async () => ({
          batchCode: 'BATCH-20261122-1234',
          overallStatus: 'PARTIAL_SUCCESS',
          requestedCount: 3, eligibleCount: 3, succeededCount: 1, processingCount: 1, unknownOutcomeCount: 0, failedCount: 1, skippedCount: 0,
          succeededTotalAmount: 50000,
          results: [
            { refundRequestId: 1, requestCode: 'RFD-1', bookingCode: 'BOX-1', amount: 50000, resultStatus: 'SUCCEEDED', referenceId: 'RFD-1-A1', message: 'OK' },
            { refundRequestId: 2, requestCode: 'RFD-2', bookingCode: 'BOX-2', amount: 80000, resultStatus: 'PROCESSING', referenceId: 'RFD-2-A1', message: 'Processing' },
            { refundRequestId: 3, requestCode: 'RFD-3', bookingCode: 'BOX-3', amount: 100000, resultStatus: 'FAILED', referenceId: 'RFD-3-A1', message: 'Bank error' }
          ]
        })
      };
    }
    if (u.startsWith('/api/admin/payouts/account-balance')) {
      return { ok: true, json: async () => ({ mode: 'MOCK', liveVerified: false, accountNumberMasked: '******MOCK', accountName: 'BOX MOCK', balance: '100000000', message: 'MOCK active' }) };
    }
    return { ok: true, json: async () => ({ content: [], totalPages: 1, totalElements: 0 }) };
  };

  try {
    initRefundOperationsWorkspace(doc);
    await new Promise(r => setTimeout(r, 25));

    // Collect the 5 rendered queue checkboxes
    for (const tr of doc.elements.opsPayoutQueueBody.children) {
      const cb = tr.children[0].children[0];
      rowCheckboxes.push(cb);
    }
    assert.equal(rowCheckboxes.length, 5);
    assert.equal(rowCheckboxes[4].disabled, true, 'Ineligible row checkbox must be disabled');

    // Select 3/5 (50k + 80k + 100k = 230,000 VND)
    for (let i = 0; i < 3; i++) {
      rowCheckboxes[i].checked = true;
      rowCheckboxes[i].dispatchEvent({ type: 'change', target: rowCheckboxes[i] });
    }
    assert.equal(doc.elements.batchSelectedCount.textContent, '3');
    assert.match(doc.elements.batchSelectedTotal.textContent, /230[.,]000 VND/);
    assert.equal(doc.elements.btnOpenBatchConfirm.disabled, false);

    // Preview & confirm modal opens with 3 items and 230,000 total
    doc.elements.btnOpenBatchConfirm.click();
    await new Promise(r => setTimeout(r, 25));
    assert.equal(doc.elements.payoutConfirmModal.classList.contains('d-none'), false);
    assert.equal(doc.elements.confirmSelectedCount.textContent, '3');
    assert.equal(doc.elements.confirmItemTableBody.children.length, 3);

    // Execute batch confirm -> sends exact 3 IDs & 230,000 expectedTotalVnd
    doc.elements.confirmNoteInput.value = 'Approved 3/5 test';
    doc.elements.btnConfirmExecute.click();
    await new Promise(r => setTimeout(r, 30));

    assert.deepEqual(batchApprovePayload.refundRequestIds, [1, 2, 3]);
    assert.equal(batchApprovePayload.expectedTotalVnd, 230000);
    assert.equal(batchApprovePayload.strictTotalMatch, true);
    assert.equal(doc.elements.batchPayoutResultPanel.classList.contains('d-none'), false);
    assert.match(doc.elements.batchResultSummary.textContent, /BATCH-20261122-1234/);
    assert.equal(doc.elements.batchResultTableBody.children.length, 3);
    assert.equal(doc.elements.batchSelectedCount.textContent, '0');

    // Check PayOS account balance button
    doc.elements.btnCheckPayoutBalance.click();
    await new Promise(r => setTimeout(r, 20));
    assert.match(doc.elements.payoutBalanceInfo.textContent, /MOCK/);
  } finally {
    globalThis.fetch = origFetch;
  }
});

test('contact-refund blocks confirm-account mismatch, supports savedBankAccountId reuse, and renders Tab 3 editable vs locked bank UI', async () => {
  const doc = createFakeDocument({
    refundSupportRoot: 'div',
    refundSavedBankWrap: 'div',
    refundSavedBankSelect: 'select',
    refundSaveBankWrap: 'div',
    refundSaveBankAccount: 'input',
    refundBankBin: 'select',
    refundAccountNumber: 'input',
    refundAccountNumberConfirm: 'input',
    refundAccountConfirmError: 'div',
    refundBankHolder: 'input',
    refundBankNote: 'input',
    refundOrderCode: 'input',
    refundPurchaseEmail: 'input',
    orderLookupStatus: 'div',
    refundOrderDetailsBox: 'div',
    refundOrderMeta: 'div',
    refundItemList: 'div',
    btnLookupOrder: 'button',
    publicRefundForm: 'form',
    refundReasonCategory: 'select',
    refundDescription: 'textarea',
    refundSubmitStatus: 'div',
    lookupRefundCode: 'input',
    lookupRefundEmail: 'input',
    refundStatusLookupForm: 'form',
    refundLookupStatus: 'div',
    refundLookupResult: 'div'
  });
  doc.elements.refundSavedBankWrap.classList.add('d-none');
  doc.elements.refundSaveBankWrap.classList.add('d-none');
  doc.elements.refundAccountConfirmError.classList.add('d-none');

  let submitCalls = [];
  let putBankPayload = null;
  let lookupEditable = true;
  const origFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    const u = String(url);
    if (u.includes('/api/refunds/public/banks')) {
      return { ok: true, json: async () => ({ '970422': 'MB Bank', '970436': 'Vietcombank' }) };
    }
    if (u === '/api/customer/bank-accounts') {
      return {
        ok: true,
        json: async () => ([
          { id: 77, bankBin: '970422', bankName: 'MB Bank', accountNumberMasked: '******9020', accountHolderName: 'NGUYEN VAN A', isDefault: true }
        ])
      };
    }
    if (u.includes('/lookup-order')) {
      return {
        ok: true,
        json: async () => ({
          bookingCode: 'BOX-20261122-1001',
          cinemaName: 'BOX Royal',
          orderType: 'TICKET_ONLY',
          totalPaidAmount: 100000,
          discountAmount: 0,
          items: [{ itemKind: 'TICKET', targetId: 1, label: 'Ticket A1', purchasedQuantity: 1, remainingRefundableQuantity: 1, netPaidAmount: 100000, discountAllocated: 0, eligibleHint: true }]
        })
      };
    }
    if (u === '/api/refunds/public') {
      const body = JSON.parse(opts.body);
      submitCalls.push(body);
      return { ok: true, json: async () => ({ requestCode: 'RFD-20261122-0001', requestStatus: 'PENDING_REVIEW', toAccountNumberMasked: '******9020' }) };
    }
    if (u.includes('/api/refunds/public/lookup?')) {
      return {
        ok: true,
        json: async () => ({
          requestCode: 'RFD-20261122-0001',
          requestStatus: lookupEditable ? 'PENDING_REVIEW' : 'AWAITING_PAYOUT_APPROVAL',
          bookingCode: 'BOX-20261122-1001',
          cinemaName: 'BOX Royal',
          toBin: '970422',
          toBankName: 'MB Bank',
          toAccountNumberMasked: '******9020',
          accountHolderName: 'NGUYEN VAN A',
          bankNote: 'CN Ha Noi',
          bankAccountEditable: lookupEditable,
          payoutStatus: 'AWAITING_APPROVAL',
          voucherStatus: 'NOT_REQUIRED',
          approvedCashTotal: 100000,
          approvedVoucherTotal: 0,
          items: [],
          communications: []
        })
      };
    }
    if (u.includes('/bank-account') && opts?.method === 'PUT') {
      putBankPayload = JSON.parse(opts.body);
      return { ok: true, json: async () => ({ requestCode: 'RFD-20261122-0001', toAccountNumberMasked: '******8888' }) };
    }
    return { ok: false, json: async () => ({ message: 'Not found' }) };
  };

  try {
    initContactRefund(doc);
    await new Promise(r => setTimeout(r, 20));
    assert.equal(doc.elements.refundSavedBankWrap.classList.contains('d-none'), false);
    assert.equal(doc.elements.refundSaveBankWrap.classList.contains('d-none'), false);
    assert.equal(doc.elements.refundSavedBankSelect.options.length, 2);

    // Lookup order and select item
    doc.elements.refundOrderCode.value = 'BOX-20261122-1001';
    doc.elements.refundPurchaseEmail.value = 'cust@example.com';
    doc.elements.btnLookupOrder.click();
    await new Promise(r => setTimeout(r, 15));

    const cb = createFakeElement('input');
    cb.dataset = { itemKind: 'TICKET', targetId: '1' };
    const qtyEl = createFakeElement('input');
    qtyEl.value = '1';
    doc.querySelectorAll = sel => sel === '.refund-item-check:checked' ? [cb] : [];
    doc.querySelector = sel => sel.includes('.refund-item-qty') ? qtyEl : null;

    // 1. Mismatched confirm account blocks submit
    doc.elements.refundReasonCategory.value = 'CINEMA_INCIDENT';
    doc.elements.refundDescription.value = 'Projector down';
    doc.elements.refundBankBin.value = '970422';
    doc.elements.refundAccountNumber.value = '1234567890';
    doc.elements.refundAccountNumberConfirm.value = '1234567899';
    doc.elements.refundBankHolder.value = 'NGUYEN VAN A';
    doc.elements.publicRefundForm.dispatchEvent({ type: 'submit', preventDefault() {} });
    await new Promise(r => setTimeout(r, 15));
    assert.equal(submitCalls.length, 0, 'Mismatch must block submit');
    assert.equal(doc.elements.refundAccountConfirmError.classList.contains('d-none'), false);

    // 2. Saved bank account reuse sends savedBankAccountId
    doc.elements.refundSavedBankSelect.value = '77';
    doc.elements.refundSavedBankSelect.dispatchEvent({ type: 'change', target: doc.elements.refundSavedBankSelect });
    assert.equal(doc.elements.refundAccountNumber.disabled, true);
    doc.elements.publicRefundForm.dispatchEvent({ type: 'submit', preventDefault() {} });
    await new Promise(r => setTimeout(r, 15));
    assert.equal(submitCalls.length, 1);
    assert.equal(submitCalls[0].savedBankAccountId, 77);
    assert.equal(doc.elements.refundAccountNumber.value, '');
    assert.equal(doc.elements.refundAccountNumberConfirm.value, '');

    // 3. Tab 3 editable vs locked bank UI
    doc.elements.lookupRefundCode.value = 'RFD-20261122-0001';
    doc.elements.lookupRefundEmail.value = 'cust@example.com';
    doc.elements.refundStatusLookupForm.dispatchEvent({ type: 'submit', preventDefault() {} });
    await new Promise(r => setTimeout(r, 20));
    assert.match(doc.elements.refundLookupResult.textContent, /Update Recipient Bank Account/);
    const editForm = doc.elements.refundLookupResult.children.find(c => c.tagName === 'FORM' && c.textContent.includes('Update Recipient Bank Account'));
    assert.ok(editForm, 'Editable bank form should be rendered');

    // Submit bank update in Tab 3
    const selects = editForm.children.filter(c => c.tagName === 'SELECT');
    const inputs = editForm.children.filter(c => c.tagName === 'INPUT');
    const bankSel = selects[selects.length - 1];
    bankSel.value = '970436';
    inputs[0].value = '9988776655';
    inputs[1].value = '9988776655';
    inputs[2].value = 'NGUYEN VAN B';
    editForm.dispatchEvent({ type: 'submit', preventDefault() {} });
    await new Promise(r => setTimeout(r, 20));
    assert.equal(putBankPayload.toBin, '970436');
    assert.equal(putBankPayload.toAccountNumber, '9988776655');
    assert.equal(putBankPayload.confirmAccountNumber, '9988776655');

    // Locked after business approval
    lookupEditable = false;
    doc.elements.refundStatusLookupForm.dispatchEvent({ type: 'submit', preventDefault() {} });
    await new Promise(r => setTimeout(r, 20));
    assert.match(doc.elements.refundLookupResult.textContent, /locked after business approval/i);
    const editFormAfter = doc.elements.refundLookupResult.children.find(c => c.tagName === 'FORM' && c.textContent.includes('Update Recipient Bank Account'));
    assert.equal(editFormAfter, undefined);
  } finally {
    globalThis.fetch = origFetch;
  }
});

test('customer-bank-accounts manages masked list, add with confirm check, set-default, and delete', async () => {
  const doc = createFakeDocument({
    customerBankAccountsRoot: 'div',
    bankAccountsStatus: 'div',
    savedBankAccountsList: 'div',
    savedBankAccountForm: 'form',
    bankFormTitle: 'h3',
    savedBankBin: 'select',
    savedBankHolder: 'input',
    savedBankAccountNumber: 'input',
    savedBankAccountConfirm: 'input',
    savedBankConfirmError: 'div',
    savedBankNote: 'input',
    savedBankDefault: 'input',
    btnSaveBankAccount: 'button',
    btnCancelEditBankAccount: 'button'
  });

  let accounts = [
    { id: 10, bankBin: '970422', bankName: 'MB Bank', accountNumberMasked: '******9020', accountHolderName: 'NGUYEN VAN A', bankNote: 'Main', isDefault: false }
  ];
  let postPayload = null;
  let defaultCalled = false;
  let deleteCalled = false;
  const origFetch = globalThis.fetch;
  globalThis.fetch = async (url, opts) => {
    const u = String(url);
    const method = (opts?.method || 'GET').toUpperCase();
    if (u === '/api/refunds/public/banks') {
      return { ok: true, json: async () => ({ '970422': 'MB Bank' }) };
    }
    if (u === '/api/customer/bank-accounts' && method === 'GET') {
      return { ok: true, json: async () => accounts };
    }
    if (u === '/api/customer/bank-accounts' && method === 'POST') {
      postPayload = JSON.parse(opts.body);
      const created = { id: 11, bankBin: postPayload.bankBin, bankName: 'MB Bank', accountNumberMasked: '******4321', accountHolderName: postPayload.accountHolderName, bankNote: postPayload.bankNote, isDefault: true };
      accounts = [...accounts, created];
      return { ok: true, json: async () => created };
    }
    if (u === '/api/customer/bank-accounts/10/default' && method === 'POST') {
      defaultCalled = true;
      return { ok: true, json: async () => ({}) };
    }
    if (u === '/api/customer/bank-accounts/10' && method === 'DELETE') {
      deleteCalled = true;
      accounts = accounts.filter(a => a.id !== 10);
      return { ok: true, json: async () => ({}) };
    }
    return { ok: false, json: async () => ({ message: 'Unexpected' }) };
  };

  try {
    initCustomerBankAccounts(doc);
    await new Promise(r => setTimeout(r, 20));
    assert.equal(doc.elements.savedBankAccountsList.children.length, 1);
    assert.match(doc.elements.savedBankAccountsList.textContent, /\*\*\*\*\*\*9020/);

    // Mismatch blocks POST
    doc.elements.savedBankBin.value = '970422';
    doc.elements.savedBankHolder.value = 'NGUYEN VAN B';
    doc.elements.savedBankAccountNumber.value = '0987654321';
    doc.elements.savedBankAccountConfirm.value = '0987654329';
    doc.elements.savedBankAccountForm.dispatchEvent({ type: 'submit', preventDefault() {} });
    await new Promise(r => setTimeout(r, 15));
    assert.equal(postPayload, null);

    // Matching confirm succeeds and clears inputs
    doc.elements.savedBankAccountConfirm.value = '0987654321';
    doc.elements.savedBankNote.value = 'Branch 1';
    doc.elements.savedBankDefault.checked = true;
    doc.elements.savedBankAccountForm.dispatchEvent({ type: 'submit', preventDefault() {} });
    await new Promise(r => setTimeout(r, 20));
    assert.equal(postPayload.accountNumber, '0987654321');
    assert.equal(postPayload.confirmAccountNumber, '0987654321');
    assert.equal(doc.elements.savedBankAccountNumber.value, '');
    assert.equal(doc.elements.savedBankAccountConfirm.value, '');

    // Set Default & Delete buttons work
    const firstCard = doc.elements.savedBankAccountsList.children[0];
    const buttons = firstCard.children[1].children;
    buttons[0].click(); // Set Default
    await new Promise(r => setTimeout(r, 15));
    assert.equal(defaultCalled, true);

    const refreshedCard = doc.elements.savedBankAccountsList.children[0];
    const delBtn = refreshedCard.children[1].children.find(b => b.textContent === 'Delete');
    delBtn.click();
    await new Promise(r => setTimeout(r, 15));
    assert.equal(deleteCalled, true);
  } finally {
    globalThis.fetch = origFetch;
  }
});

test('refund-ops displays bankNote & savedBankAccountId and calls hold-for-reverify with mandatory reason', async () => {
  const doc = createFakeDocument({
    refundOpsRoot: 'div',
    opsPayoutQueueBody: 'tbody',
    opsRefundTableBody: 'tbody',
    opsPayoutTxBody: 'tbody',
    opsRefundDetailPanel: 'div'
  });
  doc.elements.refundOpsRoot.dataset = { roleScope: 'admin', opsMode: 'refunds' };

  let holdPayload = null;
  const origFetch = globalThis.fetch;
  const origWindow = globalThis.window;
  globalThis.window = { prompt: () => 'Wrong holder name, please re-verify' };
  globalThis.fetch = async (url, opts) => {
    const u = String(url);
    if (u === '/api/admin/cinemas') return { ok: true, json: async () => [] };
    if (u.startsWith('/api/admin/refunds/payout-queue')) {
      return {
        ok: true,
        json: async () => ({
          content: [{ id: 55, requestCode: 'RFD-55', bookingCode: 'BOX-55', cinemaName: 'Royal', customerName: 'A', purchaseEmail: 'a@b.c', approvedCashTotal: 50000, toBankName: 'MB', toBin: '970422', toAccountNumberMasked: '******9020', requestStatus: 'AWAITING_PAYOUT_APPROVAL', payoutStatus: 'AWAITING_APPROVAL', payoutEligible: true }],
          totalPages: 1, totalElements: 1
        })
      };
    }
    if (u === '/api/admin/refunds/55') {
      return {
        ok: true,
        json: async () => ({
          id: 55, requestCode: 'RFD-55', bookingCode: 'BOX-55', orderType: 'TICKET_ONLY', cinemaName: 'Royal',
          customerName: 'A', purchaseEmail: 'a@b.c', toBankName: 'MB', toBin: '970422', toAccountNumberMasked: '******9020',
          accountHolderName: 'NGUYEN VAN A', bankNote: 'CN Cau Giay', savedBankAccountId: 77,
          requestStatus: 'AWAITING_PAYOUT_APPROVAL', payoutStatus: 'AWAITING_APPROVAL', payoutEligible: true,
          voucherStatus: 'NOT_REQUIRED', reasonCategory: 'CINEMA_INCIDENT', description: 'Test', items: []
        })
      };
    }
    if (u === '/api/admin/refunds/55/payout/hold-for-reverify') {
      holdPayload = JSON.parse(opts.body);
      return { ok: true, json: async () => ({ requestStatus: 'MORE_INFO_REQUIRED' }) };
    }
    return { ok: true, json: async () => ({ content: [], totalPages: 1, totalElements: 0 }) };
  };

  try {
    initRefundOperationsWorkspace(doc);
    await new Promise(r => setTimeout(r, 20));
    const row = doc.elements.opsPayoutQueueBody.children[0];
    const actBtns = row.children[7].children[0].children;
    const detailBtn = actBtns.find(b => b.textContent === 'Chi tiết');
    detailBtn.click();
    await new Promise(r => setTimeout(r, 20));
    assert.match(doc.elements.opsRefundDetailPanel.textContent, /CN Cau Giay/);
    assert.match(doc.elements.opsRefundDetailPanel.textContent, /TK đã lưu #77/);

    const holdBtn = actBtns.find(b => b.textContent === 'Tạm dừng & Yêu cầu xác minh lại TK');
    assert.ok(holdBtn);
    holdBtn.click();
    await new Promise(r => setTimeout(r, 20));
    assert.deepEqual(holdPayload, { reason: 'Wrong holder name, please re-verify' });
  } finally {
    globalThis.fetch = origFetch;
    globalThis.window = origWindow;
  }
});

test('refund-ops Payout History & Detail supports confirm-failed ONLY on MANUAL_RECONCILE_REQUIRED / UNKNOWN_OUTCOME / PROCESSING', async () => {
  const doc = createFakeDocument({
    refundOpsRoot: 'div',
    opsPayoutQueueBody: 'tbody',
    opsRefundTableBody: 'tbody',
    opsPayoutTxBody: 'tbody',
    opsPayoutTxDetailPanel: 'div'
  });
  doc.elements.refundOpsRoot.dataset = { roleScope: 'admin', opsMode: 'refunds' };

  let confirmFailedPayload = null;
  const origFetch = globalThis.fetch;
  const origWindow = globalThis.window;
  globalThis.window = { prompt: () => 'Verified failed on my.payos.vn' };
  globalThis.fetch = async (url, opts) => {
    const u = String(url);
    if (u === '/api/admin/cinemas') return { ok: true, json: async () => [] };
    if (u.startsWith('/api/admin/payouts?')) {
      return {
        ok: true,
        json: async () => ({
          content: [
            { id: 88, referenceId: 'RFD-88-A1', requestCode: 'RFD-88', bookingCode: 'BOX-88', cinemaName: 'Royal', toBin: '970422', toAccountNumberMasked: '******9020', toAccountHolderName: 'A', amount: 50000, status: 'PROCESSING', transactionState: 'MANUAL_RECONCILE_REQUIRED', payoutMode: 'LIVE' },
            { id: 89, referenceId: 'RFD-89-A1', requestCode: 'RFD-89', bookingCode: 'BOX-89', cinemaName: 'Royal', toBin: '970422', toAccountNumberMasked: '******9020', toAccountHolderName: 'B', amount: 50000, status: 'SUCCEEDED', transactionState: 'SUCCEEDED', payoutMode: 'LIVE' }
          ],
          totalPages: 1, totalElements: 2
        })
      };
    }
    if (u === '/api/admin/payouts/88/confirm-failed') {
      confirmFailedPayload = JSON.parse(opts.body);
      return { ok: true, json: async () => ({ status: 'FAILED' }) };
    }
    return { ok: true, json: async () => ({ content: [], totalPages: 1, totalElements: 0 }) };
  };

  try {
    initRefundOperationsWorkspace(doc);
    await new Promise(r => setTimeout(r, 25));
    const rows = doc.elements.opsPayoutTxBody.children;
    assert.equal(rows.length, 2);
    const procBtns = rows[0].children[6].children[0].children;
    const succBtns = rows[1].children[6].children[0].children;
    const failBtn = procBtns.find(b => b.textContent === 'Xác nhận thất bại');
    assert.ok(failBtn, 'MANUAL_RECONCILE_REQUIRED / PROCESSING row should expose confirm-failed button');
    assert.equal(succBtns.find(b => b.textContent === 'Xác nhận thất bại'), undefined, 'SUCCEEDED row must NOT expose confirm-failed');

    failBtn.click();
    await new Promise(r => setTimeout(r, 20));
    assert.equal(confirmFailedPayload.reason, 'Verified failed on my.payos.vn');
    assert.equal(confirmFailedPayload.note, 'Verified failed on my.payos.vn');
  } finally {
    globalThis.fetch = origFetch;
    globalThis.window = origWindow;
  }
});
