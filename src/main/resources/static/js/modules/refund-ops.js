import { getCsrfHeaders } from '../core/http-client.js';

export function formatPayoutStage(stage, fallbackStatus = '') {
  const s = String(stage || fallbackStatus || '').toUpperCase();
  switch (s) {
    case 'REQUESTED': return { label: 'Mới yêu cầu', cls: 'bg-info text-dark' };
    case 'UNDER_REVIEW': return { label: 'Đang thẩm định', cls: 'bg-warning text-dark' };
    case 'APPROVED': return { label: 'Đã duyệt nghiệp vụ', cls: 'bg-info text-dark' };
    case 'PAYOUT_PENDING':
    case 'AWAITING_PAYOUT_APPROVAL': return { label: 'Chờ Admin duyệt chi', cls: 'bg-warning text-dark' };
    case 'PAYOUT_APPROVED': return { label: 'Đã phê duyệt', cls: 'bg-info text-dark' };
    case 'DISPATCHED':
    case 'PENDING': return { label: 'Đã gửi lệnh', cls: 'bg-primary' };
    case 'PROCESSING':
    case 'PAYOUT_PROCESSING': return { label: 'Đang xử lý', cls: 'bg-warning text-dark' };
    case 'UNKNOWN':
    case 'UNKNOWN_OUTCOME': return { label: 'Chưa xác định kết quả', cls: 'bg-secondary' };
    case 'MANUAL_RECONCILE_REQUIRED': return { label: 'Cần đối soát thủ công (my.payos.vn)', cls: 'bg-warning text-dark' };
    case 'PAYOUT_BLOCKED': return { label: 'Chặn chi (PAYOUT_BLOCKED)', cls: 'bg-danger' };
    case 'SUCCESS':
    case 'SUCCEEDED':
    case 'PAYOUT_SUCCEEDED':
    case 'COMPLETED': return { label: 'Thành công', cls: 'bg-success' };
    case 'FAILED':
    case 'PAYOUT_FAILED': return { label: 'Thất bại', cls: 'bg-danger' };
    case 'PAYOUT_REJECTED': return { label: 'Từ chối duyệt chi', cls: 'bg-danger' };
    case 'REJECTED': return { label: 'Đã từ chối', cls: 'bg-danger' };
    case 'APPEAL_SUBMITTED': return { label: 'Kháng nghị mới', cls: 'bg-warning text-dark' };
    case 'APPEAL_UNDER_REVIEW': return { label: 'Đang xét kháng nghị', cls: 'bg-info text-dark' };
    case 'APPEAL_ACCEPTED': return { label: 'Chấp nhận kháng nghị', cls: 'bg-success' };
    case 'APPEAL_REJECTED': return { label: 'Bác kháng nghị', cls: 'bg-danger' };
    case 'INELIGIBLE': return { label: 'Không đủ điều kiện', cls: 'bg-secondary' };
    case 'CANCELLED': return { label: 'Đã hủy', cls: 'bg-secondary' };
    default: return { label: s || '-', cls: 'bg-secondary' };
  }
}

function normalizeAuthenticatedEvidenceUrl(rawUrl) {
  if (!rawUrl) return '';
  const trimmed = String(rawUrl).trim();
  if (!trimmed) return '';
  if (trimmed.startsWith('/api/refunds/evidence/')) return trimmed;
  if (trimmed.startsWith('/api/refunds/public/evidence/')) {
    return '/api/refunds/evidence/' + trimmed.slice('/api/refunds/public/evidence/'.length);
  }
  if (trimmed.startsWith('/uploads/refund-evidence/')) {
    return '/api/refunds/evidence/' + trimmed.slice('/uploads/refund-evidence/'.length);
  }
  if (/^[A-Za-z0-9._-]+$/.test(trimmed)) {
    return '/api/refunds/evidence/' + encodeURIComponent(trimmed);
  }
  return trimmed;
}

