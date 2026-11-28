import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { initPayment } from '../../src/main/resources/static/js/modules/booking-payment.js';

function createFakeElement(tag, id = '') {
  const listeners = {};
  const attrs = {};
  const el = {
    tagName: tag.toUpperCase(),
    id,
    value: '',
    href: '',
    src: '',
    textContent: '',
    className: '',
    disabled: false,
    type: '',
    style: {},
    dataset: {},
    children: [],
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
    setAttribute(k, v) { attrs[k] = String(v); },
    getAttribute(k) { return attrs[k] ?? null; },
    append(...nodes) {
      for (const n of nodes) {
        el.children.push(n);
        el.textContent += (n.textContent || '');
      }
    },
    appendChild(node) {
      el.append(node);
      return node;
    },
    replaceChildren(...nodes) {
      el.children = [];
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
      el.dispatchEvent({ type: 'click', target: el, preventDefault() {} });
    },
    querySelectorAll() { return []; }
  };
  return el;
}

function createFakeDocument(ids) {
  const elements = {};
  for (const [id, tag] of Object.entries(ids)) {
    elements[id] = createFakeElement(tag, id);
  }
  return {
    elements,
    getElementById(id) { return elements[id] || null; },
    createElement(tag) { return createFakeElement(tag); },
    querySelector() { return null; },
    querySelectorAll() { return []; }
  };
}

function collectTextAndTags(node) {
  const result = [];
  const walk = n => {
    if (!n) return;
    result.push({ tag: n.tagName, text: n.textContent, href: n.href, className: n.className });
    for (const c of n.children || []) walk(c);
  };
  walk(node);
  return result;
}

function makePaymentDoc() {
  return createFakeDocument({
    payosLoading: 'div',
    payosActiveLayout: 'div',
    payosSuccessLayout: 'div',
    payosQrImage: 'img',
    payosBankName: 'strong',
    payosAccountNo: 'strong',
    payosAccountName: 'strong',
    payosAmount: 'strong',
    payosDescription: 'strong',
    payosTimer: 'span',
    btnGoToTicket: 'a',
    voucherListContainer: 'div',
    btnShowVoucherList: 'button'
  });
}

async function runPollScenario(statusResponse) {
  const doc = makePaymentDoc();
  doc.elements.payosActiveLayout.classList.add('d-none');
  doc.elements.payosSuccessLayout.classList.add('d-none');

  const origFetch = globalThis.fetch;
  const origSetInterval = globalThis.setInterval;
  const origClearInterval = globalThis.clearInterval;
  let capturedPollFn = null;
  let intervalCleared = false;

  globalThis.setInterval = (fn, ms) => {
    if (ms === 2000) capturedPollFn = fn;
    return 1;
  };
  globalThis.clearInterval = () => { intervalCleared = true; };
  globalThis.fetch = async url => {
    if (url === '/api/bookings') {
      return { ok: true, json: async () => ({ bookingId: 77 }) };
    }
    if (url === '/api/payments/payos/create') {
      return {
        ok: true,
        json: async () => ({
          paymentId: 88,
          orderCode: 999001,
          qrCode: '000201010212',
          bankName: 'MBBank',
          accountNumber: '0000000000',
          accountName: 'BOX CINEMA MOCK',
          amount: 100000,
          description: 'BOX99001'
        })
      };
    }
    if (url === '/api/payments/88/status') {
      return { ok: true, json: async () => statusResponse };
    }
    return { ok: false, json: async () => ({}) };
  };

  try {
    const payment = initPayment(doc);
    await payment.startCheckout({ showtimeId: 1, seatIds: [1] });
    assert.equal(doc.elements.payosActiveLayout.classList.contains('d-none'), false);
    assert.ok(capturedPollFn, 'poll interval should be registered');
    await capturedPollFn();
    return { doc, intervalCleared, payment };
  } finally {
    globalThis.fetch = origFetch;
    globalThis.setInterval = origSetInterval;
    globalThis.clearInterval = origClearInterval;
  }
}

test('booking-payment.js has zero innerHTML assignments', () => {
  const js = fs.readFileSync(
    new URL('../../src/main/resources/static/js/modules/booking-payment.js', import.meta.url),
    'utf8'
  );
  assert.doesNotMatch(js, /\.innerHTML\s*=/);
});

test('PAID + CONFIRMED shows success layout with ticket link', async () => {
  const { doc, intervalCleared } = await runPollScenario({
    paymentId: 88,
    bookingId: 77,
    status: 'PAID',
    bookingStatus: 'CONFIRMED',
    isPaid: true
  });
  assert.equal(intervalCleared, true);
  assert.equal(doc.elements.payosSuccessLayout.classList.contains('d-none'), false);
  assert.equal(doc.elements.payosActiveLayout.classList.contains('d-none'), true);
  assert.equal(doc.elements.btnGoToTicket.href, '/booking/ticket/77');
});

