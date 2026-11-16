import { getSeatSvg } from "./seat-icons.js?v=3.2";
import { initPayment } from "./booking-payment.js?v=1.0";
import { loadBookingData, holdBookingSeats, releaseBookingSeats } from "./booking-data.js?v=1.4";
import { initScreenings } from "./booking-screenings.js?v=1.0";

export const DEFAULT_HOLD_SECONDS = 600; // 10 minutes per SRS FR-04

export function formatVnd(value) {
  return new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND" }).format(value);
}

export function formatCountdown(seconds) {
  const safe = Math.max(0, Math.floor(Number(seconds) || 0));
  const mins = String(Math.floor(safe / 60)).padStart(2, "0");
  const secs = String(safe % 60).padStart(2, "0");
  return `${mins}:${secs}`;
}

export function toggleSeat(selected, seat, limit = 8, allSeats = []) {
  if (seat.status === "HELD") {
    return { selected, error: `Ghế ${seat.seatLabel || seat.id} đang được khách hàng khác giữ tạm thời.` };
  }
  if (seat.status !== "AVAILABLE") {
    return { selected, error: "Ghế này hiện không khả dụng." };
  }

  if (seat.pairId && Array.isArray(allSeats) && allSeats.length) {
    const partner = allSeats.find(s => s !== seat && s.pairId === seat.pairId);
    if (selected.includes(seat.id)) {
      const partnerId = partner ? partner.id : null;
      return { selected: selected.filter(id => id !== seat.id && id !== partnerId), error: "" };
    }
    if (partner) {
      if (partner.status !== "AVAILABLE") {
        return { selected, error: "Ghế đôi đi cùng hiện không khả dụng." };
      }
      if (selected.length + 2 > limit) {
        return { selected, error: `Bạn chỉ có thể chọn tối đa ${limit} ghế mỗi lượt đặt (Ghế đôi chiếm 2 chỗ).` };
      }
      return { selected: [...selected, seat.id, partner.id], error: "" };
    }
  }

  if (selected.includes(seat.id)) return { selected: selected.filter(id => id !== seat.id), error: "" };
  if (selected.length >= limit) return { selected, error: `Bạn chỉ có thể chọn tối đa ${limit} ghế mỗi lượt đặt.` };
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
      && s.number > 0 && priceValid(s.price) && typeof s.type === "string" && s.type.length > 0
      && ["AVAILABLE", "HELD", "BOOKED", "RESERVED", "UNAVAILABLE", "INACTIVE"].includes(s.status))
    || !data.products.every(p => p && idValid(p.id) && idValid(p.name) && priceValid(p.price))
    || new Set(data.seats.map(s => s.id)).size !== data.seats.length
    || new Set(data.products.map(p => p.id)).size !== data.products.length) {
    throw new Error("Booking information is incomplete. Please try another screening.");
  }
  return data;
}

