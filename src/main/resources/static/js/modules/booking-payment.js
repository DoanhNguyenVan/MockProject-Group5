// BOX Cinema - PayOS Integrated Payment Module (V2.1 Specification)
// Complies with BR-03 (One PayOS payment), BR-04 (No redirect, in-page QR), BR-05 (Webhook/Status polling)
import { getCsrfHeaders } from "../core/http-client.js";

export function initPayment(doc = document) {
  let activePaymentId = null;
  let activeBookingId = null;
  let activeOrderCode = null;
  let pollInterval = null;
  let timerInterval = null;
  let isStartingCheckout = false;

  // Voucher state
  let appliedVoucherCode = null;
  let appliedDiscountAmount = 0;

  const loadingEl = doc.getElementById("payosLoading");
  const activeLayout = doc.getElementById("payosActiveLayout");
  const successLayout = doc.getElementById("payosSuccessLayout");
  const qrImage = doc.getElementById("payosQrImage");
  const bankNameEl = doc.getElementById("payosBankName");
  const accountNoEl = doc.getElementById("payosAccountNo");
  const accountNameEl = doc.getElementById("payosAccountName");
  const amountEl = doc.getElementById("payosAmount");
  const descEl = doc.getElementById("payosDescription");
  const timerEl = doc.getElementById("payosTimer");
  const goToTicketBtn = doc.getElementById("btnGoToTicket");

  function createEl(tag, className = "", text = "") {
    const node = doc.createElement(tag);
    if (className) node.className = className;
    if (text !== null && text !== undefined && text !== "") {
      node.textContent = String(text);
    }
    return node;
  }

  function setChildren(parent, ...nodes) {
    if (!parent) return;
    if (typeof parent.replaceChildren === "function") {
      parent.replaceChildren(...nodes);
    } else {
      parent.textContent = "";
      for (const node of nodes) {
        if (typeof parent.appendChild === "function") {
          parent.appendChild(node);
        } else if (typeof parent.append === "function") {
          parent.append(node);
        }
      }
    }
  }

  function renderLoadingNotice(alertClass, title, bodyText, actionLink = null) {
    if (!loadingEl) return;
    const alertBox = createEl("div", `alert ${alertClass} text-start`);
    const heading = createEl("h5", "fw-bold mb-2", title);
    const msg = createEl("p", actionLink ? "mb-2" : "mb-0", bodyText);
    setChildren(alertBox, heading, msg);
    if (actionLink) {
      const link = createEl("a", "btn btn-sm btn-warning fw-semibold mt-1", actionLink.label);
      link.href = actionLink.href;
      if (typeof alertBox.appendChild === "function") {
        alertBox.appendChild(link);
      } else if (typeof alertBox.append === "function") {
        alertBox.append(link);
      }
    }
    setChildren(loadingEl, alertBox);
    loadingEl.classList.remove("d-none");
    if (activeLayout) activeLayout.classList.add("d-none");
    if (successLayout) successLayout.classList.add("d-none");
  }

  // ---- Voucher UI elements ----
  const voucherInput = doc.getElementById("voucherCodeInput");
  const btnApplyVoucher = doc.getElementById("btnApplyVoucher");
  const voucherMsg = doc.getElementById("voucherMessage");
  const appliedBadge = doc.getElementById("voucherAppliedBadge");
  const appliedCodeEl = doc.getElementById("voucherAppliedCode");
  const appliedCodeSidebar = doc.getElementById("voucherAppliedCodeSidebar");
  const discountLineEl = doc.getElementById("bookingDiscountLine");
  const discountAmountEl = doc.getElementById("bookingDiscountAmount");
  const btnRemoveVoucher = doc.getElementById("btnRemoveVoucher");
  const btnShowVoucherList = doc.getElementById("btnShowVoucherList");
  const voucherListContainer = doc.getElementById("voucherListContainer");

  // Format money VND
  function formatMoney(amount) {
    return Number(amount).toLocaleString("vi-VN") + " ₫";
  }

  function getBaseSubtotal() {
    const ticketRaw = doc.getElementById("bookingTicketTotal")?.textContent?.replace(/[^0-9]/g, "") || "0";
    const productRaw = doc.getElementById("bookingProductTotal")?.textContent?.replace(/[^0-9]/g, "") || "0";
    return (parseInt(ticketRaw, 10) || 0) + (parseInt(productRaw, 10) || 0);
  }

  function refreshDisplayedTotals() {
    const base = getBaseSubtotal();
    const finalTotal = Math.max(0, base - appliedDiscountAmount);

    const totalEl = doc.getElementById("bookingTotal");
    if (totalEl) totalEl.textContent = formatMoney(finalTotal);

    const mobileTotalEl = doc.getElementById("bookingMobileTotal");
    if (mobileTotalEl) mobileTotalEl.textContent = formatMoney(finalTotal);

    doc.querySelectorAll("[data-payment-total]").forEach(amount => {
      amount.textContent = formatMoney(finalTotal);
    });
  }

  function showVoucherSuccess(code, discountAmount, promotionName) {
    appliedVoucherCode = code;
    appliedDiscountAmount = Number(discountAmount) || 0;

    if (voucherMsg) {
      voucherMsg.textContent = "";
      voucherMsg.className = "voucher-message";
    }
    if (appliedBadge) appliedBadge.classList.remove("d-none");
    if (appliedCodeEl) appliedCodeEl.textContent = code;
    if (appliedCodeSidebar) appliedCodeSidebar.textContent = code;
    if (discountLineEl) discountLineEl.classList.remove("d-none");
    if (discountAmountEl) discountAmountEl.textContent = "-" + formatMoney(appliedDiscountAmount);
    if (btnApplyVoucher) btnApplyVoucher.disabled = true;
    if (voucherInput) voucherInput.disabled = true;

    refreshDisplayedTotals();
  }

  function clearVoucher() {
    appliedVoucherCode = null;
    appliedDiscountAmount = 0;

    if (voucherInput) {
      voucherInput.value = "";
      voucherInput.disabled = false;
    }
    if (voucherMsg) {
      voucherMsg.textContent = "";
      voucherMsg.className = "voucher-message";
    }
    if (appliedBadge) appliedBadge.classList.add("d-none");
    if (discountLineEl) discountLineEl.classList.add("d-none");
    if (discountAmountEl) discountAmountEl.textContent = "";
    if (appliedCodeSidebar) appliedCodeSidebar.textContent = "";
    if (btnApplyVoucher) btnApplyVoucher.disabled = false;

    refreshDisplayedTotals();
  }

  async function applyVoucher(code) {
    const orderAmount = getBaseSubtotal();
    if (!code || code.trim() === "") {
      if (voucherMsg) {
        voucherMsg.textContent = "Please enter a voucher code.";
        voucherMsg.className = "voucher-message text-danger";
      }
      return;
    }
    if (btnApplyVoucher) btnApplyVoucher.disabled = true;
    if (voucherMsg) {
      voucherMsg.textContent = "Validating...";
      voucherMsg.className = "voucher-message text-muted";
    }

    try {
      const res = await fetch("/api/vouchers/validate", {
        method: "POST",
        headers: { "Content-Type": "application/json", ...getCsrfHeaders() },
        credentials: "same-origin",
        body: JSON.stringify({ voucherCode: code.trim().toUpperCase(), orderAmount })
      });
      const data = await res.json();
      if (data.success) {
        showVoucherSuccess(data.voucherCode, data.discountAmount, data.promotionName);
        if (voucherListContainer) voucherListContainer.classList.add("d-none");
      } else {
        if (voucherMsg) {
          voucherMsg.textContent = data.message || "Invalid voucher code.";
          voucherMsg.className = "voucher-message text-danger";
        }
        if (btnApplyVoucher) btnApplyVoucher.disabled = false;
      }
    } catch (e) {
      if (voucherMsg) {
        voucherMsg.textContent = "Connection error. Please try again.";
        voucherMsg.className = "voucher-message text-danger";
      }
      if (btnApplyVoucher) btnApplyVoucher.disabled = false;
    }
  }

  // Wire up voucher events
  if (btnApplyVoucher) {
    btnApplyVoucher.addEventListener("click", () => applyVoucher(voucherInput?.value || ""));
  }
  if (voucherInput) {
    voucherInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        applyVoucher(voucherInput.value);
      }
    });
  }
  if (btnRemoveVoucher) {
    btnRemoveVoucher.addEventListener("click", () => clearVoucher());
  }

  // Show/hide voucher list popup
  if (btnShowVoucherList) {
    btnShowVoucherList.addEventListener("click", async () => {
      if (!voucherListContainer) return;
      const isVisible = !voucherListContainer.classList.contains("d-none");
      if (isVisible) {
        voucherListContainer.classList.add("d-none");
        return;
      }
      setChildren(voucherListContainer, createEl("div", "text-muted small p-2", "Loading available vouchers..."));
      voucherListContainer.classList.remove("d-none");

      const amount = getBaseSubtotal();
      try {
        const res = await fetch(`/api/vouchers/available?amount=${amount}`);
        const promos = await res.json();
        if (!promos || !promos.length) {
          setChildren(voucherListContainer, createEl("div", "text-muted small p-2", "No vouchers available for this order."));
          return;
        }
        const items = promos.map(p => {
          const discountDesc = p.discountType === "PERCENT"
            ? `${p.discountValue}% off${p.maxDiscount ? " (max " + formatMoney(p.maxDiscount) + ")" : ""}`
            : `${formatMoney(p.discountValue)} off`;

          const row = createEl("div", "voucher-list-item d-flex justify-content-between align-items-center p-2 border-bottom border-secondary");
          const infoBox = createEl("div");
          const badge = createEl("span", "badge bg-danger fs-6", p.voucherCode || "");
          const nameSpan = createEl("span", "ms-2 text-white small fw-semibold", p.promotionName || "");
          const metaDiv = createEl("div", "text-white-50", `${discountDesc} · Min. order: ${formatMoney(p.minOrderAmount || 0)}`);
          metaDiv.style.fontSize = "12px";
          setChildren(infoBox, badge, nameSpan, metaDiv);

          const btn = createEl("button", "btn btn-sm btn-outline-success ms-2 btn-pick-voucher", "Use");
          btn.type = "button";
          if (typeof btn.setAttribute === "function") {
            btn.setAttribute("data-code", p.voucherCode || "");
          }
          btn.addEventListener("click", () => {
            const code = p.voucherCode || "";
            if (voucherInput) voucherInput.value = code;
            applyVoucher(code);
          });

          setChildren(row, infoBox, btn);
          return row;
        });
        setChildren(voucherListContainer, ...items);
      } catch (e) {
        setChildren(voucherListContainer, createEl("div", "text-danger small p-2", "Failed to load vouchers."));
      }
    });
  }

  // Copy helper
  function setupCopyBtn(btnId, getText) {
    const btn = doc.getElementById(btnId);
    if (!btn) return;
    btn.addEventListener("click", async () => {
      try {
        const text = getText();
        await navigator.clipboard.writeText(text);
        const originalText = btn.textContent;
        btn.textContent = "Copied!";
        btn.classList.add("btn-success");
        setTimeout(() => {
          btn.textContent = originalText;
          btn.classList.remove("btn-success");
        }, 1500);
      } catch (e) {
        console.warn("Clipboard write failed", e);
      }
    });
  }

  setupCopyBtn("btnCopyAccount", () => accountNoEl?.textContent?.trim() || "");
  setupCopyBtn("btnCopyAmount", () => {
    const raw = amountEl?.textContent?.replace(/[^0-9]/g, "") || "";
    return raw;
  });
  setupCopyBtn("btnCopyDesc", () => descEl?.textContent?.trim() || "");

  function startTimer(durationSeconds) {
    clearInterval(timerInterval);
    let remaining = durationSeconds;
    function tick() {
      if (remaining <= 0) {
        clearInterval(timerInterval);
        clearInterval(pollInterval);
        if (timerEl) timerEl.textContent = "00:00 (Expired)";
        return;
      }
      const m = Math.floor(remaining / 60);
      const s = remaining % 60;
      if (timerEl) {
        timerEl.textContent = `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
      }
      remaining--;
    }
    tick();
    timerInterval = setInterval(tick, 1000);
  }

  const STORAGE_KEY = "box_active_payos_payment";
  function savePendingSession(paymentId, bookingId, orderCode) {
    try {
      if (typeof sessionStorage !== "undefined" && paymentId) {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ paymentId, bookingId, orderCode, savedAt: Date.now() }));
      }
    } catch (_) {}
  }
  function loadPendingSession() {
    try {
      if (typeof sessionStorage === "undefined") return null;
      const raw = sessionStorage.getItem(STORAGE_KEY);
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      if (!parsed || !parsed.paymentId) return null;
      // Ignore stale sessions older than 20 minutes
      if (parsed.savedAt && Date.now() - Number(parsed.savedAt) > 20 * 60 * 1000) {
        sessionStorage.removeItem(STORAGE_KEY);
        return null;
      }
      return parsed;
    } catch (_) {
      return null;
    }
  }
  function clearPendingSession() {
    try {
      if (typeof sessionStorage !== "undefined") {
        sessionStorage.removeItem(STORAGE_KEY);
      }
    } catch (_) {}
  }

  // Resume pending PayOS session if returning from PayOS returnUrl / cancelUrl
  function tryResumePendingPaymentFromUrl() {
    if (typeof window === "undefined" || !window.location) return;
    const params = new URLSearchParams(window.location.search || "");
    const statusParam = (params.get("status") || "").toUpperCase();
    const cancelParam = (params.get("cancel") || "").toLowerCase();
    const orderCodeParam = params.get("orderCode");
    const saved = loadPendingSession();
    if (!saved) return;
    if (orderCodeParam && saved.orderCode && String(saved.orderCode) !== String(orderCodeParam)) return;
    if (!orderCodeParam && !statusParam && cancelParam !== "true") return;

    activePaymentId = saved.paymentId;
    activeBookingId = saved.bookingId || null;
    activeOrderCode = saved.orderCode || null;

    if (cancelParam === "true" || statusParam === "CANCELLED") {
      clearPendingSession();
      renderLoadingNotice(
        "alert-warning",
        "Payment Cancelled",
        "Your PayOS payment was cancelled. You may start a new checkout whenever you are ready."
      );
      return;
    }

    if (loadingEl) loadingEl.classList.remove("d-none");
    if (activeLayout) activeLayout.classList.add("d-none");
    if (successLayout) successLayout.classList.add("d-none");
    checkPaymentStatus();
    clearInterval(pollInterval);
    pollInterval = setInterval(checkPaymentStatus, 2000);
  }
  tryResumePendingPaymentFromUrl();

  async function checkPaymentStatus() {
    if (!activePaymentId) return;
    try {
      const res = await fetch(`/api/payments/${activePaymentId}/status`);
      if (!res.ok) return;
      const data = await res.json();
      const status = String(data.status || data.paymentStatus || "").toUpperCase();
      const bookingStatus = String(data.bookingStatus || "").toUpperCase();

      if (bookingStatus === "PAID_SEAT_CONFLICT") {
        clearInterval(pollInterval);
        clearInterval(timerInterval);
        clearPendingSession();
        const codeSuffix = data.bookingCode ? ` (Booking: ${data.bookingCode})` : "";
        renderLoadingNotice(
          "alert-warning",
          "Payment Received — Seat Conflict Detected",
          `Your seat hold expired before payment completed and the seats were taken by another order${codeSuffix}. No tickets were issued, and you are eligible for a full refund.`,
          { href: "/contact", label: "Request Full Refund (/contact)" }
        );
        return;
      }

      if (status === "REVIEW_REQUIRED" || bookingStatus === "PAYMENT_REVIEW") {
        clearInterval(pollInterval);
        clearInterval(timerInterval);
        clearPendingSession();
        const codeSuffix = data.bookingCode ? ` (Booking: ${data.bookingCode})` : "";
        renderLoadingNotice(
          "alert-warning",
          "Payment Under Verification",
          `We detected a payment discrepancy requiring manual verification${codeSuffix}. Please contact support if you need assistance.`,
          { href: "/contact", label: "Contact Support (/contact)" }
        );
        return;
      }

      if (data.expired === true || status === "EXPIRED" || status === "CANCELLED" || status === "FAILED") {
        clearInterval(pollInterval);
        clearInterval(timerInterval);
        clearPendingSession();
        renderLoadingNotice(
          "alert-danger",
          `Payment ${status || "EXPIRED"}`,
          "This payment session is no longer active. Please return and create a new booking to continue."
        );
        return;
      }

      if (bookingStatus === "CONFIRMED" || data.isPaid || data.paid || status === "PAID" || status === "COMPLETED") {
        clearInterval(pollInterval);
        clearInterval(timerInterval);
        clearPendingSession();
        showSuccess(data.bookingId || activeBookingId);
      }
    } catch (e) {
      console.warn("Error polling payment status", e);
    }
  }

  function showSuccess(bookingId) {
    if (loadingEl) loadingEl.classList.add("d-none");
    if (activeLayout) activeLayout.classList.add("d-none");
    if (successLayout) {
      successLayout.classList.remove("d-none");
      if (goToTicketBtn) {
        goToTicketBtn.href = `/booking/ticket/${bookingId}`;
      }
    }
  }

  return {
    getDiscountAmount() {
      return appliedDiscountAmount;
    },

    getVoucherCode() {
      return appliedVoucherCode;
    },

    async startCheckout(bookingPayload) {
      if (isStartingCheckout) return;
      isStartingCheckout = true;
      if (loadingEl) loadingEl.classList.remove("d-none");
      if (activeLayout) activeLayout.classList.add("d-none");
      if (successLayout) successLayout.classList.add("d-none");
      clearInterval(pollInterval);
      clearInterval(timerInterval);

      // Attach voucher code to booking payload if applied
      if (appliedVoucherCode) {
        bookingPayload.voucherCode = appliedVoucherCode;
      }

      try {
        // 1. Create Unified Booking (BR-01, BR-02)
        const bookingRes = await fetch("/api/bookings", {
          method: "POST",
          headers: { "Content-Type": "application/json", ...getCsrfHeaders() },
          credentials: "same-origin",
          body: JSON.stringify(bookingPayload)
        });

        if (!bookingRes.ok) {
          const errBody = await bookingRes.json().catch(() => ({}));
          throw new Error(errBody.message || "Failed to create booking");
        }

        const bookingData = await bookingRes.json();
        activeBookingId = bookingData.bookingId;

        // 2. Create PayOS Payment (BR-03, BR-04)
        const payRes = await fetch("/api/payments/payos/create", {
          method: "POST",
          headers: { "Content-Type": "application/json", ...getCsrfHeaders() },
          credentials: "same-origin",
          body: JSON.stringify({ bookingId: activeBookingId })
        });

        if (!payRes.ok) {
          const errBody = await payRes.json().catch(() => ({}));
          throw new Error(errBody.message || "Failed to initialize PayOS payment");
        }

        const payData = await payRes.json();
        if (!payData || !payData.qrCode) {
          throw new Error("Payment QR code is unavailable. Please try again.");
        }
        activePaymentId = payData.paymentId;
        activeOrderCode = payData.orderCode;
        savePendingSession(activePaymentId, activeBookingId, activeOrderCode);

        // 3. Render QR directly on page without redirect (BR-04)
        const qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=240x240&data=" + encodeURIComponent(payData.qrCode);
        if (qrImage) qrImage.src = qrUrl;

        if (bankNameEl) bankNameEl.textContent = payData.bankName || "MBBank";
        if (accountNoEl) accountNoEl.textContent = payData.accountNumber || "---";
        if (accountNameEl) accountNameEl.textContent = payData.accountName || "BOX CINEMA";
        if (amountEl) {
          amountEl.textContent = Number(payData.amount || 0).toLocaleString("en-US") + " ₫";
        }
        if (descEl) descEl.textContent = payData.description || "---";

        if (loadingEl) loadingEl.classList.add("d-none");
        if (activeLayout) activeLayout.classList.remove("d-none");

        // 4. Start countdown & status polling (BR-05)
        startTimer(600); // 10 minutes
        pollInterval = setInterval(checkPaymentStatus, 2000);

      } catch (err) {
        renderLoadingNotice(
          "alert-danger",
          "Payment Initialization Failed",
          err?.message || "Unable to initialize payment."
        );
      } finally {
        isStartingCheckout = false;
      }
    },

    reset() {
      clearInterval(pollInterval);
      clearInterval(timerInterval);
      clearPendingSession();
      activePaymentId = null;
      activeBookingId = null;
      activeOrderCode = null;
      clearVoucher();
      if (loadingEl) loadingEl.classList.remove("d-none");
      if (activeLayout) activeLayout.classList.add("d-none");
      if (successLayout) successLayout.classList.add("d-none");
    }
  };
}
