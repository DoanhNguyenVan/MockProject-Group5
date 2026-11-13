// Payment selection only. A server-created order and payment session are required
// before either method can be confirmed or a payable QR code can be displayed.
export function initPayment(doc = document) {
  const panel = doc.getElementById("bookingPayment");
  const radios = [...panel.querySelectorAll('input[name="paymentMethod"]')];
  function render() {
    const method = radios.find(input => input.checked)?.value;
    panel.querySelectorAll("[data-payment-method]").forEach(section => {
      section.hidden = section.dataset.paymentMethod !== method;
    });
    doc.getElementById("bookingPaymentAction").textContent = method === "CASH"
      ? "Confirm cash payment" : "Generate VNPay QR";
  }
  radios.forEach(input => input.addEventListener("change", render));
  render();
  return {
    reset() {
      radios.forEach(input => { input.checked = input.value === "CASH"; });
      render();
    }
  };
}
