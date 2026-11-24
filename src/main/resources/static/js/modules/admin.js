import { getSeatSvg } from "./seat-icons.js?v=3.2";
import { ADMIN_SECTIONS } from './admin-config.js?v=2.3';
import { createAdminPreview } from './admin-preview.js?v=2.0';
import { recordLabel, filterRecords, paginateRecords, validateAdminRecord, deleteBlockReason, summarizeSales, createSeatLayout, reportCsv } from './admin-model.js?v=2.0';

const STORAGE_KEY = 'box_admin_preview_v1';

export function initAdmin(doc = document) {
  const root = doc.querySelector('[data-admin-section]');
  if (!root || root.dataset.initialized) return;
  root.dataset.initialized = 'true';
  const win = doc.defaultView;
  const section = Object.hasOwn(ADMIN_SECTIONS, root.dataset.adminSection) ? root.dataset.adminSection : 'dashboard';
  const config = ADMIN_SECTIONS[section];
  const get = id => doc.getElementById(id);
  const element = (tag, text = '', className = '') => {
    const node = doc.createElement(tag); node.textContent = text; node.className = className; return node;
  };
  const formatMoney = value => new Intl.NumberFormat('vi-VN').format(value) + ' ₫';
  const shortMoney = value => `${new Intl.NumberFormat('en', { maximumFractionDigits: 1 }).format(value / 1000000)}M ₫`;
  const humanize = value => String(value).toLowerCase().replaceAll('_', ' ').replace(/^./, letter => letter.toUpperCase());
  let notifyTimer = null;
  const notify = message => {
    const el = get('adminStatus');
    if (!el) return;
    el.textContent = message;
    el.hidden = false;
    el.classList.remove('admin-status-hide');
    el.style.animation = 'none';
    void el.offsetHeight;
    el.style.animation = '';

    if (notifyTimer) clearTimeout(notifyTimer);
    notifyTimer = setTimeout(() => {
      el.classList.add('admin-status-hide');
      setTimeout(() => { el.hidden = true; }, 250);
    }, 2500);
  };
  get('adminStatus')?.addEventListener('click', () => {
    const el = get('adminStatus');
    if (el) {
      el.classList.add('admin-status-hide');
      setTimeout(() => { el.hidden = true; }, 200);
    }
  });

  let state = createAdminPreview();
  let page = 1;
  let editingId = null;
  let confirmAction = null;
  let seatDraft = [], seatRoom = '', seatDirty = false;
  let undoStack = [], redoStack = [];
  let report = null;

  function updateUndoRedoButtons() {
    const undoBtn = get('adminUndoSeats');
    const redoBtn = get('adminRedoSeats');
    if (undoBtn) undoBtn.disabled = undoStack.length === 0;
    if (redoBtn) redoBtn.disabled = redoStack.length === 0;
  }

  function recordHistory() {
    if (!seatDraft || !seatDraft.length) return;
    undoStack.push(structuredClone(seatDraft));
    if (undoStack.length > 50) undoStack.shift();
    redoStack = [];
    updateUndoRedoButtons();
  }

  function undoSeatAction() {
    if (!undoStack.length) return;
    redoStack.push(structuredClone(seatDraft));
    seatDraft = undoStack.pop();
    seatDirty = true;
    updateUndoRedoButtons();
    renderSeatGrid();
    notify('Action undone (Undo).');
  }

  function redoSeatAction() {
    if (!redoStack.length) return;
    undoStack.push(structuredClone(seatDraft));
    seatDraft = redoStack.pop();
    seatDirty = true;
    updateUndoRedoButtons();
    renderSeatGrid();
    notify('Action redone (Redo).');
  }

  const persist = message => {
    try { win.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(state)); notify(message); }
    catch { notify(`${message}`); }
  };

  const confirm = (title, message, label, action) => {
    get('adminConfirmTitle').textContent = title; get('adminConfirmMessage').textContent = message;
    get('adminConfirmAccept').textContent = label; confirmAction = action;
    get('adminConfirm').returnValue = ''; get('adminConfirm').showModal();
  };

  get('adminConfirm').addEventListener('close', () => {
    const action = confirmAction; confirmAction = null;
    if (get('adminConfirm').returnValue === 'confirm') action?.();
  });

  async function syncLiveData(isInitial = false) {
    try {
      const res = await win.fetch('/admin/api/data');
      if (res.ok) {
        const live = await res.json();
        if (live && Array.isArray(live.movies) && Array.isArray(live.cinemas)) {
          state = live;
          renderSection();
          if (!isInitial) notify('Synced latest data from database.');
          return true;
        }
      }
    } catch (err) {
      console.warn('Could not load live data from database, fallback to preview:', err);
    }
    return false;
  }

  get('adminReset').addEventListener('click', () => confirm('Reload live data?', 'Fetch the latest records directly from the MySQL database.', 'Reload live data', async () => {
    await syncLiveData(false);
    page = 1; seatDirty = false;
  }));

  get('adminTitle').textContent = config.title; get('adminBreadcrumb').textContent = config.title;
  get('adminDescription').textContent = config.description;
  doc.title = `${config.title} | BOX Cinema Admin`;
  let lastGroup = '';
  for (const [key, item] of Object.entries(ADMIN_SECTIONS)) {
    if (key === 'dashboard') {
      if (item.group !== lastGroup) { get('adminNavigation').append(element('p', item.group)); lastGroup = item.group; }
      const link = element('a'); link.href = `/admin/${key}`;
      const icon = element('span', item.icon); icon.setAttribute('aria-hidden', 'true');
      link.append(icon, element('span', item.title));
      if (section === key) link.setAttribute('aria-current', 'page');
      get('adminNavigation').append(link);

      const logLink = element('a'); logLink.href = '/admin/activity-logs';
      const logIcon = element('span', '☷'); logIcon.setAttribute('aria-hidden', 'true');
      logLink.append(logIcon, element('span', 'User Activity Logs'));
      if (section === 'activity-logs') logLink.setAttribute('aria-current', 'page');
      get('adminNavigation').append(logLink);
      continue;
    }
    if (key === 'staff') {
      if (item.group !== lastGroup) { get('adminNavigation').append(element('p', item.group)); lastGroup = item.group; }
      const feedbackLink = element('a'); feedbackLink.href = '/admin/feedbacks';
      const fbIcon = element('span', '✉'); fbIcon.setAttribute('aria-hidden', 'true');
      feedbackLink.append(fbIcon, element('span', 'Customer Feedback'));
      if (section === 'feedbacks') feedbackLink.setAttribute('aria-current', 'page');
      get('adminNavigation').append(feedbackLink);

      const accountLink = element('a'); accountLink.href = '/admin/accounts';
      const icon = element('span', '♙'); icon.setAttribute('aria-hidden', 'true');
      accountLink.append(icon, element('span', 'Accounts & roles'));
      get('adminNavigation').append(accountLink);
      continue;
    }
    if (item.group !== lastGroup) { get('adminNavigation').append(element('p', item.group)); lastGroup = item.group; }
    const link = element('a'); link.href = `/admin/${key}`;
    const icon = element('span', item.icon); icon.setAttribute('aria-hidden', 'true');
    link.append(icon, element('span', item.title));
    if (section === key) link.setAttribute('aria-current', 'page');
    get('adminNavigation').append(link);
  }
  get('adminMenuToggle').addEventListener('click', () => {
    const open = get('adminNavigation').classList.toggle('is-open'); get('adminMenuToggle').setAttribute('aria-expanded', String(open));
  });

  function badge(value) {
    const val = String(value || 'INACTIVE').toLowerCase();
    const span = element('span', '', `admin-status-pill admin-status-${val}`);
    const dot = element('span', '', 'admin-status-dot');
    span.append(dot, document.createTextNode(humanize(value || 'Inactive')));
    return span;
  }

  function formatBadge(type) {
    const str = String(type || 'Standard').trim();
    const lower = str.toLowerCase();
    let badgeClass = 'admin-format-standard';
    if (lower.includes('imax')) {
      badgeClass = 'admin-format-imax';
    } else if (lower.includes('dolby') || lower.includes('atmos')) {
      badgeClass = 'admin-format-atmos';
    } else if (lower.includes('4dx')) {
      badgeClass = 'admin-format-4dx';
    } else if (lower.includes('3d')) {
      badgeClass = 'admin-format-3d';
    }
    return element('span', str, `admin-format-badge ${badgeClass}`);
  }

  function hexToRgba(hex, opacity = 0.16) {
    if (!hex || !hex.startsWith('#')) return 'rgba(255, 255, 255, 0.1)';
    let h = hex.replace('#', '');
    if (h.length === 3) h = h.split('').map(c => c + c).join('');
    if (h.length !== 6) return 'rgba(255, 255, 255, 0.1)';
    const r = parseInt(h.substring(0, 2), 16);
    const g = parseInt(h.substring(2, 4), 16);
    const b = parseInt(h.substring(4, 6), 16);
    return `rgba(${r}, ${g}, ${b}, ${opacity})`;
  }

  function getSeatShapeFromType(typeObjOrName) {
    if (!typeObjOrName) return 'standard';
    if (typeof typeObjOrName === 'object') {
      if (typeObjOrName.shapeType) return typeObjOrName.shapeType;
      typeObjOrName = typeObjOrName.typeName || '';
    }
    const u = String(typeObjOrName).toUpperCase();
    if (u.includes('SWEETBOX') || u.includes('COUPLE') || u.includes('PAIR')) return 'sweetbox';
    if (u.includes('VIP')) return 'vip';
    if (u.includes('4DX') || u.includes('4D')) return '4dx';
    if (u.includes('IMAX')) return 'imax';
    if (u.includes('RECLINER') || u.includes('LOUNGE') || u.includes('BED')) return 'recliner';
    if (u.includes('WHEELCHAIR') || u.includes('ACCESSIBLE')) return 'wheelchair';
    return 'standard';
  }

  function seatTypeBadge(name, record) {
    const rawName = String(name || '').trim();
    const shape = record?.shapeType || getSeatShapeFromType(record || rawName);
    const wrap = element('div', '', 'admin-seattype-card');

    const iconBox = element('div', '', `admin-seattype-icon-box shape-${shape}${shape === 'sweetbox' ? ' is-sweetbox' : ''}`);
    iconBox.innerHTML = getSeatSvg(shape);

    const infoWrap = element('div', '', 'admin-seattype-info');
    const friendlyNames = {
      standard: 'Standard Seat',
      vip: 'VIP Luxury Seat',
      sweetbox: 'Sweetbox Couple Seat',
      imax: 'IMAX Laser Seat',
      '4dx': '4DX Motion Seat',
      recliner: 'Recliner 180° Seat',
      wheelchair: 'Wheelchair Accessible'
    };
    const titleText = friendlyNames[shape] || rawName;
    const title = element('div', titleText, 'admin-seattype-title');
    const rawCode = element('code', rawName, 'admin-seattype-code');
    infoWrap.append(title, rawCode);

    wrap.append(iconBox, infoWrap);
    return wrap;
  }

  function seatShapeTag(shape, record) {
    const s = String(shape || 'standard').toLowerCase();
    const wrap = element('span', '', `admin-shape-tag shape-tag-${s}`);
    const dot = element('span', '', 'admin-shape-tag-dot');

    const shapeLabels = {
      standard: 'STANDARD',
      vip: 'VIP',
      sweetbox: 'SWEETBOX',
      imax: 'IMAX CURVED',
      '4dx': '4DX MOTION',
      recliner: 'RECLINER 180°',
      wheelchair: 'WHEELCHAIR'
    };
    const label = element('span', shapeLabels[s] || s.toUpperCase(), 'admin-shape-tag-label');
    wrap.append(dot, label);
    return wrap;
  }

  function seatSurchargePill(val) {
    const surchargeVal = Number(val) || 0;
    const pill = element('span', '', `admin-surcharge-pill ${surchargeVal > 0 ? 'has-surcharge' : 'no-surcharge'}`);
    if (surchargeVal > 0) {
      pill.innerHTML = `<span class="surcharge-symbol">+</span><span class="surcharge-num">${formatMoney(surchargeVal)}</span>`;
    } else {
      pill.innerHTML = `<span class="surcharge-num">0 ₫</span><span class="surcharge-sub">(Base price)</span>`;
    }
    return pill;
  }

  function seatDescriptionText(text) {
    const box = element('div', text || 'No detailed description.', 'admin-desc-box');
    return box;
  }

  function fieldDisplay(field, record) {
    const value = record[field.key];
    if (field.type === 'reference') return recordLabel(field.source, state[field.source]?.find(item => item.id === value), state);
    if (['basePrice', 'price', 'priceSurcharge'].includes(field.key)) return formatMoney(value);
    if (field.key === 'discountValue') return record.discountType === 'PERCENT' ? `${value}%` : formatMoney(value);
    if (field.type === 'datetime-local') return String(value).replace('T', ' · ');
    if (field.key === 'durationMinutes') return `${value} min`;
    return String(value || '—');
  }

  function renderCatalog() {
    const displayRecords = (state[section] || []).map(record => ({ ...record, _searchReferences: config.fields.filter(field => field.type === 'reference').map(field => fieldDisplay(field, record)).join(' ') }));
    const cinemaVal = get('adminCinemaFilter')?.value || '';
    const results = paginateRecords(filterRecords(displayRecords, get('adminSearch').value, get('adminFilter').value, cinemaVal, section), page);
    page = results.page;
    get('adminTableCaption').textContent = `${config.title} — live records (${results.total} total)`;
    const header = element('tr');
    for (const key of config.columns) { const th = element('th', config.fields.find(field => field.key === key).label); th.scope = 'col'; header.append(th); }
    const actionHeader = element('th', 'Actions'); actionHeader.scope = 'col'; header.append(actionHeader);
    get('adminTableHead').replaceChildren(header); get('adminTableBody').replaceChildren();
    for (const record of results.items) {
      const row = element('tr');
      for (const key of config.columns) {
        const cell = element('td');
        if (key === 'status') {
          cell.append(badge(record[key]));
        } else if (key === 'ageRating') {
          cell.append(element('span', record[key], `badge-age-${record[key].toLowerCase()}`));
        } else if (section === 'rooms' && key === 'roomType') {
          cell.append(formatBadge(record[key]));
        } else if (section === 'pricing' && key === 'typeName') {
          cell.append(seatTypeBadge(record[key], record));
        } else if (section === 'pricing' && key === 'shapeType') {
          cell.append(seatShapeTag(record[key], record));
        } else if (section === 'pricing' && key === 'priceSurcharge') {
          cell.append(seatSurchargePill(record[key]));
        } else if (section === 'pricing' && key === 'description') {
          cell.append(seatDescriptionText(record[key]));
        } else if (section === 'pricing' && key === 'colorCode') {
          const colorWrap = element('div', '', 'admin-table-color-cell');
          const colorInput = element('input', '', 'admin-table-color-picker');
          colorInput.type = 'color';
          colorInput.value = record.colorCode || '#3b82f6';
          colorInput.title = `Click to change color for ${record.typeName}`;

          const hexText = element('span', record.colorCode || '#3b82f6', 'admin-table-color-hex');
          hexText.style.color = record.colorCode || '#3b82f6';

          colorInput.addEventListener('change', async (e) => {
            const newColor = e.target.value;
            hexText.textContent = newColor;
            hexText.style.color = newColor;
            record.colorCode = newColor;
            try {
              const res = await win.fetch('/admin/api/save/pricing', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(record)
              });
              if (res.ok) {
                notify(`Updated seat color for ${record.typeName} to ${newColor}.`);
                await syncLiveData(false);
              }
            } catch (err) {
              console.warn('Error updating seat color:', err);
            }
          });

          colorWrap.append(colorInput, hexText);
          cell.append(colorWrap);
        } else {
          cell.textContent = fieldDisplay(config.fields.find(field => field.key === key), record);
        }
        row.append(cell);
      }
      const actions = element('div', '', 'admin-actions admin-table-actions');
      const edit = element('button', '', 'admin-btn-action admin-btn-action-edit');
      edit.type = 'button';
      edit.innerHTML = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path></svg><span>Edit</span>`;
      edit.setAttribute('aria-label', `Edit ${recordLabel(section, record, state)}`);
      edit.addEventListener('click', () => openEditor(record.id));
      actions.append(edit);

      if (section === 'rooms') {
        const seats = element('a', '', 'admin-btn-action admin-btn-action-matrix');
        seats.href = `/admin/seats?roomId=${encodeURIComponent(record.id)}`;
        seats.innerHTML = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="3" width="7" height="7"></rect><rect x="14" y="3" width="7" height="7"></rect><rect x="14" y="14" width="7" height="7"></rect><rect x="3" y="14" width="7" height="7"></rect></svg><span>Seat Map</span>`;
        actions.append(seats);
      }

      const remove = element('button', '', 'admin-btn-action admin-btn-action-delete');
      remove.type = 'button';
      remove.innerHTML = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path></svg><span>Delete</span>`;
      remove.setAttribute('aria-label', `Delete ${recordLabel(section, record, state)}`);
      remove.addEventListener('click', () => {
        const reason = deleteBlockReason(section, record.id, state);
        if (reason) { notify(reason); return; }
        confirm('Delete record?', `Delete "${recordLabel(section, record, state)}" from database?`, 'Delete record', async () => {
          try {
            const res = await win.fetch(`/admin/api/delete/${section}/${record.id}`, { method: 'DELETE' });
            const data = await res.json();
            if (!res.ok || data.success === false) {
              notify(data.error || 'Could not delete record from database.');
              return;
            }
          } catch (e) {
            console.warn('Backend delete error:', e);
          }
          state[section] = state[section].filter(item => item.id !== record.id);
          if (section === 'rooms') delete state.layouts[record.id];
          persist('Record deleted.'); renderCatalog();
        });
      });
      actions.append(remove);
      const actionCell = element('td');
      actionCell.append(actions);
      row.append(actionCell);
      get('adminTableBody').append(row);
    }
    get('adminEmpty').hidden = results.total > 0;
    get('adminPageInfo').textContent = `${results.total} records · Page ${results.page} of ${results.pages}`;
    get('adminPrevious').disabled = results.page === 1; get('adminNext').disabled = results.page === results.pages;
  }

  function openEditor(id = null) {
    editingId = id;
    const record = state[section]?.find(item => item.id === id) || {};
    get('adminEditorTitle').textContent = `${id ? 'Edit' : 'Add'} ${config.singular}`;
    get('adminFormError').hidden = true; get('adminEditorFields').replaceChildren();
    for (const field of config.fields) {
      const label = element('label', field.label + (field.required ? ' *' : ''), ['textarea', 'multireference'].includes(field.type) ? 'admin-field-wide' : '');
      label.htmlFor = `adminField-${field.key}`;
      let input;
      if (['select', 'reference', 'multireference'].includes(field.type)) {
        input = element('select'); input.multiple = field.type === 'multireference';
        if (field.source) {
          if (!input.multiple) { const placeholder = element('option', `Select ${field.label.toLowerCase()}`); placeholder.value = ''; input.append(placeholder); }
          for (const item of (state[field.source] || [])) {
            const option = element('option', recordLabel(field.source, item, state)); option.value = item.id;
            if (input.multiple) option.selected = (record[field.key] || []).includes(item.id);
            input.append(option);
          }
        } else for (const value of field.options) { const option = element('option', humanize(value)); option.value = value; input.append(option); }
      } else if (field.type === 'textarea') input = element('textarea');
      else if (field.type === 'color') {
        const currentColor = (record[field.key] || '#3b82f6').toLowerCase();
        const colorWrapper = element('div', '', 'admin-color-field-wrapper');

        input = element('input');
        input.type = 'color';
        input.id = `adminField-${field.key}`;
        input.name = field.key;
        input.className = 'admin-color-picker-input';
        input.value = currentColor;
        input.required = !!field.required;

        const hexInput = element('input');
        hexInput.type = 'text';
        hexInput.className = 'admin-input admin-color-hex-input';
        hexInput.value = currentColor;
        hexInput.maxLength = 7;
        hexInput.placeholder = '#3b82f6';

        const palette = element('div', '', 'admin-color-palette');
        const PRESETS = [
          { name: 'Standard (Blue)', hex: '#3b82f6' },
          { name: 'VIP (Gold)', hex: '#f59e0b' },
          { name: 'Sweetbox (Purple)', hex: '#a855f7' },
          { name: 'Couple / Rose', hex: '#ec4899' },
          { name: 'Lounge / Emerald', hex: '#10b981' },
          { name: 'Cyan', hex: '#06b6d4' },
          { name: 'Crimson', hex: '#f43f5e' },
          { name: 'Violet', hex: '#8b5cf6' },
          { name: 'Orange', hex: '#f97316' },
          { name: 'Yellow', hex: '#eab308' }
        ];

        const syncColor = (val) => {
          if (/^#[0-9a-fA-F]{6}$/.test(val)) {
            input.value = val;
            hexInput.value = val;
            palette.querySelectorAll('.admin-color-swatch').forEach(btn => {
              btn.classList.toggle('active', btn.dataset.hex.toLowerCase() === val.toLowerCase());
            });
          }
        };

        PRESETS.forEach(p => {
          const swatch = element('button', '', 'admin-color-swatch');
          swatch.type = 'button';
          swatch.dataset.hex = p.hex;
          swatch.title = `${p.name} (${p.hex})`;
          swatch.style.backgroundColor = p.hex;
          if (p.hex.toLowerCase() === currentColor) swatch.classList.add('active');
          swatch.addEventListener('click', (e) => {
            e.preventDefault();
            syncColor(p.hex);
          });
          palette.append(swatch);
        });

        input.addEventListener('input', (e) => {
          syncColor(e.target.value);
        });

        hexInput.addEventListener('input', (e) => {
          let val = e.target.value.trim();
          if (val && !val.startsWith('#')) val = '#' + val;
          if (/^#[0-9a-fA-F]{6}$/.test(val)) {
            input.value = val;
            palette.querySelectorAll('.admin-color-swatch').forEach(btn => {
              btn.classList.toggle('active', btn.dataset.hex.toLowerCase() === val.toLowerCase());
            });
          }
        });

        const colorControls = element('div', '', 'admin-color-controls');
        colorControls.append(input, hexInput);
        colorWrapper.append(colorControls, palette);

        const error = element('span', '', 'admin-field-error');
        error.id = `${input.id}-error`;
        input.setAttribute('aria-describedby', error.id);

        label.append(colorWrapper, error);
        get('adminEditorFields').append(label);
        continue;
      }
      else { input = element('input'); input.type = field.type; }
      input.id = `adminField-${field.key}`; input.name = field.key; input.className = 'admin-input'; input.required = !!field.required;
      if (field.type === 'number') { input.min = field.min; input.max = field.max; input.step = 1; }
      else if (field.max) input.maxLength = field.max;
      if (field.type !== 'multireference' && record[field.key] != null) input.value = record[field.key];
      const error = element('span', '', 'admin-field-error'); error.id = `${input.id}-error`; input.setAttribute('aria-describedby', error.id);
      label.append(input);
      if (input.multiple) label.append(element('small', 'Use Ctrl / Command to select multiple genres.', 'admin-muted'));
      label.append(error); get('adminEditorFields').append(label);
    }

    // Auto-calculate end time for showtimes based on movie runtime
    if (section === 'showtimes') {
      const updateEndTime = () => {
        const mInput = get('adminField-movieId');
        const sInput = get('adminField-startAt');
        const eInput = get('adminField-endAt');
        if (mInput && sInput && eInput && mInput.value && sInput.value) {
          const selected = state.movies?.find(m => m.id === mInput.value);
          if (selected && selected.durationMinutes) {
            const start = new Date(sInput.value);
            if (!isNaN(start.getTime())) {
              const end = new Date(start.getTime() + (selected.durationMinutes + 15) * 60000);
              const pad = n => String(n).padStart(2, '0');
              eInput.value = `${end.getFullYear()}-${pad(end.getMonth() + 1)}-${pad(end.getDate())}T${pad(end.getHours())}:${pad(end.getMinutes())}`;
            }
          }
        }
      };
      get('adminField-movieId')?.addEventListener('change', updateEndTime);
      get('adminField-startAt')?.addEventListener('change', updateEndTime);
    }

    get('adminEditor').showModal();
  }

  function closeEditor() { get('adminEditor').close(); }
  get('adminEditorClose').addEventListener('click', closeEditor); get('adminEditorCancel').addEventListener('click', closeEditor);

  get('adminEditorForm').addEventListener('submit', async event => {
    event.preventDefault();
    const isNewRoom = !editingId && section === 'rooms';
    const record = { id: editingId || `temp-${win.crypto.randomUUID()}` };
    for (const field of config.fields) {
      const input = get(`adminField-${field.key}`);
      record[field.key] = field.type === 'multireference' ? [...input.selectedOptions].map(option => option.value) : field.type === 'number' && input.value !== '' ? Number(input.value) : input.value.trim();
      input.removeAttribute('aria-invalid'); get(`${input.id}-error`).textContent = '';
    }
    const errors = validateAdminRecord(section, record, state);
    if (Object.keys(errors).length) {
      get('adminFormError').textContent = 'Please correct the highlighted fields.'; get('adminFormError').hidden = false;
      for (const [key, message] of Object.entries(errors)) { const input = get(`adminField-${key}`); if (input) { input.setAttribute('aria-invalid', 'true'); get(`${input.id}-error`).textContent = message; } }
      get('adminEditorFields').querySelector('[aria-invalid="true"]')?.focus(); return;
    }

    // Safety check for deactivation when customer ticket impact might exist
    if (editingId && (section === 'cinemas' || section === 'rooms') && record.status === 'INACTIVE') {
      try {
        const impactRes = await win.fetch(`/admin/api/impact-check?section=${section}&id=${encodeURIComponent(editingId)}`);
        if (impactRes.ok) {
          const impactData = await impactRes.json();
          if (impactData.hasImpact) {
            showDeactivateSafetyModal(section, editingId, record, impactData);
            return;
          }
        }
      } catch (err) {
        console.warn('Impact check error:', err);
      }
    }

    try {
      const res = await win.fetch(`/admin/api/save/${section}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(record)
      });
      const data = await res.json();
      if (!res.ok || data.success === false) {
        get('adminFormError').textContent = data.error || 'Failed to save record to database.';
        get('adminFormError').hidden = false;
        return;
      }
      if (data.id) record.id = String(data.id);
      notify('Record saved to database.');
      await syncLiveData(false);
    } catch (e) {
      console.warn('Backend save error:', e);
      notify('Saved locally.');
    }

    if (editingId) state[section] = state[section].map(item => item.id === editingId ? record : item);
    else state[section].unshift(record);
    closeEditor(); get('adminSearch').value = ''; get('adminFilter').value = ''; page = 1;
    persist('Record updated.'); renderCatalog();

    // If a new room was added, prompt to customize seat matrix right away
    if (isNewRoom && record.id) {
      confirm(
        'Customize Seat Matrix?',
        `Room "${record.roomName}" was successfully created with standard seats. Would you like to design its seat matrix now?`,
        'Open Seat Designer',
        () => { win.location.href = `/admin/seats?roomId=${encodeURIComponent(record.id)}`; }
      );
    }
  });

  function showDeactivateSafetyModal(sec, id, record, impactData) {
    const modal = get('adminDeactivateSafetyModal');
    if (!modal) return;

    get('adminImpactModalTitle').textContent = sec === 'cinemas'
      ? `Deactivate facility "${record.cinemaName || ''}"`
      : `Deactivate room "${record.roomName || ''}"`;

    get('adminImpactBookingsCount').textContent = impactData.totalBookings;
    get('adminImpactTicketsCount').textContent = impactData.totalTickets;
    get('adminImpactTotalAmount').textContent = formatMoney(impactData.totalAmount);
    get('adminImpactShowtimesCount').textContent = impactData.upcomingShowtimes;

    // Render bookings table
    const tbody = get('adminImpactTableBody');
    tbody.replaceChildren();
    for (const b of (impactData.bookings || [])) {
      const tr = element('tr');
      const tdCode = element('td', b.bookingCode);
      tdCode.style.fontFamily = 'monospace';
      tdCode.style.fontWeight = '700';
      tdCode.style.color = '#ef4444';

      const tdCust = element('td');
      tdCust.innerHTML = `<strong>${b.customerName}</strong><br><small style="color: var(--box-text-muted);">${b.customerPhone || b.customerEmail || '—'}</small>`;

      const tdMovie = element('td');
      tdMovie.innerHTML = `<strong>${b.movieTitle}</strong><br><small style="color: var(--box-text-muted);">${b.roomName}</small>`;

      const tdTime = element('td', b.startAt);

      const tdTickets = element('td');
      tdTickets.innerHTML = `<span style="display: inline-block; padding: 2px 7px; border-radius: 4px; background: rgba(255,255,255,0.08); font-weight: 600;">${b.ticketCount} tickets</span>`;

      const tdAmount = element('td', formatMoney(b.totalAmount));
      tdAmount.style.fontWeight = '700';
      tdAmount.style.color = '#f43f5e';

      tr.append(tdCode, tdCust, tdMovie, tdTime, tdTickets, tdAmount);
      tbody.append(tr);
    }

    const cardRefund = get('adminOptionRefundCard');
    const cardGraceful = get('adminOptionGracefulCard');
    if (cardRefund && cardGraceful) {
      cardRefund.onclick = () => {
        cardRefund.classList.add('selected');
        cardGraceful.classList.remove('selected');
        cardRefund.querySelector('input').checked = true;
      };
      cardGraceful.onclick = () => {
        cardGraceful.classList.add('selected');
        cardRefund.classList.remove('selected');
        cardGraceful.querySelector('input').checked = true;
      };
    }

    const submitBtn = get('adminImpactSubmit');
    const newSubmitBtn = submitBtn.cloneNode(true);
    submitBtn.parentNode.replaceChild(newSubmitBtn, submitBtn);

    newSubmitBtn.onclick = async () => {
      const selectedRadio = modal.querySelector('input[name="adminDeactivateStrategy"]:checked');
      const strategy = selectedRadio ? selectedRadio.value : 'REFUND_AND_CANCEL';

      newSubmitBtn.disabled = true;
      newSubmitBtn.textContent = 'Processing...';

      try {
        const res = await win.fetch('/admin/api/resolve-deactivation', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            section: sec,
            id,
            strategy,
            record
          })
        });
        const result = await res.json();
        if (res.ok && result.success) {
          modal.close();
          closeEditor();
          notify(result.message || 'Operation completed successfully.');
          await syncLiveData(false);
        } else {
          notify(result.error || 'An error occurred during processing.');
        }
      } catch (e) {
        console.error(e);
        notify('Server connection error.');
      } finally {
        newSubmitBtn.disabled = false;
        newSubmitBtn.textContent = 'Confirm Execution';
      }
    };

    modal.showModal();
  }

  get('adminCreate').addEventListener('click', () => openEditor());
  get('adminSearch').addEventListener('input', () => { page = 1; renderCatalog(); });
  get('adminFilter').addEventListener('change', () => { page = 1; renderCatalog(); });
  if (get('adminCinemaFilter')) {
    get('adminCinemaFilter').addEventListener('change', () => { page = 1; renderCatalog(); });
  }
  get('adminClearFilters').addEventListener('click', () => {
    get('adminSearch').value = '';
    get('adminFilter').value = '';
    if (get('adminCinemaFilter')) get('adminCinemaFilter').value = '';
    page = 1;
    renderCatalog();
  });
  get('adminPrevious').addEventListener('click', () => { page--; renderCatalog(); });
  get('adminNext').addEventListener('click', () => { page++; renderCatalog(); });

  function renderMetrics(target, summary) {
    const container = get(target);
    if (!container) return;
    container.replaceChildren();
    const activeCinemas = (state.cinemas || []).filter(c => c.status !== 'INACTIVE').length || (state.cinemas?.length || 0);
    const totalRooms = state.rooms?.length || 0;
    const nowShowing = (state.movies || []).filter(m => m.status === 'NOW_SHOWING').length || (state.movies?.length || 0);
    const metrics = [
      {
        label: 'Chain Network',
        icon: '⌂',
        accent: 'accent-blue',
        value: `${activeCinemas} Cinemas`,
        note: `${totalRooms} Auditoriums · ${nowShowing} Active Titles`
      },
      {
        label: 'Ticket Revenue',
        icon: '₫',
        accent: 'accent-red',
        value: shortMoney(summary.revenue),
        note: `${formatMoney(summary.revenue)} paid total`
      },
      {
        label: 'Tickets Sold',
        icon: '▥',
        accent: 'accent-emerald',
        value: summary.tickets.toLocaleString('en'),
        note: 'Total confirmed admissions'
      },
      {
        label: 'Paid Orders',
        icon: '▤',
        accent: 'accent-amber',
        value: summary.orders.toLocaleString('en'),
        note: 'Cancelled orders excluded'
      },
      {
        label: 'Avg. Ticket Price',
        icon: '↗',
        accent: 'accent-purple',
        value: summary.tickets ? formatMoney(Math.round(summary.revenue / summary.tickets)) : '—',
        note: 'Chain-wide average yield'
      }
    ];
    for (const m of metrics) {
      const card = element('div', '', `admin-metric ${m.accent}`);
      const topRow = element('div', '', 'admin-metric-top');
      topRow.append(element('p', m.label), element('span', m.icon, 'admin-metric-icon'));
      card.append(topRow, element('strong', m.value), element('small', m.note));
      container.append(card);
    }
  }

  function formatChartDayLabel(dayStr) {
    if (!dayStr || dayStr.length < 10) return dayStr || '';
    const parts = dayStr.slice(0, 10).split('-');
    if (parts.length !== 3) return dayStr;
    const months = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
    const mIdx = Math.max(0, Math.min(11, parseInt(parts[1], 10) - 1));
    return `${parts[2]} ${months[mIdx]}`;
  }

  function renderChart(target, summary) {
    const container = get(target);
    if (!container) return;
    container.replaceChildren();
    const allDays = Object.entries(summary.days).sort(([a], [b]) => a.localeCompare(b));
    const days = allDays.slice(-10);
    if (!days.length) {
      container.append(element('p', 'No paid sales recorded for this period.', 'admin-muted'));
      return;
    }
    const maximum = Math.max(...days.map(([, amount]) => amount), 1);
    const peakEntry = days.reduce((best, curr) => (curr[1] > best[1] ? curr : best), days[0]);
    container.setAttribute('aria-label', days.map(([day, value]) => `${day}: ${formatMoney(value)}`).join('; '));

    const metaEl = get('adminRevenueMeta');
    if (metaEl && target === 'adminRevenueChart') {
      metaEl.replaceChildren();
      const avgDaily = Math.round(days.reduce((s, [, v]) => s + v, 0) / days.length);
      const pillPeak = element('span', `Peak: ${shortMoney(peakEntry[1])} (${formatChartDayLabel(peakEntry[0])})`, 'admin-meta-pill pill-peak');
      const pillAvg = element('span', `Daily Avg: ${shortMoney(avgDaily)}`, 'admin-meta-pill');
      metaEl.append(pillPeak, pillAvg);
    }

    for (const [day, amount] of days) {
      const isPeak = day === peakEntry[0] && amount > 0;
      const column = element('div', '', `admin-chart-column${isPeak ? ' is-peak' : ''}`);
      const valLabel = element('span', shortMoney(amount), 'admin-chart-val');
      const barWrap = element('div', '', 'admin-chart-bar-track');
      const bar = element('div', '', 'admin-chart-bar');
      const heightPct = Math.max(6, Math.round((amount / maximum) * 100));
      bar.style.height = `${heightPct}%`;
      bar.title = `${day}: ${formatMoney(amount)}`;
      barWrap.append(bar);
      const dateLabel = element('span', formatChartDayLabel(day), 'admin-chart-date');
      column.append(valLabel, barWrap, dateLabel);
      container.append(column);
    }
  }

  function renderDashboard() {
    let summary = summarizeSales(state.sales || [], '0000-01-01', '9999-12-31');
    if (!summary.orders && state.sales?.length) {
      summary = summarizeSales(state.sales, '2026-11-01', '2026-11-30');
    }
    renderMetrics('adminMetrics', summary);
    renderChart('adminRevenueChart', summary);

    // Top Performing Movies with share bar
    const topMoviesEl = get('adminTopMovies');
    topMoviesEl.replaceChildren();
    const topList = summary.movies.slice(0, 5);
    if (!topList.length) {
      topMoviesEl.append(element('p', 'No ticket revenue recorded yet.', 'admin-muted'));
    } else {
      const totalRev = Math.max(summary.revenue, 1);
      topList.forEach((movie, index) => {
        const movieObj = (state.movies || []).find(row => row.id === movie.movieId);
        const item = element('div', '', 'admin-ranking-item');
        const badgeClass = index === 0 ? 'rank-gold' : index === 1 ? 'rank-silver' : index === 2 ? 'rank-bronze' : 'rank-default';
        const rankBadge = element('span', `0${index + 1}`, `admin-rank-badge ${badgeClass}`);

        const info = element('div', '', 'admin-ranking-info');
        const sharePct = Math.min(100, Math.round((movie.revenue / totalRev) * 100));
        const titleRow = element('div', '', 'admin-ranking-title-row');
        titleRow.append(
          element('strong', recordLabel('movies', movieObj, state)),
          element('b', shortMoney(movie.revenue), 'admin-ranking-amount')
        );
        const subRow = element('div', '', 'admin-ranking-sub-row');
        subRow.append(
          element('small', `${movie.tickets.toLocaleString('en')} tickets sold`),
          element('small', `${sharePct}% share`, 'admin-ranking-share')
        );
        const progressTrack = element('div', '', 'admin-ranking-progress');
        const progressFill = element('div', '', `admin-ranking-progress-fill ${badgeClass}`);
        progressFill.style.width = `${Math.max(4, sharePct)}%`;
        progressTrack.append(progressFill);

        info.append(titleRow, subRow, progressTrack);
        item.append(rankBadge, info);
        topMoviesEl.append(item);
      });
    }

    // Upcoming Screenings
    const upcomingEl = get('adminUpcoming');
    upcomingEl.replaceChildren();
    const today = new Date().toISOString().slice(0, 10);
    let screenings = (state.showtimes || []).filter(item => item.status === 'ACTIVE' && item.startAt >= today).sort((a, b) => a.startAt.localeCompare(b.startAt));
    if (!screenings.length) {
      screenings = (state.showtimes || []).filter(item => item.status === 'ACTIVE').sort((a, b) => a.startAt.localeCompare(b.startAt));
    }
    screenings = screenings.slice(0, 5);
    if (!screenings.length) {
      upcomingEl.append(element('p', 'No screenings found in schedule.', 'admin-muted'));
    } else {
      screenings.forEach(show => {
        const row = element('div', '', 'admin-upcoming-item');
        const timeBox = element('div', '', 'admin-upcoming-timebox');
        const timeStr = (show.startAt || '').slice(11, 16) || '00:00';
        const dateStr = formatChartDayLabel((show.startAt || '').slice(0, 10));
        timeBox.append(
          element('span', timeStr, 'admin-time-pill'),
          element('small', dateStr, 'admin-date-sub')
        );

        const details = element('div', '', 'admin-upcoming-details');
        const movieObj = (state.movies || []).find(item => item.id === show.movieId);
        const roomObj = (state.rooms || []).find(item => item.id === show.roomId);
        details.append(
          element('strong', recordLabel('movies', movieObj, state)),
          element('small', `${recordLabel('rooms', roomObj, state)} · ${formatMoney(show.basePrice || 0)}`)
        );

        const link = element('a', 'Manage →', 'admin-upcoming-action');
        link.href = `/admin/showtimes?edit=${encodeURIComponent(show.id)}`;
        row.append(timeBox, details, link);
        upcomingEl.append(row);
      });
    }

    // Recent Ticket Orders (fills left column bottom nicely)
    const recentBookingsEl = get('adminRecentBookings');
    if (recentBookingsEl) {
      recentBookingsEl.replaceChildren();
      const recentList = (state.bookings || []).slice(0, 5);
      if (!recentList.length) {
        recentBookingsEl.append(element('p', 'No recent booking transactions available.', 'admin-muted'));
      } else {
        recentList.forEach(bk => {
          const row = element('div', '', 'admin-recent-booking-row');
          const left = element('div', '', 'admin-recent-booking-main');
          const codeRow = element('div', '', 'd-flex align-items-center gap-2');
          codeRow.append(
            element('code', bk.bookingCode || `#${bk.id}`, 'admin-recent-booking-code'),
            badge(bk.bookingStatus || 'CONFIRMED')
          );
          const metaText = `${bk.customerName || 'Guest'} · ${bk.movieTitle || 'Movie'} (${bk.cinemaName || 'BOX Cinema'})`;
          left.append(codeRow, element('small', metaText, 'admin-recent-booking-meta'));

          const right = element('div', '', 'admin-recent-booking-right');
          right.append(
            element('strong', formatMoney(bk.totalAmount || 0)),
            element('small', `${bk.ticketCount || 1} ticket(s) · ${bk.bookedAt || ''}`)
          );
          row.append(left, right);
          recentBookingsEl.append(row);
        });
      }
    }
  }

  function renderReports() {
    let from = get('adminReportFrom').value, to = get('adminReportTo').value;
    if (!from || !to) {
      from = '2026-11-01';
      to = '2026-11-30';
      get('adminReportFrom').value = from;
      get('adminReportTo').value = to;
    }
    if (from > to) { notify('Choose a valid date range. The start date must be on or before the end date.'); return; }
    report = summarizeSales(state.sales, from, to, get('adminReportCinema').value);
    renderMetrics('adminReportMetrics', report); renderChart('adminReportChart', report); get('adminReportBody').replaceChildren();
    for (const item of report.movies) {
      const row = element('tr');[recordLabel('movies', state.movies.find(movie => movie.id === item.movieId), state), item.tickets, formatMoney(item.revenue), `${(item.revenue / (report.revenue || 1) * 100).toFixed(1)}%`].forEach(value => row.append(element('td', value)));
      get('adminReportBody').append(row);
    }
    get('adminReportEmpty').hidden = report.orders > 0; get('adminExportReport').disabled = !report.orders;
  }

  get('adminReportFilters').addEventListener('submit', event => { event.preventDefault(); renderReports(); });
  get('adminExportReport').addEventListener('click', () => {
    if (!report?.orders) return;
    const url = win.URL.createObjectURL(new win.Blob([reportCsv(report, state)], { type: 'text/csv;charset=utf-8' }));
    const link = element('a'); link.href = url; link.download = 'box-cinema-report.csv'; doc.body.append(link); link.click(); link.remove();
    win.setTimeout(() => win.URL.revokeObjectURL(url), 1000);
    notify('Exported report to CSV.');
  });

  function reindexSeatDraft(seats) {
    if (!seats || !seats.length) return;
    const rowMap = new Map();
    for (const seat of seats) {
      if (!rowMap.has(seat.row)) rowMap.set(seat.row, []);
      rowMap.get(seat.row).push(seat);
    }
    for (const [rowKey, rowSeats] of rowMap) {
      rowSeats.forEach((s, idx) => {
        if (s.col === undefined || s.col === null) {
          s.col = s.number || (idx + 1);
        }
      });
      rowSeats.sort((a, b) => a.col - b.col);
      let activeSeq = 0;
      for (const s of rowSeats) {
        if (s.status === 'ACTIVE') {
          activeSeq++;
          s.activeNumber = activeSeq;
          s.seatLabel = `${rowKey}${activeSeq}`;
        } else {
          s.activeNumber = null;
          s.seatLabel = `${rowKey}×`;
        }
      }
    }
  }

  function validateRoomSeatLayout(roomType, seats) {
    const errors = [];
    const invalidSeatKeys = new Set();
    if (!seats || !seats.length) return { valid: true, errors, invalidSeatKeys };

    const normRoomType = String(roomType || '').trim().toUpperCase();

    // Map seats by row
    const rowMap = new Map();
    for (const seat of seats) {
      if (!rowMap.has(seat.row)) rowMap.set(seat.row, []);
      rowMap.get(seat.row).push(seat);
    }
    const sortedRows = Array.from(rowMap.keys()).sort();
    const lastRow = sortedRows[sortedRows.length - 1];

    const getSeatShape = (seat) => {
      const typeObj = (state.pricing || []).find(p => p.id === seat.typeId);
      const name = (typeObj?.typeName || '').toUpperCase();
      const shape = typeObj?.shapeType || (name.includes('SWEETBOX') || name.includes('COUPLE') || name.includes('PAIR') ? 'sweetbox' : (name.includes('VIP') ? 'vip' : (name.includes('4DX') ? '4dx' : (name.includes('IMAX') ? 'imax' : (name.includes('RECLINER') ? 'recliner' : (name.includes('WHEELCHAIR') ? 'wheelchair' : 'standard'))))));
      return { shape, name };
    };

    // 1. General Sweetbox validation across all rooms
    for (const rowKey of sortedRows) {
      const rowSeats = rowMap.get(rowKey).sort((a, b) => a.number - b.number);
      const activeSweetboxes = rowSeats.filter(s => s.status === 'ACTIVE' && getSeatShape(s).shape === 'sweetbox');

      // Every active Sweetbox seat MUST belong to a strict, exclusive pair of 2 contiguous adjacent seats.
      const pairedSeats = new Set();

      // Pass 1: Check existing valid explicit pairIds
      for (const s of activeSweetboxes) {
        if (s.pairId && !pairedSeats.has(s)) {
          const partner = activeSweetboxes.find(other => other !== s && other.pairId === s.pairId && !pairedSeats.has(other));
          if (partner && Math.abs(partner.number - s.number) === 1) {
            pairedSeats.add(s);
            pairedSeats.add(partner);
          }
        }
      }

      // Pass 2: For any unassigned sweetbox seats, greedily pair strictly adjacent unassigned sweetboxes
      for (let i = 0; i < activeSweetboxes.length; i++) {
        const s = activeSweetboxes[i];
        if (pairedSeats.has(s)) continue;

        const next = i < activeSweetboxes.length - 1 ? activeSweetboxes[i + 1] : null;
        if (next && !pairedSeats.has(next) && (next.number - s.number === 1)) {
          pairedSeats.add(s);
          pairedSeats.add(next);
          const newPairId = `${s.row}${s.number}-${s.row}${next.number}`;
          s.pairId = newPairId;
          next.pairId = newPairId;
        }
      }

      // Pass 3: Any active Sweetbox seat NOT in pairedSeats is orphaned / odd / invalid
      for (const s of activeSweetboxes) {
        if (!pairedSeats.has(s)) {
          s.pairId = null;
          invalidSeatKeys.add(`${s.row}${s.number}`);
          invalidSeatKeys.add(`${s.row}_${s.number}`);
          errors.push(`Sweetbox couple seat [${s.row}${s.number}] is unpaired; Sweetbox seats must be paired in contiguous 2-seat blocks.`);
        }
      }
    }

    // 2. Wheelchair Validation according to ADA Standards
    if (!normRoomType.includes('4DX') && !normRoomType.includes('4D')) {
      const firstRow = sortedRows[0];
      const maxCol = Math.max(...seats.map(s => s.number));
      const wheelchairSeats = seats.filter(s => s.status === 'ACTIVE' && getSeatShape(s).shape === 'wheelchair');

      for (const s of wheelchairSeats) {
        if (s.row !== firstRow) {
          invalidSeatKeys.add(`${s.row}${s.number}`);
          errors.push(`Wheelchair seat [${s.row}${s.number}] invalid: Must be placed in the front row (Row ${firstRow}) on level ground near entrances.`);
        } else {
          const isOuterEdge = (s.number <= 2) || (s.number >= maxCol - 1);
          if (!isOuterEdge) {
            invalidSeatKeys.add(`${s.row}${s.number}`);
            errors.push(`Wheelchair seat [${s.row}${s.number}] invalid: Wheelchair positions must be located on the outer edge (columns 1, 2 or ${maxCol - 1}, ${maxCol}) near main exit aisles for emergency evacuation compliance.`);
          }
        }
      }
    }

    // 3. Room Type Specific Validation
    if (normRoomType.includes('4DX') || normRoomType.includes('4D')) {
      // 4DX Motion Room: 100% SEAT_4DX
      for (const seat of seats) {
        if (seat.status !== 'ACTIVE') continue;
        const meta = getSeatShape(seat);
        if (meta.shape !== '4dx') {
          invalidSeatKeys.add(`${seat.row}${seat.number}`);
          errors.push(`4DX Motion auditoriums require 100% SEAT_4DX seats (excluding ${meta.name} and Wheelchair due to integrated motion bases). Seat [${seat.row}${seat.number}] is invalid.`);
        }
      }

      // Check continuous active clusters on each row: clusters of 4 or 2 (divisible by 2)
      for (const rowKey of sortedRows) {
        const rowSeats = rowMap.get(rowKey).sort((a, b) => a.number - b.number);
        let currentCluster = [];

        for (let i = 0; i < rowSeats.length; i++) {
          const seat = rowSeats[i];
          if (seat.status === 'ACTIVE') {
            currentCluster.push(seat);
          } else {
            if (currentCluster.length > 0) {
              if (currentCluster.length % 2 !== 0) {
                const first = currentCluster[0];
                const last = currentCluster[currentCluster.length - 1];
                currentCluster.forEach(s => invalidSeatKeys.add(`${s.row}${s.number}`));
                errors.push(`4DX Motion rooms require 4DX seats to be grouped in clusters of 4 or 2 seats (even multiples). Row ${rowKey} has an odd cluster of ${currentCluster.length} seats (${first.row}${first.number} - ${last.row}${last.number}).`);
              }
              currentCluster = [];
            }
          }
        }
        if (currentCluster.length > 0) {
          if (currentCluster.length % 2 !== 0) {
            const first = currentCluster[0];
            const last = currentCluster[currentCluster.length - 1];
            currentCluster.forEach(s => invalidSeatKeys.add(`${s.row}${s.number}`));
            errors.push(`4DX Motion rooms require 4DX seats to be grouped in clusters of 4 or 2 seats (even multiples). Row ${rowKey} has an odd cluster of ${currentCluster.length} seats (${first.row}${first.number} - ${last.row}${last.number}).`);
          }
        }
      }
    } else if (normRoomType.includes('GOLD')) {
      // Gold Class: 100% RECLINER, optional WHEELCHAIR
      const firstRow = sortedRows[0];
      for (const seat of seats) {
        if (seat.status !== 'ACTIVE') continue;
        const meta = getSeatShape(seat);
        if (meta.shape !== 'recliner' && meta.shape !== 'wheelchair') {
          invalidSeatKeys.add(`${seat.row}${seat.number}`);
          errors.push(`Gold Class auditoriums only permit RECLINER seats and Wheelchair positions. Seat [${seat.row}${seat.number}] (${meta.name}) is invalid.`);
        }
      }
    } else if (normRoomType.includes('IMAX')) {
      // IMAX Laser Room: 100% IMAX_SEAT and WHEELCHAIR
      for (const seat of seats) {
        if (seat.status !== 'ACTIVE') continue;
        const meta = getSeatShape(seat);
        if (meta.shape !== 'imax' && meta.shape !== 'wheelchair') {
          invalidSeatKeys.add(`${seat.row}${seat.number}`);
          errors.push(`IMAX Laser auditoriums only permit IMAX_SEAT and Wheelchair positions (disallowed: ${meta.name}). Seat [${seat.row}${seat.number}] is invalid.`);
        }
      }
    } else {
      // 2D Standard / 3D Digital / 2D Dolby Atmos
      for (const seat of seats) {
        if (seat.status !== 'ACTIVE') continue;
        const meta = getSeatShape(seat);
        if (meta.shape === '4dx' || meta.shape === 'imax' || meta.shape === 'recliner') {
          invalidSeatKeys.add(`${seat.row}${seat.number}`);
          errors.push(`${roomType} auditoriums only permit STANDARD, VIP, SWEETBOX, and WHEELCHAIR seats. Seat [${seat.row}${seat.number}] (${meta.name}) is invalid.`);
        }
        if (meta.shape === 'sweetbox' && seat.row !== lastRow) {
          invalidSeatKeys.add(`${seat.row}${seat.number}`);
          errors.push(`Sweetbox couple seats may only be positioned in the back row (${lastRow}) in ${roomType} auditoriums. Seat [${seat.row}${seat.number}] is invalid.`);
        }
      }
    }

    const uniqueErrors = Array.from(new Set(errors));
    return { valid: uniqueErrors.length === 0, errors: uniqueErrors, invalidSeatKeys };
  }

  function updateRoomDimensions(rows, cols) {
    const r = Math.max(1, Number(rows) || 1);
    const c = Math.max(1, Number(cols) || 1);
    const area = r * c;

    const dimRoomSize = get('dimRoomSize');
    if (dimRoomSize) dimRoomSize.textContent = `${c}m × ${r}m`;

    const dimRoomLength = get('dimRoomLength');
    if (dimRoomLength) dimRoomLength.textContent = `${c} m`;

    const dimRoomLengthCols = get('dimRoomLengthCols');
    if (dimRoomLengthCols) dimRoomLengthCols.textContent = `(${c} cols × 1m)`;

    const dimRoomWidth = get('dimRoomWidth');
    if (dimRoomWidth) dimRoomWidth.textContent = `${r} m`;

    const dimRoomWidthRows = get('dimRoomWidthRows');
    if (dimRoomWidthRows) dimRoomWidthRows.textContent = `(${r} rows × 1m)`;

    const dimRoomArea = get('dimRoomArea');
    if (dimRoomArea) dimRoomArea.textContent = `${area} m²`;

    const canvasRulerLength = get('canvasRulerLength');
    if (canvasRulerLength) canvasRulerLength.textContent = `Length: ${c}m (${c} columns)`;

    const canvasFooterSummary = get('canvasFooterSummary');
    if (canvasFooterSummary) {
      canvasFooterSummary.textContent = `Dimensions: ${c}m × ${r}m · Length: ${c}m · Width: ${r}m · Floor Area: ${area} m² (1 cell = 1m × 1m)`;
    }
  }

  function renderSeatGrid() {
    const container = get('adminSeatRowsContainer');
    if (!container) return;
    container.replaceChildren();

    const currentRoom = state.rooms?.find(item => item.id === seatRoom);
    const roomType = currentRoom?.roomType || '2D Standard';
    const validation = validateRoomSeatLayout(roomType, seatDraft);

    // Group seats by row (A, B, C...)
    const rowMap = new Map();
    for (const seat of seatDraft) {
      if (!rowMap.has(seat.row)) rowMap.set(seat.row, []);
      rowMap.get(seat.row).push(seat);
    }

    const sortedRows = Array.from(rowMap.keys()).sort();

    const activeTypeCounts = new Map();
    let unavailableCount = 0;

    for (const rowKey of sortedRows) {
      // Ensure col is preserved and sort strictly by physical column
      const seatsInRow = rowMap.get(rowKey);
      seatsInRow.forEach((s, idx) => { if (s.col === undefined || s.col === null) s.col = s.number || (idx + 1); });
      seatsInRow.sort((a, b) => a.col - b.col);
      // Compute active seat sequence (excluding unavailable / aisle slots)
      let activeSeq = 0;
      for (const s of seatsInRow) {
        if (s.status === 'ACTIVE') {
          activeSeq++;
          s.activeNumber = activeSeq;
          s.seatLabel = `${rowKey}${activeSeq}`;
        } else {
          s.activeNumber = null;
          s.seatLabel = `${rowKey}×`;
        }
      }
      const rowWrapper = element('div', '', 'admin-seat-row-wrapper');

      // Left Row Label
      rowWrapper.append(element('div', rowKey, 'admin-seat-row-label'));

      // Seats Container
      const seatsRow = element('div', '', 'admin-seat-row-seats');

      for (const seat of seatsInRow) {
        const typeObj = (state.pricing || []).find(item => item.id === seat.typeId);
        const typeName = typeObj ? typeObj.typeName : 'Standard';
        const typeUpper = typeName.toUpperCase();
        const shapeType = typeObj?.shapeType || (typeUpper.includes('SWEETBOX') ? 'sweetbox' : (typeUpper.includes('VIP') ? 'vip' : (typeUpper.includes('4DX') ? '4dx' : (typeUpper.includes('IMAX') ? 'imax' : (typeUpper.includes('RECLINER') ? 'recliner' : (typeUpper.includes('WHEELCHAIR') ? 'wheelchair' : 'standard'))))));

        if (seat.status === 'ACTIVE') {
          activeTypeCounts.set(typeName, (activeTypeCounts.get(typeName) || 0) + 1);
        } else {
          unavailableCount++;
        }

        let variant = 'full';
        const button = element('button', '', 'admin-matrix-seat');
        button.type = 'button';
        button.dataset.type = typeUpper;
        button.dataset.status = seat.status;
        button.dataset.shape = shapeType;
        button.classList.add(`shape-${shapeType}`);

        if (shapeType === 'sweetbox' && seat.pairId) {
          const partner = seatsInRow.find(s => s !== seat && s.pairId === seat.pairId);
          const isLeft = partner ? ((seat.col || seat.number) < (partner.col || partner.number)) : ((seat.col || seat.number) % 2 !== 0);
          variant = isLeft ? 'left' : 'right';
          button.classList.add(isLeft ? 'pair-left' : 'pair-right');
        }

        const svgWrap = element('div', '', 'seat-svg-wrap');
        svgWrap.innerHTML = getSeatSvg(shapeType, variant);
        const isUnavail = seat.status === 'INACTIVE' || seat.status === 'UNAVAILABLE';
        const displayText = isUnavail ? '×' : (seat.seatLabel || `${seat.row}${seat.activeNumber || ''}`);
        const numSpan = element('span', displayText, 'seat-num');
        button.append(svgWrap, numSpan);

        const seatKey = `${seat.row}_${seat.col || seat.number}`;
        if (validation.invalidSeatKeys.has(seatKey) || validation.invalidSeatKeys.has(`${seat.row}${seat.number}`)) {
          button.classList.add('is-invalid-seat');
        }

        button.title = seat.status === 'ACTIVE'
          ? `Row ${seat.row} - Seat ${seat.activeNumber} (${seat.seatLabel}) · ${typeName}`
          : `Row ${seat.row} - Unavailable / Aisle (×)`;
        button.setAttribute('aria-label', seat.status === 'ACTIVE' ? `${seat.seatLabel}, ${typeName}` : `${seat.row} Aisle`);

        button.addEventListener('click', () => {
          recordHistory();
          const paint = get('adminSeatPaint').value;
          const chosenType = (state.pricing || []).find(p => p.id === paint);
          const isSweetbox = chosenType && (chosenType.shapeType === 'sweetbox' || (chosenType.typeName || '').toUpperCase().includes('SWEETBOX') || (chosenType.typeName || '').toUpperCase().includes('COUPLE'));

          if (paint === 'blocked') {
            seat.status = 'INACTIVE';
            if (seat.pairId) {
              const partner = seatsInRow.find(s => s !== seat && s.pairId === seat.pairId);
              if (partner) partner.pairId = null;
              seat.pairId = null;
            }
          } else if (isSweetbox) {
            seat.typeId = paint;
            seat.status = 'ACTIVE';

            // Auto-pair with adjacent neighbor on same row
            const sCol = seat.col || seat.number;
            const nextSeat = seatsInRow.find(s => (s.col || s.number) === sCol + 1);
            const prevSeat = seatsInRow.find(s => (s.col || s.number) === sCol - 1);
            let partner = null;
            if (nextSeat) partner = nextSeat;
            else if (prevSeat) partner = prevSeat;

            if (partner) {
              partner.typeId = paint;
              partner.status = 'ACTIVE';
              const pCol = partner.col || partner.number;
              const minNum = Math.min(sCol, pCol);
              const maxNum = Math.max(sCol, pCol);
              const newPairId = `${seat.row}${minNum}-${seat.row}${maxNum}`;
              seat.pairId = newPairId;
              partner.pairId = newPairId;
            } else {
              seat.pairId = null;
            }
          } else {
            if (seat.pairId) {
              const partner = seatsInRow.find(s => s !== seat && s.pairId === seat.pairId);
              if (partner) partner.pairId = null;
              seat.pairId = null;
            }
            seat.typeId = paint;
            seat.status = 'ACTIVE';
          }
          seatDirty = true;
          renderSeatGrid();
        });

        button.addEventListener('contextmenu', (e) => {
          e.preventDefault();
          recordHistory();
          const types = [...(state.pricing || []).map(p => p.id), 'blocked'];
          const currentIdx = (seat.status === 'INACTIVE' || seat.status === 'UNAVAILABLE') ? types.indexOf('blocked') : types.indexOf(seat.typeId);
          const nextIdx = (currentIdx + 1) % types.length;
          const nextVal = types[nextIdx];
          if (nextVal === 'blocked') {
            seat.status = 'INACTIVE';
            if (seat.pairId) {
              const partner = seatsInRow.find(s => s !== seat && s.pairId === seat.pairId);
              if (partner) partner.pairId = null;
              seat.pairId = null;
            }
          } else {
            seat.typeId = nextVal;
            seat.status = 'ACTIVE';
          }
          seatDirty = true;
          renderSeatGrid();
        });

        seatsRow.append(button);
      }

      rowWrapper.append(seatsRow);

      // Right Row Label
      rowWrapper.append(element('div', rowKey, 'admin-seat-row-label'));

      container.append(rowWrapper);
    }

    let activeTotal = 0;
    activeTypeCounts.forEach(c => activeTotal += c);
    const breakdownParts = [];
    activeTypeCounts.forEach((count, name) => breakdownParts.push(`${count} ${name}`));
    if (unavailableCount > 0) {
      breakdownParts.push(`${unavailableCount} Unavailable/Aisles`);
    }

    get('adminSeatCount').textContent = `${activeTotal} active / ${seatDraft.length} total cells`;
    get('adminSeatBreakdown').textContent = `(${breakdownParts.join(' · ')})`;
    get('adminSeatDirty').textContent = seatDirty ? 'Unsaved changes' : 'Saved to database';
    get('adminSeatDirty').className = seatDirty ? 'badge bg-warning text-dark' : 'badge bg-success';

    // Update Validation Alert UI
    const alertBox = get('adminSeatValidationAlert');
    if (alertBox) {
      if (!validation.valid) {
        alertBox.hidden = false;
        alertBox.className = 'admin-seat-validation-box mb-3';
        alertBox.innerHTML = `
          <div class="admin-validation-header">
            <span>⚠️</span> Layout Warning for ${roomType} (${validation.errors.length} invalid positions)
          </div>
          <ul class="admin-validation-list">
            ${validation.errors.map(err => `<li>${err}</li>`).join('')}
          </ul>
        `;
      } else {
        alertBox.hidden = true;
        alertBox.replaceChildren();
      }
    }

    get('adminSaveSeats').disabled = !seatDraft.length || (!seatDirty && !!state.layouts[seatRoom]) || !validation.valid;
    if (!validation.valid) {
      get('adminSaveSeats').title = 'Cannot save: Please resolve layout validation errors before saving.';
    } else {
      get('adminSaveSeats').removeAttribute('title');
    }

    renderAdminSeatLegend();

    let maxCols = 0;
    for (const s of seatDraft) {
      const c = Number(s.col || s.number || 0);
      if (c > maxCols) maxCols = c;
    }
    if (!maxCols) maxCols = Number(get('adminSeatColumns')?.value) || 10;
    const rowCount = sortedRows.length || Number(get('adminSeatRows')?.value) || 6;
    updateRoomDimensions(rowCount, maxCols);
  }

  const CUSTOM_PALETTE = [
    '#10b981', '#06b6d4', '#f43f5e', '#6366f1', '#f97316', '#14b8a6', '#d946ef',
    '#84cc16', '#0ea5e9', '#eab308', '#ec4899', '#8b5cf6', '#22c55e', '#38bdf8', '#fb7185', '#e11d48'
  ];

  function getCustomSeatTypesList() {
    return (state.pricing || []).filter(p => {
      const u = (p.typeName || '').toUpperCase();
      return !['STANDARD', 'VIP', 'SWEETBOX', 'COUPLE'].includes(u)
        && !u.includes('PAIR') && !u.includes('LOVE') && !u.includes('LOUNGE') && !u.includes('BED');
    });
  }

  function getSeatTypeColor(typeUpperOrId, customIndex = 0) {
    let found = (state.pricing || []).find(p => p.id === String(typeUpperOrId) || (p.typeName && p.typeName.toUpperCase() === String(typeUpperOrId).toUpperCase()));
    if (found && found.colorCode && found.colorCode.startsWith('#')) {
      const hex = found.colorCode.trim();
      return { color: hex, border: hex, bg: hexToRgba(hex, 0.16) };
    }

    const norm = String(typeUpperOrId || '').toUpperCase();
    if (norm === 'STANDARD') {
      return { color: '#93c5fd', border: '#3b82f6', bg: '#0f172a' };
    }
    if (norm === 'VIP') {
      return { color: '#fcd34d', border: '#f59e0b', bg: '#1c1507' };
    }
    if (norm === 'SWEETBOX' || norm === 'COUPLE' || norm.includes('PAIR')) {
      return { color: '#e9d5ff', border: '#a855f7', bg: '#1e112a' };
    }
    if (norm.includes('LOUNGE') || norm.includes('BED')) {
      return { color: '#6ee7b7', border: '#10b981', bg: '#062419' };
    }
    if (norm.includes('LOVE')) {
      return { color: '#f472b6', border: '#ec4899', bg: '#260a1c' };
    }
    const hex = CUSTOM_PALETTE[Math.abs(customIndex) % CUSTOM_PALETTE.length];
    return { color: hex, border: hex, bg: hexToRgba(hex, 0.16) };
  }

  function renderAdminSeatLegend() {
    const box = get('adminSeatLegendBox');
    if (!box) return;
    box.replaceChildren();

    (state.pricing || []).forEach(pt => {
      const item = element('div', '', 'legend-item');
      const shapeType = pt.shapeType || getSeatShapeFromType(pt.typeName);
      const iconCls = `admin-shape-legend-icon shape-${shapeType}${shapeType === 'sweetbox' ? ' legend-icon-sweetbox' : ''}`;
      const icon = element('div', '', iconCls);
      icon.innerHTML = getSeatSvg(shapeType);
      item.append(icon, document.createTextNode(' ' + pt.typeName));
      box.append(item);
    });

    const unavItem = element('div', '', 'legend-item');
    const unavIcon = element('span', '×', 'admin-shape-legend-icon shape-blocked');
    unavItem.append(unavIcon, document.createTextNode(' Unavailable / Aisle (×)'));
    box.append(unavItem);
  }

  function updateSeatPaintPalette(roomType = '') {
    const paintSelect = get('adminSeatPaint');
    const paletteContainer = get('adminSeatPaintPalette');
    if (!paintSelect) return;

    const norm = String(roomType || '').toUpperCase();
    let allowedTypes = state.pricing || [];

    if (norm.includes('4DX') || norm.includes('4D')) {
      // 4DX: SEAT_4DX ONLY (no wheelchair, vip, sweetbox)
      allowedTypes = allowedTypes.filter(p => {
        const u = (p.typeName || '').toUpperCase();
        return u.includes('4DX') || p.shapeType === '4dx';
      });
    } else if (norm.includes('GOLD')) {
      // Gold Class: 100% RECLINER + WHEELCHAIR
      allowedTypes = allowedTypes.filter(p => {
        const u = (p.typeName || '').toUpperCase();
        return u.includes('RECLINER') || u.includes('WHEELCHAIR') || p.shapeType === 'recliner' || p.shapeType === 'wheelchair';
      });
    } else if (norm.includes('IMAX')) {
      // IMAX Laser: ONLY IMAX_SEAT and WHEELCHAIR!
      allowedTypes = allowedTypes.filter(p => {
        const u = (p.typeName || '').toUpperCase();
        return u.includes('IMAX') || u.includes('WHEELCHAIR') || p.shapeType === 'imax' || p.shapeType === 'wheelchair';
      });
    } else {
      // 2D Standard / 3D Digital / 2D Dolby Atmos: STANDARD, VIP, SWEETBOX, WHEELCHAIR
      allowedTypes = allowedTypes.filter(p => {
        const u = (p.typeName || '').toUpperCase();
        return !u.includes('4DX') && !u.includes('IMAX') && !u.includes('RECLINER') && p.shapeType !== '4dx' && p.shapeType !== 'imax' && p.shapeType !== 'recliner';
      });
    }

    paintSelect.replaceChildren();
    for (const type of allowedTypes) {
      const option = element('option', `${type.typeName} (+${formatMoney(type.priceSurcharge)})`);
      option.value = type.id;
      paintSelect.append(option);
    }
    const blocked = element('option', '✖ Unavailable / Aisle');
    blocked.value = 'blocked';
    paintSelect.append(blocked);

    if (paletteContainer) {
      paletteContainer.replaceChildren();
      const createPill = (label, val, shapeType) => {
        const pill = element('button', '', 'admin-paint-pill');
        pill.type = 'button';
        pill.dataset.paintValue = val;
        if (paintSelect.value === val) pill.classList.add('active');
        if (val === 'blocked') {
          const icon = element('span', '×', 'admin-shape-pill-icon shape-blocked');
          pill.append(icon, document.createTextNode(' ' + label));
        } else {
          const iconCls = `admin-shape-pill-icon shape-${shapeType}${shapeType === 'sweetbox' ? ' pill-icon-sweetbox' : ''}`;
          const icon = element('div', '', iconCls);
          icon.innerHTML = getSeatSvg(shapeType);
          pill.append(icon, document.createTextNode(' ' + label));
        }
        pill.addEventListener('click', () => {
          paintSelect.value = val;
          paletteContainer.querySelectorAll('.admin-paint-pill').forEach(p => p.classList.remove('active'));
          pill.classList.add('active');
        });
        return pill;
      };
      allowedTypes.forEach(type => {
        const shapeType = type.shapeType || getSeatShapeFromType(type);
        paletteContainer.append(createPill(type.typeName, type.id, shapeType));
      });
      paletteContainer.append(createPill('Unavailable (×)', 'blocked', 'blocked'));
    }
  }

  function cleanRoomLabel(room) {
    if (!room) return '';
    const name = String(room.roomName || '').trim();
    const type = String(room.roomType || '').trim();
    if (!type || name.toUpperCase().includes(type.toUpperCase())) return name;
    return `${name} (${type})`;
  }

  function loadSeatRoom(id) {
    seatRoom = id;
    undoStack = [];
    redoStack = [];
    updateUndoRedoButtons();
    if (get('adminSeatRoom')) get('adminSeatRoom').value = id;
    const room = state.rooms?.find(item => item.id === id);
    const cinema = state.cinemas?.find(c => c.id === room?.cinemaId);
    updateSeatPaintPalette(room?.roomType || '');
    seatDraft = room ? structuredClone(state.layouts[id] || createSeatLayout(8, 10, state.pricing[0]?.id)) : [];
    seatDraft.forEach((s, idx) => { if (s.col === undefined || s.col === null) s.col = s.number || (idx + 1); });
    get('adminSeatTitle').textContent = room ? `${cleanRoomLabel(room)} · ${cinema ? cinema.cinemaName : ''} — Seat Matrix` : 'Select a room';
    get('adminSeatRows').value = new Set(seatDraft.map(seat => seat.row)).size || 8;
    get('adminSeatColumns').value = seatDraft.length ? Math.max(...seatDraft.map(seat => seat.number)) : 10;
    seatDirty = false;
    get('adminGenerateSeats').disabled = !room || !state.pricing.length;
    renderSeatGrid();
  }

  function updateRoomDropdown(cinemaId, targetRoomId = '') {
    const roomSelect = get('adminSeatRoom');
    if (!roomSelect) return;
    roomSelect.replaceChildren();
    const rooms = (state.rooms || []).filter(r => r.cinemaId === cinemaId);
    if (!rooms.length) {
      const emptyOpt = element('option', 'No rooms in this cinema');
      emptyOpt.value = '';
      roomSelect.append(emptyOpt);
      loadSeatRoom('');
      return;
    }
    for (const room of rooms) {
      const opt = element('option', cleanRoomLabel(room));
      opt.value = room.id;
      roomSelect.append(opt);
    }
    const chosenRoomId = rooms.some(r => r.id === targetRoomId) ? targetRoomId : rooms[0].id;
    roomSelect.value = chosenRoomId;
    loadSeatRoom(chosenRoomId);
  }

  get('adminSeatCinema')?.addEventListener('change', () => {
    const nextCinema = get('adminSeatCinema').value;
    if (seatDirty) {
      confirm('Discard unsaved layout?', 'Changing cinemas will discard the unsaved layout.', 'Discard and switch', () => {
        updateRoomDropdown(nextCinema);
      });
    } else {
      updateRoomDropdown(nextCinema);
    }
  });

  get('adminSeatRoom')?.addEventListener('change', () => {
    const next = get('adminSeatRoom').value;
    if (seatDirty) {
      confirm('Discard unsaved layout?', 'Changing rooms will discard the unsaved layout.', 'Discard and switch', () => loadSeatRoom(next));
    } else {
      loadSeatRoom(next);
    }
  });

  get('adminGenerateSeats').addEventListener('click', () => {
    const rows = Number(get('adminSeatRows').value);
    const cols = Number(get('adminSeatColumns').value);
    const layout = createSeatLayout(rows, cols, state.pricing[0]?.id, 'UNAVAILABLE');
    if (!layout.length) { notify('Use 1–16 rows and 1–20 seats per row.'); return; }
    confirm('Generate new grid?', `Generate new ${rows}×${cols} grid with all seats set to Unavailable (×) to avoid standard violations?`, 'Generate Grid', () => {
      recordHistory();
      for (const seat of layout) {
        seat.status = 'UNAVAILABLE';
        seat.pairId = null;
      }
      seatDraft = layout;
      seatDirty = true;
      renderSeatGrid();
      notify(`Generated ${rows}×${cols} grid with all positions set to Unavailable.`);
    });
  });

  get('adminSeatRows')?.addEventListener('input', () => {
    updateRoomDimensions(get('adminSeatRows')?.value, get('adminSeatColumns')?.value);
  });
  get('adminSeatColumns')?.addEventListener('input', () => {
    updateRoomDimensions(get('adminSeatRows')?.value, get('adminSeatColumns')?.value);
  });

  // Preset: Apply Room Standard Layout
  get('btnPresetStandardLayout')?.addEventListener('click', () => {
    if (!seatDraft.length || !seatRoom) return;
    recordHistory();
    const currentRoom = state.rooms?.find(r => r.id === seatRoom);
    const roomType = currentRoom?.roomType || '2D Standard';
    const norm = roomType.toUpperCase();

    const stdType = state.pricing.find(t => t.shapeType === 'standard' || t.typeName.toUpperCase() === 'STANDARD');
    const vipType = state.pricing.find(t => t.shapeType === 'vip' || t.typeName.toUpperCase() === 'VIP');
    const sbType = state.pricing.find(t => t.shapeType === 'sweetbox' || t.typeName.toUpperCase() === 'SWEETBOX');
    const imaxType = state.pricing.find(t => t.shapeType === 'imax' || t.typeName.toUpperCase().includes('IMAX'));
    const fourDxType = state.pricing.find(t => t.shapeType === '4dx' || t.typeName.toUpperCase().includes('4DX'));
    const reclinerType = state.pricing.find(t => t.shapeType === 'recliner' || t.typeName.toUpperCase().includes('RECLINER'));
    const wheelchairType = state.pricing.find(t => t.shapeType === 'wheelchair' || t.typeName.toUpperCase().includes('WHEELCHAIR'));

    const rows = Array.from(new Set(seatDraft.map(s => s.row))).sort();
    const firstRow = rows[0];
    const lastRow = rows[rows.length - 1];

    if (norm.includes('4DX') || norm.includes('4D')) {
      for (const seat of seatDraft) {
        seat.status = 'ACTIVE';
        seat.typeId = fourDxType?.id || stdType?.id;
        seat.pairId = null;
      }
    } else if (norm.includes('GOLD')) {
      for (const seat of seatDraft) {
        seat.status = 'ACTIVE';
        seat.pairId = null;
        if (seat.row === firstRow && (seat.number === 1 || seat.number === 2) && wheelchairType) {
          seat.typeId = wheelchairType.id;
        } else {
          seat.typeId = reclinerType?.id || vipType?.id;
        }
      }
    } else if (norm.includes('IMAX')) {
      const maxCol = Math.max(...seatDraft.map(s => s.number));
      for (const seat of seatDraft) {
        seat.status = 'ACTIVE';
        seat.pairId = null;
        if (seat.row === firstRow && (seat.number === 1 || seat.number === maxCol) && wheelchairType) {
          seat.typeId = wheelchairType.id;
        } else {
          seat.typeId = imaxType?.id || stdType?.id;
        }
      }
    } else {
      const midStart = Math.floor(rows.length / 3);
      const midEnd = Math.max(midStart, rows.length - 2);
      const vipRows = rows.slice(midStart, midEnd + 1);

      for (const seat of seatDraft) {
        seat.status = 'ACTIVE';
        seat.pairId = null;
        if (seat.row === firstRow && (seat.number === 1 || seat.number === 2) && wheelchairType) {
          seat.typeId = wheelchairType.id;
        } else if (seat.row === lastRow && sbType) {
          seat.typeId = sbType.id;
        } else if (vipRows.includes(seat.row) && vipType) {
          seat.typeId = vipType.id;
        } else {
          seat.typeId = stdType?.id;
        }
      }
      const lastRowSeats = seatDraft.filter(s => s.row === lastRow).sort((a, b) => a.number - b.number);
      for (let i = 0; i < lastRowSeats.length; i += 2) {
        if (i + 1 < lastRowSeats.length) {
          const s1 = lastRowSeats[i];
          const s2 = lastRowSeats[i + 1];
          const pairId = `${lastRow}${s1.number}-${lastRow}${s2.number}`;
          s1.pairId = pairId;
          s2.pairId = pairId;
        }
      }
    }

    seatDirty = true;
    renderSeatGrid();
    notify(`Applied 100% compliant layout for ${roomType}.`);
  });

  // Preset: Reset All to Unavailable
  get('btnPresetReset')?.addEventListener('click', () => {
    if (!seatDraft.length) return;
    recordHistory();
    for (const seat of seatDraft) {
      seat.status = 'UNAVAILABLE';
      seat.pairId = null;
    }
    seatDirty = true;
    renderSeatGrid();
    notify('Reset all seats to Unavailable.');
  });

  get('adminSaveSeats').addEventListener('click', async () => {
    if (!seatRoom || !seatDraft.length) return;
    const currentRoom = state.rooms?.find(r => r.id === seatRoom);
    const roomType = currentRoom?.roomType || '2D Standard';
    const validation = validateRoomSeatLayout(roomType, seatDraft);
    if (!validation.valid) {
      notify('Invalid seat layout. Please resolve warning errors before saving!');
      return;
    }
    state.layouts[seatRoom] = structuredClone(seatDraft); seatDirty = false;
    try {
      const res = await win.fetch(`/admin/api/seats/${seatRoom}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(seatDraft)
      });
      const data = await res.json();
      if (!res.ok || data.success === false) {
        notify(data.error || 'Failed to save layout to database.');
        return;
      }
      notify('Seat matrix successfully saved to MySQL database!');
    } catch (e) {
      notify('Saved locally.');
    }
    renderSeatGrid();
  });

  // Undo / Redo Controls & Keyboard Shortcuts for Seat Designer
  get('adminUndoSeats')?.addEventListener('click', () => undoSeatAction());
  get('adminRedoSeats')?.addEventListener('click', () => redoSeatAction());

  win.addEventListener('keydown', (e) => {
    if (get('adminSeats')?.hidden) return;
    const tag = e.target?.tagName?.toLowerCase();
    if (tag === 'input' || tag === 'textarea' || tag === 'select') return;

    if ((e.ctrlKey || e.metaKey) && !e.altKey) {
      if (e.key === 'z' || e.key === 'Z') {
        if (e.shiftKey) {
          e.preventDefault();
          redoSeatAction();
        } else {
          e.preventDefault();
          undoSeatAction();
        }
      } else if (e.key === 'y' || e.key === 'Y') {
        e.preventDefault();
        redoSeatAction();
      }
    }
  });

  win.addEventListener('beforeunload', event => { if (section === 'seats' && seatDirty) { event.preventDefault(); event.returnValue = ''; } });

  let bookingPage = 1;
  const bookingPageSize = 8;

  function renderBookings() {
    const allBookings = state.bookings || [];

    // 1. Always populate Cinema Filter Dropdown with live cinemas from MySQL
    const cinemaFilter = get('adminBookingCinemaFilter');
    if (cinemaFilter) {
      const cur = cinemaFilter.value;
      cinemaFilter.replaceChildren();
      const allOpt = element('option', 'All Cinemas');
      allOpt.value = '';
      cinemaFilter.append(allOpt);

      // Prefer real cinemas with numeric IDs from live database
      const realCinemas = (state.cinemas || []).filter(c => !String(c.id).startsWith('c'));
      const list = realCinemas.length ? realCinemas : (state.cinemas || []);
      for (const cinema of list) {
        const opt = element('option', cinema.cinemaName);
        opt.value = cinema.id;
        cinemaFilter.append(opt);
      }
      cinemaFilter.value = cur;
    }

    // 2. Compute KPI Metrics across all bookings & render clean cards
    const confirmedList = allBookings.filter(b => b.bookingStatus === 'CONFIRMED' || b.bookingStatus === 'PAID');
    const cancelledList = allBookings.filter(b => b.bookingStatus === 'CANCELLED' || b.bookingStatus === 'REFUNDED');
    const totalTicketsSold = confirmedList.reduce((acc, b) => acc + (b.ticketCount || 1), 0);
    const totalGrossRevenue = confirmedList.reduce((acc, b) => acc + (Number(b.totalAmount) || 0), 0);

    const metricsContainer = get('adminBookingMetrics');
    if (metricsContainer) {
      metricsContainer.replaceChildren();
      const kpis = [
        { label: 'Total Bookings', value: String(allBookings.length), note: 'All transactions' },
        { label: 'Tickets Sold', value: totalTicketsSold.toLocaleString('en-US'), note: 'Confirmed tickets' },
        { label: 'Gross Revenue', value: formatMoney(totalGrossRevenue), note: 'Net of refunds' },
        { label: 'Confirmed / Cancelled', value: `${confirmedList.length} / ${cancelledList.length}`, note: 'Confirmed vs Cancelled' }
      ];
      for (const k of kpis) {
        const card = element('div', '', 'admin-metric');
        card.append(element('p', k.label), element('strong', k.value), element('small', k.note));
        metricsContainer.append(card);
      }
    }

    // 3. Filter Bookings by Search, Cinema, Status
    const query = (get('adminBookingSearch')?.value || '').trim().toLowerCase();
    const selCinema = get('adminBookingCinemaFilter')?.value || '';
    const selStatus = get('adminBookingStatusFilter')?.value || '';

    const filtered = allBookings.filter(b => {
      if (selCinema && String(b.cinemaId) !== selCinema) return false;
      if (selStatus && String(b.bookingStatus).toUpperCase() !== selStatus) return false;
      if (query) {
        const text = `${b.bookingCode} ${b.customerName} ${b.customerPhone} ${b.customerEmail} ${b.movieTitle} ${b.cinemaName} ${b.roomName}`.toLowerCase();
        if (!text.includes(query)) return false;
      }
      return true;
    });

    // 4. Pagination
    const totalPages = Math.max(1, Math.ceil(filtered.length / bookingPageSize));
    bookingPage = Math.min(totalPages, Math.max(1, bookingPage));
    const startIdx = (bookingPage - 1) * bookingPageSize;
    const pageItems = filtered.slice(startIdx, startIdx + bookingPageSize);

    if (get('adminBookingPageInfo')) {
      get('adminBookingPageInfo').textContent = `Showing ${filtered.length ? startIdx + 1 : 0} – ${Math.min(filtered.length, startIdx + bookingPageSize)} of ${filtered.length} bookings`;
    }
    if (get('adminBookingPrevious')) get('adminBookingPrevious').disabled = bookingPage <= 1;
    if (get('adminBookingNext')) get('adminBookingNext').disabled = bookingPage >= totalPages;

    const tbody = get('adminBookingTableBody');
    if (!tbody) return;
    tbody.replaceChildren();

    if (!pageItems.length) {
      if (get('adminBookingEmpty')) get('adminBookingEmpty').hidden = false;
      return;
    }
    if (get('adminBookingEmpty')) get('adminBookingEmpty').hidden = true;

    for (const b of pageItems) {
      const tr = element('tr');

      // 1. Code & Date
      const tdCode = element('td');
      tdCode.innerHTML = `<span style="font-family: monospace; font-weight: 700; color: #ef4444; font-size: 13px;">${b.bookingCode}</span><br><small style="color: var(--box-text-muted);">${b.bookedAt || '—'}</small>`;

      // 2. Movie & Showtime
      const tdMovie = element('td');
      tdMovie.innerHTML = `<div style="font-weight: 600; color: var(--box-text-primary);">${b.movieTitle} <span class="badge-age-${String(b.ageRating || 'p').toLowerCase()}" style="font-size: 10px; padding: 1px 5px;">${b.ageRating || 'P'}</span></div>` +
        `<small style="color: #38bdf8; font-weight: 600;">⏰ ${b.showtimeStart || '—'}</small><br>` +
        `<small style="color: var(--box-text-muted);">${b.roomName} (${b.roomType})</small>`;

      // 3. Cinema
      const tdCinema = element('td');
      tdCinema.innerHTML = `<strong>${b.cinemaName}</strong><br><small style="color: var(--box-text-muted);">${b.city}</small>`;

      // 4. Customer
      const tdCust = element('td');
      tdCust.innerHTML = `<strong>${b.customerName}</strong><br><small style="color: var(--box-text-muted);">${b.customerPhone || b.customerEmail || '—'}</small><br><span style="font-size: 10px; color: var(--box-text-muted); text-transform: uppercase;">[${b.bookingChannel}]</span>`;

      // 5. Seats & Combos
      const tdItems = element('td');
      let itemsHtml = `<div><span style="display: inline-block; padding: 2px 7px; border-radius: 4px; background: rgba(255,255,255,0.08); font-weight: 700; color: #fbbf24;">${b.ticketCount} tickets: ${b.seatsSummary || '—'}</span></div>`;
      if (b.combosDetail) {
        itemsHtml += `<small style="color: #a855f7; display: block; margin-top: 3px;">🍿 ${b.combosDetail}</small>`;
      }
      tdItems.innerHTML = itemsHtml;

      // 6. Total Amount & Payment
      const tdAmount = element('td');
      tdAmount.innerHTML = `<span style="font-weight: 700; color: var(--box-accent); font-size: 14px;">${formatMoney(b.totalAmount)}</span><br><small style="color: var(--box-text-muted);">${b.paymentMethod || 'VNPAY'} · ${b.paymentProvider || 'QR'}</small>`;

      // 7. Status
      const tdStatus = element('td');
      const st = String(b.bookingStatus).toUpperCase();
      let badgeClass = 'badge-status-confirmed';
      if (st === 'CANCELLED') badgeClass = 'badge-status-cancelled';
      else if (st === 'REFUNDED') badgeClass = 'badge-status-refunded';
      else if (st === 'HOLDING') badgeClass = 'badge-status-holding';
      tdStatus.innerHTML = `<span class="admin-badge ${badgeClass}">${st}</span>`;

      // 8. Action: View Invoice
      const tdAction = element('td');
      const btnInvoice = element('button', '📄 Invoice', 'admin-button');
      btnInvoice.style.padding = '5px 10px';
      btnInvoice.style.fontSize = '12px';
      btnInvoice.onclick = () => openInvoiceModal(b);
      tdAction.append(btnInvoice);

      tr.append(tdCode, tdMovie, tdCinema, tdCust, tdItems, tdAmount, tdStatus, tdAction);
      tbody.append(tr);
    }
  }

  function openInvoiceModal(b) {
    const modal = get('adminInvoiceModal');
    if (!modal) return;

    if (get('adminInvoiceSub')) {
      get('adminInvoiceSub').textContent = `Order Code: #${b.bookingCode} · Booked Date: ${b.bookedAt || '—'}`;
    }

    const content = get('adminInvoiceContent');
    const st = String(b.bookingStatus).toUpperCase();
    let badgeClass = 'badge-status-confirmed';
    if (st === 'CANCELLED') badgeClass = 'badge-status-cancelled';
    else if (st === 'REFUNDED') badgeClass = 'badge-status-refunded';
    else if (st === 'HOLDING') badgeClass = 'badge-status-holding';

    content.innerHTML = `
      <!-- Banner -->
      <div class="admin-invoice-banner">
        <div>
          <div style="font-size: 16px; font-weight: 700; color: #ef4444; font-family: monospace;">ORDER: ${b.bookingCode}</div>
          <div style="font-size: 12px; color: var(--box-text-muted); margin-top: 2px;">Channel: <strong>${b.bookingChannel}</strong> · Transaction Date: ${b.bookedAt || '—'}</div>
        </div>
        <div>
          <span class="admin-badge ${badgeClass}" style="font-size: 12px; padding: 5px 12px;">${st}</span>
        </div>
      </div>

      <!-- Customer & Screening Grid -->
      <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 14px;">
        <div class="admin-invoice-section">
          <div class="admin-invoice-section-title">👤 Customer Information</div>
          <div><strong>${b.customerName}</strong></div>
          <div style="color: var(--box-text-muted);">Phone: <strong>${b.customerPhone || '—'}</strong></div>
          <div style="color: var(--box-text-muted);">Email: ${b.customerEmail || '—'}</div>
        </div>
        <div class="admin-invoice-section">
          <div class="admin-invoice-section-title">🎬 Screening Information</div>
          <div><strong>${b.movieTitle}</strong> (${b.roomType})</div>
          <div style="color: #38bdf8; font-weight: 600;">⏰ ${b.showtimeStart || '—'}</div>
          <div style="color: var(--box-text-muted);">Cinema: ${b.cinemaName} · ${b.roomName}</div>
        </div>
      </div>

      <!-- Line Items Section -->
      <div class="admin-invoice-section">
        <div class="admin-invoice-section-title">📋 Invoice Line Items</div>
        <table class="admin-invoice-table">
          <thead>
            <tr>
              <th>Item</th>
              <th>Details</th>
              <th>Quantity</th>
              <th style="text-align: right;">Amount</th>
            </tr>
          </thead>
          <tbody>
            <tr>
              <td><strong>Movie Tickets</strong></td>
              <td>${b.seatsDetail || b.seatsSummary || 'Standard Seat'}</td>
              <td>${b.ticketCount} tickets</td>
              <td style="text-align: right; font-weight: 600;">${formatMoney(b.subtotal || b.totalAmount)}</td>
            </tr>
            ${b.combosDetail ? `
            <tr>
              <td><strong>Concessions &amp; Combo</strong></td>
              <td>${b.combosDetail}</td>
              <td>1 pack</td>
              <td style="text-align: right; font-weight: 600;">Included</td>
            </tr>` : ''}
          </tbody>
        </table>

        <!-- Totals summary -->
        <div style="margin-top: 14px; padding-top: 10px; border-top: 1px solid var(--box-border-color);">
          <div class="admin-invoice-total-row">
            <span>Subtotal:</span>
            <span>${formatMoney(b.subtotal || b.totalAmount)}</span>
          </div>
          ${b.discountAmount > 0 ? `
          <div class="admin-invoice-total-row" style="color: #10b981;">
            <span>Discount (${b.voucherCode || b.promotionName || 'Voucher'}):</span>
            <span>-${formatMoney(b.discountAmount)}</span>
          </div>` : ''}
          <div class="admin-invoice-total-row admin-invoice-grand-total">
            <span>TOTAL AMOUNT:</span>
            <span>${formatMoney(b.totalAmount)}</span>
          </div>
        </div>
      </div>

      <!-- Payment Provider Section -->
      <div class="admin-invoice-section" style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 0;">
        <div>
          <div style="font-size: 11px; color: var(--box-text-muted); text-transform: uppercase;">Payment Gateway</div>
          <div style="font-weight: 600; color: #38bdf8;">${b.paymentProvider || 'VNPAY'} · ${b.paymentMethod || 'QR_CODE'}</div>
        </div>
        <div style="text-align: right;">
          <div style="font-size: 11px; color: var(--box-text-muted); text-transform: uppercase;">Payment Status</div>
          <div style="font-weight: 700; color: ${b.paymentStatus === 'SUCCESS' ? '#10b981' : b.paymentStatus === 'REFUNDED' ? '#f59e0b' : '#ef4444'};">${b.paymentStatus || 'SUCCESS'}</div>
        </div>
      </div>
    `;

    modal.showModal();
  }

  // Booking toolbar event listeners
  if (get('adminBookingSearch')) {
    get('adminBookingSearch').addEventListener('input', () => { bookingPage = 1; renderBookings(); });
  }
  if (get('adminBookingCinemaFilter')) {
    get('adminBookingCinemaFilter').addEventListener('change', () => { bookingPage = 1; renderBookings(); });
  }
  if (get('adminBookingStatusFilter')) {
    get('adminBookingStatusFilter').addEventListener('change', () => { bookingPage = 1; renderBookings(); });
  }
  if (get('adminBookingClearFilters')) {
    get('adminBookingClearFilters').addEventListener('click', () => {
      get('adminBookingSearch').value = '';
      get('adminBookingCinemaFilter').value = '';
      get('adminBookingStatusFilter').value = '';
      bookingPage = 1;
      renderBookings();
    });
  }
  if (get('adminBookingPrevious')) {
    get('adminBookingPrevious').addEventListener('click', () => { bookingPage--; renderBookings(); });
  }
  if (get('adminBookingNext')) {
    get('adminBookingNext').addEventListener('click', () => { bookingPage++; renderBookings(); });
  }

  function renderSection() {
    if (config.fields) {
      get('adminCatalog').hidden = false;
      get('adminCreate').textContent = `+ Add ${config.singular}`;

      const cinemaFilter = get('adminCinemaFilter');
      if (cinemaFilter) {
        if (['rooms', 'cinemas', 'staff'].includes(section)) {
          cinemaFilter.hidden = false;
          const currentVal = cinemaFilter.value;
          cinemaFilter.replaceChildren();
          const allCinemas = element('option', 'All cinemas');
          allCinemas.value = '';
          cinemaFilter.append(allCinemas);
          for (const c of (state.cinemas || [])) {
            const opt = element('option', c.cinemaName);
            opt.value = c.id;
            cinemaFilter.append(opt);
          }
          if (currentVal && Array.from(cinemaFilter.options).some(o => o.value === currentVal)) {
            cinemaFilter.value = currentVal;
          }
        } else {
          cinemaFilter.hidden = true;
          cinemaFilter.value = '';
        }
      }

      const filter = get('adminFilter'); filter.replaceChildren(); const all = element('option', 'All statuses'); all.value = ''; filter.append(all);
      const statuses = config.fields.find(field => field.key === 'status')?.options || [];
      for (const value of statuses) { const option = element('option', humanize(value)); option.value = value; filter.append(option); }
      filter.hidden = !statuses.length;
      get('adminSearch').placeholder = `Search ${config.title.toLowerCase()}…`; renderCatalog();
    } else if (section === 'dashboard') { get('adminDashboard').hidden = false; renderDashboard(); }
    else if (section === 'bookings') {
      get('adminBookings').hidden = false;
      renderBookings();
    }
    else if (section === 'reports') {
      get('adminReports').hidden = false;
      get('adminReportCinema').replaceChildren(); const all = element('option', 'All cinemas'); all.value = ''; get('adminReportCinema').append(all);
      for (const cinema of (state.cinemas || [])) { const option = element('option', cinema.cinemaName); option.value = cinema.id; get('adminReportCinema').append(option); }
      renderReports();
    } else if (section === 'seats') {
      get('adminSeats').hidden = false;
      const cinemaSelect = get('adminSeatCinema');
      if (cinemaSelect) {
        cinemaSelect.replaceChildren();
        for (const cinema of (state.cinemas || [])) {
          const option = element('option', cinema.cinemaName);
          option.value = cinema.id;
          cinemaSelect.append(option);
        }
      }

      updateSeatPaintPalette('');

      const requestedRoomId = new URLSearchParams(win.location.search).get('roomId');
      const targetRoom = (state.rooms || []).find(r => r.id === requestedRoomId);
      if (targetRoom) {
        if (cinemaSelect) cinemaSelect.value = targetRoom.cinemaId;
        updateRoomDropdown(targetRoom.cinemaId, targetRoom.id);
      } else {
        const firstCinemaId = state.cinemas[0]?.id || '';
        if (cinemaSelect) cinemaSelect.value = firstCinemaId;
        updateRoomDropdown(firstCinemaId);
      }
    }
  }

  // Initial render with current state, then immediately sync live database
  renderSection();
  syncLiveData(true);

  const params = new URLSearchParams(win.location.search);
  if (config.fields && params.get('create') === 'true') openEditor();
  else if (config.fields && state[section]?.some(record => record.id === params.get('edit'))) openEditor(params.get('edit'));
}

if (typeof document !== 'undefined') {
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => initAdmin(document), { once: true });
  else initAdmin(document);
}
