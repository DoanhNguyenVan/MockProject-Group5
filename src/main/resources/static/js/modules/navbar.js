/**
 * BOX Cinema - Module 01 Navbar & Cinemê-style Home Page Interactions
 */

export function resolveActiveRoute(pathname = "/") {
  const cleanPath = pathname.replace(/\/+$/, "") || "/";
  if (cleanPath === "/" || cleanPath === "/home" || cleanPath.endsWith("home.html")) {
    return "home";
  }
  if (cleanPath.startsWith("/about") || cleanPath.endsWith("about.html")) {
    return "about";
  }
  if (cleanPath.startsWith("/contact") || cleanPath.endsWith("contact.html")) {
    return "contact";
  }
  if (cleanPath.startsWith("/login") || cleanPath.endsWith("login.html")) {
    return "login";
  }
  if (cleanPath === "/signup" || cleanPath.startsWith("/register") || cleanPath.endsWith("register.html")) {
    return "register";
  }
  return "home";
}

export function initNavbar(doc = document, currentPath = window.location.pathname) {
  const activeKey = resolveActiveRoute(currentPath);
  const navLinks = doc.querySelectorAll("[data-nav-route]");

  navLinks.forEach((link) => {
    const routeKey = link.getAttribute("data-nav-route");
    if (routeKey === activeKey) {
      link.classList.add("active");
      link.setAttribute("aria-current", "page");
    } else {
      link.classList.remove("active");
      link.removeAttribute("aria-current");
    }
  });

  const toggler = doc.querySelector(".box-navbar-toggler");
  const collapseEl = doc.getElementById("boxNavbarCollapse");
  const navEl = doc.querySelector(".cinema-nav");

  if (toggler && collapseEl) {
    const closeMenu = () => {
      collapseEl.classList.remove("show");
      toggler.setAttribute("aria-expanded", "false");
      if (navEl) navEl.classList.remove("menu-open");
    };
    toggler.addEventListener("click", () => {
      const isOpen = collapseEl.classList.toggle("show");
      toggler.setAttribute("aria-expanded", String(isOpen));
      if (navEl) navEl.classList.toggle("menu-open", isOpen);
    });
    collapseEl.addEventListener("click", (event) => {
      if (event.target.closest("a")) closeMenu();
    });
    doc.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && collapseEl.classList.contains("show")) {
        closeMenu();
        toggler.focus();
      }
    });
  }

  // Navbar scroll behavior: Transparent over hero slide, solid dark glass once scrolled past
  if (navEl && typeof window !== "undefined") {
    const heroEl = doc.getElementById("heroSpotlight") || doc.querySelector(".hero-spotlight");

    const handleScroll = () => {
      // On mobile / small screens, activate solid dark glass background immediately when user scrolls past top (> 25px)
      // so buttons and text scrolling up never collide with the fixed transparent navbar logo.
      // On desktop, activate as soon as scroll leaves the top of the hero slide (> 80px).
      const isMobile = window.innerWidth <= 768;
      const threshold = isMobile ? 25 : 80;
      if (window.scrollY > threshold) {
        navEl.classList.add("nav-scrolled");
      } else {
        navEl.classList.remove("nav-scrolled");
      }
    };

    window.addEventListener("scroll", handleScroll, { passive: true });
    // Check initial scroll state
    handleScroll();
  }

  // Expanding Search Bar Interactions
  initNavSearch(doc, navEl);

  return activeKey;
}

/**
 * Expanding Search Bar in Navbar (White icon in black circle -> expands to white search input)
 */
