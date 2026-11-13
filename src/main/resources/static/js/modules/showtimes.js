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

  // Selecting a screening opens seat selection directly.
  moviesGrid.addEventListener("click", event => {
    const slot = event.target.closest(".showtime-slot-btn");
    if (!slot?.dataset.showtimeId) return;
    window.location.assign(`/booking/buy-tickets?showtimeId=${encodeURIComponent(slot.dataset.showtimeId)}`);
  });

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
