import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  calculateTotals,
  changeQuantity,
  formatCountdown,
  formatVnd,
  safeImageUrl,
  toggleSeat,
  validateBooking
} from '../../src/main/resources/static/js/modules/booking.js';
import { bookingPreview } from '../../src/main/resources/static/js/modules/booking-preview.js';

const fixture = () => structuredClone(bookingPreview);

test('selects and removes available seats without mutating state', () => {
  const selected = [];
  assert.deepEqual(toggleSeat(selected, fixture().seats[0]).selected, ['A1']);
  assert.deepEqual(selected, []);
  assert.deepEqual(toggleSeat(['A1'], fixture().seats[0]).selected, []);
});

test('enforces Sweetbox couple pairing and max seat limit', () => {
  const pair = [
    { id: 'G1', row: 'G', number: 1, status: 'AVAILABLE', pairId: 'PAIR_G1_G2' },
    { id: 'G2', row: 'G', number: 2, status: 'AVAILABLE', pairId: 'PAIR_G1_G2' }
  ];
  const selectedPair = toggleSeat([], pair[0], 8, pair);
  assert.deepEqual(selectedPair.selected, ['G1', 'G2']);
  assert.equal(selectedPair.error, '');
  assert.deepEqual(toggleSeat(['G1', 'G2'], pair[1], 8, pair).selected, []);
  const almostFull = Array.from({ length: 7 }, (_, i) => `A${i + 1}`);
  const overLimit = toggleSeat(almostFull, pair[0], 8, pair);
  assert.ok(overLimit.error.includes('8'));
  assert.deepEqual(overLimit.selected, almostFull);
});

test('rejects booked and held seats and limits selection to eight', () => {
  const seat = fixture().seats[0];
  assert.ok(toggleSeat([], { ...seat, status: 'HELD' }).error);
  assert.deepEqual(toggleSeat([], { ...seat, status: 'BOOKED' }).selected, []);
  assert.ok(toggleSeat(Array.from({ length: 8 }, (_, i) => `B${i}`), seat).error.includes('8'));
});

test('calculates mixed seat prices and extras, ignoring unknown or unavailable seats', () => {
  const data = fixture();
  assert.deepEqual(
    calculateTotals(data.seats, ['A1', 'A1', 'D1', 'A4', 'unknown'], data.products, { solo: 2, drink: 1 }),
    { tickets: 210000, extras: 213000, total: 423000 }
  );
  assert.deepEqual(calculateTotals(data.seats, [], data.products, {}), { tickets: 0, extras: 0, total: 0 });
});

test('bounds combo quantities and formats VND and countdown timers', () => {
  assert.equal(changeQuantity(undefined, 1), 1);
  assert.equal(changeQuantity(0, -1), 0);
  assert.equal(changeQuantity(10, 1), 10);
  assert.equal(changeQuantity(2.5, 1), 3);
  assert.equal(formatVnd(120000).replace(/\s/g, ''), '120.000₫');
  assert.equal(formatCountdown(600), '10:00');
  assert.equal(formatCountdown(-5), '00:00');
});

test('accepts documented DTOs and rejects unsafe prices, duplicate seats and unknown states', () => {
  assert.equal(validateBooking(fixture()).seats.length, 80);
  for (const change of [s => { s.price = -1; }, s => { s.price = '90000'; }, s => { s.status = 'OTHER'; }]) {
    const data = fixture();
    change(data.seats[0]);
    assert.throws(() => validateBooking(data));
  }
  const duplicate = fixture();
  duplicate.seats.push(duplicate.seats[0]);
  assert.throws(() => validateBooking(duplicate));
  assert.throws(() => validateBooking(null));
});

test('restricts image protocols against javascript and protocol-relative URLs', () => {
  assert.equal(safeImageUrl('javascript:alert(1)'), '/assets/img/crimson-horizon.webp');
  assert.equal(safeImageUrl('//evil.test/image'), '/assets/img/crimson-horizon.webp');
  assert.equal(safeImageUrl('/assets/test.jpg'), '/assets/test.jpg');
  assert.ok(safeImageUrl('https://example.test/poster.jpg').startsWith('https:'));
});