export function initRefundOperationsWorkspace(doc = document) {
  const root = doc.getElementById('refundOpsRoot');
  if (!root || root.dataset.initialized) return;
  root.dataset.initialized = 'true';

  const roleScope = root.dataset.roleScope || 'manager'; // 'manager' or 'admin'
  const apiBase = roleScope === 'admin' ? '/api/admin' : '/api/manager';
  const mode = root.dataset.opsMode || 'all'; // 'all', 'refunds', 'incidents'

  const get = id => doc.getElementById(id);
  const el = (tag, text = '', className = '') => {
    const node = doc.createElement(tag);
    if (text !== null && text !== undefined) node.textContent = String(text);
    if (className) node.className = className;
    return node;
  };
  const fmtVnd = v => new Intl.NumberFormat('vi-VN').format(Math.round(Number(v || 0))) + ' VND';

  let currentPage = 0;
  let currentQueuePage = 0;
  let currentPayoutTxPage = 0;
  let currentQueueRows = [];
  const selectedQueueItems = new Map(); // id -> RefundRequestDetailDto
  let pendingConfirmAction = null; // { type: 'SINGLE'|'BATCH', refundRequestId?, ids: [], expectedTotalVnd: 0 }
  let pendingRejectRequestId = null;

  function buildSharedFilterParams(pageVal, includeStatusFilters = true) {
    const params = new URLSearchParams({ page: String(pageVal), size: '15' });
    const cinemaId = get('opsCinemaFilter')?.value;
    const search = get('opsRefundSearch')?.value?.trim();
    const reason = get('opsReasonFilter')?.value;
    const verifier = get('opsVerifierFilter')?.value?.trim();
    const minAmt = get('opsMinAmount')?.value?.trim();
    const maxAmt = get('opsMaxAmount')?.value?.trim();
    const fromDate = get('opsFromDate')?.value?.trim();
    const toDate = get('opsToDate')?.value?.trim();

    if (cinemaId) params.set('cinemaId', cinemaId);
    if (search) params.set('search', search);
    if (reason) params.set('reasonCategory', reason);
    if (verifier) params.set('verifier', verifier);
    if (minAmt) params.set('minAmount', minAmt);
    if (maxAmt) params.set('maxAmount', maxAmt);
    if (fromDate) params.set('fromDate', fromDate);
    if (toDate) params.set('toDate', toDate);

    if (includeStatusFilters) {
      const status = get('opsRefundStatusFilter')?.value;
      const payoutStatus = get('opsPayoutStatusFilter')?.value;
      if (status) params.set('status', status);
      if (payoutStatus) params.set('payoutStatus', payoutStatus);
    }
    return params;
  }

  function updateBatchActionBar() {
    const countEl = get('batchSelectedCount');
    const totalEl = get('batchSelectedTotal');
    const openBtn = get('btnOpenBatchConfirm');
    let sum = 0;
    for (const item of selectedQueueItems.values()) {
      sum += Math.round(Number(item.approvedCashTotal > 0 ? item.approvedCashTotal : item.requestedCashTotal) || 0);
    }
    if (countEl) countEl.textContent = String(selectedQueueItems.size);
    if (totalEl) totalEl.textContent = fmtVnd(sum);
    if (openBtn) openBtn.disabled = selectedQueueItems.size === 0;

    const headerCb = get('payoutQueueSelectAll');
    if (headerCb) {
      const eligibleIds = currentQueueRows.filter(r => r.payoutEligible).map(r => r.id);
      headerCb.checked = eligibleIds.length > 0 && eligibleIds.every(id => selectedQueueItems.has(id));
    }
  }

  async function loadPreflightStatus() {
    const badge = get('opsPayoutPreflightBadge');
    if (!badge) return;
    try {
      const res = await fetch(`${apiBase}/payouts/preflight-status`, { credentials: 'same-origin' });
      if (!res.ok) return;
      const pf = await res.json();
      const liveEnabled = Boolean(pf.livePayoutEnabled);
      const modeLabel = pf.payoutMode || 'MOCK';
      const feePolicy = pf.liveFeePolicy || pf.configuredFeePolicy || 'SENDER_PAYS';
      badge.textContent = `LIVE_PAYOUT_ENABLED: ${liveEnabled ? 'TRUE' : 'FALSE'} • Mode: ${modeLabel} • Fee Policy: ${feePolicy}`;
      badge.className = liveEnabled ? 'badge bg-danger py-2 px-3' : 'badge bg-info text-dark py-2 px-3';
    } catch (_) {}
  }

  async function loadLimitsSummary() {
    const bannerContent = get('opsLimitSummaryContent');
    const adminBox = get('adminLimitSummaryBox');
    if (!bannerContent && !adminBox) return;
    const params = new URLSearchParams();
    const cinemaId = get('opsCinemaFilter')?.value;
    const mgrId = get('limitSummaryManagerId')?.value?.trim();
    const incId = get('limitSummaryIncidentId')?.value?.trim();
    const dateVal = get('limitSummaryDate')?.value?.trim();
    if (cinemaId) params.set('cinemaId', cinemaId);
    if (mgrId && roleScope === 'admin') params.set('managerUserId', mgrId);
    if (incId) params.set('incidentId', incId);
    if (dateVal) params.set('date', dateVal);

    try {
      const qs = params.toString();
      const res = await fetch(`${apiBase}/refunds/limits/summary${qs ? '?' + qs : ''}`, { credentials: 'same-origin' });
      if (!res.ok) return;
      const s = await res.json();
      const renderInto = container => {
        container.replaceChildren();
        const line1 = el('div',
          `Ngày hạn mức: ${s.quotaDate || 'Hôm nay'} • Hạn mức mỗi yêu cầu (Manager Per-Request): ${fmtVnd(s.managerPerRequestLimitVnd ?? 500000)}`,
          'fw-semibold text-white mb-1');
        const line2 = el('div',
          `Quản lý / Ngày (Cap ${fmtVnd(s.managerDailyLimitVnd ?? 2000000)}): Đã chi (Committed): ${fmtVnd(s.managerCommittedVnd)} | Đang giữ chỗ (Reserved): ${fmtVnd(s.managerReservedVnd)} | Đã giải phóng (Released): ${fmtVnd(s.managerReleasedVnd)} | Khả dụng (Available): ${fmtVnd(s.managerAvailableVnd)}`,
          'text-info mb-1');
        const line3 = el('div',
          `Rạp #${s.cinemaId || 'All'} / Ngày (Cap ${fmtVnd(s.cinemaDailyLimitVnd ?? 5000000)}): Đã chi (Committed): ${fmtVnd(s.cinemaCommittedVnd)} | Đang giữ chỗ (Reserved): ${fmtVnd(s.cinemaReservedVnd)} | Đã giải phóng (Released): ${fmtVnd(s.cinemaReleasedVnd)} | Khả dụng (Available): ${fmtVnd(s.cinemaAvailableVnd)}`,
          'text-warning mb-1');
        const line4 = el('div',
          `Sự cố tích lũy${s.incidentId ? ' #' + s.incidentId : ''} (Cap ${fmtVnd(s.incidentCumulativeLimitVnd ?? 10000000)}): Đã chi: ${fmtVnd(s.incidentCommittedVnd)} | Đang giữ chỗ: ${fmtVnd(s.incidentReservedVnd)} | Đã giải phóng: ${fmtVnd(s.incidentReleasedVnd)} | Khả dụng: ${fmtVnd(s.incidentAvailableVnd)}`,
          'text-secondary');
        container.append(line1, line2, line3, line4);
      };
      if (bannerContent) renderInto(bannerContent);
      if (adminBox) renderInto(adminBox);
    } catch (_) {}
  }

  async function loadLimitConfigs() {
    const tbody = get('opsLimitConfigTableBody');
    if (!tbody || roleScope !== 'admin') return;
    try {
      const res = await fetch('/api/admin/refunds/limits', { credentials: 'same-origin' });
      tbody.replaceChildren();
      if (!res.ok) return;
      const list = await res.json();
      for (const cfg of list || []) {
        const tr = el('tr');
        const actTd = el('td');
        const pickBtn = el('button', 'Chọn sửa', 'btn btn-sm btn-outline-warning');
        pickBtn.onclick = () => {
          if (get('limitConfigKeySelect')) get('limitConfigKeySelect').value = cfg.configKey;
          if (get('limitAmountVndInput')) get('limitAmountVndInput').value = String(cfg.limitAmountVnd ?? '');
          get('limitChangeReasonInput')?.focus?.();
        };
        actTd.append(pickBtn);
        tr.append(
          el('td', cfg.configKey, 'font-monospace fw-bold text-info'),
          el('td', fmtVnd(cfg.limitAmountVnd), 'fw-bold text-warning'),
          el('td', cfg.description || '-', 'small text-light'),
          el('td', cfg.updatedAt ? String(cfg.updatedAt).slice(0, 19).replace('T', ' ') : '-', 'small text-secondary'),
          actTd
        );
        tbody.append(tr);
      }
    } catch (_) {}
  }

  async function saveLimitConfig() {
    if (roleScope !== 'admin') return;
    const keySel = get('limitConfigKeySelect');
    const amtInp = get('limitAmountVndInput');
    const reasonInp = get('limitChangeReasonInput');
    const msgBox = get('limitConfigStatusMsg');
    const btn = get('btnSaveLimitConfig');
    const configKey = keySel?.value?.trim() || '';
    const limitAmountVnd = Number(amtInp?.value || 0);
    const reason = reasonInp?.value?.trim() || '';

    if (!configKey || !(limitAmountVnd > 0) || !reason) {
      if (msgBox) {
        msgBox.className = 'alert alert-danger py-2 small mt-2';
        msgBox.textContent = 'Vui lòng nhập số tiền hạn mức hợp lệ (> 0) và lý do thay đổi bắt buộc.';
      }
      return;
    }
    if (btn) btn.disabled = true;
    try {
      const res = await fetch('/api/admin/refunds/limits', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
        credentials: 'same-origin',
        body: JSON.stringify({ configKey, limitAmountVnd, reason })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        if (msgBox) {
          msgBox.className = 'alert alert-danger py-2 small mt-2';
          msgBox.textContent = data.message || `Cập nhật hạn mức thất bại (HTTP ${res.status}).`;
        }
        return;
      }
      if (msgBox) {
        msgBox.className = 'alert alert-success py-2 small mt-2';
        msgBox.textContent = `Đã cập nhật ${data.configKey || configKey} -> ${fmtVnd(data.limitAmountVnd ?? limitAmountVnd)} và ghi lịch sử kiểm toán.`;
      }
      if (reasonInp) reasonInp.value = '';
      await loadLimitConfigs();
      await loadLimitsSummary();
    } catch (err) {
      if (msgBox) {
        msgBox.className = 'alert alert-danger py-2 small mt-2';
        msgBox.textContent = err?.message || 'Lỗi kết nối khi cập nhật hạn mức.';
      }
    } finally {
      if (btn) btn.disabled = false;
    }
  }

  async function loadCinemasIfAdmin() {
    const sel = get('opsCinemaFilter');
    if (!sel || roleScope !== 'admin') return;
    try {
      const res = await fetch('/api/admin/cinemas', { credentials: 'same-origin' });
      if (!res.ok) return;
      const cinemas = await res.json();
      sel.replaceChildren();
      const allOpt = el('option', 'All Cinemas');
      allOpt.value = '';
      sel.append(allOpt);
      for (const c of cinemas) {
        const opt = el('option', c.cinemaName);
        opt.value = String(c.cinemaId);
        sel.append(opt);
      }
    } catch (_) {}
  }

  async function loadPayoutQueue() {
    const tbody = get('opsPayoutQueueBody') || get('payoutQueueTableBody');
    if (!tbody || roleScope !== 'admin') return;
    const params = buildSharedFilterParams(currentQueuePage, false);
    const res = await fetch(`/api/admin/refunds/payout-queue?${params.toString()}`, { credentials: 'same-origin' });
    tbody.replaceChildren();
    if (!res.ok) {
      const tr = el('tr');
      const td = el('td', 'Không thể tải danh sách chờ duyệt chi.', 'text-danger py-3');
      td.colSpan = 8;
      tr.append(td);
      tbody.append(tr);
      return;
    }
    const page = await res.json();
    const rows = page.content || [];
    currentQueueRows = rows;
    const totalPages = Math.max(1, Number(page.totalPages || 1));
    if (get('opsQueuePageInfo')) {
      get('opsQueuePageInfo').textContent = `Page ${currentQueuePage + 1} of ${totalPages} (${page.totalElements ?? rows.length} total)`;
    }
    if (get('opsQueuePrev')) get('opsQueuePrev').disabled = currentQueuePage <= 0;
    if (get('opsQueueNext')) get('opsQueueNext').disabled = currentQueuePage + 1 >= totalPages;

    if (!rows.length) {
      const tr = el('tr');
      const td = el('td', 'Không có yêu cầu nào đang chờ Admin duyệt chi.', 'text-secondary py-3 text-center');
      td.colSpan = 8;
      tr.append(td);
      tbody.append(tr);
      updateBatchActionBar();
      return;
    }

    for (const r of rows) {
      const tr = el('tr');
      const cbTd = el('td');
      const cb = doc.createElement('input');
      cb.type = 'checkbox';
      cb.className = 'form-check-input payout-queue-check';
      cb.dataset.refundId = String(r.id);
      cb.disabled = !r.payoutEligible;
      cb.checked = r.payoutEligible && selectedQueueItems.has(r.id);
      cb.addEventListener('change', () => {
        if (cb.checked && r.payoutEligible) selectedQueueItems.set(r.id, r);
        else selectedQueueItems.delete(r.id);
        updateBatchActionBar();
      });
      cbTd.append(cb);

      const codeTd = el('td');
      codeTd.append(
        el('div', r.requestCode, 'fw-bold font-monospace text-white'),
        el('div', r.createdAt ? String(r.createdAt).slice(0, 16).replace('T', ' ') : '', 'small text-secondary')
      );

      const orderTd = el('td');
      orderTd.append(
        el('div', r.bookingCode, 'fw-semibold text-white'),
        el('div', r.cinemaName || '', 'small text-secondary')
      );

      const custTd = el('td');
      custTd.append(
        el('div', r.customerName || r.accountHolderName || '-', 'text-white small fw-semibold'),
        el('div', r.purchaseEmail || '', 'small text-secondary')
      );

      const verifyTd = el('td');
      verifyTd.append(
        el('div', r.reasonCategory || '', 'small text-warning'),
        el('div', `Xác minh: ${r.reviewedByName || 'N/A'}`, 'small text-secondary')
      );

      const amtTd = el('td');
      const amtVal = r.approvedCashTotal > 0 ? r.approvedCashTotal : r.requestedCashTotal;
      amtTd.append(
        el('div', fmtVnd(amtVal), 'fw-bold text-info'),
        el('div', `${r.toBankName || ''} • ${r.toAccountNumberMasked || ''}`, 'small text-secondary')
      );

      const eligTd = el('td');
      if (r.payoutEligible) {
        eligTd.append(el('span', 'Đủ điều kiện chi', 'badge bg-success'));
      } else {
        eligTd.append(el('span', 'Không đủ ĐK', 'badge bg-danger'));
        if (r.payoutEligibilityErrors && r.payoutEligibilityErrors.length) {
          eligTd.append(el('div', r.payoutEligibilityErrors[0], 'small text-danger mt-1'));
        }
      }

      const actTd = el('td');
      const actWrap = el('div', '', 'd-flex gap-1 flex-wrap');
      const detailBtn = el('button', 'Chi tiết', 'btn btn-sm btn-outline-light');
      detailBtn.onclick = () => openRefundDetail(r.id);
      actWrap.append(detailBtn);

      if (r.payoutEligible) {
        const appBtn = el('button', 'Duyệt chi', 'btn btn-sm btn-success');
        appBtn.onclick = () => openSingleConfirmModal(r);
        actWrap.append(appBtn);
      }
      const rejBtn = el('button', 'Từ chối', 'btn btn-sm btn-outline-danger');
      rejBtn.onclick = () => openRejectModal(r);
      actWrap.append(rejBtn);

      const holdBtn = el('button', 'Tạm dừng & Yêu cầu xác minh lại TK', 'btn btn-sm btn-outline-warning');
      holdBtn.onclick = () => holdForBankReverify(r.id);
      actWrap.append(holdBtn);
      actTd.append(actWrap);

      tr.append(cbTd, codeTd, orderTd, custTd, verifyTd, amtTd, eligTd, actTd);
      tbody.append(tr);
    }
    updateBatchActionBar();
  }

  async function loadRefunds() {
    const tbody = get('opsRefundTableBody');
    if (!tbody) return;
    const params = buildSharedFilterParams(currentPage, true);

    const res = await fetch(`${apiBase}/refunds?${params.toString()}`, { credentials: 'same-origin' });
    tbody.replaceChildren();
    if (!res.ok) {
      const tr = el('tr');
      const td = el('td', 'Unable to load refund requests.', 'text-danger py-3');
      td.colSpan = 7;
      tr.append(td);
      tbody.append(tr);
      return;
    }
    const page = await res.json();
    const rows = page.content || [];
    const totalPages = Math.max(1, Number(page.totalPages || 1));
    if (get('opsRefundPageInfo')) {
      get('opsRefundPageInfo').textContent = `Page ${currentPage + 1} of ${totalPages} (${page.totalElements ?? rows.length} total)`;
    }
    if (get('opsRefundPrev')) get('opsRefundPrev').disabled = currentPage <= 0;
    if (get('opsRefundNext')) get('opsRefundNext').disabled = currentPage + 1 >= totalPages;
    if (!rows.length) {
      const tr = el('tr');
      const td = el('td', 'No refund requests match current filters.', 'text-secondary py-3 text-center');
      td.colSpan = 7;
      tr.append(td);
      tbody.append(tr);
      return;
    }

    for (const r of rows) {
      const tr = el('tr');
      const stage = formatPayoutStage(r.payoutDisplayStage, r.requestStatus);
      tr.append(
        el('td', r.requestCode, 'fw-bold font-monospace'),
        el('td', `${r.bookingCode} (${r.cinemaName || ''})`),
        el('td', r.customerName ? `${r.customerName} (${r.purchaseEmail})` : r.purchaseEmail),
        el('td', `${fmtVnd(r.approvedCashTotal > 0 ? r.approvedCashTotal : r.requestedCashTotal)} / ${r.toAccountNumberMasked || ''}`),
        el('td', `${r.requestStatus} [${stage.label}]`),
        el('td', `PayOS: ${r.payoutStatus} | Vch: ${r.voucherStatus}`)
      );
      const actionTd = el('td');
      const btn = el('button', 'Review / Detail', 'btn btn-sm btn-outline-danger me-1');
      btn.onclick = () => openRefundDetail(r.id);
      actionTd.append(btn);
      tr.append(actionTd);
      tbody.append(tr);
    }
  }

  async function loadPayoutTransactions() {
    const tbody = get('opsPayoutTxBody') || get('payoutTxTableBody');
    if (!tbody || roleScope !== 'admin') return;
    const params = new URLSearchParams({ page: String(currentPayoutTxPage), size: '15' });
    const status = get('payoutTxStatusFilter')?.value;
    const batch = get('payoutTxBatchFilter')?.value?.trim();
    const search = get('payoutTxSearch')?.value?.trim();
    if (status) params.set('status', status);
    if (batch) params.set('batchCode', batch);
    if (search) params.set('search', search);

    const res = await fetch(`/api/admin/payouts?${params.toString()}`, { credentials: 'same-origin' });
    tbody.replaceChildren();
    if (!res.ok) return;
    const page = await res.json();
    const rows = page.content || [];
    const totalPages = Math.max(1, Number(page.totalPages || 1));
    if (get('opsPayoutTxPageInfo')) {
      get('opsPayoutTxPageInfo').textContent = `Page ${currentPayoutTxPage + 1} of ${totalPages} (${page.totalElements ?? rows.length} total)`;
    }
    if (get('opsPayoutTxPrev')) get('opsPayoutTxPrev').disabled = currentPayoutTxPage <= 0;
    if (get('opsPayoutTxNext')) get('opsPayoutTxNext').disabled = currentPayoutTxPage + 1 >= totalPages;

    if (!rows.length) {
      const tr = el('tr');
      const td = el('td', 'Chưa có giao dịch PayOS Payout nào khớp bộ lọc.', 'text-secondary py-3 text-center');
      td.colSpan = 7;
      tr.append(td);
      tbody.append(tr);
      return;
    }

    for (const tx of rows) {
      const tr = el('tr');
      const stage = formatPayoutStage(tx.displayStage || tx.status, tx.status);

      const refTd = el('td');
      refTd.append(
        el('div', tx.referenceId, 'fw-bold font-monospace text-white'),
        el('div', tx.batchCode ? `Batch: ${tx.batchCode}` : 'Đơn lẻ', 'small text-info')
      );

      const reqTd = el('td');
      reqTd.append(
        el('div', `${tx.requestCode || '-'} • ${tx.bookingCode || ''}`, 'small text-white'),
        el('div', tx.cinemaName || '', 'small text-secondary')
      );

      const bankTd = el('td');
      bankTd.append(
        el('div', tx.toAccountHolderName || tx.customerName || '-', 'small text-white'),
        el('div', `BIN ${tx.toBin} • ${tx.toAccountNumberMasked}`, 'small text-secondary')
      );

      const amtTd = el('td', fmtVnd(tx.amount), 'fw-bold text-info');

      const stTd = el('td');
      stTd.append(el('span', `${stage.label} (${tx.status})`, `badge ${stage.cls}`));
      if (tx.unknownOutcome) {
        stTd.append(el('div', 'Chưa xác định kết quả — Cần đối soát', 'small text-warning mt-1'));
      }

      const idTd = el('td');
      idTd.append(
        el('div', `PayOS: ${tx.payosPayoutId || 'N/A'} [${tx.payoutMode}]`, 'small text-light'),
        el('div', tx.initiatedAt ? String(tx.initiatedAt).slice(0, 19).replace('T', ' ') : '', 'small text-secondary')
      );

      const actTd = el('td');
      const wrap = el('div', '', 'd-flex gap-1');
      const detailBtn = el('button', 'Chi tiết', 'btn btn-sm btn-outline-light');
      detailBtn.onclick = () => openPayoutTxDetail(tx.id);
      wrap.append(detailBtn);
      if (tx.status === 'PROCESSING' || tx.status === 'PENDING') {
        const recBtn = el('button', 'Đối soát', 'btn btn-sm btn-warning');
        recBtn.onclick = async () => {
          recBtn.disabled = true;
          try {
            await fetch(`/api/admin/payouts/${tx.id}/reconcile`, {
              method: 'POST', headers: { ...getCsrfHeaders() }, credentials: 'same-origin'
            });
            await loadPayoutTransactions();
            await loadPayoutQueue();
          } finally {
            recBtn.disabled = false;
          }
        };
        wrap.append(recBtn);
      }
      if (tx.status === 'PROCESSING' || tx.transactionState === 'MANUAL_RECONCILE_REQUIRED' || tx.unknownOutcome || tx.displayStage === 'UNKNOWN_OUTCOME' || tx.displayStage === 'MANUAL_RECONCILE_REQUIRED') {
        const failBtn = el('button', 'Xác nhận thất bại', 'btn btn-sm btn-outline-danger');
        failBtn.onclick = () => confirmPayoutFailed(tx.id);
        wrap.append(failBtn);
      }
      actTd.append(wrap);

      tr.append(refTd, reqTd, bankTd, amtTd, stTd, idTd, actTd);
      tbody.append(tr);
    }
  }

  async function confirmPayoutFailed(txId) {
    let reason = '';
    if (typeof window !== 'undefined' && typeof window.prompt === 'function') {
      const entered = window.prompt('CẢNH BÁO: Hãy kiểm tra kỹ trạng thái giao dịch trên cổng my.payos.vn trước khi xác nhận thất bại!\nNhập lý do xác nhận giao dịch thất bại (bắt buộc):');
      if (entered === null) return;
      reason = entered.trim();
    }
    if (!reason) {
      if (typeof window !== 'undefined' && typeof window.alert === 'function') {
        window.alert('Vui lòng nhập lý do xác nhận thất bại.');
      }
      return;
    }
    try {
      const res = await fetch(`/api/admin/payouts/${txId}/confirm-failed`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
        credentials: 'same-origin',
        body: JSON.stringify({ reason, note: reason })
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        if (typeof window !== 'undefined' && typeof window.alert === 'function') {
          window.alert(err.message || `Xác nhận thất bại không thành công (HTTP ${res.status}).`);
        }
        return;
      }
      await loadPayoutTransactions();
      await loadPayoutQueue();
      await loadRefunds();
      const panel = get('opsPayoutTxDetailPanel');
      if (panel && !panel.classList.contains('d-none')) {
        await openPayoutTxDetail(txId);
      }
    } catch (_) {
      if (typeof window !== 'undefined' && typeof window.alert === 'function') {
        window.alert('Lỗi kết nối khi xác nhận giao dịch thất bại.');
      }
    }
  }

  async function openPayoutTxDetail(txId) {
    const res = await fetch(`/api/admin/payouts/${txId}`, { credentials: 'same-origin' });
    if (!res.ok) return;
    const tx = await res.json();
    const panel = get('opsPayoutTxDetailPanel') || get('payoutTxDetailPanel');
    if (!panel) return;
    panel.classList.remove('d-none');
    panel.replaceChildren();
    const stage = formatPayoutStage(tx.displayStage || tx.transactionState || tx.status, tx.status);
    panel.append(
      el('h5', `Giao dịch Payout ${tx.referenceId} — ${stage.label}`, 'text-white mb-2'),
      el('div', `Batch: ${tx.batchCode || 'Single'} • Idempotency Key: ${tx.idempotencyKey || '-'}`, 'small text-info mb-1'),
      el('div', `Refund: ${tx.requestCode || '-'} • Booking: ${tx.bookingCode || '-'} • Cinema: ${tx.cinemaName || '-'}`, 'small text-secondary mb-1'),
      el('div', `Amount: ${fmtVnd(tx.amount)} • Bank BIN ${tx.toBin} • Account ${tx.toAccountNumberMasked} • Holder ${tx.toAccountHolderName || '-'}`, 'small text-light mb-1'),
      el('div', `PayOS Payout ID: ${tx.payosPayoutId || 'N/A'} | Txn ID: ${tx.payosTransactionId || 'N/A'} | Code: ${tx.payosResponseCode || '-'} | State: ${tx.transactionState || '-'}`, 'small text-warning mb-1'),
      el('div', `Error / Desc: ${tx.errorMessage || tx.payosResponseDesc || 'None'}`, 'small text-secondary')
    );
    const txActionWrap = el('div', '', 'd-flex gap-2 flex-wrap mt-2');
    if (tx.status === 'PROCESSING' || tx.status === 'PENDING' || tx.status === 'UNKNOWN' || tx.unknownOutcome || tx.transactionState === 'MANUAL_RECONCILE_REQUIRED') {
      const recNowBtn = el('button', 'Đối soát ngay (Manual Reconcile)', 'btn btn-sm btn-warning');
      recNowBtn.onclick = async () => {
        recNowBtn.disabled = true;
        try {
          await fetch(`/api/admin/payouts/${tx.id}/reconcile`, {
            method: 'POST', headers: { ...getCsrfHeaders() }, credentials: 'same-origin'
          });
          await openPayoutTxDetail(tx.id);
          await loadPayoutTransactions();
          await loadPayoutQueue();
          await loadLimitsSummary();
        } finally {
          recNowBtn.disabled = false;
        }
      };
      txActionWrap.append(recNowBtn);
    }
    if (tx.status === 'PROCESSING' || tx.status === 'UNKNOWN' || tx.transactionState === 'MANUAL_RECONCILE_REQUIRED' || tx.unknownOutcome || tx.displayStage === 'UNKNOWN_OUTCOME' || tx.displayStage === 'MANUAL_RECONCILE_REQUIRED') {
      const failBtn = el('button', 'Xác nhận thất bại (đã kiểm tra my.payos.vn)', 'btn btn-sm btn-outline-danger');
      failBtn.onclick = () => confirmPayoutFailed(tx.id);
      txActionWrap.append(failBtn);
    }
    panel.append(txActionWrap);

    try {
      const logRes = await fetch(`/api/admin/payouts/${txId}/reconciliation-logs`, { credentials: 'same-origin' });
      if (logRes && logRes.ok) {
        const logs = await logRes.json();
        if (Array.isArray(logs) && logs.length) {
          panel.append(el('h6', 'Lịch sử đối soát (Payout Reconciliation Logs)', 'text-info mt-3 mb-1'));
          for (const lg of logs) {
            panel.append(el('div',
              `[${lg.createdAt ? String(lg.createdAt).slice(0, 19).replace('T', ' ') : ''}] ${lg.triggerSource}: ${lg.previousState || '-'} -> ${lg.newState} (Code: ${lg.providerCode || '-'} | ${lg.providerDesc || ''})`,
              'small text-secondary py-1 border-bottom border-secondary border-opacity-25'));
          }
        }
      }
    } catch (_) {}
  }

  async function checkPayoutBalance() {
    const out = get('payoutBalanceInfo');
    if (!out) return;
    out.textContent = 'Đang kiểm tra kênh Chi PayOS...';
    try {
      const res = await fetch('/api/admin/payouts/account-balance', { credentials: 'same-origin' });
      const b = await res.json();
      out.textContent = `[${b.mode}] ${b.liveVerified ? 'LIVE VERIFIED' : 'MOCK/NOT VERIFIED'} • Account: ${b.accountNumberMasked || 'N/A'} (${b.accountName || '-'}) • Số dư: ${b.balance ? fmtVnd(b.balance) : 'N/A'} • ${b.message || ''}`;
    } catch (_) {
      out.textContent = 'Không thể truy vấn số dư PayOS Payout.';
    }
  }

  function openSingleConfirmModal(r) {
    const cashVal = Math.round(Number(r.approvedCashTotal > 0 ? r.approvedCashTotal : r.requestedCashTotal) || 0);
    pendingConfirmAction = { type: 'SINGLE', refundRequestId: r.id, ids: [r.id], expectedTotalVnd: cashVal };
    const initialRow = buildPreviewRowFromDetail(r);
    renderConfirmModalContent(
      `Xác nhận duyệt chi đơn lẻ: ${r.requestCode}`,
      [initialRow],
      [],
      { livePayoutEnabled: Boolean(r.livePayoutEnabled), payoutMode: r.payoutMode || 'MOCK', blockedCount: initialRow.feeBlocked ? 1 : 0 }
    );
    if (roleScope === 'admin' && r.id) {
      fetch(`/api/admin/refunds/${r.id}/payout/preview`, { credentials: 'same-origin' })
        .then(res => (res && res.ok ? res.json() : null))
        .then(preview => {
          if (!preview || !pendingConfirmAction || pendingConfirmAction.type !== 'SINGLE' || pendingConfirmAction.refundRequestId !== r.id) return;
          if (!Array.isArray(preview.eligibleItems) && !Array.isArray(preview.ineligibleItems)) return;
          const allItems = [...(preview.eligibleItems || []), ...(preview.ineligibleItems || [])];
          if (!allItems.length) return;
          renderConfirmModalContent(
            `Xác nhận duyệt chi đơn lẻ: ${r.requestCode}`,
            preview.eligibleItems && preview.eligibleItems.length ? preview.eligibleItems : allItems,
            preview.ineligibleItems || [],
            preview
          );
        })
        .catch(() => {});
    }
  }

  function buildPreviewRowFromDetail(r) {
    const amt = Math.round(Number(r.approvedCashTotal > 0 ? r.approvedCashTotal : r.requestedCashTotal) || 0);
    const feeCls = r.feeAssessmentStatus || 'SENDER_PAYS';
    const isBlocked = r.workflowStatus === 'PAYOUT_BLOCKED' || r.payoutStatus === 'PAYOUT_BLOCKED' || Boolean(r.payoutBlockedReason)
      || ['RECIPIENT_DEDUCTED', 'UNKNOWN', 'UNVERIFIED'].includes(String(feeCls).toUpperCase());
    return {
      refundRequestId: r.id,
      requestCode: r.requestCode,
      bookingCode: r.bookingCode,
      cinemaName: r.cinemaName,
      customerName: r.customerName || r.accountHolderName || r.purchaseEmail,
      accountHolderName: r.accountHolderName,
      toBankName: r.toBankName,
      toBin: r.toBin,
      toAccountNumberMasked: r.toAccountNumberMasked,
      transferDescription: r.transferDescription,
      refundMethod: r.refundMethod || 'BANK_PAYOUT_FALLBACK',
      feeClassification: feeCls,
      estimatedFeeVnd: Number(r.feeAmountVnd || 0),
      netRecipientVnd: Number(r.netRecipientVnd || amt),
      guaranteedFullReceipt: r.guaranteedFullReceipt !== false && !isBlocked,
      feeBlocked: isBlocked,
      feeBlockReason: r.payoutBlockedReason || '',
      amountVnd: amt
    };
  }

  async function openBatchConfirmModal() {
    const ids = Array.from(selectedQueueItems.keys());
    if (!ids.length) return;
    const res = await fetch('/api/admin/refunds/payout/batch-preview', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
      credentials: 'same-origin',
      body: JSON.stringify({ refundRequestIds: ids })
    });
    if (!res.ok) return;
    const preview = await res.json();
    const eligibleIds = (preview.eligibleItems || []).map(i => i.refundRequestId);
    pendingConfirmAction = {
      type: 'BATCH',
      ids: eligibleIds,
      expectedTotalVnd: Number(preview.totalAmountVnd || 0)
    };
    renderConfirmModalContent(
      `Xác nhận duyệt chuyển tiền hàng loạt (${preview.eligibleCount} yêu cầu)`,
      preview.eligibleItems || [],
      preview.ineligibleItems || [],
      preview
    );
  }

  function renderConfirmModalContent(titleText, eligibleItems, ineligibleItems, previewMeta = null) {
    const modal = get('payoutConfirmModal');
    if (!modal) {
      // Fallback for headless environments without modal DOM
      if (typeof window !== 'undefined' && typeof window.confirm === 'function') {
        const sum = eligibleItems.reduce((a, b) => a + Number(b.amountVnd || 0), 0);
        if (window.confirm(`${titleText}\nTotal: ${fmtVnd(sum)}`)) executePendingConfirm();
      }
      return;
    }
    if (get('confirmModalTitle')) get('confirmModalTitle').textContent = titleText;
    const sum = eligibleItems.reduce((a, b) => a + Number(b.amountVnd || 0), 0);
    if (get('confirmSelectedCount')) get('confirmSelectedCount').textContent = String(eligibleItems.length);
    if (get('confirmSelectedTotal')) get('confirmSelectedTotal').textContent = fmtVnd(sum);

    const pfBanner = get('confirmPreflightBanner');
    if (pfBanner && previewMeta) {
      const liveFlag = Boolean(previewMeta.livePayoutEnabled);
      const modeStr = previewMeta.payoutMode || 'MOCK';
      const policyStr = previewMeta.configuredFeePolicy || 'SENDER_PAYS';
      pfBanner.textContent = `Chế độ: LIVE_PAYOUT_ENABLED=${liveFlag ? 'TRUE' : 'FALSE'} (${modeStr}) • Chính sách phí: ${policyStr} • Cam kết khách hàng nhận 100% số tiền hoàn được duyệt.`;
      pfBanner.className = liveFlag ? 'alert alert-danger py-2 small mb-2' : 'alert alert-info py-2 small mb-2';
    }

    const blockedItems = [...eligibleItems, ...ineligibleItems].filter(i => i && (i.feeBlocked || i.workflowStatus === 'PAYOUT_BLOCKED' || i.payoutStatus === 'PAYOUT_BLOCKED'));
    const feeBlockedBanner = get('confirmFeeBlockedBanner');
    if (feeBlockedBanner) {
      if (blockedItems.length) {
        feeBlockedBanner.classList.remove('d-none');
        feeBlockedBanner.textContent = `CHẶN CHI (PAYOUT_BLOCKED): ${blockedItems.map(i => `${i.requestCode} (${i.feeBlockReason || i.feeClassification || 'Phí chuyển khoản chưa xác minh 100% người nhận'})`).join(' | ')}`;
      } else {
        feeBlockedBanner.classList.add('d-none');
        feeBlockedBanner.textContent = '';
      }
    }

    const warn = get('confirmIneligibleWarning');
    if (warn) {
      if (ineligibleItems.length) {
        warn.classList.remove('d-none');
        warn.textContent = `${ineligibleItems.length} yêu cầu không còn đủ điều kiện sẽ bị bỏ qua: `
          + ineligibleItems.map(i => `${i.requestCode} (${(i.ineligibleReasons || [i.feeBlockReason || 'Ineligible']).join(', ')})`).join(' | ');
      } else {
        warn.classList.add('d-none');
        warn.textContent = '';
      }
    }

    const tbody = get('confirmItemTableBody');
    if (tbody) {
      tbody.replaceChildren();
      for (const it of eligibleItems) {
        const tr = el('tr');
        const feeTag = it.feeClassification ? ` • Phí: ${it.feeClassification} (${fmtVnd(it.estimatedFeeVnd || 0)})` : '';
        const methodTag = it.refundMethod ? ` [${it.refundMethod}]` : '';
        const descTag = it.transferDescription ? ` • ND: ${it.transferDescription}` : '';
        tr.append(
          el('td', it.requestCode, 'font-monospace fw-bold'),
          el('td', `${it.bookingCode || ''}${it.cinemaName ? ' (' + it.cinemaName + ')' : ''}`),
          el('td', `${it.customerName || '-'}${it.accountHolderName && it.accountHolderName !== it.customerName ? ' / ' + it.accountHolderName : ''}`),
          el('td', `${it.toBankName || ''} (${it.toBin || ''}) • ${it.toAccountNumberMasked || ''}${methodTag}${feeTag}${descTag}`),
          el('td', `${fmtVnd(it.amountVnd)}${it.netRecipientVnd ? ' (Nhận: ' + fmtVnd(it.netRecipientVnd) + ')' : ''}`, 'text-warning fw-bold')
        );
        tbody.append(tr);
      }
    }
    if (get('confirmModalError')) {
      get('confirmModalError').className = 'alert alert-danger py-2 small d-none';
      get('confirmModalError').textContent = '';
    }
    if (get('confirmNoteInput')) get('confirmNoteInput').value = '';
    if (get('confirmLimitOverrideApproved')) get('confirmLimitOverrideApproved').checked = false;
    if (get('confirmLimitOverrideReason')) get('confirmLimitOverrideReason').value = '';
    const hasBlockedEligible = eligibleItems.some(i => i.feeBlocked || i.workflowStatus === 'PAYOUT_BLOCKED');
    if (get('btnConfirmExecute')) get('btnConfirmExecute').disabled = eligibleItems.length === 0 || hasBlockedEligible;
    modal.classList.remove('d-none');
  }

  async function executePendingConfirm() {
    if (!pendingConfirmAction) return;
    const execBtn = get('btnConfirmExecute');
    const cancelBtn = get('btnConfirmCancel');
    const errBox = get('confirmModalError');
    const noteVal = get('confirmNoteInput')?.value?.trim() || '';
    const overrideChecked = Boolean(get('confirmLimitOverrideApproved')?.checked);
    const overrideReason = get('confirmLimitOverrideReason')?.value?.trim() || '';

    if (overrideChecked && !overrideReason) {
      if (errBox) {
        errBox.textContent = 'Vui lòng nhập lý do Admin vượt hạn mức (limitOverrideReason) khi bật Limit Override.';
        errBox.classList.remove('d-none');
      }
      return;
    }

    if (execBtn) execBtn.disabled = true;
    if (cancelBtn) cancelBtn.disabled = true;
    if (errBox) errBox.classList.add('d-none');

    try {
      if (pendingConfirmAction.type === 'SINGLE') {
        const id = pendingConfirmAction.refundRequestId;
        const singlePayload = { note: noteVal };
        if (overrideChecked) {
          singlePayload.limitOverrideApproved = true;
          singlePayload.limitOverrideReason = overrideReason;
        }
        const res = await fetch(`/api/admin/refunds/${id}/payout/approve`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
          credentials: 'same-origin',
          body: JSON.stringify(singlePayload)
        });
        const body = await res.json().catch(() => ({}));
        if (!res.ok) {
          if (errBox) {
            errBox.textContent = body.message || `Duyệt chi thất bại (HTTP ${res.status}).`;
            errBox.classList.remove('d-none');
          }
          return;
        }
        selectedQueueItems.delete(id);
        get('payoutConfirmModal')?.classList.add('d-none');
        await openRefundDetail(id);
      } else {
        const res = await fetch('/api/admin/refunds/payout/batch-approve', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
          credentials: 'same-origin',
          body: JSON.stringify({
            refundRequestIds: pendingConfirmAction.ids,
            expectedTotalVnd: pendingConfirmAction.expectedTotalVnd,
            strictTotalMatch: true,
            note: noteVal
          })
        });
        const batchRes = await res.json().catch(() => ({}));
        if (!res.ok) {
          if (errBox) {
            errBox.textContent = batchRes.message || `Duyệt hàng loạt thất bại (HTTP ${res.status}).`;
            errBox.classList.remove('d-none');
          }
          return;
        }
        for (const id of pendingConfirmAction.ids) selectedQueueItems.delete(id);
        get('payoutConfirmModal')?.classList.add('d-none');
        renderBatchResultPanel(batchRes);
      }
      await loadPayoutQueue();
      await loadRefunds();
      await loadPayoutTransactions();
      await loadLimitsSummary();
    } catch (err) {
      if (errBox) {
        errBox.textContent = err?.message || 'Lỗi kết nối máy chủ khi duyệt chi.';
        errBox.classList.remove('d-none');
      }
    } finally {
      if (execBtn) execBtn.disabled = false;
      if (cancelBtn) cancelBtn.disabled = false;
      pendingConfirmAction = null;
      updateBatchActionBar();
    }
  }

  function renderBatchResultPanel(batchRes) {
    const panel = get('batchPayoutResultPanel');
    const summary = get('batchResultSummary');
    const tbody = get('batchResultTableBody');
    if (!panel || !summary || !tbody) return;
    panel.classList.remove('d-none');
    summary.replaceChildren(
      el('div', `Batch ${batchRes.batchCode} • Trạng thái chung: ${batchRes.overallStatus} • Chế độ: ${batchRes.payoutMode || 'MOCK'} (LIVE=${batchRes.livePayoutEnabled ? 'TRUE' : 'FALSE'})`, 'fw-bold text-white mb-1'),
      el('div', `Đủ ĐK: ${batchRes.eligibleCount}/${batchRes.requestedCount} | Thành công: ${batchRes.succeededCount} | Đang xử lý: ${batchRes.processingCount} | Chưa xác định (UNKNOWN): ${batchRes.unknownOutcomeCount} | Chặn phí (PAYOUT_BLOCKED): ${batchRes.blockedCount ?? 0} | Thất bại: ${batchRes.failedCount} | Bỏ qua: ${batchRes.skippedCount} • Đã chi thành công: ${fmtVnd(batchRes.succeededTotalAmount)}`, 'small text-info')
    );
    tbody.replaceChildren();
    for (const r of batchRes.results || []) {
      const stage = formatPayoutStage(r.resultStatus, r.workflowStatus || r.payoutStatus);
      const tr = el('tr');
      const badgeTd = el('td');
      badgeTd.append(el('span', `${stage.label} (${r.resultStatus})`, `badge ${stage.cls}`));
      const feeNote = r.feeClassification ? ` [${r.feeClassification}${r.netRecipientVnd ? ' • Nhận ' + fmtVnd(r.netRecipientVnd) : ''}]` : '';
      tr.append(
        el('td', r.requestCode || `#${r.refundRequestId}`, 'font-monospace fw-bold'),
        el('td', r.bookingCode || '-'),
        el('td', `${r.customerName || '-'} • ${r.recipientBank || ''} ${r.maskedAccount || ''}`),
        el('td', `${fmtVnd(r.amount)}${feeNote}`, 'text-warning'),
        badgeTd,
        el('td', `${r.referenceId || '-'} / ${r.payosPayoutId || '-'}`),
        el('td', r.message || '', 'small')
      );
      tbody.append(tr);
    }
  }

  function openRejectModal(r) {
    pendingRejectRequestId = r.id;
    const modal = get('payoutRejectModal');
    if (!modal) return;
    if (get('rejectRequestSummary')) {
      const cashVal = r.approvedCashTotal > 0 ? r.approvedCashTotal : r.requestedCashTotal;
      get('rejectRequestSummary').textContent = `${r.requestCode} • ${r.bookingCode} • ${fmtVnd(cashVal)} • ${r.toBankName || ''} ${r.toAccountNumberMasked || ''}`;
    }
    if (get('rejectReasonInput')) get('rejectReasonInput').value = '';
    if (get('rejectModalError')) {
      get('rejectModalError').className = 'alert alert-danger py-2 small d-none';
      get('rejectModalError').textContent = '';
    }
    modal.classList.remove('d-none');
  }

  async function executeRejectPayout() {
    if (!pendingRejectRequestId) return;
    const reason = get('rejectReasonInput')?.value?.trim() || '';
    const errBox = get('rejectModalError');
    if (!reason) {
      if (errBox) {
        errBox.textContent = 'Vui lòng nhập lý do từ chối chuyển tiền.';
        errBox.classList.remove('d-none');
      }
      return;
    }
    const execBtn = get('btnRejectExecute');
    if (execBtn) execBtn.disabled = true;
    try {
      const res = await fetch(`/api/admin/refunds/${pendingRejectRequestId}/payout/reject`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
        credentials: 'same-origin',
        body: JSON.stringify({ reason })
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        if (errBox) {
          errBox.textContent = body.message || `Từ chối thất bại (HTTP ${res.status}).`;
          errBox.classList.remove('d-none');
        }
        return;
      }
      selectedQueueItems.delete(pendingRejectRequestId);
      get('payoutRejectModal')?.classList.add('d-none');
      const rejectedId = pendingRejectRequestId;
      pendingRejectRequestId = null;
      await openRefundDetail(rejectedId);
      await loadPayoutQueue();
      await loadRefunds();
    } finally {
      if (execBtn) execBtn.disabled = false;
    }
  }

  async function holdForBankReverify(refundId) {
    let reason = '';
    if (typeof window !== 'undefined' && typeof window.prompt === 'function') {
      const entered = window.prompt('Nhập lý do tạm dừng & yêu cầu xác minh lại tài khoản ngân hàng (bắt buộc):');
      if (entered === null) return;
      reason = entered.trim();
    }
    if (!reason) {
      if (typeof window !== 'undefined' && typeof window.alert === 'function') {
        window.alert('Vui lòng nhập lý do tạm dừng & yêu cầu xác minh lại TK.');
      }
      return;
    }
    try {
      const res = await fetch(`/api/admin/refunds/${refundId}/payout/hold-for-reverify`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
        credentials: 'same-origin',
        body: JSON.stringify({ reason })
      });
      const body = await res.json().catch(() => ({}));
      if (!res.ok) {
        if (typeof window !== 'undefined' && typeof window.alert === 'function') {
          window.alert(body.message || `Không thể tạm dừng yêu cầu (HTTP ${res.status}).`);
        }
        return;
      }
      selectedQueueItems.delete(refundId);
      await loadPayoutQueue();
      await loadRefunds();
      const detailBox = get('opsRefundDetailPanel');
      if (detailBox && !detailBox.classList.contains('d-none')) {
        await openRefundDetail(refundId);
      }
    } catch (_) {
      if (typeof window !== 'undefined' && typeof window.alert === 'function') {
        window.alert('Lỗi kết nối khi tạm dừng yêu cầu hoàn tiền.');
      }
    }
  }

  async function openRefundDetail(id) {
    const res = await fetch(`${apiBase}/refunds/${id}`, { credentials: 'same-origin' });
    if (!res.ok) return;
    const r = await res.json();
    const box = get('opsRefundDetailPanel');
    if (!box) return;
    box.classList.remove('d-none');
    box.replaceChildren();

    const stage = formatPayoutStage(r.payoutDisplayStage || r.workflowStatus, r.requestStatus);

    const header = el('div', '', 'd-flex justify-content-between align-items-start flex-wrap gap-2 mb-3 pb-2 border-bottom border-secondary border-opacity-25');
    const titleBox = el('div');
    const h5 = el('h5', `${r.requestCode} — ${r.requestStatus}${r.workflowStatus ? ' (' + r.workflowStatus + ')' : ''} `, 'mb-1 text-white');
    h5.append(el('span', stage.label, `badge ${stage.cls} ms-2`));
    const savedIndicator = r.savedBankAccountId ? `TK đã lưu #${r.savedBankAccountId}` : 'Nhập thủ công';
    const bankNoteLine = r.bankNote ? ` • Ghi chú NH: ${r.bankNote}` : '';
    const snapshotLine = r.recipientSnapshotHash ? ` • Snapshot Hash: ${String(r.recipientSnapshotHash).slice(0, 12)}...` : '';
    titleBox.append(
      h5,
      el('div', `Đơn hàng: ${r.bookingCode} (${r.orderType}) • Rạp: ${r.cinemaName} • Khách: ${r.customerName || 'N/A'} (${r.purchaseEmail}${r.customerPhone ? ' / ' + r.customerPhone : ''})`, 'small text-secondary'),
      el('div', `Xác minh nghiệp vụ: ${r.reviewedByName || 'Chưa xác minh'} (${r.reviewedByRole || '-'}) ${r.reviewedAt ? 'lúc ' + String(r.reviewedAt).slice(0, 19).replace('T', ' ') : ''}`, 'small text-light mt-1'),
      el('div', `Phương thức hoàn: ${r.refundMethod || 'BANK_PAYOUT_FALLBACK'}${r.originalPaymentRef ? ' (Ref gốc: ' + r.originalPaymentRef + ')' : ''} • Ngân hàng nhận: ${r.toBankName || ''} (${r.toBin || ''}) • TK: ${r.toAccountNumberMasked || 'N/A'} • Chủ TK: ${r.accountHolderName || '-'} • Nguồn: ${savedIndicator}${bankNoteLine}${snapshotLine}`, 'small text-info mt-1'),
      el('div', `Payout: ${r.payoutStatus}${r.latestPayout?.referenceId ? ' (' + r.latestPayout.referenceId + ')' : ''} | Voucher: ${r.voucherStatus}${r.recoveryVoucher?.newVoucherCode ? ' (' + r.recoveryVoucher.newVoucherCode + ' - ' + fmtVnd(r.recoveryVoucher.recoveryAmount) + ')' : ''}${r.transferDescription ? ' • Nội dung CK: ' + r.transferDescription : ''}`, 'small text-warning mt-1')
    );

    if (r.refundDeadlineAt || r.deadlineBasis || r.deadlineExceeded) {
      const dlText = `Thời hạn hoàn tiền (Asia/Ho_Chi_Minh): ${r.refundDeadlineAt ? String(r.refundDeadlineAt).slice(0, 19).replace('T', ' ') : 'N/A'} • Cơ sở tính: ${r.deadlineBasis || 'SHOWTIME_END_PLUS_72H'} • Trạng thái hạn: ${r.deadlineExceeded ? 'QUÁ HẠN (DEADLINE EXCEEDED)' : 'TRONG HẠN'}`
        + (r.deadlineExceptionReason ? ` • Ngoại lệ Admin: ${r.deadlineExceptionReason}` : '');
      titleBox.append(el('div', dlText, `small mt-1 ${r.deadlineExceeded && !r.deadlineExceptionReason ? 'text-danger fw-semibold' : 'text-secondary'}`));
    }

    if (r.feeAssessmentStatus || r.netRecipientVnd || r.feeAmountVnd !== undefined) {
      const feeText = `Đánh giá phí chuyển khoản: ${r.feeAssessmentStatus || 'SENDER_PAYS'} • Phí ước tính: ${fmtVnd(r.feeAmountVnd || 0)} • Khách thực nhận: ${fmtVnd(r.netRecipientVnd ?? (r.approvedCashTotal > 0 ? r.approvedCashTotal : r.requestedCashTotal))} • Bảo đảm nhận 100%: ${r.guaranteedFullReceipt === false ? 'KHÔNG ĐẠT (CHẶN CHI)' : 'ĐẠT (100%)'}`;
      titleBox.append(el('div', feeText, `small mt-1 ${r.guaranteedFullReceipt === false ? 'text-danger fw-bold' : 'text-success'}`));
    }

    if (r.limitOverrideApplied || r.limitOverrideReason) {
      titleBox.append(
        el('div', `Admin Limit Override đã áp dụng: ${r.limitOverrideReason || 'Approved by Admin'} ${r.limitOverrideAt ? 'lúc ' + String(r.limitOverrideAt).slice(0, 19).replace('T', ' ') : ''}`, 'small text-warning mt-1')
      );
    }

    if (r.originalPayment) {
      titleBox.append(
        el('div', `Giao dịch thanh toán gốc: OrderCode ${r.originalPayment.orderCode || '-'} • ${fmtVnd(r.originalPayment.amount)} • ${r.originalPayment.paymentMethod || ''} • ${r.originalPayment.paymentStatus}`, 'small text-success mt-1')
      );
    }
    if (r.payoutDecidedByName) {
      titleBox.append(
        el('div', `Quyết định duyệt chi (Admin): ${r.payoutDecidedByName} ${r.payoutDecidedAt ? 'lúc ' + String(r.payoutDecidedAt).slice(0, 19).replace('T', ' ') : ''} ${r.payoutDecisionNote ? '— ' + r.payoutDecisionNote : ''}`, 'small text-secondary mt-1')
      );
    }
    if (r.payoutEligibilityErrors && r.payoutEligibilityErrors.length && (r.requestStatus === 'AWAITING_PAYOUT_APPROVAL' || r.workflowStatus === 'PAYOUT_BLOCKED')) {
      titleBox.append(el('div', `Cảnh báo điều kiện chi: ${r.payoutEligibilityErrors.join('; ')}`, 'small text-danger mt-1'));
    }

    const retryStatusOut = el('div', '', 'd-none');
    const confirmPayoutAction = (verb, cashAmount) => {
      if (typeof window !== 'undefined' && typeof window.confirm === 'function') {
        const msg = `${verb} for ${r.requestCode}\nCash Amount: ${fmtVnd(cashAmount)}\nBank: ${r.toBankName || ''} (${r.toBin || ''})\nAccount: ${r.toAccountNumberMasked || 'N/A'}`;
        return window.confirm(msg);
      }
      return true;
    };

    const actionGroup = el('div', '', 'd-flex gap-2 flex-wrap');
    let btnApprovePayout = null;
    let btnRejectPayout = null;
    let btnHoldReverify = null;
    let btnRetryPayout = null;
    let btnRetryVoucher = null;
    let btnAssessFee = null;
    const setActionButtonsDisabled = disabled => {
      if (btnApprovePayout) btnApprovePayout.disabled = disabled;
      if (btnRejectPayout) btnRejectPayout.disabled = disabled;
      if (btnHoldReverify) btnHoldReverify.disabled = disabled;
      if (btnRetryPayout) btnRetryPayout.disabled = disabled;
      if (btnRetryVoucher) btnRetryVoucher.disabled = disabled;
      if (btnAssessFee) btnAssessFee.disabled = disabled;
    };

    if (roleScope === 'admin' && (r.requestStatus === 'AWAITING_PAYOUT_APPROVAL' || r.workflowStatus === 'APPROVED' || r.workflowStatus === 'PAYOUT_PENDING' || r.workflowStatus === 'PAYOUT_BLOCKED')) {
      btnApprovePayout = el('button', 'Đồng ý chuyển tiền (PayOS)', 'btn btn-sm btn-success fw-semibold');
      btnApprovePayout.disabled = !r.payoutEligible || r.workflowStatus === 'PAYOUT_BLOCKED' || r.payoutStatus === 'PAYOUT_BLOCKED';
      btnApprovePayout.onclick = () => openSingleConfirmModal(r);
      btnRejectPayout = el('button', 'Từ chối chuyển tiền', 'btn btn-sm btn-danger');
      btnRejectPayout.onclick = () => openRejectModal(r);
      btnHoldReverify = el('button', 'Tạm dừng & Yêu cầu xác minh lại TK', 'btn btn-sm btn-outline-warning');
      btnHoldReverify.onclick = () => holdForBankReverify(r.id);
      btnAssessFee = el('button', 'Thẩm định phí chuyển khoản (Assess Fee)', 'btn btn-sm btn-outline-info');
      btnAssessFee.onclick = async () => {
        setActionButtonsDisabled(true);
        retryStatusOut.className = 'd-none';
        try {
          const resp = await fetch(`/api/admin/refunds/${r.id}/payout/assess-fee`, {
            method: 'POST', headers: { ...getCsrfHeaders() }, credentials: 'same-origin'
          });
          const feeRes = await resp.json().catch(() => ({}));
          if (!resp.ok) {
            const errCode = feeRes.errorCode || feeRes.code;
            retryStatusOut.className = 'alert alert-danger mt-2 small';
            retryStatusOut.textContent = (errCode ? `[${errCode}] ` : '') + (feeRes.message || `Thẩm định phí thất bại (HTTP ${resp.status}).`);
            return;
          }
          const cls = feeRes.classification || feeRes.feeClassification || 'UNKNOWN';
          const reason = feeRes.reason || feeRes.blockReason || '';
          const alertMsg = `Kết quả thẩm định phí: ${cls} • Phí: ${fmtVnd(feeRes.estimatedFeeVnd || 0)} • Khách nhận: ${fmtVnd(feeRes.netRecipientVnd || 0)} • ${feeRes.blocked ? 'CHẶN CHI: ' + reason : 'Đủ điều kiện nhận 100%'}`;
          const alertCls = feeRes.blocked ? 'alert alert-danger mt-2 small' : 'alert alert-success mt-2 small';
          await openRefundDetail(r.id);
          await loadPayoutQueue();
          const refreshedBox = get('opsRefundDetailPanel');
          if (refreshedBox) {
            refreshedBox.append(el('div', alertMsg, alertCls));
          } else {
            retryStatusOut.className = alertCls;
            retryStatusOut.textContent = alertMsg;
          }
        } finally {
          setActionButtonsDisabled(false);
        }
      };
      actionGroup.append(btnApprovePayout, btnRejectPayout, btnHoldReverify, btnAssessFee);
    }

    if (roleScope === 'admin' && (r.payoutStatus === 'FAILED' || r.payoutStatus === 'PROCESSING' || r.payoutStatus === 'PENDING' || r.payoutStatus === 'UNKNOWN' || r.requestStatus === 'PAYOUT_APPROVED')) {
      const isPendingOrProcessing = r.payoutStatus === 'PROCESSING' || r.payoutStatus === 'PENDING' || r.payoutStatus === 'UNKNOWN';
      btnRetryPayout = el('button', isPendingOrProcessing ? 'Đối soát PayOS' : 'Gửi lại lệnh PayOS', 'btn btn-sm btn-warning');
      btnRetryPayout.onclick = async () => {
        const cashVal = Number(r.approvedCashTotal > 0 ? r.approvedCashTotal : r.requestedCashTotal) || 0;
        if (!confirmPayoutAction(isPendingOrProcessing ? 'Reconcile PayOS payout' : 'Retry cash payout', cashVal)) {
          return;
        }
        setActionButtonsDisabled(true);
        retryStatusOut.className = 'd-none';
        try {
          const resp = await fetch(`/api/admin/refunds/${r.id}/retry-payout`, {
            method: 'POST', headers: { ...getCsrfHeaders() }, credentials: 'same-origin'
          });
          if (!resp.ok) {
            const errBody = await resp.json().catch(() => ({}));
            retryStatusOut.className = 'alert alert-danger mt-2 small';
            retryStatusOut.textContent = errBody.message || `Payout retry failed (HTTP ${resp.status}).`;
            return;
          }
          await openRefundDetail(r.id);
          await loadPayoutQueue();
          await loadRefunds();
          await loadPayoutTransactions();
          await loadLimitsSummary();
        } catch (err) {
          retryStatusOut.className = 'alert alert-danger mt-2 small';
          retryStatusOut.textContent = err?.message || 'Network error while retrying payout.';
        } finally {
          setActionButtonsDisabled(false);
        }
      };
      actionGroup.append(btnRetryPayout);

      if (r.payoutStatus === 'FAILED' && r.requestStatus === 'PAYOUT_FAILED') {
        btnRejectPayout = el('button', 'Từ chối chuyển tiền', 'btn btn-sm btn-outline-danger');
        btnRejectPayout.onclick = () => openRejectModal(r);
        btnHoldReverify = el('button', 'Tạm dừng & Yêu cầu xác minh lại TK', 'btn btn-sm btn-outline-warning');
        btnHoldReverify.onclick = () => holdForBankReverify(r.id);
        actionGroup.append(btnRejectPayout, btnHoldReverify);
      }
    }

    if (r.voucherStatus === 'FAILED') {
      btnRetryVoucher = el('button', 'Retry Voucher Only', 'btn btn-sm btn-info');
      btnRetryVoucher.onclick = async () => {
        setActionButtonsDisabled(true);
        retryStatusOut.className = 'd-none';
        try {
          const resp = await fetch(`${apiBase}/refunds/${r.id}/retry-voucher`, {
            method: 'POST', headers: { ...getCsrfHeaders() }, credentials: 'same-origin'
          });
          if (!resp.ok) {
            const errBody = await resp.json().catch(() => ({}));
            retryStatusOut.className = 'alert alert-danger mt-2 small';
            retryStatusOut.textContent = errBody.message || `Voucher retry failed (HTTP ${resp.status}).`;
            return;
          }
          await openRefundDetail(r.id);
          await loadRefunds();
        } catch (err) {
          retryStatusOut.className = 'alert alert-danger mt-2 small';
          retryStatusOut.textContent = err?.message || 'Network error while retrying voucher.';
        } finally {
          setActionButtonsDisabled(false);
        }
      };
      actionGroup.append(btnRetryVoucher);
    }

    header.append(titleBox, actionGroup);
    box.append(header, retryStatusOut);

    if (r.workflowStatus === 'PAYOUT_BLOCKED' || r.payoutStatus === 'PAYOUT_BLOCKED' || r.payoutBlockedReason) {
      box.append(el('div',
        `CHẶN CHI (PAYOUT_BLOCKED): ${r.payoutBlockedReason || 'Phí chuyển khoản của kênh chi chưa được xác minh hoặc trừ phí người nhận (Customer 100% Refund Guarantee).'}`,
        'alert alert-danger py-2 small fw-semibold mb-2'));
    }

    box.append(el('div', `Lý do (${r.reasonCategory}): ${r.description}`, 'small text-light mb-2'));
    if (r.evidenceUrl) {
      const link = doc.createElement('a');
      link.href = normalizeAuthenticatedEvidenceUrl(r.evidenceUrl);
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.className = 'small text-info d-inline-block mb-3';
      link.textContent = `Xem minh chứng đã xác thực (${r.evidenceUrl})`;
      box.append(link);
    }

    if (r.relatedIncidents && r.relatedIncidents.length) {
      const incBanner = el('div', `Sự cố đã xác minh liên quan: ${r.relatedIncidents.map(i => i.incidentCode + ' (' + i.incidentGroup + ' - Verified by ' + (i.managerVerifiedByName || 'Admin') + ')').join(', ')}`, 'alert alert-info py-2 small');
      box.append(incBanner);
    }

    const itemTable = el('div', '', 'mb-3');
    const decisionInputs = [];
    const canReview = ['REQUESTED', 'UNDER_REVIEW', 'PENDING_REVIEW', 'UNDER_VERIFICATION', 'MORE_INFO_REQUIRED', 'VERIFIED'].includes(r.requestStatus)
      || ['REQUESTED', 'UNDER_REVIEW'].includes(r.workflowStatus);

    for (const item of r.items || []) {
      const row = el('div', '', 'p-2 mb-2 rounded border border-secondary border-opacity-25');
      row.append(
        el('div', `${item.itemLabel} [${item.itemKind}] — Req Qty: ${item.requestedQuantity} — Status: ${item.itemStatus}`, 'fw-semibold small text-white'),
        el('div', `Gross Unit: ${fmtVnd(item.grossUnitPrice ?? (Number(item.netPaidAmount || 0) + Number(item.discountAllocatedAmount || 0)))} | Voucher Allocated (Largest Remainder): ${fmtVnd(item.discountAllocatedAmount)} | Net Paid (Cash Cap): ${fmtVnd(item.netPaidAmount)} | Approved Cash: ${fmtVnd(item.approvedCashAmount)} | Approved Voucher: ${fmtVnd(item.approvedVoucherAmount)}`, 'small text-secondary')
      );
      if (canReview) {
        const ctrlRow = el('div', '', 'row g-2 mt-1');
        const sel = doc.createElement('select');
        sel.className = 'form-select form-select-sm form-select-dark col-md-3';
        sel.style.maxWidth = '150px';
        for (const optVal of ['APPROVED', 'REJECTED']) {
          const o = el('option', optVal);
          o.value = optVal;
          sel.append(o);
        }
        const qtyInp = doc.createElement('input');
        qtyInp.type = 'number';
        qtyInp.min = '1';
        qtyInp.max = String(item.requestedQuantity);
        qtyInp.value = String(item.requestedQuantity);
        qtyInp.className = 'form-control form-control-sm form-control-dark';
        qtyInp.style.maxWidth = '85px';
        const reasonInp = doc.createElement('input');
        reasonInp.type = 'text';
        reasonInp.placeholder = 'Reason (mandatory if REJECTED)';
        reasonInp.className = 'form-control form-control-sm form-control-dark col';

        ctrlRow.append(sel, qtyInp, reasonInp);
        row.append(ctrlRow);
        decisionInputs.push({ itemId: item.id, sel, qtyInp, reasonInp });
      }
      itemTable.append(row);
    }
    box.append(itemTable);

    if (canReview) {
      let adminLimitOverrideCb = null;
      let adminLimitOverrideReasonInp = null;
      let adminDeadlineExcCb = null;
      let adminDeadlineExcReasonInp = null;

      if (roleScope === 'admin') {
        const adminOverridePanel = el('div', '', 'p-2 mb-2 rounded border border-warning small');
        const limRow = el('div', '', 'row g-2 align-items-center mb-2');
        const limCheckCol = el('div', '', 'col-md-5');
        adminLimitOverrideCb = doc.createElement('input');
        adminLimitOverrideCb.type = 'checkbox';
        adminLimitOverrideCb.className = 'form-check-input me-2';
        adminLimitOverrideCb.id = 'adminLimitOverrideApproved';
        const limLabel = el('label', 'Admin Limit Override (Cho phép vượt hạn mức 500k/2M/5M/10M)', 'form-check-label text-warning fw-semibold');
        limCheckCol.append(adminLimitOverrideCb, limLabel);
        const limReasonCol = el('div', '', 'col-md-7');
        adminLimitOverrideReasonInp = doc.createElement('input');
        adminLimitOverrideReasonInp.type = 'text';
        adminLimitOverrideReasonInp.id = 'adminLimitOverrideReason';
        adminLimitOverrideReasonInp.className = 'form-control form-control-sm form-control-dark';
        adminLimitOverrideReasonInp.placeholder = 'Lý do Admin vượt hạn mức (bắt buộc nếu chọn Limit Override)...';
        limReasonCol.append(adminLimitOverrideReasonInp);
        limRow.append(limCheckCol, limReasonCol);

        const dlRow = el('div', '', 'row g-2 align-items-center');
        const dlCheckCol = el('div', '', 'col-md-5');
        adminDeadlineExcCb = doc.createElement('input');
        adminDeadlineExcCb.type = 'checkbox';
        adminDeadlineExcCb.className = 'form-check-input me-2';
        adminDeadlineExcCb.id = 'adminDeadlineExceptionApproved';
        const dlLabel = el('label', 'Admin Deadline Exception (Duyệt ngoại lệ quá hạn 72h)', 'form-check-label text-info fw-semibold');
        dlCheckCol.append(adminDeadlineExcCb, dlLabel);
        const dlReasonCol = el('div', '', 'col-md-7');
        adminDeadlineExcReasonInp = doc.createElement('input');
        adminDeadlineExcReasonInp.type = 'text';
        adminDeadlineExcReasonInp.id = 'adminDeadlineExceptionReason';
        adminDeadlineExcReasonInp.className = 'form-control form-control-sm form-control-dark';
        adminDeadlineExcReasonInp.placeholder = 'Lý do Admin duyệt ngoại lệ quá hạn (bắt buộc nếu chọn Deadline Exception)...';
        dlReasonCol.append(adminDeadlineExcReasonInp);
        dlRow.append(dlCheckCol, dlReasonCol);

        adminOverridePanel.append(limRow, dlRow);
        box.append(adminOverridePanel);
      }

      const noteInp = doc.createElement('textarea');
      noteInp.className = 'form-control form-control-dark mb-2';
      noteInp.rows = 2;
      noteInp.placeholder = 'Ghi chú xác minh / phê duyệt nghiệp vụ (bắt buộc cho Yêu cầu bổ sung, Chuyển Admin, Không đủ ĐK, Hủy)...';

      const statusOut = el('div', '', 'd-none');
      const btns = el('div', '', 'd-flex gap-2 flex-wrap');

      const submitDecisionBtn = el('button', 'Phê duyệt nghiệp vụ / Duyệt hoàn tiền (Chuyển Admin chi)', 'btn btn-sm btn-success');
      const startVerBtn = el('button', 'Bắt đầu xác minh', 'btn btn-sm btn-outline-info');
      const markVerBtn = el('button', 'Đánh dấu Đã xác minh', 'btn btn-sm btn-info');
      const moreInfoBtn = el('button', 'Yêu cầu bổ sung thông tin', 'btn btn-sm btn-warning');
      const escalateBtn = roleScope === 'manager' ? el('button', 'Chuyển Admin (Escalate)', 'btn btn-sm btn-outline-info') : null;
      const ineligibleBtn = el('button', 'Không đủ điều kiện', 'btn btn-sm btn-outline-danger');
      const cancelBtn = el('button', 'Hủy yêu cầu', 'btn btn-sm btn-outline-secondary');

      const allReviewBtns = [submitDecisionBtn, startVerBtn, markVerBtn, moreInfoBtn, escalateBtn, ineligibleBtn, cancelBtn].filter(Boolean);
      const setReviewButtonsDisabled = disabled => {
        for (const b of allReviewBtns) b.disabled = disabled;
      };

      const sendReviewAction = async (payload) => {
        if (roleScope === 'admin') {
          if (adminLimitOverrideCb?.checked) {
            payload.limitOverrideApproved = true;
            payload.limitOverrideReason = adminLimitOverrideReasonInp?.value?.trim() || '';
          }
          if (adminDeadlineExcCb?.checked) {
            payload.deadlineExceptionApproved = true;
            payload.deadlineExceptionReason = adminDeadlineExcReasonInp?.value?.trim() || '';
          }
        }
        setReviewButtonsDisabled(true);
        statusOut.className = 'd-none';
        try {
          const resp = await fetch(`${apiBase}/refunds/${r.id}/review`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
            credentials: 'same-origin',
            body: JSON.stringify(payload)
          });
          const d = await resp.json().catch(() => ({}));
          if (!resp.ok) {
            statusOut.className = 'alert alert-danger mt-2 small';
            const errCode = d.errorCode || d.code;
            const codePrefix = errCode ? `[${errCode}] ` : '';
            statusOut.textContent = codePrefix + (d.message || 'Thao tác thất bại');
            return;
          }
          await openRefundDetail(r.id);
          await loadRefunds();
          await loadPayoutQueue();
          await loadLimitsSummary();
        } catch (err) {
          statusOut.className = 'alert alert-danger mt-2 small';
          statusOut.textContent = err?.message || 'Lỗi mạng khi xử lý yêu cầu.';
        } finally {
          setReviewButtonsDisabled(false);
        }
      };

      submitDecisionBtn.onclick = async () => {
        const itemDecisions = decisionInputs.map(d => ({
          itemId: d.itemId,
          decision: d.sel.value,
          approvedQuantity: Number(d.qtyInp.value || 1),
          reason: d.reasonInp.value.trim()
        }));
        await sendReviewAction({
          action: 'DECIDE_ITEMS',
          note: noteInp.value.trim(),
          itemDecisions
        });
      };

      startVerBtn.onclick = () => sendReviewAction({ action: 'START_VERIFICATION', note: noteInp.value.trim() });
      markVerBtn.onclick = () => sendReviewAction({ action: 'MARK_VERIFIED', note: noteInp.value.trim() });
      moreInfoBtn.onclick = () => sendReviewAction({ action: 'REQUEST_MORE_INFO', note: noteInp.value.trim() });
      if (escalateBtn) escalateBtn.onclick = () => sendReviewAction({ action: 'ESCALATE_TO_ADMIN', note: noteInp.value.trim() });
      ineligibleBtn.onclick = () => sendReviewAction({ action: 'MARK_INELIGIBLE', note: noteInp.value.trim() });
      cancelBtn.onclick = () => sendReviewAction({ action: 'CANCEL', note: noteInp.value.trim() });

      btns.append(submitDecisionBtn, startVerBtn, markVerBtn, moreInfoBtn);
      if (escalateBtn) btns.append(escalateBtn);
      btns.append(ineligibleBtn, cancelBtn);
      box.append(noteInp, btns, statusOut);
    }

    if (r.payoutTransactions && r.payoutTransactions.length) {
      box.append(el('h6', 'Lịch sử giao dịch PayOS Payout', 'text-white mt-3'));
      for (const tx of r.payoutTransactions) {
        const txStage = formatPayoutStage(tx.unknownOutcome ? 'UNKNOWN_OUTCOME' : tx.status, tx.status);
        box.append(el('div',
          `[Attempt #${tx.attemptCount}] ${tx.referenceId}${tx.batchCode ? ' (' + tx.batchCode + ')' : ''} • ${fmtVnd(tx.amount)} • ${txStage.label} (${tx.status}) • PayOS ID: ${tx.payosPayoutId || 'N/A'} • Idemp: ${tx.idempotencyKey || '-'}`,
          'small text-info py-1 border-bottom border-secondary border-opacity-25'));
      }
    }

    if (r.history && r.history.length) {
      box.append(el('h6', 'Lịch sử xử lý & Kiểm toán (Approval History)', 'text-white mt-3'));
      for (const h of r.history) {
        box.append(el('div',
          `[${h.createdAt ? String(h.createdAt).slice(0, 19).replace('T', ' ') : ''}] ${h.action} (${h.actorName || h.actorRole}) : ${h.previousStatus || '-'} -> ${h.newStatus}${h.note ? ' — ' + h.note : ''}`,
          'small text-secondary py-1'));
      }
    }

    if (r.communications && r.communications.length) {
      box.append(el('h6', 'Trao đổi với khách hàng', 'text-white mt-3'));
      for (const c of r.communications) {
        box.append(el('div', `[${c.senderType}] ${c.senderName || ''}: ${c.message}`, 'small text-secondary py-1'));
      }
    }
  }

  async function loadAppeals() {
    const tbody = get('opsAppealTableBody');
    if (!tbody) return;
    const params = new URLSearchParams();
    const cinemaId = get('opsCinemaFilter')?.value;
    const status = get('opsAppealStatusFilter')?.value;
    if (cinemaId && roleScope === 'admin') params.set('cinemaId', cinemaId);
    if (status) params.set('status', status);

    try {
      const qs = params.toString();
      const res = await fetch(`${apiBase}/refunds/appeals${qs ? '?' + qs : ''}`, { credentials: 'same-origin' });
      tbody.replaceChildren();
      if (!res.ok) return;
      const list = await res.json();
      const rows = Array.isArray(list) ? list : (list.content || []);
      if (!rows.length) {
        const tr = el('tr');
        const td = el('td', 'Không có kháng nghị hoàn tiền nào khớp bộ lọc.', 'text-secondary py-3 text-center');
        td.colSpan = 7;
        tr.append(td);
        tbody.append(tr);
        return;
      }

      for (const a of rows) {
        const tr = el('tr');
        const stBadge = formatPayoutStage(a.status, a.status);

        const reasonTd = el('td');
        reasonTd.append(el('div', a.appealReason || '-', 'small text-light'));
        if (a.evidenceUrl) {
          const evLink = doc.createElement('a');
          evLink.href = normalizeAuthenticatedEvidenceUrl(a.evidenceUrl);
          evLink.target = '_blank';
          evLink.rel = 'noopener noreferrer';
          evLink.className = 'small text-info';
          evLink.textContent = 'Minh chứng kháng nghị';
          reasonTd.append(evLink);
        }

        const statusTd = el('td');
        statusTd.append(
          el('span', `${stBadge.label} (${a.status})`, `badge ${stBadge.cls}`),
          el('div', `Refund: ${a.refundWorkflowStatus || a.refundRequestStatus || '-'}`, 'small text-secondary mt-1')
        );

        const revTd = el('td');
        revTd.append(
          el('div', a.reviewedByName ? `Reviewer: ${a.reviewedByName}` : 'Chưa xử lý', 'small text-light'),
          el('div', a.submittedAt ? String(a.submittedAt).slice(0, 16).replace('T', ' ') : '', 'small text-secondary')
        );

        const actTd = el('td');
        const actWrap = el('div', '', 'd-flex gap-1 flex-wrap');
        const inspectBtn = el('button', 'Chi tiết / Xử lý', 'btn btn-sm btn-outline-info');
        inspectBtn.onclick = () => openAppealDetail(a.id);
        actWrap.append(inspectBtn);
        if (a.refundRequestId) {
          const refBtn = el('button', 'Xem yêu cầu gốc', 'btn btn-sm btn-outline-light');
          refBtn.onclick = () => openRefundDetail(a.refundRequestId);
          actWrap.append(refBtn);
        }
        actTd.append(actWrap);

        tr.append(
          el('td', a.appealCode || `#${a.id}`, 'fw-bold font-monospace text-white'),
          el('td', `${a.refundRequestCode || '#' + a.refundRequestId} (${a.cinemaName || ''})`),
          el('td', a.purchaseEmail || '-'),
          reasonTd,
          statusTd,
          revTd,
          actTd
        );
        tbody.append(tr);
      }
    } catch (_) {}
  }

  async function openAppealDetail(appealId) {
    const box = get('opsAppealDetailPanel');
    if (!box) return;
    const res = await fetch(`${apiBase}/refunds/appeals/${appealId}`, { credentials: 'same-origin' });
    if (!res.ok) return;
    const a = await res.json();
    box.classList.remove('d-none');
    box.replaceChildren();

    const stBadge = formatPayoutStage(a.status, a.status);
    const title = el('h5', `Kháng nghị ${a.appealCode} (Yêu cầu ${a.refundRequestCode}) `, 'text-white mb-1');
    title.append(el('span', `${stBadge.label} (${a.status})`, `badge ${stBadge.cls} ms-2`));

    box.append(
      title,
      el('div', `Rạp: ${a.cinemaName || '-'} • Email khách: ${a.purchaseEmail || '-'} • Trạng thái yêu cầu gốc: ${a.refundWorkflowStatus || a.refundRequestStatus || '-'}`, 'small text-secondary mb-2'),
      el('div', `Lý do kháng nghị của khách: ${a.appealReason || '-'}`, 'small text-light mb-2')
    );

    if (a.evidenceUrl) {
      const evLink = doc.createElement('a');
      evLink.href = normalizeAuthenticatedEvidenceUrl(a.evidenceUrl);
      evLink.target = '_blank';
      evLink.rel = 'noopener noreferrer';
      evLink.className = 'small text-info d-inline-block mb-2';
      evLink.textContent = `Xem minh chứng kháng nghị (${a.evidenceUrl})`;
      box.append(evLink);
    }

    if (a.decisionNote || a.reviewedByName) {
      box.append(el('div', `Kết luận thẩm định: ${a.reviewedByName || '-'} ${a.reviewedAt ? 'lúc ' + String(a.reviewedAt).slice(0, 19).replace('T', ' ') : ''} — ${a.decisionNote || ''}`, 'small text-warning mb-2'));
    }

    const msgOut = el('div', '', 'd-none');
    if (a.status === 'APPEAL_SUBMITTED' || a.status === 'APPEAL_UNDER_REVIEW') {
      const noteInp = doc.createElement('textarea');
      noteInp.className = 'form-control form-control-dark mb-2';
      noteInp.rows = 2;
      noteInp.placeholder = 'Nhập lý do quyết định kháng nghị (bắt buộc cho cả Chấp nhận và Từ chối kháng nghị)...';

      const btnRow = el('div', '', 'd-flex gap-2 flex-wrap');
      if (a.status === 'APPEAL_SUBMITTED') {
        const startBtn = el('button', 'Bắt đầu xem xét (APPEAL_UNDER_REVIEW)', 'btn btn-sm btn-outline-info');
        startBtn.onclick = async () => {
          startBtn.disabled = true;
          try {
            const resp = await fetch(`${apiBase}/refunds/appeals/${a.id}/start-review`, {
              method: 'POST', headers: { ...getCsrfHeaders() }, credentials: 'same-origin'
            });
            if (!resp.ok) {
              const err = await resp.json().catch(() => ({}));
              msgOut.className = 'alert alert-danger py-2 small mt-2';
              msgOut.textContent = err.message || 'Không thể chuyển trạng thái kháng nghị.';
              return;
            }
            await openAppealDetail(a.id);
            await loadAppeals();
          } finally {
            startBtn.disabled = false;
          }
        };
        btnRow.append(startBtn);
      }

      const sendAppealDecision = async (decision) => {
        const decisionNote = noteInp.value.trim();
        if (!decisionNote) {
          msgOut.className = 'alert alert-danger py-2 small mt-2';
          msgOut.textContent = 'Vui lòng nhập ghi chú quyết định kháng nghị (decisionNote bắt buộc).';
          return;
        }
        const resp = await fetch(`${apiBase}/refunds/appeals/${a.id}/decide`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
          credentials: 'same-origin',
          body: JSON.stringify({ decision, decisionNote })
        });
        const d = await resp.json().catch(() => ({}));
        if (!resp.ok) {
          msgOut.className = 'alert alert-danger py-2 small mt-2';
          msgOut.textContent = d.message || `Xử lý kháng nghị thất bại (HTTP ${resp.status}).`;
          return;
        }
        await openAppealDetail(a.id);
        await loadAppeals();
        await loadRefunds();
      };

      const accBtn = el('button', 'Chấp nhận kháng nghị (APPEAL_ACCEPTED -> Đưa yêu cầu gốc về UNDER_REVIEW)', 'btn btn-sm btn-success');
      accBtn.onclick = () => sendAppealDecision('APPEAL_ACCEPTED');
      const rejBtn = el('button', 'Bác kháng nghị (APPEAL_REJECTED -> Giữ nguyên REJECTED)', 'btn btn-sm btn-danger');
      rejBtn.onclick = () => sendAppealDecision('APPEAL_REJECTED');
      btnRow.append(accBtn, rejBtn);
      box.append(noteInp, btnRow, msgOut);
    }

    if (Array.isArray(a.history) && a.history.length) {
      box.append(el('h6', 'Lịch sử kháng nghị (Appeal Audit History)', 'text-white mt-3 mb-1'));
      for (const h of a.history) {
        box.append(el('div',
          `[${h.createdAt ? String(h.createdAt).slice(0, 19).replace('T', ' ') : ''}] ${h.action} (${h.actorRole || '-'}): ${h.previousStatus || '-'} -> ${h.newStatus}${h.note ? ' — ' + h.note : ''}`,
          'small text-secondary py-1 border-bottom border-secondary border-opacity-25'));
      }
    }
  }

  function resetIncidentForm() {
    if (get('incFormId')) get('incFormId').value = '';
    if (get('incFormHeading')) get('incFormHeading').textContent = 'Tạo sự cố rạp mới (Không tự động hoàn tiền)';
    if (get('incFormTitle')) get('incFormTitle').value = '';
    if (get('incFormType')) get('incFormType').value = '';
    if (get('incFormShowtimeId')) get('incFormShowtimeId').value = '';
    if (get('incFormBookingCode')) get('incFormBookingCode').value = '';
    if (get('incFormAffectedQty')) get('incFormAffectedQty').value = '1';
    if (get('incFormCustomerNotifiedAt')) get('incFormCustomerNotifiedAt').value = '';
    if (get('incFormDescription')) get('incFormDescription').value = '';
    if (get('incFormEvidenceUrl')) get('incFormEvidenceUrl').value = '';
    if (get('opsIncidentFormStatus')) get('opsIncidentFormStatus').className = 'd-none';
  }

  function populateIncidentFormForEdit(ir) {
    const card = get('opsIncidentFormCard');
    if (!card) return;
    card.classList.remove('d-none');
    if (get('incFormId')) get('incFormId').value = String(ir.id || '');
    if (get('incFormHeading')) get('incFormHeading').textContent = `Cập nhật sự cố ${ir.incidentCode} (#${ir.id})`;
    if (get('incFormCinemaId') && ir.cinemaId) get('incFormCinemaId').value = String(ir.cinemaId);
    if (get('incFormGroup') && ir.incidentGroup) get('incFormGroup').value = ir.incidentGroup;
    if (get('incFormType')) get('incFormType').value = ir.incidentType || '';
    if (get('incFormSeverity') && ir.severity) get('incFormSeverity').value = ir.severity;
    if (get('incFormTitle')) get('incFormTitle').value = ir.title || '';
    if (get('incFormShowtimeId')) get('incFormShowtimeId').value = ir.showtimeId ? String(ir.showtimeId) : '';
    if (get('incFormBookingCode')) get('incFormBookingCode').value = ir.bookingCode || '';
    if (get('incFormAffectedQty')) get('incFormAffectedQty').value = String(ir.affectedQuantity || 1);
    if (get('incFormCustomerNotifiedAt')) {
      get('incFormCustomerNotifiedAt').value = ir.customerNotifiedAt ? String(ir.customerNotifiedAt).slice(0, 16) : '';
    }
    if (get('incFormDescription')) get('incFormDescription').value = ir.description || '';
    if (get('incFormEvidenceUrl')) get('incFormEvidenceUrl').value = ir.evidenceUrl || '';
  }

  async function saveIncidentForm() {
    const statusBox = get('opsIncidentFormStatus');
    const saveBtn = get('btnSaveIncident');
    const editId = get('incFormId')?.value?.trim() || '';
    const title = get('incFormTitle')?.value?.trim() || '';
    const description = get('incFormDescription')?.value?.trim() || '';
    const incidentGroup = get('incFormGroup')?.value || 'CINEMA_INCIDENT';
    const incidentType = get('incFormType')?.value?.trim() || 'GENERAL_INCIDENT';
    const severity = get('incFormSeverity')?.value || 'MEDIUM';
    const affectedQuantity = Number(get('incFormAffectedQty')?.value || 1);
    const evidenceUrl = get('incFormEvidenceUrl')?.value?.trim() || null;
    const rawNotified = get('incFormCustomerNotifiedAt')?.value?.trim() || '';
    const customerNotifiedAt = rawNotified ? (rawNotified.length === 16 ? rawNotified + ':00' : rawNotified) : null;

    if (!title || !description) {
      if (statusBox) {
        statusBox.className = 'alert alert-danger py-2 small mt-2';
        statusBox.textContent = 'Vui lòng nhập đầy đủ tiêu đề và mô tả sự cố.';
      }
      return;
    }

    const payload = {
      incidentGroup,
      incidentType,
      severity,
      title,
      description,
      affectedQuantity,
      evidenceUrl,
      customerNotifiedAt
    };
    const showtimeVal = get('incFormShowtimeId')?.value?.trim();
    const bookingVal = get('incFormBookingCode')?.value?.trim();
    const cinemaVal = get('incFormCinemaId')?.value?.trim() || get('opsCinemaFilter')?.value?.trim();
    if (showtimeVal) payload.showtimeId = Number(showtimeVal);
    if (bookingVal) payload.bookingCode = bookingVal;
    if (cinemaVal && roleScope === 'admin') payload.cinemaId = Number(cinemaVal);

    if (saveBtn) saveBtn.disabled = true;
    try {
      const url = editId ? `${apiBase}/incidents/${editId}` : `${apiBase}/incidents`;
      const method = editId ? 'PUT' : 'POST';
      const res = await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
        credentials: 'same-origin',
        body: JSON.stringify(payload)
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        if (statusBox) {
          const errCode = data.errorCode || data.code;
          statusBox.className = 'alert alert-danger py-2 small mt-2';
          statusBox.textContent = (errCode ? `[${errCode}] ` : '') + (data.message || `Lưu sự cố thất bại (HTTP ${res.status}).`);
        }
        return;
      }
      if (statusBox) {
        statusBox.className = 'alert alert-success py-2 small mt-2';
        statusBox.textContent = `Đã lưu sự cố ${data.incidentCode || ''} thành công (Không tự động hoàn tiền).`;
      }
      await loadIncidents();
      if (data.id) await openIncidentDetail(data.id);
    } catch (err) {
      if (statusBox) {
        statusBox.className = 'alert alert-danger py-2 small mt-2';
        statusBox.textContent = err?.message || 'Lỗi kết nối khi lưu sự cố.';
      }
    } finally {
      if (saveBtn) saveBtn.disabled = false;
    }
  }

  async function deleteIncidentById(id, incidentCode, statusTargetEl = null) {
    const outBox = statusTargetEl || get('opsIncidentFormStatus');
    try {
      const res = await fetch(`${apiBase}/incidents/${id}`, {
        method: 'DELETE',
        headers: { ...getCsrfHeaders() },
        credentials: 'same-origin'
      });
      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        const errCode = err.errorCode || err.code;
        const msg = (errCode ? `[${errCode}] ` : '') + (err.message || `Không thể xóa sự cố ${incidentCode} (HTTP ${res.status}).`);
        if (outBox) {
          outBox.className = 'alert alert-danger py-2 small mt-2';
          outBox.textContent = msg;
        }
        return;
      }
      if (outBox) {
        outBox.className = 'alert alert-success py-2 small mt-2';
        outBox.textContent = `Đã xóa sự cố ${incidentCode} chưa liên kết hoàn tiền.`;
      }
      get('opsIncidentDetailPanel')?.classList.add('d-none');
      await loadIncidents();
    } catch (err) {
      if (outBox) {
        outBox.className = 'alert alert-danger py-2 small mt-2';
        outBox.textContent = err?.message || 'Lỗi mạng khi xóa sự cố.';
      }
    }
  }

  async function loadIncidents() {
    const tbody = get('opsIncidentTableBody');
    if (!tbody) return;
    const params = new URLSearchParams({ page: '0', size: '30' });
    const cinemaId = get('opsCinemaFilter')?.value;
    const status = get('opsIncidentStatusFilter')?.value;
    const group = get('opsIncidentGroupFilter')?.value;
    if (cinemaId) params.set('cinemaId', cinemaId);
    if (status) params.set('status', status);
    if (group) params.set('group', group);

    const res = await fetch(`${apiBase}/incidents?${params.toString()}`, { credentials: 'same-origin' });
    tbody.replaceChildren();
    if (!res.ok) return;
    const page = await res.json();
    const rows = page.content || [];
    if (!rows.length) {
      const tr = el('tr');
      const td = el('td', 'No incidents found.', 'text-secondary py-3 text-center');
      td.colSpan = 6;
      tr.append(td);
      tbody.append(tr);
      return;
    }

    for (const ir of rows) {
      const tr = el('tr');
      const notifiedStr = ir.customerNotifiedAt ? ` • Notified: ${String(ir.customerNotifiedAt).slice(0, 16).replace('T', ' ')}` : '';
      tr.append(
        el('td', ir.incidentCode, 'fw-bold font-monospace'),
        el('td', `${ir.cinemaName || ''} • ${ir.incidentGroup}`),
        el('td', `${ir.title}${notifiedStr}`),
        el('td', ir.status),
        el('td', `Mgr: ${ir.managerDecision || '-'} | Adm: ${ir.adminDecision || '-'}`)
      );
      const td = el('td');
      const wrap = el('div', '', 'd-flex gap-1 flex-wrap');
      const btn = el('button', roleScope === 'admin' ? 'Inspect / Resolve Appeal' : 'Verify', 'btn btn-sm btn-outline-warning');
      btn.onclick = () => openIncidentDetail(ir.id);
      const editBtn = el('button', 'Sửa', 'btn btn-sm btn-outline-info');
      editBtn.onclick = () => populateIncidentFormForEdit(ir);
      const delBtn = el('button', 'Xóa', 'btn btn-sm btn-outline-danger');
      delBtn.onclick = () => deleteIncidentById(ir.id, ir.incidentCode);
      wrap.append(btn, editBtn, delBtn);
      td.append(wrap);
      tr.append(td);
      tbody.append(tr);
    }
  }

  async function openIncidentDetail(id) {
    const res = await fetch(`${apiBase}/incidents/${id}`, { credentials: 'same-origin' });
    if (!res.ok) return;
    const ir = await res.json();
    const box = get('opsIncidentDetailPanel');
    if (!box) return;
    box.classList.remove('d-none');
    box.replaceChildren();

    const detailMsg = el('div', '', 'd-none');
    const topBar = el('div', '', 'd-flex justify-content-between align-items-start flex-wrap gap-2 mb-2');
    topBar.append(
      el('h5', `${ir.incidentCode} — ${ir.title} [${ir.status}]`, 'text-white mb-0')
    );
    const crudBtns = el('div', '', 'd-flex gap-2');
    const editBtn = el('button', 'Sửa sự cố (Edit)', 'btn btn-sm btn-outline-info');
    editBtn.onclick = () => populateIncidentFormForEdit(ir);
    const delBtn = el('button', 'Xóa sự cố (Delete)', 'btn btn-sm btn-outline-danger');
    delBtn.onclick = () => deleteIncidentById(ir.id, ir.incidentCode, detailMsg);
    crudBtns.append(editBtn, delBtn);
    topBar.append(crudBtns);

    box.append(
      topBar,
      el('div', `${ir.cinemaName} • ${ir.incidentGroup} / ${ir.incidentType} • Severity: ${ir.severity || 'MEDIUM'} • Reporter: ${ir.reportedByName || '-'}`, 'small text-secondary mb-1'),
      el('div', `Thời điểm xảy ra: ${ir.occurredAt ? String(ir.occurredAt).slice(0, 19).replace('T', ' ') : '-'} • Thời điểm thông báo khách (customerNotifiedAt): ${ir.customerNotifiedAt ? String(ir.customerNotifiedAt).slice(0, 19).replace('T', ' ') : 'Chưa ghi nhận'}`, 'small text-info mb-2'),
      el('div', ir.description, 'small text-light mb-2'),
      el('div', `Manager Conclusion: ${ir.managerDecision || 'PENDING'} ${ir.managerNote ? '— ' + ir.managerNote : ''}`, 'small text-warning mb-1'),
      el('div', `Admin Resolution: ${ir.adminDecision || 'NONE'} ${ir.adminNote ? '— ' + ir.adminNote : ''}`, 'small text-info mb-2'),
      detailMsg
    );

    if (ir.evidenceUrl) {
      const evLink = doc.createElement('a');
      evLink.href = normalizeAuthenticatedEvidenceUrl(ir.evidenceUrl);
      evLink.target = '_blank';
      evLink.rel = 'noopener noreferrer';
      evLink.className = 'small text-info d-inline-block mb-2';
      evLink.textContent = `Xem minh chứng sự cố (${ir.evidenceUrl})`;
      box.append(evLink);
    }

    // Inline customerNotifiedAt update control (for late incident deadline formula)
    const notifyRow = el('div', '', 'd-flex align-items-center gap-2 flex-wrap mb-3 p-2 rounded border border-secondary border-opacity-25');
    notifyRow.append(el('span', 'Cập nhật customer_notified_at (Hạn hoàn tiền sự cố muộn):', 'small text-secondary'));
    const notifyInp = doc.createElement('input');
    notifyInp.type = 'datetime-local';
    notifyInp.className = 'form-control form-control-sm form-control-dark';
    notifyInp.style.maxWidth = '220px';
    notifyInp.value = ir.customerNotifiedAt ? String(ir.customerNotifiedAt).slice(0, 16) : '';
    const notifyBtn = el('button', 'Lưu thời điểm thông báo khách', 'btn btn-sm btn-outline-info');
    notifyBtn.onclick = async () => {
      notifyBtn.disabled = true;
      try {
        const raw = notifyInp.value?.trim() || '';
        const formatted = raw ? (raw.length === 16 ? raw + ':00' : raw) : '';
        const patchRes = await fetch(`${apiBase}/incidents/${ir.id}/customer-notified`, {
          method: 'PATCH',
          headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
          credentials: 'same-origin',
          body: JSON.stringify({ customerNotifiedAt: formatted })
        });
        if (!patchRes.ok) {
          const err = await patchRes.json().catch(() => ({}));
          detailMsg.className = 'alert alert-danger py-2 small mb-2';
          detailMsg.textContent = err.message || 'Không thể cập nhật customerNotifiedAt.';
          return;
        }
        await openIncidentDetail(ir.id);
        await loadIncidents();
      } finally {
        notifyBtn.disabled = false;
      }
    };
    notifyRow.append(notifyInp, notifyBtn);
    box.append(notifyRow);

    if (['PENDING_VERIFICATION', 'MORE_EVIDENCE_REQUIRED'].includes(ir.status)) {
      const note = doc.createElement('textarea');
      note.className = 'form-control form-control-dark mb-2';
      note.rows = 2;
      note.placeholder = 'Verification note (mandatory for Request More Evidence or Reject)...';
      const btns = el('div', '', 'd-flex gap-2 mb-2');
      for (const dec of ['VERIFIED', 'REQUEST_MORE_EVIDENCE', 'REJECTED']) {
        const b = el('button', dec, `btn btn-sm ${dec === 'VERIFIED' ? 'btn-success' : (dec === 'REJECTED' ? 'btn-danger' : 'btn-warning')}`);
        b.onclick = async () => {
          b.disabled = true;
          try {
            const r = await fetch(`${apiBase}/incidents/${ir.id}/verify`, {
              method: 'POST', headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
              credentials: 'same-origin', body: JSON.stringify({ decision: dec, note: note.value.trim() })
            });
            if (!r.ok) {
              const d = await r.json().catch(() => ({}));
              detailMsg.className = 'alert alert-danger py-2 small mb-2';
              detailMsg.textContent = d.message || 'Failed';
              return;
            }
            openIncidentDetail(ir.id);
            loadIncidents();
          } finally {
            b.disabled = false;
          }
        };
        btns.append(b);
      }
      box.append(note, btns);
    }

    if (roleScope === 'admin') {
      const note = doc.createElement('textarea');
      note.className = 'form-control form-control-dark mb-2';
      note.rows = 2;
      note.placeholder = 'Admin appeal resolution note (preserves Manager original conclusion)...';
      const btns = el('div', '', 'd-flex gap-2 flex-wrap');
      for (const dec of ['UPHELD_MANAGER', 'OVERRIDDEN_VERIFIED', 'OVERRIDDEN_REJECTED']) {
        const b = el('button', dec, 'btn btn-sm btn-outline-info');
        b.onclick = async () => {
          b.disabled = true;
          try {
            const r = await fetch(`/api/admin/incidents/${ir.id}/resolve-appeal`, {
              method: 'POST', headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
              credentials: 'same-origin', body: JSON.stringify({ decision: dec, note: note.value.trim() })
            });
            if (!r.ok) {
              const d = await r.json().catch(() => ({}));
              detailMsg.className = 'alert alert-danger py-2 small mb-2';
              detailMsg.textContent = d.message || 'Failed';
              return;
            }
            openIncidentDetail(ir.id);
            loadIncidents();
          } finally {
            b.disabled = false;
          }
        };
        btns.append(b);
      }
      box.append(note, btns);
    }
  }

  async function loadStaffErrors() {
    const tbody = get('opsStaffErrorTableBody');
    if (!tbody) return;
    const cinemaId = get('opsCinemaFilter')?.value;
    const params = new URLSearchParams({ page: '0', size: '30' });
    if (cinemaId) params.set('cinemaId', cinemaId);
    const res = await fetch(`${apiBase}/staff-errors?${params.toString()}`, { credentials: 'same-origin' });
    tbody.replaceChildren();
    if (!res.ok) return;
    const page = await res.json();
    for (const s of page.content || []) {
      const tr = el('tr');
      tr.append(
        el('td', s.cinemaName || ''),
        el('td', s.staffName || ''),
        el('td', s.errorCategory),
        el('td', s.bookingCode || s.incidentCode || '-'),
        el('td', s.description),
        el('td', s.status)
      );
      tbody.append(tr);
    }
  }

  // Event bindings
  const adminTabButtons = doc.querySelectorAll('.admin-refund-tab');
  if (adminTabButtons && adminTabButtons.length) {
    adminTabButtons.forEach(btn => {
      btn.addEventListener('click', () => {
        const targetId = btn.dataset?.targetPane;
        adminTabButtons.forEach(b => {
          b.classList.remove('active', 'btn-danger');
          if (!b.classList.contains('btn-outline-light') && !b.classList.contains('btn-outline-warning')) {
            b.classList.add('btn-outline-light');
          }
        });
        btn.classList.remove('btn-outline-light', 'btn-outline-warning');
        btn.classList.add('active', 'btn-danger');
        doc.querySelectorAll('.admin-refund-pane').forEach(p => {
          if (p.id === targetId) p.classList.remove('d-none');
          else p.classList.add('d-none');
        });
        if (targetId === 'admin-tab-payout-queue') loadPayoutQueue();
        else if (targetId === 'admin-tab-all-refunds') loadRefunds();
        else if (targetId === 'admin-tab-payout-history') loadPayoutTransactions();
        else if (targetId === 'admin-tab-appeals') loadAppeals();
        else if (targetId === 'admin-tab-limits') { loadLimitsSummary(); loadLimitConfigs(); }
      });
    });
  }

  get('payoutQueueSelectAll')?.addEventListener('change', ev => {
    const checked = Boolean(ev?.target?.checked);
    for (const r of currentQueueRows) {
      if (!r.payoutEligible) continue;
      if (checked) selectedQueueItems.set(r.id, r);
      else selectedQueueItems.delete(r.id);
    }
    loadPayoutQueue();
  });

  get('btnBatchSelectAllEligible')?.addEventListener('click', () => {
    for (const r of currentQueueRows) {
      if (r.payoutEligible) selectedQueueItems.set(r.id, r);
    }
    doc.querySelectorAll('.payout-queue-check').forEach(cb => {
      if (!cb.disabled) cb.checked = true;
    });
    updateBatchActionBar();
  });

  get('btnBatchClearSelection')?.addEventListener('click', () => {
    selectedQueueItems.clear();
    doc.querySelectorAll('.payout-queue-check').forEach(cb => { cb.checked = false; });
    if (get('payoutQueueSelectAll')) get('payoutQueueSelectAll').checked = false;
    updateBatchActionBar();
  });

  get('btnOpenBatchConfirm')?.addEventListener('click', openBatchConfirmModal);
  get('btnConfirmCancel')?.addEventListener('click', () => {
    pendingConfirmAction = null;
    get('payoutConfirmModal')?.classList.add('d-none');
  });
  get('btnConfirmExecute')?.addEventListener('click', executePendingConfirm);

  get('btnRejectCancel')?.addEventListener('click', () => {
    pendingRejectRequestId = null;
    get('payoutRejectModal')?.classList.add('d-none');
  });
  get('btnRejectExecute')?.addEventListener('click', executeRejectPayout);
  get('btnCloseBatchResult')?.addEventListener('click', () => get('batchPayoutResultPanel')?.classList.add('d-none'));

  get('btnOpsReloadQueue')?.addEventListener('click', () => {
    currentQueuePage = 0;
    currentPage = 0;
    loadPayoutQueue();
    loadRefunds();
  });
  get('btnOpsReloadRefunds')?.addEventListener('click', () => { currentPage = 0; loadRefunds(); });
  get('opsRefundPrev')?.addEventListener('click', () => { if (currentPage > 0) { currentPage--; loadRefunds(); } });
  get('opsRefundNext')?.addEventListener('click', () => { currentPage++; loadRefunds(); });
  get('opsQueuePrev')?.addEventListener('click', () => { if (currentQueuePage > 0) { currentQueuePage--; loadPayoutQueue(); } });
  get('opsQueueNext')?.addEventListener('click', () => { currentQueuePage++; loadPayoutQueue(); });

  get('btnReloadPayoutTx')?.addEventListener('click', () => { currentPayoutTxPage = 0; loadPayoutTransactions(); });
  get('opsPayoutTxPrev')?.addEventListener('click', () => { if (currentPayoutTxPage > 0) { currentPayoutTxPage--; loadPayoutTransactions(); } });
  get('opsPayoutTxNext')?.addEventListener('click', () => { currentPayoutTxPage++; loadPayoutTransactions(); });
  get('btnCheckPayoutBalance')?.addEventListener('click', checkPayoutBalance);

  get('btnOpsReloadIncidents')?.addEventListener('click', loadIncidents);
  get('btnOpsReloadErrors')?.addEventListener('click', loadStaffErrors);
  get('btnOpsReloadLimits')?.addEventListener('click', loadLimitsSummary);
  get('btnReloadLimitSummary')?.addEventListener('click', loadLimitsSummary);
  get('btnSaveLimitConfig')?.addEventListener('click', saveLimitConfig);
  get('btnOpsReloadAppeals')?.addEventListener('click', loadAppeals);
  get('opsAppealStatusFilter')?.addEventListener('change', loadAppeals);
  get('btnToggleIncidentForm')?.addEventListener('click', () => {
    const card = get('opsIncidentFormCard');
    if (!card) return;
    if (card.classList.contains('d-none')) {
      resetIncidentForm();
      card.classList.remove('d-none');
    } else {
      card.classList.add('d-none');
    }
  });
  get('btnCancelIncidentEdit')?.addEventListener('click', () => {
    resetIncidentForm();
    get('opsIncidentFormCard')?.classList.add('d-none');
  });
  get('btnSaveIncident')?.addEventListener('click', saveIncidentForm);

  get('opsCinemaFilter')?.addEventListener('change', () => {
    currentQueuePage = 0;
    currentPage = 0;
    loadPayoutQueue();
    loadRefunds();
    loadIncidents();
    loadStaffErrors();
    loadAppeals();
    loadLimitsSummary();
  });

  const initialTab = root.dataset.activeTab;
  if (initialTab && doc.querySelector(`[data-bs-target="#mgr-${initialTab}"]`)) {
    const tabBtn = doc.querySelector(`[data-bs-target="#mgr-${initialTab}"]`);
    doc.querySelectorAll('.mgr-tab-btn').forEach(b => b.classList.remove('active'));
    tabBtn.classList.add('active');
    doc.querySelectorAll('.tab-pane').forEach(p => p.classList.remove('show', 'active'));
    doc.getElementById(`mgr-${initialTab}`)?.classList.add('show', 'active');
  }

  loadCinemasIfAdmin().then(() => {
    loadPreflightStatus();
    loadLimitsSummary();
    if (roleScope === 'admin') loadLimitConfigs();
    loadAppeals();
    if (mode === 'all' || mode === 'refunds') {
      if (roleScope === 'admin') loadPayoutQueue();
      loadRefunds();
      if (roleScope === 'admin') loadPayoutTransactions();
    }
    if (mode === 'all' || mode === 'incidents') { loadIncidents(); loadStaffErrors(); }
  });
}

if (typeof document !== 'undefined') {
  document.addEventListener('DOMContentLoaded', () => initRefundOperationsWorkspace(document));
}
