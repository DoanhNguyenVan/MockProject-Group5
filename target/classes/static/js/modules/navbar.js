/**
 * BOX Cinema - Module 01 Navbar & Core Page Interactions
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

  if (toggler && collapseEl) {
    const closeMenu = () => {
      collapseEl.classList.remove("show");
      toggler.setAttribute("aria-expanded", "false");
    };
    toggler.addEventListener("click", () => {
      toggler.setAttribute("aria-expanded", String(collapseEl.classList.toggle("show")));
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

  return activeKey;
}

function initSpotlight(doc) {
  const spotlight = doc.querySelector(".spotlight");
  if (!spotlight) return;

  const buttons = [...spotlight.querySelectorAll("[data-show-feature]")];
  const slides = spotlight.querySelectorAll("[data-feature]");
  const motion = doc.defaultView.matchMedia("(prefers-reduced-motion: reduce)");
  let index = Math.max(0, buttons.findIndex((button) => button.getAttribute("aria-pressed") === "true"));
  let timer;

  function schedule() {
    clearTimeout(timer);
    if (!motion.matches && !doc.hidden && !spotlight.querySelector(".feature-actions:focus-within, .feature-actions:hover")) {
      timer = setTimeout(() => selectFilm((index + 1) % buttons.length), 6000);
    }
  }

  function selectFilm(next) {
    index = next;
    slides.forEach((slide) => {
      slide.hidden = slide.dataset.feature !== buttons[index].dataset.showFeature;
    });
    buttons.forEach((button, i) => button.setAttribute("aria-pressed", String(i === index)));
    schedule();
  }

  buttons.forEach((button, i) => button.addEventListener("click", () => selectFilm(i)));
  motion.addEventListener("change", schedule);
  spotlight.querySelectorAll(".feature-actions").forEach((actions) => {
    actions.addEventListener("pointerenter", () => clearTimeout(timer));
    actions.addEventListener("pointerleave", schedule);
    actions.addEventListener("focusin", () => clearTimeout(timer));
    actions.addEventListener("focusout", (event) => {
      if (!actions.contains(event.relatedTarget)) {
        // Focus moves after focusout. Recheck once the next control has focus.
        doc.defaultView.queueMicrotask(schedule);
      }
    });
  });
  doc.addEventListener("visibilitychange", schedule);
  schedule();
}

export function initHomeInteractions(doc = document) {
  initSpotlight(doc);

  // Now Showing / Coming Soon tab filter
  const tabButtons = doc.querySelectorAll("[data-movie-tab]");
  const movieItems = doc.querySelectorAll("[data-movie-status]");

  tabButtons.forEach((btn) => {
    btn.addEventListener("click", () => {
      const targetStatus = btn.getAttribute("data-movie-tab");
      tabButtons.forEach((button) => button.setAttribute("aria-pressed", String(button === btn)));
      let count = 0;
      movieItems.forEach((item) => {
        item.hidden = targetStatus !== "all" && item.dataset.movieStatus !== targetStatus;
        if (!item.hidden) count += 1;
      });
      doc.getElementById("filmFilterStatus").textContent = `${count} films shown.`;
    });
  });

  // Quick Booking form preview handler
  const quickBookForm = doc.getElementById("quickBookingForm");
  const quickBookFeedback = doc.getElementById("quickBookingFeedback");

  if (quickBookForm && quickBookFeedback) {
    doc.querySelectorAll("[data-book-film]").forEach((link) => {
      link.addEventListener("click", () => {
        doc.getElementById("qbMovie").value = link.dataset.bookFilm;
        quickBookFeedback.textContent = "";
        doc.getElementById("qbCinema").focus({ preventScroll: true });
      });
    });
    quickBookForm.addEventListener("change", () => { quickBookFeedback.textContent = ""; });
    quickBookForm.addEventListener("submit", (event) => {
      event.preventDefault();
      const movieSelect = doc.getElementById("qbMovie");
      const cinemaSelect = doc.getElementById("qbCinema");
      const dateSelect = doc.getElementById("qbDate");
      const timeSelect = doc.getElementById("qbTime");

      const movie = movieSelect ? movieSelect.value : "";
      const cinema = cinemaSelect ? cinemaSelect.value : "";
      const date = dateSelect ? dateSelect.value : "";
      const time = timeSelect ? timeSelect.value : "";

      if (!movie || !cinema || !date || !time) {
        quickBookFeedback.className = "mt-3 small text-warning fw-semibold";
        quickBookFeedback.textContent = "Please select Movie, Theater, Date, and Showtime to proceed to Step 2 (Seat Map).";
        return;
      }

      quickBookFeedback.textContent = `${movie} · ${cinema} · ${date} · ${time}. Demo screening only. Seats cannot be reserved yet.`;
    });
  }
}

if (typeof document !== "undefined") {
  document.addEventListener("DOMContentLoaded", () => {
    initNavbar(document, window.location.pathname);
    initHomeInteractions(document);
  });
}
