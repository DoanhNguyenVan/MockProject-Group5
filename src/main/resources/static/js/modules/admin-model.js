import { ADMIN_SECTIONS, RECORD_LABELS } from './admin-config.js';

export function recordLabel(section, record, state) {
  if (!record) return 'Unavailable';
  if (section === 'rooms') return `${record.roomName} · ${state.cinemas.find(item => item.id === record.cinemaId)?.cinemaName || 'Unavailable cinema'}`;
  return String(record[RECORD_LABELS[section]] || record.id);
}

export function filterRecords(records, query = '', status = '') {
  const normalize = value => String(value).normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase();
  const search = normalize(query.trim());
  return records.filter(record => (!status || record.status === status) && normalize(Object.values(record).join(' ')).includes(search));
}

export function paginateRecords(records, page = 1, pageSize = 6) {
  const pages = Math.max(1, Math.ceil(records.length / pageSize));
  const current = Math.min(pages, Math.max(1, Number(page) || 1));
  return { items: records.slice((current - 1) * pageSize, current * pageSize), page: current, pages, total: records.length };
}

export function validateAdminRecord(section, record, state) {
  const errors = {};
  const fields = ADMIN_SECTIONS[section]?.fields;
  if (!fields) return { form: 'This section cannot be edited.' };
  for (const field of fields) {
    const value = record[field.key];
    const empty = value == null || String(value).trim() === '' || (Array.isArray(value) && !value.length);
    if (empty) { if (field.required) errors[field.key] = `${field.label} is required.`; continue; }
    if (field.max && field.type !== 'number' && String(value).length > field.max) errors[field.key] = `Use at most ${field.max} characters.`;
    if (field.type === 'number' && (!Number.isSafeInteger(Number(value)) || Number(value) < field.min || Number(value) > field.max)) errors[field.key] = `Enter a whole number from ${field.min} to ${field.max}.`;
    if (field.type === 'select' && !field.options.includes(value)) errors[field.key] = 'Choose a valid option.';
    if (field.type === 'email' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) errors[field.key] = 'Enter a valid email address.';
    if (field.key === 'phone' && !/^\+?[\d ()-]{8,20}$/.test(value)) errors[field.key] = 'Enter a valid phone number.';
    if (field.type === 'url') {
      try { if (new URL(value).protocol !== 'https:') errors[field.key] = 'Use a full HTTPS URL.'; }
      catch { errors[field.key] = 'Use a full HTTPS URL.'; }
    }
    if (field.type === 'date' || field.type === 'datetime-local') {
      const parsed = new Date(value);
      const pattern = field.type === 'date' ? /^\d{4}-\d{2}-\d{2}$/ : /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/;
      const calendarDay = new Date(String(value).slice(0, 10) + 'T12:00:00Z');
      if (!pattern.test(value) || !Number.isFinite(parsed.getTime()) || !Number.isFinite(calendarDay.getTime()) || calendarDay.toISOString().slice(0, 10) !== String(value).slice(0, 10)) errors[field.key] = 'Enter a valid date.';
    }
    if (field.type === 'reference' && !state[field.source].some(item => item.id === value)) errors[field.key] = 'Choose an existing record.';
    if (field.type === 'multireference' && (!Array.isArray(value) || value.some(id => !state[field.source].some(item => item.id === id)))) errors[field.key] = 'Choose valid genres.';
  }
  if (record.endDate && record.endDate < record.releaseDate) errors.endDate = 'End date must be on or after release date.';
  if (record.startAt && record.endAt && record.endAt <= record.startAt) errors.endAt = 'End time must be after start time.';
  const uniqueKeys = { genres: ['genreName'], pricing: ['typeName'], promotions: ['voucherCode'], staff: ['email', 'phone'] }[section] || [];
  for (const key of uniqueKeys) {
    if (state[section].some(item => item.id !== record.id && String(item[key]).trim().toLowerCase() === String(record[key]).trim().toLowerCase())) errors[key] = 'This value is already in use.';
  }
  if (section === 'rooms' && state.rooms.some(item => item.id !== record.id && item.cinemaId === record.cinemaId && item.roomName.toLowerCase() === String(record.roomName).toLowerCase())) errors.roomName = 'This room name already exists at the cinema.';
  if (section === 'promotions' && record.discountType === 'PERCENT' && Number(record.discountValue) > 100) errors.discountValue = 'A percentage cannot exceed 100.';
  if (section === 'promotions' && Number(record.perUserLimit) > Number(record.usageLimit)) errors.perUserLimit = 'Cannot exceed total redemptions.';
  if (section === 'showtimes' && record.status === 'ACTIVE') {
    const movie = state.movies.find(item => item.id === record.movieId);
    const room = state.rooms.find(item => item.id === record.roomId);
    if (room?.status !== 'ACTIVE' || state.cinemas.find(item => item.id === room?.cinemaId)?.status !== 'ACTIVE') errors.roomId = 'Choose a room in an active cinema.';
    if (movie?.status === 'ENDED') errors.movieId = 'This movie has ended its run.';
    if (movie && (record.startAt?.slice(0, 10) < movie.releaseDate || (movie.endDate && record.startAt?.slice(0, 10) > movie.endDate))) errors.startAt = 'Schedule within the movie release window.';
    if (movie && new Date(record.endAt) - new Date(record.startAt) < movie.durationMinutes * 60000) errors.endAt = 'Allow at least the full movie runtime.';
    if (state.showtimes.some(item => item.id !== record.id && item.status === 'ACTIVE' && item.roomId === record.roomId && record.startAt < item.endAt && record.endAt > item.startAt)) errors.startAt = 'This room already has a showtime in that time range.';
  }
  return errors;
}

