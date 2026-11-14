export const THEME_STORAGE_KEY = "box_cinema_theme";
export const DEFAULT_THEME = "dark";

export function normalizeTheme(value) {
  return value === "light" ? "light" : DEFAULT_THEME;
}

export function getStoredTheme(storage = typeof localStorage !== "undefined" ? localStorage : null) {
  if (!storage) return DEFAULT_THEME;
  try {
    return normalizeTheme(storage.getItem(THEME_STORAGE_KEY));
  } catch (_) {
    return DEFAULT_THEME;
  }
}

export function saveTheme(theme, storage = typeof localStorage !== "undefined" ? localStorage : null) {
  const resolved = normalizeTheme(theme);
  if (!storage) return resolved;
  try {
    storage.setItem(THEME_STORAGE_KEY, resolved);
  } catch (_) {}
  return resolved;
}

export function syncThemeToggleButtons(theme, doc = document) {
  if (!doc) return;
  const resolved = normalizeTheme(theme);
  const isLight = resolved === "light";
  const actionLabel = isLight ? "Switch to dark mode" : "Switch to light mode";
  const statusText = isLight ? "Light Mode" : "Dark Mode";

  const buttons = doc.querySelectorAll("[data-theme-toggle]");
  buttons.forEach((btn) => {
    btn.setAttribute("aria-pressed", String(isLight));
    btn.setAttribute("aria-label", actionLabel);
    btn.setAttribute("title", actionLabel);

    const labelEl = btn.querySelector("[data-theme-label]");
    if (labelEl) {
      labelEl.textContent = statusText;
    }
  });
}

export function applyTheme(theme, doc = document) {
  const resolved = normalizeTheme(theme);
  if (!doc || !doc.documentElement) return resolved;

  doc.documentElement.setAttribute("data-theme", resolved);
  doc.documentElement.setAttribute("data-bs-theme", resolved);
  syncThemeToggleButtons(resolved, doc);

  if (typeof CustomEvent === "function") {
    doc.dispatchEvent(new CustomEvent("box:theme-changed", {
      detail: { theme: resolved }
    }));
  }

  return resolved;
}

export function toggleTheme(
  doc = document,
  storage = typeof localStorage !== "undefined" ? localStorage : null
) {
  const current = doc?.documentElement?.getAttribute("data-theme");
  const next = normalizeTheme(current) === "light" ? "dark" : "light";
  saveTheme(next, storage);
  return applyTheme(next, doc);
}

export function initThemeToggle(
  doc = document,
  storage = typeof localStorage !== "undefined" ? localStorage : null
) {
  if (!doc) return DEFAULT_THEME;

  const initialTheme = getStoredTheme(storage);
  applyTheme(initialTheme, doc);

  const buttons = doc.querySelectorAll("[data-theme-toggle]");
  buttons.forEach((btn) => {
    if (btn.dataset.themeBound === "true") return;
    btn.dataset.themeBound = "true";
    btn.addEventListener("click", () => {
      toggleTheme(doc, storage);
    });
  });

  return initialTheme;
}