function initNavSearch(doc, navEl) {
  const searchWrap = doc.getElementById("navSearchWrap");
  const searchForm = doc.getElementById("navSearchForm");
  const searchBtn = doc.getElementById("navSearchBtn");
  const searchInput = doc.getElementById("navSearchInput");

  if (!searchWrap || !searchForm || !searchBtn || !searchInput) return;

  const openSearch = () => {
    searchForm.classList.add("active");
    if (navEl) navEl.classList.add("search-active");
    setTimeout(() => searchInput.focus(), 80);
  };

  const closeSearch = () => {
    searchForm.classList.remove("active");
    if (navEl) navEl.classList.remove("search-active");
    searchInput.blur();
  };

  searchBtn.addEventListener("click", (e) => {
    e.stopPropagation();
    if (!searchForm.classList.contains("active")) {
      openSearch();
    } else {
      if (searchInput.value.trim().length > 0) {
        performSearch(searchInput.value.trim(), true);
      } else {
        closeSearch();
      }
    }
  });

  const performSearch = (query, scrollTo = true) => {
    if (typeof window !== "undefined" && window.__boxCinemaCatalog?.setSearchQuery) {
      window.__boxCinemaCatalog.setSearchQuery(query, scrollTo);
      return;
    }

    const q = query.toLowerCase();
    const catalogCards = doc.querySelectorAll("#catalogGrid .catalog-card");
    catalogCards.forEach((card) => {
      const title = (card.querySelector(".catalog-movie-title")?.textContent || "").toLowerCase();
      const meta = (card.querySelector(".catalog-movie-meta")?.textContent || "").toLowerCase();
      const matches = !q || title.includes(q) || meta.includes(q);
      card.style.display = matches ? "" : "none";
    });

    if (scrollTo) {
      const targetSection = doc.getElementById("moviesSection");
      if (targetSection) {
        targetSection.scrollIntoView({ behavior: "smooth" });
      }
    }
  };

  searchInput.addEventListener("input", () => {
    performSearch(searchInput.value.trim(), false);
  });

  searchInput.addEventListener("keydown", (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      performSearch(searchInput.value.trim(), true);
    } else if (e.key === "Escape") {
      closeSearch();
    }
  });

  searchForm.addEventListener("click", (e) => {
    e.stopPropagation();
  });

  doc.addEventListener("click", (e) => {
    if (searchForm.classList.contains("active") && !searchWrap.contains(e.target)) {
      closeSearch();
    }
  });

  doc.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && searchForm.classList.contains("active")) {
      closeSearch();
    }
  });
}

/**
 * Hero Spotlight Slider: Prev/Next Slide Navigation & Auto-play (Cinemê style)
 */
