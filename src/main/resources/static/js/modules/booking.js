import { initPayment } from "./booking-payment.js?v=1.0";
import { loadBookingData } from "./booking-data.js?v=1.0";
import { initScreenings } from "./booking-screenings.js?v=1.0";

export function formatVnd(value) {
  return new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND" }).format(value);
}

export function toggleSeat(selected, seat, limit = 8) {
  if (seat.status !== "AVAILABLE") return { selected, error: "This seat is no longer available." };
  if (selected.includes(seat.id)) return { selected: selected.filter(id => id !== seat.id), error: "" };
  if (selected.length >= limit) return { selected, error: `You can select up to ${limit} seats per booking.` };
  return { selected: [...selected, seat.id], error: "" };
}

export function changeQuantity(current, delta) {
  return Math.max(0, Math.min(10, Math.trunc((Number(current) || 0) + delta)));
}

export function calculateTotals(seats, selected, products, quantities) {
  const tickets = seats.filter(s => s.status === "AVAILABLE" && selected.includes(s.id)).reduce((sum, s) => sum + s.price, 0);
  const extras = products.reduce((sum, p) => sum + p.price * changeQuantity(quantities[p.id], 0), 0);
  return { tickets, extras, total: tickets + extras };
}

export function validateBooking(data) {
  const priceValid = value => Number.isSafeInteger(value) && value >= 0;
  const idValid = value => typeof value === "string" && value.length > 0;
  if (!data || !idValid(data.movieTitle) || !idValid(data.cinemaName) || !idValid(data.screeningLabel)
      || !idValid(data.roomName) || !Array.isArray(data.seats) || !Array.isArray(data.products)
      || !data.seats.every(s => s && idValid(s.id) && idValid(s.row) && Number.isInteger(s.number)
        && s.number > 0 && priceValid(s.price) && ["STANDARD", "VIP"].includes(s.type)
        && ["AVAILABLE", "HELD", "BOOKED", "UNAVAILABLE"].includes(s.status))
      || !data.products.every(p => p && idValid(p.id) && idValid(p.name) && priceValid(p.price))
      || new Set(data.seats.map(s => s.id)).size !== data.seats.length
      || new Set(data.products.map(p => p.id)).size !== data.products.length) {
    throw new Error("Booking information is incomplete. Please try another screening.");
  }
  return data;
}

