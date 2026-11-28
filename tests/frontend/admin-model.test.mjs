import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ADMIN_SECTIONS } from '../../src/main/resources/static/js/modules/admin-config.js';
import { createAdminPreview } from '../../src/main/resources/static/js/modules/admin-preview.js';
import {
  recordLabel,
  filterRecords,
  paginateRecords,
  validateAdminRecord,
  deleteBlockReason,
  summarizeSales,
  createSeatLayout,
  reportCsv
} from '../../src/main/resources/static/js/modules/admin-model.js';

test('starts with valid independent fixtures for every editable screen', () => {
  const state = createAdminPreview();
  for (const [key, config] of Object.entries(ADMIN_SECTIONS)) {
    for (const record of config.fields ? state[key] : []) {
      assert.deepEqual(validateAdminRecord(key, record, state), {});
    }
  }
  state.movies[0].title = 'Changed';
  assert.equal(createAdminPreview().movies[0].title, 'Interstellar');
});

test('resolves room names with their cinema and handles missing references', () => {
  const state = createAdminPreview();
  assert.equal(recordLabel('rooms', state.rooms[0], state), 'Screen 01 · BOX Landmark');
  assert.equal(recordLabel('movies', null, state), 'Unavailable');
});

test('searches case and accents and combines the status filter', () => {
  const records = [
    { id: '1', title: 'Đà Nẵng', status: 'ACTIVE' },
    { id: '2', title: 'Da Nang', status: 'INACTIVE' }
  ];
  assert.deepEqual(filterRecords(records, '  DA NANG ', 'ACTIVE'), [records[0]]);
  assert.deepEqual(filterRecords(records, 'missing'), []);
});

test('clamps pagination after removing the final row of a page', () => {
  const records = createAdminPreview().movies;
  assert.equal(paginateRecords(records, 2).items.length, 1);
  assert.equal(paginateRecords(records.slice(0, 6), 2).page, 1);
  assert.deepEqual(paginateRecords([], 5), { items: [], page: 1, pages: 1, total: 0 });
});

test('rejects missing fields, fractional prices and unsafe URLs', () => {
  const state = createAdminPreview();
  assert.ok('title' in validateAdminRecord('movies', { ...state.movies[0], title: ' ', posterUrl: 'javascript:alert(1)', durationMinutes: 0 }, state));
  assert.ok('trailerUrl' in validateAdminRecord('movies', { ...state.movies[0], trailerUrl: 'http://example.com/video' }, state));
  assert.ok('price' in validateAdminRecord('combos', { ...state.combos[0], price: 1.5 }, state));
  assert.ok('genreIds' in validateAdminRecord('movies', { ...state.movies[0], genreIds: ['missing'] }, state));
});

test('validates promotion dates, percentages and redemption limits', () => {
  const state = createAdminPreview();
  const errors = validateAdminRecord('promotions', {
    ...state.promotions[0],
    discountValue: 101,
    endAt: '2026-10-01T00:00',
    perUserLimit: 501
  }, state);
  assert.ok('discountValue' in errors);
  assert.ok('endAt' in errors);
  assert.ok('perUserLimit' in errors);
});

test('rejects impossible calendar days and malformed local date-times', () => {
  const state = createAdminPreview();
  assert.ok('releaseDate' in validateAdminRecord('movies', { ...state.movies[0], releaseDate: '2026-02-30' }, state));
  assert.ok('startAt' in validateAdminRecord('showtimes', { ...state.showtimes[0], startAt: '2026-11-13' }, state));
});

test('requires unique staff contacts and a valid role and cinema', () => {
  const state = createAdminPreview();
  const errors = validateAdminRecord('staff', {
    ...state.staff[1],
    email: state.staff[0].email.toUpperCase(),
    role: 'OWNER',
    cinemaId: 'missing',
    phone: 'invalid'
  }, state);
  for (const key of ['email', 'role', 'cinemaId', 'phone']) {
    assert.ok(key in errors);
  }
});