export function safeImageUrl(value, fallback = "/assets/img/crimson-horizon.webp") {
  if (typeof value !== "string") return fallback;
  if (/^\/(?!\/)[\w/.-]+$/.test(value) || /^https:\/\//i.test(value)) return value;
  return fallback;
}

const VIETNAMESE_SEAT_TYPE_NAMES = {
  standard: "Ghế thường",
  vip: "Ghế VIP",
  sweetbox: "Ghế đôi Sweetbox",
  imax: "Ghế IMAX",
  "4dx": "Ghế 4DX",
  recliner: "Ghế Recliner",
  wheelchair: "Ghế xe lăn"
};

function resolveSeatShape(seat) {
  const rawShape = String(seat?.shapeType || seat?.type || "standard").toLowerCase().replace(/[^a-z0-9]+/g, "");
  if (["standard", "vip", "sweetbox", "imax", "4dx", "recliner", "wheelchair"].includes(rawShape)) {
    return rawShape;
  }
  const upper = String(seat?.type || "").toUpperCase();
  if (upper.includes("SWEETBOX") || upper.includes("COUPLE") || upper.includes("ĐÔI")) return "sweetbox";
  if (upper.includes("VIP")) return "vip";
  if (upper.includes("IMAX")) return "imax";
  if (upper.includes("4DX") || upper.includes("4D")) return "4dx";
  if (upper.includes("RECLINER") || upper.includes("GOLD")) return "recliner";
  if (upper.includes("WHEELCHAIR")) return "wheelchair";
  return "standard";
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
  let activeShapeFilter = "ALL";
  let zoomPercent = 100;
  let holdTimerId = null;
  let otherHoldsTimerId = null;
  let seatEventSource = null;
  let broadcastChannel = null;
  let isSyncingLiveMap = false;
  let tickCounter = 0;
  let holdTotalSeconds = DEFAULT_HOLD_SECONDS;
  let holdRemainingSeconds = 0;
  let holdDeadlineMs = 0;
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

  function status(message = "") {
    const st = el("Status");
    if (!st) return;
    st.textContent = message;
    st.hidden = !message;
  }

  function isUserAuthenticated() {
    const userIdAttr = app.getAttribute("data-user-id");
    if (userIdAttr === "GUEST") return false;
    if (doc.querySelector('a[data-nav-route="login"]')) return false;
    const nav = doc.querySelector(".cinema-nav");
    if (nav && !nav.querySelector(".nav-user-pill")) return false;
    if (userIdAttr && userIdAttr !== "GUEST") return true;
    return true;
  }

  function requireAuthenticationForBooking() {
    if (!isUserAuthenticated()) {
      win.location.assign("/login");
      return false;
    }
    return true;
  }

  function hideExpiryNotice() {
    const exp = el("ExpiryNotice");
    if (exp) exp.hidden = true;
  }

  function showExpiryNotice(expiredSeatLabels = []) {
    const exp = el("ExpiryNotice");
    if (!exp) return;
    const msg = el("ExpiryMessage");
    if (msg) {
      const seatStr = expiredSeatLabels.length ? ` (${expiredSeatLabels.join(", ")})` : "";
      msg.textContent = `Thời gian giữ ghế tạm thời cho các ghế${seatStr} đã hết hạn. Hệ thống đã tự động mở lại ghế để phục vụ khách hàng khác. Vui lòng chọn lại vị trí ghế trên sơ đồ.`;
    }
    exp.hidden = false;
    exp.scrollIntoView?.({ block: "nearest", behavior: "smooth" });
  }

  function empty(title, message, canRetry = false) {
    el("Content").hidden = true; el("Empty").hidden = false;
    el("EmptyTitle").textContent = title; el("EmptyText").textContent = message; el("Retry").hidden = !canRetry;
  }

  function stopHoldTimer() {
    if (holdTimerId) {
      clearInterval(holdTimerId);
      holdTimerId = null;
    }
    holdRemainingSeconds = 0;
    holdDeadlineMs = 0;
    const banner = el("HoldBanner");
    const summaryBox = el("SummaryHoldBox");
    if (banner) banner.hidden = true;
    if (summaryBox) summaryBox.hidden = true;
  }

  function stopOtherHoldsWatcher() {
    if (otherHoldsTimerId) {
      clearInterval(otherHoldsTimerId);
      otherHoldsTimerId = null;
    }
    if (seatEventSource) {
      try { seatEventSource.close(); } catch (_) { }
      seatEventSource = null;
    }
    if (broadcastChannel) {
      try { broadcastChannel.close(); } catch (_) { }
      broadcastChannel = null;
    }
  }

  function seatStatusSignature(seatsList) {
    if (!Array.isArray(seatsList)) return "";
    return seatsList.map(s => `${s.id}:${s.status}`).join("|");
  }

  async function refreshLiveSeatMapSilently() {
    if (!data || data.preview === true || !data.showtimeId || isSyncingLiveMap) return;
    isSyncingLiveMap = true;
    try {
      const fresh = await loadBookingData(data.showtimeId);
      if (!fresh || !Array.isArray(fresh.seats) || !data) return;

      const oldSig = seatStatusSignature(data.seats);
      const newSig = seatStatusSignature(fresh.seats);

      // Update remaining hold seconds on HELD seats even if status signature is unchanged
      const freshById = new Map(fresh.seats.map(s => [s.id, s]));
      data.seats.forEach(s => {
        const updated = freshById.get(s.id);
        if (updated) {
          s.holdRemainingSeconds = updated.holdRemainingSeconds;
          s.holdExpiresAt = updated.holdExpiresAt;
        }
      });

      if (oldSig !== newSig) {
        const scrollEl = el("SeatScroll");
        const prevScrollLeft = scrollEl ? scrollEl.scrollLeft : 0;

        data = validateBooking(fresh);
        const stillSelectable = new Set(data.seats.filter(s => s.status === "AVAILABLE").map(s => s.id));
        const filteredSelected = selected.filter(id => stillSelectable.has(id));
        if (filteredSelected.length !== selected.length) {
          selected = filteredSelected;
          if (!selected.length) stopHoldTimer();
        }

        renderSeats();
        syncSeatStates();
        updateSummary();

        if (scrollEl) {
          scrollEl.scrollLeft = prevScrollLeft;
        }
      }
    } catch (_) {
    } finally {
      isSyncingLiveMap = false;
    }
  }

  function notifyCrossTabSeatSync() {
    if (!data || !data.showtimeId) return;
    try {
      if (broadcastChannel) {
        broadcastChannel.postMessage({ showtimeId: String(data.showtimeId), ts: Date.now() });
      }
    } catch (_) { }
  }

  function startOtherHoldsWatcher() {
    stopOtherHoldsWatcher();
    if (!data || data.preview === true || !data.showtimeId) return;

    // 1. Real-time Server-Sent Events (SSE) push stream
    if (typeof win.EventSource === "function") {
      try {
        seatEventSource = new win.EventSource(`/api/booking/showtimes/${encodeURIComponent(data.showtimeId)}/stream`);
        seatEventSource.addEventListener("seat-update", () => {
          refreshLiveSeatMapSilently();
        });
      } catch (_) { }
    }

    // 2. Instant cross-tab BroadcastChannel push
    if (typeof win.BroadcastChannel === "function") {
      try {
        broadcastChannel = new win.BroadcastChannel("box_cinema_seat_realtime");
        broadcastChannel.onmessage = (ev) => {
          if (ev?.data?.showtimeId === String(data?.showtimeId)) {
            refreshLiveSeatMapSilently();
          }
        };
      } catch (_) { }
    }

    // 3. 1-second local countdown + 2-second delta sync
    tickCounter = 0;
    otherHoldsTimerId = setInterval(() => {
      if (!data || !Array.isArray(data.seats)) return;
      tickCounter++;
      let anyJustExpired = false;
      data.seats.forEach(s => {
        if (s.status === "HELD" && Number(s.holdRemainingSeconds) > 0) {
          s.holdRemainingSeconds = Math.max(0, Number(s.holdRemainingSeconds) - 1);
          if (s.holdRemainingSeconds === 0) {
            anyJustExpired = true;
          }
        }
      });
      if (anyJustExpired || tickCounter % 2 === 0) {
        refreshLiveSeatMapSilently();
      }
    }, 1000);
  }

  function renderHoldTimerUi() {
    const banner = el("HoldBanner");
    const summaryBox = el("SummaryHoldBox");
    const mobileTimer = el("MobileTimer");
    if (!selected.length || holdRemainingSeconds <= 0) {
      if (banner) banner.hidden = true;
      if (summaryBox) summaryBox.hidden = true;
      if (mobileTimer) mobileTimer.hidden = true;
      return;
    }

    const formatted = formatCountdown(holdRemainingSeconds);
    const isDanger = holdRemainingSeconds <= 30;
    const isWarn = !isDanger && holdRemainingSeconds <= 120;

    if (banner) {
      banner.hidden = false;
      banner.classList.toggle("is-warning", isWarn);
      banner.classList.toggle("is-danger", isDanger);

      const countdownEl = el("HoldCountdown");
      if (countdownEl) countdownEl.textContent = formatted;

      const seatsTextEl = el("HoldBannerSeats");
      if (seatsTextEl) seatsTextEl.textContent = `Ghế: ${selected.join(", ")}`;

      const badgeEl = el("HoldStateBadge");
      if (badgeEl) {
        badgeEl.textContent = isDanger
          ? "SẮP HẾT HẠN GIỮ GHẾ!"
          : (isWarn ? "CÒN ÍT THỜI GIAN" : "ĐANG GIỮ GHẾ TẠM THỜI");
      }

      const progressEl = el("HoldProgressBar");
      if (progressEl) {
        const pct = Math.max(0, Math.min(100, (holdRemainingSeconds / Math.max(1, holdTotalSeconds)) * 100));
        progressEl.style.width = `${pct.toFixed(1)}%`;
      }
    }

    if (summaryBox) {
      summaryBox.hidden = false;
      summaryBox.classList.toggle("is-warning", isWarn);
      summaryBox.classList.toggle("is-danger", isDanger);
      const sumCountdown = el("SummaryCountdown");
      if (sumCountdown) sumCountdown.textContent = formatted;
    }

    if (mobileTimer) {
      mobileTimer.hidden = false;
      mobileTimer.textContent = `⏱ ${formatted}`;
      mobileTimer.classList.toggle("is-warning", isWarn);
      mobileTimer.classList.toggle("is-danger", isDanger);
    }
  }

  async function handleHoldExpired() {
    const expiredSeats = [...selected];
    stopHoldTimer();
    selected = [];
    if (data && data.preview !== true && data.showtimeId) {
      releaseBookingSeats(data.showtimeId).catch(() => { });
    }
    syncSeatStates();
    updateSummary();
    if (step !== 0) {
      showStep(0, false);
    }
    showExpiryNotice(expiredSeats);
    if (data && data.preview !== true && data.showtimeId) {
      try {
        const fresh = await loadBookingData(data.showtimeId);
        if (fresh && Array.isArray(fresh.seats)) {
          data = validateBooking(fresh);
          renderLegend();
          renderTypeFilters();
          renderSeats();
          updateSummary();
        }
      } catch (_) { }
    }
  }

  function startOrUpdateHoldTimer(seconds = DEFAULT_HOLD_SECONDS, totalBasisSeconds = seconds) {
    const safeSec = Math.max(1, Math.floor(Number(seconds) || DEFAULT_HOLD_SECONDS));
    holdTotalSeconds = Math.max(safeSec, Math.floor(Number(totalBasisSeconds) || DEFAULT_HOLD_SECONDS));
    holdRemainingSeconds = safeSec;
    holdDeadlineMs = Date.now() + safeSec * 1000;
    hideExpiryNotice();
    renderHoldTimerUi();

    if (holdTimerId) clearInterval(holdTimerId);
    holdTimerId = setInterval(() => {
      if (!selected.length) {
        stopHoldTimer();
        return;
      }
      const left = Math.max(0, Math.ceil((holdDeadlineMs - Date.now()) / 1000));
      holdRemainingSeconds = left;
      renderHoldTimerUi();
      if (left <= 0) {
        handleHoldExpired();
      }
    }, 1000);
  }

  async function syncServerSeatHold(nextSelected, { resetTimer = false, durationSeconds } = {}) {
    if (!data) return;

    if (!nextSelected.length) {
      stopHoldTimer();
      if (data.preview !== true && data.showtimeId) {
        releaseBookingSeats(data.showtimeId).then(() => notifyCrossTabSeatSync()).catch(() => { });
      }
      return;
    }

    // Start local timer immediately for snappy feedback
    if (!holdTimerId || resetTimer || durationSeconds) {
      const targetDur = durationSeconds || data.holdDurationSeconds || DEFAULT_HOLD_SECONDS;
      startOrUpdateHoldTimer(targetDur, targetDur);
    } else {
      renderHoldTimerUi();
    }

    // Do not send POST requests in preview/sample mode (preserves preview & unit test purity)
    if (data.preview === true || !data.showtimeId || data.showtimeId === "preview") {
      return;
    }

    try {
      const res = await holdBookingSeats(data.showtimeId, nextSelected, {
        durationSeconds,
        resetTimer
      });
      notifyCrossTabSeatSync();
      if (res && Number.isFinite(res.holdRemainingSeconds) && res.holdRemainingSeconds > 0) {
        startOrUpdateHoldTimer(
          res.holdRemainingSeconds,
          res.holdDurationSeconds || durationSeconds || data.holdDurationSeconds || DEFAULT_HOLD_SECONDS
        );
      }
    } catch (err) {
      if (err && err.status === 401) {
        stopHoldTimer();
        selected = [];
        win.location.assign("/login");
        return;
      }
      if (err && err.status === 409) {
        const conflicts = Array.isArray(err.conflictingSeats) ? err.conflictingSeats : [];
        if (conflicts.length > 0) {
          selected = selected.filter(id => !conflicts.includes(id));
        } else {
          selected = [];
        }
        if (!selected.length) stopHoldTimer();
        status(err.message || "Ghế bạn chọn vừa được khách hàng khác giữ hoặc đặt.");
        // Reload live seat statuses so the conflicting seat turns HELD/BOOKED on the map
        try {
          const fresh = await loadBookingData(data.showtimeId);
          if (fresh && Array.isArray(fresh.seats)) {
            data = validateBooking(fresh);
            renderSeats();
          }
        } catch (_) { }
        syncSeatStates();
        updateSummary();
      }
    }
  }

  function clearSelection(releaseServer = false) {
    if (releaseServer && data && data.preview !== true && data.showtimeId && selected.length) {
      releaseBookingSeats(data.showtimeId).catch(() => { });
    }
    stopHoldTimer();
    stopOtherHoldsWatcher();
    data = null;
    selected = [];
    quantities = Object.create(null);
    step = 0;
    activeShapeFilter = "ALL";
    payment.reset();
    status();
    hideExpiryNotice();
  }

  function beginRequest() {
    requestController?.abort(); requestController = new AbortController();
    return { signal: requestController.signal, version: ++requestVersion };
  }

  function handleError(error, version) {
    if (error.name === "AbortError" || version !== requestVersion) return;
    empty("Booking is currently unavailable", error.message, true); status(error.message);
  }

  function updateSeatStatsAndChips() {
    if (!data || !Array.isArray(data.seats)) return;

    const activeSeats = data.seats.filter(s => s.status !== "UNAVAILABLE" && s.status !== "INACTIVE");
    const availCount = activeSeats.filter(s => s.status === "AVAILABLE" && !selected.includes(s.id)).length;
    const heldOtherCount = activeSeats.filter(s => s.status === "HELD").length;
    const statEl = el("AvailableStat");
    if (statEl) {
      statEl.textContent = heldOtherCount > 0
        ? `${availCount}/${activeSeats.length} ghế trống · ${heldOtherCount} đang giữ`
        : `${availCount}/${activeSeats.length} ghế trống`;
    }

    const tray = el("SelectedTray");
    const trayCount = el("TrayCount");
    const chipsWrap = el("SelectedChips");
    if (!tray || !chipsWrap) return;

    const selectedSeatObjs = data.seats.filter(s => selected.includes(s.id));
    if (trayCount) trayCount.textContent = String(selectedSeatObjs.length);

    if (!selectedSeatObjs.length) {
      tray.hidden = true;
      chipsWrap.replaceChildren();
      return;
    }

    tray.hidden = false;
    chipsWrap.replaceChildren();

    const processedPairs = new Set();
    for (const seat of selectedSeatObjs) {
      const fullSeatCode = seat.seatLabel || `${seat.row}${seat.activeNumber || seat.number}`;
      if (seat.pairId) {
        if (processedPairs.has(seat.pairId)) continue;
        processedPairs.add(seat.pairId);
        const pairMembers = selectedSeatObjs
          .filter(s => s.pairId === seat.pairId)
          .sort((a, b) => (a.col || a.number) - (b.col || b.number));

        if (pairMembers.length === 2) {
          const [first, second] = pairMembers;
          const code1 = first.seatLabel || `${first.row}${first.activeNumber || first.number}`;
          const code2 = second.seatLabel || `${second.row}${second.activeNumber || second.number}`;
          const chip = node("div", undefined, "booking-seat-chip booking-seat-chip-pair");
          const labelSpan = node(
            "span",
            `${code1}, ${code2} · ${formatVnd(first.price + second.price)}`,
            "chip-text"
          );
          const removeBtn = node("button", "×", "chip-remove");
          removeBtn.type = "button";
          removeBtn.setAttribute("aria-label", `Bỏ chọn ghế ${code1}, ${code2}`);
          removeBtn.addEventListener("click", () => {
            selected = selected.filter(id => id !== first.id && id !== second.id);
            status("");
            syncSeatStates();
            updateSummary();
            syncServerSeatHold(selected);
          });
          chip.append(labelSpan, removeBtn);
          chipsWrap.append(chip);
          continue;
        }
      }

      const chip = node("div", undefined, "booking-seat-chip");
      const labelSpan = node("span", `${fullSeatCode} · ${formatVnd(seat.price)}`, "chip-text");
      const removeBtn = node("button", "×", "chip-remove");
      removeBtn.type = "button";
      removeBtn.setAttribute("aria-label", `Bỏ chọn ghế ${fullSeatCode}`);
      removeBtn.addEventListener("click", () => {
        selected = selected.filter(id => id !== seat.id);
        status("");
        syncSeatStates();
        updateSummary();
        syncServerSeatHold(selected);
      });
      chip.append(labelSpan, removeBtn);
      chipsWrap.append(chip);
    }
  }

  function updateSummary() {
    if (!data) return;
    const seats = data.seats.filter(s => selected.includes(s.id));
    const totals = calculateTotals(data.seats, selected, data.products, quantities);
    el("SeatCount").textContent = `${seats.length} ${seats.length === 1 ? "vé" : "vé"}`;

    const displayedLabels = seats.map(
      seat => seat.seatLabel || `${seat.row}${seat.activeNumber || seat.number}`
    );

    el("SelectedSeats").textContent = displayedLabels.join(", ") || "Chưa chọn ghế";
    el("TicketTotal").textContent = formatVnd(totals.tickets);
    el("ProductTotal").textContent = formatVnd(totals.extras);
    el("Total").textContent = formatVnd(totals.total);
    el("Extras").textContent = data.products.filter(p => quantities[p.id]).map(p => `${quantities[p.id]} × ${p.name}`).join(" · ");
    el("Next").disabled = !selected.length || step === 3;
    el("Next").hidden = step === 3;
    app.querySelectorAll("[data-payment-total]").forEach(amount => { amount.textContent = formatVnd(totals.total); });
    const nextLabel = ["Tiếp tục chọn bắp nước →", "Kiểm tra vé đã chọn →", "Thanh toán ngay →", "Thanh toán"][step];
    el("Next").textContent = nextLabel;
    el("CheckoutNote").textContent = selected.length ? "Ghế đang được giữ tạm thời. Tiến hành đặt vé ngay." : "Vui lòng chọn ghế để bắt đầu.";
    el("StepHint").textContent = [selected.length ? "Hài lòng với vị trí ghế? Chọn thêm bắp nước nhé." : "Chọn ít nhất 1 vị trí ghế để tiếp tục.", "Combo bắp nước là tùy chọn.", "Kiểm tra lại thông tin vé trước khi thanh toán.", "Quét mã thanh toán qua ngân hàng / VNPay."][step];

    const mobileSeats = el("MobileSeats");
    const mobileTotal = el("MobileTotal");
    const mobileNext = el("MobileNext");
    const mobileBar = el("MobileBar");
    if (mobileSeats) {
      mobileSeats.textContent = displayedLabels.length
        ? `Ghế (${seats.length}): ${displayedLabels.join(", ")}`
        : "Chưa chọn ghế";
    }
    if (mobileTotal) mobileTotal.textContent = formatVnd(totals.total);
    if (mobileNext) {
      mobileNext.disabled = !selected.length || step === 3;
      mobileNext.textContent = ["Tiếp tục →", "Xem lại vé →", "Thanh toán →", "Thanh toán"][step];
    }
    if (mobileBar) {
      mobileBar.hidden = step === 3;
    }

    updateSeatStatsAndChips();
    renderHoldTimerUi();
  }

  function renderTypeFilters() {
    const filterWrap = el("TypeFilters");
    if (!filterWrap || !data || !Array.isArray(data.seats)) return;
    filterWrap.replaceChildren();

    const shapesPresent = new Map();
    data.seats.forEach(s => {
      if (s.status === "INACTIVE" || s.status === "UNAVAILABLE") return;
      const shape = resolveSeatShape(s);
      if (!shapesPresent.has(shape)) {
        shapesPresent.set(shape, {
          shape,
          label: VIETNAMESE_SEAT_TYPE_NAMES[shape] || s.type || "Ghế thường",
          price: s.price
        });
      }
    });

    if (shapesPresent.size <= 1) {
      filterWrap.hidden = true;
      return;
    }
    filterWrap.hidden = false;

    const allBtn = node("button", "Tất cả hạng ghế", `booking-filter-pill${activeShapeFilter === "ALL" ? " is-active" : ""}`);
    allBtn.type = "button";
    allBtn.addEventListener("click", () => {
      activeShapeFilter = "ALL";
      renderTypeFilters();
      applySeatFilterHighlight();
    });
    filterWrap.append(allBtn);

    shapesPresent.forEach(info => {
      const btn = node(
        "button",
        `${info.label} (${formatVnd(info.price)})`,
        `booking-filter-pill${activeShapeFilter === info.shape ? " is-active" : ""}`
      );
      btn.type = "button";
      btn.addEventListener("click", () => {
        activeShapeFilter = activeShapeFilter === info.shape ? "ALL" : info.shape;
        renderTypeFilters();
        applySeatFilterHighlight();
      });
      filterWrap.append(btn);
    });
  }

  function applySeatFilterHighlight() {
    const seatsContainer = el("Seats");
    if (!seatsContainer) return;
    seatsContainer.querySelectorAll(".booking-seat").forEach(btn => {
      if (btn.classList.contains("seat-inactive-empty")) return;
      if (activeShapeFilter === "ALL") {
        btn.classList.remove("is-filter-dimmed", "is-filter-highlighted");
      } else {
        const match = btn.dataset.shapeType === activeShapeFilter;
        btn.classList.toggle("is-filter-highlighted", match);
        btn.classList.toggle("is-filter-dimmed", !match);
      }
    });
  }

  function renderLegend() {
    const legend = doc.getElementById("bookingLegend");
    if (!legend) return;
    legend.replaceChildren();

    const grid = node("div", undefined, "booking-legend-grid");

    // 1. Column Left: 5 Seat Status Colors
    const statusCol = node("div", undefined, "booking-legend-col");
    statusCol.append(node("div", "Trạng thái ghế", "legend-col-title"));
    const statuses = [
      { label: "Ghế trống (Có thể chọn)", cls: "status-available" },
      { label: "Ghế đang được khách khác giữ", cls: "status-holding" },
      { label: "Ghế bạn đang chọn & giữ", cls: "status-selected" },
      { label: "Ghế đã bán", cls: "status-sold" },
      { label: "Ghế đã đặt trước", cls: "status-reserved" }
    ];
    statuses.forEach(s => {
      const row = node("div", undefined, "legend-row");
      const icon = node("div", undefined, `legend-seat-icon ${s.cls}`);
      icon.innerHTML = getSeatSvg("standard");
      const text = node("span", s.label, "legend-text");
      row.append(icon, text);
      statusCol.append(row);
    });

    // 2. Column Right: Seat Shapes & Prices
    const typesCol = node("div", undefined, "booking-legend-col");
    typesCol.append(node("div", "Loại ghế & Giá vé", "legend-col-title"));
    const typesInSeats = new Map();
    if (Array.isArray(data.seats)) {
      data.seats.forEach(s => {
        if (!s.type || s.status === "INACTIVE" || s.status === "UNAVAILABLE") return;
        const shape = resolveSeatShape(s);
        if (!typesInSeats.has(shape)) {
          typesInSeats.set(shape, {
            name: s.type,
            shape: shape,
            price: shape === "sweetbox" ? s.price * 2 : s.price
          });
        }
      });
    }

    const typeList = [];
    if (typesInSeats.size > 0) {
      typesInSeats.forEach(val => typeList.push(val));
    } else if (Array.isArray(data.seatTypes) && data.seatTypes.length) {
      const basePrice = Number(data.basePrice || 60000);
      data.seatTypes.forEach(st => {
        const shape = st.shapeType || "standard";
        const unitPrice = basePrice + Number(st.surcharge || 0);
        typeList.push({
          name: st.typeName || st.name,
          shape: shape,
          price: shape === "sweetbox" ? unitPrice * 2 : unitPrice
        });
      });
    } else {
      typeList.push(
        { name: "Standard", shape: "standard", price: 60000 },
        { name: "VIP", shape: "vip", price: 65000 },
        { name: "Sweetbox", shape: "sweetbox", price: 130000 }
      );
    }

    const shapeOrder = ["standard", "vip", "sweetbox", "imax", "4dx", "recliner", "wheelchair"];
    typeList.sort((a, b) => {
      const ia = shapeOrder.indexOf(a.shape);
      const ib = shapeOrder.indexOf(b.shape);
      return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
    });

    typeList.forEach(t => {
      const row = node("div", undefined, "legend-row");
      const iconCls = `legend-seat-icon shape-${t.shape}${t.shape === "sweetbox" ? " legend-icon-sweetbox" : ""}`;
      const shapeIcon = node("div", undefined, iconCls);
      shapeIcon.innerHTML = getSeatSvg(t.shape);

      const priceWrap = node("div", undefined, "legend-price-wrap");
      const title = VIETNAMESE_SEAT_TYPE_NAMES[t.shape] || t.name;
      const priceSuffix = t.shape === "sweetbox" ? " / cặp" : "";
      priceWrap.append(
        node("span", title, "legend-type-title"),
        node("span", formatVnd(t.price) + priceSuffix, "legend-type-val")
      );

      row.append(shapeIcon, priceWrap);
      typesCol.append(row);
    });

    grid.append(statusCol, typesCol);
    legend.append(grid);
  }

  function updateSeatHoverBar(seatOrPairLabel, typeLabel, priceValue, seatStatus, isSelectedNow, holdRemSec) {
    const hoverBar = el("SeatHoverInfo");
    const hoverText = el("SeatHoverText");
    if (!hoverBar || !hoverText) return;

    let stateText = "Còn trống — Nhấn để chọn và giữ ghế";
    let stateClass = "state-available";
    if (isSelectedNow) {
      stateText = "Đang giữ cho bạn — Nhấn lại để bỏ chọn";
      stateClass = "state-selected";
    } else if (seatStatus === "HELD") {
      const remLabel = holdRemSec > 0 ? ` (còn ${formatCountdown(holdRemSec)})` : "";
      stateText = `Đang được khách khác giữ tạm thời${remLabel} — Không thể chọn`;
      stateClass = "state-held";
    } else if (seatStatus === "BOOKED") {
      stateText = "Đã bán — Không thể chọn";
      stateClass = "state-booked";
    } else if (seatStatus === "RESERVED") {
      stateText = "Đã đặt trước — Không thể chọn";
      stateClass = "state-reserved";
    }

    hoverBar.className = `booking-seat-hover-bar ${stateClass}`;
    hoverText.textContent = `${seatOrPairLabel} · ${typeLabel} · ${formatVnd(priceValue)} — ${stateText}`;
  }

  function syncSeatStates() {
    el("Seats").querySelectorAll(".booking-seat").forEach(btn => {
      if (btn.classList.contains("seat-sweetbox-seamless")) {
        const id1 = btn.dataset.seatId1;
        const id2 = btn.dataset.seatId2;
        const isPressed = Boolean(id1 && id2 && selected.includes(id1) && selected.includes(id2));
        btn.setAttribute("aria-pressed", String(isPressed));
      } else if (btn.dataset.seatId) {
        btn.setAttribute("aria-pressed", String(selected.includes(btn.dataset.seatId)));
      }
    });
  }

  function renderSeats() {
    renderLegend();
    renderTypeFilters();

    el("Seats").replaceChildren();
    const rows = new Map();
    data.seats.forEach(seat => {
      if (!rows.has(seat.row)) rows.set(seat.row, []);
      rows.get(seat.row).push(seat);
    });

    rows.forEach((seats, label) => {
      const row = node("div", undefined, "booking-seat-row");
      row.append(node("span", label, "booking-row-label"));

      seats.sort((a, b) => (a.col || a.number) - (b.col || b.number));

      let activeNum = 0;
      seats.forEach(seat => {
        const isInactive = seat.status === "UNAVAILABLE" || seat.status === "INACTIVE";
        if (!isInactive) {
          activeNum++;
          seat.activeNumber = activeNum;
          seat.seatLabel = `${label}${activeNum}`;
        } else {
          seat.activeNumber = null;
          seat.seatLabel = `${label}×`;
        }
      });

      const handledSeatIds = new Set();

      seats.forEach((seat, index) => {
        if (handledSeatIds.has(seat.id)) return;

        const isInactive = seat.status === "UNAVAILABLE" || seat.status === "INACTIVE";
        if (isInactive) {
          const emptySlot = node("div", undefined, "booking-seat seat-inactive-empty");
          emptySlot.style.visibility = "hidden";
          emptySlot.style.pointerEvents = "none";
          row.append(emptySlot);
          return;
        }

        const typeSlug = seat.typeSlug || (seat.type || "standard").toLowerCase().replace(/[^a-z0-9]+/g, "-");
        const normalizedShape = resolveSeatShape(seat);
        const vietnameseType = VIETNAMESE_SEAT_TYPE_NAMES[normalizedShape] || seat.type;

        // Sweetbox seamless pair rendering: 1 continuous double couch spanning 2 slots
        if (normalizedShape === "sweetbox") {
          let partner = null;
          if (seat.pairId) {
            partner = seats.find(other => other !== seat && other.pairId === seat.pairId && other.status !== "UNAVAILABLE" && other.status !== "INACTIVE");
          }
          if (!partner) {
            const nextSeat = seats[index + 1];
            if (nextSeat && nextSeat.status !== "UNAVAILABLE" && nextSeat.status !== "INACTIVE") {
              if (resolveSeatShape(nextSeat) === "sweetbox") {
                partner = nextSeat;
              }
            }
          }

          if (partner && !handledSeatIds.has(partner.id)) {
            handledSeatIds.add(seat.id);
            handledSeatIds.add(partner.id);

            const first = (seat.col || seat.number) < (partner.col || partner.number) ? seat : partner;
            const second = first === seat ? partner : seat;

            if (!first.pairId || !second.pairId || first.pairId !== second.pairId) {
              const pairId = `${first.id}-${second.id}`;
              first.pairId = pairId;
              second.pairId = pairId;
            }

            const isPairAvailable = first.status === "AVAILABLE" && second.status === "AVAILABLE";
            let pairStatus = "AVAILABLE";
            if (first.status === "BOOKED" || second.status === "BOOKED") {
              pairStatus = "BOOKED";
            } else if (first.status === "HELD" || second.status === "HELD") {
              pairStatus = "HELD";
            } else if (first.status === "RESERVED" || second.status === "RESERVED") {
              pairStatus = "RESERVED";
            }

            let seatClasses = `booking-seat seat-shape-sweetbox seat-sweetbox-seamless seat-type-${typeSlug}`;
            if (pairStatus === "BOOKED") {
              seatClasses += " booked";
            } else if (pairStatus === "HELD") {
              seatClasses += " held";
            } else if (pairStatus === "RESERVED") {
              seatClasses += " reserved";
            }

            const button = node("button", undefined, seatClasses);
            button.type = "button";
            button.disabled = !isPairAvailable;
            button.dataset.seatId = `${first.id},${second.id}`;
            button.dataset.seatId1 = first.id;
            button.dataset.seatId2 = second.id;
            button.dataset.aisle = String(second.aisleAfter === true);
            button.dataset.seatType = first.type;
            button.dataset.shapeType = "sweetbox";
            button.dataset.typeSlug = typeSlug;
            button.dataset.pairId = first.pairId;
            button.dataset.status = pairStatus;

            const isBothSelected = selected.includes(first.id) && selected.includes(second.id);
            button.setAttribute("aria-pressed", String(isBothSelected));
            button.setAttribute("aria-label", `Ghế đôi ${first.seatLabel} - ${second.seatLabel}, ${first.type}, ${formatVnd(first.price + second.price)}, ${pairStatus.toLowerCase()}`);

            const svgWrap = node("div", undefined, "seat-svg-wrap");
            svgWrap.innerHTML = getSeatSvg("sweetbox", "full");

            const labelsWrap = node("div", undefined, "sweetbox-labels");
            const num1 = node("span", first.seatLabel || `${label}${first.activeNumber}`, "sweetbox-num");
            const num2 = node("span", second.seatLabel || `${label}${second.activeNumber}`, "sweetbox-num");
            labelsWrap.append(num1, num2);

            button.append(svgWrap, labelsWrap);

            const showPairHover = () => {
              const selNow = selected.includes(first.id) && selected.includes(second.id);
              const remSec = Math.max(Number(first.holdRemainingSeconds || 0), Number(second.holdRemainingSeconds || 0));
              updateSeatHoverBar(
                `Ghế đôi ${first.seatLabel}–${second.seatLabel}`,
                vietnameseType,
                first.price + second.price,
                pairStatus,
                selNow,
                remSec
              );
            };
            button.addEventListener("mouseenter", showPairHover);
            button.addEventListener("focus", showPairHover);

            if (isPairAvailable) {
              button.addEventListener("click", () => {
                if (!requireAuthenticationForBooking()) return;
                hideExpiryNotice();
                const isSelected = selected.includes(first.id) || selected.includes(second.id);
                if (isSelected) {
                  selected = selected.filter(id => id !== first.id && id !== second.id);
                  status("");
                } else {
                  if (selected.length + 2 > 8) {
                    status("Bạn chỉ có thể chọn tối đa 8 ghế mỗi lượt đặt (Ghế đôi chiếm 2 chỗ).");
                  } else {
                    selected = [...selected, first.id, second.id];
                    status("");
                  }
                }

                syncSeatStates();
                updateSummary();
                showPairHover();
                syncServerSeatHold(selected);
              });
            }

            row.append(button);
            return;
          }
        }

        // Standard, VIP, IMAX, 4DX, Recliner, Wheelchair
        handledSeatIds.add(seat.id);
        const isAvailable = seat.status === "AVAILABLE";
        const text = seat.seatLabel || `${label}${seat.activeNumber}`;
        let seatClasses = `booking-seat seat-shape-${normalizedShape} seat-type-${typeSlug}`;
        if (seat.status === "BOOKED") {
          seatClasses += " booked";
        } else if (seat.status === "HELD") {
          seatClasses += " held";
        } else if (seat.status === "RESERVED") {
          seatClasses += " reserved";
        }

        const button = node("button", undefined, seatClasses);
        button.type = "button";
        button.disabled = !isAvailable;
        const seatId = seat.id;
        button.dataset.seatId = seatId;
        button.dataset.aisle = String(seat.aisleAfter === true);
        button.dataset.seatType = seat.type;
        button.dataset.shapeType = normalizedShape;
        button.dataset.typeSlug = typeSlug;
        button.dataset.pairId = seat.pairId || "";
        button.dataset.status = seat.status;
        button.setAttribute("aria-pressed", String(selected.includes(seatId)));
        button.setAttribute("aria-label", `${seat.seatLabel || seatId}, ${seat.type}, ${formatVnd(seat.price)}, ${seat.status.toLowerCase()}`);

        const svgWrap = node("div", undefined, "seat-svg-wrap");
        svgWrap.innerHTML = getSeatSvg(normalizedShape, "full");
        const numSpan = node("span", text, "seat-num");
        button.append(svgWrap, numSpan);

        const showSingleHover = () => {
          updateSeatHoverBar(
            `Ghế ${seat.seatLabel || seatId}`,
            vietnameseType,
            seat.price,
            seat.status,
            selected.includes(seatId),
            Number(seat.holdRemainingSeconds || 0)
          );
        };
        button.addEventListener("mouseenter", showSingleHover);
        button.addEventListener("focus", showSingleHover);

        if (isAvailable) {
          button.addEventListener("click", () => {
            if (!requireAuthenticationForBooking()) return;
            hideExpiryNotice();
            const result = toggleSeat(selected, seat, 8, data.seats);
            selected = result.selected;
            status(result.error);

            syncSeatStates();
            updateSummary();
            showSingleHover();
            if (!result.error) {
              syncServerSeatHold(selected);
            }
          });
        }

        row.append(button);
      });

      const rightLabel = node("span", label, "booking-row-label booking-row-label-right");
      row.append(rightLabel);
      el("Seats").append(row);
    });

    applySeatFilterHighlight();
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
    for (const [id, val] of [["Title", data.movieTitle], ["CinemaName", data.cinemaName], ["Screening", data.screeningLabel], ["Room", data.roomName], ["Format", data.format || ""]]) el(id).textContent = val;
    const posterUrl = safeImageUrl(data.posterUrl, "");
    el("Poster").hidden = !posterUrl;
    if (posterUrl) el("Poster").src = posterUrl;
    const rating = ["P", "T13", "T16", "T18"].includes(data.ageRating) ? data.ageRating : "";
    el("Rating").textContent = rating; el("Rating").className = `badge-age-${rating.toLowerCase()}`;
    el("Empty").hidden = true; el("Content").hidden = false; status();
    screenings?.hide();
    el("ChangeScreening").hidden = preview;
    // Restore active holds belonging to the current user/session if returned by backend
    if (isUserAuthenticated() && Array.isArray(data.heldSeats) && data.heldSeats.length > 0) {
      const validIds = new Set(data.seats.filter(s => s.status === "AVAILABLE").map(s => s.id));
      selected = data.heldSeats.filter(id => validIds.has(id));
      if (selected.length > 0) {
        const rem = Number(data.holdRemainingSeconds) > 0 ? Number(data.holdRemainingSeconds) : DEFAULT_HOLD_SECONDS;
        startOrUpdateHoldTimer(rem, Number(data.holdDurationSeconds) || DEFAULT_HOLD_SECONDS);
      }
    }

    renderSeats();
    renderLegend();
    renderProducts();
    showStep(0, false);
    startOtherHoldsWatcher();
    // Auto-center horizontal scroll on smaller viewports so center seats are immediately in view
    setTimeout(() => {
      const scrollEl = el("SeatScroll");
      if (scrollEl && scrollEl.scrollWidth > scrollEl.clientWidth) {
        scrollEl.scrollLeft = Math.floor((scrollEl.scrollWidth - scrollEl.clientWidth) / 2);
      }
    }, 30);
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
    beginRequest(); clearSelection(false); el("Content").hidden = true; el("Empty").hidden = true;
    const url = new URL(win.location.href); url.searchParams.delete("showtimeId"); win.history.replaceState({}, "", url);
    screenings?.show();
  }

  function applyZoom(nextZoom) {
    zoomPercent = Math.max(75, Math.min(135, nextZoom));
    const seatsEl = el("Seats");
    const zoomLabel = el("ZoomLabel");
    if (seatsEl) {
      seatsEl.style.transform = zoomPercent === 100 ? "" : `scale(${(zoomPercent / 100).toFixed(2)})`;
      seatsEl.style.transformOrigin = "top center";
    }
    if (zoomLabel) zoomLabel.textContent = `${zoomPercent}%`;
  }

  el("ChangeScreening").addEventListener("click", startPicker);
  el("Poster").addEventListener("error", () => { el("Poster").hidden = true; });
  el("Retry").addEventListener("click", () => retry());
  el("Next").addEventListener("click", () => {
    if (!requireAuthenticationForBooking()) return;
    if (selected.length && step < 3) showStep(step + 1);
  });
  el("MobileNext")?.addEventListener("click", () => {
    if (!requireAuthenticationForBooking()) return;
    if (selected.length && step < 3) showStep(step + 1);
  });
  el("Previous").addEventListener("click", () => { if (step > 0) showStep(step - 1); });

  el("ZoomOut")?.addEventListener("click", () => applyZoom(zoomPercent - 10));
  el("ZoomIn")?.addEventListener("click", () => applyZoom(zoomPercent + 10));
  el("ZoomLabel")?.addEventListener("click", () => applyZoom(100));

  el("ClearSeats")?.addEventListener("click", () => {
    selected = [];
    status("");
    syncSeatStates();
    updateSummary();
    syncServerSeatHold([]);
  });

  el("ExpiryDismiss")?.addEventListener("click", () => {
    hideExpiryNotice();
  });

  el("RefreshMap")?.addEventListener("click", async () => {
    if (!data || data.preview === true || !data.showtimeId) return;
    try {
      const fresh = await loadBookingData(data.showtimeId);
      if (fresh && Array.isArray(fresh.seats)) {
        const prevSelected = [...selected];
        data = validateBooking(fresh);
        const stillAvailable = new Set(data.seats.filter(s => s.status === "AVAILABLE").map(s => s.id));
        selected = prevSelected.filter(id => stillAvailable.has(id));
        renderSeats();
        syncSeatStates();
        updateSummary();
      }
    } catch (_) { }
  });

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