export function safeImageUrl(value, fallback = "/assets/img/crimson-horizon.webp") {
  if (typeof value !== "string") return fallback;
  // Reject script/data URLs and protocol-relative paths.
  if (/^\/(?!\/)[\w/.-]+$/.test(value) || /^https:\/\//i.test(value)) return value;
  return fallback;
}

export function initBooking(doc = document) {
  const app = doc.getElementById("bookingApp");
  if (!app) return;
  const win = doc.defaultView;
  const el = id => doc.getElementById(`booking${id}`);
  const payment = initPayment(doc);
  const query = new URLSearchParams(win.location.search);
  const preview = query.get("preview") === "true";
  let data = null, selected = [], quantities = Object.create(null), step = 0;
  let requestController, requestVersion = 0, retry = () => startPicker();
  const screenings = initScreenings(doc, {
    onSelect: id => loadBooking(id),
    onReset: () => {
      beginRequest(); clearSelection(); el("Content").hidden = true; el("Empty").hidden = true;
      const url = new URL(win.location.href); url.searchParams.delete("showtimeId"); win.history.replaceState({}, "", url);
    }
  });

  function node(tag, text, className) {
    const item = doc.createElement(tag);
    if (text !== undefined) item.textContent = text;
    if (className) item.className = className;
    return item;
  }
  function status(message = "") { el("Status").textContent = message; el("Status").hidden = !message; }
  function empty(title, message, canRetry = false) {
    el("Content").hidden = true; el("Empty").hidden = false;
    el("EmptyTitle").textContent = title; el("EmptyText").textContent = message; el("Retry").hidden = !canRetry;
  }
  function clearSelection() { data = null; selected = []; quantities = Object.create(null); step = 0; payment.reset(); status(); }
  function beginRequest() {
    requestController?.abort(); requestController = new AbortController();
    return { signal: requestController.signal, version: ++requestVersion };
  }
  function handleError(error, version) {
    if (error.name === "AbortError" || version !== requestVersion) return;
    empty("Booking is currently unavailable", error.message, true); status(error.message);
  }
  function updateSummary() {
    const seats = data.seats.filter(s => selected.includes(s.id));
    const totals = calculateTotals(data.seats, selected, data.products, quantities);
    el("SeatCount").textContent = `${seats.length} ${seats.length === 1 ? "ticket" : "tickets"}`;
    el("SelectedSeats").textContent = seats.map(s => s.id).join(", ") || "No seats selected";
    el("TicketTotal").textContent = formatVnd(totals.tickets);
    el("ProductTotal").textContent = formatVnd(totals.extras);
    el("Total").textContent = formatVnd(totals.total);
    el("Extras").textContent = data.products.filter(p => quantities[p.id]).map(p => `${quantities[p.id]} × ${p.name}`).join(" · ");
    el("Next").disabled = !selected.length || step === 3;
    el("Next").hidden = step === 3;
    app.querySelectorAll("[data-payment-total]").forEach(amount => { amount.textContent = formatVnd(totals.total); });
    el("Next").textContent = ["Continue to snacks →", "Review selection →", "Continue to payment →", "Payment"][step];
    el("CheckoutNote").textContent = selected.length ? "No seats reserved. No payment taken." : "Select your seats to get started.";
    el("StepHint").textContent = [selected.length ? "Happy with your view? Add a little extra." : "Choose at least one seat to continue.", "Snacks are optional. You can continue without them.", "You can go back to edit your selection.", "Go back to review or edit your booking."][step];
  }
  function renderSeats() {
    el("Seats").replaceChildren();
    const rows = new Map();
    data.seats.forEach(seat => {
      if (!rows.has(seat.row)) rows.set(seat.row, []);
      rows.get(seat.row).push(seat);
    });
    rows.forEach((seats, label) => {
      const row = node("div", undefined, "booking-seat-row");
      row.append(node("span", label, "booking-row-label"));
      seats.forEach(seat => {
        const button = node("button", seat.status === "AVAILABLE" ? String(seat.number) : "×", `booking-seat${seat.type === "VIP" ? " vip" : ""}`);
        button.type = "button"; button.disabled = seat.status !== "AVAILABLE";
        button.dataset.aisle = String(seat.aisleAfter === true);
        button.setAttribute("aria-pressed", "false");
        button.setAttribute("aria-label", `${seat.id}, ${seat.type}, ${formatVnd(seat.price)}, ${seat.status.toLowerCase()}`);
        button.addEventListener("click", () => {
          const result = toggleSeat(selected, seat); selected = result.selected; status(result.error);
          button.setAttribute("aria-pressed", String(selected.includes(seat.id))); updateSummary();
        });
        row.append(button);
      });
      row.append(node("span", label, "booking-row-label")); el("Seats").append(row);
    });
  }
  function renderProducts() {
    el("Products").replaceChildren();
    if (!data.products.length) el("Products").append(node("p", "No snacks are available for this screening. Continue to review your seats.", "booking-muted"));
    data.products.forEach(product => {
      const card = node("article", undefined, "booking-product");
      const image = node("img"); image.src = safeImageUrl(product.imageUrl, "/assets/img/fnb/box-combos.jpg"); image.alt = "";
      const info = node("div"); info.append(node("h3", product.name), node("p", product.description || ""), node("strong", formatVnd(product.price)));
      const control = node("div", undefined, "booking-quantity");
      const minus = node("button", "−"), plus = node("button", "+"), output = node("output", "0");
      minus.type = plus.type = "button"; minus.disabled = true;
      minus.setAttribute("aria-label", `Remove one ${product.name}`); plus.setAttribute("aria-label", `Add one ${product.name}`);
      output.setAttribute("aria-label", `${product.name} quantity`); output.setAttribute("aria-live", "polite");
      for (const [button, delta] of [[minus, -1], [plus, 1]]) button.addEventListener("click", () => {
        quantities[product.id] = changeQuantity(quantities[product.id], delta);
        output.textContent = quantities[product.id]; minus.disabled = quantities[product.id] === 0; plus.disabled = quantities[product.id] === 10;
        updateSummary();
      });
      control.append(minus, output, plus); card.append(image, info, control); el("Products").append(card);
    });
  }
  function showStep(next, focus = true) {
    step = next;
    app.querySelectorAll("[data-booking-step]").forEach(section => { section.hidden = Number(section.dataset.bookingStep) !== step; });
    app.querySelectorAll("[data-step-marker]").forEach(marker => {
      marker.classList.toggle("is-complete", Number(marker.dataset.stepMarker) < step);
      if (Number(marker.dataset.stepMarker) === step) marker.setAttribute("aria-current", "step"); else marker.removeAttribute("aria-current");
    });
    el("Previous").hidden = step === 0;
    if (step === 2) {
      el("Review").replaceChildren();
      for (const [label, value] of [["Movie", data.movieTitle], ["Cinema", data.cinemaName], ["Screening", data.screeningLabel], ["Room", data.roomName], ["Seats", selected.join(", ")], ["Extras", el("Extras").textContent || "No snacks added"], ["Estimate", formatVnd(calculateTotals(data.seats, selected, data.products, quantities).total)]]) {
        const row = node("div"); row.append(node("dt", label), node("dd", value)); el("Review").append(row);
      }
    }
    updateSummary();
    if (focus) doc.getElementById(["seatTitle", "comboTitle", "reviewTitle", "paymentTitle"][step]).focus();
  }
  function showBooking(value) {
    data = validateBooking(value);
    el("PreviewNotice").hidden = data.preview !== true;
    el("PreviewNotice").querySelector("span").textContent = preview
      ? "Sample screening, seats and prices. No seats are reserved and no payment is taken."
      : "Your selected screening with sample seats and snacks. Seat availability and extras prices are for demonstration only. No seats are reserved.";
    if (!data.seats.some(s => s.status === "AVAILABLE")) { empty("No seats available", "This screening has no available seats. Please choose another showtime."); return; }
    for (const [id, value] of [["Title", data.movieTitle], ["CinemaName", data.cinemaName], ["Screening", data.screeningLabel], ["Room", data.roomName], ["Format", data.format || ""]]) el(id).textContent = value;
    const posterUrl = safeImageUrl(data.posterUrl, "");
    el("Poster").hidden = !posterUrl;
    if (posterUrl) el("Poster").src = posterUrl;
    const rating = ["P", "T13", "T16", "T18"].includes(data.ageRating) ? data.ageRating : "";
    el("Rating").textContent = rating; el("Rating").className = `badge-age-${rating.toLowerCase()}`;
    el("Empty").hidden = true; el("Content").hidden = false; status();
    screenings?.hide();
    el("ChangeScreening").hidden = preview;
    renderSeats(); renderProducts(); showStep(0, false);
    el("Content").scrollIntoView?.({ block: "start" });
  }
  async function loadBooking(id) {
    clearSelection(); const { signal, version } = beginRequest();
    empty("Loading your screening…", "Checking seat information."); retry = () => loadBooking(id);
    el("Empty").scrollIntoView?.({ block: "start", behavior: "smooth" });
    try {
      const value = await loadBookingData(id, {
        signal, onUnauthenticated: () => win.location.assign("/login")
      });
      if (version === requestVersion) showBooking(value);
    } catch (error) { handleError(error, version); }
  }
  function startPicker() {
    if (data?.movieId && String(data.movieId) !== query.get("movieId")) {
      win.location.assign(`/booking/buy-tickets?movieId=${encodeURIComponent(data.movieId)}`);
      return;
    }
    el("PreviewNotice").hidden = true;
    beginRequest(); clearSelection(); el("Content").hidden = true; el("Empty").hidden = true;
    const url = new URL(win.location.href); url.searchParams.delete("showtimeId"); win.history.replaceState({}, "", url);
    screenings?.show();
  }
  el("ChangeScreening").addEventListener("click", startPicker);
  el("Poster").addEventListener("error", () => { el("Poster").hidden = true; });
  el("Retry").addEventListener("click", () => retry());
  el("Next").addEventListener("click", () => { if (selected.length && step < 3) showStep(step + 1); });
  el("Previous").addEventListener("click", () => { if (step > 0) showStep(step - 1); });
  if (preview) {
    el("PreviewNotice").hidden = false;
    import("./booking-preview.js?v=1.1").then(({ bookingPreview }) => showBooking(bookingPreview)).catch(error => { empty("Preview unavailable", error.message); });
  } else if (query.get("showtimeId")) loadBooking(query.get("showtimeId"));
  else startPicker();
}

if (typeof document !== "undefined") {
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", () => initBooking(document));
  else initBooking(document);
}
