import { getSeatSvg } from "./seat-icons.js?v=3.2";
import { initPayment } from "./booking-payment.js?v=1.0";
import { loadBookingData } from "./booking-data.js?v=1.0";
import { initScreenings } from "./booking-screenings.js?v=1.0";

export function formatVnd(value) {
  return new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND" }).format(value);
}

export function toggleSeat(selected, seat, limit = 8, allSeats = []) {
  if (seat.status !== "AVAILABLE") return { selected, error: "Ghế này hiện không khả dụng." };

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
        && ["AVAILABLE", "HELD", "BOOKED", "UNAVAILABLE", "INACTIVE"].includes(s.status))
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
    el("SeatCount").textContent = `${seats.length} ${seats.length === 1 ? "vé" : "vé"}`;

    // Group paired seats together for crystal-clear cart display
    const displayedLabels = [];
    const processedPairIds = new Set();

    for (const seat of seats) {
      if (seat.pairId) {
        if (!processedPairIds.has(seat.pairId)) {
          processedPairIds.add(seat.pairId);
          const pairSeats = seats.filter(s => s.pairId === seat.pairId).sort((a, b) => (a.col || a.number) - (b.col || b.number));
          if (pairSeats.length === 2) {
            displayedLabels.push(`Ghế đôi (${pairSeats[0].seatLabel || pairSeats[0].id} - ${pairSeats[1].seatLabel || pairSeats[1].id})`);
          } else {
            displayedLabels.push(`${seat.seatLabel || seat.id} (${seat.type || "Sweetbox"})`);
          }
        }
      } else {
        displayedLabels.push(`${seat.seatLabel || seat.id} (${seat.type || "Standard"})`);
      }
    }

    el("SelectedSeats").textContent = displayedLabels.join(", ") || "Chưa chọn ghế";
    el("TicketTotal").textContent = formatVnd(totals.tickets);
    el("ProductTotal").textContent = formatVnd(totals.extras);
    el("Total").textContent = formatVnd(totals.total);
    el("Extras").textContent = data.products.filter(p => quantities[p.id]).map(p => `${quantities[p.id]} × ${p.name}`).join(" · ");
    el("Next").disabled = !selected.length || step === 3;
    el("Next").hidden = step === 3;
    app.querySelectorAll("[data-payment-total]").forEach(amount => { amount.textContent = formatVnd(totals.total); });
    el("Next").textContent = ["Tiếp tục chọn bắp nước →", "Kiểm tra vé đã chọn →", "Thanh toán ngay →", "Thanh toán"][step];
    el("CheckoutNote").textContent = selected.length ? "Ghế đang được chọn. Tiến hành đặt vé ngay." : "Vui lòng chọn ghế để bắt đầu.";
    el("StepHint").textContent = [selected.length ? "Hài lòng với vị trí ghế? Chọn thêm bắp nước nhé." : "Chọn ít nhất 1 vị trí ghế để tiếp tục.", "Combo bắp nước là tùy chọn.", "Kiểm tra lại thông tin vé trước khi thanh toán.", "Quét mã thanh toán qua ngân hàng / VNPay."][step];
  }

  function renderLegend() {
    const legend = doc.getElementById("bookingLegend");
    if (!legend) return;
    legend.replaceChildren();

    const grid = node("div", undefined, "booking-legend-grid");

    // 1. Column Left: 5 Seat Status Colors (Chính xác theo ảnh mẫu rạp chiếu phim)
    const statusCol = node("div", undefined, "booking-legend-col");
    const statuses = [
      { label: "Ghế trống", cls: "status-available" },
      { label: "Ghế đang được giữ", cls: "status-holding" },
      { label: "Ghế đang chọn", cls: "status-selected" },
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

    // Identify which seat shapes actually exist in this screening
    const typesInSeats = new Map();
    if (Array.isArray(data.seats)) {
      data.seats.forEach(s => {
        if (!s.type || s.status === "INACTIVE" || s.status === "UNAVAILABLE") return;
        const rawShape = s.shapeType || (s.type || "standard").toLowerCase().replace(/[^a-z0-9]+/g, "");
        const shape = ["standard", "vip", "sweetbox", "imax", "4dx", "recliner", "wheelchair"].includes(rawShape)
          ? rawShape
          : (s.type?.toUpperCase().includes("SWEETBOX") ? "sweetbox" : (s.type?.toUpperCase().includes("VIP") ? "vip" : "standard"));

        if (!typesInSeats.has(shape)) {
          typesInSeats.set(shape, {
            name: s.type,
            shape: shape,
            price: s.price
          });
        }
      });
    }

    const typeVietnameseNames = {
      standard: "Ghế thường",
      vip: "Ghế VIP",
      sweetbox: "Ghế đôi",
      imax: "Ghế IMAX",
      "4dx": "Ghế 4DX",
      recliner: "Ghế Recliner",
      wheelchair: "Ghế xe lăn"
    };

    const typeList = [];
    if (typesInSeats.size > 0) {
      typesInSeats.forEach(val => typeList.push(val));
    } else if (Array.isArray(data.seatTypes) && data.seatTypes.length) {
      const basePrice = Number(data.basePrice || 60000);
      data.seatTypes.forEach(st => {
        const shape = st.shapeType || "standard";
        typeList.push({
          name: st.typeName || st.name,
          shape: shape,
          price: basePrice + Number(st.surcharge || 0)
        });
      });
    } else {
      typeList.push(
        { name: "Standard", shape: "standard", price: 60000 },
        { name: "VIP", shape: "vip", price: 65000 },
        { name: "Sweetbox", shape: "sweetbox", price: 130000 }
      );
    }

    typeList.forEach(t => {
      const row = node("div", undefined, "legend-row");
      const iconCls = `legend-seat-icon shape-${t.shape}${t.shape === "sweetbox" ? " legend-icon-sweetbox" : ""}`;
      const shapeIcon = node("div", undefined, iconCls);
      shapeIcon.innerHTML = getSeatSvg(t.shape);

      const priceWrap = node("div", undefined, "legend-price-wrap");
      const title = typeVietnameseNames[t.shape] || t.name;
      priceWrap.append(
        node("span", title, "legend-type-title"),
        node("span", formatVnd(t.price), "legend-type-val")
      );

      row.append(shapeIcon, priceWrap);
      typesCol.append(row);
    });

    grid.append(statusCol, typesCol);
    legend.append(grid);
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
    el("Seats").replaceChildren();
    const rows = new Map();
    data.seats.forEach(seat => {
      if (!rows.has(seat.row)) rows.set(seat.row, []);
      rows.get(seat.row).push(seat);
    });

    rows.forEach((seats, label) => {
      const row = node("div", undefined, "booking-seat-row");
      row.append(node("span", label, "booking-row-label"));

      // Sort seats by physical column
      seats.sort((a, b) => (a.col || a.number) - (b.col || b.number));

      // Re-index active seats in this row (ignoring unavailable/inactive slots!)
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
          // Customer interface: "những chỗ unavailable để trống màu đen thôi"
          const emptySlot = node("div", undefined, "booking-seat seat-inactive-empty");
          emptySlot.style.visibility = "hidden";
          emptySlot.style.pointerEvents = "none";
          row.append(emptySlot);
          return;
        }

        const typeSlug = seat.typeSlug || (seat.type || "standard").toLowerCase().replace(/[^a-z0-9]+/g, "-");
        const shapeType = seat.shapeType || (seat.type || "standard").toLowerCase().replace(/[^a-z0-9]+/g, "");
        const normalizedShape = ['standard', 'vip', 'sweetbox', 'imax', '4dx', 'recliner', 'wheelchair'].includes(shapeType)
          ? shapeType
          : (seat.type?.toUpperCase().includes('SWEETBOX') ? 'sweetbox' : (seat.type?.toUpperCase().includes('VIP') ? 'vip' : 'standard'));

        // Sweetbox seamless pair rendering: 1 continuous double couch spanning 2 slots
        if (normalizedShape === "sweetbox") {
          let partner = null;
          if (seat.pairId) {
            partner = seats.find(other => other !== seat && other.pairId === seat.pairId && other.status !== "UNAVAILABLE" && other.status !== "INACTIVE");
          }
          if (!partner) {
            const nextSeat = seats[index + 1];
            if (nextSeat && nextSeat.status !== "UNAVAILABLE" && nextSeat.status !== "INACTIVE") {
              const nextShape = nextSeat.shapeType || (nextSeat.type || "").toLowerCase().replace(/[^a-z0-9]+/g, "");
              if (nextShape === "sweetbox" || (nextSeat.type || "").toUpperCase().includes("SWEETBOX")) {
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

            const isBothSelected = selected.includes(first.id) && selected.includes(second.id);
            button.setAttribute("aria-pressed", String(isBothSelected));
            button.setAttribute("aria-label", `Ghế đôi ${first.seatLabel} - ${second.seatLabel}, ${first.type}, ${formatVnd(first.price + second.price)}, ${pairStatus.toLowerCase()}`);

            const svgWrap = node("div", undefined, "seat-svg-wrap");
            svgWrap.innerHTML = getSeatSvg("sweetbox", "full");

            const labelsWrap = node("div", undefined, "sweetbox-labels");
            const num1 = node("span", String(first.activeNumber), "sweetbox-num");
            const num2 = node("span", String(second.activeNumber), "sweetbox-num");
            labelsWrap.append(num1, num2);

            button.append(svgWrap, labelsWrap);

            if (isPairAvailable) {
              button.addEventListener("click", () => {
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
              });
            }

            row.append(button);
            return;
          }
        }

        // Standard, VIP, IMAX, 4DX, Recliner, Wheelchair (Numbered like all other seats)
        handledSeatIds.add(seat.id);
        const isAvailable = seat.status === "AVAILABLE";
        const text = String(seat.activeNumber);
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
        button.setAttribute("aria-pressed", String(selected.includes(seatId)));
        button.setAttribute("aria-label", `${seat.seatLabel || seatId}, ${seat.type}, ${formatVnd(seat.price)}, ${seat.status.toLowerCase()}`);

        const svgWrap = node("div", undefined, "seat-svg-wrap");
        svgWrap.innerHTML = getSeatSvg(normalizedShape, "full");
        const numSpan = node("span", text, "seat-num");
        button.append(svgWrap, numSpan);

        if (isAvailable) {
          button.addEventListener("click", () => {
            const result = toggleSeat(selected, seat, 8, data.seats);
            selected = result.selected;
            status(result.error);

            syncSeatStates();
            updateSummary();
          });
        }

        row.append(button);
      });
      el("Seats").append(row);
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
