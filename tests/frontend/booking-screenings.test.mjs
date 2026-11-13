import { test } from 'node:test';
import assert from 'node:assert/strict';
import { normalizeSearch, findCinemas, dateParts, groupShowtimes } from '../../src/main/resources/static/js/modules/booking-screenings.js';

const cinemas = [
  { cinemaId: 1, cinemaName: 'BOX Gò Vấp', city: 'Hồ Chí Minh' },
  { cinemaId: 2, cinemaName: 'BOX Ba Đình', city: 'Hà Nội' },
  { cinemaId: 3, cinemaName: 'BOX Hải Châu', city: 'Đà Nẵng' },
  { cinemaId: 4, cinemaName: 'BOX Bình Thạnh', city: 'Hồ Chí Minh' }
];

test('search finds cinemas outside suggestions and accepts Vietnamese without accents', () => {
  assert.equal(normalizeSearch('  ĐÀ NẴNG  '), 'da nang');
  assert.equal(findCinemas(cinemas, 'binh thanh')[0].cinemaId, 4);
  assert.equal(findCinemas(cinemas, 'da nang')[0].cinemaId, 3);
  assert.equal(findCinemas(cinemas, 'go vap ho chi minh')[0].cinemaId, 1);
  assert.equal(findCinemas(cinemas, 'unknown').length, 0);
});

test('city filter and preference preserve matching and original source order', () => {
  assert.deepEqual(findCinemas(cinemas, '', 'Hồ Chí Minh', '4').map(c => c.cinemaId), [4, 1]);
  assert.deepEqual(findCinemas(cinemas, '', '', '4').slice(0, 3).map(c => c.cinemaId), [4, 1, 2]);
  assert.deepEqual(cinemas.map(c => c.cinemaId), [1, 2, 3, 4]);
  assert.equal(findCinemas(cinemas, 'ha noi', 'Đà Nẵng').length, 0);
});

test('calendar labels preserve the cinema date regardless of browser timezone', () => {
  assert.deepEqual(dateParts('2026-11-13'), { day: '13', weekday: 'Fri', month: 'Nov', full: 'Friday, November 13, 2026' });
  assert.equal(dateParts('2026-02-30'), null);
  assert.equal(dateParts('invalid'), null);
  assert.equal(dateParts('2026-12-01').day, '01');
});

test('showtimes group by room format and retain distinct rooms and identifiers', () => {
  const groups = groupShowtimes([
    { showtimeId: 1, roomType: '2D', timeDisplay: '19:30', roomName: '1' },
    { showtimeId: 2, roomType: 'IMAX', timeDisplay: '20:00' },
    { showtimeId: 3, roomType: '2D', timeDisplay: '19:30', roomName: '2' },
    { timeDisplay: '21:00' }
  ]);
  assert.deepEqual([...groups.keys()], ['2D', 'IMAX']);
  assert.deepEqual(groups.get('2D').map(s => s.showtimeId), [1, 3]);
  assert.equal(groupShowtimes([]).size, 0);
});
