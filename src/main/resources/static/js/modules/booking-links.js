/** Keep the hero booking link in sync with the movie selected by its carousel. */
export function initBookingLinks(doc = document) {
  const heroBuy = doc.getElementById("heroBuyBtn");
  const heroDetail = doc.getElementById("heroDetailBtn");
  if (heroBuy && heroDetail) {
    const syncDetails = () => { heroDetail.href = `/movies/${encodeURIComponent(heroBuy.dataset.bookId)}`; };
    syncDetails();
    new doc.defaultView.MutationObserver(syncDetails).observe(heroBuy, { attributes: true, attributeFilter: ["data-book-id"] });
  }
  doc.addEventListener("click", event => {
    const link = event.target.closest("a.btn-cta-buy[data-book-id], a.btn-pop-buy[data-book-id]");
    if (!link) return;
    link.href = `/booking/buy-tickets?movieId=${encodeURIComponent(link.dataset.bookId)}`;
  });
}

if (typeof document !== "undefined") initBookingLinks(document);
