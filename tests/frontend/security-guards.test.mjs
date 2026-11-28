import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const root = process.cwd();
const read = rel => fs.readFileSync(path.resolve(root, rel), 'utf8');

test('public/scanner.html blocks external open redirects and allows only same-origin /t/ or BOX1 tokens', () => {
  const html = read('src/main/resources/templates/public/scanner.html');
  assert.match(html, /parsed\.origin !== window\.location\.origin \|\| !parsed\.pathname\.startsWith\('\/t\/'\)/);
  assert.match(html, /Invalid QR code: external or non-ticket URLs are not permitted/);
  assert.doesNotMatch(html, /window\.location\.href\s*=\s*text\s*;/);
});

test('staff/checkin.html and staff/food.html include CSRF meta/headers and safe DOM rendering', () => {
  const checkin = read('src/main/resources/templates/staff/checkin.html');
  assert.match(checkin, /name="_csrf"/);
  assert.match(checkin, /X-CSRF-TOKEN/);
  assert.equal(/\.\s*innerHTML\s*=/.test(checkin), false, 'staff/checkin.html must have zero innerHTML assignments');

  const food = read('src/main/resources/templates/staff/food.html');
  assert.match(food, /name="_csrf"/);
  assert.match(food, /X-CSRF-TOKEN/);
  assert.equal(/\.\s*innerHTML\s*=/.test(food), false, 'staff/food.html must have zero innerHTML assignments');
});

test('admin.js escapes dynamic fields in innerHTML and attaches CSRF headers on mutations', () => {
  const adminJs = read('src/main/resources/static/js/modules/admin.js');
  assert.match(adminJs, /(?:function|const)\s+escapeHtml\b/);
  assert.match(adminJs, /escapeHtml\(b\.customerName/);
  assert.match(adminJs, /escapeHtml\(b\.movieTitle/);
  assert.match(adminJs, /getCsrfHeaders\(\)/);
});

test('navbar.js avoids innerHTML option injection and wires My Vouchers modal to /api/vouchers/my', () => {
  const navbarJs = read('src/main/resources/static/js/modules/navbar.js');
  assert.match(navbarJs, /function populateSelectOptions\(/);
  assert.match(navbarJs, /\/api\/vouchers\/my/);
  assert.doesNotMatch(navbarJs, /My Vouchers feature is coming soon/);
  assert.equal(/\.innerHTML\s*=\s*(?:[^;]*\$\{|[^;]*\+)/.test(navbarJs), false, 'navbar.js must not interpolate variables into innerHTML');
});

test('showtimes.js validates media URLs and contact.html reflects incident/refund policy', () => {
  const showtimesJs = read('src/main/resources/static/js/modules/showtimes.js');
  assert.match(showtimesJs, /safeMediaUrl\(/);
  assert.match(showtimesJs, /escapeHtml\(movie\.ageRating/);

  const contactHtml = read('src/main/resources/templates/customer/contact.html');
  assert.doesNotMatch(contactHtml, />Payment &amp; Transaction Support \(No-Refund Policy\)</);
  assert.match(contactHtml, />Payment &amp; Transaction Support \(Incident &amp; Refund Policy\)</);
});
