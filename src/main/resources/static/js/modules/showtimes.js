/**
 * BOX Cinema - Showtimes by Cinema & Date Interactive Module
 */

export function initShowtimesPage(doc = document) {
  const cinemaSwitch = doc.getElementById("showtimeCinemaSwitch");
  const dateTabsContainer = doc.getElementById("showtimeDateTabs");
  const moviesGrid = doc.getElementById("showtimeMoviesGrid");
  const emptyState = doc.getElementById("showtimeEmptyState");
  const trailerModal = doc.getElementById("trailerModal");
  const trailerIframe = doc.getElementById("trailerIframe");
  const trailerCloseBtn = doc.getElementById("trailerModalClose");

  if (!dateTabsContainer || !moviesGrid) return;

  let currentSearchQuery = (doc.getElementById("navSearchInput")?.value || "").trim();

  function applyShowtimeSearchFilter() {
    const q = currentSearchQuery.trim().toLowerCase();
    const cards = Array.from(moviesGrid.querySelectorAll(".showtime-movie-card"));
    let visibleCount = 0;

    cards.forEach((card) => {
      const title = (card.querySelector(".showtime-movie-title")?.textContent || "").toLowerCase();
      const meta = (card.querySelector(".showtime-movie-meta")?.textContent || "").toLowerCase();
      const matches = !q || title.includes(q) || meta.includes(q);
      card.style.display = matches ? "" : "none";
      if (matches) visibleCount++;
    });

    if (emptyState) {
      const emptyTitle = emptyState.querySelector(".showtimes-empty-title");
      const emptyDesc = emptyState.querySelector(".showtimes-empty-desc");
      if (cards.length === 0) {
        if (emptyTitle) emptyTitle.textContent = "No Showtimes Available";
        if (emptyDesc) emptyDesc.textContent = "No showtimes found for this cinema on the selected date. Please choose another date or cinema.";
        emptyState.classList.remove("d-none");
      } else if (visibleCount === 0) {
        if (emptyTitle) emptyTitle.textContent = "No Matching Movies Found";
        if (emptyDesc) emptyDesc.textContent = `No showtimes match "${currentSearchQuery.trim()}" at this cinema on the selected date.`;
        emptyState.classList.remove("d-none");
      } else {
        emptyState.classList.add("d-none");
      }
    }
  }

  if (typeof window !== "undefined") {
    window.__boxCinemaShowtimes = {
      setSearchQuery: (query, scrollTo = true) => {
        currentSearchQuery = query || "";
        applyShowtimeSearchFilter();
        if (scrollTo) {
          moviesGrid.scrollIntoView({ behavior: "smooth", block: "nearest" });
        }
      }
    };
  }

  // Apply filter on initial load if search input already has text
  if (currentSearchQuery) {
    applyShowtimeSearchFilter();
  }

  // 1. Initial Cinema Sync with localStorage
  try {
    const savedCinemaId = localStorage.getItem("box_cinema_selected_branch_id");
    if (savedCinemaId && savedCinemaId !== "all" && cinemaSwitch) {
      if (cinemaSwitch.value !== savedCinemaId) {
        // If query string didn't explicitly override it, sync to localStorage
        const urlParams = new URLSearchParams(window.location.search);
        if (!urlParams.has("cinemaId")) {
          cinemaSwitch.value = savedCinemaId;
          const currentDate = getActiveDate();
          loadSchedule(savedCinemaId, currentDate);
        }
      }
    }
  } catch (_) {}

  // 2. Cinema Switch Change
  if (cinemaSwitch) {
    cinemaSwitch.addEventListener("change", () => {
      const cinemaId = cinemaSwitch.value;
      const cinemaName = cinemaSwitch.options[cinemaSwitch.selectedIndex]?.text;
      try {
        localStorage.setItem("box_cinema_selected_branch_id", cinemaId);
        if (cinemaName) localStorage.setItem("box_cinema_selected_branch_name", cinemaName);
      } catch (_) {}

      // Update URL query without full reload
      const url = new URL(window.location);
      url.searchParams.set("cinemaId", cinemaId);
      window.history.replaceState({}, "", url);

      const activeDate = getActiveDate();
      loadSchedule(cinemaId, activeDate);
    });
  }

  // Sync with global cinema selection event (from Navbar or Welcome Popup)
  doc.addEventListener("box:cinema-selected", (e) => {
    const cinemaId = e.detail?.cinemaId;
    if (cinemaId && cinemaId !== "all" && cinemaSwitch) {
      cinemaSwitch.value = cinemaId;
      const activeDate = getActiveDate();
      loadSchedule(cinemaId, activeDate);
    }
  });

  // 3. Date Tab Click
  dateTabsContainer.addEventListener("click", (e) => {
    const tab = e.target.closest(".showtime-date-tab");
    if (!tab) return;

    const targetDate = tab.dataset.date;
    if (!targetDate) return;

    // Update active class
    dateTabsContainer.querySelectorAll(".showtime-date-tab").forEach(t => t.classList.remove("active"));
    tab.classList.add("active");

    const cinemaId = cinemaSwitch ? cinemaSwitch.value : "";

    // Update URL query
    const url = new URL(window.location);
    url.searchParams.set("date", targetDate);
    window.history.replaceState({}, "", url);

    loadSchedule(cinemaId, targetDate);
  });

  function getActiveDate() {
    const activeTab = dateTabsContainer.querySelector(".showtime-date-tab.active");
    return activeTab ? activeTab.dataset.date : "";
  }

  // 4. Fetch & Render Schedule
  async function loadSchedule(cinemaId, date) {
    moviesGrid.style.opacity = "0.5";
    moviesGrid.style.pointerEvents = "none";

    try {
      const res = await fetch(`/api/quick-booking/schedule?cinemaId=${cinemaId || ""}&date=${date || ""}`);
      if (!res.ok) throw new Error("Failed to load showtimes");
      const data = await res.json();

      renderMovies(data.movieGroups || []);
    } catch (err) {
      console.error(err);
    } finally {
      moviesGrid.style.opacity = "1";
      moviesGrid.style.pointerEvents = "auto";
    }
  }

  function renderMovies(movieGroups) {
    if (!movieGroups || movieGroups.length === 0) {
      moviesGrid.innerHTML = "";
      applyShowtimeSearchFilter();
      return;
    }

    if (emptyState) emptyState.classList.add("d-none");

    const html = movieGroups.map(movie => {
      const ageClass = movie.ageRating === 'P' ? 'badge-age-p' :
                       movie.ageRating === 'T13' ? 'badge-age-t13' :
                       movie.ageRating === 'T16' ? 'badge-age-t16' : 'badge-age-t18';

      // Render formats
      let formatsHtml = "";
      for (const [formatName, slots] of Object.entries(movie.formats || {})) {
        const slotsHtml = slots.map(slot => {
          const lateClass = slot.late ? "slot-late" : "";
          const dateSubHtml = slot.late ? `<span class="slot-date-sub">${slot.dateSub}</span>` : "";

          return `
            <button type="button" class="showtime-slot-btn ${lateClass}"
                    data-showtime-id="${slot.showtimeId}"
                    data-movie-title="${escapeHtml(movie.title)}"
                    data-slot-time="${slot.time}"
                    data-slot-price="${slot.price}">
              <span class="slot-time">${slot.time}</span>
              ${dateSubHtml}
              <span class="slot-seats">${slot.availableSeats} seats available</span>
            </button>
          `;
        }).join("");

        formatsHtml += `
          <div class="showtime-format-block">
            <h5 class="showtime-format-title">${escapeHtml(formatName)}</h5>
            <div class="showtime-slots-wrap">${slotsHtml}</div>
          </div>
        `;
      }

      return `
        <div class="showtime-movie-card">
          <div class="showtime-poster-col">
            <span class="showtime-age-badge ${ageClass}">${movie.ageRating}</span>
            <img src="${movie.posterUrl}" alt="${escapeHtml(movie.title)}" loading="lazy" onerror="this.style.display='none'">
            ${movie.trailerUrl ? `
              <button type="button" class="poster-trailer-overlay" data-trailer-url="${movie.trailerUrl}" aria-label="Watch trailer">
                <span class="trailer-play-icon">
                  <svg viewBox="0 0 24 24" width="20" height="20"><polygon points="6 3 20 12 6 21 6 3"></polygon></svg>
                </span>
              </button>
            ` : ""}
          </div>
          <div class="showtime-details-col">
            <h4 class="showtime-movie-title">${escapeHtml(movie.title)}</h4>
            <div class="showtime-movie-meta">
              <span class="meta-item">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M20.59 13.41l-7.17 7.17a2 2 0 0 1-2.83 0L2 12V2h10l8.59 8.59a2 2 0 0 1 0 2.82z"></path><line x1="7" y1="7" x2="7.01" y2="7"></line></svg>
                ${escapeHtml(movie.genreNamesDisplay || "Movies")}
              </span>
              <span class="meta-item">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"></circle><polyline points="12 6 12 12 16 14"></polyline></svg>
                ${movie.durationMinutes} mins
              </span>
            </div>
            ${formatsHtml}
          </div>
        </div>
      `;
    }).join("");

    moviesGrid.innerHTML = html;
    applyShowtimeSearchFilter();
  }

  function escapeHtml(str) {
    if (!str) return "";
    return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  // 5. Showtime Slot Click Booking Action -> Open Confirmation Modal (Beta Cinema Style)
  const confirmModal = doc.getElementById("bookingConfirmModal");
  const confirmCloseBtn = doc.getElementById("bookingConfirmClose");
  const confirmAgreeBtn = doc.getElementById("btnConfirmAgree");
  const confirmMovieTitle = doc.getElementById("confirmMovieTitle");
  const confirmCinemaName = doc.getElementById("confirmCinemaName");
  const confirmDate = doc.getElementById("confirmDate");
  const confirmTime = doc.getElementById("confirmTime");
  let currentSelectedShowtimeId = null;

  function openConfirmModal(movieTitle, cinemaName, formattedDate, slotTime, showtimeId) {
    if (!confirmModal) return;
    currentSelectedShowtimeId = showtimeId || null;
    if (confirmMovieTitle) confirmMovieTitle.textContent = movieTitle;
    if (confirmCinemaName) confirmCinemaName.textContent = cinemaName;
    if (confirmDate) confirmDate.textContent = formattedDate;
    if (confirmTime) confirmTime.textContent = slotTime;

    confirmModal.classList.add("show");
    confirmModal.setAttribute("aria-hidden", "false");
    document.body.style.overflow = "hidden";
  }

  function closeConfirmModal() {
    if (!confirmModal) return;
    confirmModal.classList.remove("show");
    confirmModal.setAttribute("aria-hidden", "true");
    document.body.style.overflow = "";
  }

  function formatDateDisplay(dateStr) {
    if (!dateStr) return "";
    const parts = dateStr.split("-");
    if (parts.length === 3) {
      return `${parts[2]}/${parts[1]}/${parts[0]}`;
    }
    return dateStr;
  }

  moviesGrid.addEventListener("click", (e) => {
    const btn = e.target.closest(".showtime-slot-btn");
    if (btn) {
      const showtimeId = btn.dataset.showtimeId;
      const movieTitle = btn.dataset.movieTitle;
      const slotTime = btn.dataset.slotTime;
      const cinemaName = cinemaSwitch ? cinemaSwitch.options[cinemaSwitch.selectedIndex]?.text : "BOX Cinema";
      const rawDate = getActiveDate();
      const formattedDate = formatDateDisplay(rawDate);

      openConfirmModal(movieTitle, cinemaName, formattedDate, slotTime, showtimeId);
    }
  });

  if (confirmCloseBtn) {
    confirmCloseBtn.addEventListener("click", closeConfirmModal);
  }

  if (confirmModal) {
    confirmModal.addEventListener("click", (e) => {
      if (e.target === confirmModal) closeConfirmModal();
    });
  }

  doc.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && confirmModal && confirmModal.classList.contains("show")) {
      closeConfirmModal();
    }
  });

  if (confirmAgreeBtn) {
    confirmAgreeBtn.addEventListener("click", () => {
      const mTitle = confirmMovieTitle ? confirmMovieTitle.textContent : "";
      const cName = confirmCinemaName ? confirmCinemaName.textContent : "";
      const dVal = confirmDate ? confirmDate.textContent : "";
      const tVal = confirmTime ? confirmTime.textContent : "";
      const sId = currentSelectedShowtimeId || "";

      // Check if user is signed in via presence of .nav-user-pill in navbar
      const isLoggedIn = Boolean(doc.querySelector(".nav-user-pill"));

      if (!isLoggedIn) {
        // Save pending booking details so user can continue seamlessly after login
        try {
          sessionStorage.setItem("boxcinema_pending_booking", JSON.stringify({
            showtimeId: sId,
            movieTitle: mTitle,
            cinemaName: cName,
            date: dVal,
            time: tVal
          }));
        } catch (_) {}

        closeConfirmModal();
        const movieParam = encodeURIComponent(mTitle);
        window.location.href = `/login?booking=true&movie=${movieParam}`;
        return;
      }

      closeConfirmModal();
      
      // If signed in, proceed to seat selection feedback toast
      showAgreeToast(mTitle, cName, dVal, tVal);
    });
  }

  function showAgreeToast(mTitle, cName, dVal, tVal) {
    let toast = doc.getElementById("showtimeBookingToast");
    if (!toast) {
      toast = doc.createElement("div");
      toast.id = "showtimeBookingToast";
      toast.style.cssText = `
        position: fixed;
        bottom: 2rem;
        right: 2rem;
        background: #14151b;
        border: 1px solid #e50914;
        color: #fff;
        padding: 1.25rem 1.5rem;
        border-radius: 12px;
        box-shadow: 0 10px 35px rgba(0,0,0,0.9), 0 0 25px rgba(229, 9, 20, 0.25);
        z-index: 9999;
        max-width: 380px;
        transform: translateY(100px);
        opacity: 0;
        transition: transform 0.3s cubic-bezier(0.16, 1, 0.3, 1), opacity 0.3s ease;
      `;
      doc.body.appendChild(toast);
    }

    toast.innerHTML = `
      <div style="display:flex; justify-content:space-between; align-items:flex-start; margin-bottom:0.5rem;">
        <strong style="color:#e50914; text-transform:uppercase; font-size:0.85rem; letter-spacing:0.04em;">Selected Showtime</strong>
        <button type="button" style="background:none;border:none;color:#94a3b8;cursor:pointer;font-size:1.2rem;line-height:1;" onclick="this.closest('#showtimeBookingToast').style.opacity='0';this.closest('#showtimeBookingToast').style.transform='translateY(100px)';">×</button>
      </div>
      <div style="font-weight:700; font-size:1rem; margin-bottom:0.25rem; color:#fff;">${escapeHtml(mTitle)}</div>
      <div style="font-size:0.85rem; color:#cbd5e1;">
        <span>🕒 <strong>${escapeHtml(tVal)}</strong> · ${escapeHtml(dVal)}</span><br>
        <span>📍 ${escapeHtml(cName)}</span>
      </div>
    `;

    toast.style.opacity = "1";
    toast.style.transform = "translateY(0)";

    setTimeout(() => {
      if (toast) {
        toast.style.opacity = "0";
        toast.style.transform = "translateY(100px)";
      }
    }, 6000);
  }

  // 6. Trailer Modal Interaction
  function toEmbedUrl(url) {
    if (!url) return "";
    let videoId = "";
    if (url.includes("youtu.be/")) {
      videoId = url.split("youtu.be/")[1].split("?")[0];
    } else if (url.includes("watch?v=")) {
      videoId = url.split("watch?v=")[1].split("&")[0];
    }
    return videoId ? `https://www.youtube.com/embed/${videoId}?autoplay=1` : url;
  }

  doc.addEventListener("click", (e) => {
    const trailerBtn = e.target.closest("[data-trailer-url]");
    if (trailerBtn && trailerModal && trailerIframe) {
      e.preventDefault();
      trailerIframe.src = toEmbedUrl(trailerBtn.dataset.trailerUrl);
      trailerModal.classList.add("show");
    }
  });

  if (trailerCloseBtn && trailerModal && trailerIframe) {
    trailerCloseBtn.addEventListener("click", () => {
      trailerModal.classList.remove("show");
      trailerIframe.src = "";
    });
    trailerModal.addEventListener("click", (e) => {
      if (e.target === trailerModal) {
        trailerModal.classList.remove("show");
        trailerIframe.src = "";
      }
    });
  }
}

if (typeof document !== "undefined") {
  document.addEventListener("DOMContentLoaded", () => {
    initShowtimesPage(document);
  });
}
