import { httpRequest } from "../core/http-client.js";

export function normalizeSearch(value) {
  return String(value ?? "").normalize("NFD").replace(/[\u0300-\u036f]/g, "").replace(/[đĐ]/g, "d").toLowerCase().trim();
}

export function findCinemas(cinemas, search = "", city = "", preferredId = "") {
  const words = normalizeSearch(search).split(/\s+/).filter(Boolean);
  return cinemas.filter(cinema => (!city || cinema.city === city)
    && words.every(word => normalizeSearch(`${cinema.cinemaName} ${cinema.city || ""}`).includes(word)))
    .sort((a, b) => Number(String(b.cinemaId) === preferredId) - Number(String(a.cinemaId) === preferredId));
}

export function groupShowtimes(slots) {
  const groups = new Map();
  for (const slot of slots) {
    if (slot.showtimeId == null || !slot.timeDisplay) continue;
    const format = slot.roomType || "Standard";
    if (!groups.has(format)) groups.set(format, []);
    groups.get(format).push(slot);
  }
  return groups;
}

export function dateParts(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
  const date = new Date(`${value}T12:00:00Z`);
  if (!Number.isFinite(date.getTime()) || date.toISOString().slice(0, 10) !== value) return null;
  return {
    day: value.slice(-2), weekday: new Intl.DateTimeFormat("en", { weekday: "short", timeZone: "UTC" }).format(date),
    month: new Intl.DateTimeFormat("en", { month: "short", timeZone: "UTC" }).format(date),
    full: new Intl.DateTimeFormat("en", { dateStyle: "full", timeZone: "UTC" }).format(date)
  };
}