export function deleteBlockReason(section, id, state) {
  for (const [source, config] of Object.entries(ADMIN_SECTIONS)) {
    for (const field of config.fields || []) {
      if (field.source === section && state[source].some(item => Array.isArray(item[field.key]) ? item[field.key].includes(id) : item[field.key] === id)) return `Used by ${config.title.toLowerCase()}. Update those records first.`;
    }
  }
  if (section === 'pricing' && Object.values(state.layouts).some(layout => layout.some(seat => seat.typeId === id))) return 'This seat type is used in a saved layout.';
  if (state.sales.some(sale => (section === 'movies' && sale.movieId === id) || (section === 'cinemas' && sale.cinemaId === id))) return 'Referenced by sample sales. Keep this record for reporting.';
  return '';
}

export function summarizeSales(sales, from, to, cinemaId = '') {
  const filtered = sales.filter(sale => sale.date >= from && sale.date <= to && (!cinemaId || sale.cinemaId === cinemaId) && sale.status === 'PAID');
  const days = {}, movies = {};
  for (const sale of filtered) {
    days[sale.date] = (days[sale.date] || 0) + sale.revenue;
    const movie = movies[sale.movieId] ||= { movieId: sale.movieId, tickets: 0, revenue: 0 };
    movie.tickets += sale.tickets; movie.revenue += sale.revenue;
  }
  return { revenue: filtered.reduce((sum, sale) => sum + sale.revenue, 0), tickets: filtered.reduce((sum, sale) => sum + sale.tickets, 0), orders: filtered.length, days, movies: Object.values(movies).sort((a, b) => b.revenue - a.revenue), records: filtered };
}

export function createSeatLayout(rows, columns, typeId) {
  if (!Number.isInteger(rows) || !Number.isInteger(columns) || rows < 1 || rows > 12 || columns < 1 || columns > 16 || !typeId) return [];
  return Array.from({ length: rows * columns }, (_, index) => ({ row: String.fromCharCode(65 + Math.floor(index / columns)), number: index % columns + 1, typeId, status: 'ACTIVE' }));
}

export function reportCsv(summary, state) {
  const cell = value => {
    const text = String(value);
    const safe = /^(?:\s*[=+@-]|[\t\r\n])/.test(text) ? "'" + text : text;
    return `"${safe.replace(/"/g, '""')}"`;
  };
  const rows = [['Sample report — not live revenue'], ['Movie', 'Tickets', 'Revenue (VND)'], ...summary.movies.map(item => [recordLabel('movies', state.movies.find(movie => movie.id === item.movieId), state), item.tickets, item.revenue])];
  return '\uFEFF' + rows.map(row => row.map(cell).join(',')).join('\r\n');
}