function initHeroRail(doc) {
  const heroSpotlight = doc.getElementById("heroSpotlight");
  const heroBackdrop = doc.getElementById("heroBackdrop");
  const heroTitle = doc.getElementById("heroTitle");
  const heroSynopsis = doc.getElementById("heroSynopsis");
  const heroAge = doc.getElementById("heroAge");
  const heroDuration = doc.getElementById("heroDuration");
  const heroGenres = doc.getElementById("heroGenres");
  const heroLang = doc.getElementById("heroLang");
  const heroBuyBtn = doc.getElementById("heroBuyBtn");
  const heroTrailerBtn = doc.getElementById("heroTrailerBtn");
  const hotCards = doc.querySelectorAll(".hot-mini-card");
  const rail = doc.getElementById("hotRail");
  const prevBtn = doc.getElementById("heroSlidePrev");
  const nextBtn = doc.getElementById("heroSlideNext");

  if (!hotCards.length) return;

  let currentIndex = 0;
  let autoTimer = null;
  const AUTO_INTERVAL = 4000;

  function updateHeroDisplay(card) {
    if (!card) return;
    const title = card.dataset.title;
    const synopsis = card.dataset.synopsis;
    const age = card.dataset.age;
    const duration = card.dataset.duration;
    const genres = card.dataset.genres;
    const lang = card.dataset.lang;
    const banner = card.dataset.banner;
    const trailer = card.dataset.trailer;
    const movieId = card.dataset.movieId;

    if (heroBackdrop && banner) {
      heroBackdrop.style.backgroundImage = `url(${banner})`;
    }
    if (heroTitle) heroTitle.textContent = title;
    if (heroSynopsis) heroSynopsis.textContent = synopsis;
    if (heroAge) {
      heroAge.textContent = age;
      heroAge.className = "meta-badge " + (
        age === "P" ? "badge-age-p" :
        age === "T13" ? "badge-age-t13" :
        age === "T16" ? "badge-age-t16" : "badge-age-t18"
      );
    }
    if (heroDuration) heroDuration.textContent = duration;
    if (heroGenres) heroGenres.textContent = genres;
    if (heroLang) {
      heroLang.textContent = lang || "";
      heroLang.style.display = lang ? "inline" : "none";
    }
    if (heroBuyBtn) {
      heroBuyBtn.dataset.bookId = movieId;
      heroBuyBtn.dataset.bookTitle = title;
    }
    if (heroTrailerBtn) {
      if (trailer) {
        heroTrailerBtn.dataset.trailerUrl = trailer;
        heroTrailerBtn.style.display = "inline-flex";
      } else {
        heroTrailerBtn.style.display = "none";
      }
    }
  }

  function goToSlide(index) {
    if (index >= hotCards.length) {
      currentIndex = 0;
    } else if (index < 0) {
      currentIndex = hotCards.length - 1;
    } else {
      currentIndex = index;
    }

    hotCards.forEach((c, idx) => {
      c.classList.toggle("active", idx === currentIndex);
    });

    const activeCard = hotCards[currentIndex];
    updateHeroDisplay(activeCard);

    // Smooth scroll the rail so active card is centered
    if (rail && activeCard) {
      const cardLeft = activeCard.offsetLeft;
      const cardWidth = activeCard.offsetWidth;
      const railWidth = rail.offsetWidth;
      rail.scrollTo({
        left: cardLeft - (railWidth / 2) + (cardWidth / 2),
        behavior: "smooth"
      });
    }
  }

  function startAutoPlay() {
    stopAutoPlay();
    autoTimer = setInterval(() => {
      goToSlide(currentIndex + 1);
    }, AUTO_INTERVAL);
  }

  function stopAutoPlay() {
    if (autoTimer) {
      clearInterval(autoTimer);
      autoTimer = null;
    }
  }

  function restartAutoPlay() {
    stopAutoPlay();
    startAutoPlay();
  }

  // Slide navigation arrows (Prev / Next)
  if (prevBtn) {
    prevBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      goToSlide(currentIndex - 1);
      restartAutoPlay();
    });
  }

  if (nextBtn) {
    nextBtn.addEventListener("click", (e) => {
      e.stopPropagation();
      goToSlide(currentIndex + 1);
      restartAutoPlay();
    });
  }

  // Touch swipe gestures for mobile phones & iPad / tablets
  if (heroSpotlight) {
    let touchStartX = 0;
    let touchStartY = 0;
    let touchEndX = 0;
    let touchEndY = 0;

    heroSpotlight.addEventListener("touchstart", (e) => {
      // Allow independent horizontal scrolling inside the hot movies rail
      if (e.target && e.target.closest("#hotRail")) {
        touchStartX = 0;
        touchEndX = 0;
        return;
      }
      if (e.touches && e.touches.length > 0) {
        touchStartX = e.touches[0].clientX;
        touchStartY = e.touches[0].clientY;
        touchEndX = touchStartX;
        touchEndY = touchStartY;
      }
    }, { passive: true });

    heroSpotlight.addEventListener("touchmove", (e) => {
      if (e.touches && e.touches.length > 0) {
        touchEndX = e.touches[0].clientX;
        touchEndY = e.touches[0].clientY;
      }
    }, { passive: true });

    heroSpotlight.addEventListener("touchend", () => {
      const diffX = touchEndX - touchStartX;
      const diffY = touchEndY - touchStartY;
      // Trigger if horizontal swipe is dominant and exceeds 40px threshold
      if (Math.abs(diffX) > 40 && Math.abs(diffX) > Math.abs(diffY)) {
        if (diffX < 0) {
          // Swiped left -> next slide
          goToSlide(currentIndex + 1);
        } else {
          // Swiped right -> previous slide
          goToSlide(currentIndex - 1);
        }
        restartAutoPlay();
      }
    }, { passive: true });
  }

  // Clicking any card directly
  hotCards.forEach((card, idx) => {
    card.addEventListener("click", () => {
      goToSlide(idx);
      restartAutoPlay();
    });
  });

  // Only pause auto-play when hovering directly over the card rail or action buttons
  if (rail) {
    rail.addEventListener("mouseenter", stopAutoPlay);
    rail.addEventListener("mouseleave", startAutoPlay);
  }

  const actionsEl = doc.querySelector(".hero-actions");
  if (actionsEl) {
    actionsEl.addEventListener("mouseenter", stopAutoPlay);
    actionsEl.addEventListener("mouseleave", startAutoPlay);
  }

  if (typeof doc.addEventListener !== "undefined") {
    doc.addEventListener("visibilitychange", () => {
      if (doc.hidden) stopAutoPlay();
      else startAutoPlay();
    });
  }

  // Start auto-play immediately
  startAutoPlay();
}

