import { httpRequest } from "../core/http-client.js";
import { buildBookingPreview } from "./booking-preview.js?v=1.1";

function getClientHoldToken() {
  try {
    if (typeof document !== "undefined" && typeof window !== "undefined") {
      const appEl = document.getElementById("bookingApp");
      const rawUserId = appEl?.getAttribute("data-user-id") ? String(appEl.getAttribute("data-user-id")).trim() : "";
      const isLoggedOutInDom = rawUserId === "GUEST" || Boolean(document.querySelector('a[data-nav-route="login"]'));

      if (!isLoggedOutInDom && rawUserId) {
        if (window.sessionStorage) {
          window.sessionStorage.setItem("box_last_auth_user_id", rawUserId);
          window.sessionStorage.removeItem("box_hold_session_token");
        }
        return `acct_hold_user_${rawUserId}`;
      }

      const navUserName = document.querySelector(".cinema-nav .nav-user-name, #navUserAccountDropdown .nav-user-name, .nav-user-btn .nav-user-name, .nav-user-pill .nav-user-name")?.textContent?.trim();
      if (!isLoggedOutInDom && navUserName) {
        const slug = navUserName.toLowerCase().replace(/[^a-z0-9]+/g, "_");
        return `acct_hold_name_${slug}`;
      }

      if (window.sessionStorage) {
        window.sessionStorage.removeItem("box_last_auth_user_id");
        window.sessionStorage.removeItem("box_hold_session_token");
      }
      return "guest_viewer_unauthenticated";
    }
  } catch (_) {}
  return "";
}

/**
 * Loads live screening and seat availability from the MySQL database.
 * Reflects the exact layout, seat types, hold states and pricing configured in database.
 */
export async function loadBookingData(showtimeId, { request = httpRequest, signal, onUnauthenticated = () => {} } = {}) {
  async function read(url) {
    const holdToken = getClientHoldToken();
    const headers = holdToken ? { "X-Hold-Session": holdToken } : undefined;
    const response = await request(url, headers ? { signal, headers } : { signal });
    if (response.status === 401 || (response.redirected && new URL(response.url).pathname === "/login")) {
      onUnauthenticated();
      const error = new Error("Please sign in to continue.");
      error.status = 401;
      throw error;
    }
    if (!response.ok) {
      const body = await response.json().catch(() => ({}));
      const error = new Error(body.message || "We could not load this screening. Please try again.");
      error.status = response.status;
      throw error;
    }
    return response.json();
  }

  const id = encodeURIComponent(showtimeId);
  try {
    return await read(`/api/booking/showtimes/${id}`);
  } catch (error) {
    if (error.status !== 404) throw error;
  }

  const context = await read(`/api/quick-booking/showtimes/${id}`);
  return buildBookingPreview(context);
}

/**
 * Holds selected seats on the backend with automatic expiration (default 600s = 10 minutes).
 */
export async function holdBookingSeats(
  showtimeId,
  seatIds,
  { request = httpRequest, signal, durationSeconds, resetTimer = false } = {}
) {
  const id = encodeURIComponent(showtimeId);
  const holdSessionId = getClientHoldToken();
  const payload = {
    seatIds: Array.isArray(seatIds) ? seatIds : [],
    resetTimer: Boolean(resetTimer)
  };
  if (holdSessionId) payload.holdSessionId = holdSessionId;
  if (Number.isFinite(durationSeconds) && durationSeconds > 0) {
    payload.durationSeconds = durationSeconds;
  }

  const response = await request(`/api/booking/showtimes/${id}/hold`, {
    method: "POST",
    signal,
    headers: holdSessionId ? { "X-Hold-Session": holdSessionId } : {},
    body: JSON.stringify(payload)
  });

  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(body.message || "Unable to hold selected seats. Please try again.");
    error.status = response.status;
    error.errorCode = body.errorCode;
    error.conflictingSeats = body.conflictingSeats || [];
    throw error;
  }
  return body;
}

/**
 * Releases all currently held seats for this showtime.
 */
export async function releaseBookingSeats(showtimeId, { request = httpRequest, signal } = {}) {
  const id = encodeURIComponent(showtimeId);
  const holdSessionId = getClientHoldToken();
  const response = await request(`/api/booking/showtimes/${id}/hold`, {
    method: "DELETE",
    signal,
    headers: holdSessionId ? { "X-Hold-Session": holdSessionId } : {},
    body: JSON.stringify(holdSessionId ? { holdSessionId } : {})
  });
  return response.json().catch(() => ({ success: response.ok }));
}
