import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadBookingData } from '../../src/main/resources/static/js/modules/booking-data.js';
import { buildBookingPreview, bookingPreview } from '../../src/main/resources/static/js/modules/booking-preview.js';

const context = {
  showtimeId: '42', movieId: '16', movieTitle: 'Alien: Romulus', cinemaId: '3',
  cinemaName: 'BOX Landmark', roomName: 'Screen 2', screeningLabel: '14 Nov 2026 / 19:30',
  posterUrl: '/assets/alien.jpg', ageRating: 'T18', format: 'IMAX / English', basePrice: 140000
};
const response = (status, body) => ({ status, ok: status < 400, json: async () => body });

test('both entry routes can load a labelled seat screen from the selected showtime ID', async () => {
  const paths = [];
  const data = await loadBookingData('42', { request: async path => {
    paths.push(path);
    return path.startsWith('/api/booking/') ? response(404, {}) : response(200, context);
  } });
  assert.deepEqual(paths, ['/api/booking/showtimes/42', '/api/quick-booking/showtimes/42']);
  assert.equal(data.preview, true);
  for (const [key, value] of Object.entries(context)) assert.equal(data[key], value);
  assert.equal(data.seats.length, 80);
  assert.equal(data.seats[0].price, 140000);
  assert.equal(data.seats.find(seat => seat.type === 'VIP').price, 170000);
});

test('live inventory is used directly without replacing it with sample seats', async () => {
  let calls = 0;
  const live = { ...context, seats: [], products: [] };
  const data = await loadBookingData('42', { request: async () => { calls++; return response(200, live); } });
  assert.equal(data, live); assert.equal(calls, 1); assert.equal(data.preview, undefined);
});

test('authentication failures redirect without any fallback request', async () => {
  let calls = 0, redirects = 0;
  await assert.rejects(loadBookingData('42', {
    request: async () => { calls++; return response(401, {}); }, onUnauthenticated: () => redirects++
  }), /sign in/);
  assert.equal(calls, 1); assert.equal(redirects, 1);
});

test('outages, forbidden requests and aborted requests are not hidden with sample data', async () => {
  for (const status of [403, 409, 500, 503]) {
    let calls = 0;
    await assert.rejects(loadBookingData('42', { request: async () => { calls++; return response(status, { message: 'Service error' }); } }), /Service error/);
    assert.equal(calls, 1);
  }
  await assert.rejects(loadBookingData('42', { request: async () => { throw new DOMException('Cancelled', 'AbortError'); } }), { name: 'AbortError' });
});

test('nonexistent or expired real screenings do not turn into an unrelated preview', async () => {
  let calls = 0;
  await assert.rejects(loadBookingData('42', { request: async () => {
    calls++; return response(calls === 1 ? 404 : 409, { message: 'Screening expired' });
  } }), /Screening expired/);
  assert.equal(calls, 2);
});

test('sample selections have independent seat/product objects and require valid screening data', () => {
  const first = buildBookingPreview(context), second = buildBookingPreview(context);
  first.seats[0].price = 1; first.products[0].name = 'edited';
  assert.equal(second.seats[0].price, context.basePrice);
  assert.notEqual(second.products[0].name, 'edited');
  assert.equal(bookingPreview.seats[0].price, 90000);
  assert.throws(() => buildBookingPreview({ ...context, basePrice: -1 }));
  assert.throws(() => buildBookingPreview({ ...context, movieId: null }));
});