/**
 * Catalog Manager: Handles Tab filtering, Navbar Search, Empty state, and 12 movies/page Pagination.
 * Ensures Phim đang chiếu appear first, Phim sắp chiếu appear next.
 */
function initCatalogManager(doc) {
  const catalogGrid = doc.getElementById("catalogGrid");
  if (!catalogGrid) return;

  const cards = Array.from(catalogGrid.querySelectorAll(".catalog-card"));
  const tabs = doc.querySelectorAll("[data-catalog-tab]");
  const paginationWrap = doc.getElementById("catalogPagination");
  const paginationNumbers = doc.getElementById("paginationNumbers");
  const prevBtn = doc.getElementById("paginationPrev");
  const nextBtn = doc.getElementById("paginationNext");
  const emptyState = doc.getElementById("catalogEmptyState");
  const resetBtn = doc.getElementById("btnResetFilters");
  const resetAllBtn = doc.getElementById("btnResetAllFilters");
  const searchInput = doc.getElementById("navSearchInput");

  // Secondary Filter Selects
  const genreSelect = doc.getElementById("filterGenre");
  const ageSelect = doc.getElementById("filterAge");
  const dateSelect = doc.getElementById("filterDate");
  const sortSelect = doc.getElementById("filterSort");

  // Dynamically populate genre options from cards if not already populated
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

  // Store original index on each card for default sort restoration
  cards.forEach((card, idx) => {
    card.dataset.origOrder = idx;
  });

  const PAGE_SIZE = 12;
  let currentPage = 1;
  let currentTab = "all";
  let currentGenre = "all";
  let currentAge = "all";
  let currentDate = "all";
  let currentSort = "default";
  let searchQuery = "";

  function scrollToCatalogHeader() {
    const header = doc.getElementById("moviesSection");
    if (header) {
      const rect = header.getBoundingClientRect();
      if (rect.top < -50 || rect.top > 300) {
        header.scrollIntoView({ behavior: "smooth", block: "start" });
      }
    }
  }

  function getFilteredAndSortedCards() {
    const q = searchQuery.trim().toLowerCase();
    const todayMs = new Date().setHours(0, 0, 0, 0);

    const filtered = cards.filter((card) => {
      // 1. Status Tab Filter (All / Now Showing / Coming Soon)
      const matchesTab = currentTab === "all" || card.dataset.status === currentTab;
      if (!matchesTab) return false;

      // 2. Genre Filter
      if (currentGenre !== "all") {
        const cardGenres = (card.dataset.genres || "").toLowerCase();
        const cardMeta = (card.querySelector(".catalog-movie-meta")?.textContent || "").toLowerCase();
        const targetGenre = currentGenre.toLowerCase();
        if (!cardGenres.includes(targetGenre) && !cardMeta.includes(targetGenre)) {
          return false;
        }
      }

      // 3. Age Rating Filter
      if (currentAge !== "all") {
        const cardAge = card.dataset.age || "";
        if (cardAge !== currentAge) return false;
      }

      // 4. Screening / Release Date Filter
      if (currentDate !== "all") {
        const releaseStr = card.dataset.release;
        const status = card.dataset.status;

        if (currentDate === "today") {
          // Movies currently playing in theaters
          if (status !== "now-showing") return false;
        } else if (currentDate === "upcoming-7") {
          if (status === "now-showing") return true;
          if (!releaseStr) return false;
          const relMs = new Date(releaseStr).getTime();
          const diffDays = (relMs - todayMs) / (1000 * 60 * 60 * 24);
          if (diffDays < 0 || diffDays > 7) return false;
        } else if (currentDate === "upcoming-30") {
          if (status === "now-showing") return true;
          if (!releaseStr) return false;
          const relMs = new Date(releaseStr).getTime();
          const diffDays = (relMs - todayMs) / (1000 * 60 * 60 * 24);
          if (diffDays < 0 || diffDays > 30) return false;
        }
      }

      // 5. Search Query Filter
      if (q) {
        const title = (card.dataset.title || card.querySelector(".catalog-movie-title")?.textContent || "").toLowerCase();
        const meta = (card.querySelector(".catalog-movie-meta")?.textContent || "").toLowerCase();
        const genres = (card.dataset.genres || "").toLowerCase();
        if (!title.includes(q) && !meta.includes(q) && !genres.includes(q)) {
          return false;
        }
      }

      return true;
    });

    // Sort matching cards
    filtered.sort((a, b) => {
      if (currentSort === "newest") {
        const dateA = new Date(a.dataset.release || "1970-01-01").getTime();
        const dateB = new Date(b.dataset.release || "1970-01-01").getTime();
        return dateB - dateA;
      } else if (currentSort === "duration-desc") {
        const durA = parseInt(a.dataset.duration, 10) || 0;
        const durB = parseInt(b.dataset.duration, 10) || 0;
        return durB - durA;
      } else if (currentSort === "duration-asc") {
        const durA = parseInt(a.dataset.duration, 10) || 0;
        const durB = parseInt(b.dataset.duration, 10) || 0;
        return durA - durB;
      } else if (currentSort === "title-asc") {
        const titleA = (a.dataset.title || a.querySelector(".catalog-movie-title")?.textContent || "").toLowerCase();
        const titleB = (b.dataset.title || b.querySelector(".catalog-movie-title")?.textContent || "").toLowerCase();
        return titleA.localeCompare(titleB);
      } else {
        // Default: Original insertion order
        const orderA = parseInt(a.dataset.origOrder, 10) || 0;
        const orderB = parseInt(b.dataset.origOrder, 10) || 0;
        return orderA - orderB;
      }
    });

    return filtered;
  }

  function render() {
    const matchedCards = getFilteredAndSortedCards();
    const totalMatches = matchedCards.length;
    const totalPages = Math.ceil(totalMatches / PAGE_SIZE) || 1;

    // Clamp current page
    if (currentPage > totalPages) currentPage = totalPages;
    if (currentPage < 1) currentPage = 1;

    const startIdx = (currentPage - 1) * PAGE_SIZE;
    const endIdx = startIdx + PAGE_SIZE;

    // Re-order matched cards in DOM to reflect sort order
    matchedCards.forEach((c) => catalogGrid.appendChild(c));

    // Toggle card visibility based on active page slice
    const visiblePageSet = new Set(matchedCards.slice(startIdx, endIdx));
    cards.forEach((card) => {
      if (visiblePageSet.has(card)) {
        card.style.display = "flex";
      } else {
        card.style.display = "none";
      }
    });

    // Toggle Empty State vs Grid
    if (totalMatches === 0) {
      if (emptyState) emptyState.classList.remove("d-none");
      if (paginationWrap) paginationWrap.style.display = "none";
    } else {
      if (emptyState) emptyState.classList.add("d-none");

      // Update Pagination Controls
      if (paginationWrap) {
        if (totalPages <= 1) {
          paginationWrap.style.display = "none";
        } else {
          paginationWrap.style.display = "flex";

          if (prevBtn) {
            prevBtn.disabled = currentPage <= 1;
            prevBtn.classList.toggle("disabled", currentPage <= 1);
          }
          if (nextBtn) {
            nextBtn.disabled = currentPage >= totalPages;
            nextBtn.classList.toggle("disabled", currentPage >= totalPages);
          }

          if (paginationNumbers) {
            paginationNumbers.innerHTML = "";
            for (let p = 1; p <= totalPages; p++) {
              const btn = doc.createElement("button");
              btn.type = "button";
              btn.className = `pagination-btn ${p === currentPage ? "active" : ""}`;
              btn.textContent = p;
              btn.setAttribute("aria-label", `Page ${p}`);
              if (p === currentPage) {
                btn.setAttribute("aria-current", "page");
              }
              btn.addEventListener("click", () => {
                if (currentPage !== p) {
                  currentPage = p;
                  render();
                  scrollToCatalogHeader();
                }
              });
              paginationNumbers.appendChild(btn);
            }
          }
        }
      }
    }
  }

  // Prev / Next button listeners
  if (prevBtn) {
    prevBtn.addEventListener("click", () => {
      if (currentPage > 1) {
        currentPage--;
        render();
        scrollToCatalogHeader();
      }
    });
  }

  if (nextBtn) {
    nextBtn.addEventListener("click", () => {
      const matchedCards = getFilteredAndSortedCards();
      const totalPages = Math.ceil(matchedCards.length / PAGE_SIZE) || 1;
      if (currentPage < totalPages) {
        currentPage++;
        render();
        scrollToCatalogHeader();
      }
    });
  }

  // Tabs listener (All Movies, Now Showing, Coming Soon)
  tabs.forEach((tab) => {
    tab.addEventListener("click", () => {
      tabs.forEach((t) => t.classList.remove("active"));
      tab.classList.add("active");
      currentTab = tab.dataset.catalogTab || "all";
      currentPage = 1;
      render();
    });
  });

  // Secondary Filter Select change listeners
  if (genreSelect) {
    genreSelect.addEventListener("change", () => {
      currentGenre = genreSelect.value;
      currentPage = 1;
      render();
    });
  }

  if (ageSelect) {
    ageSelect.addEventListener("change", () => {
      currentAge = ageSelect.value;
      currentPage = 1;
      render();
    });
  }

  if (dateSelect) {
    dateSelect.addEventListener("change", () => {
      currentDate = dateSelect.value;
      currentPage = 1;
      render();
    });
  }

  if (sortSelect) {
    sortSelect.addEventListener("change", () => {
      currentSort = sortSelect.value;
      currentPage = 1;
      render();
    });
  }

  // Unified Reset function
  function resetAllFilters(scrollTo = true) {
    currentTab = "all";
    currentGenre = "all";
    currentAge = "all";
    currentDate = "all";
    currentSort = "default";
    searchQuery = "";
    currentPage = 1;

    tabs.forEach((t) => {
      t.classList.toggle("active", t.dataset.catalogTab === "all");
    });

    if (genreSelect) genreSelect.value = "all";
    if (ageSelect) ageSelect.value = "all";
    if (dateSelect) dateSelect.value = "all";
    if (sortSelect) sortSelect.value = "default";
    if (searchInput) searchInput.value = "";

    render();
    if (scrollTo) scrollToCatalogHeader();
  }

  if (resetBtn) {
    resetBtn.addEventListener("click", () => resetAllFilters(true));
  }
  if (resetAllBtn) {
    resetAllBtn.addEventListener("click", () => resetAllFilters(false));
  }

  // Expose search trigger so navbar search updates catalog seamlessly
  if (typeof window !== "undefined") {
    window.__boxCinemaCatalog = {
      setSearchQuery: (query, scrollTo = true) => {
        searchQuery = query;
        currentPage = 1;
        render();
        if (scrollTo) {
          const section = doc.getElementById("moviesSection");
          if (section) section.scrollIntoView({ behavior: "smooth" });
        }
      },
      resetAllFilters: resetAllFilters
    };
  }

  // Initial render
  render();
}

