import { describe, it, expect } from 'vitest';
import { ADMIN_SECTIONS } from '../../src/main/resources/static/js/modules/admin-config.js';
import { createAdminPreview } from '../../src/main/resources/static/js/modules/admin-preview.js';
import { recordLabel, filterRecords, paginateRecords, validateAdminRecord, deleteBlockReason, summarizeSales, createSeatLayout, reportCsv } from '../../src/main/resources/static/js/modules/admin-model.js';

describe('admin model and isolated sample data', () => {
  it('starts with valid independent fixtures for every editable screen', () => {
    const state = createAdminPreview();
    for (const [key, config] of Object.entries(ADMIN_SECTIONS)) {
      for (const record of config.fields ? state[key] : []) expect(validateAdminRecord(key, record, state)).toEqual({});
    }
    state.movies[0].title = 'Changed';
    expect(createAdminPreview().movies[0].title).toBe('Interstellar');
  });
  it('resolves room names with their cinema and handles missing references', () => {
    const state = createAdminPreview();
    expect(recordLabel('rooms', state.rooms[0], state)).toBe('Screen 01 · BOX Landmark');
    expect(recordLabel('movies', null, state)).toBe('Unavailable');
  });
  it('searches case and accents and combines the status filter', () => {
    const records = [{ id: '1', title: 'Đà Nẵng', status: 'ACTIVE' }, { id: '2', title: 'Da Nang', status: 'INACTIVE' }];
    expect(filterRecords(records, '  DA NANG ', 'ACTIVE')).toEqual([records[0]]);
    expect(filterRecords(records, 'missing')).toEqual([]);
  });
  it('clamps pagination after removing the final row of a page', () => {
    const records = createAdminPreview().movies;
    expect(paginateRecords(records, 2).items).toHaveLength(1);
    expect(paginateRecords(records.slice(0, 6), 2).page).toBe(1);
    expect(paginateRecords([], 5)).toEqual({ items: [], page: 1, pages: 1, total: 0 });
  });
  it('rejects missing fields, fractional prices and unsafe URLs', () => {
    const state = createAdminPreview();
    expect(validateAdminRecord('movies', { ...state.movies[0], title: ' ', posterUrl: 'javascript:alert(1)', durationMinutes: 0 }, state)).toHaveProperty('title');
    expect(validateAdminRecord('movies', { ...state.movies[0], trailerUrl: 'http://example.com/video' }, state)).toHaveProperty('trailerUrl');
    expect(validateAdminRecord('combos', { ...state.combos[0], price: 1.5 }, state)).toHaveProperty('price');
    expect(validateAdminRecord('movies', { ...state.movies[0], genreIds: ['missing'] }, state)).toHaveProperty('genreIds');
  });
  it('validates promotion dates, percentages and redemption limits', () => {
    const state = createAdminPreview();
    const errors = validateAdminRecord('promotions', { ...state.promotions[0], discountValue: 101, endAt: '2026-10-01T00:00', perUserLimit: 501 }, state);
    expect(errors).toHaveProperty('discountValue'); expect(errors).toHaveProperty('endAt'); expect(errors).toHaveProperty('perUserLimit');
  });
  it('rejects impossible calendar days and malformed local date-times', () => {
    const state = createAdminPreview();
    expect(validateAdminRecord('movies', { ...state.movies[0], releaseDate: '2026-02-30' }, state)).toHaveProperty('releaseDate');
    expect(validateAdminRecord('showtimes', { ...state.showtimes[0], startAt: '2026-11-13' }, state)).toHaveProperty('startAt');
  });
  it('requires unique staff contacts and a valid role and cinema', () => {
    const state = createAdminPreview();
    const errors = validateAdminRecord('staff', { ...state.staff[1], email: state.staff[0].email.toUpperCase(), role: 'OWNER', cinemaId: 'missing', phone: 'invalid' }, state);
    for (const key of ['email', 'role', 'cinemaId', 'phone']) expect(errors).toHaveProperty(key);
  });
  it('rejects overlapping active screenings but permits exact boundaries and other rooms', () => {
    const state = createAdminPreview();
    const draft = { ...state.showtimes[0], id: 'new' };
    expect(validateAdminRecord('showtimes', draft, state)).toHaveProperty('startAt');
    expect(validateAdminRecord('showtimes', { ...draft, startAt: '2026-11-13T20:49', endAt: '2026-11-13T23:38' }, state)).toEqual({});
    expect(validateAdminRecord('showtimes', { ...draft, roomId: 'r3', startAt: '2026-11-14T18:00', endAt: '2026-11-14T20:49' }, state)).toEqual({});
    expect(validateAdminRecord('showtimes', state.showtimes[0], state)).toEqual({});
  });
  it('rejects short runtimes, ended movies and inactive cinemas', () => {
    const state = createAdminPreview();
    expect(validateAdminRecord('showtimes', { ...state.showtimes[0], endAt: '2026-11-13T18:10' }, state)).toHaveProperty('endAt');
    expect(validateAdminRecord('showtimes', { ...state.showtimes[0], movieId: 'm5' }, state)).toHaveProperty('movieId');
    state.cinemas[0].status = 'INACTIVE';
    expect(validateAdminRecord('showtimes', state.showtimes[0], state)).toHaveProperty('roomId');
  });
  it('prevents deleting referenced records and allows unused records', () => {
    const state = createAdminPreview();
    expect(deleteBlockReason('movies', 'm1', state)).toContain('showtimes');
    expect(deleteBlockReason('genres', 'g1', state)).toContain('movies');
    expect(deleteBlockReason('cinemas', 'c1', state)).toContain('rooms');
    expect(deleteBlockReason('movies', 'm7', state)).toBe('');
    state.layouts.r1 = createSeatLayout(2, 3, 'p1');
    expect(deleteBlockReason('pricing', 'p1', state)).toContain('saved layout');
  });
  it('aggregates only paid sales within inclusive date and cinema filters', () => {
    const sales = [{ movieId: 'm1', cinemaId: 'c1', date: '2026-11-10', revenue: 200, tickets: 2, status: 'PAID' }, { movieId: 'm2', cinemaId: 'c2', date: '2026-11-10', revenue: 300, tickets: 3, status: 'PAID' }, { movieId: 'm1', cinemaId: 'c1', date: '2026-11-10', revenue: 500, tickets: 5, status: 'CANCELLED' }];
    const summary = summarizeSales(sales, '2026-11-10', '2026-11-10', 'c1');
    expect(summary.revenue).toBe(200); expect(summary.tickets).toBe(2); expect(summary.orders).toBe(1);
    expect(summary.days).toEqual({ '2026-11-10': 200 });
    expect(summarizeSales(sales, '2027-01-01', '2027-01-02').movies).toEqual([]);
  });
  it('generates bounded seat layouts with unique row/number pairs', () => {
    const layout = createSeatLayout(6, 10, 'p1');
    expect(layout).toHaveLength(60); expect(layout[59]).toEqual({ row: 'F', number: 10, typeId: 'p1', status: 'ACTIVE' });
    expect(new Set(layout.map(seat => seat.row + seat.number)).size).toBe(60);
    expect(createSeatLayout(13, 10, 'p1')).toEqual([]); expect(createSeatLayout(1.5, 10, 'p1')).toEqual([]);
    expect(createSeatLayout(6, 0, 'p1')).toEqual([]); expect(createSeatLayout(6, 10, '')).toEqual([]);
  });
  it('exports quoted sample CSV and prevents formula injection from movie titles', () => {
    const state = createAdminPreview(); state.movies[0].title = '=HYPERLINK("bad")';
    const csv = reportCsv({ movies: [{ movieId: 'm1', tickets: 1, revenue: 120000 }] }, state);
    expect(csv).toContain('Sample report'); expect(csv).toContain('"\'=HYPERLINK(""bad"")"'); expect(csv).toContain('120000');
  });
});
