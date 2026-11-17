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

  // Showtimes Filter Controls
  const genreSelect = doc.getElementById("showtimeFilterGenre") || doc.getElementById("filterGenre");
  const ageSelect = doc.getElementById("showtimeFilterAge") || doc.getElementById("filterAge");
  const formatSelect = doc.getElementById("showtimeFilterFormat") || doc.getElementById("filterFormat");
  const sortSelect = doc.getElementById("showtimeFilterSort") || doc.getElementById("filterSort");
  const resetBtn = doc.getElementById("btnResetShowtimeFilters") || doc.getElementById("btnResetAllFilters");
  const searchInput = doc.getElementById("navSearchInput");

  if (!dateTabsContainer || !moviesGrid) return;

  let currentSearchQuery = (searchInput?.value || "").trim();

  // Dynamically populate genre options from cards if not already populated
  function syncGenreOptions(cards) {
    if (genreSelect && genreSelect.options.length <= 1) {
      const genreSet = new Set();
      cards.forEach((card) => {
        const gStr = card.dataset.genres || "";
        gStr.split("/").forEach((g) => {
          const trimmed = g.trim();
          if (trimmed) genreSet.add(trimmed);
        });
      });
      Array.from(genreSet).sort().forEach((genreName) => {
        const opt = doc.createElement("option");
        opt.value = genreName;
        opt.textContent = genreName;
        genreSelect.appendChild(opt);
      });
    }
  }

  // Dynamically populate format options from cards if not already populated
  function syncFormatOptions(cards) {
    if (formatSelect && formatSelect.options.length <= 1) {
      const formatSet = new Set();
      cards.forEach((card) => {
        const fStr = card.dataset.formats || "";
        if (fStr) {
          fStr.split(",").forEach((f) => {
            const trimmed = f.trim();
            if (trimmed) formatSet.add(trimmed);
          });
        }
      });
      Array.from(formatSet).sort().forEach((fmtName) => {
        const opt = doc.createElement("option");
        opt.value = fmtName;
        opt.textContent = fmtName;
        formatSelect.appendChild(opt);
      });
    }
  }

  function applyShowtimesFilter() {
    const q = (currentSearchQuery || searchInput?.value || "").trim().toLowerCase();
    const currentGenre = genreSelect ? genreSelect.value : "all";
    const currentAge = ageSelect ? ageSelect.value : "all";
    const currentFormat = formatSelect ? formatSelect.value : "all";
    const currentSort = sortSelect ? sortSelect.value : "default";

    const allCards = Array.from(moviesGrid.querySelectorAll(".showtime-movie-card"));

    // Ensure each card has origOrder
    allCards.forEach((card, idx) => {
      if (card.dataset.origOrder === undefined) {
        card.dataset.origOrder = idx;
      }
    });

    syncGenreOptions(allCards);
    syncFormatOptions(allCards);

    const matchedCards = [];

    allCards.forEach((card) => {
      // 1. Search Query Filter
      const title = (card.dataset.title || card.querySelector(".showtime-movie-title")?.textContent || "").toLowerCase();
      const meta = (card.querySelector(".showtime-movie-meta")?.textContent || "").toLowerCase();
      const cardGenres = (card.dataset.genres || "").toLowerCase();
      const matchesQuery = !q || title.includes(q) || meta.includes(q) || cardGenres.includes(q);

      // 2. Genre Filter
      const matchesGenre = (currentGenre === "all") || cardGenres.includes(currentGenre.toLowerCase());

      // 3. Age Rating Filter
      const cardAge = card.dataset.age || "";
      const matchesAge = (currentAge === "all") || (cardAge === currentAge);

      // 4. Room Format Filter
      let hasMatchingFormat = (currentFormat === "all");
      const formatBlocks = card.querySelectorAll(".showtime-format-block");
      formatBlocks.forEach((block) => {
        const blockTitle = (block.querySelector(".showtime-format-title")?.textContent || "").toLowerCase();
        let blockMatches = false;
        if (currentFormat === "all") {
          blockMatches = true;
        } else {
          const targetFmt = currentFormat.toLowerCase().trim();
          if (blockTitle.includes(targetFmt) || targetFmt.includes(blockTitle)) {
            blockMatches = true;
          } else {
            const keywords = ["imax", "4dx", "screenx", "gold", "3d", "2d", "dolby"];
            for (const kw of keywords) {
              if (targetFmt.includes(kw) && blockTitle.includes(kw)) {
                blockMatches = true;
                break;
              }
            }
          }
        }
        block.style.display = blockMatches ? "" : "none";
        if (blockMatches) hasMatchingFormat = true;
      });

      const matches = matchesQuery && matchesGenre && matchesAge && hasMatchingFormat;
      if (matches) {
        card.style.display = "";
        matchedCards.push(card);
      } else {
        card.style.display = "none";
      }
    });

    // 5. Sort matched cards
    matchedCards.sort((a, b) => {
      if (currentSort === "title-asc") {
        const tA = (a.dataset.title || a.querySelector(".showtime-movie-title")?.textContent || "").toLowerCase();
        const tB = (b.dataset.title || b.querySelector(".showtime-movie-title")?.textContent || "").toLowerCase();
        return tA.localeCompare(tB);
      } else if (currentSort === "duration-desc") {
        const dA = parseInt(a.dataset.duration, 10) || 0;
        const dB = parseInt(b.dataset.duration, 10) || 0;
        return dB - dA;
      } else if (currentSort === "duration-asc") {
        const dA = parseInt(a.dataset.duration, 10) || 0;
        const dB = parseInt(b.dataset.duration, 10) || 0;
        return dA - dB;
      } else {
        const oA = parseInt(a.dataset.origOrder, 10) || 0;
        const oB = parseInt(b.dataset.origOrder, 10) || 0;
        return oA - oB;
      }
    });

    // Re-order matched cards in DOM
    matchedCards.forEach((c) => moviesGrid.appendChild(c));

    // 6. Empty State Handling
    if (emptyState) {
      const emptyTitle = emptyState.querySelector(".showtimes-empty-title");
      const emptyDesc = emptyState.querySelector(".showtimes-empty-desc");
      if (allCards.length === 0) {
        if (emptyTitle) emptyTitle.textContent = "No Showtimes Available";
        if (emptyDesc) emptyDesc.textContent = "No showtimes found for this cinema on the selected date. Please choose another date or cinema.";
        emptyState.classList.remove("d-none");
      } else if (matchedCards.length === 0) {
        if (emptyTitle) emptyTitle.textContent = "No Matching Movies Found";
        if (emptyDesc) emptyDesc.textContent = "No movies or showtimes match your selected filter criteria. Try resetting the filters.";
        emptyState.classList.remove("d-none");
      } else {
        emptyState.classList.add("d-none");
      }
    }
  }

  // Filter Control Event Listeners
  [genreSelect, ageSelect, formatSelect, sortSelect].forEach((el) => {
    if (el) {
      el.addEventListener("change", applyShowtimesFilter);
    }
  });

  if (resetBtn) {
    resetBtn.addEventListener("click", () => {
      if (genreSelect) genreSelect.value = "all";
      if (ageSelect) ageSelect.value = "all";
      if (formatSelect) formatSelect.value = "all";
      if (sortSelect) sortSelect.value = "default";
      if (searchInput) searchInput.value = "";
      currentSearchQuery = "";
      applyShowtimesFilter();
    });
  }

  if (searchInput) {
    searchInput.addEventListener("input", (e) => {
      currentSearchQuery = e.target.value || "";
      applyShowtimesFilter();
    });
  }

  if (typeof window !== "undefined") {
    window.__boxCinemaShowtimes = {
      setSearchQuery: (query, scrollTo = true) => {
        currentSearchQuery = query || "";
        if (searchInput) searchInput.value = query || "";
        applyShowtimesFilter();
        if (scrollTo) {
          moviesGrid.scrollIntoView({ behavior: "smooth", block: "nearest" });
        }
      }
    };
  }

  // Apply filter on initial load
  applyShowtimesFilter();

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
  } catch (_) { }

  // 2. Cinema Switch Change
  if (cinemaSwitch) {
    cinemaSwitch.addEventListener("change", () => {
      const cinemaId = cinemaSwitch.value;
      const cinemaName = cinemaSwitch.options[cinemaSwitch.selectedIndex]?.text;
      try {
        localStorage.setItem("box_cinema_selected_branch_id", cinemaId);
        if (cinemaName) localStorage.setItem("box_cinema_selected_branch_name", cinemaName);
      } catch (_) { }

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

  function isSlotExpired(slotTimeStr, selectedDateStr) {
    if (!slotTimeStr) return false;
    const now = new Date();
    const todayStr = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, "0")}-${String(now.getDate()).padStart(2, "0")}`;
    if (!selectedDateStr || selectedDateStr === todayStr) {
      const parts = slotTimeStr.trim().split(":");
      if (parts.length >= 2) {
        const slotHour = parseInt(parts[0], 10);
        const slotMin = parseInt(parts[1], 10);
        const curHour = now.getHours();
        const curMin = now.getMinutes();
        if (slotHour < curHour || (slotHour === curHour && slotMin <= curMin)) {
          return true;
        }
      }
    } else if (selectedDateStr < todayStr) {
      return true;
    }
    return false;
  }

  function pruneExpiredSlots() {
    const activeDate = getActiveDate();
    let changed = false;
    moviesGrid.querySelectorAll(".showtime-slot-btn").forEach(btn => {
      const slotTime = btn.dataset.slotTime;
      if (isSlotExpired(slotTime, activeDate)) {
        btn.remove();
        changed = true;
      }
    });

    if (changed) {
      moviesGrid.querySelectorAll(".showtime-format-block").forEach(block => {
        const remaining = block.querySelectorAll(".showtime-slot-btn");
        if (remaining.length === 0) {
          block.remove();
        }
      });
      moviesGrid.querySelectorAll(".showtime-movie-card").forEach(card => {
        const remainingBlocks = card.querySelectorAll(".showtime-format-block");
        if (remainingBlocks.length === 0) {
          card.remove();
        }
      });
      applyShowtimesFilter();
    }
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
      applyShowtimesFilter();
      return;
    }

    const activeDate = getActiveDate();
    const visibleCards = [];

    movieGroups.forEach((movie, idx) => {
      const ageClass = movie.ageRating === 'P' ? 'badge-age-p' :
        movie.ageRating === 'T13' ? 'badge-age-t13' :
          movie.ageRating === 'T16' ? 'badge-age-t16' : 'badge-age-t18';

      // Render formats
      let formatsHtml = "";
      const formatNames = [];
      for (const [formatName, slots] of Object.entries(movie.formats || {})) {
        const validSlots = (slots || []).filter(slot => !isSlotExpired(slot.time, activeDate));
        if (validSlots.length === 0) continue;

        formatNames.push(formatName);

        const slotsHtml = validSlots.map(slot => {
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

      if (!formatsHtml) return;

      visibleCards.push(`
        <div class="showtime-movie-card"
             data-movie-id="${movie.movieId || ''}"
             data-title="${escapeHtml(movie.title)}"
             data-genres="${escapeHtml(movie.genreNamesDisplay || '')}"
             data-age="${movie.ageRating || ''}"
             data-duration="${movie.durationMinutes || 0}"
             data-formats="${escapeHtml(formatNames.join(', '))}"
             data-orig-order="${idx}">
          <div class="showtime-poster-col">
            <span class="showtime-age-badge ${ageClass}">${movie.ageRating}</span>
            <img src="${movie.posterUrl}" alt="${escapeHtml(movie.title)}" loading="lazy" onerror="this.onerror=null;this.src='/assets/img/crimson-horizon.webp'">
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
      `);
    });

    if (emptyState) emptyState.classList.add("d-none");
    moviesGrid.innerHTML = visibleCards.join("");
    applyShowtimesFilter();
  }

  function escapeHtml(str) {
    if (!str) return "";
    return str.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  // Selecting a screening opens seat selection directly.
  moviesGrid.addEventListener("click", event => {
    const slot = event.target.closest(".showtime-slot-btn");
    if (!slot?.dataset.showtimeId) return;
    if (isSlotExpired(slot.dataset.slotTime, getActiveDate())) {
      event.preventDefault();
      alert("This screening has started or ticket sales have closed. Please select another screening.");
      pruneExpiredSlots();
      return;
    }
    window.location.assign(`/booking/buy-tickets?showtimeId=${encodeURIComponent(slot.dataset.showtimeId)}`);
  });

  // Prune any expired slots on load and check periodically every 15s
  pruneExpiredSlots();
  setInterval(pruneExpiredSlots, 15000);

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