/**
 * YouTube Trailer Modal
 */
function initTrailerModal(doc) {
  const modal = doc.getElementById("trailerModal");
  const iframe = doc.getElementById("trailerIframe");
  const closeBtn = doc.getElementById("trailerModalClose");

  if (!modal || !iframe) return;

  function toEmbedUrl(url) {
    if (!url) return "";
    let videoId = "";
    if (url.includes("v=")) {
      videoId = url.split("v=")[1].split("&")[0];
    } else if (url.includes("youtu.be/")) {
      videoId = url.split("youtu.be/")[1].split("?")[0];
    }
    return videoId ? `https://www.youtube.com/embed/${videoId}?autoplay=1` : url;
  }

  function openTrailer(url) {
    iframe.src = toEmbedUrl(url);
    modal.classList.add("show");
  }

  function closeTrailer() {
    modal.classList.remove("show");
    iframe.src = "";
  }

  doc.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-trailer-url]");
    if (btn) {
      e.preventDefault();
      openTrailer(btn.dataset.trailerUrl);
    }
  });

  if (closeBtn) closeBtn.addEventListener("click", closeTrailer);
  modal.addEventListener("click", (e) => {
    if (e.target === modal) closeTrailer();
  });
  doc.addEventListener("keydown", (e) => {
    if (e.key === "Escape" && modal.classList.contains("show")) {
      closeTrailer();
    }
  });
}

