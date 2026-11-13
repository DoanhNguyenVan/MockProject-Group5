import { JSDOM } from "jsdom";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { readFileSync } from "node:fs";
import { calculateTotals, changeQuantity, formatVnd, initBooking, safeImageUrl, toggleSeat, validateBooking } from "../../src/main/resources/static/js/modules/booking.js";
import { bookingPreview } from "../../src/main/resources/static/js/modules/booking-preview.js";
import { initBookingLinks } from "../../src/main/resources/static/js/modules/booking-links.js";

const fixture = () => structuredClone(bookingPreview);
const template = readFileSync(new URL("../../src/main/resources/templates/customer/buy-tickets.html", import.meta.url), "utf8");
const flush = () => new Promise(resolve => setTimeout(resolve, 0));
const response = data => ({ ok: true, status: 200, json: async () => data });

describe("booking selection and estimates", () => {
  it("selects and removes available seats without mutating state", () => {
    const selected = [];
    expect(toggleSeat(selected, fixture().seats[0]).selected).toEqual(["A1"]);
    expect(selected).toEqual([]);
    expect(toggleSeat(["A1"], fixture().seats[0]).selected).toEqual([]);
  });
  it("rejects booked and held seats and limits selection to eight", () => {
    const seat = fixture().seats[0];
    expect(toggleSeat([], { ...seat, status: "HELD" }).error).toBeTruthy();
    expect(toggleSeat([], { ...seat, status: "BOOKED" }).selected).toEqual([]);
    expect(toggleSeat(Array.from({ length: 8 }, (_, i) => `B${i}`), seat).error).toContain("8");
  });
  it("calculates mixed seat prices and extras, ignoring unknown or unavailable seats", () => {
    const data = fixture();
    expect(calculateTotals(data.seats, ["A1", "A1", "D1", "A4", "unknown"], data.products, { solo: 2, drink: 1 })).toEqual({ tickets: 210000, extras: 213000, total: 423000 });
    expect(calculateTotals(data.seats, [], data.products, {})).toEqual({ tickets: 0, extras: 0, total: 0 });
  });
  it("bounds combo quantities and formats VND without decimals", () => {
    expect(changeQuantity(undefined, 1)).toBe(1);
    expect(changeQuantity(0, -1)).toBe(0);
    expect(changeQuantity(10, 1)).toBe(10);
    expect(changeQuantity(2.5, 1)).toBe(3);
    expect(formatVnd(120000).replace(/\s/g, "")).toBe("120.000₫");
  });
  it("accepts documented DTOs and rejects unsafe prices, duplicate seats and unknown states", () => {
    expect(validateBooking(fixture()).seats).toHaveLength(80);
    for (const change of [s => { s.price = -1; }, s => { s.price = "90000"; }, s => { s.status = "OTHER"; }]) {
      const data = fixture(); change(data.seats[0]); expect(() => validateBooking(data)).toThrow();
    }
    const duplicate = fixture(); duplicate.seats.push(duplicate.seats[0]); expect(() => validateBooking(duplicate)).toThrow();
    expect(() => validateBooking(null)).toThrow();
  });
  it("restricts image protocols", () => {
    expect(safeImageUrl("javascript:alert(1)")).toBe("/assets/img/crimson-horizon.webp");
    expect(safeImageUrl("//evil.test/image")).toBe("/assets/img/crimson-horizon.webp");
    expect(safeImageUrl("/assets/test.jpg")).toBe("/assets/test.jpg");
    expect(safeImageUrl("https://example.test/poster.jpg")).toContain("https:");
  });
});

