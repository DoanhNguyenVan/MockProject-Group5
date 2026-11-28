import { getCsrfHeaders } from '../core/http-client.js';

export function initCustomerBankAccounts(doc = document) {
  const root = doc.getElementById('customerBankAccountsRoot');
  if (!root || root.dataset.initialized) return;
  root.dataset.initialized = 'true';

  const get = id => doc.getElementById(id);
  const el = (tag, text = '', className = '') => {
    const node = doc.createElement(tag);
    if (text !== null && text !== undefined) node.textContent = String(text);
    if (className) node.className = className;
    return node;
  };

  let editingAccountId = null;

  function showStatus(message, isError = false) {
    const box = get('bankAccountsStatus');
    if (!box) return;
    box.className = `alert ${isError ? 'alert-danger' : 'alert-success'} mb-3`;
    box.textContent = message;
  }

  function clearConfirmError() {
    const err = get('savedBankConfirmError');
    if (err) {
      err.textContent = '';
      err.classList.add('d-none');
    }
  }

  function validateAccountConfirm() {
    const acc = (get('savedBankAccountNumber')?.value || '').trim();
    const conf = (get('savedBankAccountConfirm')?.value || '').trim();
    const err = get('savedBankConfirmError');
    if (!conf) {
      clearConfirmError();
      return true;
    }
    if (acc !== conf) {
      if (err) {
        err.textContent = 'Account number confirmation does not match.';
        err.classList.remove('d-none');
      }
      return false;
    }
    clearConfirmError();
    return true;
  }

  function resetForm() {
    editingAccountId = null;
    if (get('bankFormTitle')) get('bankFormTitle').textContent = 'Add New Bank Account';
    if (get('btnSaveBankAccount')) get('btnSaveBankAccount').textContent = 'Save Bank Account';
    get('btnCancelEditBankAccount')?.classList.add('d-none');
    if (get('savedBankBin')) get('savedBankBin').value = '';
    if (get('savedBankAccountNumber')) get('savedBankAccountNumber').value = '';
    if (get('savedBankAccountConfirm')) get('savedBankAccountConfirm').value = '';
    if (get('savedBankHolder')) get('savedBankHolder').value = '';
    if (get('savedBankNote')) get('savedBankNote').value = '';
    if (get('savedBankDefault')) get('savedBankDefault').checked = false;
    clearConfirmError();
  }

  async function loadBanks() {
    const sel = get('savedBankBin');
    if (!sel) return;
    try {
      const res = await fetch('/api/refunds/public/banks', { credentials: 'same-origin' });
      if (!res.ok) return;
      const banks = await res.json();
      sel.replaceChildren();
      const defOpt = el('option', '-- Select Recipient Bank --');
      defOpt.value = '';
      sel.append(defOpt);
      for (const [bin, name] of Object.entries(banks)) {
        const opt = el('option', `${name} (${bin})`);
        opt.value = bin;
        sel.append(opt);
      }
    } catch (_) {}
  }

  async function loadSavedAccounts() {
    const list = get('savedBankAccountsList');
    if (!list) return;
    try {
      const res = await fetch('/api/customer/bank-accounts', { credentials: 'same-origin' });
      if (!res.ok) {
        list.replaceChildren(el('div', 'Unable to load saved bank accounts.', 'small text-danger'));
        return;
      }
      const accounts = await res.json();
      renderAccounts(Array.isArray(accounts) ? accounts : []);
    } catch (_) {
      list.replaceChildren(el('div', 'Network error loading saved bank accounts.', 'small text-danger'));
    }
  }

  function renderAccounts(accounts) {
    const list = get('savedBankAccountsList');
    if (!list) return;
    list.replaceChildren();
    if (!accounts.length) {
      list.append(el('div', 'No saved bank accounts yet. Add one below for faster refunds.', 'small text-secondary'));
      return;
    }

    for (const acc of accounts) {
      const isDefault = Boolean(acc.isDefault ?? acc.default);
      const card = el('div', '', 'p-3 mb-2 rounded border border-secondary border-opacity-25 d-flex justify-content-between align-items-start flex-wrap gap-2');
      const info = el('div');

      const titleRow = el('div', '', 'd-flex align-items-center gap-2 flex-wrap');
      titleRow.append(el('span', `${acc.bankName || acc.bankBin} (${acc.bankBin})`, 'fw-bold text-white'));
      titleRow.append(el('span', acc.accountNumberMasked || '******', 'font-monospace text-info'));
      if (isDefault) {
        titleRow.append(el('span', 'Default', 'badge bg-success'));
      }
      info.append(titleRow);
      info.append(el('div', `Holder: ${acc.accountHolderName || '-'}`, 'small text-light mt-1'));
      if (acc.bankNote) {
        info.append(el('div', `Note: ${acc.bankNote}`, 'small text-secondary'));
      }

      const actions = el('div', '', 'd-flex gap-2 flex-wrap');
      if (!isDefault) {
        const defBtn = el('button', 'Set Default', 'btn btn-sm btn-outline-success');
        defBtn.type = 'button';
        defBtn.onclick = async () => {
          defBtn.disabled = true;
          try {
            const res = await fetch(`/api/customer/bank-accounts/${acc.id}/default`, {
              method: 'POST',
              headers: { ...getCsrfHeaders() },
              credentials: 'same-origin'
            });
            if (res.ok) {
              showStatus('Default bank account updated.');
              await loadSavedAccounts();
            } else {
              const err = await res.json().catch(() => ({}));
              showStatus(err.message || 'Could not set default bank account.', true);
            }
          } finally {
            defBtn.disabled = false;
          }
        };
        actions.append(defBtn);
      }

      const editBtn = el('button', 'Edit', 'btn btn-sm btn-outline-light');
      editBtn.type = 'button';
      editBtn.onclick = () => {
        editingAccountId = acc.id;
        if (get('bankFormTitle')) get('bankFormTitle').textContent = `Update Bank Account (${acc.accountNumberMasked || ''})`;
        if (get('btnSaveBankAccount')) get('btnSaveBankAccount').textContent = 'Update Bank Account';
        get('btnCancelEditBankAccount')?.classList.remove('d-none');
        if (get('savedBankBin')) get('savedBankBin').value = acc.bankBin || '';
        if (get('savedBankHolder')) get('savedBankHolder').value = acc.accountHolderName || '';
        if (get('savedBankNote')) get('savedBankNote').value = acc.bankNote || '';
        if (get('savedBankDefault')) get('savedBankDefault').checked = isDefault;
        if (get('savedBankAccountNumber')) get('savedBankAccountNumber').value = '';
        if (get('savedBankAccountConfirm')) get('savedBankAccountConfirm').value = '';
        clearConfirmError();
      };
      actions.append(editBtn);

      const delBtn = el('button', 'Delete', 'btn btn-sm btn-outline-danger');
      delBtn.type = 'button';
      delBtn.onclick = async () => {
        if (typeof window !== 'undefined' && typeof window.confirm === 'function'
            && !window.confirm(`Delete saved bank account ${acc.accountNumberMasked || ''}?`)) {
          return;
        }
        delBtn.disabled = true;
        try {
          const res = await fetch(`/api/customer/bank-accounts/${acc.id}`, {
            method: 'DELETE',
            headers: { ...getCsrfHeaders() },
            credentials: 'same-origin'
          });
          if (res.ok) {
            if (editingAccountId === acc.id) resetForm();
            showStatus('Saved bank account deleted.');
            await loadSavedAccounts();
          } else {
            const err = await res.json().catch(() => ({}));
            showStatus(err.message || 'Could not delete bank account.', true);
          }
        } finally {
          delBtn.disabled = false;
        }
      };
      actions.append(delBtn);

      card.append(info, actions);
      list.append(card);
    }
  }

  async function handleFormSubmit(e) {
    e.preventDefault();
    const bankBin = (get('savedBankBin')?.value || '').trim();
    const accountNumber = (get('savedBankAccountNumber')?.value || '').trim();
    const confirmAccountNumber = (get('savedBankAccountConfirm')?.value || '').trim();
    const accountHolderName = (get('savedBankHolder')?.value || '').trim();
    const bankNote = (get('savedBankNote')?.value || '').trim();
    const isDefault = Boolean(get('savedBankDefault')?.checked);

    if (!bankBin || !accountNumber || !confirmAccountNumber || !accountHolderName) {
      showStatus('Please fill in bank, account number, confirmation, and account holder name.', true);
      return;
    }
    if (accountNumber !== confirmAccountNumber) {
      const err = get('savedBankConfirmError');
      if (err) {
        err.textContent = 'Account number confirmation does not match.';
        err.classList.remove('d-none');
      }
      showStatus('Bank account number and confirmation do not match.', true);
      return;
    }

    const url = editingAccountId
      ? `/api/customer/bank-accounts/${editingAccountId}`
      : '/api/customer/bank-accounts';
    const method = editingAccountId ? 'PUT' : 'POST';

    const submitBtn = get('btnSaveBankAccount');
    if (submitBtn) submitBtn.disabled = true;

    try {
      const res = await fetch(url, {
        method,
        headers: { 'Content-Type': 'application/json', ...getCsrfHeaders() },
        credentials: 'same-origin',
        body: JSON.stringify({
          bankBin,
          accountNumber,
          confirmAccountNumber,
          accountHolderName,
          bankNote: bankNote || null,
          isDefault
        })
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        showStatus(data.message || 'Could not save bank account.', true);
        return;
      }
      resetForm();
      showStatus(editingAccountId ? 'Bank account updated.' : 'Bank account saved.');
      await loadSavedAccounts();
    } catch (_) {
      showStatus('Network error while saving bank account.', true);
    } finally {
      if (submitBtn) submitBtn.disabled = false;
    }
  }

  get('savedBankAccountNumber')?.addEventListener('input', validateAccountConfirm);
  get('savedBankAccountConfirm')?.addEventListener('input', validateAccountConfirm);
  get('btnCancelEditBankAccount')?.addEventListener('click', resetForm);
  get('savedBankAccountForm')?.addEventListener('submit', handleFormSubmit);

  loadBanks();
  loadSavedAccounts();
}

if (typeof document !== 'undefined') {
  document.addEventListener('DOMContentLoaded', () => initCustomerBankAccounts(document));
}
