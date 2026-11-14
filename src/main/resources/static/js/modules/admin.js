import { ADMIN_SECTIONS } from './admin-config.js';
import { createAdminPreview } from './admin-preview.js';
import { recordLabel, filterRecords, paginateRecords, validateAdminRecord, deleteBlockReason, summarizeSales, createSeatLayout, reportCsv } from './admin-model.js';

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
  const notify = message => { get('adminStatus').textContent = message; get('adminStatus').hidden = false; };
  let state = createAdminPreview();
  let page = 1;
  let editingId = null;
  let confirmAction = null;
  let seatDraft = [], seatRoom = '', seatDirty = false;
  let report = null;
  try {
    const saved = JSON.parse(win.sessionStorage.getItem(STORAGE_KEY));
    if (saved) {
      const collections = Object.keys(ADMIN_SECTIONS).filter(key => ADMIN_SECTIONS[key].fields);
      if (saved.version !== 1 || !saved.layouts || typeof saved.layouts !== 'object' || !Array.isArray(saved.sales) ||
        !collections.every(key => Array.isArray(saved[key]) && saved[key].every(item => item && typeof item.id === 'string' && ADMIN_SECTIONS[key].fields.every(field => field.type === 'multireference' ? Array.isArray(item[field.key]) : typeof item[field.key] === 'string' || typeof item[field.key] === 'number'))) ||
        !Object.values(saved.layouts).every(layout => Array.isArray(layout) && layout.every(seat => seat && typeof seat.row === 'string' && Number.isInteger(seat.number) && typeof seat.typeId === 'string'))) throw new Error('Invalid preview');
      // Sales are immutable fixtures, never trusted from browser storage.
      state = { ...saved, sales: state.sales };
    }
  } catch { notify('The saved demo could not be loaded. A fresh sample workspace is ready.'); }

  const persist = message => {
    try { win.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(state)); notify(message); }
    catch { notify(`${message} Browser storage is unavailable; changes will be lost when you leave this page.`); }
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
  get('adminReset').addEventListener('click', () => confirm('Reset sample workspace?', 'All demo edits in this tab will be replaced with the original sample records.', 'Reset demo', () => {
    state = createAdminPreview(); page = 1; seatDirty = false;
    persist('Sample workspace reset.'); renderSection();
  }));

  get('adminTitle').textContent = config.title; get('adminBreadcrumb').textContent = config.title;
  get('adminDescription').textContent = config.description;
  doc.title = `${config.title} | BOX Cinema Admin`;
  let lastGroup = '';
  for (const [key, item] of Object.entries(ADMIN_SECTIONS)) {
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
  // The customer branch picker is intentionally separate from this fictional workspace.
  function badge(value) { return element('span', humanize(value), `admin-badge admin-badge-${String(value).toLowerCase()}`); }
  function fieldDisplay(field, record) {
    const value = record[field.key];
    if (field.type === 'reference') return recordLabel(field.source, state[field.source].find(item => item.id === value), state);
    if (['basePrice', 'price', 'priceSurcharge'].includes(field.key)) return formatMoney(value);
    if (field.key === 'discountValue') return record.discountType === 'PERCENT' ? `${value}%` : formatMoney(value);
    if (field.type === 'datetime-local') return String(value).replace('T', ' · ');
    if (field.key === 'durationMinutes') return `${value} min`;
    return String(value || '—');
  }
  function renderCatalog() {
    const displayRecords = state[section].map(record => ({ ...record, _searchReferences: config.fields.filter(field => field.type === 'reference').map(field => fieldDisplay(field, record)).join(' ') }));
    const results = paginateRecords(filterRecords(displayRecords, get('adminSearch').value, get('adminFilter').value), page);
    page = results.page;
    get('adminTableCaption').textContent = `${config.title} — sample records`;
    const header = element('tr');
    for (const key of config.columns) { const th = element('th', config.fields.find(field => field.key === key).label); th.scope = 'col'; header.append(th); }
    const actionHeader = element('th', 'Actions'); actionHeader.scope = 'col'; header.append(actionHeader);
    get('adminTableHead').replaceChildren(header); get('adminTableBody').replaceChildren();
    for (const record of results.items) {
      const row = element('tr');
      for (const key of config.columns) {
        const cell = element('td');
        if (key === 'status') cell.append(badge(record[key]));
        else if (key === 'ageRating') cell.append(element('span', record[key], `badge-age-${record[key].toLowerCase()}`));
        else cell.textContent = fieldDisplay(config.fields.find(field => field.key === key), record);
        row.append(cell);
      }
      const actions = element('div', '', 'admin-actions');
      const edit = element('button', 'Edit', 'admin-button'); edit.type = 'button'; edit.setAttribute('aria-label', `Edit ${recordLabel(section, record, state)}`);
      edit.addEventListener('click', () => openEditor(record.id)); actions.append(edit);
      if (section === 'rooms') { const seats = element('a', 'Seats', 'admin-button'); seats.href = `/admin/seats?roomId=${encodeURIComponent(record.id)}`; actions.append(seats); }
      const remove = element('button', 'Delete', 'admin-text-button'); remove.type = 'button'; remove.setAttribute('aria-label', `Delete ${recordLabel(section, record, state)}`);
      remove.addEventListener('click', () => {
        const reason = deleteBlockReason(section, record.id, state);
        if (reason) { notify(reason); return; }
        confirm('Delete sample record?', `${recordLabel(section, record, state)} will be removed from this demo.`, 'Delete demo record', () => {
          state[section] = state[section].filter(item => item.id !== record.id);
          if (section === 'rooms') delete state.layouts[record.id];
          persist('Sample record deleted.'); renderCatalog();
        });
      });
      actions.append(remove); const actionCell = element('td'); actionCell.append(actions); row.append(actionCell); get('adminTableBody').append(row);
    }
    get('adminEmpty').hidden = results.total > 0;
    get('adminPageInfo').textContent = `${results.total} records · Page ${results.page} of ${results.pages}`;
    get('adminPrevious').disabled = results.page === 1; get('adminNext').disabled = results.page === results.pages;
  }
  function openEditor(id = null) {
    editingId = id;
    const record = state[section].find(item => item.id === id) || {};
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
          for (const item of state[field.source]) {
            const option = element('option', recordLabel(field.source, item, state)); option.value = item.id;
            if (input.multiple) option.selected = (record[field.key] || []).includes(item.id);
            input.append(option);
          }
        } else for (const value of field.options) { const option = element('option', humanize(value)); option.value = value; input.append(option); }
      } else if (field.type === 'textarea') input = element('textarea');
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
    get('adminEditor').showModal();
  }
  function closeEditor() { get('adminEditor').close(); }
  get('adminEditorClose').addEventListener('click', closeEditor); get('adminEditorCancel').addEventListener('click', closeEditor);
  get('adminEditorForm').addEventListener('submit', event => {
    event.preventDefault();
    const record = { id: editingId || `demo-${win.crypto.randomUUID()}` };
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
    if (editingId) state[section] = state[section].map(item => item.id === editingId ? record : item);
    else state[section].unshift(record);
    closeEditor(); get('adminSearch').value = ''; get('adminFilter').value = ''; page = 1;
    persist('Demo changes saved in this tab. No live data was changed.'); renderCatalog();
  });
  get('adminCreate').addEventListener('click', () => openEditor());
  get('adminSearch').addEventListener('input', () => { page = 1; renderCatalog(); });
  get('adminFilter').addEventListener('change', () => { page = 1; renderCatalog(); });
  get('adminClearFilters').addEventListener('click', () => { get('adminSearch').value = ''; get('adminFilter').value = ''; page = 1; renderCatalog(); });
  get('adminPrevious').addEventListener('click', () => { page--; renderCatalog(); });
  get('adminNext').addEventListener('click', () => { page++; renderCatalog(); });

  function renderMetrics(target, summary) {
    get(target).replaceChildren();
    const metrics = [['Ticket revenue', shortMoney(summary.revenue), 'VND · paid sample sales'], ['Tickets sold', summary.tickets.toLocaleString('en'), 'From paid sample sales'], ['Paid sale records', String(summary.orders), 'Cancelled sales excluded'], ['Revenue per ticket', summary.tickets ? formatMoney(Math.round(summary.revenue / summary.tickets)) : '—', 'Average · sample period']];
    for (const [label, value, note] of metrics) { const card = element('article', '', 'admin-metric'); card.append(element('p', label), element('strong', value), element('small', note)); get(target).append(card); }
  }
  function renderChart(target, summary) {
    get(target).replaceChildren();
    const days = Object.entries(summary.days).sort(([a], [b]) => a.localeCompare(b));
    if (!days.length) { get(target).append(element('p', 'No paid sample sales for this period.', 'admin-muted')); return; }
    const maximum = Math.max(...days.map(([, amount]) => amount), 1);
    get(target).setAttribute('aria-label', days.map(([day, value]) => `${day}: ${formatMoney(value)}`).join('; '));
    for (const [day, amount] of days) {
      const column = element('div', '', 'admin-chart-column'); const bar = element('div', '', 'admin-chart-bar');
      bar.style.height = `${Math.max(1, amount / maximum * 150)}px`; bar.title = `${day}: ${formatMoney(amount)}`;
      column.append(element('span', shortMoney(amount)), bar, element('span', day.slice(8) + ' Nov')); get(target).append(column);
    }
  }
  function renderDashboard() {
    const summary = summarizeSales(state.sales, '2026-11-09', '2026-11-15');
    renderMetrics('adminMetrics', summary); renderChart('adminRevenueChart', summary);
    get('adminTopMovies').replaceChildren();
    summary.movies.slice(0, 3).forEach((movie, index) => {
      const item = element('div', '', 'admin-ranking-item'); const title = element('div');
      title.append(element('strong', recordLabel('movies', state.movies.find(row => row.id === movie.movieId), state)), element('small', `${movie.tickets} tickets sold`));
      item.append(element('span', `0${index + 1}`), title, element('b', shortMoney(movie.revenue))); get('adminTopMovies').append(item);
    });
    get('adminUpcoming').replaceChildren();
    const screenings = state.showtimes.filter(item => item.status === 'ACTIVE' && item.startAt.startsWith('2026-11-13')).sort((a, b) => a.startAt.localeCompare(b.startAt));
    if (!screenings.length) get('adminUpcoming').append(element('p', 'No screenings in the sample schedule.', 'admin-muted'));
    screenings.forEach(show => {
      const row = element('div', '', 'admin-upcoming-item'); const details = element('div');
      details.append(element('strong', recordLabel('movies', state.movies.find(item => item.id === show.movieId), state)), element('small', recordLabel('rooms', state.rooms.find(item => item.id === show.roomId), state)));
      const time = element('time', show.startAt.slice(11)); time.dateTime = show.startAt;
      const link = element('a', 'Edit →', 'admin-text-button'); link.href = `/admin/showtimes?edit=${encodeURIComponent(show.id)}`;
      row.append(time, details, link); get('adminUpcoming').append(row);
    });
  }

  function renderReports() {
    const from = get('adminReportFrom').value, to = get('adminReportTo').value;
    if (!from || !to || from > to) { notify('Choose a valid date range. The start date must be on or before the end date.'); return; }
    report = summarizeSales(state.sales, from, to, get('adminReportCinema').value);
    renderMetrics('adminReportMetrics', report); renderChart('adminReportChart', report); get('adminReportBody').replaceChildren();
    for (const item of report.movies) {
      const row = element('tr'); [recordLabel('movies', state.movies.find(movie => movie.id === item.movieId), state), item.tickets, formatMoney(item.revenue), `${(item.revenue / report.revenue * 100).toFixed(1)}%`].forEach(value => row.append(element('td', value)));
      get('adminReportBody').append(row);
    }
    get('adminReportEmpty').hidden = report.orders > 0; get('adminExportReport').disabled = !report.orders;
  }
  get('adminReportFilters').addEventListener('submit', event => { event.preventDefault(); renderReports(); });
  get('adminExportReport').addEventListener('click', () => {
    if (!report?.orders) return;
    const url = win.URL.createObjectURL(new win.Blob([reportCsv(report, state)], { type: 'text/csv;charset=utf-8' }));
    const link = element('a'); link.href = url; link.download = 'box-cinema-sample-report.csv'; doc.body.append(link); link.click(); link.remove();
    win.setTimeout(() => win.URL.revokeObjectURL(url), 1000);
    notify('Exported the displayed sample report.');
  });

  function renderSeatGrid() {
    get('adminSeatGrid').replaceChildren();
    const columns = Math.max(1, ...seatDraft.map(seat => seat.number)); get('adminSeatGrid').style.setProperty('--seat-columns', columns);
    for (const seat of seatDraft) {
      const type = state.pricing.find(item => item.id === seat.typeId)?.typeName || 'STANDARD';
      const button = element('button', seat.status === 'INACTIVE' ? '×' : `${seat.row}${seat.number}`, 'admin-seat'); button.type = 'button';
      button.dataset.type = type; button.dataset.status = seat.status;
      button.setAttribute('aria-label', `${seat.row}${seat.number}, ${type}, ${seat.status === 'ACTIVE' ? 'available' : 'unavailable'}`);
      button.addEventListener('click', () => {
        const paint = get('adminSeatPaint').value;
        if (paint === 'blocked') seat.status = 'INACTIVE';
        else { seat.typeId = paint; seat.status = 'ACTIVE'; }
        seatDirty = true; renderSeatGrid();
        get('adminSeatGrid').children[seatDraft.indexOf(seat)]?.focus();
      }); get('adminSeatGrid').append(button);
    }
    get('adminSeatCount').textContent = `${seatDraft.filter(seat => seat.status === 'ACTIVE').length} available / ${seatDraft.length} seats`;
    get('adminSeatDirty').textContent = seatDirty ? 'Unsaved demo changes' : 'No unsaved changes';
    get('adminSaveSeats').disabled = !seatDraft.length || (!seatDirty && !!state.layouts[seatRoom]);
  }
  function loadSeatRoom(id) {
    seatRoom = id; get('adminSeatRoom').value = id;
    const room = state.rooms.find(item => item.id === id);
    seatDraft = room ? structuredClone(state.layouts[id] || createSeatLayout(6, 10, state.pricing[0]?.id)) : [];
    get('adminSeatTitle').textContent = room ? recordLabel('rooms', room, state) : 'Add a room before creating a layout';
    get('adminSeatRows').value = new Set(seatDraft.map(seat => seat.row)).size || 6;
    get('adminSeatColumns').value = seatDraft.length ? Math.max(...seatDraft.map(seat => seat.number)) : 10;
    seatDirty = false;
    get('adminGenerateSeats').disabled = !room || !state.pricing.length;
    renderSeatGrid();
  }
  get('adminSeatRoom').addEventListener('change', () => {
    const next = get('adminSeatRoom').value;
    if (seatDirty) { get('adminSeatRoom').value = seatRoom; confirm('Discard unsaved layout?', 'Changing rooms will discard the unsaved layout. Save it first if you want to keep it.', 'Discard and switch', () => loadSeatRoom(next)); }
    else loadSeatRoom(next);
  });
  get('adminGenerateSeats').addEventListener('click', () => {
    const layout = createSeatLayout(Number(get('adminSeatRows').value), Number(get('adminSeatColumns').value), state.pricing[0]?.id);
    if (!layout.length) { notify('Use 1–12 rows and 1–16 seats per row. At least one seat type is required.'); return; }
    confirm('Replace the demo layout?', 'This generates a new layout and resets all seat types and unavailable seats in the editor.', 'Generate layout', () => { seatDraft = layout; seatDirty = true; renderSeatGrid(); });
  });
  get('adminSaveSeats').addEventListener('click', () => {
    if (!seatRoom || !seatDraft.length) return;
    state.layouts[seatRoom] = structuredClone(seatDraft); seatDirty = false;
    persist('Demo seat layout saved in this tab.'); renderSeatGrid();
  });
  win.addEventListener('beforeunload', event => { if (section === 'seats' && seatDirty) { event.preventDefault(); event.returnValue = ''; } });
  function renderSection() {
    if (config.fields) {
      get('adminCatalog').hidden = false;
      get('adminCreate').textContent = `+ Add ${config.singular}`;
      const filter = get('adminFilter'); filter.replaceChildren(); const all = element('option', 'All statuses'); all.value = ''; filter.append(all);
      const statuses = config.fields.find(field => field.key === 'status')?.options || [];
      for (const value of statuses) { const option = element('option', humanize(value)); option.value = value; filter.append(option); }
      filter.hidden = !statuses.length;
      get('adminSearch').placeholder = `Search ${config.title.toLowerCase()}…`; renderCatalog();
    } else if (section === 'dashboard') { get('adminDashboard').hidden = false; renderDashboard(); }
    else if (section === 'reports') {
      get('adminReports').hidden = false;
      get('adminReportCinema').replaceChildren(); const all = element('option', 'All cinemas'); all.value = ''; get('adminReportCinema').append(all);
      for (const cinema of state.cinemas) { const option = element('option', cinema.cinemaName); option.value = cinema.id; get('adminReportCinema').append(option); }
      renderReports();
    } else if (section === 'seats') {
      get('adminSeats').hidden = false; get('adminSeatRoom').replaceChildren(); get('adminSeatPaint').replaceChildren();
      for (const room of state.rooms) { const option = element('option', recordLabel('rooms', room, state)); option.value = room.id; get('adminSeatRoom').append(option); }
      for (const type of state.pricing) { const option = element('option', type.typeName); option.value = type.id; get('adminSeatPaint').append(option); }
      const blocked = element('option', 'Unavailable'); blocked.value = 'blocked'; get('adminSeatPaint').append(blocked);
      const requested = new URLSearchParams(win.location.search).get('roomId');
      loadSeatRoom(state.rooms.some(room => room.id === requested) ? requested : state.rooms[0]?.id || '');
    }
  }
  renderSection();
  const params = new URLSearchParams(win.location.search);
  if (config.fields && params.get('create') === 'true') openEditor();
  else if (config.fields && state[section].some(record => record.id === params.get('edit'))) openEditor(params.get('edit'));
}

if (typeof document !== 'undefined') {
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', () => initAdmin(document), { once: true });
  else initAdmin(document);
}