/** Movie poster is server-rendered; existing discovery APIs supply cinemas, dates and times. */
export function initScreenings(doc = document, { onSelect = () => {}, onReset = () => {} } = {}) {
  const root = doc.getElementById("bookingPicker");
  if (!root) return null;
  const win = doc.defaultView;
  const el = name => doc.getElementById(`screening${name}`);
  const query = new URLSearchParams(win.location.search);
  const movieId = root.dataset.movieId || query.get("movieId") || "";
  let cinemas = [], dates = [], date = "", search = "", city = "", limit = 3;
  let preferredId = query.get("cinemaId") || "", initialController, slotsController, revision = 0, searchTimer;
  let loaded = false;
  const cache = new Map();
  try { preferredId ||= win.localStorage.getItem("box_cinema_selected_branch_id") || ""; } catch (_) {}
  function node(tag, text, className) {
    const item = doc.createElement(tag); if (text != null) item.textContent = text; if (className) item.className = className; return item;
  }
  function button(text, className, action) {
    const item = node("button", text, className); item.type = "button"; item.addEventListener("click", action); return item;
  }
  function message(text = "", retry = false) { el("Message").textContent = text; el("Message").hidden = !text; el("Retry").hidden = !retry; }
  async function read(path, signal) {
    const response = await httpRequest(`/api/quick-booking/${path}`, { signal });
    if (response.status === 401 || (response.redirected && new URL(response.url).pathname === "/login")) { win.location.assign("/login"); throw new Error("Please sign in to continue."); }
    if (!response.ok) { const body = await response.json().catch(() => ({})); throw new Error(body.message || "We could not load screenings. Please try again."); }
    const result = await response.json();
    if (!Array.isArray(result)) throw new Error("Screening information is unavailable. Please try again.");
    return result;
  }
  function renderDates() {
    el("Dates").replaceChildren();
    for (const item of dates) {
      const parts = dateParts(item.value); if (!parts) continue;
      const tab = button("", "screening-date", () => {
        if (date === item.value) return;
        date = item.value; cache.clear(); onReset(); updateUrl(); renderDates(); renderResults();
      });
      tab.dataset.date = item.value; tab.setAttribute("aria-pressed", String(item.value === date)); tab.setAttribute("aria-label", parts.full);
      tab.append(node("span", parts.weekday), node("strong", parts.day), node("small", parts.month)); el("Dates").append(tab);
    }
    const parts = dateParts(date); el("Month").textContent = parts ? `${parts.month} ${date.slice(0, 4)}` : "";
  }
  function renderCities() {
    el("Cities").replaceChildren();
    const preferredCity = cinemas.find(c => String(c.cinemaId) === preferredId)?.city;
    const options = [...new Set([preferredCity, ...cinemas.map(c => c.city)].filter(Boolean))].slice(0, 3);
    for (const value of ["", ...options]) {
      const chip = button(value || "All cities", "screening-city", () => {
        clearTimeout(searchTimer);
        city = value; search = ""; el("Search").value = ""; el("Clear").hidden = true; limit = 3;
        onReset(); renderCities(); renderResults();
      });
      chip.setAttribute("aria-pressed", String(city === value)); el("Cities").append(chip);
    }
  }
  function updateUrl() {
    const url = new URL(win.location.href); url.searchParams.delete("showtimeId");
    if (date) url.searchParams.set("date", date);
    win.history.replaceState({}, "", url);
  }
  async function renderResults() {
    slotsController?.abort(); slotsController = new AbortController();
    const signal = slotsController.signal, current = ++revision;
    const matches = findCinemas(cinemas, search, city, preferredId), visible = matches.slice(0, limit);
    el("Results").replaceChildren(); el("Results").setAttribute("aria-busy", "true");
    el("ResultsTitle").textContent = search ? "Search results" : city ? `Cinemas in ${city}` : "Suggested cinemas";
    el("ResultCount").textContent = `${visible.length} of ${matches.length} cinemas`;
    el("More").hidden = visible.length >= matches.length;
    el("More").textContent = `Show more cinemas (${Math.max(0, matches.length - visible.length)} remaining)`;
    if (!visible.length || !date) {
      message(!cinemas.length ? "No cinemas are currently showing this movie." : !date ? "No screening dates are available." : "No cinemas match your search. Try another name or city.");
      el("Results").setAttribute("aria-busy", "false"); return;
    }
    message();
    const tasks = visible.map(cinema => {
      const card = node("article", null, "screening-cinema"); card.dataset.cinemaId = cinema.cinemaId;
      card.append(node("h4", cinema.cinemaName), node("p", cinema.city || "", "screening-cinema-location"));
      const times = node("div"); times.append(node("p", "Loading showtimes...", "screening-card-status")); card.append(times); el("Results").append(card);
      return { cinema, times };
    });
    // Bound parallel reads even after expanding results; only displayed cinemas are fetched.
    let cursor = 0;
    async function worker() {
      while (cursor < tasks.length && current === revision) {
        const { cinema, times } = tasks[cursor++];
        const key = `${cinema.cinemaId}:${date}`;
        try {
          let slots = cache.get(key);
          if (!slots) {
            slots = await read(`showtimes?${new URLSearchParams({ movieId, cinemaId: cinema.cinemaId, date })}`, signal);
            if (current !== revision) return;
            cache.set(key, slots);
          }
          if (current !== revision) return;
          times.replaceChildren();
          const groups = groupShowtimes(slots);
          if (!groups.size) times.append(node("p", "No showtimes on this date. Try another day.", "screening-card-status"));
          for (const [format, group] of groups) {
            times.append(node("span", format, "screening-format"));
            const row = node("div", null, "screening-times");
            for (const slot of group) {
              const link = button(slot.timeDisplay, "screening-time", () => {
                const url = new URL(win.location.href); url.searchParams.set("movieId", movieId); url.searchParams.set("showtimeId", slot.showtimeId); url.searchParams.set("cinemaId", cinema.cinemaId); url.searchParams.set("date", date);
                win.history.replaceState({}, "", url); onSelect(String(slot.showtimeId));
              });
              link.setAttribute("aria-label", `${slot.timeDisplay}, ${format}, ${slot.roomName || ""}, ${cinema.cinemaName}, ${date}`);
              row.append(link);
            }
            times.append(row);
          }
        } catch (error) {
          if (signal.aborted || current !== revision) return;
          times.replaceChildren(node("p", error.message, "screening-card-status"), button("Retry showtimes", "booking-secondary", () => renderResults()));
        }
      }
    }
    await Promise.all([worker(), worker(), worker()]);
    if (current === revision) el("Results").setAttribute("aria-busy", "false");
  }
  async function load() {
    initialController?.abort(); initialController = new AbortController(); const signal = initialController.signal;
    message("Finding screenings..."); el("Retry").hidden = true;
    if (root.dataset.movieMissing === "true") { message("This movie is no longer available. Please choose another movie."); return; }
    try {
      if (!movieId) {
        el("MovieChooser").hidden = false;
        const movies = await read("movies", signal);
        const placeholder = node("option", "Choose a movie"); placeholder.value = ""; el("MovieSelect").replaceChildren(placeholder);
        for (const movie of movies) { const option = node("option", movie.title); option.value = movie.movieId; el("MovieSelect").append(option); }
        message(movies.length ? "Choose a movie to see its screenings." : "No movies are available right now."); return;
      }
      const result = await Promise.all([read(`cinemas?movieId=${encodeURIComponent(movieId)}`, signal), read(`dates?movieId=${encodeURIComponent(movieId)}`, signal)]);
      if (signal.aborted) return;
      cinemas = result[0]; dates = result[1].filter(item => dateParts(item.value));
      date = dates.some(item => item.value === query.get("date")) ? query.get("date") : dates[0]?.value || "";
      loaded = true; renderDates(); renderCities(); await renderResults();
    } catch (error) { if (!signal.aborted) message(error.message, true); }
  }
  function applySearch() {
    clearTimeout(searchTimer); search = el("Search").value; city = ""; limit = search.trim() ? 6 : 3;
    el("Clear").hidden = !search; onReset(); renderCities(); if (loaded) renderResults();
  }
  el("Search").addEventListener("input", () => {
    // Invalidate pending times immediately, before debouncing the next search.
    onReset();
    slotsController?.abort(); ++revision;
    el("Results").replaceChildren(); el("More").hidden = true;
    if (loaded) message("Searching cinemas...");
    clearTimeout(searchTimer); searchTimer = setTimeout(applySearch, 180);
  });
  el("Clear").addEventListener("click", () => { el("Search").value = ""; applySearch(); el("Search").focus(); });
  el("More").addEventListener("click", () => { limit += 3; renderResults(); });
  el("Retry").addEventListener("click", load);
  el("MovieSelect").addEventListener("change", () => { if (el("MovieSelect").value) win.location.assign(`/booking/buy-tickets?movieId=${encodeURIComponent(el("MovieSelect").value)}`); });
  const poster = el("Poster");
  if (poster) {
    poster.addEventListener("error", () => { poster.hidden = true; });
    if (poster.complete && poster.naturalWidth === 0) poster.hidden = true;
  }
  doc.addEventListener("box:cinema-selected", event => {
    if (query.get("preview") === "true" || event.detail?.initial) return;
    clearTimeout(searchTimer);
    preferredId = String(event.detail?.cinemaId || ""); city = ""; search = ""; el("Search").value = ""; limit = 3;
    el("Clear").hidden = true; onReset(); root.hidden = false;
    if (loaded) { renderCities(); renderResults(); } else load();
  });
  return {
    show() { root.hidden = false; if (!loaded) load(); else renderResults(); },
    hide() { clearTimeout(searchTimer); root.hidden = true; slotsController?.abort(); ++revision; },
  };
}