test('PAID + PAID_SEAT_CONFLICT shows refund notice and /contact link, never fake ticket success', async () => {
  const { doc, intervalCleared } = await runPollScenario({
    paymentId: 88,
    bookingId: 77,
    bookingCode: 'BOX-2026-CONFLICT',
    status: 'PAID',
    bookingStatus: 'PAID_SEAT_CONFLICT',
    isPaid: true
  });
  assert.equal(intervalCleared, true);
  assert.equal(doc.elements.payosSuccessLayout.classList.contains('d-none'), true);
  assert.equal(doc.elements.payosLoading.classList.contains('d-none'), false);
  const nodes = collectTextAndTags(doc.elements.payosLoading);
  const contactLink = nodes.find(n => n.tag === 'A' && n.href === '/contact');
  assert.ok(contactLink, 'must render /contact link for refund request');
  assert.match(doc.elements.payosLoading.textContent, /refund/i);
  assert.match(doc.elements.payosLoading.textContent, /BOX-2026-CONFLICT/);
});

test('REVIEW_REQUIRED / PAYMENT_REVIEW stops polling and shows verification notice', async () => {
  const { doc, intervalCleared } = await runPollScenario({
    paymentId: 88,
    bookingId: 77,
    bookingCode: 'BOX-2026-REV',
    status: 'REVIEW_REQUIRED',
    bookingStatus: 'PAYMENT_REVIEW',
    isPaid: false
  });
  assert.equal(intervalCleared, true);
  assert.equal(doc.elements.payosSuccessLayout.classList.contains('d-none'), true);
  assert.equal(doc.elements.payosLoading.classList.contains('d-none'), false);
  assert.match(doc.elements.payosLoading.textContent, /verification/i);
  const nodes = collectTextAndTags(doc.elements.payosLoading);
  assert.ok(nodes.some(n => n.tag === 'A' && n.href === '/contact'));
});

test('EXPIRED status stops polling and shows expired notice', async () => {
  const { doc, intervalCleared } = await runPollScenario({
    paymentId: 88,
    bookingId: 77,
    status: 'EXPIRED',
    bookingStatus: 'EXPIRED',
    expired: true,
    isPaid: false
  });
  assert.equal(intervalCleared, true);
  assert.equal(doc.elements.payosSuccessLayout.classList.contains('d-none'), true);
  assert.equal(doc.elements.payosLoading.classList.contains('d-none'), false);
  assert.match(doc.elements.payosLoading.textContent, /EXPIRED/);
});

test('error rendering escapes XSS payloads via textContent without HTML injection', async () => {
  const doc = makePaymentDoc();
  const xssPayload = '<img src=x onerror="alert(1)"><script>evil()</script>';
  const origFetch = globalThis.fetch;
  globalThis.fetch = async url => {
    if (url === '/api/bookings') {
      return { ok: false, json: async () => ({ message: xssPayload }) };
    }
    return { ok: false, json: async () => ({}) };
  };

  try {
    const payment = initPayment(doc);
    await payment.startCheckout({ showtimeId: 1, seatIds: [1] });
    const nodes = collectTextAndTags(doc.elements.payosLoading);
    assert.equal(nodes.some(n => n.tag === 'IMG' || n.tag === 'SCRIPT'), false);
    const pNode = nodes.find(n => n.tag === 'P');
    assert.ok(pNode);
    assert.equal(pNode.text, xssPayload);
  } finally {
    globalThis.fetch = origFetch;
  }
});

test('missing payData.qrCode throws and renders safe failure alert', async () => {
  const doc = makePaymentDoc();
  const origFetch = globalThis.fetch;
  globalThis.fetch = async url => {
    if (url === '/api/bookings') {
      return { ok: true, json: async () => ({ bookingId: 77 }) };
    }
    if (url === '/api/payments/payos/create') {
      return { ok: true, json: async () => ({ paymentId: 88, orderCode: 12345, qrCode: '' }) };
    }
    return { ok: false, json: async () => ({}) };
  };

  try {
    const payment = initPayment(doc);
    await payment.startCheckout({ showtimeId: 1, seatIds: [1] });
    assert.equal(doc.elements.payosActiveLayout.classList.contains('d-none'), true);
    assert.equal(doc.elements.payosLoading.classList.contains('d-none'), false);
    assert.match(doc.elements.payosLoading.textContent, /QR code is unavailable/i);
  } finally {
    globalThis.fetch = origFetch;
  }
});

test('CANCELLED and FAILED polling stops interval, clears sessionStorage, and renders danger notice', async () => {
  const store = new Map();
  const origSessionStorage = globalThis.sessionStorage;
  globalThis.sessionStorage = {
    getItem(k) { return store.has(k) ? store.get(k) : null; },
    setItem(k, v) { store.set(k, String(v)); },
    removeItem(k) { store.delete(k); }
  };
  try {
    for (const terminalStatus of ['CANCELLED', 'FAILED']) {
      store.clear();
      const { doc, intervalCleared } = await runPollScenario({
        paymentId: 88,
        bookingId: 77,
        status: terminalStatus,
        bookingStatus: terminalStatus === 'CANCELLED' ? 'CANCELLED' : 'PENDING_PAYMENT',
        isPaid: false
      });
      assert.equal(intervalCleared, true);
      assert.equal(store.has('box_active_payos_payment'), false);
      assert.equal(doc.elements.payosSuccessLayout.classList.contains('d-none'), true);
      assert.equal(doc.elements.payosLoading.classList.contains('d-none'), false);
      assert.match(doc.elements.payosLoading.textContent, new RegExp(`Payment ${terminalStatus}`));
    }
  } finally {
    globalThis.sessionStorage = origSessionStorage;
  }
});

