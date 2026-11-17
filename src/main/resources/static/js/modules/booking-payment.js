// BOX Cinema - PayOS Integrated Payment Module (V2.1 Specification)
// Complies with BR-03 (One PayOS payment), BR-04 (No redirect, in-page QR), BR-05 (Webhook/Status polling)

export function initPayment(doc = document) {
  let activePaymentId = null;
  let activeBookingId = null;
  let activeOrderCode = null;
  let pollInterval = null;
  let timerInterval = null;

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
  const simulateBtn = doc.getElementById("btnSimulatePayment");
  const goToTicketBtn = doc.getElementById("btnGoToTicket");

  // Copy helper
  function setupCopyBtn(btnId, getText) {
    const btn = doc.getElementById(btnId);
    if (!btn) return;
    btn.addEventListener("click", async () => {
      try {
        const text = getText();
        await navigator.clipboard.writeText(text);
        const originalText = btn.textContent;
        btn.textContent = "Đã chép!";
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

  if (simulateBtn) {
    simulateBtn.addEventListener("click", async () => {
      if (!activeOrderCode) return;
      simulateBtn.disabled = true;
      simulateBtn.textContent = "Đang xử lý...";
      try {
        const res = await fetch(`/api/payments/payos/simulate-payment/${activeOrderCode}`, { method: "POST" });
        if (res.ok) {
          checkPaymentStatus();
        } else {
          alert("Mô phỏng không thành công");
        }
      } catch (err) {
        alert("Lỗi: " + err.message);
      } finally {
        simulateBtn.disabled = false;
        simulateBtn.textContent = "⚡ Mô phỏng PayOS Thành Công";
      }
    });
  }

  function startTimer(durationSeconds) {
    clearInterval(timerInterval);
    let remaining = durationSeconds;
    function tick() {
      if (remaining <= 0) {
        clearInterval(timerInterval);
        clearInterval(pollInterval);
        if (timerEl) timerEl.textContent = "00:00 (Hết hạn)";
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

  async function checkPaymentStatus() {
    if (!activePaymentId) return;
    try {
      const res = await fetch(`/api/payments/${activePaymentId}/status`);
      if (!res.ok) return;
      const data = await res.json();
      if (data.isPaid || data.paid || data.status === "PAID" || data.paymentStatus === "COMPLETED" || data.bookingStatus === "CONFIRMED") {
        clearInterval(pollInterval);
        clearInterval(timerInterval);
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
    async startCheckout(bookingPayload) {
      if (loadingEl) loadingEl.classList.remove("d-none");
      if (activeLayout) activeLayout.classList.add("d-none");
      if (successLayout) successLayout.classList.add("d-none");
      clearInterval(pollInterval);
      clearInterval(timerInterval);

      try {
        // 1. Create Unified Booking (BR-01, BR-02)
        const bookingRes = await fetch("/api/bookings", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(bookingPayload)
        });

        if (!bookingRes.ok) {
          const errBody = await bookingRes.json().catch(() => ({}));
          throw new Error(errBody.message || "Không thể tạo đơn đặt vé");
        }

        const bookingData = await bookingRes.json();
        activeBookingId = bookingData.bookingId;

        // 2. Create PayOS Payment (BR-03, BR-04)
        const payRes = await fetch("/api/payments/payos/create", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ bookingId: activeBookingId })
        });

        if (!payRes.ok) {
          const errBody = await payRes.json().catch(() => ({}));
          throw new Error(errBody.message || "Không thể khởi tạo thanh toán PayOS");
        }

        const payData = await payRes.json();
        activePaymentId = payData.paymentId;
        activeOrderCode = payData.orderCode;

        // 3. Render QR directly on page without redirect (BR-04)
        const qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=240x240&data=" + encodeURIComponent(payData.qrCode);
        if (qrImage) qrImage.src = qrUrl;

        if (bankNameEl) bankNameEl.textContent = payData.bankName || "MBBank (Ngân hàng Quân Đội)";
        if (accountNoEl) accountNoEl.textContent = payData.accountNumber || "---";
        if (accountNameEl) accountNameEl.textContent = payData.accountName || "BOX CINEMA";
        if (amountEl) {
          amountEl.textContent = Number(payData.amount || 0).toLocaleString("vi-VN") + " đ";
        }
        if (descEl) descEl.textContent = payData.description || "---";

        if (loadingEl) loadingEl.classList.add("d-none");
        if (activeLayout) activeLayout.classList.remove("d-none");

        // 4. Start countdown & status polling (BR-05)
        startTimer(600); // 10 minutes
        pollInterval = setInterval(checkPaymentStatus, 2000);

      } catch (err) {
        if (loadingEl) {
          loadingEl.innerHTML = `
            <div class="alert alert-danger">
              <h5 class="fw-bold">Khởi tạo thanh toán thất bại</h5>
              <p class="mb-0">${err.message}</p>
            </div>
          `;
        }
      }
    },

    reset() {
      clearInterval(pollInterval);
      clearInterval(timerInterval);
      activePaymentId = null;
      activeBookingId = null;
      activeOrderCode = null;
      if (loadingEl) loadingEl.classList.remove("d-none");
      if (activeLayout) activeLayout.classList.add("d-none");
      if (successLayout) successLayout.classList.add("d-none");
    }
  };
}
