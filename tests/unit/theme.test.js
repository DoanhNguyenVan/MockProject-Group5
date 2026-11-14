import fs from "node:fs";
import path from "node:path";
import { describe, it, expect, vi } from "vitest";
import { JSDOM } from "jsdom";
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

function createThemeDom() {
  const dom = new JSDOM(`<!DOCTYPE html>
  <html>
    <body>
      <button id="navThemeToggle" type="button" data-theme-toggle aria-pressed="false"></button>
      <button id="mobileThemeToggle" type="button" data-theme-toggle aria-pressed="false">
        <span data-theme-label>Dark Mode</span>
      </button>
    </body>
  </html>`);
  globalThis.CustomEvent = dom.window.CustomEvent;
  return dom.window.document;
}

describe("theme core module", () => {
  it("normalizes supported theme values and defaults to dark", () => {
    expect(DEFAULT_THEME).toBe("dark");
    expect(normalizeTheme("light")).toBe("light");
    expect(normalizeTheme("dark")).toBe("dark");
    expect(normalizeTheme("invalid")).toBe("dark");
    expect(normalizeTheme(null)).toBe("dark");
    expect(normalizeTheme(undefined)).toBe("dark");
  });

  it("reads and persists themes safely in storage", () => {
    const storage = createMemoryStorage();
    expect(getStoredTheme(storage)).toBe("dark");

    expect(saveTheme("light", storage)).toBe("light");
    expect(storage.getItem(THEME_STORAGE_KEY)).toBe("light");
    expect(getStoredTheme(storage)).toBe("light");

    expect(saveTheme("unexpected", storage)).toBe("dark");
    expect(getStoredTheme(storage)).toBe("dark");
  });

  it("falls back safely when storage is unavailable or throws", () => {
    const brokenStorage = {
      getItem: () => {
        throw new Error("blocked");
      },
      setItem: () => {
        throw new Error("blocked");
      }
    };

    expect(getStoredTheme(null)).toBe("dark");
    expect(getStoredTheme(brokenStorage)).toBe("dark");
    expect(saveTheme("light", brokenStorage)).toBe("light");
  });

  it("applies theme attributes, updates toggle metadata, and dispatches change event", () => {
    const doc = createThemeDom();
    const events = [];
    doc.addEventListener("box:theme-changed", (event) => {
      events.push(event.detail.theme);
    });

    const applied = applyTheme("light", doc);
    expect(applied).toBe("light");
    expect(doc.documentElement.getAttribute("data-theme")).toBe("light");
    expect(doc.documentElement.getAttribute("data-bs-theme")).toBe("light");

    const desktopBtn = doc.getElementById("navThemeToggle");
    const mobileBtn = doc.getElementById("mobileThemeToggle");
    expect(desktopBtn.getAttribute("aria-pressed")).toBe("true");
    expect(desktopBtn.getAttribute("aria-label")).toBe("Switch to dark mode");
    expect(mobileBtn.getAttribute("aria-pressed")).toBe("true");
    expect(mobileBtn.querySelector("[data-theme-label]").textContent).toBe("Light Mode");
    expect(events).toEqual(["light"]);

    syncThemeToggleButtons("dark", doc);
    expect(desktopBtn.getAttribute("aria-pressed")).toBe("false");
    expect(desktopBtn.getAttribute("aria-label")).toBe("Switch to light mode");
    expect(mobileBtn.querySelector("[data-theme-label]").textContent).toBe("Dark Mode");
  });

  it("toggles between dark and light and initializes click handlers without double-binding", () => {
    const doc = createThemeDom();
    const storage = createMemoryStorage({ [THEME_STORAGE_KEY]: "light" });

    const initial = initThemeToggle(doc, storage);
    expect(initial).toBe("light");
    expect(doc.documentElement.getAttribute("data-theme")).toBe("light");

    const desktopBtn = doc.getElementById("navThemeToggle");
    const mobileBtn = doc.getElementById("mobileThemeToggle");
    const addListenerSpy = vi.spyOn(desktopBtn, "addEventListener");

    // Re-initializing must not attach duplicate click listeners
    initThemeToggle(doc, storage);
    expect(addListenerSpy).not.toHaveBeenCalled();

    desktopBtn.click();
    expect(doc.documentElement.getAttribute("data-theme")).toBe("dark");
    expect(storage.getItem(THEME_STORAGE_KEY)).toBe("dark");
    expect(mobileBtn.getAttribute("aria-pressed")).toBe("false");

    mobileBtn.click();
    expect(doc.documentElement.getAttribute("data-theme")).toBe("light");
    expect(storage.getItem(THEME_STORAGE_KEY)).toBe("light");
    expect(desktopBtn.getAttribute("aria-pressed")).toBe("true");

    expect(toggleTheme(doc, storage)).toBe("dark");
    expect(storage.getItem(THEME_STORAGE_KEY)).toBe("dark");
    expect(doc.documentElement.getAttribute("data-theme")).toBe("dark");
  });

  it("embeds zero-FOUC inline theme script and both toggles in shared navbar fragment", () => {
    const navbarPath = path.resolve(
      process.cwd(),
      "src/main/resources/templates/fragments/navbar.html"
    );
    const navbarHtml = fs.readFileSync(navbarPath, "utf8");

    expect(navbarHtml).toContain("box_cinema_theme");
    expect(navbarHtml).toContain("data-theme");
    expect(navbarHtml).toContain('id="navThemeToggle"');
    expect(navbarHtml).toContain('id="mobileThemeToggle"');
    expect(navbarHtml).toContain("data-theme-toggle");
  });
});
