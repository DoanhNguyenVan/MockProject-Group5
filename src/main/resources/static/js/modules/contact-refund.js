import { getCsrfHeaders } from '../core/http-client.js';

export function initContactRefund(doc = document) {
  const root = doc.getElementById('refundSupportRoot');
  if (!root || root.dataset.initialized) return;
  root.dataset.initialized = 'true';

  const get = id => doc.getElementById(id);
  const el = (tag, text = '', className = '') => {
    const node = doc.createElement(tag);
    if (text !== null && text !== undefined) node.textContent = String(text);
    if (className) node.className = className;
    return node;
  };
  const fmtVnd = v => new Intl.NumberFormat('vi-VN').format(Math.round(Number(v || 0))) + ' VND';

  let currentOrder = null;
  let loadedBanks = {};
  let savedAccounts = [];

  function formatApiErrorMessage(res, data, fallbackMsg) {
    const baseMsg = (data && data.message) ? String(data.message) : fallbackMsg;
    if (res && res.status === 429) {
      const retryHeader = res.headers && typeof res.headers.get === 'function'
        ? res.headers.get('Retry-After')
        : null;
      const retrySec = retryHeader || (data && data.retryAfterSeconds ? String(data.retryAfterSeconds) : null);
      return retrySec
        ? `${baseMsg} (Rate limit 429 — Retry-After: ${retrySec}s)`
        : `${baseMsg} (Rate limit 429 — please wait before retrying)`;
    }
    if (data && data.errorCode === 'EVIDENCE_QUARANTINED') {
      return `Security Scan Quarantined File: ${baseMsg}`;
    }
    return baseMsg;
  }

  function activateSupportTab(tabName) {
    const tabMap = {
      inquiry: { btn: 'tab-inquiry-btn', pane: 'tab-inquiry' },
      refund: { btn: 'tab-refund-btn', pane: 'tab-refund' },
      lookup: { btn: 'tab-lookup-btn', pane: 'tab-lookup' }
    };
    const target = tabMap[tabName];
    if (!target) return;
    for (const [key, cfg] of Object.entries(tabMap)) {
      const btn = get(cfg.btn);
      const pane = get(cfg.pane);
      const isTarget = key === tabName;
      if (btn) {
        if (isTarget) btn.classList.add('active');
        else btn.classList.remove('active');
      }
      if (pane) {
        if (isTarget) {
          pane.classList.add('show', 'active');
        } else {
          pane.classList.remove('show', 'active');
        }
      }
    }
  }

  async function loadBanks() {
    const sel = get('refundBankBin');
    try {
      const res = await fetch('/api/refunds/public/banks');
      if (!res.ok) return;
      const banks = await res.json();
      loadedBanks = banks || {};
      if (!sel) return;
      sel.replaceChildren();
      sel.append(el('option', '-- Select Recipient Bank --', ''));
      sel.firstChild.value = '';
      for (const [bin, name] of Object.entries(loadedBanks)) {
        const opt = el('option', `${name} (${bin})`);
        opt.value = bin;
        sel.append(opt);
      }
    } catch (_) {}
  }

  function applySavedBankSelection(selectedId) {
    const useSaved = Boolean(selectedId);
    const manualIds = ['refundBankBin', 'refundAccountNumber', 'refundAccountNumberConfirm', 'refundBankHolder', 'refundBankNote', 'refundSaveBankAccount'];
    for (const id of manualIds) {
      const field = get(id);
      if (field) field.disabled = useSaved;
    }
    const reqIds = ['refundBankBin', 'refundAccountNumber', 'refundAccountNumberConfirm', 'refundBankHolder'];
    for (const id of reqIds) {
      const field = get(id);
      if (field) field.required = !useSaved;
    }
    if (useSaved) {
      const err = get('refundAccountConfirmError');
      if (err) { err.textContent = ''; err.classList.add('d-none'); }
    }
  }

  async function loadSavedBankAccounts() {
    try {
      const res = await fetch('/api/customer/bank-accounts', { credentials: 'same-origin' });
      if (!res.ok) return;
      const accounts = await res.json();
      savedAccounts = Array.isArray(accounts) ? accounts : [];
      get('refundSaveBankWrap')?.classList.remove('d-none');
      const wrap = get('refundSavedBankWrap');
      const sel = get('refundSavedBankSelect');
      if (!wrap || !sel || !savedAccounts.length) return;
      wrap.classList.remove('d-none');
      sel.replaceChildren();
      const manualOpt = el('option', '-- Enter new bank account manually --');
      manualOpt.value = '';
      sel.append(manualOpt);
      for (const acc of savedAccounts) {
        const isDef = Boolean(acc.isDefault ?? acc.default);
        const opt = el('option', `${acc.bankName || acc.bankBin} • ${acc.accountNumberMasked} • ${acc.accountHolderName}${isDef ? ' (Default)' : ''}`);
        opt.value = String(acc.id);
        sel.append(opt);
      }
    } catch (_) {}
  }

  function validateConfirmAccount() {
    const acc = (get('refundAccountNumber')?.value || '').trim();
    const conf = (get('refundAccountNumberConfirm')?.value || '').trim();
    const err = get('refundAccountConfirmError');
    if (!conf) {
      if (err) { err.textContent = ''; err.classList.add('d-none'); }
      return true;
    }
    if (acc !== conf) {
      if (err) {
        err.textContent = 'Account number confirmation does not match.';
        err.classList.remove('d-none');
      }
      return false;
    }
    if (err) { err.textContent = ''; err.classList.add('d-none'); }
    return true;
  }

  function showAlert(containerId, message, isError = false) {
    const box = get(containerId);
    if (!box) return;
    box.className = `alert ${isError ? 'alert-danger' : 'alert-success'} mt-3`;
    box.textContent = message;
  }

  function updateSelectedRefundableTotal() {
    const badge = get('refundSelectedTotalBadge');
    if (!badge || !currentOrder) return;
    let sum = 0;
    for (const cb of doc.querySelectorAll('.refund-item-check:checked')) {
      const kind = cb.dataset.itemKind;
      const targetId = Number(cb.dataset.targetId);
      const qtyEl = doc.querySelector(`.refund-item-qty[data-item-kind="${kind}"][data-target-id="${targetId}"]`);
      const qty = Math.max(1, Number(qtyEl?.value || 1));
      const matched = (currentOrder.items || []).find(it => it.itemKind === kind && Number(it.targetId) === targetId);
      if (matched) {
        const purchasedQty = Math.max(1, Number(matched.purchasedQuantity || 1));
        const unitNet = Number(matched.netPaidAmount || 0) / purchasedQty;
        sum += Math.round(unitNet * qty);
      }
    }
    badge.textContent = `Selected Refundable Total: ${fmtVnd(sum)}`;
  }

  async function handleOrderLookup() {
    const code = (get('refundOrderCode')?.value || '').trim();
    const email = (get('refundPurchaseEmail')?.value || '').trim();
    const statusBox = get('orderLookupStatus');
    if (statusBox) { statusBox.className = 'd-none'; statusBox.textContent = ''; }

    if (!code || !email) {
      showAlert('orderLookupStatus', 'Please enter both Order/Ticket Code and Purchase Email.', true);
      return;
    }

    try {
      const res = await fetch('/api/refunds/public/lookup-order', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
        credentials: 'same-origin',
        body: JSON.stringify({ orderCode: code, purchaseEmail: email })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        currentOrder = null;
        get('refundOrderDetailsBox')?.classList.add('d-none');
        showAlert('orderLookupStatus', formatApiErrorMessage(res, data, 'Order not found or email mismatch.'), true);
        return;
      }
      currentOrder = data;
      renderOrderItems(data);
      get('refundOrderDetailsBox')?.classList.remove('d-none');
    } catch (_) {
      showAlert('orderLookupStatus', 'Network error while verifying order.', true);
    }
  }

  function renderOrderItems(order) {
    const meta = get('refundOrderMeta');
    meta.replaceChildren();

    const items = order.items || [];
    const maxRefundableVnd = items.reduce((acc, it) => {
      if ((Number(it.remainingRefundableQuantity ?? 1)) <= 0) return acc;
      return acc + Number(it.netPaidAmount || 0);
    }, 0);

    meta.append(
      el('div', `Order #${order.bookingCode} • ${order.cinemaName || 'BOX Cinema'} • ${order.orderType || ''}`, 'fw-bold text-white'),
      el('div', `Paid Total: ${fmtVnd(order.totalPaidAmount)} | Order Voucher Discount: ${fmtVnd(order.discountAmount)}${order.voucherCode ? ' (' + order.voucherCode + ')' : ''} | Backend Refundable Balance: ${fmtVnd(maxRefundableVnd)}`, 'small text-secondary mt-1')
    );

    if (order.refundDeadlineAt || order.deadlineBasis) {
      const dlRow = el('div', '', 'd-flex align-items-center flex-wrap gap-2 mt-2');
      const dlBadgeClass = order.deadlineExceeded ? 'badge bg-danger' : 'badge bg-success';
      const dlStatusText = order.deadlineExceeded
        ? 'DEADLINE EXCEEDED (Requires Admin Exception)'
        : 'WITHIN REFUND WINDOW';
      dlRow.append(
        el('span', `Refund Window (Asia/Ho_Chi_Minh): ${order.refundDeadlineAt || 'Standard 72h'} [${order.deadlineBasis || 'STANDARD_72H'}]`, 'small text-info'),
        el('span', dlStatusText, dlBadgeClass)
      );
      meta.append(dlRow);
    }

    const hasCheckedInItems = Boolean(order.checkedIn) || items.some(it => Boolean(it.checkedIn) || ['USED', 'CHECKED_IN'].includes(String(it.itemStatus || '').toUpperCase()));
    if (hasCheckedInItems) {
      meta.append(
        el('div', 'Note: USED / CHECKED_IN tickets are eligible for submission and Manager/Admin review when backed by a verified cinema incident.', 'small text-warning mt-2')
      );
    }

    const list = get('refundItemList');
    list.replaceChildren();
    for (const item of items) {
      const row = el('div', '', 'p-3 mb-2 rounded border border-secondary border-opacity-25 bg-dark bg-opacity-50');
      const top = el('div', '', 'd-flex justify-content-between align-items-start gap-2');

      const left = el('div', '', 'form-check');
      const cb = doc.createElement('input');
      cb.type = 'checkbox';
      cb.className = 'form-check-input refund-item-check';
      cb.id = `rfi-${item.itemKind}-${item.targetId}`;
      cb.dataset.itemKind = item.itemKind;
      cb.dataset.targetId = String(item.targetId);
      cb.disabled = item.remainingRefundableQuantity <= 0;
      cb.addEventListener('change', updateSelectedRefundableTotal);

      const lbl = doc.createElement('label');
      lbl.className = 'form-check-label fw-semibold text-white';
      lbl.setAttribute('for', cb.id);
      lbl.textContent = item.label;

      const grossVal = item.grossAmount ?? item.grossPrice ?? (Number(item.netPaidAmount || 0) + Number(item.discountAllocated || 0));
      const isItemUsed = Boolean(item.checkedIn) || ['USED', 'CHECKED_IN'].includes(String(item.itemStatus || '').toUpperCase());
      const statusSuffix = item.itemStatus ? ` | Status: ${item.itemStatus}${isItemUsed ? ' (Reviewable under Incident)' : ''}` : '';

      const sub = el('div',
        `Gross: ${fmtVnd(grossVal)} |Net Paid: ${fmtVnd(item.netPaidAmount)} | Voucher Disc: ${fmtVnd(item.discountAllocated)} | Purchased: ${item.purchasedQuantity} | Available: ${item.remainingRefundableQuantity}${statusSuffix}`,
        'small text-secondary mt-1');
      const hint = el('div', item.eligibilityNote || '', `small mt-1 ${item.eligibleHint ? 'text-success' : 'text-warning'}`);

      left.append(cb, lbl, sub, hint);

      const qtyWrap = el('div', '', 'text-end');
      const qtyInput = doc.createElement('input');
      qtyInput.type = 'number';
      qtyInput.min = '1';
      qtyInput.max = String(Math.max(1, item.remainingRefundableQuantity));
      qtyInput.value = '1';
      qtyInput.className = 'form-control form-control-sm form-control-dark refund-item-qty';
      qtyInput.style.width = '80px';
      qtyInput.disabled = item.remainingRefundableQuantity <= 1;
      qtyInput.dataset.itemKind = item.itemKind;
      qtyInput.dataset.targetId = String(item.targetId);
      qtyInput.addEventListener('input', updateSelectedRefundableTotal);
      qtyWrap.append(el('label', 'Qty', 'small text-secondary d-block'), qtyInput);

      top.append(left, qtyWrap);
      row.append(top);
      list.append(row);
    }
    updateSelectedRefundableTotal();
  }

  async function handleEvidenceUpload(fileInputId, targetUrlInputId, statusId) {
    const fileInput = get(fileInputId);
    const file = fileInput?.files?.[0];
    if (!file) return;

    const buildFormData = () => {
      const fd = new FormData();
      fd.append('file', file);
      return fd;
    };

    try {
      let res = await fetch('/api/refunds/evidence/upload', {
        method: 'POST',
        headers: { ...getCsrfHeaders() },
        credentials: 'same-origin',
        body: buildFormData()
      });
      if (!res.ok && (res.status === 401 || res.status === 403 || res.status === 404)) {
        res = await fetch('/api/refunds/public/evidence-upload', {
          method: 'POST',
          headers: { ...getCsrfHeaders() },
          credentials: 'same-origin',
          body: buildFormData()
        });
      }
      const data = await res.json().catch(() => ({}));
      const resolvedUrl = data.publicUrl || data.url;
      if (res.ok && resolvedUrl) {
        const targetInput = get(targetUrlInputId);
        if (targetInput) targetInput.value = resolvedUrl;
        if (statusId) showAlert(statusId, 'Evidence image uploaded and security-scanned (CLEAN).');
      } else if (statusId) {
        showAlert(statusId, formatApiErrorMessage(res, data, 'Evidence upload failed.'), true);
      }
    } catch (_) {
      if (statusId) showAlert(statusId, 'Evidence upload failed.', true);
    }
  }

  async function handleRefundSubmit(e) {
    e.preventDefault();
    if (!currentOrder) {
      showAlert('refundSubmitStatus', 'Please verify your order first.', true);
      return;
    }
    const items = [];
    for (const cb of doc.querySelectorAll('.refund-item-check:checked')) {
      const qtyEl = doc.querySelector(`.refund-item-qty[data-item-kind="${cb.dataset.itemKind}"][data-target-id="${cb.dataset.targetId}"]`);
      items.push({
        itemKind: cb.dataset.itemKind,
        targetId: Number(cb.dataset.targetId),
        quantity: Number(qtyEl?.value || 1)
      });
    }
    if (!items.length) {
      showAlert('refundSubmitStatus', 'Please select at least one item or combo component.', true);
      return;
    }

    const selectedSavedId = (get('refundSavedBankSelect')?.value || '').trim();
    const accVal = (get('refundAccountNumber')?.value || '').trim();
    const confirmEl = get('refundAccountNumberConfirm');
    const confirmVal = confirmEl ? confirmEl.value.trim() : accVal;
    const holderVal = (get('refundBankHolder')?.value || '').trim();
    const bankNoteVal = (get('refundBankNote')?.value || '').trim();

    if (!selectedSavedId) {
      if (confirmEl && accVal !== confirmVal) {
        const err = get('refundAccountConfirmError');
        if (err) {
          err.textContent = 'Account number confirmation does not match.';
          err.classList.remove('d-none');
        }
        showAlert('refundSubmitStatus', 'Bank account number and confirmation do not match.', true);
        return;
      }
      if (get('refundBankHolder') && !holderVal) {
        showAlert('refundSubmitStatus', 'Please enter the account holder name.', true);
        return;
      }
    }

    const payload = {
      orderCode: get('refundOrderCode').value.trim(),
      purchaseEmail: get('refundPurchaseEmail').value.trim(),
      customerName: get('refundCustomerName')?.value.trim() || '',
      customerPhone: get('refundCustomerPhone')?.value.trim() || '',
      reasonCategory: get('refundReasonCategory').value,
      description: get('refundDescription').value.trim(),
      evidenceUrl: get('refundEvidenceUrl')?.value.trim() || null,
      items
    };

    if (selectedSavedId) {
      payload.savedBankAccountId = Number(selectedSavedId);
    } else {
      payload.toBin = get('refundBankBin').value;
      payload.toAccountNumber = accVal;
      payload.confirmAccountNumber = confirmVal;
      payload.bankAccountName = holderVal;
      if (bankNoteVal) payload.bankNote = bankNoteVal;
      if (get('refundSaveBankAccount')?.checked) payload.saveBankAccount = true;
    }

    const submitBtn = get('publicRefundForm')?.querySelector?.('button[type="submit"]');
    if (submitBtn) submitBtn.disabled = true;

    try {
      const res = await fetch('/api/refunds/public', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
        credentials: 'same-origin',
        body: JSON.stringify(payload)
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        showAlert('refundSubmitStatus', formatApiErrorMessage(res, data, 'Could not submit refund request.'), true);
        return;
      }
      const wf = data.workflowStatus || data.requestStatus;
      showAlert('refundSubmitStatus',
        `Refund request ${data.requestCode} received! Status: ${data.requestStatus} / Workflow: ${wf} (Pending review — not yet approved or refunded). Payout account: ${data.toAccountNumberMasked}.`);
      if (get('refundAccountNumber')) get('refundAccountNumber').value = '';
      if (get('refundAccountNumberConfirm')) get('refundAccountNumberConfirm').value = '';
      const err = get('refundAccountConfirmError');
      if (err) { err.textContent = ''; err.classList.add('d-none'); }
      if (get('lookupRefundCode')) get('lookupRefundCode').value = data.requestCode;
      if (get('lookupRefundEmail')) get('lookupRefundEmail').value = payload.purchaseEmail;
    } catch (_) {
      showAlert('refundSubmitStatus', 'Network error while submitting refund request.', true);
    } finally {
      if (submitBtn) submitBtn.disabled = false;
    }
  }

  async function fetchCustomerAppeals(requestCode, email) {
    if (!requestCode || !email) return [];
    try {
      const res = await fetch(
        `/api/refunds/public/${encodeURIComponent(requestCode)}/appeals?email=${encodeURIComponent(email)}&purchaseEmail=${encodeURIComponent(email)}`,
        { credentials: 'same-origin' }
      );
      if (!res.ok) return [];
      const list = await res.json();
      return Array.isArray(list) ? list : [];
    } catch (_) {
      return [];
    }
  }

  async function handleStatusLookup(e) {
    if (e) e.preventDefault();
    const code = (get('lookupRefundCode')?.value || '').trim();
    const email = (get('lookupRefundEmail')?.value || '').trim();
    if (!code || !email) {
      showAlert('refundLookupStatus', 'Please enter both Refund Code and Purchase Email.', true);
      return;
    }
    try {
      const res = await fetch(`/api/refunds/public/lookup?refundCode=${encodeURIComponent(code)}&email=${encodeURIComponent(email)}`, {
        credentials: 'same-origin'
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        get('refundLookupResult')?.classList.add('d-none');
        showAlert('refundLookupStatus', formatApiErrorMessage(res, data, 'Refund request not found.'), true);
        return;
      }
      const appeals = await fetchCustomerAppeals(data.requestCode || code, email);
      if (get('refundLookupStatus')) get('refundLookupStatus').className = 'd-none';
      renderLookupResult(data, appeals);
    } catch (_) {
      showAlert('refundLookupStatus', 'Network error looking up refund request.', true);
    }
  }

  function renderLookupResult(data, appeals = []) {
    const box = get('refundLookupResult');
    box.classList.remove('d-none');
    box.replaceChildren();

    const wfStatus = data.workflowStatus || data.requestStatus || 'REQUESTED';
    const latestAppeal = Array.isArray(appeals) && appeals.length > 0 ? appeals[0] : null;
    const appealStatusText = latestAppeal ? latestAppeal.status : 'NONE';

    const header = el('div', '', 'p-3 rounded border border-secondary border-opacity-25 bg-dark bg-opacity-50 mb-3');
    header.append(
      el('div', `${data.requestCode} — Status: ${data.requestStatus} (Workflow: ${wfStatus})`, 'h5 text-white mb-1'),
      el('div', `Order: ${data.bookingCode} • Cinema: ${data.cinemaName} • Method: ${data.refundMethod || 'BANK_PAYOUT'} • Bank: ${data.toBankName || ''} ${data.toAccountNumberMasked || ''} • Holder: ${data.accountHolderName || '-'}${data.bankNote ? ' • Note: ' + data.bankNote : ''}`, 'small text-secondary'),
      el('div', `Refund Status: ${wfStatus} | Payout Status: ${data.payoutStatus} | Fee Policy: ${data.feeAssessmentStatus || 'N/A'} | Appeal Status: ${appealStatusText} | Voucher Recovery: ${data.voucherStatus}`, 'small text-info mt-1'),
      el('div', `Requested Cash: ${fmtVnd(data.requestedCashTotal ?? data.approvedCashTotal)} | Approved Cash: ${fmtVnd(data.approvedCashTotal)} | Approved Voucher: ${fmtVnd(data.approvedVoucherTotal)}`, 'small text-success mt-1')
    );

    if (data.refundDeadlineAt || data.deadlineBasis) {
      const dlInfo = `Refund Deadline (Asia/Ho_Chi_Minh): ${data.refundDeadlineAt || '-'} [${data.deadlineBasis || 'STANDARD_72H'}]${data.deadlineExceeded ? ' — DEADLINE EXCEEDED' : ''}${data.deadlineExceptionReason ? ' (Admin Exception: ' + data.deadlineExceptionReason + ')' : ''}`;
      header.append(el('div', dlInfo, `small mt-1 ${data.deadlineExceeded && !data.deadlineExceptionReason ? 'text-warning' : 'text-secondary'}`));
    }

    if (data.decisionNote || data.payoutDecisionNote) {
      header.append(
        el('div', `Review / Decision Reason: ${data.decisionNote || data.payoutDecisionNote}`, 'alert alert-secondary py-1 px-2 small mt-2 mb-0')
      );
    }

    if (wfStatus === 'PAYOUT_BLOCKED' || data.payoutStatus === 'PAYOUT_BLOCKED' || data.payoutBlockedReason) {
      header.append(
        el('div', `PAYOUT_BLOCKED: ${data.payoutBlockedReason || 'Automatic payout is blocked pending fee policy or financial reconciliation review.'}`, 'alert alert-warning py-1 px-2 small mt-2 mb-0')
      );
    }

    if (data.bankAccountEditable === false) {
      header.append(el('div', 'Bank account modification is locked after business approval.', 'badge bg-secondary mt-2'));
    }
    if (data.recoveryVoucher?.newVoucherCode) {
      header.append(el('div', `New Recovery Voucher: ${data.recoveryVoucher.newVoucherCode} (${fmtVnd(data.recoveryVoucher.recoveryAmount)})`, 'badge bg-primary mt-2'));
    }
    box.append(header);

    if (data.bankAccountEditable === true) {
      const bankEditForm = doc.createElement('form');
      bankEditForm.className = 'p-3 mb-3 rounded border border-info border-opacity-25 bg-dark bg-opacity-50';
      bankEditForm.append(el('h6', 'Update Recipient Bank Account', 'text-info mb-2'));

      let editSavedSel = null;
      if (savedAccounts.length) {
        editSavedSel = doc.createElement('select');
        editSavedSel.className = 'form-select form-select-sm form-select-dark mb-2';
        const mOpt = el('option', '-- Enter new bank account manually --');
        mOpt.value = '';
        editSavedSel.append(mOpt);
        for (const acc of savedAccounts) {
          const opt = el('option', `${acc.bankName || acc.bankBin} • ${acc.accountNumberMasked} • ${acc.accountHolderName}`);
          opt.value = String(acc.id);
          editSavedSel.append(opt);
        }
        bankEditForm.append(editSavedSel);
      }

      const editBankSel = doc.createElement('select');
      editBankSel.className = 'form-select form-select-sm form-select-dark mb-2';
      const defOpt = el('option', '-- Select Recipient Bank --');
      defOpt.value = '';
      editBankSel.append(defOpt);
      for (const [bin, name] of Object.entries(loadedBanks)) {
        const opt = el('option', `${name} (${bin})`);
        opt.value = bin;
        editBankSel.append(opt);
      }
      if (data.toBin) editBankSel.value = data.toBin;

      const editAccInp = doc.createElement('input');
      editAccInp.type = 'text';
      editAccInp.className = 'form-control form-control-sm form-control-dark mb-2';
      editAccInp.placeholder = 'New Bank Account Number (6–19 digits)';

      const editConfInp = doc.createElement('input');
      editConfInp.type = 'text';
      editConfInp.className = 'form-control form-control-sm form-control-dark mb-2';
      editConfInp.placeholder = 'Confirm Bank Account Number';

      const editHolderInp = doc.createElement('input');
      editHolderInp.type = 'text';
      editHolderInp.className = 'form-control form-control-sm form-control-dark mb-2';
      editHolderInp.placeholder = 'Account Holder Name';
      editHolderInp.value = data.accountHolderName || '';

      const editNoteInp = doc.createElement('input');
      editNoteInp.type = 'text';
      editNoteInp.className = 'form-control form-control-sm form-control-dark mb-2';
      editNoteInp.placeholder = 'Ghi chú ngân hàng / chi nhánh (tùy chọn)';
      editNoteInp.value = data.bankNote || '';

      const editStatus = el('div', '', 'd-none');
      const editBtn = el('button', 'Update Bank Account', 'btn btn-sm btn-outline-info');
      editBtn.type = 'submit';

      if (editSavedSel) {
        editSavedSel.addEventListener('change', () => {
          const useSaved = Boolean(editSavedSel.value);
          editBankSel.disabled = useSaved;
          editAccInp.disabled = useSaved;
          editConfInp.disabled = useSaved;
          editHolderInp.disabled = useSaved;
          editNoteInp.disabled = useSaved;
        });
      }

      bankEditForm.append(editBankSel, editAccInp, editConfInp, editHolderInp, editNoteInp, editBtn, editStatus);
      bankEditForm.addEventListener('submit', async ev => {
        ev.preventDefault();
        editStatus.className = 'd-none';
        const savedId = (editSavedSel?.value || '').trim();
        const body = { purchaseEmail: (get('lookupRefundEmail')?.value || '').trim() };
        if (savedId) {
          body.savedBankAccountId = Number(savedId);
        } else {
          const accNum = editAccInp.value.trim();
          const confNum = editConfInp.value.trim();
          if (!editBankSel.value || !accNum || !confNum || !editHolderInp.value.trim()) {
            editStatus.className = 'alert alert-danger mt-2 py-1 small';
            editStatus.textContent = 'Please fill in bank, account number, confirmation, and holder name.';
            return;
          }
          if (accNum !== confNum) {
            editStatus.className = 'alert alert-danger mt-2 py-1 small';
            editStatus.textContent = 'Account number confirmation does not match.';
            return;
          }
          body.toBin = editBankSel.value;
          body.toAccountNumber = accNum;
          body.confirmAccountNumber = confNum;
          body.bankAccountName = editHolderInp.value.trim();
          if (editNoteInp.value.trim()) body.bankNote = editNoteInp.value.trim();
        }
        editBtn.disabled = true;
        try {
          const r = await fetch(`/api/refunds/public/${encodeURIComponent(data.requestCode)}/bank-account`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
            credentials: 'same-origin',
            body: JSON.stringify(body)
          });
          const respData = await r.json().catch(() => ({}));
          if (!r.ok) {
            editStatus.className = 'alert alert-danger mt-2 py-1 small';
            editStatus.textContent = formatApiErrorMessage(r, respData, 'Could not update bank account.');
            return;
          }
          editAccInp.value = '';
          editConfInp.value = '';
          await handleStatusLookup();
        } finally {
          editBtn.disabled = false;
        }
      });
      box.append(bankEditForm);
    }

    const itemsTitle = el('h6', 'Requested Items & Financial Breakdown', 'text-white mt-3');
    box.append(itemsTitle);
    for (const it of data.items || []) {
      const breakdown = (it.grossUnitPrice !== undefined || it.netPaidAmount !== undefined)
        ? ` [Gross: ${fmtVnd(it.grossUnitPrice)} | Voucher Allocated: ${fmtVnd(it.discountAllocatedAmount)} | Net Paid: ${fmtVnd(it.netPaidAmount)}]`
        : '';
      const li = el('div', `${it.itemLabel} — Qty ${it.requestedQuantity} — ${it.itemStatus}${breakdown}${it.decisionReason ? ' (' + it.decisionReason + ')' : ''}`, 'small text-light py-1 border-bottom border-secondary border-opacity-25');
      box.append(li);
    }

    if (Array.isArray(data.history) && data.history.length > 0) {
      box.append(el('h6', 'Refund Processing History', 'text-white mt-3'));
      for (const h of data.history) {
        const hCard = el('div', '', 'p-2 mb-1 rounded bg-black bg-opacity-25 border border-secondary border-opacity-25');
        hCard.append(
          el('div', `${h.action || 'UPDATE'} (${h.previousStatus || '-'} -> ${h.newStatus || '-'}) • ${h.actorRole || ''} • ${h.createdAt || ''}`, 'small text-info'),
          el('div', h.note || '', 'small text-light')
        );
        box.append(hCard);
      }
    }

    // Separate Customer Refund Appeals & Appeal History Section
    const appealSection = el('div', '', 'mt-3 p-3 rounded border border-warning border-opacity-25 bg-dark bg-opacity-50');
    appealSection.append(el('h6', `Customer Refund Appeals (Current Appeal Status: ${appealStatusText})`, 'text-warning mb-2'));

    if (Array.isArray(appeals) && appeals.length > 0) {
      for (const ap of appeals) {
        const apCard = el('div', '', 'p-2 mb-2 rounded bg-black bg-opacity-25 border border-secondary border-opacity-25');
        apCard.append(
          el('div', `${ap.appealCode || ('Appeal #' + ap.id)} — Status: ${ap.status} • Submitted: ${ap.submittedAt || ''}`, 'small fw-bold text-white'),
          el('div', `Appeal Reason: ${ap.appealReason || ''}`, 'small text-light mt-1')
        );
        if (ap.decisionNote) {
          apCard.append(el('div', `Review Decision Note (${ap.reviewedByName || 'Reviewer'}): ${ap.decisionNote}`, 'small text-info mt-1'));
        }
        if (Array.isArray(ap.history) && ap.history.length > 0) {
          for (const ah of ap.history) {
            apCard.append(
              el('div', `• [${ah.createdAt || ''}] ${ah.action}: ${ah.previousStatus || 'NONE'} -> ${ah.newStatus} (${ah.actorRole || ''}) ${ah.note ? '— ' + ah.note : ''}`, 'small text-secondary mt-1')
            );
          }
        }
        appealSection.append(apCard);
      }
    } else {
      appealSection.append(el('div', 'No customer appeals submitted for this refund request.', 'small text-secondary mb-2'));
    }

    const isRejected = ['REJECTED', 'PAYOUT_REJECTED', 'INELIGIBLE'].includes(String(wfStatus).toUpperCase())
      || ['REJECTED', 'PAYOUT_REJECTED', 'INELIGIBLE'].includes(String(data.requestStatus || '').toUpperCase());
    const hasOpenAppeal = Array.isArray(appeals) && appeals.some(a =>
      a && (a.activeLockOpen === true || ['APPEAL_SUBMITTED', 'APPEAL_UNDER_REVIEW'].includes(String(a.status || '').toUpperCase()))
    );

    if (isRejected && !hasOpenAppeal) {
      const appealForm = doc.createElement('form');
      appealForm.id = 'customerAppealForm';
      appealForm.className = 'mt-2 pt-2 border-top border-secondary border-opacity-25';
      appealForm.append(
        el('div', 'Submit a Refund Appeal (Available for REJECTED requests)', 'small fw-semibold text-warning mb-2')
      );

      const reasonInput = doc.createElement('textarea');
      reasonInput.id = 'customerAppealReason';
      reasonInput.className = 'form-control form-control-sm form-control-dark mb-2';
      reasonInput.rows = 3;
      reasonInput.required = true;
      reasonInput.placeholder = 'Explain why this refund rejection should be reconsidered and describe any supporting evidence...';

      const evUrlInput = doc.createElement('input');
      evUrlInput.id = 'customerAppealEvidenceUrl';
      evUrlInput.type = 'text';
      evUrlInput.className = 'form-control form-control-sm form-control-dark mb-2';
      evUrlInput.placeholder = 'Optional supporting evidence URL (/api/refunds/evidence/... or https://...)';

      const appealStatusBox = el('div', '', 'd-none');
      const appealBtn = el('button', 'Submit Refund Appeal', 'btn btn-sm btn-warning fw-semibold');
      appealBtn.type = 'submit';

      appealForm.append(reasonInput, evUrlInput, appealBtn, appealStatusBox);
      appealForm.addEventListener('submit', async ev => {
        ev.preventDefault();
        const appealReason = reasonInput.value.trim();
        const purchaseEmail = (get('lookupRefundEmail')?.value || data.purchaseEmail || '').trim();
        if (!appealReason) {
          appealStatusBox.className = 'alert alert-danger mt-2 py-1 small';
          appealStatusBox.textContent = 'Please provide a reason for your appeal.';
          return;
        }
        appealBtn.disabled = true;
        try {
          const targetIdOrCode = data.id || data.requestCode;
          const r = await fetch(`/api/refunds/public/${encodeURIComponent(targetIdOrCode)}/appeals`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
            credentials: 'same-origin',
            body: JSON.stringify({
              purchaseEmail,
              appealReason,
              evidenceUrl: evUrlInput.value.trim() || null
            })
          });
          const respData = await r.json().catch(() => ({}));
          if (!r.ok) {
            appealStatusBox.className = 'alert alert-danger mt-2 py-1 small';
            appealStatusBox.textContent = formatApiErrorMessage(r, respData, 'Could not submit refund appeal.');
            return;
          }
          reasonInput.value = '';
          evUrlInput.value = '';
          await handleStatusLookup();
        } finally {
          appealBtn.disabled = false;
        }
      });
      appealSection.append(appealForm);
    }
    box.append(appealSection);

    const commTitle = el('h6', 'Communication History', 'text-white mt-3');
    box.append(commTitle);
    for (const c of data.communications || []) {
      const msgCard = el('div', '', 'p-2 mb-2 rounded bg-black bg-opacity-25 border border-secondary border-opacity-25');
      msgCard.append(
        el('div', `${c.senderType} (${c.senderName || 'Customer'}) • ${c.createdAt || ''}`, 'small text-secondary'),
        el('div', c.message, 'small text-white mt-1')
      );
      box.append(msgCard);
    }

    // Allow customer reply with optional evidence URL
    const replyForm = doc.createElement('form');
    replyForm.className = 'mt-3 p-3 rounded border border-secondary border-opacity-25';
    const replyLbl = el('label', 'Send Additional Information / Reply', 'form-label small text-secondary fw-semibold');
    const replyInput = doc.createElement('textarea');
    replyInput.className = 'form-control form-control-dark mb-2';
    replyInput.rows = 3;
    replyInput.placeholder = 'Provide requested details or additional explanation...';
    const replyEvInput = doc.createElement('input');
    replyEvInput.type = 'text';
    replyEvInput.className = 'form-control form-control-dark mb-2';
    replyEvInput.placeholder = 'Optional evidence URL (https://...)';
    const replyBtn = el('button', 'Send Reply', 'btn btn-sm btn-box-primary');
    replyBtn.type = 'submit';
    replyForm.append(replyLbl, replyInput, replyEvInput, replyBtn);
    replyForm.addEventListener('submit', async ev => {
      ev.preventDefault();
      const message = replyInput.value.trim();
      if (!message) return;
      replyBtn.disabled = true;
      try {
        const r = await fetch(`/api/refunds/public/${encodeURIComponent(data.requestCode)}/respond`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
          credentials: 'same-origin',
          body: JSON.stringify({
            purchaseEmail: get('lookupRefundEmail').value.trim(),
            message,
            evidenceUrl: replyEvInput.value.trim() || null
          })
        });
        if (r.ok) {
          handleStatusLookup();
        }
      } finally {
        replyBtn.disabled = false;
      }
    });
    box.append(replyForm);
  }

  function applyUrlParameters() {
    const rawSearch = doc.defaultView?.location?.search
      ?? (typeof window !== 'undefined' && window.location ? window.location.search : '');
    if (!rawSearch) return;
    const params = new URLSearchParams(rawSearch);
    const tab = (params.get('tab') || '').trim().toLowerCase();
    const orderCode = (params.get('orderCode') || params.get('bookingCode') || '').trim();
    const refundCode = (params.get('refundCode') || '').trim();
    const email = (params.get('email') || params.get('purchaseEmail') || '').trim();

    if (tab === 'refund' || orderCode) {
      activateSupportTab('refund');
      if (orderCode && get('refundOrderCode')) {
        get('refundOrderCode').value = orderCode;
      }
      if (email && get('refundPurchaseEmail')) {
        get('refundPurchaseEmail').value = email;
      }
      const effectiveCode = (get('refundOrderCode')?.value || '').trim();
      const effectiveEmail = (get('refundPurchaseEmail')?.value || '').trim();
      if (effectiveCode && effectiveEmail) {
        handleOrderLookup();
      }
    } else if (tab === 'lookup' || refundCode) {
      activateSupportTab('lookup');
      if (refundCode && get('lookupRefundCode')) {
        get('lookupRefundCode').value = refundCode;
      }
      if (email && get('lookupRefundEmail')) {
        get('lookupRefundEmail').value = email;
      }
      const effectiveRfd = (get('lookupRefundCode')?.value || '').trim();
      const effectiveEmail = (get('lookupRefundEmail')?.value || '').trim();
      if (effectiveRfd && effectiveEmail) {
        handleStatusLookup();
      }
    }
  }

  get('btnLookupOrder')?.addEventListener('click', handleOrderLookup);
  get('refundEvidenceFile')?.addEventListener('change', () => handleEvidenceUpload('refundEvidenceFile', 'refundEvidenceUrl', 'refundSubmitStatus'));
  get('refundSavedBankSelect')?.addEventListener('change', ev => applySavedBankSelection(ev?.target?.value || get('refundSavedBankSelect')?.value || ''));
  get('refundAccountNumber')?.addEventListener('input', validateConfirmAccount);
  get('refundAccountNumberConfirm')?.addEventListener('input', validateConfirmAccount);
  get('publicRefundForm')?.addEventListener('submit', handleRefundSubmit);
  get('refundStatusLookupForm')?.addEventListener('submit', handleStatusLookup);
  loadBanks();
  loadSavedBankAccounts();
  applyUrlParameters();
}

if (typeof document !== 'undefined') {
  document.addEventListener('DOMContentLoaded', () => initContactRefund(document));
}