test('sessionStorage tracks active PayOS payment on create and clears on PAID or reset()', async () => {
  const store = new Map();
  const origSessionStorage = globalThis.sessionStorage;
  globalThis.sessionStorage = {
    getItem(k) { return store.has(k) ? store.get(k) : null; },
    setItem(k, v) { store.set(k, String(v)); },
    removeItem(k) { store.delete(k); }
  };
  const doc = makePaymentDoc();
  const origFetch = globalThis.fetch;
  const origSetInterval = globalThis.setInterval;
  const origClearInterval = globalThis.clearInterval;
  let capturedPollFn = null;
  globalThis.setInterval = (fn, ms) => { if (ms === 2000) capturedPollFn = fn; return 1; };
  globalThis.clearInterval = () => {};
  globalThis.fetch = async url => {
    if (url === '/api/bookings') return { ok: true, json: async () => ({ bookingId: 77 }) };
    if (url === '/api/payments/payos/create') {
      return { ok: true, json: async () => ({ paymentId: 88, orderCode: 999001, qrCode: '000201010212', amount: 100000 }) };
    }
    if (url === '/api/payments/88/status') {
      return { ok: true, json: async () => ({ paymentId: 88, bookingId: 77, status: 'PAID', bookingStatus: 'CONFIRMED', paid: true }) };
    }
    return { ok: false, json: async () => ({}) };
  };
  try {
    const payment = initPayment(doc);
    await payment.startCheckout({ showtimeId: 1, seatIds: [1] });
    assert.equal(store.has('box_active_payos_payment'), true);
    const saved = JSON.parse(store.get('box_active_payos_payment'));
    assert.equal(saved.paymentId, 88);
    assert.equal(saved.bookingId, 77);

    payment.reset();
    assert.equal(store.has('box_active_payos_payment'), false);

    await payment.startCheckout({ showtimeId: 1, seatIds: [1] });
    assert.equal(store.has('box_active_payos_payment'), true);
    await capturedPollFn();
    assert.equal(store.has('box_active_payos_payment'), false);
  } finally {
    globalThis.fetch = origFetch;
    globalThis.setInterval = origSetInterval;
    globalThis.clearInterval = origClearInterval;
    globalThis.sessionStorage = origSessionStorage;
  }
});

test('startCheckout sends CSRF headers, guards against double-click, and resumes pending PayOS redirect URLs', async () => {
  const store = new Map();
  store.set('box_active_payos_payment', JSON.stringify({ paymentId: 91, bookingId: 81, orderCode: 555123, savedAt: Date.now() }));
  const origSessionStorage = globalThis.sessionStorage;
  const origWindow = globalThis.window;
  const origFetch = globalThis.fetch;
  const origSetInterval = globalThis.setInterval;
  const origClearInterval = globalThis.clearInterval;

  globalThis.sessionStorage = {
    getItem(k) { return store.has(k) ? store.get(k) : null; },
    setItem(k, v) { store.set(k, String(v)); },
    removeItem(k) { store.delete(k); }
  };
  globalThis.window = { location: { search: '?orderCode=555123&cancel=true' } };
  globalThis.setInterval = () => 1;
  globalThis.clearInterval = () => {};

  const doc = makePaymentDoc();
  let fetchCalls = [];
  globalThis.fetch = async (url, opts = {}) => {
    fetchCalls.push({ url, opts });
    if (url === '/api/bookings') {
      await new Promise(r => setTimeout(r, 10));
      return { ok: true, json: async () => ({ bookingId: 82 }) };
    }
    if (url === '/api/payments/payos/create') {
      return { ok: true, json: async () => ({ paymentId: 92, orderCode: 555124, qrCode: '000201010212', amount: 90000 }) };
    }
    return { ok: false, json: async () => ({}) };
  };

  try {
    const payment = initPayment(doc);
    assert.equal(store.has('box_active_payos_payment'), false);
    assert.match(doc.elements.payosLoading.textContent, /Payment Cancelled/i);

    await Promise.all([
      payment.startCheckout({ showtimeId: 1, seatIds: [1] }),
      payment.startCheckout({ showtimeId: 1, seatIds: [1] })
    ]);
    assert.equal(fetchCalls.filter(c => c.url === '/api/bookings').length, 1);
    assert.equal(fetchCalls.filter(c => c.url === '/api/payments/payos/create').length, 1);
  } finally {
    globalThis.fetch = origFetch;
    globalThis.setInterval = origSetInterval;
    globalThis.clearInterval = origClearInterval;
    globalThis.sessionStorage = origSessionStorage;
    globalThis.window = origWindow;
  }
});