describe("booking screen interactions", () => {
  // Use an isolated document so cinema listeners do not leak between tests.
  let doc, dom;
  beforeEach(() => {
    dom = new JSDOM(template, { url: "http://localhost/booking/buy-tickets?showtimeId=42" });
    doc = dom.window.document;
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(response(fixture())));
  });
  afterEach(() => { dom.window.close(); vi.unstubAllGlobals(); });
  it("supports seats → snacks → review and preserves edits when going back", async () => {
    initBooking(doc); await flush();
    const next = doc.getElementById("bookingNext");
    expect(next.disabled).toBe(true);
    const seat = doc.querySelector('.booking-seat[aria-label^="A1,"]'); seat.click();
    expect(seat.getAttribute("aria-pressed")).toBe("true");
    expect(doc.getElementById("bookingTotal").textContent).toBe(formatVnd(90000));
    next.click(); doc.querySelector('[aria-label="Add one Solo combo"]').click();
    expect(doc.getElementById("bookingTotal").textContent).toBe(formatVnd(179000));
    next.click(); expect(next.disabled).toBe(false);
    expect(doc.getElementById("bookingReview").textContent).toContain("A1");
    next.click();
    expect(doc.getElementById("bookingPayment").hidden).toBe(false);
    expect(doc.activeElement.id).toBe("paymentTitle");
    expect(doc.querySelectorAll('.booking-steps .is-complete')).toHaveLength(3);
    expect(doc.getElementById("paymentCash").hidden).toBe(false);
    expect(doc.querySelector('[data-payment-total]').textContent).toBe(formatVnd(179000));
    doc.querySelector('input[value="VNPAY"]').click();
    expect(doc.getElementById("paymentCash").hidden).toBe(true);
    expect(doc.getElementById("paymentVnpay").hidden).toBe(false);
    expect(doc.getElementById("bookingPaymentAction").disabled).toBe(true);
    expect(doc.getElementById("bookingPaymentAction").textContent).toBe("Generate VNPay QR");
    doc.getElementById("bookingPrevious").click(); next.click();
    expect(doc.querySelector('input[value="VNPAY"]').checked).toBe(true);
    doc.getElementById("bookingPrevious").click();
    doc.getElementById("bookingPrevious").click(); doc.getElementById("bookingPrevious").click(); seat.click();
    expect(next.disabled).toBe(true);
    expect(fetch.mock.calls.every(([, opts]) => opts.method === "GET")).toBe(true);
  });
  it("renders service errors as text, clears seats and offers retry", async () => {
    fetch.mockResolvedValue({ ok: false, status: 503, json: async () => ({ message: '<img src=x onerror="alert(1)">' }) });
    initBooking(doc); await flush();
    expect(doc.getElementById("bookingContent").hidden).toBe(true);
    expect(doc.getElementById("bookingStatus").querySelector("img")).toBeNull();
    expect(doc.getElementById("bookingStatus").textContent).toContain("<img");
    fetch.mockResolvedValue(response(fixture())); doc.getElementById("bookingRetry").click(); await flush();
    expect(doc.getElementById("bookingContent").hidden).toBe(false);
  });
  it("shows a sold-out state rather than selectable fake seats", async () => {
    const data = fixture(); data.seats.forEach(s => { s.status = "BOOKED"; }); fetch.mockResolvedValue(response(data));
    initBooking(doc); await flush(); expect(doc.getElementById("bookingEmptyTitle").textContent).toBe("No seats available");
    expect(doc.getElementById("bookingContent").hidden).toBe(true);
  });
  it("does not replace an explicit screening with the navbar initial cinema event", async () => {
    initBooking(doc); await flush();
    doc.dispatchEvent(new dom.window.CustomEvent("box:cinema-selected", { detail: { cinemaId: 3, initial: true } }));
    expect(fetch).toHaveBeenCalledTimes(1);
  });
  it("discards outdated screening responses after a cinema switch", async () => {
    let finishOld;
    fetch.mockImplementationOnce(() => new Promise(resolve => { finishOld = resolve; }));
    initBooking(doc);
    fetch.mockResolvedValue(response([]));
    doc.dispatchEvent(new dom.window.CustomEvent("box:cinema-selected", { detail: { cinemaId: 3 } }));
    await flush(); finishOld(response(fixture())); await flush();
    expect(doc.getElementById("bookingContent").hidden).toBe(true);
    expect(doc.getElementById("bookingPicker").hidden).toBe(false);
    expect(doc.getElementById("screeningMessage").textContent).toBe("No movies are available right now.");
  });
  it("renders API movie names as text and updates hero links for the current movie", async () => {
    const data = fixture(); data.movieTitle = "<img src=x>"; fetch.mockResolvedValue(response(data));
    initBooking(doc); await flush();
    expect(doc.getElementById("bookingTitle").textContent).toBe("<img src=x>");
    expect(doc.getElementById("bookingTitle").children.length).toBe(0);
    const link = doc.createElement("a"); link.className = "btn-cta-buy"; link.dataset.bookId = "17"; doc.body.append(link);
    initBookingLinks(doc); link.addEventListener("click", e => e.preventDefault()); link.click();
    expect(link.getAttribute("href")).toBe("/booking/buy-tickets?movieId=17");
  });
});