/**
 * Quick Booking & Showtime Finder
 */
function initQuickBooking(doc) {
  const quickBookForm = doc.getElementById("quickBookingForm");
  if (!quickBookForm) return;

  const quickBookFeedback = doc.getElementById("quickBookingFeedback");
  const movieSelect = doc.getElementById("qbMovie");
  const cinemaSelect = doc.getElementById("qbCinema");
  const dateSelect = doc.getElementById("qbDate");
  const timeSelect = doc.getElementById("qbTime");

  async function loadCinemasForMovie() {
    if (!cinemaSelect) return;
    const movieId = movieSelect ? movieSelect.value : "";
    try {
      const url = movieId ? `/api/quick-booking/cinemas?movieId=${movieId}` : `/api/quick-booking/cinemas`;
      const res = await fetch(url);
      if (res.ok) {
        const cinemas = await res.json();
        cinemaSelect.innerHTML = '<option value="">-- Chọn cụm rạp --</option>' +
          cinemas.map(c => `<option value="${c.cinemaId}">${c.displayName}</option>`).join("");
      }
    } catch {
      // ignore
    }
  }

  async function loadDates() {
    if (!dateSelect) return;
    const movieId = movieSelect ? movieSelect.value : "";
    const cinemaId = cinemaSelect ? cinemaSelect.value : "";
    if (!movieId || !cinemaId) {
      dateSelect.innerHTML = '<option value="">-- Chọn ngày --</option>';
      if (timeSelect) timeSelect.innerHTML = '<option value="">-- Chọn giờ chiếu --</option>';
      return;
    }

    try {
      const res = await fetch(`/api/quick-booking/dates?movieId=${movieId}&cinemaId=${cinemaId}`);
      if (res.ok) {
        const dates = await res.json();
        dateSelect.innerHTML = '<option value="">-- Chọn ngày --</option>' +
          dates.map(d => `<option value="${d.value}">${d.label}</option>`).join("");
        if (dates.length > 0) {
          dateSelect.value = dates[0].value;
          await loadShowtimes();
        }
      }
    } catch {
      // ignore
    }
  }

  async function loadShowtimes() {
    if (!timeSelect) return;
    const movieId = movieSelect ? movieSelect.value : "";
    const cinemaId = cinemaSelect ? cinemaSelect.value : "";
    const date = dateSelect ? dateSelect.value : "";
    if (!movieId || !cinemaId || !date) {
      timeSelect.innerHTML = '<option value="">-- Chọn giờ chiếu --</option>';
      return;
    }

    try {
      const res = await fetch(`/api/quick-booking/showtimes?movieId=${movieId}&cinemaId=${cinemaId}&date=${date}`);
      if (res.ok) {
        const times = await res.json();
        if (times.length > 0) {
          timeSelect.innerHTML = '<option value="">-- Chọn giờ chiếu --</option>' +
            times.map(t => `<option value="${t.showtimeId}">${t.label}</option>`).join("");
        } else {
          timeSelect.innerHTML = '<option value="">Không có suất chiếu</option>';
        }
      }
    } catch {
      // ignore
    }
  }

  if (movieSelect) {
    movieSelect.addEventListener("change", async () => {
      await loadCinemasForMovie();
      await loadDates();
    });
  }

  if (cinemaSelect) {
    cinemaSelect.addEventListener("change", () => {
      loadDates();
    });
  }

  if (dateSelect) {
    dateSelect.addEventListener("change", () => {
      loadShowtimes();
    });
  }

  // Pre-select movie when clicking any [data-book-id]
  doc.addEventListener("click", (e) => {
    const btn = e.target.closest("[data-book-id]");
    if (btn && movieSelect) {
      const movieId = btn.dataset.bookId;
      if (movieId) {
        movieSelect.value = movieId;
        loadCinemasForMovie().then(() => {
          loadDates();
        });
        if (cinemaSelect) cinemaSelect.focus({ preventScroll: true });
      }
    }
  });

  if (quickBookForm && quickBookFeedback) {
    quickBookForm.addEventListener("change", () => { quickBookFeedback.textContent = ""; });
    quickBookForm.addEventListener("submit", (event) => {
      event.preventDefault();

      const movieOpt = movieSelect ? movieSelect.options[movieSelect.selectedIndex] : null;
      const cinemaOpt = cinemaSelect ? cinemaSelect.options[cinemaSelect.selectedIndex] : null;
      const dateOpt = dateSelect ? dateSelect.options[dateSelect.selectedIndex] : null;
      const timeOpt = timeSelect ? timeSelect.options[timeSelect.selectedIndex] : null;

      const movieVal = movieSelect ? movieSelect.value : "";
      const cinemaVal = cinemaSelect ? cinemaSelect.value : "";
      const dateVal = dateSelect ? dateSelect.value : "";
      const timeVal = timeSelect ? timeSelect.value : "";

      if (!movieVal || !cinemaVal || !dateVal || !timeVal) {
        quickBookFeedback.className = "mt-3 small text-warning fw-semibold";
        quickBookFeedback.textContent = "Vui lòng chọn đầy đủ Phim, Rạp, Ngày và Giờ chiếu để tiếp tục sang bước Chọn ghế.";
        return;
      }

      quickBookFeedback.className = "mt-3 small text-success fw-bold";
      const movieText = movieOpt ? movieOpt.text : movieVal;
      const cinemaText = cinemaOpt ? cinemaOpt.text : cinemaVal;
      const dateText = dateOpt ? dateOpt.text : dateVal;
      const timeText = timeOpt ? timeOpt.text : timeVal;
      quickBookFeedback.textContent = `✔ Đã chọn: ${movieText} · ${cinemaText} · ${dateText} · ${timeText}. Sẵn sàng chuyển sang sơ đồ chọn ghế!`;
    });
  }
}

export function initHomeInteractions(doc = document) {
  initHeroRail(doc);
  initCatalogManager(doc);
  initTrailerModal(doc);
  initQuickBooking(doc);
}

if (typeof document !== "undefined") {
  document.addEventListener("DOMContentLoaded", () => {
    initNavbar(document, window.location.pathname);
    initHomeInteractions(document);
  });
}
