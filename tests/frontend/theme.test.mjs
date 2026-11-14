import fs from "node:fs";
import path from "node:path";
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  THEME_STORAGE_KEY,
  DEFAULT_THEME,
  normalizeTheme,
  getStoredTheme,
  saveTheme,
  syncThemeToggleButtons,
  applyTheme,
  toggleTheme,
  initThemeToggle
} from "../../src/main/resources/static/js/core/theme.js";

function createMemoryStorage(initial = {}) {
  const store = new Map(Object.entries(initial));
  return {
    getItem: (key) => (store.has(key) ? store.get(key) : null),
    setItem: (key, value) => store.set(key, String(value))
  };
}

function createMockElement(hasLabel = false) {
  const attrs = {};
  const listeners = new Map();
  const labelEl = hasLabel ? { textContent: "Dark Mode" } : null;
  return {
    dataset: {},
    setAttribute: (k, v) => { attrs[k] = String(v); },
    getAttribute: (k) => (k in attrs ? attrs[k] : null),
    querySelector: (selector) => (selector === "[data-theme-label]" ? labelEl : null),
    addEventListener: (type, handler) => {
      const list = listeners.get(type) || [];
      list.push(handler);
      listeners.set(type, list);
    },
    click: () => {
      for (const handler of listeners.get("click") || []) handler();
    },
    listenerCount: (type) => (listeners.get(type) || []).length
  };
}

function createMockDocument() {
  const rootAttrs = {};
  const dispatched = [];
  const docListeners = new Map();
  const desktopBtn = createMockElement(false);
  const mobileBtn = createMockElement(true);

  return {
    desktopBtn,
    mobileBtn,
    dispatched,
    documentElement: {
      setAttribute: (k, v) => { rootAttrs[k] = String(v); },
      getAttribute: (k) => (k in rootAttrs ? rootAttrs[k] : null)
    },
    querySelectorAll: (selector) => (selector === "[data-theme-toggle]" ? [desktopBtn, mobileBtn] : []),
    addEventListener: (type, handler) => {
      const list = docListeners.get(type) || [];
      list.push(handler);
      docListeners.set(type, list);
    },
    dispatchEvent: (event) => {
      dispatched.push(event);
      for (const handler of docListeners.get(event.type) || []) handler(event);
    }
  };
}

test("normalizeTheme defaults invalid values to dark", () => {
  assert.equal(DEFAULT_THEME, "dark");
  assert.equal(normalizeTheme("light"), "light");
  assert.equal(normalizeTheme("dark"), "dark");
  assert.equal(normalizeTheme("auto"), "dark");
  assert.equal(normalizeTheme(null), "dark");
});

test("getStoredTheme and saveTheme persist cleanly and handle errors", () => {
  const storage = createMemoryStorage();
  assert.equal(getStoredTheme(storage), "dark");
  assert.equal(saveTheme("light", storage), "light");
  assert.equal(storage.getItem(THEME_STORAGE_KEY), "light");
  assert.equal(getStoredTheme(storage), "light");

  const broken = {
    getItem: () => { throw new Error("blocked"); },
    setItem: () => { throw new Error("blocked"); }
  };
  assert.equal(getStoredTheme(broken), "dark");
  assert.equal(saveTheme("light", broken), "light");
});

test("applyTheme, syncThemeToggleButtons, toggleTheme, and initThemeToggle update DOM and storage", () => {
  const doc = createMockDocument();
  const storage = createMemoryStorage({ [THEME_STORAGE_KEY]: "light" });
  const events = [];
  doc.addEventListener("box:theme-changed", (e) => events.push(e.detail.theme));

  const initial = initThemeToggle(doc, storage);
  assert.equal(initial, "light");
  assert.equal(doc.documentElement.getAttribute("data-theme"), "light");
  assert.equal(doc.documentElement.getAttribute("data-bs-theme"), "light");
  assert.equal(doc.desktopBtn.getAttribute("aria-pressed"), "true");
  assert.equal(doc.mobileBtn.querySelector("[data-theme-label]").textContent, "Light Mode");

  // Re-initializing does not duplicate click listeners
  initThemeToggle(doc, storage);
  assert.equal(doc.desktopBtn.listenerCount("click"), 1);
  assert.equal(doc.mobileBtn.listenerCount("click"), 1);

  doc.desktopBtn.click();
  assert.equal(doc.documentElement.getAttribute("data-theme"), "dark");
  assert.equal(storage.getItem(THEME_STORAGE_KEY), "dark");
  assert.equal(doc.mobileBtn.getAttribute("aria-pressed"), "false");

  doc.mobileBtn.click();
  assert.equal(doc.documentElement.getAttribute("data-theme"), "light");
  assert.equal(storage.getItem(THEME_STORAGE_KEY), "light");

  assert.equal(toggleTheme(doc, storage), "dark");
  assert.equal(storage.getItem(THEME_STORAGE_KEY), "dark");
  assert.deepEqual(events, ["light", "light", "dark", "light", "dark"]);

  syncThemeToggleButtons("light", doc);
  assert.equal(doc.desktopBtn.getAttribute("aria-label"), "Switch to dark mode");

  applyTheme("dark", doc);
  assert.equal(doc.documentElement.getAttribute("data-theme"), "dark");
});

test("navbar fragment includes FOUC script and both desktop/mobile theme toggles", () => {
  const navbarPath = path.resolve(
    process.cwd(),
    "src/main/resources/templates/fragments/navbar.html"
  );
  const html = fs.readFileSync(navbarPath, "utf8");
  assert.ok(html.includes("box_cinema_theme"));
  assert.ok(html.includes('id="navThemeToggle"'));
  assert.ok(html.includes('id="mobileThemeToggle"'));
  assert.ok(html.includes("data-theme-toggle"));
});