test('rejects overlapping active screenings but permits exact boundaries and other rooms', () => {
  const state = createAdminPreview();
  const draft = { ...state.showtimes[0], id: 'new' };
  assert.ok('startAt' in validateAdminRecord('showtimes', draft, state));
  assert.deepEqual(validateAdminRecord('showtimes', { ...draft, startAt: '2026-11-13T20:49', endAt: '2026-11-13T23:38' }, state), {});
  assert.deepEqual(validateAdminRecord('showtimes', { ...draft, roomId: 'r3', startAt: '2026-11-14T18:00', endAt: '2026-11-14T20:49' }, state), {});
  assert.deepEqual(validateAdminRecord('showtimes', state.showtimes[0], state), {});
});

test('rejects short runtimes, ended movies and inactive cinemas', () => {
  const state = createAdminPreview();
  assert.ok('endAt' in validateAdminRecord('showtimes', { ...state.showtimes[0], endAt: '2026-11-13T18:10' }, state));
  assert.ok('movieId' in validateAdminRecord('showtimes', { ...state.showtimes[0], movieId: 'm5' }, state));
  state.cinemas[0].status = 'INACTIVE';
  assert.ok('roomId' in validateAdminRecord('showtimes', state.showtimes[0], state));
});

test('prevents deleting referenced records and allows unused records', () => {
  const state = createAdminPreview();
  assert.ok(deleteBlockReason('movies', 'm1', state).includes('showtimes'));
  assert.ok(deleteBlockReason('genres', 'g1', state).includes('movies'));
  assert.ok(deleteBlockReason('cinemas', 'c1', state).includes('rooms'));
  assert.equal(deleteBlockReason('movies', 'm7', state), '');
  state.layouts.r1 = createSeatLayout(2, 3, 'p1');
  assert.ok(deleteBlockReason('pricing', 'p1', state).includes('saved layout'));
});

test('aggregates only paid sales within inclusive date and cinema filters', () => {
  const sales = [
    { movieId: 'm1', cinemaId: 'c1', date: '2026-11-10', revenue: 200, tickets: 2, status: 'PAID' },
    { movieId: 'm2', cinemaId: 'c2', date: '2026-11-10', revenue: 300, tickets: 3, status: 'PAID' },
    { movieId: 'm1', cinemaId: 'c1', date: '2026-11-10', revenue: 500, tickets: 5, status: 'CANCELLED' }
  ];
  const summary = summarizeSales(sales, '2026-11-10', '2026-11-10', 'c1');
  assert.equal(summary.revenue, 200);
  assert.equal(summary.tickets, 2);
  assert.equal(summary.orders, 1);
  assert.deepEqual(summary.days, { '2026-11-10': 200 });
  assert.deepEqual(summarizeSales(sales, '2027-01-01', '2027-01-02').movies, []);
});

test('generates bounded seat layouts with unique row/number pairs', () => {
  const layout = createSeatLayout(6, 10, 'p1', 'ACTIVE');
  assert.equal(layout.length, 60);
  assert.deepEqual(layout[59], { row: 'F', col: 10, number: 10, typeId: 'p1', status: 'ACTIVE' });
  assert.equal(new Set(layout.map(seat => seat.row + seat.number)).size, 60);
  assert.deepEqual(createSeatLayout(21, 10, 'p1'), []);
  assert.deepEqual(createSeatLayout(1.5, 10, 'p1'), []);
  assert.deepEqual(createSeatLayout(6, 0, 'p1'), []);
  assert.deepEqual(createSeatLayout(6, 10, ''), []);
});

test('exports quoted sample CSV and prevents formula injection from movie titles', () => {
  const state = createAdminPreview();
  state.movies[0].title = '=HYPERLINK("bad")';
  const csv = reportCsv({ movies: [{ movieId: 'm1', tickets: 1, revenue: 120000 }] }, state);
  assert.ok(csv.includes('Sample report'));
  assert.ok(csv.includes('"\'=HYPERLINK(""bad"")"'));
  assert.ok(csv.includes('120000'));
});
