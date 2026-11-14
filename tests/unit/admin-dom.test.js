import fs from 'node:fs';
import { describe, it, expect, vi } from 'vitest';
import { JSDOM } from 'jsdom';
import { initAdmin } from '../../src/main/resources/static/js/modules/admin.js';

const template = fs.readFileSync(new URL('../../src/main/resources/templates/admin/workspace.html', import.meta.url), 'utf8');
function workspace(section, stored) {
  const dom = new JSDOM(template, { url: `https://box.test/admin/${section}` });
  const { document } = dom.window;
  document.querySelector('[data-admin-section]').dataset.adminSection = section;
  if (stored) dom.window.sessionStorage.setItem('box_admin_preview_v1', stored);
  // JSDOM does not implement native dialog display/focus behavior.
  dom.window.HTMLDialogElement.prototype.showModal = function () { this.open = true; };
  dom.window.HTMLDialogElement.prototype.close = function () { this.open = false; this.dispatchEvent(new dom.window.Event('close')); };
  initAdmin(document);
  return { dom, document, get: id => document.getElementById(id), event: type => new dom.window.Event(type, { bubbles: true, cancelable: true }) };
}

describe('admin workspace interaction', () => {
  it('renders every section without making management API requests', () => {
    const fetch = vi.spyOn(globalThis, 'fetch');
    try {
      for (const section of ['dashboard', 'movies', 'genres', 'cinemas', 'rooms', 'seats', 'showtimes', 'pricing', 'promotions', 'combos', 'staff', 'reports']) {
        const { dom, document } = workspace(section);
        expect(document.querySelectorAll('#adminNavigation a')).toHaveLength(12);
        expect(document.querySelector('[aria-current="page"]').getAttribute('href')).toBe(`/admin/${section}`);
        expect(document.title).toContain('BOX Cinema Admin');
        dom.window.close();
      }
      expect(fetch).not.toHaveBeenCalled();
    } finally { fetch.mockRestore(); }
  });
  it('searches, filters, paginates and shows a recoverable empty state', () => {
    const { dom, get, event } = workspace('movies');
    expect(get('adminTableBody').children).toHaveLength(6);
    get('adminNext').click(); expect(get('adminTableBody').children).toHaveLength(1);
    get('adminSearch').value = 'unknown film'; get('adminSearch').dispatchEvent(event('input'));
    expect(get('adminEmpty').hidden).toBe(false);
    get('adminClearFilters').click();
    get('adminFilter').value = 'COMING_SOON'; get('adminFilter').dispatchEvent(event('change'));
    expect(get('adminTableBody').children).toHaveLength(2);
    dom.window.close();
  });
  it('validates editing, saves safely and retains changes on navigation', () => {
    const { dom, get, event } = workspace('movies');
    get('adminTableBody').querySelector('button').click();
    get('adminField-title').value = '';
    get('adminEditorForm').dispatchEvent(event('submit'));
    expect(get('adminField-title').getAttribute('aria-invalid')).toBe('true');
    get('adminField-title').value = '<img src=x onerror=alert(1)>';
    get('adminEditorForm').dispatchEvent(event('submit'));
    expect(get('adminEditor').open).toBe(false);
    expect(get('adminTableBody').textContent).toContain('<img src=x onerror=alert(1)>');
    expect(get('adminTableBody').querySelector('img')).toBeNull();
    const next = workspace('movies', dom.window.sessionStorage.getItem('box_admin_preview_v1'));
    expect(next.get('adminTableBody').textContent).toContain('<img src=x onerror=alert(1)>');
    next.dom.window.close(); dom.window.close();
  });
  it('prevents referenced deletion and requires confirmation for an unused record', () => {
    const { dom, get } = workspace('movies');
    get('adminTableBody').querySelector('[aria-label="Delete Interstellar"]').click();
    expect(get('adminStatus').textContent).toContain('showtimes');
    expect(get('adminConfirm').open).toBe(false);
    get('adminNext').click(); get('adminTableBody').querySelector('[aria-label="Delete Soul"]').click();
    expect(get('adminConfirm').open).toBe(true);
    get('adminConfirm').returnValue = 'confirm'; get('adminConfirm').close();
    expect(get('adminPageInfo').textContent).toContain('6 records');
    dom.window.close();
  });
  it('paints and saves a room layout without changing the other rooms', () => {
    const { dom, get, event } = workspace('seats');
    get('adminSeatPaint').value = 'p2'; get('adminSeatGrid').children[0].click();
    expect(get('adminSeatGrid').children[0].dataset.type).toBe('VIP');
    get('adminSaveSeats').click();
    const saved = JSON.parse(dom.window.sessionStorage.getItem('box_admin_preview_v1'));
    expect(saved.layouts.r1[0].typeId).toBe('p2'); expect(saved.layouts.r2).toBeUndefined();
    get('adminSeatRoom').value = 'r2'; get('adminSeatRoom').dispatchEvent(event('change'));
    expect(get('adminSeatGrid').children[0].dataset.type).toBe('STANDARD');
    dom.window.close();
  });
  it('filters reports and disables export for an empty period', () => {
    const { dom, get, event } = workspace('reports');
    expect(get('adminReportBody').children).toHaveLength(3);
    get('adminReportFrom').value = '2027-01-01'; get('adminReportTo').value = '2027-01-02';
    get('adminReportFilters').dispatchEvent(event('submit'));
    expect(get('adminReportEmpty').hidden).toBe(false); expect(get('adminExportReport').disabled).toBe(true);
    dom.window.close();
  });
  it('recovers from damaged storage and toggles the mobile menu', () => {
    const { dom, get } = workspace('dashboard', '{broken');
    expect(get('adminStatus').textContent).toContain('fresh sample workspace');
    get('adminMenuToggle').click(); expect(get('adminMenuToggle').getAttribute('aria-expanded')).toBe('true');
    get('adminMenuToggle').click(); expect(get('adminMenuToggle').getAttribute('aria-expanded')).toBe('false');
    dom.window.close();
  });
});
